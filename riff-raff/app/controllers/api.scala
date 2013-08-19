package controllers

import play.api.mvc.{BodyParser, Action, AnyContent, Controller}
import play.api.mvc.Results._
import org.joda.time.{DateMidnight, DateTime}
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import play.api.data._
import play.api.data.Forms._
import java.security.SecureRandom
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.mongodb.casbah.Imports._
import deployment.{Record, DeployFilter, DeployInfoManager}
import utils.Graph
import magenta._
import play.api.mvc.BodyParsers.parse
import java.util.UUID

case class ApiKey(
  application:String,
  key:String,
  issuedBy:String,
  created:DateTime,
  lastUsed:Option[DateTime] = None,
  callCounters:Map[String, Long] = Map.empty
){
  lazy val totalCalls = callCounters.values.fold(0L){_+_}
}

object ApiKey extends MongoSerialisable[ApiKey] {
  implicit val keyFormat:MongoFormat[ApiKey] = new KeyMongoFormat
  private class KeyMongoFormat extends MongoFormat[ApiKey] with Logging {
    def toDBO(a: ApiKey) = {
      val fields:List[(String,Any)] =
        List(
          "application" -> a.application,
          "_id" -> a.key,
          "issuedBy" -> a.issuedBy,
          "created" -> a.created
        ) ++ a.lastUsed.map("lastUsed" ->) ++
          List(
            "callCounters" -> a.callCounters.asDBObject
          )
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = Some(ApiKey(
      application = dbo.as[String]("application"),
      key = dbo.as[String]("_id"),
      issuedBy = dbo.as[String]("issuedBy"),
      created = dbo.as[DateTime]("created"),
      lastUsed = dbo.getAs[DateTime]("lastUsed"),
      callCounters = dbo.as[DBObject]("callCounters").map { entry =>
        val key = entry._1
        val counter = try {
          entry._2.asInstanceOf[Long]
        } catch {
          case cce:ClassCastException =>
            log.warn("Automatically marshalling an Int to a Long (you should only see this during unit tests)")
            entry._2.asInstanceOf[Int].toLong
        }
        key -> counter
      }.toMap
    ))
  }
}

object ApiKeyGenerator {
  lazy val secureRandom = new SecureRandom()

  def newKey(length: Int = 32): String = {
    val rawData = new Array[Byte](length)
    secureRandom.nextBytes(rawData)
    rawData.map{ byteData =>
      val char = (byteData & 63)
      char match {
        case lower if lower < 26 => ('a' + lower).toChar
        case upper if upper >= 26 && upper < 52 => ('A' + (upper - 26)).toChar
        case numeral if numeral >= 52 && numeral < 62 => ('0' + (numeral - 52)).toChar
        case hyphen if hyphen == 62 => '-'
        case underscore if underscore == 63 => '_'
        case default =>
          throw new IllegalStateException("byte value out of expected range")
      }
    }.mkString
  }

}

object ApiJsonEndpoint {
  def apply[A](counter: String, p: BodyParser[A])(f: AuthenticatedRequest[A] => JsValue): Action[A] = {
    ApiAuthAction(counter, p) { authenticatedRequest =>
      val format = authenticatedRequest.queryString.get("format").toSeq.flatten
      val jsonpCallback = authenticatedRequest.queryString.get("callback").map(_.head)

      val response = try {
        f(authenticatedRequest)
      } catch {
        case t:Throwable =>
          toJson(Map(
            "response" -> toJson(Map(
              "status" -> toJson("error"),
              "message" -> toJson(t.getMessage),
              "stacktrace" -> toJson(t.getStackTraceString.split("\n"))
            ))
          ))
      }

      val responseObject = response match {
        case jso:JsObject => jso
        case jsv:JsValue => JsObject(Seq(("value", jsv)))
      }

      jsonpCallback map { callback =>
        Ok("%s(%s)" format (callback, responseObject.toString)).as("application/javascript")
      } getOrElse {
        response \ "response" \ "status" match {
          case JsString("ok") => Ok(responseObject)
          case JsString("error") => BadRequest(responseObject)
          case _ => throw new IllegalStateException("Response status missing or invalid")
        }
      }
    }
  }
  def apply(counter: String)(f: AuthenticatedRequest[AnyContent] => JsValue): Action[AnyContent] = {
    this.apply(counter, parse.anyContent)(f)
  }
}

object Api extends Controller with Logging {

