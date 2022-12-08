package magenta
package tasks

import java.io.{File, InputStream, PipedInputStream, PipedOutputStream}
import magenta.artifact._
import magenta.deployment_type.{
  LambdaFunction,
  LambdaFunctionName,
  LambdaFunctionTags
}
import magenta.deployment_type.param_reads.PatternValue
import okhttp3.{FormBody, HttpUrl, OkHttpClient, Request}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.internal.util.Mimetype
import software.amazon.awssdk.core.sync.{
  ResponseTransformer,
  RequestBody => AWSRequestBody
}
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  ObjectCannedACL,
  PutObjectRequest
}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal
import scala.collection.parallel.CollectionConverters._

case class S3Upload(
    region: Region,
    bucket: String,
    paths: Seq[(S3Location, String)],
    cacheControlPatterns: List[PatternValue] = Nil,
    surrogateControlPatterns: List[PatternValue] = Nil,
    extensionToMimeType: Map[String, String] = Map.empty,
    publicReadAcl: Boolean = false,
    detailedLoggingThreshold: Int = 10
)(implicit
    val keyRing: KeyRing,
    artifactClient: S3Client,
    withClientFactory: (
        KeyRing,
        Region,
        ClientOverrideConfiguration,
        DeploymentResources
    ) => (S3Client => Unit) => Unit = S3.withS3client[Unit]
) extends Task
    with Loggable {

  lazy val objectMappings: Seq[(S3Object, S3Path)] = paths flatMap {
    case (file, targetKey) => resolveMappings(file, targetKey, bucket)
  }

  lazy val totalSize: Long = objectMappings.map { case (source, _) =>
    source.size
  }.sum

  lazy val requests: Seq[PutReq] = objectMappings.map { case (source, target) =>
    PutReq(
      source,
      target,
      cacheControlLookup(target.key),
      surrogateControlLookup(target.key),
      contentTypeLookup(target.key),
      publicReadAcl
    )
  }

  def fileString(quantity: Int) =
    s"$quantity file${if (quantity != 1) "s" else ""}"

  // end-user friendly description of this task
  def description: String =
    s"Upload ${fileString(objectMappings.size)} to S3 bucket $bucket using file mapping $paths"

  def requestToString(source: S3Object, request: PutReq): String =
    s"s3://${source.bucket}/${source.key} to s3://${request.target.bucket}/${request.target.key} with " +
      s"CacheControl:${request.cacheControl} ContentType:${request.contentType} PublicRead:${request.publicReadAcl}"

  // execute this task (should throw on failure)
  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    if (totalSize == 0) {
      val locationDescription = paths
        .map {
          case (path: S3Path, _) => path.show()
          case (location, _)     => location.toString
        }
        .mkString("\n")
      resources.reporter.fail(
        s"No files found to upload in $locationDescription"
      )
    }

    val withClient = withClientFactory(
      keyRing,
      region,
      AWS.clientConfigurationNoRetry,
      resources
    )
    withClient { client =>
      resources.reporter.verbose(
        s"Starting transfer of ${fileString(objectMappings.size)} ($totalSize bytes)"
      )
      requests.zipWithIndex.par.foreach { case (req, index) =>
        logger.debug(s"Transferring ${requestToString(req.source, req)}")
        index match {
          case x if x < 10 =>
            resources.reporter.verbose(
              s"Transferring ${requestToString(req.source, req)}"
            )
          case 10 =>
            resources.reporter.verbose(
              s"Not logging details for the remaining ${fileString(objectMappings.size - 10)}"
            )
          case _ =>
        }
        retryOnException(AWS.clientConfiguration) {
          val getObjectRequest = GetObjectRequest
            .builder()
            .bucket(req.source.bucket)
            .key(req.source.key)
            .build()

          val os = new PipedOutputStream()
          val is = new PipedInputStream(os, 1024 * 1024)

          val transformer
              : ResponseTransformer[GetObjectResponse, GetObjectResponse] =
            ResponseTransformer.toOutputStream(os)
          val response: Future[GetObjectResponse] = Future {
            try {
              resources.artifactClient.getObject(getObjectRequest, transformer)
            } finally {
              os.close()
            }
          }(resources.ioExecutionContext)

          val putRequest: PutObjectRequest = req.toAwsRequest
          val result =
            try {
              client.putObject(
                putRequest,
                AWSRequestBody.fromContentProvider(
                  () => is,
                  req.source.size,
                  req.mimeType
                )
              )
            } finally {
              is.close()
            }

          Await.result(response, 5 minutes)

          logger.debug(
            s"Put object ${putRequest.key}: MD5: ${result.sseCustomerKeyMD5} Metadata: ${result.responseMetadata}"
          )
        }
      }
    }
    resources.reporter.verbose(
      s"Finished transfer of ${fileString(objectMappings.size)}"
    )
  }

  private def subDirectoryPrefix(key: String, fileName: String): String =
    if (fileName.isEmpty)
      key
    else if (key.isEmpty)
      fileName
    else s"$key/$fileName"

  private def resolveMappings(
      path: S3Location,
      targetKey: String,
      targetBucket: String
  ): Seq[(S3Object, S3Path)] = {
    path.listAll()(artifactClient).map { obj =>
      obj -> S3Path(
        targetBucket,
        subDirectoryPrefix(targetKey, obj.relativeTo(path))
      )
    }
  }

  private def contentTypeLookup(fileName: String) =
    fileExtension(fileName).flatMap(extensionToMimeType.get)
  private def cacheControlLookup(fileName: String) = cacheControlPatterns
    .find(_.regex.findFirstMatchIn(fileName).isDefined)
    .map(_.value)
  private def surrogateControlLookup(fileName: String) =
    surrogateControlPatterns
      .find(_.regex.findFirstMatchIn(fileName).isDefined)
      .map(_.value)
  private def fileExtension(fileName: String) =
    fileName.split('.').drop(1).lastOption
}

