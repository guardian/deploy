package magenta
package cli

import java.io.File
import json.{DeployInfoJsonReader, JsonReader}
import scopt.OptionParser
import HostList._
import tasks.CommandLocator

object Main extends scala.App {

  val sink = new MessageSink {
    def message(stack: MessageStack) {
      val indent = "  " * (stack.messages.size - 1)
      stack.top match {
        case Verbose(message) => if (Config.verbose) Console.out.println(indent + message)
        case TaskList(tasks) =>
          MessageBroker.infoContext("Tasks to execute: ") {
            tasks.zipWithIndex.foreach { case (task, idx) =>
              MessageBroker.info("%d. %s" format (idx + 1, task.fullDescription))
              MessageBroker.verbose(task.verbose)
            }
          }
        case StartContext(Info(message)) => { Console.out.println(indent + message) }
        case FinishContext(original) => {}
        case _ => Console.out.println(indent + stack.top.text)
      }
    }
  }

  MessageBroker.subscribe(sink)

  object Config {

    var project: Option[String] = None
    var build: Option[String] = None
    var host: Option[String] = None
    var deployer: Deployer = Deployer("unknown")
    var stage = ""
    var recipe = RecipeName("default")
    var verbose = false
    var dryRun = false

    var keyLocation: Option[File] = None
    var jvmSsh = false

    var deployInfo = "/opt/bin/deployinfo.json"

    lazy val parsedDeployInfo = {
      import sys.process._
      DeployInfoJsonReader.parse(deployInfo.!!)
    }

    private var _localArtifactDir: Option[File] = None

    def localArtifactDir_=(dir: Option[File]) {
      dir.foreach { f =>
        if (!f.exists() || !f.isDirectory) sys.error("Directory not found.")
        MessageBroker.info("WARN: Ignoring <project> and <build>; using local artifact directory of " + f.getAbsolutePath)
      }

      _localArtifactDir = dir
    }

    def localArtifactDir = _localArtifactDir
  }

  object ManagementBuildInfo {
    lazy val version = Option(getClass.getPackage.getImplementationVersion) getOrElse "DEV"
  }

  val programName = "magenta"
  val programVersion = ManagementBuildInfo.version

  val parser = new OptionParser(programName, programVersion) {
    help("h", "help", "show this usage message")

    separator("\n  What to deploy:")
    opt("r", "recipe", "recipe to execute (default: 'default')", { r => Config.recipe = RecipeName(r) })
    opt("t", "host", "only deply to the named host", { h => Config.host = Some(h) })

    separator("\n  Diagnostic options:")
    opt("v", "verbose", "verbose logging", { Config.verbose = true } )
    opt("n", "dry-run", "don't execute any tasks, just show what would be done", { Config.dryRun = true })
    opt("deployer", "fullname or username of person executing the deployment", { name => Config.deployer = Deployer(name)})

    separator("\n  Advanced options:")
    opt("local-artifact", "Path to local artifact directory (overrides <project> and <build>)",
      { dir => Config.localArtifactDir = Some(new File(dir)) })
    opt("deployinfo", "use a different deployinfo script", { deployinfo => Config.deployInfo = deployinfo })
    opt("path", "Path for deploy support scripts (default: '/opt/deploy/bin')", { path => CommandLocator.rootPath = path })
    opt("i", "keyLocation", "specify location of SSH key file", {keyLocation => Config.keyLocation = Some(validFile(keyLocation))})
    opt("j", "jvm-ssh", "perform ssh within the JVM, rather than shelling out to do so", { Config.jvmSsh = true })

    separator("\n")

    arg("<stage>", "Stage to deploy (e.g. TEST)", { s => Config.stage = s })
    argOpt("<project>", "TeamCity project name (e.g. tools::stats-aggregator)", { p => Config.project = Some(p) })
    argOpt("<build>", "TeamCity build number", { b => Config.build = Some(b) })
  }

  def validFile(s: String) = {
    val file = new File(s)
    if (file.exists() && file.isFile) file else sys.error("File not found: %s" format (s))
  }

  if (parser.parse(args)) {
    try {
      val parameters = DeployParameters(Config.deployer,Build(Config.project.get,Config.build.get),Stage(Config.stage),Config.recipe)

      MessageBroker.deployContext(parameters) {

        MessageBroker.info("%s build %s" format (programName, programVersion))

        MessageBroker.info("Locating artifact...")
        val dir = Config.localArtifactDir getOrElse Artifact.download(Config.project, Config.build)

        MessageBroker.info("Loading project file...")
        val project = JsonReader.parse(new File(dir, "deploy.json"))

        MessageBroker.verbose("Loaded: " + project)
        val hostsInStage = Config.parsedDeployInfo.filter(_.stage == Config.stage)
        val hosts = Config.host map { h => hostsInStage.filter(_.name == h) } getOrElse hostsInStage

        val context = DeployContext(parameters,project,hosts)

        if (Config.dryRun) {

          val tasks = context.tasks
          MessageBroker.info("Tasks to execute: ")
          tasks.zipWithIndex.foreach { case (task, idx) =>
            MessageBroker.info("%d. %s" format (idx + 1, task.fullDescription))
            MessageBroker.verbose(task.verbose)
          }

          MessageBroker.info("Dry run requested. Not executing.")

        } else {

          val credentials = if (Config.jvmSsh) {
            val passphrase = System.console.readPassword("Please enter your passphrase:")
            PassphraseProvided(System.getenv("USER"), passphrase.toString, Config.keyLocation)
          } else SystemUser(keyFile = Config.keyLocation)

          context.execute(KeyRing(credentials))
        }
      }

      MessageBroker.info("Done")

    } catch {
      case e: UsageError =>
        Console.err.println("Error: " + e.getMessage)
        parser.showUsage
    }
  }
}