package deployment.preview

import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, NonEmptyList => NEL}
import com.amazonaws.services.s3.AmazonS3Client
import magenta.artifact.S3YamlArtifact
import magenta.fixtures.{ValidatedValues, _}
import magenta.graph.{DeploymentTasks, EndNode, Graph, StartNode, ValueNode}
import magenta.input.DeploymentKey
import magenta.{Build, DeployParameters, DeployReporter, Deployer, DeploymentResources, Region, Stage}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class PreviewTest extends FlatSpec with Matchers with ValidatedValues with MockitoSugar {
  def valid(n: Int): Validated[NEL[String], Int] = Valid(n)
  def invalid(error: String): Validated[NEL[String], Int] = Invalid(NEL.of(error))

  "sequenceGraph" should "invert a graph with only Valid nodes" in {
    val g = Graph.from(Seq(valid(1), valid(2), valid(3)))
    val inverted = Preview.sequenceGraph(g)
    inverted.valid shouldBe Graph(
      StartNode ~> ValueNode(1),
      ValueNode(1) ~> ValueNode(2),
      ValueNode(2) ~> ValueNode(3),
      ValueNode(3) ~> EndNode
    )
  }

  it should "invert a graph with multiple Invalid nodes" in {
    val g = Graph.from(Seq(valid(1), invalid("error-one"), valid(3), valid(4), invalid("error-two")))
    val inverted = Preview.sequenceGraph(g)
    inverted.invalid shouldBe NEL.of("error-one", "error-two")
  }

  implicit val artifactClient = mock[AmazonS3Client]

  "apply" should "create a preview" in {
    val artifact = S3YamlArtifact("test-bucket", "test-key")
    val config =
      """
        |stacks: [testStack]
        |regions: [testRegion]
        |deployments:
        |  testDeployment:
        |    type: stub-package-type
      """.stripMargin
    val parameters = DeployParameters(Deployer("test user"), Build("testProject", "1"), Stage("TEST"))
    val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), parameters)
    val resources = DeploymentResources(reporter, stubLookup(), artifactClient)
    val preview = Preview(artifact, config, parameters, resources, Seq(stubDeploymentType(Seq("testAction"))))

    val deploymentTuple = (
      DeploymentKey("testDeployment", "testAction", "testStack", "testRegion"),
      DeploymentTasks(
        List(
          StubTask("testAction per app task number one", Region("testRegion"), None, None),
          StubTask("testAction per app task number two", Region("testRegion"), None, None)
        ),
        "testDeployment [testAction] => testRegion/testStack"
      )
    )

    preview.graph.valid shouldBe Graph(
      StartNode ~> ValueNode(deploymentTuple),
      ValueNode(deploymentTuple) ~> EndNode
    )
  }
}
