package controllers

import java.security.SecureRandom
import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import com.mongodb.casbah.Imports._
import deployment.{ApiRequestSource, DeployFilter, Deployments, Record}
import magenta._
import magenta.deployment_type.DeploymentType
import magenta.input.All
import magenta.input.resolver.{Resolver => YamlResolver}
import org.joda.time.{DateTime, LocalDate}
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.functional.syntax._
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, AnyContent, BodyParser, Controller, Result}
import utils.Json.DefaultJodaDateWrites
import utils.{ChangeFreeze, Graph}

case class ApiKey(
    application: String,
    key: String,
    issuedBy: String,
    created: DateTime,
    lastUsed: Option[DateTime] = None,
    callCounters: Map[String, Long] = Map.empty
) {
  lazy val totalCalls = callCounters.values.fold(0L) { _ + _ }
}

object ApiKey extends MongoSerialisable[ApiKey] {
  implicit val keyFormat: MongoFormat[ApiKey] = new KeyMongoFormat
  private class KeyMongoFormat extends MongoFormat[ApiKey] with Logging {
    def toDBO(a: ApiKey) = {
      val fields: List[(String, Any)] =
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

    def fromDBO(dbo: MongoDBObject) =
      Some(
        ApiKey(
          application = dbo.as[String]("application"),
          key = dbo.as[String]("_id"),
          issuedBy = dbo.as[String]("issuedBy"),
          created = dbo.as[DateTime]("created"),
          lastUsed = dbo.getAs[DateTime]("lastUsed"),
          callCounters = dbo
            .as[DBObject]("callCounters")
            .map { entry =>
              val key = entry._1
              val counter = try {
                entry._2.asInstanceOf[Long]
              } catch {
                case cce: ClassCastException =>
                  log.warn("Automatically marshalling an Int to a Long (you should only see this during unit tests)")
                  entry._2.asInstanceOf[Int].toLong
              }
              key -> counter
            }
            .toMap
        ))
  }
}

object ApiKeyGenerator {
  lazy val secureRandom = new SecureRandom()

