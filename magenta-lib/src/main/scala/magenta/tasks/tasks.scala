package magenta
package tasks

import java.io.InputStream
import java.nio.ByteBuffer

import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.internal.Mimetypes
import com.amazonaws.services.s3.model.CannedAccessControlList._
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest}
import com.amazonaws.util.IOUtils
import com.gu.management.Loggable
import magenta.artifact._
import magenta.deployment_type.{LambdaFunction, LambdaFunctionName, LambdaFunctionTags}
import magenta.deployment_type.param_reads.PatternValue
import okhttp3._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

case class S3Upload(
  region: Region,
  bucket: String,
  paths: Seq[(S3Location, String)],
  cacheControlPatterns: List[PatternValue] = Nil,
  extensionToMimeType: Map[String,String] = Map.empty,
  publicReadAcl: Boolean = false,
  detailedLoggingThreshold: Int = 10
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3,
  clientFactory: (KeyRing, Region, ClientConfiguration) => AmazonS3 = S3.makeS3client) extends Task with Loggable {

  lazy val objectMappings = paths flatMap {
    case (file, targetKey) => resolveMappings(file, targetKey, bucket)
  }

  lazy val totalSize = objectMappings.map{ case (source, target) => source.size}.sum

  lazy val requests = objectMappings.map { case (source, target) =>
    PutReq(source, target, cacheControlLookup(target.key), contentTypeLookup(target.key), publicReadAcl)
  }

  def fileString(quantity: Int) = s"$quantity file${if (quantity != 1) "s" else ""}"

  // A verbose description of this task. For command line tasks,
  def verbose: String = s"$description using file mapping $paths"

  // end-user friendly description of this task
  def description: String = s"Upload ${fileString(objectMappings.size)} to S3 bucket $bucket"

  def requestToString(source: S3Object, request: PutReq): String =
    s"s3://${source.bucket}/${source.key} to s3://${request.target.bucket}/${request.target.key} with "+
      s"CacheControl:${request.cacheControl} ContentType:${request.contentType} PublicRead:${request.publicReadAcl}"

  // execute this task (should throw on failure)
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    if (totalSize == 0) {
      val locationDescription = (paths.map {
        case (path: S3Path, _) => path.show()
        case (location, _) => location.toString()
      }).mkString("\n")
      reporter.fail(s"No files found to upload in $locationDescription")
    }

    val client = clientFactory(keyRing, region, S3.clientConfigurationNoRetry)

    reporter.verbose(s"Starting transfer of ${fileString(objectMappings.size)} ($totalSize bytes)")
    requests.zipWithIndex.par foreach { case (req, index) =>
      logger.debug(s"Transferring ${requestToString(req.source, req)}")
      index match {
        case x if x < 10 => reporter.verbose(s"Transferring ${requestToString(req.source, req)}")
        case 10 => reporter.verbose(s"Not logging details for the remaining ${fileString(objectMappings.size - 10)}")
        case _ =>
      }
      retryOnException(S3.clientConfiguration) {
        val inputStream = artifactClient.getObject(req.source.bucket, req.source.key).getObjectContent
        val putRequest = req.toAwsRequest(inputStream)
        try {
          val result = client.putObject(putRequest)
          logger.debug(s"Put object ${putRequest.getKey}: MD5: ${result.getContentMd5} Metadata: ${result.getMetadata.getRawMetadata.asScala}")
          result
        } finally {
          inputStream.close()
        }
      }
    }
    reporter.verbose(s"Finished transfer of ${fileString(objectMappings.size)}")
  }

  private def subDirectoryPrefix(key: String, fileName: String): String =
    if (fileName.isEmpty)
      key
    else if (key.isEmpty)
      fileName
    else s"$key/$fileName"

  private def resolveMappings(path: S3Location, targetKey: String, targetBucket: String): Seq[(S3Object, S3Path)] = {
    path.listAll()(artifactClient).map { obj =>
      obj -> S3Path(targetBucket, subDirectoryPrefix(targetKey, obj.relativeTo(path)))
    }
  }

  private def contentTypeLookup(fileName: String) = fileExtension(fileName).flatMap(extensionToMimeType.get)
  private def cacheControlLookup(fileName:String) = cacheControlPatterns.find(_.regex.findFirstMatchIn(fileName).isDefined).map(_.value)
  private def fileExtension(fileName: String) = fileName.split('.').drop(1).lastOption
}

object S3Upload {
  private val mimeTypes = Mimetypes.getInstance()

  def awsMimeTypeLookup(fileName: String): String = mimeTypes.getMimetype(fileName)

