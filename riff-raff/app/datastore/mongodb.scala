package datastore

import java.util.UUID
import deployment.Task
import lifecycle.Lifecycle
import magenta._
import com.mongodb.casbah.{MongoURI, MongoDB, MongoConnection}
import com.mongodb.casbah.Imports._
import conf.Configuration
import controllers.Logging
import com.novus.salat._
import play.Application
import play.api.Play
import play.api.Play.current
import deployment.DeployRecord
import magenta.DeployParameters
import magenta.MessageStack
import magenta.Deployer
import scala.Some
import magenta.Build
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers

object MongoDatastore extends Lifecycle with Logging {
  def buildDatastore(app:Application) = try {
    if (Configuration.mongo.isConfigured) {
      val uri = MongoURI(Configuration.mongo.uri.get)
      val mongoConn = MongoConnection(uri)
      val mongoDB = mongoConn(uri.database.getOrElse(Configuration.mongo.database))
      if (mongoDB.authenticate(uri.username.get,new String(uri.password.get))) {
        RegisterJodaTimeConversionHelpers()
        Some(new MongoDatastore(mongoDB, app.classloader()))
      } else {
        log.error("Authentication to mongoDB failed")
        None
      }
    } else None
  } catch {
    case e:Throwable =>
      log.error("Couldn't initialise MongoDB connection", e)
      None
  }

  def init(app:Application) {
    val datastore = buildDatastore(app)
    datastore.foreach(DataStore.register(_))
  }
  def shutdown(app:Application) { DataStore.unregisterAll() }

  val testUUID = UUID.randomUUID()
  val testParams = DeployParameters(Deployer("Simon Hildrew"), Build("tools::deploy", "182"), Stage("DEV"))
  val testRecord = DeployRecord(Task.Deploy, testUUID, testParams)
  val testStack1 = MessageStack(List(Deploy(testParams)))
  val testStack2 = MessageStack(List(Info("Test info message"),Deploy(testParams)))
}

class MongoDatastore(database: MongoDB, loader: ClassLoader) extends DataStore {
  implicit val context = {
    val context = new Context {
      val name = "global"
      override val typeHintStrategy = StringTypeHintStrategy(TypeHintFrequency.Always)
    }
    context.registerClassLoader(loader)
    context.registerPerClassKeyOverride(classOf[DeployRecord], remapThis = "uuid", toThisInstead = "_id")
    context
  }

  val recordGrater = grater[DeployRecord]
  val stackGrater = grater[MessageStack]
  val deployCollection = database("deploys")

  def createDeploy(record: DeployRecord) {
    val dbObject = recordGrater.asDBObject(record)
    deployCollection insert dbObject
  }
  def updateDeploy(uuid: UUID, stack: MessageStack) {
    val newMessageStack = stackGrater.asDBObject(stack)
    deployCollection.update(MongoDBObject("_id" -> uuid), $push("messageStacks" -> newMessageStack))
  }
  def getDeploy(uuid: UUID): Option[DeployRecord] = {
    val deploy = deployCollection.findOneByID(uuid)
    deploy.map(recordGrater.asObject(_))
  }

  def getDeploys(limit: Int): Iterable[DeployRecord] = {
    val deploys = deployCollection.find().sort(MongoDBObject("time" -> -1)).limit(limit)
    deploys.toIterable.map(recordGrater.asObject(_))
  }
}