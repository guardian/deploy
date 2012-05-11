import sbt._
import Keys._
import Defaults._
import sbtassembly.Plugin._
import AssemblyKeys._
import PlayProject.{SCALA,dist}
import com.gu.PlayArtifact._

object MagentaBuild extends Build {
  lazy val root = Project("root", file(".")) aggregate (lib, cli, riffraff)

  lazy val lib = magentaProject("magenta-lib")

  lazy val cli = magentaProject("magenta-cli") dependsOn(lib)

  lazy val riffraff = magentaPlayProject("riff-raff") dependsOn(lib)

  val liftVersion = "2.4-M4"

  def magentaProject(name: String) = Project(name, file(name), settings = defaultSettings ++ magentaSettings)

  def magentaPlayProject(name: String) = PlayProject(name, magentaVersion, path=file(name), mainLang=SCALA)
    .settings( playArtifactDistSettings: _* )
    .settings( magentaSettings: _* )
    .settings(
      testOptions in Test := Nil,
      jarName in assembly := "%s.jar" format name
  )


  val magentaSettings: Seq[Setting[_]] = Seq(
    scalaVersion := "2.9.1",
    scalacOptions ++= Seq("-deprecation"),
    version := magentaVersion
  )

  val magentaVersion = "1.0"
}