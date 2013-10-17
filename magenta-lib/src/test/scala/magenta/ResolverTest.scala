package magenta


import fixtures._
import fixtures.StubDeploymentType
import fixtures.StubPerAppAction
import fixtures.StubPerHostAction
import fixtures.StubTask
import json._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.io.File
import scala.Some
import tasks.{Task, CopyFile}


class ResolverTest extends FlatSpec with ShouldMatchers {

  val simpleExample = """
  {
    "packages":{
      "htmlapp":{ "type":"file", "apps":["apache"]  }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "htmlapp-only":{
        "actions":["htmlapp.deploy"],
      }
    }
  }
"""

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample, new File("/tmp"))
    val deployRecipe = parsed.recipes("htmlapp-only")

    val host = Host("host1", stage = CODE.name, tags=Map("group" -> "")).app("apache")
    val deployinfo = stubDeployInfo(host :: Nil)

    val tasks = Resolver.resolve(project(deployRecipe), deployinfo, parameters(deployRecipe))

    tasks.size should be (1)
    tasks should be (List(
      CopyFile(host, "/tmp/packages/htmlapp", "/")
    ))
  }

  val app2 = App("the_2nd_role")

  val host = Host("the_host", stage = CODE.name, tags=Map("group" -> "")).app(app1)

  val host1 = Host("host1", stage = CODE.name, tags=Map("group" -> "")).app(app1)
  val host2 = Host("host2", stage = CODE.name, tags=Map("group" -> "")).app(app1)

  val deployinfoTwoHosts = stubDeployInfo(List(host1, host2))

  it should "generate the tasks from the actions supplied" in {
    Resolver.resolve(project(baseRecipe), deployinfoSingleHost, parameters(baseRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "only generate tasks for hosts that have apps" in {
    Resolver.resolve(project(baseRecipe),
      stubDeployInfo(Host("other_host").app("other_app") :: deployinfoSingleHost.hosts), parameters(baseRecipe)) should be (List(
        StubTask("init_action_one per app task"),
        StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "generate tasks for all hosts with app" in {
    Resolver.resolve(project(baseRecipe), deployinfoTwoHosts, parameters(baseRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_one per host task on host2", Some(host2))
    ))
  }

  it should "generate tasks only for hosts with app per action" in {
    val multiRoleRecipe = {
      val doubleAppPackage = stubPackage.copy(pkgApps = Set(app1, app2))
      val appTwoPackage = stubPackage.copy(pkgApps = Set(app2))

      val doubleAppPackageType = stubPackageType(Seq("init_action_one"), Seq("action_two"))
      val appTwoPackageType = stubPackageType(Seq(), Seq("action_three"))

      Recipe("two",
        actionsBeforeApp = doubleAppPackageType.mkAction("init_action_one")(doubleAppPackage) :: Nil,
        actionsPerHost = basePackageType.mkAction("action_one")(stubPackage) ::
          doubleAppPackageType.mkAction("action_two")(doubleAppPackage) ::
          appTwoPackageType.mkAction("action_three")(appTwoPackage) :: Nil,
        dependsOn = Nil)
    }
    val host2WithApp2 = Host("host2", stage = CODE.name, tags = Map("group" -> "")).app(app2)
    val deployInfoMultiHost = stubDeployInfo(List(host1, host2WithApp2))

    Resolver.resolve(project(multiRoleRecipe), deployInfoMultiHost, parameters(multiRoleRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_two per host task on host1", Some(host1)),
      StubTask("action_two per host task on host2", Some(host2WithApp2)),
      StubTask("action_three per host task on host2", Some(host2WithApp2))
    ))
  }

  it should "resolve all actions for a given host before moving on to the next host" in {
    val allOnAllPackageType = stubPackageType(Seq("init_action_one"), Seq("action_one", "action_two"))
    val recipe = Recipe("all",
      actionsBeforeApp = allOnAllPackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      actionsPerHost = allOnAllPackageType.mkAction("action_one")(stubPackage) ::
        allOnAllPackageType.mkAction("action_two")(stubPackage) :: Nil
    )

    Resolver.resolve(project(recipe), deployinfoTwoHosts, parameters(recipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host1", Some(host1)),
      StubTask("action_two per host task on host1", Some(host1)),
      StubTask("action_one per host task on host2", Some(host2)),
      StubTask("action_two per host task on host2", Some(host2))
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val basePackageType = stubPackageType(Seq("main_init_action"), Seq("main_action"))

    val mainRecipe = Recipe("main",
      actionsBeforeApp = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("main_action")(stubPackage) :: Nil,
      dependsOn = List("one"))

    Resolver.resolve(project(mainRecipe, baseRecipe), deployinfoSingleHost, parameters(mainRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host)),
      StubTask("main_init_action per app task"),
      StubTask("main_action per host task on the_host", Some(host))
    ))

  }

  it should "only include dependencies once" in {
    val basePackageType = stubPackageType(Seq("main_init_action", "init_action_two"), Seq("main_action", "action_two"))

    val indirectDependencyRecipe = Recipe("two",
      actionsBeforeApp = basePackageType.mkAction("init_action_two")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("action_two")(stubPackage) :: Nil,
      dependsOn = List("one"))
    val mainRecipe = Recipe("main",
      actionsBeforeApp = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      actionsPerHost = basePackageType.mkAction("main_action")(stubPackage) :: Nil,
      dependsOn = List("two", "one"))

    Resolver.resolve(project(mainRecipe, indirectDependencyRecipe, baseRecipe), deployinfoSingleHost, parameters(mainRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host)),
      StubTask("init_action_two per app task"),
      StubTask("action_two per host task on the_host", Some(host)),
      StubTask("main_init_action per app task"),
      StubTask("main_action per host task on the_host", Some(host))
    ))
  }
  
  it should "provide a list of all apps for a recipe" in  {
    val recipe = Recipe("recipe", actionsPerHost = StubPerHostAction("action", Set(app1)) :: Nil)
    
    Resolver.possibleApps(project(recipe), recipe.name) should be (app1.name)
  }

  it should "disable the recipe if no hosts found and actions require some" in {
    val recipeTasks = Resolver.resolveDetail(project(baseRecipe), stubDeployInfo(List()), parameters(baseRecipe))
    recipeTasks.length should be(1)
    recipeTasks.head.disabled should be(true)
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      actionsBeforeApp =  basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      dependsOn = Nil)

    Resolver.resolve(project(nonHostRecipe), stubDeployInfo(List()), parameters(nonHostRecipe))
  }

  it should "only resolve tasks on hosts in the correct stage" in {
    Resolver.resolve(
      project(baseRecipe),
      stubDeployInfo(List(host, Host("host_in_other_stage", Set(app1), "other_stage"))),
      parameters(baseRecipe)
    ) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on the_host", Some(host))
    ))
  }

  it should "observe ordering of hosts in deployInfo" in {
    Resolver.resolve(project(baseRecipe), stubDeployInfo(List(host2, host1)), parameters(baseRecipe)) should be (List(
      StubTask("init_action_one per app task"),
      StubTask("action_one per host task on host2", Some(host2)),
      StubTask("action_one per host task on host1", Some(host1))
    ))
  }

  it should "observe ordering of hosts in deployInfo irrespective of connection user" in {
    val pkgTypeWithUser = StubDeploymentType(
      perHostActions = {
        case "deploy" => pkg => host => List(StubTask("with conn", Some(host as "user")), StubTask("without conn", Some(host)))
      }
    )
    val pkg = stubPackage.copy(pkgApps = Set(app1, app2))
    val recipe = Recipe("with-user",
      actionsPerHost = List(pkgTypeWithUser.mkAction("deploy")(pkg)))

    Resolver.resolve(project(recipe), stubDeployInfo(List(host2, host1)), parameters(recipe)) should be (List(
      StubTask("with conn", Some(host2 as "user")),
      StubTask("without conn", Some(host2)),
      StubTask("with conn", Some(host1 as "user")),
      StubTask("without conn", Some(host1))
    ))
  }

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}