  def prefixGenerator(stack:Option[Stack] = None, stage:Option[Stage] = None, packageName:Option[String] = None): String = {
    (stack.map(_.name) :: stage.map(_.name) :: packageName :: Nil).flatten.mkString("/")
  }
  def prefixGenerator(stack: Stack, stage: Stage, packageName: String): String =
    prefixGenerator(Some(stack), Some(stage), Some(packageName))
}

case class PutReq(source: S3Object, target: S3Path, cacheControl: Option[String], contentType: Option[String], publicReadAcl: Boolean) {
  def toAwsRequest(inputStream: InputStream): PutObjectRequest = {
    val metaData = new ObjectMetadata
    cacheControl foreach metaData.setCacheControl
    metaData.setContentType(contentType.getOrElse(S3Upload.awsMimeTypeLookup(target.key)))
    metaData.setContentLength(source.size)
    val req = new PutObjectRequest(target.bucket, target.key, inputStream, metaData)
    if (publicReadAcl) req.withCannedAcl(PublicRead) else req
  }
}

trait PollingCheck {
  def duration: Long

  def check(reporter: DeployReporter, stopFlag: => Boolean)(theCheck: => Boolean) {
    val expiry = System.currentTimeMillis() + duration

    def checkAttempt(currentAttempt: Int) {
      if (!theCheck) {
        if (stopFlag) {
          reporter.info("Abandoning remaining checks as stop flag has been set")
        } else {
          val remainingTime = expiry - System.currentTimeMillis()
          if (remainingTime > 0) {
            val sleepyTime = calculateSleepTime(currentAttempt)
            reporter.verbose("Check failed on attempt #%d (Will wait for a further %.1f seconds, retrying again after %.1fs)" format (currentAttempt, (remainingTime.toFloat/1000), (sleepyTime.toFloat/1000)))
            Thread.sleep(sleepyTime)
            checkAttempt(currentAttempt + 1)
          } else {
            reporter.fail("Check failed to pass within %d milliseconds (tried %d times) - aborting" format (duration,currentAttempt))
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
    math.min(math.pow(2,exponent).toLong*100, 25000)
  }
}

trait SlowRepeatedPollingCheck extends PollingCheck {

  def calculateSleepTime(currentAttempt: Int): Long = 30000
}


case class SayHello(host: Host)(implicit val keyRing: KeyRing) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    reporter.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class ChangeSwitch(host: Host, protocol:String, port: Int, path: String, switchName: String, desiredState: Boolean)(implicit val keyRing: KeyRing) extends Task {
  val desiredStateName = if (desiredState) "ON" else "OFF"
  val switchboardUrl = s"$protocol://${host.name}:$port$path"

  // execute this task (should throw on failure)
  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = {
    reporter.verbose(s"Changing $switchName to $desiredStateName using $switchboardUrl")

    val request = new Request.Builder()
      .url(
        HttpUrl.parse(switchboardUrl).newBuilder()
          .addQueryParameter(switchName, desiredStateName).build()
      )
      .post(new FormBody.Builder().build())
      .build()

    try {
      reporter.verbose(s"Changing switch with request: $request")
      val result = ChangeSwitch.client.newCall(request).execute()
      if (result.code() != 200) {
        reporter.fail(
          s"Couldn't set $switchName to $desiredState, status was ${result.code}:\n${result.body().string()}")
      }
      result.body().close()
    } catch {
      case NonFatal(t) => {
        reporter.fail(s"Couldn't set $switchName to $desiredState", t)
      }
    }
  }

  def verbose: String = s"$description using switchboard at $switchboardUrl"
  def description: String = s"$switchName to $desiredStateName"
}

object ChangeSwitch {
  val client = new OkHttpClient()
}

case class UpdateS3Lambda(function: LambdaFunction, s3Bucket: String, s3Key: String, region: Region)(implicit val keyRing: KeyRing) extends Task {
  def description = s"Updating $function Lambda using S3 $s3Bucket:$s3Key"
  def verbose = description

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    val client = Lambda.makeLambdaClient(keyRing, region)

    val functionName: String = function match {
      case LambdaFunctionName(name) => name
      case LambdaFunctionTags(tags) =>
        val functionConfig = Lambda.findFunctionByTags(tags, reporter, client)
        functionConfig.map(_.getFunctionName).getOrElse{
          reporter.fail(s"Failed to find any function with tags $tags")
        }
    }

    reporter.verbose(s"Starting update $function Lambda")
    client.updateFunctionCode(Lambda.lambdaUpdateFunctionCodeRequest(functionName, s3Bucket, s3Key))
    reporter.verbose(s"Finished update $function Lambda")
  }

}
