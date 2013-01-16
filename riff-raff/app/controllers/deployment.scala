package controllers

import teamcity._
import play.api.mvc.Controller
import play.api.data.Form
import deployment._
import deployment.Domains.responsibleFor
import deployment.DomainAction._
import play.api.data.Forms._
import java.util.UUID
import akka.actor.ActorSystem
import magenta._
import magenta.Build
import akka.agent.Agent
import akka.util.Timeout
import akka.util.duration._
import play.api.libs.json.Json
import com.codahale.jerkson.Json._
import org.joda.time.format.DateTimeFormat
import persistence.{DocumentStoreConverter}
import lifecycle.LifecycleWithoutApp
import com.gu.management.DefaultSwitch
import conf.AtomicSwitch
import org.joda.time.{Interval, DateTime}

object DeployController extends Logging with LifecycleWithoutApp {
  val sink = new MessageSink {
    def message(message: MessageWrapper) { update(message) }
  }
  def init() { MessageBroker.subscribe(sink) }
  def shutdown() { MessageBroker.unsubscribe(sink) }

  lazy val enableSwitches = List(enableDeploysSwitch, enableQueueingSwitch)

  lazy val enableDeploysSwitch = new AtomicSwitch("enable-deploys", "Enable riff-raff to queue and run deploys.  This switch can only be turned off if no deploys are running.", true) {
    override def switchOff() {
      super.switchOff {
        if (getControllerDeploys.exists(!_.isDone))
          throw new IllegalStateException("Cannot turn switch off as builds are currently running")
      }
    }
  }

  lazy val enableQueueingSwitch = new DefaultSwitch("enable-deploy-queuing", "Enable riff-raff to queue deploys.  Turning this off will prevent anyone queueing a new deploy, although running deploys will continue.", true)

  implicit val system = ActorSystem("deploy")

  val library = Agent(Map.empty[UUID,Agent[DeployV2Record]])

  def create(recordType: TaskType.Value, params: DeployParameters): Record = {
    val uuid = java.util.UUID.randomUUID()
    val record = DeployV2Record(recordType, uuid, params)
    library send { _ + (uuid -> Agent(record)) }
    DocumentStoreConverter.saveDeploy(record)
    await(uuid)
  }

  def update(wrapper: MessageWrapper) {
    Option(library()(wrapper.context.deployId)) foreach { recordAgent =>
      recordAgent send { record =>
        val updated = record + wrapper
        DocumentStoreConverter.saveMessage(wrapper)
        if (record.state != updated.state) DocumentStoreConverter.updateDeployStatus(updated)
        updated
      }
      wrapper.stack.messages match {
        case List(FinishContext(_),Deploy(_)) => cleanup(wrapper.context.deployId)
        case List(FailContext(_),Deploy(_)) => cleanup(wrapper.context.deployId)
        case _ =>
      }
    }
  }

  def cleanup(uuid: UUID) {
    log.debug("Queuing removal of deploy record %s from internal caches" format uuid)
    library sendOff { allDeploys =>
      val timeout = Timeout(10 seconds)
      val record = allDeploys(uuid).await(timeout)
      log.debug("Done removing deploy record %s from internal caches" format uuid)
      allDeploys - record.uuid
    }
  }

  def preview(params: DeployParameters): UUID = deploy(params, TaskType.Preview)

  def deploy(requestedParams: DeployParameters, mode: TaskType.Value = TaskType.Deploy): UUID = {
    if (enableQueueingSwitch.isSwitchedOff)
      throw new IllegalStateException("Unable to queue a new deploy; deploys are currently disabled by the %s switch" format enableQueueingSwitch.name)

    val params = TeamCity.transformLastSuccessful(requestedParams)
    Domains.assertResponsibleFor(params)

    enableDeploysSwitch.whileOnYield {
      val record = DeployController.create(mode, params)
      DeployControlActor.interruptibleDeploy(record)
      record.uuid
    } getOrElse {
      throw new IllegalStateException("Unable to queue a new deploy; deploys are currently disabled by the %s switch" format enableDeploysSwitch.name)
    }
  }

  def stop(uuid: UUID, fullName: String) {
    DeployControlActor.stopDeploy(uuid, fullName)
  }

  def getStopFlag(uuid: UUID) = DeployControlActor.getDeployStopFlag(uuid)

  def getControllerDeploys: Iterable[Record] = { library().values.map{ _() } }
  def getDatastoreDeploys(filter:Option[DeployFilter] = None, pagination: PaginationView, fetchLogs: Boolean): Iterable[Record] =
    DocumentStoreConverter.getDeployList(filter, pagination, fetchLogs)

  def getDeploys(filter:Option[DeployFilter] = None, pagination: PaginationView = PaginationView(), fetchLogs: Boolean = false): List[Record] = {
    require(!fetchLogs || pagination.pageSize.isDefined, "Too much effort required to fetch complete record with no pagination")
    getDatastoreDeploys(filter, pagination, fetchLogs=fetchLogs).toList.sortWith{ _.time.getMillis < _.time.getMillis }
  }

