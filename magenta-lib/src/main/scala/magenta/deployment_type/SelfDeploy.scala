package magenta.deployment_type

import magenta.tasks.{ChangeSwitch, S3Upload}

object SelfDeploy extends DeploymentType {
  val name = "self-deploy"
  val documentation =
    """
      |This is a special deployment type that can be used to self-deploy Riff-Raff.
      |
      |The approach is to upload a file to S3 and then hit a switch on the singleton instance.
      |When the switch is hit it will shutdown with a specific exit code at the next
      |opportunity (when it has finished deploys).
    """.stripMargin

  val bucket = Param[String]("bucket",
                             """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stack>/<stage>/<packageName>/<fileName>`.
    """.stripMargin)

  val publicReadAcl =
    Param[Boolean]("publicReadAcl",
                   "Whether the uploaded artifacts should be given the PublicRead Canned ACL. (Default is true!)")
      .defaultFromPackage(_.legacyConfig)

  val managementPort = Param[Int](
    "managementPort",
    "For deferred deployment only: The port of the management pages containing the location of the switchboard")
    .default(18080)
  val managementProtocol = Param[String](
    "managementProtocol",
    "For deferred deployment only: The protocol of the management pages containing the location of the switchboard")
    .default("http")
  val switchboardPath =
    Param[String]("switchboardPath",
                  "For deferred deployment only: The URL path on the host to the switchboard management page")
      .default("/management/switchboard")

  def defaultActions = List("uploadArtifacts", "selfDeploy")

  def actions = {
    case "uploadArtifacts" =>
      (pkg) => (resources, target) =>
        implicit val keyRing = resources.assembleKeyring(target, pkg)
        implicit val artifactClient = resources.artifactClient
        val prefix = S3Upload.prefixGenerator(target.stack, target.parameters.stage, pkg.name)
        List(
          S3Upload(
            target.region,
            bucket.get(pkg).orElse(target.stack.nameOption.map(stackName => s"$stackName-dist")).get,
            paths = Seq(pkg.s3Package -> prefix)
          )
        )
    case "selfDeploy" =>
      (pkg) => (resources, target) =>
        implicit val keyRing = resources.assembleKeyring(target, pkg)
        val hosts =
          pkg.apps.toList.flatMap(app => resources.lookup.hosts.get(pkg, app, target.parameters, target.stack))
        hosts.map { host =>
          ChangeSwitch(
            host,
            managementProtocol(pkg),
            managementPort(pkg),
            switchboardPath(pkg),
            "shutdown-when-inactive",
            desiredState = true
          )
        }
  }
}