object S3Upload {
  private val mimeTypes = Mimetype.getInstance()

  def awsMimeTypeLookup(fileName: String): String =
    mimeTypes.getMimetype(new File(fileName))

  def prefixGenerator(
      stack: Option[Stack] = None,
      stage: Option[Stage] = None,
      packageOrAppName: Option[String] = None
  ): String = {
    (stack.map(_.name) :: stage.map(_.name) :: packageOrAppName :: Nil).flatten
      .mkString("/")
  }
  def prefixGenerator(stack: Stack, stage: Stage, packageName: String): String =
    prefixGenerator(Some(stack), Some(stage), Some(packageName))
}

case class PutReq(
    source: S3Object,
    target: S3Path,
    cacheControl: Option[String],
    surrogateControl: Option[String],
    contentType: Option[String],
    publicReadAcl: Boolean
) {
  import collection.JavaConverters._

  type ReqModifier = PutObjectRequest.Builder => PutObjectRequest.Builder
  private val setCacheControl: ReqModifier = r =>
    cacheControl.map(cc => r.cacheControl(cc)).getOrElse(r)
  private val setsurrogateControl: ReqModifier = r =>
    surrogateControl
      .map(scc =>
        r.metadata(Map[String, String]("surrogate-control" -> scc).asJava)
      )
      .getOrElse(r)

  val mimeType: String =
    contentType.getOrElse(S3Upload.awsMimeTypeLookup(target.key))

  def toAwsRequest: PutObjectRequest = {
    val req = PutObjectRequest
      .builder()
      .bucket(target.bucket)
      .key(target.key)
      .contentType(mimeType)
      .contentLength(source.size)
    val reqWithCacheControl = (setCacheControl andThen setsurrogateControl)(req)
    if (publicReadAcl)
      reqWithCacheControl.acl(ObjectCannedACL.PUBLIC_READ).build()
    else reqWithCacheControl.build()
  }
}

trait PollingCheck {
  def duration: Long

