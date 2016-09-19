package magenta.input

import play.api.libs.json.{JsValue, Json, Reads}


case class RiffRaffDeployConfig(
  stacks: Option[List[String]],
  regions: Option[List[String]],
  templates: Option[Map[String, DeploymentOrTemplate]],
  deployments: Map[String, DeploymentOrTemplate]
)
object RiffRaffDeployConfig {
  implicit val reads: Reads[RiffRaffDeployConfig] = Json.reads
}

/**
  * Represents entries for deployments and templates in a riff-raff.yml.
  * Deployments and deployment templates have the same structure so this class can represent both.
  *
  * @param `type`           The type of deployment to perform (e.g. autoscaling, s3).
  * @param template         Name of the custom deploy template to use for this deployment.
  * @param stacks           Stack tags to apply to this deployment. The deployment will be executed once for each stack.
  * @param regions          A list of the regions in which this deploy will be executed. Defaults to just 'eu-west-1'
  * @param app              The `app` tag to use for this deployment. By default the deployment's key is used.
  * @param contentDirectory The path where this deployment is found in the build output. Defaults to app.
  * @param dependencies     This deployment's execution will be delayed until all named dependencies have completed. (Default empty)
  * @param parameters       Provides additional parameters to the deployment type. Refer to the deployment types to see what is required.
  */
case class DeploymentOrTemplate(
  `type`: Option[String],
  template: Option[String],
  stacks: Option[List[String]],
  regions: Option[List[String]],
  app: Option[String],
  contentDirectory: Option[String],
  dependencies: Option[List[String]],
  parameters: Option[Map[String, JsValue]]
)
object DeploymentOrTemplate {
  implicit val reads: Reads[DeploymentOrTemplate] = Json.reads
}

/**
  * A deployment that has been parsed and validated out of a riff-raff.yml file.
  */
case class Deployment(
  name: String,
  `type`: String,
  stacks: List[String],
  regions: List[String],
  app: String,
  contentDirectory: String,
  dependencies: List[String],
  parameters: Map[String, JsValue]
)
