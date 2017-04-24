package magenta.input

import magenta.fixtures.ValidatedValues
import org.scalatest.{FlatSpec, ShouldMatchers}
import play.api.libs.json.{JsArray, JsNumber, JsString, Json}

class RiffRaffYamlReaderTest extends FlatSpec with ShouldMatchers with ValidatedValues {
  "RiffRaffYamlReader" should "read a minimal file" in {
    val yaml =
      """
        |---
        |stacks: [banana]
        |deployments:
        |  monkey:
        |    type: autoscaling
      """.stripMargin
    val input = RiffRaffYamlReader.fromString(yaml).valid
    input.stacks.isDefined should be(true)
    input.stacks.get.size should be(1)
    input.stacks.get should be(List("banana"))
    input.deployments.size should be(1)
    input.deployments.head should be(
      "monkey" -> DeploymentOrTemplate(Some("autoscaling"), None, None, None, None, None, None, None, None))
  }

  it should "parse a more complex yaml example" in {
    val yaml =
      """
        |---
        |stacks: [banana, cabbage]
        |templates:
        |  custom-auto:
        |    type: autoscaling
        |    parameters:
        |      paramString: value1
        |      paramNumber: 2000
        |      paramList: [valueOne, valueTwo]
        |      paramMap:
        |        txt: text/plain
        |        json: application/json
        |deployments:
        |  human:
        |    template: custom-auto
        |    dependencies: [elephant]
        |    stacks: [carrot]
        |    actions: [overridden]
        |    parameters:
        |      paramString: value2
        |  monkey:
        |    type: autoscaling
        |    app: ook
        |    dependencies: [elephant]
        |  elephant:
        |    type: dung
      """.stripMargin
    val input = RiffRaffYamlReader.fromString(yaml).valid
    input.stacks.isDefined should be(true)
    input.stacks.get.size should be(2)
    input.stacks.get should be(List("banana", "cabbage"))
    input.templates.isDefined should be(true)
    input.templates.get should be(
      Map("custom-auto" -> DeploymentOrTemplate(
        Some("autoscaling"),
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Some(Map(
          "paramString" -> JsString("value1"),
          "paramNumber" -> JsNumber(2000),
          "paramList" -> JsArray(Seq(JsString("valueOne"), JsString("valueTwo"))),
          "paramMap" -> Json.obj("txt" -> "text/plain", "json" -> "application/json")
        ))
      )))
    input.deployments.size should be(3)
    input.deployments should contain(
      "human" ->
        DeploymentOrTemplate(
          None,
          Some("custom-auto"),
          Some(List("carrot")),
          None,
          Some(List("overridden")),
          None,
          None,
          Some(List("elephant")),
          Some(Map("paramString" -> JsString("value2")))
        ))
    input.deployments should contain(
      "monkey" ->
        DeploymentOrTemplate(Some("autoscaling"),
                             None,
                             None,
                             None,
                             None,
                             Some("ook"),
                             None,
                             Some(List("elephant")),
                             None))
    input.deployments should contain(
      "elephant" ->
        DeploymentOrTemplate(Some("dung"), None, None, None, None, None, None, None, None))
    input.deployments.map(_._1) should be(List("human", "monkey", "elephant"))
  }

  it should "give an error if an extra field is on the top level object" in {
    val yaml =
      """
        |---
        |stacks: [banana]
        |template:
        |  template-in-misspelled-object:
        |    type: autoscaling
        |deployments:
        |  monkey:
        |    template: template-in-misspelled-object
      """.stripMargin
    val error = RiffRaffYamlReader.fromString(yaml).invalid
    error.errors.head.context shouldBe "Parsing YAML"
    error.errors.head.message shouldBe "Unexpected fields provided: template"
  }

  it should "give an error if an extra field is on a nested object" in {
    val yaml =
      """
        |---
        |stacks: [banana]
        |templates:
        |  template:
        |    type: autoscaling
        |    region: [region-in-misspelled-field]
        |deployments:
        |  monkey:
        |    template: template
      """.stripMargin
    val error = RiffRaffYamlReader.fromString(yaml).invalid
    error.errors.head.context shouldBe "Parsing /templates/template"
    error.errors.head.message shouldBe "Unexpected fields provided: region"
  }
}
