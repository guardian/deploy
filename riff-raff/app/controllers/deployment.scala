package controllers

import ci._
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
import scala.concurrent.duration._
import play.api.libs.json.Json
import org.joda.time.format.DateTimeFormat
import persistence.DocumentStoreConverter
import lifecycle.LifecycleWithoutApp
import com.gu.management.DefaultSwitch
import conf.AtomicSwitch
import org.joda.time.{Interval, DateTime}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.Akka

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
    val hostNameMetadata = Map(Record.RIFFRAFF_DOMAIN -> conf.Configuration.domains.identityName,
                               Record.RIFFRAFF_HOSTNAME -> java.net.InetAddress.getLocalHost.getHostName)
    val record = DeployV2Record(recordType, uuid, params) ++ hostNameMetadata
    library send { _ + (uuid -> Agent(record)) }
    DocumentStoreConverter.saveDeploy(record)
    attachMetaData(record)
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

  def attachMetaData(record: Record) {
    import play.api.Play.current
    val metaData = Akka.future {
      ContinuousIntegration.getMetaData(record.buildName, record.buildId)
    }
    metaData.map { md =>
      DocumentStoreConverter.addMetaData(record.uuid, md)
      Option(library()(record.uuid)) foreach { recordAgent =>
        recordAgent send { record =>
          record ++ md
        }
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

  def deploy(requestedParams: DeployParameters, mode: TaskType.Value = TaskType.Deploy): UUID = {
    if (enableQueueingSwitch.isSwitchedOff)
      throw new IllegalStateException("Unable to queue a new deploy; deploys are currently disabled by the %s switch" format enableQueueingSwitch.name)

    val params = TeamCityBuilds.transformLastSuccessful(requestedParams)
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

  def getLastCompletedDeploys(project: String): Map[String, Record] = {
    DocumentStoreConverter.getLastCompletedDeploys(project)
  }

  def findProjects(): List[String] = {
    DocumentStoreConverter.findProjects()
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
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> text,
      "recipe" -> optional(text),
      "action" -> nonEmptyText,
      "hosts" -> list(text)
    )(DeployParameterForm)(DeployParameterForm.unapply)
  )

  def deploy = AuthAction { implicit request =>
    Ok(views.html.deploy.form(request, deployForm))
  }

  def processForm = AuthAction { implicit request =>
    deployForm.bindFromRequest().fold(
      errors => BadRequest(views.html.deploy.form(request,errors)),
      form => {
        log.info("Host list: %s" format form.hosts)
        val defaultRecipe = DeployInfoManager.deployInfo
          .firstMatchingData("default-recipe", App(form.project), form.stage)
          .map(data => RecipeName(data.value)).getOrElse(DefaultRecipe())
        val parameters = new DeployParameters(Deployer(request.identity.get.fullName),
          Build(form.project,form.build.toString),
          Stage(form.stage),
          recipe = form.recipe.map(RecipeName(_)).getOrElse(defaultRecipe),
          hostList = form.hosts)

        form.action match {
          case "preview" =>
            Redirect(routes.Deployment.preview(parameters.build.projectName, parameters.build.id, parameters.stage.name, parameters.recipe.name, parameters.hostList.mkString(",")))
          case "deploy" =>
            responsibleFor(parameters) match {
              case Local() =>
                val uuid = DeployController.deploy(parameters)
                Redirect(routes.Deployment.viewUUID(uuid.toString))
              case Remote(_, urlPrefix) =>
                implicit val formWrites = Json.writes[DeployParameterForm]
                val call = routes.Deployment.deployConfirmation(Json.toJson(form).toString)
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
    val stopFlag = if (record.isDone) false else DeployController.getStopFlag(uuid).getOrElse(false)
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
    val previewId = PreviewController.startPreview(parameters)
    Ok(views.html.deploy.preview(request, parameters, previewId.toString))
  }

  def previewContent(previewId: String, projectName: String, buildId: String, stage: String, recipe: String, hosts: String) = AuthAction { implicit request =>
    val previewUUID = UUID.fromString(previewId)
    val hostList = hosts.split(",").toList.filterNot(_.isEmpty)
    val parameters = DeployParameters(Deployer(request.identity.get.fullName), Build(projectName, buildId), Stage(stage), RecipeName(recipe), hostList)
    val result = PreviewController.getPreview(previewUUID, parameters)
    result match {
      case PreviewReady(preview) => Ok(views.html.deploy.previewContent(request, preview))
      case PreviewFailed(exception) => Ok(views.html.errorContent(exception, "Couldn't resolve preview information."))
      case PreviewInProgress(startTime) =>
        val duration = new org.joda.time.Duration(startTime, new DateTime())
        Ok(views.html.deploy.previewLoading(request, duration.getStandardSeconds))
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
    val possibleProjects = TeamCityBuilds.buildTypes.map(_.fullName).filter(_.toLowerCase.contains(term.toLowerCase)).toList.sorted.take(10)
    Ok(Json.toJson(possibleProjects))
  }

  def autoCompleteBuild(project: String, term: String) = AuthAction {
    val possibleProjects = TeamCityBuilds.successfulBuilds(project).filter(
      p => p.number.contains(term) || p.branchName.contains(term)
    ).map { build =>
      val formatter = DateTimeFormat.forPattern("HH:mm d/M/yy")
      val label = "%s [%s] (%s)" format (build.number, build.branchName, formatter.print(build.startDate))
      Map("label" -> label, "value" -> build.number)
    }
    Ok(Json.toJson(possibleProjects))
  }

  def projectHistory(project: String) = AuthAction {
    val buildMap = DeployController.getLastCompletedDeploys(project)
    Ok(views.html.deploy.projectHistory(project, buildMap))
  }

  def teamcity = AuthAction {
    val header = Seq("Build Type Name", "Build Number", "Build Branch", "Build Type ID", "Build ID")
    val data =
      for(build <- TeamCityBuilds.builds.sortBy(_.buildType.fullName))
        yield Seq(build.buildType.name,build.number,build.branchName,build.buildType.id,build.id)

    Ok((header :: data.toList).map(_.mkString(",")).mkString("\n")).as("text/csv")
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

  def dashboard(projects: String, search: Boolean) = AuthAction { implicit request =>
    Ok(views.html.deploy.dashboard(request, projects, search))
  }

  def dashboardContent(projects: String, search: Boolean) = AuthAction { implicit request =>
    val projectTerms = projects.split(",").toList.filterNot(""==)
    val projectNames = if (search) {
      projectTerms.flatMap(term => { DeployController.findProjects().filter(_.contains(term)) })
    } else projectTerms
    val deploys = projectNames.map{ project =>
      project -> DeployController.getLastCompletedDeploys(project)
    }.filterNot(_._2.isEmpty)
    Ok(views.html.deploy.dashboardContent(deploys))
  }

}