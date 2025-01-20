package magenta.tasks

import java.util.concurrent.Executors
import com.gu.fastly.api.FastlyApiClient
import magenta._
import magenta.artifact.{S3Object, S3Path}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.io.Codec.ISO8859
import scala.util.{Failure, Success, Try}

case class FastlyComputeTasks(s3Package: S3Path)(implicit
    val keyRing: KeyRing,
    artifactClient: S3Client,
    parameters: DeployParameters
) extends Task
    with FastlyUtils {

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(10))

  private def executeWithCredentials(
      client: FastlyApiClient,
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Try[Int] = {
    Try {
      val activeVersionNumber =
        getActiveVersionNumber(client, resources.reporter, stopFlag)
      val nextVersionNumber =
        clone(activeVersionNumber, client, resources.reporter, stopFlag)

      uploadPackage(
        nextVersionNumber,
        s3Package,
        client,
        resources.reporter,
        stopFlag
      )

      commentVersion(
        nextVersionNumber,
        client,
        resources.reporter,
        parameters,
        stopFlag
      )

      activateVersion(
        nextVersionNumber,
        client,
        resources.reporter,
        stopFlag
      )

      nextVersionNumber
    }
  }

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    FastlyApiClientProvider
      .get(keyRing)
      .map(client =>
        (client, executeWithCredentials(client, resources, stopFlag))
      ) match {
      case None =>
        resources.reporter.fail(
          "Failed to fetch Fastly API credentials from keyring"
        )
      case Some((client, Success(nextVersionNumber))) =>
        resources.reporter
          .info(
            s"Fastly Compute@Edge service ${client.serviceId} - version $nextVersionNumber is now active"
          )
      case Some((_, Failure(err))) =>
        resources.reporter.fail(
          s"$err. Ensure that your Fastly service currently has an active version and is deployed.  Alternatively, your API key may be invalid or it may have expired"
        )

    }
  }

  /** A helper method that creates a `java.io.File` from an S3 object, making
    * sure that it has the correct Compute@Edge package extension.
    * @param obj
    *   The S3 Object to be converted to an instance of the `java.io.File` class
    * @param reporter
    *   The Deploy Reporter
    * @return
    *   a valid File
    */
  private def createFileFromS3Object(
      obj: S3Object,
      reporter: DeployReporter
  ): File = {
    if (!obj.extension.contains("gz")) {
      reporter.fail("The object is not a valid Compute@Edge package")
    }

    val fileName = obj.relativeTo(s3Package)

    val getObjectRequest = GetObjectRequest
      .builder()
      .bucket(obj.bucket)
      .key(obj.key)
      .build()

    reporter.info(s"About to fetch $fileName from S3")
    val `package` =
      withResource(artifactClient.getObject(getObjectRequest)) { stream =>
        // `fromInputStream` uses an implicit codec which needs to be
        // overridden in order to convert binary to Latin-1, hence ISO-8859-1.
        // This will allow us to send a valid HTTP request to the Fastly API
        val bufferedSource =
          scala.io.Source.fromInputStream(stream)(ISO8859)
        val bytes =
          bufferedSource.mkString.getBytes(StandardCharsets.ISO_8859_1)

        // `createTempFile` expects a prefix and a suffix.
        // If we don't strip off the suffix from the file name (which includes the extension),
        // it will be duplicated (e.g. `package-name.tar.gz.tar.gz`)
        val tempFile = Files
          .createTempFile(fileName.replace(".tar.gz", ""), ".tar.gz")

        Files
          .write(
            tempFile,
            bytes
          )
          .toFile
      }

    reporter.info(s"${`package`.getName} successfully created from S3 resource")
    `package`
  }

  private def uploadPackage(
      versionNumber: Int,
      s3Package: S3Path,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {

      s3Package.listAll()(artifactClient).map { obj =>
        val `package` = createFileFromS3Object(obj, reporter)

        val response = block(
          client.packageUpload(client.serviceId, versionNumber, `package`)
        )

        if (response.getStatusCode != 200) {
          reporter.fail(
            s"Failed to upload package to the Fastly API: ${response.getResponseBody}"
          )
        }
        reporter.info("Compute@Edge package successfully uploaded to Fastly")
      }
    }
  }

  override def activateVersion(
      versionNumber: Int,
      client: FastlyApiClient,
      reporter: DeployReporter,
      stopFlag: => Boolean
  ): Unit = {
    stopOnFlag(stopFlag) {
      reporter.info(
        s"Activating Fastly Compute@Edge service ${client.serviceId} - version $versionNumber"
      )
      block(client.versionActivate(versionNumber))
    }
  }

  override def description: String =
    "Upload a Compute@Edge package"
}
