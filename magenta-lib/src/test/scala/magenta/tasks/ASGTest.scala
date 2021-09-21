package magenta.tasks

import java.util.UUID
import magenta.artifact.S3Path
import magenta.deployment_type.{MustBePresent, MustNotBePresent}
import magenta.tasks.ASG.{TagAbsent, TagExists, TagMatch}
import magenta.{App, KeyRing, Stage, _}
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.pagination.sync.SdkIterable
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{Instance => ASGInstance, _}
import software.amazon.awssdk.services.autoscaling.paginators.DescribeAutoScalingGroupsIterable
import software.amazon.awssdk.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResponse, InstanceState}
import software.amazon.awssdk.services.elasticloadbalancing.{ElasticLoadBalancingClient => ClassicELBClient}
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{DescribeTargetHealthRequest, TargetHealthStateEnum, TagDescription => _, _}
import software.amazon.awssdk.services.elasticloadbalancingv2.{ElasticLoadBalancingV2Client => ApplicationELBClient}

import scala.collection.JavaConverters._

class ASGTest extends AnyFlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val deploymentTypes: Nil.type = Nil

  private def mockClassicELBGroupHealth(classicELBClient: ClassicELBClient, state: String) = {
    when(classicELBClient.describeInstanceHealth(
      DescribeInstanceHealthRequest.builder().loadBalancerName("elb").build()
    )).thenReturn(DescribeInstanceHealthResponse.builder().instanceStates(
      InstanceState.builder().state(state).build()
    ).build())
  }

  private def mockTargetGroupHealth(appELBClient: ApplicationELBClient, state: TargetHealthStateEnum) = {
    when(appELBClient.describeTargetHealth(
      DescribeTargetHealthRequest.builder().targetGroupArn("elbTargetARN").build()
    )).thenReturn(DescribeTargetHealthResponse.builder().targetHealthDescriptions(
      TargetHealthDescription.builder().targetHealth(
        TargetHealth.builder().state(state).build()
      ).build()
    ).build())
  }

  private def toSdkIterable[T](thing: java.lang.Iterable[T]): SdkIterable[T] = {
    () => thing.iterator()
  }

  it should "find the matching auto-scaling group with Stack and App tags" in {
    val asgClientMock = mock[AutoScalingClient]
    val asgDescribeIterableMock = mock[DescribeAutoScalingGroupsIterable]

    val desiredGroup: AutoScalingGroup = AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD")

    when (asgDescribeIterableMock.autoScalingGroups()) thenReturn toSdkIterable(List(
      desiredGroup,
      AutoScalingGroupWithTags("Role" -> "other", "Stage" -> "PROD"),
      AutoScalingGroupWithTags("Role" -> "example", "Stage" -> "TEST"),
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
      AutoScalingGroupWithTags("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD")
    ).asJava)
    when (asgClientMock.describeAutoScalingGroupsPaginator()) thenReturn asgDescribeIterableMock

    val tags = List(
      TagMatch("Stage", "PROD"),
      TagMatch("Stack", "contentapi"),
      TagMatch("App", "logcabin"),
    )
    val group = ASG.groupWithTags(tags, asgClientMock, reporter)
    group shouldBe desiredGroup
  }

  it should "fail if more than one ASG matches the Stack and App tags (unless MigrationTagRequirements are specified)" in {
    val asgClientMock = mock[AutoScalingClient]
    val asgDescribeIterableMock = mock[DescribeAutoScalingGroupsIterable]

    val desiredGroup = AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey")

    when (asgDescribeIterableMock.autoScalingGroups()) thenReturn toSdkIterable(List(
      desiredGroup,
      AutoScalingGroupWithTags("Role" -> "other", "Stage" -> "PROD"),
      AutoScalingGroupWithTags("Role" -> "example", "Stage" -> "TEST"),
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "orangutang"),
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
      AutoScalingGroupWithTags("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD")
    ).asJava)
    when (asgClientMock.describeAutoScalingGroupsPaginator()) thenReturn asgDescribeIterableMock

    val tags = List(
      TagMatch("Stage", "PROD"),
      TagMatch("Stack", "contentapi"),
      TagMatch("App", "logcabin"),
    )

    a [FailException] should be thrownBy {
      val group = ASG.groupWithTags(tags, asgClientMock, reporter)
      group shouldBe desiredGroup
    }
  }

  it should "identify a single ASG based on Stack & App tags and MustBePresent MigrationTagRequirements" in {
    val asgClientMock = mock[AutoScalingClient]
    val asgDescribeIterableMock = mock[DescribeAutoScalingGroupsIterable]

    val desiredGroup = AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey", "gu:riffraff:new-asg" -> "true")

    when (asgDescribeIterableMock.autoScalingGroups()) thenReturn toSdkIterable(List(
      desiredGroup,
      // This should be ignored due to lack of migration tag
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey"),
    ).asJava)
    when (asgClientMock.describeAutoScalingGroupsPaginator()) thenReturn asgDescribeIterableMock

    val tags = List(
      TagMatch("Stage", "PROD"),
      TagMatch("Stack", "contentapi"),
      TagMatch("App", "logcabin"),
      TagExists("gu:riffraff:new-asg")
    )
    val group = ASG.groupWithTags(tags, asgClientMock, reporter)
    group shouldBe desiredGroup
  }

  it should "identify a single ASG based on Stack & App tags and MustNotBePresent MigrationTagRequirements" in {
    val asgClientMock = mock[AutoScalingClient]
    val asgDescribeIterableMock = mock[DescribeAutoScalingGroupsIterable]

    val desiredGroup = AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey")

    when (asgDescribeIterableMock.autoScalingGroups()) thenReturn toSdkIterable(List(
      desiredGroup,
      // This should be ignored due to presence of migration tag
      AutoScalingGroupWithTags("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey", "gu:riffraff:new-asg" -> "true"),
    ).asJava)
    when (asgClientMock.describeAutoScalingGroupsPaginator()) thenReturn asgDescribeIterableMock

    val tags = List(
      TagMatch("Stage", "PROD"),
      TagMatch("Stack", "contentapi"),
      TagMatch("App", "logcabin"),
      TagAbsent("gu:riffraff:new-asg")
    )
    val group = ASG.groupWithTags(tags, asgClientMock, reporter)
    group shouldBe desiredGroup
  }

  it should "wait for instances in ELB to stabilise if there is one" in {
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroupWithTags("elb", "Role" -> "example", "Stage" -> "PROD").toBuilder.desiredCapacity(1).build()

    mockClassicELBGroupHealth(classicELBClient, "")

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 Classic ELB instances InService")

    mockClassicELBGroupHealth(classicELBClient, "InService")

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  it should "wait for instances in an ELB target group to be healthy" in {
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroupWithTags("Role" -> "example", "Stage" -> "PROD").toBuilder
      .targetGroupARNs("elbTargetARN")
      .desiredCapacity(1)
      .build()

    mockTargetGroupHealth(appELBClient, TargetHealthStateEnum.UNHEALTHY)

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 V2 ELB instances healthy")

    mockTargetGroupHealth(appELBClient, TargetHealthStateEnum.HEALTHY)

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  it should "wait for classic ELB and ELB target group to report as healthy" in {
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroupWithTags("elb", "Role" -> "example", "Stage" -> "PROD").toBuilder
      .targetGroupARNs("elbTargetARN")
      .desiredCapacity(1)
      .build()

    mockTargetGroupHealth(appELBClient, TargetHealthStateEnum.UNHEALTHY)

    mockClassicELBGroupHealth(classicELBClient, "")

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 Classic ELB instances InService")

    mockClassicELBGroupHealth(classicELBClient, "InService")

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 V2 ELB instances healthy")

    mockTargetGroupHealth(appELBClient, TargetHealthStateEnum.HEALTHY)

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  it should "just check ASG health for stability if there is no ELB" in {
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroupWithTags("Role" -> "example", "Stage" -> "PROD").toBuilder
      .desiredCapacity(1)
      .instances(ASGInstance.builder().healthStatus("Foobar").build())
      .build()

    when(
      classicELBClient.describeInstanceHealth(DescribeInstanceHealthRequest.builder()
        .loadBalancerName("elb")
        .build())
    ).thenReturn(
      DescribeInstanceHealthResponse.builder().instanceStates(
        InstanceState.builder()
          .state("")
          .build())
        .build()
    )

    ASG.isStabilized(group, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 instances InService")

    val updatedGroup = AutoScalingGroupWithTags("Role" -> "example", "Stage" -> "PROD").toBuilder
      .desiredCapacity(1)
      .instances(ASGInstance.builder()
        .lifecycleState(LifecycleState.IN_SERVICE)
        .build())
      .build()

    ASG.isStabilized(updatedGroup, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  object AutoScalingGroupWithTags {
    def apply(tags: (String, String)*): AutoScalingGroup = {
      val awsTags = tags map {
        case (key, value) => TagDescription.builder().key(key).value(value).build()
      }
      AutoScalingGroup.builder().tags(awsTags.asJava).build()
    }
    def apply(elbName: String, tags: (String, String)*): AutoScalingGroup = {
      val awsTags = tags map {
        case (key, value) => TagDescription.builder().key(key).value(value).build()
      }
      AutoScalingGroup.builder().tags(awsTags.asJava).loadBalancerNames(elbName).build()
    }
  }
}
