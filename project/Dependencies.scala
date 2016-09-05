import sbt._
import sbt.Keys._
import play.PlayImport._

object Dependencies {

  object Versions {
    val aws = "1.11.18"
    val guardianManagement = "5.35"
    val guardianManagementPlay = "7.2"
  }

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.26.0",
    "org.parboiled" %% "parboiled" % "2.0.1",
    "org.scalatest" %% "scalatest" % "2.2.3" % Test
  )

  val magentaLibDeps = commonDeps ++ Seq(
    "net.databinder" %% "dispatch-http" % "0.8.10",
    "org.json4s" %% "json4s-native" % "3.2.11",
    "org.bouncycastle" % "bcprov-jdk16" % "1.46",
    "org.bouncycastle" % "bcpg-jdk16" % "1.46",
    "com.decodified" %% "scala-ssh" % "0.7.0" exclude ("org.bouncycastle", "bcpkix-jdk15on"),
    "ch.qos.logback" % "logback-classic" % "1.1.2",
    "com.amazonaws" % "aws-java-sdk-core" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-s3" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-ec2" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-lambda" % Versions.aws,
    "com.amazonaws" % "aws-java-sdk-cloudformation" % Versions.aws,
    "com.github.scala-incubator.io" %% "scala-io-core" % "0.4.3",
    "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3",
    "com.gu" %% "management" % Versions.guardianManagement,
    "com.gu" %% "fastly-api-client" % "0.2.5",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.7.1",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.7.1",
    "org.mockito" % "mockito-core" % "1.10.19" % "test"
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

  val riffRaffDeps = commonDeps ++ Seq(
    "com.gu" %% "management-play" % Versions.guardianManagementPlay exclude("javassist", "javassist"), // http://code.google.com/p/reflections/issues/detail?id=140
    "com.gu" %% "management-logback" % Versions.guardianManagement,
    "com.gu" %% "configuration" % "4.0",
    "com.gu" %% "play-googleauth" % "0.2.2" exclude("com.google.guava", "guava-jdk5"),
    "org.mongodb" %% "casbah" % "3.1.0",
    "org.pircbotx" % "pircbotx" % "1.7",
    "com.typesafe.akka" %% "akka-agent" % "2.3.8",
    "org.clapper" %% "markwrap" % "1.0.2",
    "com.adrianhurt" %% "play-bootstrap3" % "0.3",
    "com.gu" %% "scanamo" % "0.6.0",
    "com.amazonaws" % "aws-java-sdk-dynamodb" % Versions.aws,
    filters,
    ws,
    "org.scalatest" %% "scalatest" % "2.2.3" % "test",
    "org.scalatestplus" %% "play" % "1.2.0" % Test,
    "com.typesafe.akka" %% "akka-testkit" % "2.3.8" % Test
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

}
