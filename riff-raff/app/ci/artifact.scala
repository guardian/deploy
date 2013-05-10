package ci

import teamcity._
import teamcity.TeamCity.{BuildTypeLocator, BuildLocator}
import utils.{VCSInfo, Update, PeriodicScheduledAgentUpdate, ScheduledAgent}
import scala.concurrent.duration._
import org.joda.time.{Duration, DateTime}
import controllers.Logging
import scala.Predef._
import collection.mutable
import lifecycle.LifecycleWithoutApp
import scala.Some
import magenta.DeployParameters
import concurrent.Future
import concurrent.Await
import concurrent.ExecutionContext.Implicits.global

object `package` {
  implicit def listOfBuild2helpers(builds: List[Build]) = new {
    def buildTypes: Set[teamcity.BuildType] = builds.map(_.buildType).toSet
  }

  implicit def futureIterable2FlattenMap[A](futureIterable: Future[Iterable[A]]) = new {
    def flatFutureMap[B](p: A => Future[Iterable[B]]):Future[Iterable[B]] = {
      futureIterable.flatMap { iterable =>
        val newFutures:Iterable[Future[Iterable[B]]] = iterable.map(p)
        Future.sequence(newFutures).map(_.flatten)
      }
    }
  }
}

object ContinuousIntegration {
  def getMetaData(projectName: String, buildId: String): Map[String, String] = {
    val build = TeamCityBuilds.builds.find { build =>
      build.buildType.fullName == projectName && build.number == buildId
    }
    build.map { build =>
      val branch = Map("branch" -> build.branchName)
      val futureMap = build.detail.flatMap { detailedBuild =>
        Future.sequence(detailedBuild.revision.map {
          revision =>
            revision.vcsDetails.map {
              vcsDetails =>
                branch ++
                  Map(
                    VCSInfo.REVISION -> revision.version,
                    VCSInfo.CIURL -> vcsDetails.properties("url")
                  )
            }
        }.toIterable)
          .map(_.flatten.toMap)
      } recover {
        case _ => Map.empty[String,String]
      }
      Await.result(futureMap, 5 seconds)
    }.getOrElse(Map.empty[String,String])
  }
}

trait BuildWatcher {
  def newBuilds(builds: List[Build])
}

object TeamCityBuilds extends LifecycleWithoutApp with Logging {
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private val listeners = mutable.Buffer[BuildWatcher]()
  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  def notifyListeners(newBuilds: List[Build]) {
    if (!newBuilds.isEmpty) {
      buildAgent.foreach{ agent =>
        log.info("Queueing listener notification")
        agent.queueUpdate(Update{
          log.info("Notifying listeners")
          listeners.foreach{ listener =>
            try listener.newBuilds(newBuilds)
            catch {
              case e:Exception => log.error("BuildWatcher threw an exception", e)
            }
          }
        })
      }
    }
  }

  private val fullUpdate = PeriodicScheduledAgentUpdate[List[Build]](0 seconds, fullUpdatePeriod) { currentBuilds =>
    val builds = Await.result(getSuccessfulBuilds, 1 minute)
    if (!currentBuilds.isEmpty) notifyListeners((builds.toSet diff currentBuilds.toSet).toList)
    builds
  }

  private val incrementalUpdate = PeriodicScheduledAgentUpdate[List[Build]](1 minute, pollingPeriod) { currentBuilds =>
    if (currentBuilds.isEmpty) {
      log.warn("No builds yet, aborting incremental update")
      currentBuilds
    } else {
      Await.result(getNewBuilds(currentBuilds).map { newBuilds =>
        if (newBuilds.isEmpty)
          currentBuilds
        else {
          notifyListeners(newBuilds)
          (currentBuilds ++ newBuilds).sortBy(-_.id)
        }
      },pollingPeriod)
    }
  }

  private var buildAgent:Option[ScheduledAgent[List[Build]]] = None

  def builds: List[Build] = buildAgent.map(_.apply()).getOrElse(Nil)
  def build(project: String, number: String) = builds.find(b => b.buildType.fullName == project && b.number == number)
  def buildTypes: Set[BuildType] = builds.buildTypes

  def successfulBuilds(projectName: String): List[Build] = builds.filter(_.buildType.fullName == projectName)

  def transformLastSuccessful(params: DeployParameters): DeployParameters = {
    if (params.build.id != "lastSuccessful")
      params
    else {
      val builds = successfulBuilds(params.build.projectName)
      builds.headOption.map{ latestBuild =>
        params.copy(build = params.build.copy(id = latestBuild.number))
      }.getOrElse(params)
    }
  }


  def init() {
    if (TeamCityWS.teamcityURL.isDefined) {
      buildAgent = Some(ScheduledAgent[List[Build]](List.empty[Build], fullUpdate, incrementalUpdate))
    }
  }

  def shutdown() { buildAgent.foreach(_.shutdown()) }

  private def getSuccessfulBuilds: Future[List[Build]] = {
    log.debug("Getting successful builds")
    val buildTypes = BuildTypeLocator.list
    buildTypes.flatMap { fulfilledBuildTypes =>
      log.debug("Found %d buildTypes" format fulfilledBuildTypes.size)
      val allBuilds = Future.sequence(fulfilledBuildTypes.map(_.builds(BuildLocator.status("SUCCESS")))).map(_.flatten)
      allBuilds.map { result =>
        log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(fulfilledBuildTypes.size, result.size))
        result
      }
    }
  }

  private def getNewBuilds(currentBuilds: List[Build]): Future[List[Build]] = {
    val knownBuilds = currentBuilds.map(_.id).toSet
    val buildTypeMap = currentBuilds.map(b => b.buildType.id -> b.buildType).toMap
    val getBuildType = (buildTypeId:String) => {
      buildTypeMap.get(buildTypeId).orElse {
        buildAgent.foreach(_.queueUpdate(fullUpdate))
        log.warn("Unknown build type %s, queuing complete refresh" format buildTypeId)
        None
      }
    }
    val pollingWindowStart = (new DateTime()).minus(pollingWindow)
    log.info("Querying TC for all builds since %s" format pollingWindowStart)
    val builds = BuildSummary.listWithLookup(BuildLocator.sinceDate(pollingWindowStart).status("SUCCESS"), getBuildType)
    builds.map { builds =>
      log.debug("Found %d builds since %s" format (builds.size, pollingWindowStart))
      val newBuilds = builds.filterNot(build => knownBuilds.contains(build.id))
      log.info("Discovered builds: \n%s" format newBuilds.mkString("\n"))
      newBuilds
    }
  }
}

