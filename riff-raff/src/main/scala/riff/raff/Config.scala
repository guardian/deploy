package riff.raff

import magenta.json._


object Config {

  lazy val stages = List("CODE", "QA", "TEST", "RELEASE", "STAGE", "PROD")

  // TODO: this needs to be re-read on a schedule
  lazy val parsedDeployInfo = {
    import sys.process._
    new DeployInfoJsonHostProvider("contrib/deployinfo.json".!!).hosts
   }

}