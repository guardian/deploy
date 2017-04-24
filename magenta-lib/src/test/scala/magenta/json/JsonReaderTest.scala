package magenta
package json

import magenta.artifact.{S3JsonArtifact, S3Path}
import magenta.deployment_type.AutoScaling
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsString, Json}

class JsonReaderTest extends FlatSpec with Matchers {
  val deploymentTypes = Seq(AutoScaling)
  val deployJsonExample = parseOrDie("""
  {
    "stack":"content-api",
    "packages":{
      "index-builder":{
        "type":"autoscaling",
        "apps":["index-builder"]
      },
      "api":{
        "type":"autoscaling",
        "apps":["api"],
        "data": {
          "healthcheck_paths": [
            "/api/index.json",
            "/api/search.json"
          ]
        }
      },
      "solr":{
        "type":"autoscaling",
        "apps":["solr"],
        "data": {
          "port": "8400"
        }
      }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "index-build-only":{
        "default":false,
        "actions":["index-builder.deploy"]
      },
      "api-only":{
        "default":false,
        "actions":[
          "api.deploy","solr.deploy"
        ]
      },
      "api-counter-only":{
        "default":false,
        "actionsPerHost":[
          "api.deploy","solr.deploy"
        ],
        "actionsBeforeApp":[
          "api.uploadArtifacts","solr.uploadArtifacts"
        ]
      }
    }
  }
  """)

  "json parser" should "parse json and resolve links" in {
    val parsedProject =
      JsonReader.buildProject(deployJsonExample, S3JsonArtifact("artifact-bucket", "test/123"), deploymentTypes)

    parsedProject.applications should be(Set(App("index-builder"), App("api"), App("solr")))

    parsedProject.packages.size should be(3)
    parsedProject.packages("index-builder") shouldBe
      DeploymentPackage("index-builder",
                        Seq(App("index-builder")),
                        Map.empty,
                        "autoscaling",
                        S3Path("artifact-bucket", "test/123/packages/index-builder/"),
                        true,
                        deploymentTypes)
    parsedProject.packages("api") shouldBe
      DeploymentPackage(
        "api",
        Seq(App("api")),
        Map("healthcheck_paths" -> Json.arr("/api/index.json", "/api/search.json")),
        "autoscaling",
        S3Path("artifact-bucket", "test/123/packages/api/"),
        true,
        deploymentTypes
      )
    parsedProject.packages("solr") shouldBe
      DeploymentPackage("solr",
                        Seq(App("solr")),
                        Map("port" -> JsString("8400")),
                        "autoscaling",
                        S3Path("artifact-bucket", "test/123/packages/solr/"),
                        true,
                        deploymentTypes)

    val recipes = parsedProject.recipes
    recipes.size should be(4)
    recipes("all") should be(Recipe("all", Nil, List("index-build-only", "api-only")))

    val apiCounterRecipe = recipes("api-counter-only")

    apiCounterRecipe.deploymentSteps.toSeq.length should be(4)
  }

  val minimalExample = parseOrDie("""
{
  "packages": {
    "dinky": { "type": "autoscaling" }
  }
}
""")

  "json parser" should "infer a single app if none specified" in {
    val parsedProject =
      JsonReader.buildProject(minimalExample, S3JsonArtifact("artifact-bucket", "test/123"), deploymentTypes)

    parsedProject.applications should be(Set(App("dinky")))
  }

  val twoPackageExample = parseOrDie("""
{
  "packages": {
    "one": { "type": "autoscaling" },
    "two": { "type": "autoscaling" }
  }
}
""")

  "json parser" should "infer a default recipe that deploys all packages" in {
    val parsedProject =
      JsonReader.buildProject(twoPackageExample, S3JsonArtifact("artifact-bucket", "test/123"), deploymentTypes)

    val recipes = parsedProject.recipes
    recipes.size should be(1)
    recipes("default") should be(
      Recipe("default", deploymentSteps = parsedProject.packages.values.map(_.mkDeploymentStep("deploy"))))
  }

  "json parser" should "default to using the package name for the file name" in {
    val parsedProject =
      JsonReader.buildProject(minimalExample, S3JsonArtifact("artifact-bucket", "test/123"), deploymentTypes)

    parsedProject.packages("dinky").s3Package should be(S3Path("artifact-bucket", "test/123/packages/dinky/"))
  }

  val withExplicitFileName = parseOrDie("""
{
  "packages": {
    "dinky": {
      "type": "autoscaling",
      "fileName": "awkward"
    }
  }
}
""")

  "json parser" should "use override file name if specified" in {
    val parsedProject =
      JsonReader.buildProject(withExplicitFileName, S3JsonArtifact("artifact-bucket", "test/123"), deploymentTypes)

    parsedProject.packages("dinky").s3Package should be(S3Path("artifact-bucket", "test/123/packages/awkward/"))
  }

  def parseOrDie(s: String) = JsonInputFile.parse(s).right.get
}
