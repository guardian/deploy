resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.7")

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.4")
addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.1")

addSbtPlugin("com.gu" % "sbt-teamcity-test-reporting-plugin" % "1.5")
addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "0.8.3")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")