  val applicationForm = Form(
    "application" -> nonEmptyText.verifying("Application name already exists", Persistence.store.getApiKeyByApplication(_).isEmpty)
  )

  val apiKeyForm = Form(
    "key" -> nonEmptyText
  )

  def createKeyForm = AuthAction { implicit request =>
    Ok(views.html.api.form(request, applicationForm))
  }

  def createKey = AuthAction { implicit request =>
    applicationForm.bindFromRequest().fold(
      errors => BadRequest(views.html.api.form(request, errors)),
      applicationName => {
        val randomKey = ApiKeyGenerator.newKey()
        val key = ApiKey(applicationName, randomKey, request.identity.get.fullName, new DateTime())
        Persistence.store.createApiKey(key)
        Redirect(routes.Api.listKeys)
      }
    )
  }

  def listKeys = AuthAction { implicit request =>
    Ok(views.html.api.list(request, Persistence.store.getApiKeyList))
  }

  def delete = AuthAction { implicit request =>
    apiKeyForm.bindFromRequest().fold(
      errors => Redirect(routes.Api.listKeys()),
      apiKey => {
        Persistence.store.deleteApiKey(apiKey)
        Redirect(routes.Api.listKeys())
      }
    )
  }

  def historyGraph = ApiJsonEndpoint("historyGraph") { implicit request =>
    val filter = deployment.DeployFilter.fromRequest(request).map(_.withMaxDaysAgo(Some(90))).orElse(Some(DeployFilter(maxDaysAgo = Some(30))))
    val count = DeployController.countDeploys(filter)
    val pagination = deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count)).withPageSize(None)
    val deployList = DeployController.getDeploys(filter, pagination.pagination, fetchLogs = false)

    def description(state: RunState.Value) = state + " deploys" + filter.map { f =>
      f.projectName.map(" of " + _).getOrElse("") + f.stage.map(" in " + _).getOrElse("")
    }.getOrElse("")

    val allDataByDay = deployList.groupBy(_.time.toDateMidnight).mapValues(_.size).toList.sortBy {
      case (date, _) => date.getMillis
    }
    val firstDate = allDataByDay.headOption.map(_._1)
    val lastDate = allDataByDay.lastOption.map(_._1)

    val deploysByState = deployList.groupBy(_.state).toList.sortBy {
      case (RunState.Completed, _) => 1
      case (RunState.Failed, _) => 2
      case (RunState.Running, _) => 3
      case (RunState.NotRunning, _) => 4
      case default => 5
    }

    val deploys = deploysByState.map { case (state, deployList) =>
      val seriesDataByDay = deployList.groupBy(_.time.toDateMidnight).mapValues(_.size).toList.sortBy {
        case (date, _) => date.getMillis
      }
      val seriesJson = Graph.zeroFillDays(seriesDataByDay, firstDate, lastDate).map {
        case (day, deploys) =>
          toJson(Map(
            "x" -> toJson(day.getMillis / 1000),
            "y" -> toJson(deploys)
          ))
      }
      Map(
        "data" -> toJson(seriesJson),
        "points" -> toJson(seriesJson.length),
        "deploystate" -> toJson(state.toString),
        "name" -> toJson(description(state))
      )
    }

