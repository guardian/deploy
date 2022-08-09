import play.sbt.PlayImport._
import sbt._

object Dependencies {

  object Versions {
    val aws = "2.17.109"
    val jackson = "2.13.3"
    val awsRds = "1.12.276"
    val enumeratumPlay = "1.7.0"
  }

  // https://github.com/orgs/playframework/discussions/11222
  private val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core"     % "jackson-core",
    "com.fasterxml.jackson.core"     % "jackson-annotations",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310",
    "com.fasterxml.jackson.core" % "jackson-databind",
  ).map(_ % Versions.jackson)

  private val akkaSerializationJacksonOverrides = Seq(
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module"     % "jackson-module-parameter-names",
    "com.fasterxml.jackson.module"     %% "jackson-module-scala",
  ).map(_ % Versions.jackson)

  val commonDeps = Seq(
    "io.reactivex" %% "rxscala" % "0.27.0",
    "org.parboiled" %% "parboiled" % "2.1.8",
    "org.typelevel" %% "cats-core" % "2.8.0",
    "org.scalatest" %% "scalatest" % "3.2.12" % Test,
    "org.mockito" %% "mockito-scala" % "1.17.12" % Test
  )

  val magentaLibDeps = commonDeps ++ jacksonOverrides ++ akkaSerializationJacksonOverrides ++ Seq(
    "com.squareup.okhttp3" % "okhttp" % "4.10.0",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.61",
    "org.bouncycastle" % "bcpg-jdk15on" % "1.61",
    "ch.qos.logback" % "logback-classic" % "1.2.11",
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
    "com.gu" %% "fastly-api-client" % "0.4.1",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson,
    "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
    "com.typesafe.play" %% "play-json" % "2.9.2",
    "com.beachape" %% "enumeratum-play-json" % Versions.enumeratumPlay,
    "com.google.apis" % "google-api-services-deploymentmanager" % "v2-rev20220714-2.0.0",
    "com.google.cloud" % "google-cloud-storage" % "2.11.3",
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )

  val riffRaffDeps = commonDeps ++ jacksonOverrides ++ akkaSerializationJacksonOverrides ++ Seq(
    evolutions,
    jdbc,
    "com.gu.play-googleauth" %% "play-v28" % "2.2.6",
    "com.gu.play-secret-rotation" %% "play-v28" % "0.33",
    "com.gu.play-secret-rotation" %% "aws-parameterstore-sdk-v2" % "0.33",
    "com.typesafe.akka" %% "akka-agent" % "2.5.32",
    "org.pegdown" % "pegdown" % "1.6.0",
    "com.adrianhurt" %% "play-bootstrap" % "1.6.1-P28-B4",
    "org.scanamo" %% "scanamo" % "1.0.0-M11",
    "software.amazon.awssdk" % "dynamodb" % Versions.aws,
    "software.amazon.awssdk" % "sns" % Versions.aws,
    "org.quartz-scheduler" % "quartz" % "2.3.2",
    "com.gu" %% "anghammarad-client" % "1.2.0",
    "org.webjars" %% "webjars-play" % "2.8.13",
    "org.webjars" % "jquery" % "3.1.1",
    "org.webjars" % "jquery-ui" % "1.13.2",
    "org.webjars" % "bootstrap" % "3.3.7",
    "org.webjars" % "jasny-bootstrap" % "3.1.3-2",
    "org.webjars" % "momentjs" % "2.16.0",
    "net.logstash.logback" % "logstash-logback-encoder" % "7.2",
    "com.gu" % "kinesis-logback-appender" % "2.0.3",
    "org.slf4j" % "jul-to-slf4j" % "1.7.36",
    "org.scalikejdbc" %% "scalikejdbc" % "3.5.0",
    "org.postgresql" % "postgresql" % "42.4.1",
    "com.beachape" %% "enumeratum-play" % Versions.enumeratumPlay,
    filters,
    ws,
    "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % Test,
    "com.amazonaws" % "aws-java-sdk-rds" % Versions.awsRds
  ).map((m: ModuleID) =>
    // don't even ask why I need to do this
    m.excludeAll(ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305"))
  )
}
