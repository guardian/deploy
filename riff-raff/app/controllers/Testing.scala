package controllers

import play.api.mvc.Controller
import magenta.tasks.Task
import magenta._
import collection.mutable.ArrayBuffer
import magenta.CommandOutput
import magenta.Info
import magenta.CommandError
import magenta.FinishContext
import magenta.RecipeName
import magenta.DeployParameters
import magenta.StartContext
import magenta.TaskRun
import magenta.Verbose
import magenta.KeyRing
import magenta.MessageStack
import magenta.Deploy
import magenta.Deployer
import magenta.Stage
import magenta.Build

object Testing extends Controller with Logging {
  def reportTestPartial(verbose: Boolean) = NonAuthAction { implicit request =>
    val task1 = new Task {
      def execute(sshCredentials: KeyRing) {}
      def description = "Test task that does stuff, the first time"
      def verbose = "A particularly verbose task description that lists some stuff, innit"
    }
    val task2 = new Task {
      def execute(sshCredentials: KeyRing) {}
      def description = "Test task that does stuff"
      def verbose = "A particularly verbose task description that lists some stuff, innit"
    }
    val input = ArrayBuffer(
      MessageStack(List(
        StartContext(Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default")))))),
      MessageStack(List(
        Info("Downloading artifact"),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        Verbose("Downloading from http://teamcity.gudev.gnl:8111/guestAuth/repository/download/tools%3A%3Adeploy/131/artifacts.zip to /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15..."),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        Verbose("http: teamcity.gudev.gnl GET /guestAuth/repository/download/tools%3A%3Adeploy/131/artifacts.zip HTTP/1.1"),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        Verbose("""downloaded:
      /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15/deploy.json
    /var/folders/ZO/ZOSa3fR3FsCiU3jxetWKQU+++TQ/-Tmp-/sbt_5489e15/packages/riff-raff/riff-raff.jar"""),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        Info("Reading deploy.json"),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        StartContext(TaskRun(task1)),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        FinishContext(TaskRun(task1)),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        StartContext(TaskRun(task2)),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        StartContext(Info("$ command line action")),
        TaskRun(task2),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        CommandOutput("Some command output from command line action"),
        Info("$ command line action"),
        TaskRun(task2),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        CommandError("Some command error from command line action"),
        Info("$ command line action"),
        TaskRun(task2),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default"))))),
      MessageStack(List(
        CommandOutput("Some more command output from command line action"),
        Info("$ command line action"),
        TaskRun(task2),
        Deploy(DeployParameters(Deployer("Simon Hildrew"),Build("tools::deploy","131"),Stage("DEV"),RecipeName("default")))))
    )

    val report = DeployReport(input.toList, "Deployment report")

    Ok(views.html.reportTest(request,report,verbose))
  }


}
