package magenta
package tasks

import scala.io.Source
import java.net.Socket
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.CannedAccessControlList.PublicRead
import scala._
import java.io.{IOException, FileNotFoundException, File}

object CommandLocator {
  var rootPath = "/opt/deploy/bin"
  def conditional(binary: String) = List("if", "[", "-f", rootPath+"/"+binary, "];", "then", rootPath+"/"+binary,";", "fi" )
}

case class CopyFile(host: Host, source: String, dest: String) extends ShellTask {
  def commandLine = List("rsync", "-rv", source, "%s:%s" format(host.connectStr, dest))
  def commandLine(sshCredentials: Credentials): CommandLine = sshCredentials.keyFileLocation map { location =>
    val shellCommand = CommandLine("ssh" :: "-i" :: location.getPath :: Nil).quoted
    CommandLine(commandLine.commandLine.head :: "-e" :: shellCommand :: commandLine.commandLine.tail)
  } getOrElse (commandLine)

  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)

  override def execute(sshCredentials: Credentials) {
    commandLine(sshCredentials).run()
  }
}

case class S3Upload(stage: Stage, bucket: String, file: File) extends Task with S3 {

  private val base = file.getParent + "/"

  private val describe = "Upload %s %s to S3" format ( if (file.isDirectory) "directory" else "file", file )
  def description = describe
  def verbose = describe

  def execute(sshCredentials: Credentials)  {
    val client = s3client
    val filesToCopy = resolveFiles(file)
    val requests = filesToCopy map { file => putObjectRequestWithPublicRead(bucket, toKey(file), file) }
    requests.par foreach { client.putObject }
  }

  def toKey(file: File) = stage.name + "/" + file.getAbsolutePath.replace(base, "")

  private def resolveFiles(file: File): Seq[File] =
    Option(file.listFiles).map { _.toSeq.flatMap(resolveFiles) } getOrElse (Seq(file)).distinct
}

case class BlockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = CommandLocator conditional "block-load-balancer"
}

case class Restart(host: Host, appName: String) extends RemoteShellTask {
  def commandLine = List("sudo", "/sbin/service", appName, "restart")
}

case class UnblockFirewall(host: Host) extends RemoteShellTask {
  def commandLine =  CommandLocator conditional "unblock-load-balancer"
}

case class WaitForPort(host: Host, port: String, duration: Long) extends Task with RepeatedPollingCheck {
  def description = "to %s on %s" format(host.name, port)
  def verbose = "Wail until a socket connection can be made to %s:%s" format(host.name, port)

  def execute(sshCredentials: Credentials) {
    check { new Socket(host.name, port.toInt).close() }
  }
}

case class CheckUrls(host: Host, port: String, paths: List[String], duration: Long) extends Task with RepeatedPollingCheck {
  def description = "check [%s] on " format(paths, host)
  def verbose = "Check that [%s] returns a 200" format(paths)

  def execute(sshCredentials: Credentials) {
    for (path <- paths) check { Source.fromURL("http://%s:%s%s" format (host.connectStr, port, path))  }
  }
}

trait RepeatedPollingCheck {
  def MAX_CONNECTION_ATTEMPTS: Int = 10
  def duration: Long

  def check(action: => Unit) {
    def checkAttempt(currentAttempt: Int) {
      if (currentAttempt > MAX_CONNECTION_ATTEMPTS)
        sys.error("Timed out")
      try action
      catch {
        case e: FileNotFoundException => {
          sys.error("404 Not Found")
        }
        case e: IOException => {
          Log.verbose("Timed out attempt #"+currentAttempt +"- Retrying")
          Thread.sleep(duration/MAX_CONNECTION_ATTEMPTS)
          checkAttempt(currentAttempt + 1)
        }
      }
    }
    checkAttempt(0)
  }
}


case class SayHello(host: Host) extends Task {
  def execute(sshCredentials: Credentials) {
    Log.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class EchoHello(host: Host) extends ShellTask {
  def commandLine = List("echo", "hello to " + host.name)
  def description = "to " + host.name
}

case class Link(host: Host, target: String, linkName: String) extends RemoteShellTask {
  def commandLine = List("ln", "-sfn", target, linkName)
}

case class ApacheGracefulStop(host: Host) extends RemoteShellTask {
  def commandLine = List("sudo", "/usr/sbin/apachectl", "graceful-stop")
}

case class ApacheStart(host: Host) extends RemoteShellTask {
  def commandLine = List("sudo", "/usr/sbin/apachectl", "start")
}

trait S3 {
  lazy val accessKey = Option(System.getenv.get("aws_access_key")).getOrElse{
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey = Option(System.getenv.get("aws_secret_access_key")).getOrElse{
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  lazy val credentials = new BasicAWSCredentials(accessKey, secretAccessKey)

  def s3client = new AmazonS3Client(credentials)
  def putObjectRequestWithPublicRead(bucket: String, key: String, file: File) =
    new PutObjectRequest(bucket, key, file).withCannedAcl(PublicRead)
}