  def check(reporter: DeployReporter, stopFlag: => Boolean)(
      theCheck: => Boolean
  ): Unit = {
    val expiry = System.currentTimeMillis() + duration

    def checkAttempt(currentAttempt: Int): Unit = {
      if (!theCheck) {
        if (stopFlag) {
          reporter.info("Abandoning remaining checks as stop flag has been set")
        } else {
          val remainingTime = expiry - System.currentTimeMillis()
          if (remainingTime > 0) {
            val sleepyTime = calculateSleepTime(currentAttempt)
            reporter.verbose(
              f"Check failed on attempt #$currentAttempt (Will wait for a further ${remainingTime.toFloat / 1000} seconds, retrying again after ${sleepyTime.toFloat / 1000}s)"
            )
            Thread.sleep(sleepyTime)
            checkAttempt(currentAttempt + 1)
          } else {
            reporter.fail(
              s"Check failed to pass within $duration milliseconds (tried $currentAttempt times) - aborting"
            )
          }
        }
      }
    }
    checkAttempt(1)
  }

  def calculateSleepTime(currentAttempt: Int): Long
}

trait RepeatedPollingCheck extends PollingCheck {

  def calculateSleepTime(currentAttempt: Int): Long = {
    val exponent = math.min(currentAttempt, 8)
    math.min(math.pow(2, exponent).toLong * 100, 25000)
  }
}

trait SlowRepeatedPollingCheck extends PollingCheck {

  def calculateSleepTime(currentAttempt: Int): Long = 30000
}

case class SayHello(host: Host)(implicit val keyRing: KeyRing) extends Task {
  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    resources.reporter.info("Hello to " + host.name + "!")
  }

  def description: String = "to " + host.name
}

case class ShutdownTask(host: Host)(implicit val keyRing: KeyRing)
    extends Task {

  val description = "Shutdown Riffraff target(s) as part of self-deploy."
  val shutdownUrl = s"http://${host.name}:9000/requestShutdown"

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    val parsedUrl = Option(HttpUrl.parse(shutdownUrl))

    val request = parsedUrl match {
      case Some(url) =>
        new Request.Builder()
          .url(url.newBuilder().build())
          .post(new FormBody.Builder().build())
          .build()
      case None =>
        resources.reporter.fail(
          s"Shutdown request as part of self-deploy failed. Invalid target URL: ${shutdownUrl}"
        )
    }

    try {
      resources.reporter.verbose(s"Invoking shutdown with request: $request")
      val result = ShutdownTask.client.newCall(request).execute()
      if (result.code() != 200) {
        resources.reporter.fail(
          s"Shutdown request as part of self-deploy failed. Status was ${result.code}:\n${result.body().string()}"
        )
      }
      result.body().close()
    } catch {
      case NonFatal(t) =>
        resources.reporter.fail(
          s"Shutdown request as part of self-deploy failed",
          t
        )
    }
  }
}

object ShutdownTask {
  val client = new OkHttpClient()
}

case class UpdateS3Lambda(
    function: LambdaFunction,
    s3Bucket: String,
    s3Key: String,
    region: Region
)(implicit val keyRing: KeyRing)
    extends Task {
  def description = s"Updating $function Lambda using S3 $s3Bucket:$s3Key"

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    Lambda.withLambdaClient(keyRing, region, resources) { client =>
      val functionName: String = function match {
        case LambdaFunctionName(name) => name
        case LambdaFunctionTags(tags) =>
          val functionConfig =
            Lambda.findFunctionByTags(tags, resources.reporter, client)
          functionConfig.map(_.functionName).getOrElse {
            resources.reporter
              .fail(s"Failed to find any function with tags $tags")
          }
      }

      resources.reporter.verbose(s"Starting update $function Lambda")
      client.updateFunctionCode(
        Lambda.lambdaUpdateFunctionCodeRequest(functionName, s3Bucket, s3Key)
      )
      resources.reporter.verbose(s"Finished update $function Lambda")
    }
  }

}

case class InvokeLambda(artifactsPath: S3Path)(implicit val keyRing: KeyRing) extends Task {
  def description = "Task that invokes a lambda."

  override def execute(resources: DeploymentResources, stopFlag: => Boolean){
    resources.reporter.info(s"Path is $artifactsPath")
    S3Location.listObjects(artifactsPath)(resources.artifactClient).foreach(s3object => resources.reporter.verbose(s3object.toString))
    // TODO: Read contents of each file`
    // TODO THEN: build JSON payload (Map of file name to array of bytes)
  }
}