package notification

import java.util.UUID

import com.gu.scanamo.DynamoFormat
import com.mongodb.casbah.Imports._
import magenta._
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import persistence.{DeployRecordDocument, AllDocument, ParametersDocument}
import play.api.libs.ws.WSAuthScheme
import play.api.libs.ws.ahc.AhcWSClient

class HooksTest extends FlatSpec with Matchers with BeforeAndAfterAll {

  implicit val wsClient = new AhcWSClient(new DefaultAsyncHttpClientConfig.Builder().build())(materializer = null)

  override protected def afterAll(): Unit = {
    super.afterAll()
    wsClient.close()
  }

  it should "create an authenticated request" in {
    val action = HookConfig("testProject", "TEST", "http://simon:bobbins@localhost:80/test", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.auth should be(Some(("simon", "bobbins", WSAuthScheme.BASIC)))
  }

  it should "create a plain request" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.auth should be(None)
  }

  it should "substitute parameters" in {
    val action = HookConfig("testProject", "TEST", "http://localhost:80/test?build=%deploy.build%", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?build=23")
  }

  it should "escape substitute parameters" in {
    val action =
      HookConfig("testProject", "TEST", "http://localhost:80/test?project=%deploy.project%", true, "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?project=test%3A%3Aproject")
  }

  it should "substitute tag parameters" in {
    val action = HookConfig("testProject",
                            "TEST",
                            "http://localhost:80/test?build=%deploy.build%&sha=%deploy.tag.vcsRevision%",
                            true,
                            "Mr. Tester")
    val req = action.request(testDeployParams)
    req.url should be("http://localhost:80/test?build=23&sha=9110598b83a908d7882ac4e3cd4b643d7d8bc54e")
  }

  val testUUID = UUID.fromString("758fa00e-e9da-41e0-b31f-1af417e333a1")
  val startTime = new DateTime(2013, 9, 23, 13, 23, 33)
  val testDeployParams = DeployRecordDocument(
    testUUID,
    Some(testUUID.toString),
    startTime,
    ParametersDocument(
      "Mr. Tester",
      "test::project",
      "23",
      "TEST",
      "default",
      Nil,
      List("host1.dom", "host2.dom"),
      Map("vcsRevision" -> "9110598b83a908d7882ac4e3cd4b643d7d8bc54e", "riffraff-domain" -> "10-252-94-200"),
      AllDocument
    ),
    RunState.Completed
  )
}
