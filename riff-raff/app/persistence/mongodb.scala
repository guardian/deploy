package persistence

import java.util.{UUID}
import com.mongodb.casbah.{MongoURI, MongoConnection}
import com.mongodb.casbah.Imports._
import conf.Configuration
import controllers.{AuthorisationRecord, Logging}
import com.novus.salat._
import play.api.Application
import deployment.{PaginationView, DeployFilter}
import magenta.{RunState, MessageStack}
import scala.Some
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import notification.{HookAction, HookCriteria}
import com.mongodb.casbah.commons.MongoDBObject
import org.joda.time.DateTime
import controllers.SimpleDeployDetail
import java.util
import com.novus.salat.util

trait MongoSerialisable {
  def dbObject: DBObject
}

trait CollectionStats {
  def dataSize: Long
  def storageSize: Long
  def documentCount: Long
}

object MongoDatastore extends Logging {

  val MESSAGE_STACKS = "messageStacks"

  def buildDatastore(app:Option[Application]) = try {
    if (Configuration.mongo.isConfigured) {
      val uri = MongoURI(Configuration.mongo.uri.get)
      val mongoConn = MongoConnection(uri)
      val mongoDB = mongoConn(uri.database.get)

      if ( uri.username.isEmpty || uri.password.isEmpty ||
             mongoDB.authenticate(uri.username.get,new String(uri.password.get)) ) {
        Some(new MongoDatastore(mongoDB, app.map(_.classloader)))
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
}

trait RiffRaffGraters {
  RegisterJodaTimeConversionHelpers()
  def loader:Option[ClassLoader]
  val riffRaffContext = {
    val context = new Context {
      val name = "global"
      override val typeHintStrategy = StringTypeHintStrategy(TypeHintFrequency.Always)
    }
    loader.foreach(context.registerClassLoader(_))
    context
  }
  val stackGrater = {
    implicit val context = riffRaffContext
    grater[MessageStack]
  }
}

class MongoDatastore(database: MongoDB, val loader: Option[ClassLoader]) extends DataStore with DocumentStore with RiffRaffGraters with DocumentGraters with Logging {
  val deployV2Collection = database("%sdeployV2" format Configuration.mongo.collectionPrefix)
  val deployV2LogCollection = database("%sdeployV2Logs" format Configuration.mongo.collectionPrefix)
  val hooksCollection = database("%shooks" format Configuration.mongo.collectionPrefix)
  val authCollection = database("%sauth" format Configuration.mongo.collectionPrefix)

  val collections = List(deployV2Collection, deployV2LogCollection, hooksCollection, authCollection)

  private def collectionStats(collection: MongoCollection): CollectionStats = {
    val stats = collection.stats
    new CollectionStats {
      def dataSize = logAndSquashExceptions(None,0L){ stats.getLong("size", 0L) }
      def storageSize = logAndSquashExceptions(None,0L){ stats.getLong("storageSize", 0L) }
      def documentCount = logAndSquashExceptions(None,0L){ collection.count }
    }
  }

  override def collectionStats: Map[String, CollectionStats] = collections.map(coll => (coll.name, collectionStats(coll))).toMap

  // ensure indexes
  deployV2Collection.ensureIndex("startTime")
  deployV2LogCollection.ensureIndex("deploy")

  override def getPostDeployHooks = hooksCollection.find().map{ dbo =>
    val criteria = HookCriteria(dbo.as[DBObject]("_id"))
    val action = HookAction(dbo.as[String]("url"),dbo.as[Boolean]("enabled"))
    criteria -> action
  }.toMap

  override def getPostDeployHook(criteria: HookCriteria) =
    logAndSquashExceptions[Option[HookAction]](Some("Requesting post deploy hook for %s" format criteria),None) {
      hooksCollection.find(MongoDBObject("_id" -> criteria.dbObject)).map(HookAction(_)).toSeq.headOption
    }

  override def setPostDeployHook(criteria: HookCriteria, action: HookAction) {
    logAndSquashExceptions(Some("Creating post deploy hook %s" format criteria),()) {
      val criteriaId = MongoDBObject("_id" -> criteria.dbObject)
      hooksCollection.findAndModify(
        query = criteriaId,
        update = criteriaId ++ action.dbObject,
        upsert = true, fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew=false
      )
    }
  }

  override def deletePostDeployHook(criteria: HookCriteria) {
    logAndSquashExceptions(Some("Deleting post deploy hook %s" format criteria),()) {
      hooksCollection.findAndRemove(MongoDBObject("_id" -> criteria.dbObject))
    }
  }

  override def setAuthorisation(auth: AuthorisationRecord) {
    logAndSquashExceptions(Some("Creating auth object %s" format auth),()) {
      val criteriaId = MongoDBObject("_id" -> auth.email)
      authCollection.findAndModify(
        query = criteriaId,
        update = auth.dbObject,
        upsert = true, fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew=false
      )
    }
  }

  override def getAuthorisation(email: String): Option[AuthorisationRecord] =
    logAndSquashExceptions[Option[AuthorisationRecord]](Some("Requesting authorisation object for %s" format email),None) {
      authCollection.find(MongoDBObject("_id" -> email)).map(AuthorisationRecord(_)).toSeq.headOption
    }

  override def getAuthorisationList: List[AuthorisationRecord] =
    logAndSquashExceptions[List[AuthorisationRecord]](Some("Requesting list of authorisation objects"), Nil) {
      authCollection.find().map(AuthorisationRecord(_)).toList
    }

  override def deleteAuthorisation(email: String) {
    logAndSquashExceptions(Some("Deleting authorisation object for %s" format email),()) {
      authCollection.findAndRemove(MongoDBObject("_id" -> email))
    }
  }

  override def writeDeploy(deploy: DeployRecordDocument) {
    logAndSquashExceptions(Some("Saving deploy record document for %s" format deploy.uuid),()) {
      val gratedDeploy = deployGrater.asDBObject(deploy)
      deployV2Collection.insert(gratedDeploy)
    }
  }

  override def updateStatus(uuid: UUID, status: RunState.Value) {
    logAndSquashExceptions(Some("Updating status of %s to %s" format (uuid, status)), ()) {
      deployV2Collection.update(MongoDBObject("_id" -> uuid), $set("status" -> status.toString), concern=WriteConcern.Safe)
    }
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] =
    logAndSquashExceptions[Option[DeployRecordDocument]](Some("Retrieving deploy record document for %s" format uuid), None) {
      deployV2Collection.findOneByID(uuid).map(deployGrater.asObject(_))
    }

  override def writeLog(log: LogDocument) {
    logAndSquashExceptions(Some("Writing new log document with id %s for deploy %s" format (log.id, log.deploy)),()) {
      deployV2LogCollection.insert(logDocumentGrater.asDBObject(log), WriteConcern.Safe)
    }
  }

  override def readLogs(uuid: UUID): Iterable[LogDocument] =
    logAndSquashExceptions[Iterable[LogDocument]](Some("Retriving logs for deploy %s" format uuid),Nil) {
      val criteria = MongoDBObject("deploy" -> uuid)
      deployV2LogCollection.find(criteria).toIterable.map(logDocumentGrater.asObject(_))
    }

  override def getDeployV2UUIDs(limit: Int = 0) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    val cursor = deployV2Collection.find(MongoDBObject(), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    val limitedCursor = if (limit == 0) cursor else cursor.limit(limit)
    limitedCursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime").get
      SimpleDeployDetail(uuid, dateTime)
    }
  }

  override def getDeploysV2(filter: Option[DeployFilter], pagination: PaginationView) = logAndSquashExceptions[Iterable[DeployRecordDocument]](None,Nil){
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    val cursor = deployV2Collection.find(criteria).sort(MongoDBObject("startTime" -> -1)).pagination(pagination)
    cursor.toIterable.map { deployGrater.asObject(_) }
  }

  override def countDeploysV2(filter: Option[DeployFilter]) = logAndSquashExceptions[Int](Some("Counting documents matching filter"),0) {
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    deployV2Collection.count(criteria).toInt
  }

  override def deleteDeployLogV2(uuid: UUID) {
    logAndSquashExceptions(None,()) {
      deployV2Collection.findAndRemove(MongoDBObject("_id" -> uuid))
      deployV2LogCollection.remove(MongoDBObject("deploy" -> uuid))
    }
  }
}