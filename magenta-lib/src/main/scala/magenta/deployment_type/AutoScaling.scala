package magenta.deployment_type

import magenta.{DeployTarget, DeploymentPackage, DeploymentResources, KeyRing}
import magenta.tasks._

sealed trait MigrationTagRequirements
case object MustBePresent extends MigrationTagRequirements
case object MustNotBePresent extends MigrationTagRequirements

object AutoScalingGroupLookup {
  def getTargetAsgName(keyRing: KeyRing, target: DeployTarget, migrationTagRequirements: Option[MigrationTagRequirements], resources: DeploymentResources, pkg: DeploymentPackage) = {
    ASG.withAsgClient[String](keyRing, target.region, resources) { asgClient =>
      ASG.groupForAppAndStage(pkg, target.parameters.stage, target.stack, migrationTagRequirements, asgClient, resources.reporter).autoScalingGroupName()
    }
  }
}

object AutoScaling extends DeploymentType {
  val name = "autoscaling"
  val documentation =
    """
      |Deploy to an autoscaling group in AWS.
      |
      |The approach of this deploy type is to:
      |
      | - upload a new application artifact to an S3 bucket (from which new instances download their application)
      | - scale up, wait for the new instances to become healthy and then scale back down
    """.stripMargin

  val bucket = Param[String]("bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stack>/<stage>/<packageName>/<fileName>`.
      |
      |Despite there being a default for this we are migrating to always requiring it to be specified.
    """.stripMargin,
    optional = true,
    deprecatedDefault = true
  ).defaultFromContext((_, target) => Right(s"${target.stack.name}-dist"))
  val secondsToWait = Param("secondsToWait", "Number of seconds to wait for instances to enter service").default(15 * 60)
  val healthcheckGrace = Param("healthcheckGrace", "Number of seconds to wait for the AWS api to stabilise").default(20)
  val warmupGrace = Param("warmupGrace", "Number of seconds to wait for the instances in the load balancer to warm up").default(1)
  val terminationGrace = Param("terminationGrace", "Number of seconds to wait for the AWS api to stabilise after instance termination").default(10)

  val prefixStage = Param[Boolean]("prefixStage",
    documentation = "Whether to prefix `stage` to the S3 location"
  ).default(true)
  val prefixPackage = Param[Boolean]("prefixPackage",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)
  val prefixStack = Param[Boolean]("prefixStack",
    documentation = "Whether to prefix `stack` to the S3 location"
  ).default(true)

  val publicReadAcl = Param[Boolean]("publicReadAcl",
    "Whether the uploaded artifacts should be given the PublicRead Canned ACL"
  ).default(false)

  val asgMigrationInProgress = Param[Boolean]("asgMigrationInProgress",
    "When this is set to true, Riff-Raff will search for two autoscaling groups and deploy to them both"
  ).default(false)

  val deploy = Action("deploy",
    """
      |Carries out the update of instances in an autoscaling group. We carry out the following tasks:
      | - tag existing instances in the ASG with a termination tag
      | - double the size of the auto-scaling group (new instances will have the new application)
      | - wait for the new instances to enter service
      | - terminate previously tagged instances
      |
      |The action checks whether the auto-scaling group maxsize is big enough before starting the process and also
      |suspends and resumes cloud watch alarms in order to prevent false alarms.
      |
      |There are some delays introduced in order to work around consistency issues in the AWS ASG APIs.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    val reporter = resources.reporter
    def tasksPerAutoScalingGroup(autoScalingGroupName: String): List[ASGTask] = {
      List(
        WaitForStabilization(autoScalingGroupName, 5 * 60 * 1000, target.region),
        CheckGroupSize(autoScalingGroupName, target.region),
        SuspendAlarmNotifications(autoScalingGroupName, target.region),
        TagCurrentInstancesWithTerminationTag(autoScalingGroupName, target.region),
        ProtectCurrentInstances(autoScalingGroupName, target.region),
        DoubleSize(autoScalingGroupName, target.region),
        HealthcheckGrace(autoScalingGroupName, target.region, healthcheckGrace(pkg, target, reporter) * 1000),
        WaitForStabilization(autoScalingGroupName, secondsToWait(pkg, target, reporter) * 1000, target.region),
        WarmupGrace(autoScalingGroupName, target.region, warmupGrace(pkg, target, reporter) * 1000),
        WaitForStabilization(autoScalingGroupName, secondsToWait(pkg, target, reporter) * 1000, target.region),
        CullInstancesWithTerminationTag(autoScalingGroupName, target.region),
        TerminationGrace(autoScalingGroupName, target.region, terminationGrace(pkg, target, reporter) * 1000),
        WaitForStabilization(autoScalingGroupName, secondsToWait(pkg, target, reporter) * 1000, target.region),
        ResumeAlarmNotifications(autoScalingGroupName, target.region)
      )
    }
    val groupsToUpdate: List[String] = if (asgMigrationInProgress(pkg, target, reporter)) {
      List(
        AutoScalingGroupLookup.getTargetAsgName(keyRing, target, Some(MustNotBePresent), resources, pkg),
        AutoScalingGroupLookup.getTargetAsgName(keyRing, target, Some(MustBePresent), resources, pkg)
      )
    } else {
      List(AutoScalingGroupLookup.getTargetAsgName(keyRing, target, None, resources, pkg))
    }
    groupsToUpdate.flatMap(asg => tasksPerAutoScalingGroup(asg))
  }

  val uploadArtifacts = Action("uploadArtifacts",
    """
      |Uploads the files in the deployment's directory to the specified bucket.
    """.stripMargin
  ){ (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    val reporter = resources.reporter
    val prefix = S3Upload.prefixGenerator(
      stack = if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
      stage = if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage) else None,
      packageName = if (prefixPackage(pkg, target, reporter
      )) Some(pkg.name) else None
    )
    List(
      S3Upload(
        target.region,
        bucket(pkg, target, reporter),
        Seq(pkg.s3Package -> prefix),
        publicReadAcl = publicReadAcl(pkg, target, reporter)
      )
    )
  }

  val defaultActions = List(uploadArtifacts, deploy)
}
