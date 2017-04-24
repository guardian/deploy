package lookup

import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, AnyContent, Request}
import play.api.mvc.Results._
import play.api.routing.sird._
import play.api.test.WsTestClient
import play.core.server.Server
import resources.{Image, PrismLookup}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class PrismLookupTest extends FlatSpec with Matchers {

  def withPrismClient[T](images: List[Image])(block: WSClient => T): (T, Option[Request[AnyContent]]) = {
    var mockRequest: Option[Request[AnyContent]] = None
    val result = Server.withRouter() {
      case GET(p"/images") =>
        Action { request =>
          mockRequest = Some(request)
          Ok(
            Json.obj(
              "data" -> Json.obj(
                "images" -> Json.toJson(images)
              )
            ))
        }
    } { implicit port =>
      WsTestClient.withClient { client =>
        block(client)
      }
    }
    (result, mockRequest)
  }

  "PrismLookup" should "return latest image" in {
    val images = List(
      Image("test-ami", new DateTime(2017, 3, 2, 13, 32, 0)),
      Image("test-later-ami", new DateTime(2017, 4, 2, 13, 32, 0)),
      Image("test-later-still-ami", new DateTime(2017, 5, 2, 13, 32, 0)),
      Image("test-early-ami", new DateTime(2017, 1, 2, 13, 32, 0))
    )
    withPrismClient(images) { client =>
      val lookup = new PrismLookup(client, "", 10 seconds)
      val result = lookup.getLatestAmi("bob")(Map.empty)
      result shouldBe Some("test-later-still-ami")
    }
  }

  it should "narrows ami query by region" in {
    val (result, request) = withPrismClient(Nil) { client =>
      val lookup = new PrismLookup(client, "", 10 seconds)
      lookup.getLatestAmi("bob")(Map.empty)
    }
    result shouldBe None
    request.flatMap(_.getQueryString("region")) shouldBe Some("bob")
  }

  it should "correctly query using the tags" in {
    val (result, request) = withPrismClient(Nil) { client =>
      val lookup = new PrismLookup(client, "", 10 seconds)
      lookup.getLatestAmi("bob")(Map("tagName" -> "tagValue?", "tagName*" -> "tagValue2"))
    }
    request.map(_.queryString) shouldBe Some(
      Map(
        "region" -> ArrayBuffer("bob"),
        "tags.tagName" -> ArrayBuffer("tagValue?"),
        "tags.tagName*" -> ArrayBuffer("tagValue2")
      ))
  }
}
