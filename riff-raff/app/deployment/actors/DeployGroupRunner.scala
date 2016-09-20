package deployment.actors

import java.util.UUID

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, ActorRefFactory, OneForOneStrategy, Terminated}
import akka.agent.Agent
import controllers.Logging
import deployment.Record
import magenta.artifact.S3Artifact
import magenta.graph.{Deployment, DeploymentGraph, Graph, MidNode, StartNode}
import magenta.json.JsonReader
import magenta.{DeployContext, DeployReporter, DeployStoppedException}
import org.joda.time.DateTime
import resources.PrismLookup

import scala.util.Try
import scala.util.control.NonFatal

class DeployGroupRunner(
  record: Record,
  deployCoordinator: ActorRef,
  deploymentRunnerFactory: (ActorRefFactory, String) => ActorRef,
  stopFlagAgent: Agent[Map[UUID, String]],
  prismLookup: PrismLookup
) extends Actor with Logging {
  import DeployGroupRunner._

  override def supervisorStrategy() = OneForOneStrategy() {
    case throwable =>
      log.warn("DeploymentRunner died with exception", throwable)
      Stop
  }

  val rootReporter = DeployReporter.startDeployContext(DeployReporter.rootReporterFor(record.uuid, record.parameters))
  var rootContextClosed = false

  var deployContext: Option[DeployContext] = None

  var executing: Set[MidNode[Deployment]] = Set.empty
  var completed: Set[MidNode[Deployment]] = Set.empty
  var failed: Set[MidNode[Deployment]] = Set.empty

  def deploymentGraph: Graph[Deployment] = deployContext.map(_.tasks).getOrElse(Graph.empty[Deployment])
  def allDeployments = deploymentGraph.nodes.filterMidNodes

  def isFinished: Boolean = allDeployments == completed ++ failed
  def isExecuting: Boolean = executing.nonEmpty

  def firstDeployments: List[MidNode[Deployment]] = deploymentGraph.orderedSuccessors(StartNode).filterMidNodes
  /* these two functions can return a number of things
      - Deployments: list of deployments
      - FinishPath: indicator there are no more tasks on this path
      - FinishDeploy: indicator that there are no more tasks for this deploy
      first will actually only ever return the first of these.  */
  def nextDeployments(deployment: Deployment): NextResult = {
    // if this was a last node and there is no other nodes executing then there is nothing left to do
    if (isFinished) FinishDeploy
    // otherwise let's see what children are valid to return
    else {
      // candidates are all successors not already executing or completing
      val nextDeploymentCandidates = deploymentGraph.orderedSuccessors(deploymentGraph.get(deployment)).filterMidNodes
      // now filter for only tasks whose predecessors are all completed
      val nextDeployments = nextDeploymentCandidates.filter { deployment => (deploymentGraph.predecessors(deployment) -- completed).isEmpty }
      if (nextDeployments.nonEmpty) {
        Deployments(nextDeployments)
      } else {
        FinishPath
      }
    }
  }
  protected[deployment] def markExecuting(deployment: Deployment) = {
    executing += deploymentGraph.get(deployment)
  }
  protected[deployment] def markComplete(deployment: Deployment) = {
    val node = deploymentGraph.get(deployment)
    executing -= node
    completed += node
  }
  protected[deployment] def markFailed(deployment: Deployment) = {
    val node = deploymentGraph.get(deployment)
    executing -= node
    failed += node
  }
  def finishRootContext() = {
    rootContextClosed = true
    DeployReporter.finishContext(rootReporter)
  }
  def failRootContext() = {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter)
  }
  def failRootContext(message: String, exception: Throwable) = {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter, message, exception)
  }
  private def cleanup() = {
    if (!rootContextClosed) finishRootContext()
    deployCoordinator ! DeployCoordinator.CleanupDeploy(record.uuid)
    context.stop(self)
  }

  override def toString: String = {
    s"""
       |UUID: ${record.uuid.toString}
       |#Tasks: ${allDeployments.size}
       |#Executing: ${executing.mkString("; ")}
       |#Completed: ${completed.size} Failed: ${failed.size}
       |#Done: ${completed.size+failed.size}
     """.stripMargin
  }

  def receive = {
    case Start =>
      try {
        self ! ContextCreated(createContext)
        self ! StartDeployment
      } catch {
        case NonFatal(t) =>
          if (!rootContextClosed) failRootContext("Preparing deploy failed", t)
          cleanup()
      }

    case ContextCreated(preparedContext) =>
      deployContext = Some(preparedContext)

    case StartDeployment =>
      runDeployments(firstDeployments)

    case DeploymentCompleted(deployment) =>
      log.debug("Deployment completed")
      markComplete(deployment)
      nextDeployments(deployment) match {
        case Deployments(deployments) =>
          runDeployments(deployments)
        case FinishPath =>
        case FinishDeploy =>
          cleanup()
      }

    case DeploymentFailed(deployment, exception) =>
      log.debug("Deployment failed")
      markFailed(deployment)
      if (isExecuting) {
        log.debug("Failed during deployment but others still running - deferring clean up")
      } else {
        cleanup()
      }

    case Terminated(actor) =>
      if (!rootContextClosed) failRootContext("DeploymentRunner unexpectedly terminated", new RuntimeException("DeploymentRunner unexpectedly terminated"))
      log.warn(s"Received terminate from ${actor.path}")
  }

  private def createContext: DeployContext = {
    DeployReporter.withFailureHandling(rootReporter) { implicit safeReporter =>
      import conf.Configuration.artifact.aws._
      safeReporter.info("Reading deploy.json")
      val s3Artifact = S3Artifact(record.parameters.build, bucketName)
      val json = S3Artifact.withZipFallback(s3Artifact) { artifact =>
        Try(artifact.deployObject.fetchContentAsString()(client).get)
      }(client, safeReporter)
      val project = JsonReader.parse(json, s3Artifact)
      val context = record.parameters.toDeployContext(record.uuid, project, prismLookup, safeReporter, client)
      if (DeploymentGraph.toTaskList(context.tasks).isEmpty)
        safeReporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")
      context
    }
  }

  private def runDeployments(deployments: List[MidNode[Deployment]]) = {
    try {
      honourStopFlag(rootReporter) {
        deployments.zipWithIndex.foreach { case (MidNode(deployment), index) =>
          val actorName = s"${record.uuid}-${context.children.size}"
          log.debug(s"Running next deployment (${deployment.name}/$index) on actor $actorName")
          val deploymentRunner = context.watch(deploymentRunnerFactory(context, actorName))
          deploymentRunner ! DeploymentRunner.RunDeployment(record.uuid, deployment, rootReporter, new DateTime())
          markExecuting(deployment)
        }
      }
    } catch {
      case NonFatal(t) => log.error("Couldn't run deployment", t)
    }
  }

  private def honourStopFlag(reporter: DeployReporter)(elseBlock: => Unit) {
    stopFlagAgent().get(record.uuid) match {
      case Some(userName) =>
        log.debug("Stop flag set")
        val stopMessage = s"Deploy has been stopped by $userName"
        if (!isExecuting) {
          DeployReporter.failContext(rootReporter, stopMessage, DeployStoppedException(stopMessage))
          log.debug("Cleaning up")
          cleanup()
        }

      case None =>
        elseBlock
    }
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.debug(s"Deployment group runner ${self.path} stopped")
    if (!rootContextClosed) failRootContext()
    super.postStop()
  }
}

object DeployGroupRunner {
  sealed trait NextResult
  case class Deployments(deployments: List[MidNode[Deployment]]) extends NextResult
  case object FinishPath extends NextResult
  case object FinishDeploy extends NextResult

  sealed trait Message
  case object Start extends Message
  case class ContextCreated(context: DeployContext) extends Message
  case object StartDeployment extends Message
  case class DeploymentCompleted(deployment: Deployment) extends Message
  case class DeploymentFailed(deployment: Deployment, exception: Throwable) extends Message
}