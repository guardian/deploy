import sbt._

object DeployPlugins extends Build {
  val playArtifactPluginVersion = "v2.10"

  lazy val plugins = Project("deploy-plugins", file("."))
    .dependsOn(
    uri("git://github.com/guardian/sbt-teamcity-test-reporting-plugin.git#v1.3"),
    uri("git://github.com/guardian/sbt-dist-plugin.git#1.7"),
    uri("git://github.com/guardian/sbt-play-artifact.git#" + playArtifactPluginVersion)
  )
}
