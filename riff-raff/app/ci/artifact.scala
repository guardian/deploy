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
import concurrent.{ Future, Promise, promise, Await }
import concurrent.ExecutionContext.Implicits.global
import play.api.Logger
import akka.agent.Agent
import akka.actor.ActorSystem
import scala.util.Random

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

trait TagWatcher {
  def tag: String
  def newBuilds(builds: List[Build])
}

trait ApiTracker[T] {
  case class Result(diff: List[T], updated: List[T], previous: List[T])
  private val promises = Agent[List[Promise[List[T]]]](Nil)(ActorSystem("teamcity-trackers"))

  private val fullAgentUpdate = PeriodicScheduledAgentUpdate[(Boolean, List[T])](startupDelay, fullUpdatePeriod) { case (flag, current) =>
    val updated = fullUpdate(current)
    val diff: Set[T] = updated.toSet diff current.toSet
    trackerLog.info(s"[$name] Full update: discovered: ${diff.size}, total: ${updated.size}")
    queueNotify(Result(diff.toList, updated, current))
    (true, updated)
  }

  private val incrementalAgentUpdate = PeriodicScheduledAgentUpdate[(Boolean, List[T])](incrementalUpdatePeriod + startupDelay, incrementalUpdatePeriod) { case (flag, current) =>
    if (!flag) {
      trackerLog.error(s"[$name] Nothing tracked yet, aborting incremental update")
      (flag, current)
    } else {
      val result = incrementalUpdate(current)
      if (!result.diff.isEmpty) {
        trackerLog.info(s"[$name] Incremental update: discovered: ${result.diff.size}, total: ${result.updated.size}")
        queueNotify(result)
      } else {
        trackerLog.debug(s"[$name] No changes")
      }
      (flag, result.updated)
    }
  }

  private val agent: ScheduledAgent[(Boolean, List[T])] = ScheduledAgent[(Boolean, List[T])]((false, Nil), fullAgentUpdate, incrementalAgentUpdate)

  /*
   returns the current list, regardless of if the initial data has been fetched
    */
  def get(): List[T] = agent()._2
  /*
   return a future that is fulfilled as soon as there is valid data (immediately if we have fully initialised)
    */
  def future(): Future[List[T]] = {
    agent() match {
      case (true, data) =>
        Future.successful(data)
      case (false, _) =>
        val p = promise[List[T]]()
        promises.send { p :: _ }
        p.future
    }
  }

  def fullUpdatePeriod: FiniteDuration
  def incrementalUpdatePeriod: FiniteDuration
  def startupDelay: FiniteDuration

  def fullUpdate(previous: List[T]): List[T]
  def incrementalUpdate(previous: List[T]): Result = {
    val updated = fullUpdate(previous)
    Result((updated.toSet diff previous.toSet).toList, updated, previous)
  }

  def queueNotify(result: Result) {
    agent.queueUpdate(Update{
      notify(result.diff, result.previous)
      promises.send { promisesMade =>
        promisesMade.foreach(_.success(result.updated))
        Nil
      }
    })
  }

  def notify(discovered: List[T], previous: List[T])
  def shutdown() = agent.shutdown()
  def trackerLog:Logger
  def name:String
}

case class BuildLocatorTracker(locator: BuildLocator,
                          buildTypeTracker: ApiTracker[BuildType],
                          fullUpdatePeriod: FiniteDuration,
                          incrementalUpdatePeriod: FiniteDuration,
                          notifyHook: List[Build] => Unit,
                          pollingWindow: Duration,
                          startupDelay: FiniteDuration = 0L.seconds) extends ApiTracker[Build] with Logging {

  def notify(discovered: List[Build], previous: List[Build]) = if (!previous.isEmpty) notifyHook(discovered)
  def name = locator.toString

  def fullUpdate(previous: List[Build]) = {
    Await.result(getBuilds, incrementalUpdatePeriod * 20)
  }

  override def incrementalUpdate(previous: List[Build]) = {
    Await.result(getNewBuilds(previous).map { newBuilds =>
      if (newBuilds.isEmpty)
        Result(Nil, previous, previous)
      else
        Result(newBuilds, (previous ++ newBuilds).sortBy(-_.id), previous)
    },incrementalUpdatePeriod)
  }

  def getBuilds: Future[List[Build]] = {
    log.debug(s"[$name] Getting builds")
    val buildTypes = buildTypeTracker.future()
    buildTypes.flatMap{ fulfilledBuildTypes =>
      Future.sequence(fulfilledBuildTypes.map(_.builds(locator, followNext = true))).map(_.flatten)
    }
  }

  def getNewBuilds(currentBuilds:List[Build]): Future[List[Build]] = {
    val knownBuilds = currentBuilds.map(_.id).toSet
    val locatorWithWindow = locator.sinceDate(new DateTime().minus(pollingWindow))
    log.info(s"[$name] Querying with $locatorWithWindow")
    val builds = BuildSummary.listWithLookup(locatorWithWindow, TeamCityBuilds.getBuildType, followNext = true)
    builds.map { builds =>
      val newBuilds = builds.filterNot(build => knownBuilds.contains(build.id))
      if (!newBuilds.isEmpty)
        log.info(s"[$name] Discovered builds: \n${newBuilds.mkString("\n")}")
      newBuilds
    }
  }

  val trackerLog = log
}

