package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import akka.actor.{Actor, Props, ActorRef, ActorSystem}
import controllers.{DeployLibrary, Logging}
import magenta.teamcity.Artifact.build2download
import java.util.UUID

object DeployActor {
  trait Event
  case class Deploy(uuid: UUID) extends Event
  case class Resolve(uuid: UUID) extends Event
  case class Execute(uuid: UUID) extends Event

  lazy val system = ActorSystem("deploy")

  def apply(uuid:UUID): ActorRef = {
    system.actorOf(Props[DeployActor],"deploy-%s" format uuid.toString)
  }
}

class DeployActor() extends Actor with Logging {
  import DeployActor._

  def receive = {
    case Resolve(uuid) => {
      val record = DeployLibrary.await(uuid)
      record.loggingContext {
        record.withDownload { artifactDir =>
          resolveContext(artifactDir, record)
        }
      }
    }

    case Execute(uuid) => {
      val record = DeployLibrary.await(uuid)
      record.loggingContext {
        record.withDownload { artifactDir =>
          resolveContext(artifactDir, record)
          DeployLibrary.await(uuid).context.foreach { realContext =>
            log.info("Executing deployContext")
            val keyRing = DeployInfoManager.keyRing(realContext)
            realContext.execute(keyRing)
          }
        }
      }
    }
  }

  def resolveContext(artifactDir: File, record: DeployRecord) {
    log.info("Reading deploy.json")
    MessageBroker.info("Reading deploy.json")
    val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
    DeployLibrary.updateWithContext() { record =>
        val updatedRecord = record.attachContext(project)
        updatedRecord.context.foreach(_.tasks)
        updatedRecord
    }
  }
}