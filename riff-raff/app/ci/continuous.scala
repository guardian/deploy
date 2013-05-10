package ci

import controllers.{Logging, DeployController}
import lifecycle.LifecycleWithoutApp
import java.util.UUID
import magenta.{Build => MagentaBuild}
import magenta.RecipeName
import magenta.DeployParameters
import magenta.Deployer
import magenta.Stage
import scala.Some
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import deployment.DomainAction.Local
import deployment.Domains
import org.joda.time.DateTime
import teamcity.Build
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.Implicits._

case class ContinuousDeploymentConfig(
  id: UUID,
  projectName: String,
  stage: String,
  recipe: String,
  branchMatcher:Option[String],
  enabled: Boolean,
  user: String,
  lastEdited: DateTime = new DateTime()
) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  lazy val buildFilter =
    (build:Build) => build.buildType.fullName == projectName && branchRE.findFirstMatchIn(build.branchName).isDefined
  def findMatch(builds: List[Build]): Option[Build] = {
    val potential = builds.filter(buildFilter).sortBy(-_.id)
    potential.find { build =>
      val olderBuilds = TeamCityBuilds.successfulBuilds(projectName).filter(buildFilter)
      !olderBuilds.exists(_.id > build.id)
    }
  }
}

object ContinuousDeploymentConfig extends MongoSerialisable[ContinuousDeploymentConfig] {
  implicit val configFormat: MongoFormat[ContinuousDeploymentConfig] = new ConfigMongoFormat
  private class ConfigMongoFormat extends MongoFormat[ContinuousDeploymentConfig] {
    def toDBO(a: ContinuousDeploymentConfig) = {
      val values = Map(
        "_id" -> a.id,
        "projectName" -> a.projectName,
        "stage" -> a.stage,
        "recipe" -> a.recipe,
        "enabled" -> a.enabled,
        "user" -> a.user,
        "lastEdited" -> a.lastEdited
      ) ++ (a.branchMatcher map ("branchMatcher" -> _))
      values.toMap
    }
    def fromDBO(dbo: MongoDBObject) = Some(ContinuousDeploymentConfig(
      id = dbo.as[UUID]("_id"),
      projectName = dbo.as[String]("projectName"),
      stage = dbo.as[String]("stage"),
      recipe = dbo.as[String]("recipe"),
      enabled = dbo.as[Boolean]("enabled"),
      user = dbo.as[String]("user"),
      lastEdited = dbo.as[DateTime]("lastEdited"),
      branchMatcher = dbo.getAs[String]("branchMatcher")
    ))
  }
}

object ContinuousDeployment extends LifecycleWithoutApp {

  var buildWatcher: Option[ContinuousDeployment] = None

  def init() {
    if (buildWatcher.isEmpty) {
      buildWatcher = Some(new ContinuousDeployment(Domains))
      buildWatcher.foreach(TeamCityBuilds.subscribe)
    }
  }

  def shutdown() {
    buildWatcher.foreach(TeamCityBuilds.unsubscribe)
    buildWatcher = None
  }
}

class ContinuousDeployment(domains: Domains) extends BuildWatcher with Logging {

  type ProjectCdMap = Map[String, Set[ContinuousDeploymentConfig]]

  def getApplicableDeployParams(builds: List[Build], configs: Iterable[ContinuousDeploymentConfig]): Iterable[DeployParameters] = {
    val enabledConfigs = configs.filter(_.enabled)

    val allParams = enabledConfigs.flatMap { config =>
      config.findMatch(builds).map { build =>
        DeployParameters(
          Deployer("Continuous Deployment"),
          MagentaBuild(build.buildType.fullName,build.number),
          Stage(config.stage),
          RecipeName(config.recipe)
        )
      }
    }
    allParams.filter { params =>
      domains.responsibleFor(params) match {
        case Local() => true
        case _ => false
      }
    }
  }

  def newBuilds(newBuilds: List[Build]) {
    log.info("New builds to consider for deployment %s" format newBuilds)
    val deploysToRun = getApplicableDeployParams(newBuilds, Persistence.store.getContinuousDeploymentList)

    deploysToRun.foreach{ params =>
      if (conf.Configuration.continuousDeployment.enabled) {
        log.info("Triggering deploy of %s" format params.toString)
        DeployController.deploy(params)
      } else
        log.info("Would deploy %s" format params.toString)
    }
  }

}