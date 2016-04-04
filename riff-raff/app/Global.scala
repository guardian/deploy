import ci.{Builds, ContinuousDeployment}
import conf.{DeployMetrics, PlayRequestMetrics}
import controllers.Logging
import deployment.{DeployInfoManager, Deployments}
import lifecycle.{Lifecycle, ShutdownWhenInactive}
import notification._
import persistence.SummariseDeploysHousekeeping
import play.api.Application
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{RequestHeader, Result, WithFilters}
import play.filters.gzip.GzipFilter
import utils.{HstsFilter, ScheduledAgent}

import scala.collection.mutable
import scala.concurrent.Future

object Global extends WithFilters(new GzipFilter() :: new HstsFilter() :: PlayRequestMetrics.asFilters: _*) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent,
      DeployInfoManager,
      Deployments,
      IrcClient,
      DeployMetrics,
      HooksClient,
      Builds,
      SummariseDeploysHousekeeping,
      ContinuousDeployment,
      ShutdownWhenInactive
    )

    log.info(s"Calling init() on Lifecycle singletons: ${lifecycleSingletons.map(_.getClass.getName).mkString(", ")}")
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  override def onStop(app: Application) {
    log.info(s"Calling shutdown() on Lifecycle singletons: ${lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", ")}")
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
  }

  override def onError(request: RequestHeader, t: Throwable): Future[Result] = {
    log.error("Error whilst trying to serve request", t)
    val reportException = if (t.getCause != null) t.getCause else t
    Future.successful(InternalServerError(views.html.errorPage(reportException)))
  }
}