  def countDeploys(filter:Option[DeployFilter]) = DocumentStoreConverter.countDeploys(filter)

  def markAsFailed(record: Record) {
    DocumentStoreConverter.updateDeployStatus(record.uuid, RunState.Failed)
  }

  def get(uuid: UUID, fetchLog: Boolean = true): Record = {
    val agent = library().get(uuid)
    agent.map(_()).getOrElse {
      DocumentStoreConverter.getDeploy(uuid, fetchLog).get
    }
  }

  def await(uuid: UUID): Record = {
    val timeout = Timeout(5 second)
    library.await(timeout)(uuid).await(timeout)
  }
}

case class DeployParameterForm(project:String, build:String, stage:String, recipe: Option[String], action: String, hosts: List[String])
case class UuidForm(uuid:String, action:String)

object Deployment extends Controller with Logging {

  def changeFreeze[A](defrosted: A, frozen: A):A = {
    val freezeInterval = new Interval(new DateTime(2012,12,19,0,1,0), new DateTime(2013,1,7,0,0,0))
    val now = new DateTime()
    if (freezeInterval.contains(now)) frozen else defrosted
  }

  lazy val uuidForm = Form[UuidForm](
    mapping(
      "uuid" -> text(36,36),
      "action" -> nonEmptyText
    )(UuidForm.apply)
      (UuidForm.unapply)
  )

  lazy val deployForm = Form[DeployParameterForm](
    tuple(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> optional(text),
      "manualStage" -> optional(text),
      "recipe" -> optional(text),
      "action" -> nonEmptyText,
      "hosts" -> list(text)
    ).verifying("no.stage.specified", _ match {
      case(_,_, stage, manualStage, _, _,_) => !stage.isEmpty || !manualStage.isEmpty}
    ).transform[DeployParameterForm]({
        case (project, build, Some(stage), _, recipe, action, hosts) =>
          DeployParameterForm(project, build, stage, recipe, action, hosts)
        case (project, build, None, Some(manualStage), recipe, action, hosts) =>
          DeployParameterForm(project, build, manualStage, recipe, action, hosts)
        case _ => throw new Error("Should have failed validation")
      },
      form => (form.project, form.build, Some(form.stage), None, None, form.action, form.hosts )
    )
  )

  def deploy = AuthAction { implicit request =>
    Ok(views.html.deploy.form(request, deployForm))
  }

  def processForm = AuthAction { implicit request =>
    deployForm.bindFromRequest().fold(
      errors => BadRequest(views.html.deploy.form(request,errors)),
      form => {
        log.info("Host list: %s" format form.hosts)
        val parameters = new DeployParameters(Deployer(request.identity.get.fullName),
          Build(form.project,form.build.toString),
          Stage(form.stage),
          recipe = form.recipe.map(RecipeName(_)).getOrElse(DefaultRecipe()),
          hostList = form.hosts)

        form.action match {
          case "preview" =>
            Redirect(routes.Deployment.preview(parameters.build.projectName, parameters.build.id, parameters.stage.name, parameters.recipe.name, parameters.hostList.mkString(",")))
          case "previewOld" =>
            responsibleFor(parameters) match {
              case Local() =>
                val uuid = DeployController.preview(parameters)
                Redirect(routes.Deployment.viewUUID(uuid.toString))
              case Remote(urlPrefix) =>
                val call = routes.Deployment.deployConfirmation(generate(form))
                Redirect(urlPrefix+call.url)
              case Noop() =>
                throw new IllegalArgumentException("There isn't a domain in the riff-raff configuration that can run this preview")
            }
          case "deploy" =>
            responsibleFor(parameters) match {
              case Local() =>
                val uuid = DeployController.deploy(parameters)
                Redirect(routes.Deployment.viewUUID(uuid.toString))
              case Remote(urlPrefix) =>
                val call = routes.Deployment.deployConfirmation(generate(form))
                Redirect(urlPrefix+call.url)
              case Noop() =>
                throw new IllegalArgumentException("There isn't a domain in the riff-raff configuration that can run this deploy")
            }
          case _ => throw new RuntimeException("Unknown action")
        }
      }
    )
  }

  def stop(uuid: String) = AuthAction { implicit request =>
    DeployController.stop(UUID.fromString(uuid), request.identity.get.fullName)
    Redirect(routes.Deployment.viewUUID(uuid))
  }

  def viewUUID(uuidString: String, verbose: Boolean) = AuthAction { implicit request =>
    val uuid = UUID.fromString(uuidString)
    val record = DeployController.get(uuid)
    val stopFlag = DeployController.getStopFlag(uuid).getOrElse(false)
    Ok(views.html.deploy.viewDeploy(request, record, verbose, stopFlag))
  }