    toJson(Map("response" -> toJson(Map(
      "series" -> toJson(deploys),
      "status" -> toJson("ok")
    ))))
  }

  def record2apiResponse(deploy:Record)(implicit request: AuthenticatedRequest[AnyContent]) =
    Json.obj(
      "time" -> deploy.time.getMillis,
      "uuid" -> deploy.uuid.toString,
      "taskType" -> deploy.taskType.toString,
      "projectName" -> deploy.parameters.build.projectName,
      "build" -> deploy.parameters.build.id,
      "stage" -> deploy.parameters.stage.name,
      "deployer" -> deploy.parameters.deployer.name,
      "recipe" -> deploy.parameters.recipe.name,
      "status" -> deploy.state.toString,
      "logURL" -> routes.Deployment.viewUUID(deploy.uuid.toString).absoluteURL(),
      "tags" -> toJson(deploy.allMetaData)
    )


  def history = ApiJsonEndpoint("history") { implicit request =>
    val filter = deployment.DeployFilter.fromRequest(request)
    val count = DeployController.countDeploys(filter)
    val pagination = deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count))
    val deployList = DeployController.getDeploys(filter, pagination.pagination, fetchLogs = false).reverse

    val deploys = deployList.map{ record2apiResponse }
    val response = Map(
      "response" -> toJson(Map(
        "status" -> toJson("ok"),
        "total" -> toJson(pagination.itemCount),
        "pageSize" -> toJson(pagination.pageSize),
        "currentPage" -> toJson(pagination.page),
        "pages" -> toJson(pagination.pageCount.get),
        "filter" -> toJson(filter.map(_.queryStringParams.toMap.mapValues(toJson(_))).getOrElse(Map.empty)),
        "results" -> toJson(deploys)
      ))
    )
    toJson(response)
  }

  def deployinfo = ApiJsonEndpoint("deployinfo") { implicit request =>
    assert(!DeployInfoManager.deployInfo.hosts.isEmpty, "No deploy information available")

    val filter = deployment.HostFilter.fromRequest
    val query:List[(String,JsValue)] = Nil ++
      filter.stage.map("stage" -> toJson(_)) ++
      filter.app.map("app" -> toJson(_)) ++
      Some("hostList" -> toJson(filter.hostList))

    import net.liftweb.json.{Serialization,NoTypeHints}
    implicit val format = Serialization.formats(NoTypeHints)
    val filtered = DeployInfoManager.deployInfo.filterHosts { host =>
        (filter.stage.isEmpty || filter.stage.get == host.stage) &&
          (filter.app.isEmpty || host.apps.exists(_.name == filter.app.get) ) &&
          (filter.hostList.isEmpty || filter.hostList.contains(host.name))
      }
    val results = Json.parse(Serialization.write(filtered.input))

    val response = Json.obj(
      "response" -> Json.obj(
        "status" -> "ok",
        "filter" -> toJson(query.toMap),
        "results" -> results
      )
    )
    toJson(response)
  }

  val deployRequestReader =
    (__ \ "project").read[String] and
    (__ \ "build").read[String] and
    (__ \ "stage").read[String] and
    (__ \ "recipe").readNullable[String] and
    (__ \ "hosts").readNullable[List[String]] tupled

  def deploy = ApiJsonEndpoint("deploy", parse.json) { implicit request =>
    deployRequestReader.reads(request.body).fold(
      valid = { deployRequest =>
        val (project, build, stage, recipeOption, hostsOption) = deployRequest
        val recipe = recipeOption.map(RecipeName).getOrElse(DefaultRecipe())
        val hosts = hostsOption.getOrElse(Nil)
        val params = DeployParameters(
          Deployer(request.identity.get.fullName),
          Build(project, build),
          Stage(stage),
          recipe,
          hosts
        )
        val deployId = DeployController.deploy(params)
        Json.obj(
          "response" -> Json.obj(
            "status" -> "ok",
            "request" -> Json.obj(
              "project" -> project,
              "build" -> build,
              "stage" -> stage,
              "recipe" -> recipe.name,
              "hosts" -> toJson(hosts)
            ),
            "uuid" -> deployId.toString
          )
        )
      },
      invalid = { error =>
        Json.obj(
          "response" -> Json.obj(
            "status" -> "error",
            "errors" -> JsError.toFlatJson(error)
          )
        )
      }
    )
  }

  def view(uuid: String) = ApiJsonEndpoint("viewDeploy") { implicit request =>
    val record = DeployController.get(UUID.fromString(uuid), fetchLog = false)
    Json.obj(
      "response" -> Json.obj(
        "status" -> "ok",
        "deploy" -> record2apiResponse(record)
      )
    )
  }

}