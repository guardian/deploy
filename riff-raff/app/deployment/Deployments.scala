package deployment

import java.util.UUID

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.util.Switch
import ci._
import com.gu.management.DefaultSwitch
import controllers.Logging
import lifecycle.Lifecycle
import magenta._
import persistence.{DocumentStoreConverter, RestrictionConfigDynamoRepository}
import play.api.libs.concurrent.Execution.Implicits._
import restrictions.RestrictionChecker
import rx.lang.scala.{Observable, Subject, Subscription}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

class Deployments(deploymentEngine: DeploymentEngine) extends Logging {
  import Deployments._

  def deploy(requestedParams: DeployParameters, requestSource: RequestSource): Either[Error, UUID] = {
    log.info(s"Started deploying $requestedParams")
    val restrictionsPreventingDeploy = RestrictionChecker.configsThatPreventDeployment(
      RestrictionConfigDynamoRepository,
      requestedParams.build.projectName,
      requestedParams.stage.name,
      requestSource)
    if (restrictionsPreventingDeploy.nonEmpty) {
      Left(Error(s"Unable to queue deploy as restrictions are currently in place: ${restrictionsPreventingDeploy
        .map(r => s"${r.fullName}: ${r.note}")
        .mkString("; ")}"))
    } else if (enableQueueingSwitch.isSwitchedOff) {
      Left(
        Error(
          s"Unable to queue a new deploy; deploys are currently disabled by the ${enableQueueingSwitch.name} switch"))
    } else {
      val params =
        if (requestedParams.build.id != "lastSuccessful")
          requestedParams
        else {
          Builds
            .getLastSuccessful(requestedParams.build.projectName)
            .map { latestId =>
              requestedParams.copy(build = requestedParams.build.copy(id = latestId))
            }
            .getOrElse(requestedParams)
        }
      deploysEnabled.whileOnYield {
        val record = Deployments.create(params)
        deploymentEngine.interruptibleDeploy(record)
        Right(record.uuid)
      } getOrElse {
        Left(
          Error(
            s"Unable to queue a new deploy; deploys are currently disabled by the ${enableDeploysSwitch.name} switch"))
      }
    }
  }

  def stop(uuid: UUID, fullName: String) {
    deploymentEngine.stopDeploy(uuid, fullName)
  }

  def getStopFlag(uuid: UUID): Boolean = deploymentEngine.getDeployStopFlag(uuid)

}

object Deployments extends Logging with Lifecycle {
  lazy val completed: Observable[UUID] = deployCompleteSubject
  private lazy val deployCompleteSubject = Subject[UUID]()

  private val messagesSubscription: Subscription =
    DeployReporter.messages.subscribe { wrapper =>
      try {
        update(wrapper)
      } catch {
        case NonFatal(t) =>
          log.error(s"Exception thrown whilst processing update with $wrapper", t)
      }
    }
  def init() {}
  def shutdown() { messagesSubscription.unsubscribe() }

  val deploysEnabled = new Switch(startAsOn = true)

  /**
    * Attempt to disable deploys from running.
    *
    * @return true if successful and false if this failed because a deploy was currently running
    */
  def atomicDisableDeploys: Boolean = {
    try {
      deploysEnabled.switchOff {
        if (getControllerDeploys.exists(!_.isDone))
          throw new IllegalStateException("Cannot turn switch off as builds are currently running")
      }
      true
    } catch {
      case e: IllegalStateException => false
    }
  }

  def enableDeploys = deploysEnabled.switchOn

  lazy val enableSwitches = List(enableDeploysSwitch, enableQueueingSwitch)

  lazy val enableDeploysSwitch = new DefaultSwitch(
    "enable-deploys",
    "Enable riff-raff to queue and run deploys.  This switch can only be turned off if no deploys are running.",
    deploysEnabled.isOn) {
    override def switchOff() {
      if (!atomicDisableDeploys)
        throw new IllegalStateException("Cannot turn switch off as builds are currently running")
      super.switchOff()
    }
    override def switchOn(): Unit = {
      enableDeploys
      super.switchOn()
    }
  }

  lazy val enableQueueingSwitch = new DefaultSwitch(
    "enable-deploy-queuing",
    "Enable riff-raff to queue deploys.  Turning this off will prevent anyone queueing a new deploy, although running deploys will continue.",
    true
  )

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID, Agent[DeployRecord]])

  def create(params: DeployParameters): Record = {
    log.info(s"Creating deploy record for $params")
    val uuid = java.util.UUID.randomUUID()
    val hostNameMetadata = Map(Record.RIFFRAFF_HOSTNAME -> java.net.InetAddress.getLocalHost.getHostName)
    val record = DeployRecord(uuid, params) ++ hostNameMetadata
    library send { _ + (uuid -> Agent(record)) }
    DocumentStoreConverter.saveDeploy(record)
    await(uuid)
  }

  def update(wrapper: MessageWrapper) {
    Option(library()(wrapper.context.deployId)) foreach { recordAgent =>
      recordAgent send { record =>
        val updated = record + wrapper
        DocumentStoreConverter.saveMessage(wrapper)
        if (record.state != updated.state) DocumentStoreConverter.updateDeployStatus(updated)
        if (record.totalTasks != updated.totalTasks || record.completedTasks != updated.completedTasks)
          DocumentStoreConverter.updateDeploySummary(updated)
        if (updated.isDone) cleanup(record.uuid)
        updated
      }
    }
  }

  def cleanup(uuid: UUID) {
    log.debug(s"Queuing removal of deploy record $uuid from internal caches")
    library sendOff { allDeploys =>
      val record = Await.result(allDeploys(uuid).future(), 10 seconds)
      log.debug(s"Done removing deploy record $uuid from internal caches")
      allDeploys - record.uuid
    }
    firePostCleanup(uuid)
  }

  def firePostCleanup(uuid: UUID) {
    library.future().onComplete { _ =>
      deployCompleteSubject.onNext(uuid)
    }
  }
  def getControllerDeploys: Iterable[Record] = { library().values.map { _() } }
  def getDatastoreDeploys(filter: Option[DeployFilter] = None,
                          pagination: PaginationView,
                          fetchLogs: Boolean): Iterable[Record] =
    DocumentStoreConverter.getDeployList(filter, pagination, fetchLogs)

  def getDeploys(filter: Option[DeployFilter] = None,
                 pagination: PaginationView = PaginationView(),
                 fetchLogs: Boolean = false): List[Record] = {
    require(!fetchLogs || pagination.pageSize.isDefined,
            "Too much effort required to fetch complete record with no pagination")
    getDatastoreDeploys(filter, pagination, fetchLogs = fetchLogs).toList.sortWith {
      _.time.getMillis < _.time.getMillis
    }
  }

  def getLastCompletedDeploys(project: String): Map[String, Record] = {
    DocumentStoreConverter.getLastCompletedDeploys(project)
  }

  def findProjects(): List[String] = {
    DocumentStoreConverter.findProjects()
  }

  def countDeploys(filter: Option[DeployFilter]) = DocumentStoreConverter.countDeploys(filter)

  def markAsFailed(record: Record) {
    DocumentStoreConverter.updateDeployStatus(record.uuid, RunState.Failed)
  }

  def get(uuid: UUID, fetchLog: Boolean = true): Record = {
    val agent = library().get(uuid)
    agent.map(_()).getOrElse {
      DocumentStoreConverter.getDeploy(uuid, fetchLog).get
    }
  }

  def await(uuid: UUID): Record = {
    Await.result(library.future().flatMap(_(uuid).future()), 5 seconds)
  }
}
