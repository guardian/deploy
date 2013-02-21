package magenta.tasks

import magenta.{KeyRing, Stage}
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import dispatch._
import net.liftweb.json._
import java.net.ConnectException

case class WaitForElasticSearchClusterGreen(packageName: String, stage: Stage, duration: Long)
  extends ASGTask with RepeatedPollingCheck {

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    val instance = EC2(asg.getInstances().headOption.getOrElse {
      throw new IllegalArgumentException("Auto-scaling group: %s had no instances" format (asg))
    })
    val node = ElasticSearchNode(instance.getPublicDnsName)
    check(stopFlag) {
      node.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity)
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(packageName: String, stage: Stage, duration: Long)
  extends ASGTask with RepeatedPollingCheck{

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    val newNode = asg.getInstances.filterNot(EC2.hasTag(_, "Magenta", "Terminate")).head
    val newESNode = ElasticSearchNode(EC2(newNode).getPublicDnsName)

    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        val node = ElasticSearchNode(EC2(instance).getPublicDnsName)
        check(stopFlag) {
          newESNode.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity)
        }
        if (!stopFlag) {
          node.shutdown()
          check(stopFlag) {
            newESNode.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity - 1)
          }
        }
        if (!stopFlag) cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class ElasticSearchNode(address: String) {
  implicit val format = DefaultFormats

  val http = new Http()
  private def clusterHealth = http(:/(address, 9200) / "_cluster" / "health" >- {json =>
    parse(json)
  })

  def dataNodesInCluster = (clusterHealth \ "number_of_data_nodes").extract[Int]
  def clusterIsHealthy = (clusterHealth \ "status").extract[String] == "green"

  def inHealthyClusterOfSize(desiredClusterSize: Int) =
    try {
      clusterIsHealthy && dataNodesInCluster == desiredClusterSize
    } catch {
      case e: ConnectException => false
    }

  def shutdown() = http((:/(address, 9200) / "_cluster" / "nodes" / "_local" / "_shutdown").POST >|)
}


