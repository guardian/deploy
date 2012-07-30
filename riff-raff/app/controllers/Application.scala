package controllers

import play.api.data._
import play.api.data.Forms._

import play.api.mvc._
import deployment._

import play.api.Logger
import conf.{TimedAction, Configuration}
import magenta._
import collection.mutable.ArrayBuffer
import tasks.Task

trait Logging {
  implicit val log = Logger(getClass)
}

case class MenuItem(title: String, target: Call, identityRequired: Boolean) {
  def isActive(request: AuthenticatedRequest[AnyContent]) = target.url == request.path
}

object Menu {
  lazy val menuItems = Seq(
    MenuItem("Home", routes.Application.index, false),
    MenuItem("Deployment Info", routes.Application.deployInfo(stage = ""), true),
    MenuItem("Frontend-Article CODE", routes.Deployment.frontendArticleCode(), true),
    MenuItem("Deploy Anything\u2122", routes.Deployment.deploy(), true),
    MenuItem("Deploy History", routes.Deployment.history(), true)
  )

  lazy val loginMenuItem = MenuItem("Login", routes.Login.login, false)

  def items(request: AuthenticatedRequest[AnyContent]) = {
    val loggedIn = request.identity.isDefined
    menuItems.filter { item =>
      !item.identityRequired ||
        (item.identityRequired && loggedIn)
    }
  }
}

object Application extends Controller with Logging {

  def index = TimedAction {
    NonAuthAction { implicit request =>
      request.identity.isDefined
      Ok(views.html.index(request))
    }
  }

  def deployInfo(stage: String) = TimedAction {
    AuthAction { request =>
      val stageAppHosts = DeployInfo.hostList filter { host =>
        host.stage == stage || stage == ""
      } groupBy { _.stage } mapValues { hostList =>
        hostList.groupBy {
          _.apps
        }
      }

      Ok(views.html.deploy.hostInfo(request, stageAppHosts))
    }
  }

}