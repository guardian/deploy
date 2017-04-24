package deployment

import java.util.UUID

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.agent.Agent
import com.typesafe.config.ConfigFactory
import controllers.Logging
import deployment.actors.{DeployCoordinator, DeployGroupRunner, TasksRunner}
import magenta.deployment_type.DeploymentType
import resources.PrismLookup

import scala.collection.JavaConverters._

class DeploymentEngine(prismLookup: PrismLookup, deploymentTypes: Seq[DeploymentType]) extends Logging {
  import DeploymentEngine._

  private lazy val deploymentRunnerFactory = (context: ActorRefFactory, runnerName: String) =>
    context.actorOf(
      props = Props(new TasksRunner(stopFlagAgent)).withDispatcher("akka.deploy-dispatcher"),
      name = s"deploymentRunner-$runnerName"
  )

  private lazy val deployRunnerFactory = (context: ActorRefFactory, record: Record, deployCoordinator: ActorRef) =>
    context.actorOf(
      props = Props(
        new DeployGroupRunner(record,
                              deployCoordinator,
                              deploymentRunnerFactory,
                              stopFlagAgent,
                              prismLookup,
                              deploymentTypes)
      ).withDispatcher("akka.deploy-dispatcher"),
      name = s"deployGroupRunner-${record.uuid.toString}"
  )

  private lazy val deployCoordinator = system.actorOf(
    Props(
      new DeployCoordinator(deployRunnerFactory, concurrentDeploys, stopFlagAgent)
    ),
    name = "deployCoordinator")

  def interruptibleDeploy(record: Record) {
    log.debug("Sending start deploy message to co-ordinator")
    deployCoordinator ! DeployCoordinator.StartDeploy(record)
  }

  def stopDeploy(uuid: UUID, userName: String) {
    stopFlagAgent.send(_ + (uuid -> userName))
  }

  def getDeployStopFlag(uuid: UUID): Boolean = {
    stopFlagAgent().contains(uuid)
  }
}

object DeploymentEngine {

  private val concurrentDeploys = conf.Configuration.concurrency.maxDeploys

  private lazy val dispatcherConfig = ConfigFactory.parseMap(
    Map(
      "akka.deploy-dispatcher.type" -> "Dispatcher",
      "akka.deploy-dispatcher.executor" -> "fork-join-executor",
      "akka.deploy-dispatcher.fork-join-executor.parallelism-min" -> s"$concurrentDeploys",
      "akka.deploy-dispatcher.fork-join-executor.parallelism-factor" -> s"$concurrentDeploys",
      "akka.deploy-dispatcher.fork-join-executor.parallelism-max" -> s"${concurrentDeploys * 4}",
      "akka.deploy-dispatcher.fork-join-executor.task-peeking-mode" -> "FIFO",
      "akka.deploy-dispatcher.throughput" -> "1"
    ).asJava
  )

  private lazy val system = ActorSystem("deploy", dispatcherConfig.withFallback(ConfigFactory.load()))

  private lazy val stopFlagAgent = Agent(Map.empty[UUID, String])(system.dispatcher)
}
