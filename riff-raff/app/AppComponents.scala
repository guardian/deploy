import ci._
import com.gu.googleauth.{AntiForgeryChecker, AuthAction, GoogleAuthConfig}
import com.gu.play.secretrotation.aws.parameterstore
import com.gu.play.secretrotation.{
  RotatingSecretComponents,
  SnapshotProvider,
  TransitionTiming
}
import conf.{Config, Secrets}
import controllers._
import deployment.preview.PreviewCoordinator
import deployment.{DeploymentEngine, Deployments}
import housekeeping.ArtifactHousekeeping
import lifecycle.{
  Lifecycle,
  ShutdownWhenInactive,
  TerminateInstanceWhenInactive
}
import magenta.deployment_type._
import magenta.tasks.AWS
import notification.{
  DeployFailureNotifications,
  GrafanaAnnotationLogger,
  HooksClient
}
import persistence._
import play.api.ApplicationLoader.Context
import play.api.BuiltInComponentsFromContext
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.http.DefaultHttpErrorHandler
import play.api.i18n.{I18nComponents, MessagesApi}
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{AnyContent, RequestHeader, Result}
import play.api.routing.Router
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import resources.PrismLookup
import router.Routes
import schedule.DeployScheduler
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import utils._

import java.time.Duration.{ofHours, ofMinutes}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AppComponents(
    context: Context,
    config: Config,
    passwordProvider: PasswordProvider
) extends BuiltInComponentsFromContext(context)
    with RotatingSecretComponents
    with AhcWSComponents
    with I18nComponents
    with CSRFComponents
    with GzipFilterComponents
    with AssetsComponents
    with EvolutionsComponents
    with DBComponents
    with HikariCPComponents
    with Logging {

  lazy val datastore: DataStore =
    new PostgresDatastoreOps(config, passwordProvider).buildDatastore()

  val secretStateSupplier: SnapshotProvider = {
    new parameterstore.SecretSupplier(
      transitionTiming = TransitionTiming(
        usageDelay = ofMinutes(3),
        overlapDuration = ofHours(2)
      ),
      parameterName = config.auth.secretStateSupplierKeyName,
      ssmClient = parameterstore.AwsSdkV2(config.auth.secretStateSupplierClient)
    )
  }

  lazy val googleAuthConfig = GoogleAuthConfig(
    clientId = config.auth.clientId,
    clientSecret = config.auth.clientSecret,
    redirectUrl = config.auth.redirectUrl,
    domains = List(config.auth.domain),
    antiForgeryChecker = AntiForgeryChecker(
      secretStateSupplier,
      AntiForgeryChecker.signatureAlgorithmFromPlay(httpConfiguration)
    )
  )

  // Lazy val needs to be accessed so that database evolutions are applied
  applicationEvolutions

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  val s3BuildOps = new S3BuildOps(config)
  val buildPoller = new CIBuildPoller(config, s3BuildOps, executionContext)
  val builds = new Builds(buildPoller)

  object DefaultBuildTags extends BuildTags {
    def get(projectName: String, buildId: String): Map[String, String] = {
      val build = builds.build(projectName, buildId)
      val repoUrl = build.flatMap(b => VCSInfo.normalise(b.vcsURL))
      val buildTool = build.flatMap(b => b.buildTool)
      val default = "unknown"

      Map(
        "gu:build-tool" -> buildTool.getOrElse(default),
        "gu:repo" -> repoUrl.getOrElse(default)
      )
    }
  }

  val availableDeploymentTypes = Seq(
    S3,
    AutoScaling,
    Fastly,
    FastlyCompute,
    new CloudFormation(DefaultBuildTags),
    Lambda,
    LambdaLayer,
    AmiCloudFormationParameter,
    SelfDeploy
  )

  val ioExecutionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("io-context")

  val documentStoreConverter = new DocumentStoreConverter(datastore)
  val targetDynamoRepository = new TargetDynamoRepository(config)
  val restrictionConfigDynamoRepository = new RestrictionConfigDynamoRepository(
    config
  )
  val changeFreeze = new ChangeFreeze(config)
  val scheduleRepository = new ScheduleRepository(config)
  val hookConfigRepository = new HookConfigRepository(config)
  val continuousDeploymentConfigRepository =
    new ContinuousDeploymentConfigRepository(config)
  val menu = new Menu(config)
  val s3Tag = new S3Tag(config)

  val ssmClient = SsmClient
    .builder()
    .credentialsProvider(config.credentialsProviderChain(None, None))
    .overrideConfiguration(AWS.clientConfiguration)
    .region(Region.of(config.credentials.regionName))
    .build()

  val secretProvider = new Secrets(config, ssmClient)
  secretProvider.populate()
  val prismLookup = new PrismLookup(config, wsClient, secretProvider)
  val deploymentEngine = new DeploymentEngine(
    config,
    prismLookup,
    availableDeploymentTypes,
    ioExecutionContext
  )

  val targetResolver = new TargetResolver(
    config,
    buildPoller,
    availableDeploymentTypes,
    targetDynamoRepository
  )
  val deployments = new Deployments(
    deploymentEngine,
    builds,
    documentStoreConverter,
    restrictionConfigDynamoRepository
  )
  val continuousDeployment = new ContinuousDeployment(
    config,
    changeFreeze,
    buildPoller,
    deployments,
    continuousDeploymentConfigRepository
  )
  val previewCoordinator = new PreviewCoordinator(
    config,
    prismLookup,
    availableDeploymentTypes,
    ioExecutionContext
  )
  val artifactHousekeeper = new ArtifactHousekeeping(config, deployments)
  val scheduledDeployNotifier =
    new DeployFailureNotifications(config, targetResolver, prismLookup)

  val authAction = new AuthAction[AnyContent](
    googleAuthConfig,
    routes.Login.loginAction,
    controllerComponents.parsers.default
  )(executionContext)

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new HstsFilter()(executionContext)
  ) // TODO (this would require an upgrade of the management-play lib) ++ PlayRequestMetrics.asFilters

  val deployScheduler =
    new DeployScheduler(config, deployments, scheduledDeployNotifier)
  log.info("Starting deployment scheduler")
  deployScheduler.start()
  applicationLifecycle.addStopHook { () =>
    log.info("Shutting down deployment scheduler")
    Future.successful(deployScheduler.shutdown())
  }
  deployScheduler.initialise(new ScheduleRepository(config).getScheduleList())

  val hooksClient =
    new HooksClient(datastore, hookConfigRepository, wsClient, executionContext)

  val shutdownWhenInactive = new ShutdownWhenInactive(deployments)
  val rotateInstanceWhenInactive =
    new TerminateInstanceWhenInactive(deployments, config)

  val lifecycleSingletons: Seq[Lifecycle] = Seq(
    ScheduledAgent,
    deployments,
    builds,
    targetResolver,
    new GrafanaAnnotationLogger(config.urls.publicPrefix),
    hooksClient,
    new SummariseDeploysHousekeeping(config, datastore),
    continuousDeployment,
    artifactHousekeeper,
    scheduledDeployNotifier,
    shutdownWhenInactive,
    rotateInstanceWhenInactive
  )

  log.info(
    s"Calling init() on Lifecycle singletons: ${lifecycleSingletons.map(_.getClass.getName).mkString(", ")}"
  )
  lifecycleSingletons.foreach(_.init())

  context.lifecycle.addStopHook(() =>
    Future {
      lifecycleSingletons.reverse.foreach { singleton =>
        try {
          singleton.shutdown()
        } catch {
          case NonFatal(e) =>
            log.error(
              "Caught unhandled exception whilst calling shutdown() on Lifecycle singleton",
              e
            )
        }
      }
    }(ExecutionContext.global)
  )

  val applicationController = new Application(
    config,
    menu,
    prismLookup,
    availableDeploymentTypes,
    authAction,
    controllerComponents,
    assets
  )(environment, wsClient, executionContext)
  val deployController = new DeployController(
    config,
    menu,
    deployments,
    prismLookup,
    availableDeploymentTypes,
    changeFreeze,
    builds,
    s3Tag,
    authAction,
    restrictionConfigDynamoRepository,
    controllerComponents
  )
  val apiController = new Api(
    config,
    menu,
    deployments,
    availableDeploymentTypes,
    datastore,
    changeFreeze,
    authAction,
    controllerComponents
  )
  val continuousDeployController = new ContinuousDeployController(
    config,
    menu,
    changeFreeze,
    prismLookup,
    authAction,
    continuousDeploymentConfigRepository,
    controllerComponents
  )
  val previewController = new PreviewController(
    config,
    menu,
    previewCoordinator,
    authAction,
    controllerComponents
  )(wsClient, executionContext)
  val hooksController = new HooksController(
    config,
    menu,
    prismLookup,
    authAction,
    hookConfigRepository,
    controllerComponents
  )
  val restrictionsController = new Restrictions(
    config,
    menu,
    authAction,
    restrictionConfigDynamoRepository,
    controllerComponents
  )
  val scheduleController = new ScheduleController(
    config,
    menu,
    authAction,
    controllerComponents,
    scheduleRepository,
    prismLookup,
    deployScheduler
  )
  val targetController = new TargetController(
    config,
    menu,
    deployments,
    targetDynamoRepository,
    authAction,
    controllerComponents
  )
  val loginController = new Login(
    config,
    menu,
    deployments,
    datastore,
    controllerComponents,
    authAction,
    googleAuthConfig
  )
  val testingController = new Testing(
    config,
    menu,
    datastore,
    prismLookup,
    documentStoreConverter,
    authAction,
    controllerComponents,
    artifactHousekeeper,
    deployments
  )
  val managementController = new Management(
    controllerComponents,
    shutdownWhenInactive,
    rotateInstanceWhenInactive
  )

  override lazy val httpErrorHandler = new DefaultHttpErrorHandler(
    environment,
    configuration,
    sourceMapper,
    Some(router)
  ) {
    override def onServerError(
        request: RequestHeader,
        t: Throwable
    ): Future[Result] = {
      log.error("Error whilst trying to serve request", t)
      val reportException = if (t.getCause != null) t.getCause else t
      Future.successful(
        InternalServerError(views.html.errorPage(config)(reportException))
      )
    }
  }

  override def router: Router = new Routes(
    httpErrorHandler,
    applicationController,
    previewController,
    deployController,
    apiController,
    continuousDeployController,
    hooksController,
    restrictionsController,
    scheduleController,
    targetController,
    loginController,
    testingController,
    assets,
    managementController
  )
}
