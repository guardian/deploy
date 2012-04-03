package magenta

import tasks.Task
import collection.SortedSet

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    connectAs: Option[String] = None)
{
  def app(name: String) = this.copy(apps = apps + App(name))
  def app(app: App) = this.copy(apps= apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name
}

class HostList(hosts: List[Host]) {
  def supportedApps = {
    val apps = for {
      host <- hosts
      app <- host.apps
    } yield app.name
    SortedSet(apps: _*).mkString(", ")
  }

  def dump = hosts
    .sortBy { _.name }
    .map { h => " %s: %s" format (h.name, h.apps.map { _.name } mkString ", ") }
    .mkString("\n")
}
object HostList {
  implicit def listOfHostsAsHostList(hosts: List[Host]) = new HostList(hosts)
}

/*
 An action represents a step within a recipe. It isn't executable
 until it's resolved against a particular host.
 */
trait Action {
  def apps: Set[App]
  def description: String
}

trait PerHostAction extends Action {
  def resolve(host: Host): List[Task]
}

trait PerAppAction extends Action {
  def resolve(stage: Stage): List[Task]
}

case class App(name: String)

case class Recipe(
  name: String,
  actionsBeforeApp: List[Action] = Nil, //executed once per app (before the host actions are executed)
  actionsPerHost: List[Action] = Nil,  //executed once per host in the application
  dependsOn: List[String] = Nil
)

case class Project(
  packages: Map[String, Package] = Map.empty,
  recipes: Map[String, Recipe] = Map.empty
) {
  lazy val applications = packages.values.flatMap(_.apps).toSet
}

case class Stage(name: String)