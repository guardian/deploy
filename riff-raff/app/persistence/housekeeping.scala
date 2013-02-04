package persistence

import lifecycle.LifecycleWithoutApp
import conf.Configuration.housekeeping
import org.joda.time.{DateMidnight, LocalTime}
import persistence.Persistence.store
import utils.{DailyScheduledAgentUpdate, ScheduledAgent}
import controllers.Logging

object SummariseDeploysHousekeeping extends LifecycleWithoutApp with Logging {
  lazy val maxAgeDays = housekeeping.summariseDeploysAfterDays
  lazy val housekeepingTime = new LocalTime(housekeeping.hour, housekeeping.minute)

  def summariseDeploys(): Int = {
    log.info("Summarising deploys older than %d days" format maxAgeDays)
    val maxAgeThreshold = (new DateMidnight()).minusDays(maxAgeDays)
    val deploys = store.getCompleteDeploysOlderThan(maxAgeThreshold.toDateTime).toList
    log.info("Found %d deploys to summarise" format deploys.size)
    deploys.foreach(detail => store.summariseDeploy(detail.uuid))
    log.info("Finished summarising")
    deploys.size
  }

  var summariseSchedule:Option[ScheduledAgent[Int]] = None

  val update = DailyScheduledAgentUpdate[Int](housekeepingTime){ _ + summariseDeploys() }

  def init() {
    summariseSchedule = Some(ScheduledAgent(0, update))
  }
  def shutdown() {
    summariseSchedule.foreach(_.shutdown())
    summariseSchedule = None
  }
}
