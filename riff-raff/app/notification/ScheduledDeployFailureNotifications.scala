package notification

import java.util.UUID
import ci.TargetResolver
import controllers.{Logging, routes}
import lifecycle.Lifecycle
import magenta.{DeployParameters, DeployReporter, Fail}
import scala.concurrent.ExecutionContext
import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Config
import magenta.deployment_type.DeploymentType
import magenta.input.resolver.Resolver
import schedule.ScheduledDeployer

class ScheduledDeployFailureNotifications(config: Config,
                                          deploymentTypes: Seq[DeploymentType],
                                          targetResolver: TargetResolver)
                                         (implicit ec: ExecutionContext)
  extends Lifecycle with Logging {

  lazy private val anghammaradTopicARN = config.scheduledDeployment.anghammaradTopicARN
  lazy private val snsClient = config.scheduledDeployment.snsClient
  lazy private val prefix = config.urls.publicPrefix

  def url(uuid: UUID): String = {
    val path = routes.DeployController.viewUUID(uuid.toString,true)
    prefix + path.url
  }

  def failedDeployNotification(uuid: UUID, parameters: DeployParameters) = {
    val deriveAnghammaradTargets = for {
      yaml <- targetResolver.fetchYaml(parameters.build)
      deployGraph <- Resolver.resolveDeploymentGraph(yaml, deploymentTypes, magenta.input.All).toEither
    } yield {
      TargetResolver.extractTargets(deployGraph).toList.flatMap { target =>
        List(App(target.app), Stack(target.stack))
      } ++ List(Stage(parameters.stage.name))
    }

    deriveAnghammaradTargets match {
      case Right(targets) =>
        val failureMessage = s"Your scheduled deploy for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name} failed."
        Anghammarad.notify(
          subject = s"Scheduled deploy failed",
          message = failureMessage,
          sourceSystem = "riff-raff",
          channel = All,
          target = targets,
          actions = List(Action("View failed deploy", url(uuid))),
          topicArn = anghammaradTopicARN,
          client = snsClient
        ).recover { case ex => log.error(s"Failed to send notification (via Anghammarad) for ${uuid}", ex) }
      case Left(_) =>
        log.error(s"Failed to derive targets required to notify about failed scheduled deploy: ${uuid}")
    }
  }

  def scheduledDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ScheduledDeployer.deployer

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case Fail(_, _) if scheduledDeploy(message.context.parameters) =>
        log.info(s"Attempting to send notification via Anghammarad")
        failedDeployNotification(message.context.deployId, message.context.parameters)
      case _ =>
    }
  })

  def init() {}

  def shutdown() {
    messageSub.unsubscribe()
  }


}
