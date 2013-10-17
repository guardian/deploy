package magenta

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.io.File
import tasks._
import tasks.BlockFirewall
import tasks.CheckUrls
import tasks.CopyFile
import tasks.Restart
import tasks.WaitForPort
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.{JArray, JString}
import magenta.deployment_type.ResinWebapp

class ResinWebappTest extends FlatSpec with ShouldMatchers {
  "resin web app package type" should "have a deploy action" in {
    val p = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))

    val host = Host("host_name")

    ResinWebapp.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "resin"),
      CopyFile(host as "resin", "/tmp/packages/webapp/", "/resin-apps/webapp/"),
      Restart(host as "resin", "webapp"),
      WaitForPort(host, 8080, 1 minute),
      CheckUrls(host, 8080, List("/webapp/management/healthcheck"), 2 minutes, 5),
      UnblockFirewall(host as "resin")
    ))
  }

  it should "allow port to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))
    ResinWebapp.port(basic) should be (8080)

    val overridden = Package("webapp", Set.empty, Map("port" -> "80"), "resin-webapp", new File("/tmp/packages/webapp"))
    ResinWebapp.port(overridden) should be (80)
  }

  it should "check urls when specified" in {
    val urls = List("/test", "/xx")
    val urls_json = JArray(urls map { JString(_)})

    val p = Package("webapp", Set.empty, Map("healthcheck_paths" -> urls_json), "resin-webapp", new File("/tmp/packages/webapp"))
    val host = Host("host_name")

    ResinWebapp.perHostActions("deploy")(p)(host) should be (List(
      BlockFirewall(host as "resin"),
      CopyFile(host as "resin", "/tmp/packages/webapp/", "/resin-apps/webapp/"),
      Restart(host as "resin", "webapp"),
      WaitForPort(host, 8080, 1 minute),
      CheckUrls(host, 8080, urls, 2 minutes, 5),
      UnblockFirewall(host as "resin")
    ))
  }

  it should "allow wait and check times to be overriden" in {
    val basic = Package("webapp", Set.empty, Map.empty, "resin-webapp", new File("/tmp/packages/webapp"))
    ResinWebapp.waitseconds(basic) should be (60)
    ResinWebapp.checkseconds(basic) should be (120)

    val overridden = Package("webapp", Set.empty, Map("waitseconds" -> 120, "checkseconds" -> 60), "resin-webapp", new File("/tmp/packages/webapp"))
    ResinWebapp.waitseconds(overridden) should be(120)
    ResinWebapp.checkseconds(overridden) should be(60)
  }


}
