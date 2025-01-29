// keep in sync with the play version in Dependencies
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.6")

addSbtPlugin("com.github.sbt" % "sbt-coffeescript" % "2.0.1")

// Scala-steward update disabled for this dependency. For some reason updating to 1.5 causes a weird 'mkdirp' not
// found when compilling less.
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.1.2") // scala-steward:off
addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
