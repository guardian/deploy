package notification

import java.net.URL
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import com.mongodb.casbah.commons.MongoDBObject
import controllers.Logging
import lifecycle.Lifecycle
import magenta.{Deploy, DeployParameters, FinishContext, _}
import org.joda.time.DateTime
import persistence.{DeployRecordDocument, HookConfigRepository, MongoFormat, MongoSerialisable, Persistence}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.libs.ws._

import scala.util.Try
import scala.util.control.NonFatal

case class Auth(user: String, password: String, scheme: WSAuthScheme = WSAuthScheme.BASIC)

case class HookConfig(id: UUID,
                      projectName: String,
                      stage: String,
                      url: String,
                      enabled: Boolean,
                      lastEdited: DateTime,
                      user: String,
                      method: HttpMethod = GET,
                      postBody: Option[String] = None)
    extends Logging {

  def request(record: DeployRecordDocument)(implicit wsClient: WSClient) = {
    val templatedUrl = new HookTemplate(url, record, urlEncode = true).Template.run().get
    authFor(templatedUrl)
      .map(ui => wsClient.url(templatedUrl).withAuth(ui.user, ui.password, ui.scheme))
      .getOrElse(wsClient.url(templatedUrl))
  }

  def authFor(url: String): Option[Auth] = Option(new URL(url).getUserInfo).flatMap { ui =>
    ui.split(':') match {
      case Array(username, password) => Some(Auth(username, password))
      case _ => None
    }
  }

  def act(record: DeployRecordDocument)(implicit wSClient: WSClient) {
    if (enabled) {
      val urlRequest = request(record)
      log.info(s"Calling ${urlRequest.url}")
      (method match {
        case GET =>
          urlRequest.get()
        case POST =>
          postBody
            .map { t =>
              val body = new HookTemplate(t, record, urlEncode = false).Template.run().get
              val json = Try {
                Json.parse(body)
              }.toOption
              json.map(urlRequest.post(_)).getOrElse(urlRequest.post(body))
            }
            .getOrElse(
              urlRequest.post(Map[String, Seq[String]](
                "build" -> Seq(record.parameters.buildId),
                "project" -> Seq(record.parameters.projectName),
                "stage" -> Seq(record.parameters.stage),
                "recipe" -> Seq(record.parameters.recipe),
                "hosts" -> record.parameters.hostList,
                "stacks" -> record.parameters.stacks,
                "deployer" -> Seq(record.parameters.deployer),
                "uuid" -> Seq(record.uuid.toString),
                "tags" -> record.parameters.tags.toSeq.map { case ((k, v)) => s"$k:$v" }
              ))
            )
      }).map { response =>
          log.info(s"HTTP status code ${response.status} from ${urlRequest.url}")
          log.debug(s"HTTP response body from ${urlRequest.url}: ${response.status}")
        }
        .recover {
          case NonFatal(e) => log.error(s"Problem calling ${urlRequest.url}", e)
        }
    } else {
      log.info("Hook disabled")
    }
  }
}
object HookConfig {
  def apply(projectName: String, stage: String, url: String, enabled: Boolean, updatedBy: String): HookConfig =
    HookConfig(UUID.randomUUID(), projectName, stage, url, enabled, new DateTime(), updatedBy)
}

class HooksClient(wsClient: WSClient) extends Lifecycle with Logging {
  lazy val system = ActorSystem("notify")
  val actor = try {
    Some(system.actorOf(Props(classOf[HooksClientActor], wsClient), "hook-client"))
  } catch {
    case t: Throwable =>
      log.error("Failed to start HookClient", t)
      None
  }

  def finishedBuild(uuid: UUID, parameters: DeployParameters) {
    actor.foreach(_ ! HooksClientActor.Finished(uuid, parameters))
  }

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case FinishContext(Deploy(parameters)) =>
        finishedBuild(message.context.deployId, parameters)
      case _ =>
    }
  })

  def init() {}
  def shutdown() {
    messageSub.unsubscribe()
    actor.foreach(system.stop)
  }
}

object HooksClientActor {
  trait Event
  case class Finished(uuid: UUID, params: DeployParameters)
}

class HooksClientActor(implicit wsClient: WSClient) extends Actor with Logging {
  import notification.HooksClientActor._

  def receive = {
    case Finished(uuid, params) =>
      HookConfigRepository.getPostDeployHook(params.build.projectName, params.stage.name).foreach { config =>
        try {
          config.act(Persistence.store.readDeploy(uuid).get)
        } catch {
          case t: Throwable =>
            log.warn(s"Exception caught whilst processing post deploy hooks for $config", t)
        }
      }
  }
}

object HttpMethod {
  def apply(stringRep: String): HttpMethod = stringRep match {
    case GET.serialised => GET
    case POST.serialised => POST
    case _ => throw new IllegalArgumentException(s"Can't translate $stringRep to HTTP verb")
  }
  def all: List[HttpMethod] = List(GET, POST)
}

sealed trait HttpMethod {
  def serialised: String
  override def toString = serialised
}
case object GET extends HttpMethod {
  override val serialised = "GET"
}
case object POST extends HttpMethod {
  override val serialised = "POST"
}
