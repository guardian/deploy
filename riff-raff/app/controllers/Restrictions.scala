package controllers

import java.util.UUID

import conf.Configuration.auth
import deployment.Error
import org.joda.time.DateTime
import persistence.RestrictionConfigDynamoRepository
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.ws.WSClient
import play.api.mvc.Controller
import restrictions.{RestrictionChecker, RestrictionConfig, RestrictionForm}

import scala.util.Try

class Restrictions()(implicit val messagesApi: MessagesApi, val wsClient: WSClient)
    extends Controller
    with LoginActions
    with I18nSupport {

  lazy val restrictionsForm = Form[RestrictionForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "editingLocked" -> boolean,
      "whitelist" -> optional(text),
      "continuousDeployment" -> boolean,
      "note" -> nonEmptyText
    )(
      (id, projectName, stage, editingLocked, whitelist, cdPermitted, note) =>
        RestrictionForm(id,
                        projectName,
                        stage,
                        editingLocked,
                        whitelist.map(_.split('\n').map(_.trim).toSeq.filter(_.nonEmpty)).getOrElse(Seq.empty),
                        cdPermitted,
                        note))(
      f =>
        Some(
          (f.id,
           f.projectName,
           f.stage,
           f.editingLocked,
           Some(f.whitelist.mkString("\n")),
           f.continuousDeployment,
           f.note)))
      .verifying(
        "Stage is invalid - should be a valid regular expression or contain no special values",
        form => Try(form.stage.r).isSuccess
      )
  )

  def list = AuthAction { implicit request =>
    val configs = RestrictionConfigDynamoRepository.getRestrictionList.toList.sortBy(r => r.projectName + r.stage)
    Ok(views.html.restrictions.list(configs, auth.superusers))
  }

  def form = AuthAction { implicit request =>
    val newForm = restrictionsForm.fill(
      RestrictionForm(UUID.randomUUID(), "", "", editingLocked = false, Seq.empty, continuousDeployment = false, ""))
    Ok(views.html.restrictions.form(newForm, saveDisabled = false))
  }

  def save = AuthAction { implicit request =>
    restrictionsForm
      .bindFromRequest()
      .fold(
        formWithErrors => Ok(views.html.restrictions.form(formWithErrors, saveDisabled = false)),
        f => {
          RestrictionChecker.isEditable(RestrictionConfigDynamoRepository.getRestriction(f.id),
                                        request.user,
                                        auth.superusers) match {
            case Right(_) =>
              val newConfig = RestrictionConfig(f.id,
                                                f.projectName,
                                                f.stage,
                                                new DateTime(),
                                                request.user.fullName,
                                                request.user.email,
                                                f.editingLocked,
                                                f.whitelist,
                                                f.continuousDeployment,
                                                f.note)
              RestrictionConfigDynamoRepository.setRestriction(newConfig)
              Redirect(routes.Restrictions.list())
            case Left(Error(reason)) =>
              Forbidden(s"Not possible to update this restriction: $reason")
          }
        }
      )
  }

  def edit(id: String) = AuthAction { implicit request =>
    RestrictionConfigDynamoRepository
      .getRestriction(UUID.fromString(id))
      .map { rc =>
        val form = restrictionsForm.fill(
          RestrictionForm(rc.id,
                          rc.projectName,
                          rc.stage,
                          rc.editingLocked,
                          rc.whitelist,
                          rc.continuousDeployment,
                          rc.note)
        )
        val cannotSave = RestrictionChecker.isEditable(Some(rc), request.user, auth.superusers).isLeft

        Ok(
          views.html.restrictions.form(
            restrictionForm = form,
            saveDisabled = cannotSave
          ))
      }
      .getOrElse(Redirect(routes.Restrictions.list()))
  }

  def delete(id: String) = AuthAction { request =>
    RestrictionChecker.isEditable(RestrictionConfigDynamoRepository.getRestriction(UUID.fromString(id)),
                                  request.user,
                                  auth.superusers) match {
      case Right(_) =>
        RestrictionConfigDynamoRepository.deleteRestriction(UUID.fromString(id))
        Redirect(routes.Restrictions.list())
      case Left(Error(reason)) =>
        Forbidden(s"Not possible to delete this restriction: $reason")
    }
  }
}