  def updatesUUID(uuid: String) = AuthAction { implicit request =>
    val record = DeployController.get(UUID.fromString(uuid))
    record.taskType match {
      case TaskType.Deploy => Ok(views.html.deploy.logContent(request, record))
      case TaskType.Preview => Ok(views.html.deploy.oldPreviewContent(request,record))
    }
  }

  def preview(projectName: String, buildId: String, stage: String, recipe: String, hosts: String) = AuthAction { implicit request =>
    val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
    val parameters = DeployParameters(Deployer(request.identity.get.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), hostList)
    Ok(views.html.deploy.preview(request, parameters))
  }

  def previewContent(projectName: String, buildId: String, stage: String, recipe: String, hosts: String) = AuthAction { implicit request =>
    try {
      val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
      val parameters = DeployParameters(Deployer(request.identity.get.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), hostList)
      val previewData = Preview(parameters)
      Ok(views.html.deploy.previewContent(request, previewData))
    } catch {
      case e: Throwable =>
        Ok(views.html.errorContent(e, "Couldn't resolve preview information."))
    }
  }

  def history() = AuthAction { implicit request =>
    Ok(views.html.deploy.history(request))
  }

  def historyContent() = AuthAction { implicit request =>
    val records = try {
      DeployController.getDeploys(deployment.DeployFilter.fromRequest(request), deployment.PaginationView.fromRequest(request), fetchLogs = false).reverse
    } catch {
      case e:Exception =>
        log.error("Exception whilst fetching records",e)
        Nil
    }
    val count = try {
      Some(DeployController.countDeploys(deployment.DeployFilter.fromRequest(request)))
    } catch {
      case e:Exception => None
    }
    Ok(views.html.deploy.historyContent(request, records, deployment.DeployFilterPagination.fromRequest.withItemCount(count)))
  }

  def autoCompleteProject(term: String) = AuthAction {
    val possibleProjects = TeamCity.buildTypes.map(_.name).filter(_.toLowerCase.contains(term.toLowerCase)).sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = TeamCity.successfulBuilds(project).filter(p => p.number.contains(term) || p.branch.contains(term)).map { build =>
      val formatter = DateTimeFormat.forPattern("HH:mm d/M/yy")
      val label = "%s [%s] (%s)" format (build.number, build.branch, formatter.print(build.startDate))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def teamcity = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Branch", "Build Type ID", "Build ID")
    val data =
      for((buildType, builds) <- TeamCity.buildMap;
          build <- builds)
        yield Seq(buildType.name,build.number,build.branch,buildType.id,build.buildId)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
  }

  def continuousState() = AuthAction { implicit request =>
    case class ContinuousParameters(projectName:String, stage:String, enabled:Boolean)
    lazy val continuousForm = Form[ContinuousParameters](
      mapping(
        "projectName" -> nonEmptyText,
        "stage" -> nonEmptyText,
        "enabled" -> boolean
      )(ContinuousParameters.apply)
        (ContinuousParameters.unapply)
    )

    continuousForm.bindFromRequest().fold(
      errors => Redirect(routes.Deployment.continuousDeployment()),
      form => {
        log.info("request: %s" format form)
        if (form.enabled)
          ContinuousDeployment.enable(form.projectName,form.stage)
        else
          ContinuousDeployment.disable(form.projectName,form.stage)
      }
    )
    Redirect(routes.Deployment.continuousDeployment())
  }

  def continuousStateGlobal() = AuthAction { implicit request =>
    case class ContinuousParameters(enabled:Boolean)
    lazy val continuousForm = Form[ContinuousParameters](
      mapping(
        "enabled" -> boolean
      )(ContinuousParameters.apply)
        (ContinuousParameters.unapply)
    )

    continuousForm.bindFromRequest().fold(
      errors => Redirect(routes.Deployment.continuousDeployment()),
      form => {
        log.info("request: %s" format form)
        if (form.enabled)
          ContinuousDeployment.enableAll()
        else
          ContinuousDeployment.disableAll()
      }
    )
    Redirect(routes.Deployment.continuousDeployment())
  }

  def continuousDeployment() = AuthAction { implicit request =>
    val status = ContinuousDeployment.status()
    Ok(views.html.deploy.continuousDeployment(request, status))
  }

  def deployConfirmation(deployFormJson: String) = AuthAction { implicit request =>
    val parametersJson = Json.parse(deployFormJson)
    Ok(views.html.deploy.deployConfirmation(request, deployForm.bind(parametersJson)))
  }

  def deployConfirmationWithParameters = AuthAction { implicit request =>
    val form = deployForm.bindFromRequest()
    Ok(views.html.deploy.deployConfirmation(request, form))
  }

  def markAsFailed = AuthAction { implicit request =>
    uuidForm.bindFromRequest().fold(
      errors => Redirect(routes.Deployment.history),
      form => {
        form.action match {
          case "markAsFailed" =>
            val record = DeployController.get(UUID.fromString(form.uuid))
            if (record.isStalled)
              DeployController.markAsFailed(record)
            Redirect(routes.Deployment.viewUUID(form.uuid))
        }
      }
    )
  }

}