object TeamCityBuilds extends LifecycleWithoutApp with Logging {
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val tagPollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.tagging.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private val listeners = mutable.Buffer[BuildWatcher]()
  private var locatorTrackers = Map.empty[TagWatcher, BuildLocatorTracker]

  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  def subscribe(sink: TagWatcher) {
    if (TeamCityWS.teamcityURL.isDefined) {
      val tracker = new BuildLocatorTracker(
          BuildLocator.tag(sink.tag).status("SUCCESS"),
          buildTypeTracker.get,
          fullUpdatePeriod,
          pollingPeriod,
          builds => sink.newBuilds(builds),
          tagPollingWindow,
          Random.nextInt(conf.Configuration.teamcity.pollingPeriodSeconds).seconds
        )
      locatorTrackers += (sink -> tracker)
    }
  }

  def unsubscribe(sink: TagWatcher) {
    locatorTrackers.get(sink).foreach(_.shutdown())
    locatorTrackers -= sink
  }

  def notifyNewBuilds(newBuilds: List[Build]) = {
    log.info("Notifying listeners")
    listeners.foreach{ listener =>
      try listener.newBuilds(newBuilds)
      catch {
        case e:Exception => log.error("BuildWatcher threw an exception", e)
      }
    }
  }

  private var buildTypeTracker: Option[ApiTracker[BuildType]] = None
  private var successfulBuildTracker: Option[BuildLocatorTracker] = None

  def builds: List[Build] = successfulBuildTracker.map(_.get()).getOrElse(Nil)
  def build(project: String, number: String) = builds.find(b => b.buildType.fullName == project && b.number == number)
  def buildTypes: Set[BuildType] = buildTypeTracker.map(_.get().toSet).getOrElse(Set.empty)
  def getBuildType(id: String):Option[BuildType] = buildTypes.find(_.id == id)
  def successfulBuilds(projectName: String): List[Build] = builds.filter(_.buildType.fullName == projectName)
  def getLastSuccessful(projectName: String): Option[String] =
    successfulBuilds(projectName).headOption.map{ latestBuild =>
      latestBuild.number
    }

  def init() {
    if (TeamCityWS.teamcityURL.isDefined) {
      buildTypeTracker = Some(new ApiTracker[BuildType] {
        def name = "BuildType tracker"
        def fullUpdatePeriod = TeamCityBuilds.fullUpdatePeriod
        def incrementalUpdatePeriod = pollingPeriod
        def fullUpdate(previous: List[BuildType]) = {
          Await.result(BuildTypeLocator.list, pollingPeriod / 2)
        }
        def notify(discovered: List[BuildType], previous: List[BuildType]) { }
        def trackerLog = log
        def startupDelay = 0L.seconds
      })
      successfulBuildTracker = Some(new BuildLocatorTracker(
        BuildLocator.status("SUCCESS"),
        buildTypeTracker.get,
        fullUpdatePeriod,
        pollingPeriod,
        notifyNewBuilds,
        pollingWindow
      ))
    }
  }

  def shutdown() {
    successfulBuildTracker.foreach(_.shutdown())
    buildTypeTracker.foreach(_.shutdown())
    locatorTrackers.values.foreach(_.shutdown())
    locatorTrackers = Map.empty
  }
}

