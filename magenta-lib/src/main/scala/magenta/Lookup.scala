package magenta

import org.joda.time.DateTime

trait Data {
  def keys: List[String]
  def all: Map[String,List[Datum]]
  def get(key:String): List[Datum] = all.get(key).getOrElse(Nil)
  def datum(key: String, app: App, stage: Stage): Option[Datum]
}

trait Instances {
  def all:List[Host]
  def get(app: App, stage: Stage):List[Host]
}

trait Lookup {
  def lastUpdated: DateTime
  def instances: Instances
  def stages: List[String]
  def data: Data
  def credentials(stage: Stage, apps: Set[App]): Map[String, ApiCredentials]
}

trait SecretProvider {
  def lookup(service: String, account: String): Option[String]
}

case class DeployInfoLookupShim(deployInfo: DeployInfo, secretProvider: SecretProvider) extends Lookup {
  def lastUpdated: DateTime = deployInfo.createdAt.getOrElse(new DateTime(0L))

  def instances: Instances = new Instances {
    def get(app: App, stage: Stage): List[Host] = all.filter { host =>
      host.stage == stage.name && host.apps.contains(app)
    }
    def all: List[Host] = deployInfo.hosts
  }

  def data: Data = new Data {
    def keys: List[String] = deployInfo.knownKeys
    def all: Map[String, List[Datum]] = deployInfo.data
    def datum(key: String, app: App, stage: Stage): Option[Datum] = deployInfo.firstMatchingData(key, app, stage.name)
  }

  def stages = deployInfo.knownHostStages

  def credentials(stage: Stage, apps: Set[App]): Map[String, ApiCredentials] =
    apps.toList.flatMap {
      app => {
        val KeyPattern = """credentials:(.*)""".r
        val apiCredentials = data.keys flatMap {
          case key@KeyPattern(service) =>
            data.datum(key, app, stage).flatMap { data =>
              secretProvider.lookup(service, data.value).map { secret =>
                service -> ApiCredentials(service, data.value, secret, data.comment)
              }
            }
          case _ => None
        }
        apiCredentials
      }
    }.distinct.toMap
}