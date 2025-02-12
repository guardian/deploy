import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "2.29.52"
    val jackson = "2.18.2"
    val awsRds = "1.12.780"
    val enumeratumPlay = "1.8.2"
  }

  // https://github.com/orgs/playframework/discussions/11222
  // We no longer have any vulnerabilities through Jackson but still need to define jackson dependencies.
  // Jackson does not like having different versions of its packages installed.
  private val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core" % "jackson-core",
    "com.fasterxml.jackson.core" % "jackson-annotations",
    "com.fasterxml.jackson.core" % "jackson-databind",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module" % "jackson-module-parameter-names",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"
  ).map(_ % Versions.jackson)

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.27.0",
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "org.parboiled" %% "parboiled" % "2.5.1",
    "org.typelevel" %% "cats-core" % "2.12.0",
    "org.mockito" %% "mockito-scala" % "1.17.37" % Test
  )

  val magentaLibDeps =
    commonDeps ++ jacksonOverrides ++ Seq(
      "com.squareup.okhttp3" % "okhttp" % "4.12.0",
      "software.amazon.awssdk" % "core" % Versions.aws,
      "software.amazon.awssdk" % "autoscaling" % Versions.aws,
      "software.amazon.awssdk" % "s3" % Versions.aws,
      "software.amazon.awssdk" % "ec2" % Versions.aws,
      "software.amazon.awssdk" % "elasticloadbalancing" % Versions.aws,
      "software.amazon.awssdk" % "elasticloadbalancingv2" % Versions.aws,
      "software.amazon.awssdk" % "lambda" % Versions.aws,
      "software.amazon.awssdk" % "cloudformation" % Versions.aws,
      "software.amazon.awssdk" % "sts" % Versions.aws,
      "software.amazon.awssdk" % "ssm" % Versions.aws,
      "com.gu" %% "fastly-api-client" % "1.0.1",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
      "com.beachape" %% "enumeratum-play-json" % Versions.enumeratumPlay,
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0"
    ).map((m: ModuleID) =>
      // don't even ask why I need to do this
      m.excludeAll(
        ExclusionRule(
          organization = "com.google.code.findbugs",
          name = "jsr305"
        )
      )
    )

  val riffRaffDeps =
    commonDeps ++ jacksonOverrides ++ Seq(
      evolutions,
      jdbc,
      "com.gu.play-googleauth" %% "play-v30" % "19.0.0",
      "com.gu.play-secret-rotation" %% "play-v30" % "13.1.2",
      "com.gu.play-secret-rotation" %% "aws-parameterstore-sdk-v2" % "13.1.2",
      "org.pegdown" % "pegdown" % "1.6.0",
      "com.adrianhurt" %% "play-bootstrap" % "1.6.1-P28-B3", // scala-steward:off,
      "org.scanamo" %% "scanamo" % "3.0.0",
      "software.amazon.awssdk" % "dynamodb" % Versions.aws,
      "software.amazon.awssdk" % "sns" % Versions.aws,
      "org.quartz-scheduler" % "quartz" % "2.3.2",
      "com.gu" %% "anghammarad-client" % "4.0.0",
      "org.webjars" %% "webjars-play" % "3.0.2",
      "org.webjars" % "jquery" % "3.7.1",
      "org.webjars" % "jquery-ui" % "1.14.1",
      "org.webjars" % "bootstrap" % "3.4.1", // scala-steward:off
      "org.webjars" % "jasny-bootstrap" % "3.1.3-2", // scala-steward:off
      "org.webjars" % "momentjs" % "2.30.1",
      "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
      "org.scalikejdbc" %% "scalikejdbc" % "3.5.0", // scala-steward:off
      "org.postgresql" % "postgresql" % "42.7.5",
      "com.beachape" %% "enumeratum-play" % Versions.enumeratumPlay,
      filters,
      ws,
      "org.apache.pekko" %% "pekko-testkit" % "1.0.3" % Test,
      "com.amazonaws" % "aws-java-sdk-rds" % Versions.awsRds,
      "org.scala-stm" %% "scala-stm" % "0.11.1",
      // Play 3.0 currently uses logback-classic 1.4.11 which is vulnerable to CVE-2023-45960
      "ch.qos.logback" % "logback-classic" % "1.5.16"
    ).map((m: ModuleID) =>
      // don't even ask why I need to do this
      m.excludeAll(
        ExclusionRule(
          organization = "com.google.code.findbugs",
          name = "jsr305"
        )
      )
    )
}
