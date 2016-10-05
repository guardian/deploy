package magenta.tasks

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import magenta.{KeyRing, Stage, _}

import scala.collection.JavaConversions._

case class CheckGroupSize(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity || doubleCapacity == 0) {
      reporter.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.getMaxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description = "Checking there is enough capacity to deploy"
}

case class TagCurrentInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    implicit val ec2Client = EC2.makeEc2Client(keyRing, region)
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate", ec2Client)
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {

  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    ASG.desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2, asgClient)
  }

  lazy val description =
    s"Double the size of the auto-scaling group in $stage, $stack for apps ${pkg.apps.mkString(", ")}"
}

sealed abstract class Pause(durationMillis: Long)(implicit val keyRing: KeyRing) extends Task {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    Thread.sleep(durationMillis)
  }

  def description = verbose
}

case class HealthcheckGrace(durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def verbose: String = s"Wait extra ${durationMillis}ms to let Load Balancer report correctly"
}

case class WarmupGrace(durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def verbose: String = s"Wait extra ${durationMillis}ms to let instances in Load Balancer warm up"
}

case class CheckForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    implicit val elbClient = ELB.makeElbClient(keyRing, region)
    ASG.isStabilized(asg, asgClient, elbClient)
  }
  lazy val description: String =
    "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class WaitForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask
    with SlowRepeatedPollingCheck {

  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    implicit val elbClient = ELB.makeElbClient(keyRing, region)
    check(reporter, stopFlag) {
      try {
        ASG.isStabilized(ASG.refresh(asg, asgClient), asgClient, elbClient)
      } catch {
        case e: AmazonServiceException if isRateExceeded(e) => {
          reporter.info(e.getMessage)
          false
        }
      }
    }

    //found this out by good old trial and error
    def isRateExceeded(e: AmazonServiceException) = e.getStatusCode == 400 && e.getErrorCode == "Throttling"
  }

  lazy val description: String =
    "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class CullInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    implicit val ec2Client = EC2.makeEc2Client(keyRing, region)
    implicit val elbClient = ELB.makeElbClient(keyRing, region)
    val instancesToKill = asg.getInstances.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate", ec2Client))
    val orderedInstancesToKill = instancesToKill.transposeBy(_.getAvailabilityZone)
    orderedInstancesToKill.foreach(instance => ASG.cull(asg, instance, asgClient, elbClient))
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {

  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    ASG.suspendAlarmNotifications(asg.getAutoScalingGroupName, asgClient)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack, region: Region)(
    implicit val keyRing: KeyRing)
    extends ASGTask {

  override def execute(asg: AutoScalingGroup,
                       reporter: DeployReporter,
                       stopFlag: => Boolean,
                       asgClient: AmazonAutoScalingClient) {
    ASG.resumeAlarmNotifications(asg.getAutoScalingGroupName, asgClient)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

trait ASGTask extends Task {
  def region: Region
  def pkg: DeploymentPackage
  def stage: Stage
  def stack: Stack

  def execute(asg: AutoScalingGroup,
              reporter: DeployReporter,
              stopFlag: => Boolean,
              asgClient: AmazonAutoScalingClient)

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) {
    val asgClient = ASG.makeAsgClient(keyRing, region)
    val group = ASG.groupForAppAndStage(pkg, stage, stack, asgClient, reporter)
    execute(group, reporter, stopFlag, asgClient)
  }

  def verbose = description
}