  def newKey(length: Int = 32): String = {
    val rawData = new Array[Byte](length)
    secureRandom.nextBytes(rawData)
    rawData.map { byteData =>
      val char = byteData & 63
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

class Api(deployments: Deployments, deploymentTypes: Seq[DeploymentType])(implicit val messagesApi: MessagesApi,
                                                                          val wsClient: WSClient)
    extends Controller
    with Logging
    with LoginActions
    with I18nSupport {

  object ApiJsonEndpoint {
    val INTERNAL_KEY = ApiKey("internal", "n/a", "n/a", new DateTime())

    def apply[A](authenticatedRequest: ApiRequest[A])(f: ApiRequest[A] => JsValue): Result = {
      val format = authenticatedRequest.queryString.get("format").toSeq.flatten
      val jsonpCallback = authenticatedRequest.queryString.get("callback").map(_.head)

      val response = try {
        f(authenticatedRequest)
      } catch {
        case t: Throwable =>
          toJson(
            Map(
              "response" -> toJson(
                Map(
                  "status" -> toJson("error"),
                  "message" -> toJson(t.getMessage),
                  "stacktrace" -> toJson(t.getStackTrace.map(_.toString))
                ))
            ))
      }

      val responseObject = response match {
        case jso: JsObject => jso
        case jsv: JsValue => JsObject(Seq(("value", jsv)))
      }

      jsonpCallback map { callback =>
        Ok("%s(%s)" format (callback, responseObject.toString)).as("application/javascript")
      } getOrElse {
        response \ "response" \ "status" match {
          case JsDefined(JsString("ok")) => Ok(responseObject)
          case JsDefined(JsString("error")) => BadRequest(responseObject)
          case _ => throw new IllegalStateException("Response status missing or invalid")
        }
      }
    }

    def apply[A](counter: String, p: BodyParser[A])(f: ApiRequest[A] => JsValue): Action[A] =
      ApiAuthAction(counter, p) { apiRequest =>
        this.apply(apiRequest)(f)
      }

    def apply(counter: String)(f: ApiRequest[AnyContent] => JsValue): Action[AnyContent] =
      this.apply(counter, parse.anyContent)(f)

    def withAuthAccess(f: ApiRequest[AnyContent] => JsValue): Action[AnyContent] = AuthAction { request =>
      val apiRequest: ApiRequest[AnyContent] = new ApiRequest[AnyContent](INTERNAL_KEY, request)
      this.apply(apiRequest)(f)
    }
  }

  val applicationForm = Form(
    "application" -> nonEmptyText.verifying("Application name already exists",
                                            Persistence.store.getApiKeyByApplication(_).isEmpty)
  )

  val apiKeyForm = Form(
    "key" -> nonEmptyText
  )

  def createKeyForm = AuthAction { implicit request =>
    Ok(views.html.api.form(applicationForm))
  }

  def createKey = AuthAction { implicit request =>
    applicationForm
      .bindFromRequest()
      .fold(
        errors => BadRequest(views.html.api.form(errors)),
        applicationName => {
          val randomKey = ApiKeyGenerator.newKey()
          val key = ApiKey(applicationName, randomKey, request.user.fullName, new DateTime())
          Persistence.store.createApiKey(key)
          Redirect(routes.Api.listKeys)
        }
      )
  }

  def listKeys = AuthAction { implicit request =>
    Ok(views.html.api.list(request, Persistence.store.getApiKeyList))
  }

  def delete = AuthAction { implicit request =>
    apiKeyForm
      .bindFromRequest()
      .fold(
        errors => Redirect(routes.Api.listKeys()),
        apiKey => {
          Persistence.store.deleteApiKey(apiKey)
          Redirect(routes.Api.listKeys())
        }
      )
  }

  def historyGraph = ApiJsonEndpoint.withAuthAccess { implicit request =>
    val filter = deployment.DeployFilter
      .fromRequest(request)
      .map(_.withMaxDaysAgo(Some(90)))
      .orElse(Some(DeployFilter(maxDaysAgo = Some(30))))
    val count = Deployments.countDeploys(filter)
    val pagination = deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count)).withPageSize(None)
    val deployList = Deployments.getDeploys(filter, pagination.pagination, fetchLogs = false)

    def description(state: RunState.Value) =
      state + " deploys" + filter
        .map { f =>
          f.projectName.map(" of " + _).getOrElse("") + f.stage.map(" in " + _).getOrElse("")
        }
        .getOrElse("")

    implicit val dateOrdering = new Ordering[LocalDate] {
      override def compare(x: LocalDate, y: LocalDate): Int = x.compareTo(y)
    }
    val allDataByDay = deployList.groupBy(_.time.toLocalDate).mapValues(_.size).toList.sortBy {
      case (day, _) => day
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

    val deploys = deploysByState.map {
      case (state, deploysInThatState) =>
        val seriesDataByDay = deploysInThatState.groupBy(_.time.toLocalDate).mapValues(_.size).toList.sortBy {
          case (day, _) => day
        }
        val seriesJson = Graph.zeroFillDays(seriesDataByDay, firstDate, lastDate).map {
          case (day, deploysOnThatDay) =>
            toJson(
              Map(
                "x" -> toJson(day.toDateTimeAtStartOfDay.getMillis / 1000),
                "y" -> toJson(deploysOnThatDay)
              ))
        }
        Map(
          "data" -> toJson(seriesJson),
          "points" -> toJson(seriesJson.length),
          "deploystate" -> toJson(state.toString),
          "name" -> toJson(description(state))
        )
    }

    toJson(
      Map(
        "response" -> toJson(
          Map(
            "series" -> toJson(deploys),
            "status" -> toJson("ok")
          ))))
  }

  def record2apiResponse(deploy: Record)(implicit request: ApiRequest[AnyContent]) =
    Json.obj(
      "time" -> deploy.time,
      "uuid" -> deploy.uuid.toString,
      "projectName" -> deploy.parameters.build.projectName,
      "build" -> deploy.parameters.build.id,
      "stage" -> deploy.parameters.stage.name,
      "deployer" -> deploy.parameters.deployer.name,
      "recipe" -> deploy.parameters.recipe.name,
      "status" -> deploy.state.toString,
      "logURL" -> routes.DeployController.viewUUID(deploy.uuid.toString).absoluteURL(),
      "tags" -> toJson(deploy.allMetaData)
    )

  def history = ApiJsonEndpoint("history") { implicit request =>
    val filter = deployment.DeployFilter.fromRequest(request)
    val count = Deployments.countDeploys(filter)
    val pagination = deployment.DeployFilterPagination.fromRequest.withItemCount(Some(count))
    val deployList = Deployments.getDeploys(filter, pagination.pagination, fetchLogs = false).reverse

    val deploys = deployList.map { record2apiResponse }
    val response = Map(
      "response" -> toJson(
        Map(
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

  val deployRequestReader =
    (__ \ "project").read[String] and
      (__ \ "build").read[String] and
      (__ \ "stage").read[String] and
      (__ \ "recipe").readNullable[String] and
      (__ \ "hosts").readNullable[List[String]] tupled

  def deploy = ApiJsonEndpoint("deploy", parse.json) { implicit request =>
    deployRequestReader
      .reads(request.body)
      .fold(
        valid = { deployRequest =>
          val (project, build, stage, recipeOption, hostsOption) = deployRequest
          val recipe = recipeOption.map(RecipeName).getOrElse(DefaultRecipe())
          val hosts = hostsOption.getOrElse(Nil)
          val params = DeployParameters(
            Deployer(request.fullName),
            Build(project, build),
            Stage(stage),
            recipe,
            Nil,
            hosts
          )
          assert(
            !ChangeFreeze.frozen(stage),
            s"Deployment to $stage is frozen (API disabled, use the web interface if you need to deploy): ${ChangeFreeze.message}")

          deployments.deploy(params, requestSource = ApiRequestSource(request.apiKey)) match {
            case Right(deployId) =>
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
                  "uuid" -> deployId.toString,
                  "logURL" -> routes.DeployController.viewUUID(deployId.toString).absoluteURL()
                )
              )
            case Left(error) =>
              Json.obj(
                "response" -> Json.obj(
                  "status" -> "error",
                  "errors" -> Json.arr(error.message)
                )
              )
          }
        },
        invalid = { error =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "error",
              "errors" -> JsError.toJson(error)
            )
          )
        }
      )
  }

  def view(uuid: String) = ApiJsonEndpoint("viewDeploy") { implicit request =>
    val record = Deployments.get(UUID.fromString(uuid), fetchLog = false)
    Json.obj(
      "response" -> Json.obj(
        "status" -> "ok",
        "deploy" -> record2apiResponse(record)
      )
    )
  }

  def stop = ApiJsonEndpoint("stopDeploy") { implicit request =>
    Form("uuid" -> nonEmptyText).bindFromRequest.fold(
      errors => throw new IllegalArgumentException("No UUID specified"),
      uuid => {
        val record = Deployments.get(UUID.fromString(uuid), fetchLog = false)
        assert(!record.isDone, "Can't stop a deploy that has already completed")
        deployments.stop(UUID.fromString(uuid), request.fullName)
        Json.obj(
          "response" -> Json.obj(
            "status" -> "ok",
            "message" -> "Set stop flag for deploy",
            "uuid" -> uuid
          )
        )
      }
    )
  }

  def validate = ApiJsonEndpoint("validate") { implicit request =>
    request.body.asText.fold {
      Json.obj(
        "response" -> Json.obj(
          "status" -> "error",
          "errors" -> Json.arr("No configuration provided in request body")
        )
      )
    } { body =>
      val validatedGraph = YamlResolver.resolveDeploymentGraph(body, deploymentTypes, All)
      validatedGraph match {
        case Valid(graph) =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "ok",
              "result" -> "passed",
              "deployments" -> graph.toList.map { deployment =>
                Json.obj(
                  "name" -> deployment.name,
                  "type" -> deployment.`type`,
                  "stacks" -> deployment.stacks.toList,
                  "regions" -> deployment.regions.toList,
                  "actions" -> deployment.actions.toList,
                  "app" -> deployment.app,
                  "contentDirectory" -> deployment.contentDirectory,
                  "dependencies" -> deployment.dependencies,
                  "parameters" -> deployment.parameters
                )
              }
            )
          )
        case Invalid(errors) =>
          Json.obj(
            "response" -> Json.obj(
              "status" -> "ok",
              "result" -> "failed",
              "errors" -> errors.errors.toList.map { err =>
                Json.obj(
                  "context" -> err.context,
                  "message" -> err.message
                )
              }
            )
          )
      }
    }
  }

}
