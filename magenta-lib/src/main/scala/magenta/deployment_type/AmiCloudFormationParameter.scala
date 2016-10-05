package magenta.deployment_type

import magenta.tasks.{CheckUpdateEventsTask, UpdateAmiCloudFormationParameterTask}

object AmiCloudFormationParameter extends DeploymentType {
  val name = "ami-cloudformation-parameter"
  def documentation =
    """Update an AMI parameter in a CloudFormation stack.
      |
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      |on the provided CloudFormation stack.
    """.stripMargin

  val cloudFormationStackName =
    Param[String]("cloudFormationStackName", documentation = "The name of the CloudFormation stack to update")
      .defaultFromPackage(_.name)
  val prependStackToCloudFormationStackName = Param[Boolean](
    "prependStackToCloudFormationStackName",
    documentation =
      "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp")
    .default(true)
  val appendStageToCloudFormationStackName =
    Param[Boolean]("appendStageToCloudFormationStackName",
                   documentation =
                     "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD")
      .default(true)
  val amiTags =
    Param[Map[String, String]]("amiTags", documentation = "Specify the set of tags to use to find the latest AMI")
  val amiParameter =
    Param[String]("amiParameter", documentation = "The CloudFormation parameter name for the AMI").default("AMI")

  def defaultActions = List("update")

  override def actions = {
    case "update" =>
      pkg => (resources, target) =>
        {
          implicit val keyRing = resources.assembleKeyring(target, pkg)

          val stackName = target.stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg))
          val stageName = Some(target.parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg))
          val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg)), stageName).flatten
          val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")

          List(
            UpdateAmiCloudFormationParameterTask(
              fullCloudFormationStackName,
              amiParameter(pkg),
              amiTags(pkg),
              resources.lookup.getLatestAmi,
              target.parameters.stage,
              target.stack
            ),
            CheckUpdateEventsTask(fullCloudFormationStackName)
          )
        }
  }
}
