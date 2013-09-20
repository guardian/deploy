package deployment

import magenta.json.DeployInfoJsonReader
import magenta._
import akka.actor.ActorSystem
import scala.concurrent.duration._
import controllers.Logging
import magenta.App
import conf.{DeployInfoMode, Configuration}
import utils.ScheduledAgent
import java.io.{FileNotFoundException, File}
import java.net.{URLConnection, URL, URLStreamHandler}
import io.Source
import lifecycle.LifecycleWithoutApp
import net.liftweb.json.{DefaultFormats, JsonParser}
import net.liftweb.json.JsonAST.JObject
import org.joda.time.{DateTime, Duration}
import scala.collection.mutable
import scala.concurrent._
import ExecutionContext.Implicits.global
import java.util.concurrent.TimeoutException

object DeployInfoManager extends LifecycleWithoutApp with Logging {

  implicit class DeployInfoWithStale(di: DeployInfo) {
    def stale: Boolean = {
      di.createdAt.exists(new Duration(_, new DateTime).getStandardMinutes > Configuration.deployinfo.staleMinutes)
    }
  }

  private val classpathHandler = new URLStreamHandler {
    val classloader = getClass.getClassLoader
    override def openConnection(u: URL): URLConnection = {
      val resourceURL = classloader.getResource(u.getPath)
      if (resourceURL == null)
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      resourceURL.openConnection()
    }
  }

  private def getDeployInfo = {
    import sys.process._
    log.info("Populating deployinfo hosts...")
    val deployInfoJsonOption: Option[String] = Configuration.deployinfo.mode match {
      case DeployInfoMode.Execute =>
        if (new File(Configuration.deployinfo.location).exists) {
          val buffer = mutable.Buffer[String]()
          val process = Configuration.deployinfo.location.run(ProcessLogger( (s) => buffer += s, _ => ()))
          try {
            val futureExitValue = Await.result(future {
              process.exitValue()
            }, Configuration.deployinfo.timeoutSeconds.seconds)
            if (futureExitValue == 0) Some(buffer.mkString("")) else None
          } catch {
            case t:TimeoutException =>
              process.destroy()
              log.error("The deployinfo process didn't finish quickly enough, tried to terminate the process")
              None
          }
        } else {
          log.warn("No file found at '%s', defaulting to empty DeployInfo" format (Configuration.deployinfo.location))
          None
        }
      case DeployInfoMode.URL =>
        val url = Configuration.deployinfo.location match {
          case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
          case otherURL => new URL(otherURL)
        }
        log.info("URL: %s" format url)
        Some(Source.fromURL(url).getLines.mkString)
    }

    deployInfoJsonOption.map{ deployInfoJson =>
      implicit val formats = DefaultFormats
      val json = JsonParser.parse(deployInfoJson)
      val deployInfo = (json \ "response") match {
        case response:JObject => {
          val updateTime = (response \ "updateTime").extractOpt[String].map(s => new DateTime(s))
          DeployInfoJsonReader.parse(response \ "results").copy(createdAt = updateTime.orElse(Some(new DateTime())))
        }
        case _ => DeployInfoJsonReader.parse(deployInfoJson)
      }


      log.info("Successfully retrieved deployinfo (%d hosts and %d data found)" format (
        deployInfo.hosts.size, deployInfo.data.values.map(_.size).fold(0)(_+_)))

      deployInfo
    }
  }

  val system = ActorSystem("deploy")
  var agent: Option[ScheduledAgent[DeployInfo]] = None

  def init() {
    agent = Some(ScheduledAgent[DeployInfo](0 seconds, Configuration.deployinfo.refreshSeconds.seconds, DeployInfo()){ original =>
      getDeployInfo.getOrElse(original)
    })
  }

  def deployInfo = agent.map(_()).getOrElse(DeployInfo())
  def stale = deployInfo.stale

  def stageList = deployInfo.knownHostStages.sorted(conf.Configuration.stages.ordering)
  def hostList = deployInfo.hosts
  def dataList = deployInfo.data

  def credentials(stage: String, apps: Set[App]): Map[String, ApiCredentials] = {
    apps.toList.flatMap {
      app => {
        val KeyPattern = """credentials:(.*)""".r
        val apiCredentials = deployInfo.data.keys flatMap { key =>
          key match {
            case KeyPattern(service) =>
              deployInfo.firstMatchingData(key, app, stage).flatMap { data =>
                Configuration.credentials.lookupSecret(service, data.value).map{ secret =>
                  (service, ApiCredentials(service, data.value, secret, data.comment))
                }
              }
            case _ => None
          }
        }
        apiCredentials
      }
    }.distinct.toMap
  }

  def keyRing(context:DeployContext): KeyRing = {
    KeyRing( SystemUser(keyFile = Configuration.sshKey.file),
                credentials(context.stage.name, context.project.applications))
  }

  def shutdown() {
    agent.foreach(_.shutdown())
    agent = None
  }
}