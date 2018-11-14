package persistence

import java.util.UUID

import conf.Configuration
import controllers.{ApiKey, AuthorisationRecord, Logging, SimpleDeployDetail}
import deployment.{DeployFilter, PaginationView}
import magenta.RunState
import org.joda.time.{DateTime, Period}
import play.api.libs.json._
import scalikejdbc._
import utils.Json._

class PostgresDatastore extends DataStore with Logging {

  // Table: auth(email: String, content: jsonb)
  def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]] = logExceptions(Some(s"Requesting authorisation object for $email")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM auth WHERE email = $email".map(res => Json.parse(res.string(1)).as[AuthorisationRecord]).single.apply()
    }
  }

  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]] = logExceptions(Some("Requesting list of authorisation objects")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM auth".map(res => Json.parse(res.string(1)).as[AuthorisationRecord]).list().apply()
    }
  }

  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit] = logExceptions(Some(s"Creating auth object $auth")) {
    DB localTx { implicit session =>
      val json = Json.toJson(auth).toString()
      sql"INSERT INTO auth (email, content) VALUES (${auth.email}, $json::jsonb)".update.apply()
    }
  }

  def deleteAuthorisation(email: String): Either[Throwable, Unit] = logExceptions(Some(s"Deleting authorisation object for $email")) {
    DB localTx { implicit session =>
      sql"DELETE FROM auth WHERE email = $email".update.apply()
    }
  }

  // Table: apiKey(id: String, content: jsonb)
  def createApiKey(newKey: ApiKey): Unit = DB localTx { implicit session =>
    val json = Json.toJson(newKey).toString()
    sql"INSERT INTO apiKey (id, content) VALUES (${newKey.key}, $json::jsonb)".update.apply()
  }

  def getApiKeyList: Either[Throwable, List[ApiKey]] = logExceptions(Some("Requesting list of API keys")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM apiKey".map(res => Json.parse(res.string(1)).as[ApiKey]).list().apply()
    }
  }

  def getApiKey(key: String): Option[ApiKey] = DB readOnly { implicit session =>
    sql"SELECT content FROM apiKey WHERE id = $key".map(res => Json.parse(res.string(1)).as[ApiKey]).single.apply()
  }

  //TODO: Fix this
  def getAndUpdateApiKey(key: String, counter: Option[String]): Option[ApiKey] = DB localTx { implicit session =>
    val dateTime = DateTime.now().toString()
    val update =
      if(counter.isDefined)
        sqls"""{"lastUsed": "$dateTime", "callCounters": {"${counter.get}": "10"}}"""
      else
        s"""{"lastUsed": "$dateTime"}"""

    sql"""UPDATE apiKey SET content = content || $update::jsonb WHERE id = $key""".update().apply()
    sql"SELECT content FROM apiKey WHERE id = $key".map(res => Json.parse(res.string(1)).as[ApiKey]).single().apply()
  }

  def getApiKeyByApplication(application: String): Option[ApiKey] = DB readOnly { implicit session =>
    sql"SELECT content FROM apiKey WHERE content->>'application' = $application".map(res => Json.parse(res.string(1)).as[ApiKey]).single.apply()
  }

  def deleteApiKey(key: String): Unit = DB localTx { implicit session =>
    sql"DELETE FROM apiKey WHERE id = $key".update.apply()
  }

  // Table: deploy(id: String, content: jsonb)
  override def writeDeploy(deploy: DeployRecordDocument): Unit = DB localTx { implicit session =>
    val json = Json.toJson(deploy).toString()
    sql"INSERT INTO deploy (id, content) VALUES (${deploy.uuid}::uuid, $json::jsonb)".update.apply()
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] = DB readOnly { implicit session =>
    sql"SELECT content FROM deploy WHERE id = $uuid::uuid".map(res =>
      Json.parse(res.string(1)).as[DeployRecordDocument]
    ).single.apply()
  }

  override def getDeploys(filter: Option[DeployFilter], pagination: PaginationView): Either[Throwable, Iterable[DeployRecordDocument]] = DB readOnly { implicit session =>
    val whereFilters: SQLSyntax = filter.map(_.postgresFilters).getOrElse(sqls"")
    val paginationFilters = pagination.pageSize.fold(sqls"")(size => sqls"OFFSET ${size*(pagination.page-1)} LIMIT $size")
    Right(sql"SELECT content FROM deploy $whereFilters $paginationFilters".map(res =>
      Json.parse(res.string(1)).as[DeployRecordDocument]
    ).list.apply())
  }

  override def updateStatus(uuid: UUID, status: RunState): Unit = DB localTx { implicit session =>
    val update = Json.toJson(Map("status" -> status.entryName)).toString()
    sql"UPDATE deploy SET content = content || $update WHERE id = $uuid".update.apply()
  }

  override def updateDeploySummary(uuid: UUID, totalTasks: Option[Int], completedTasks: Int, lastActivityTime: DateTime, hasWarnings: Boolean): Unit = DB localTx { implicit session =>
    val updates: Map[String, Any] = Map(
      "completedTasks" -> completedTasks,
      "lastActivityTime" -> lastActivityTime,
      "hasWarnings" -> hasWarnings) ++ totalTasks.map("totalTasks" -> _)
    sql"UPDATE deploy SET content = content || $updates WHERE id = $uuid".update.apply()
  }

  // Used in testing
  override def getDeployUUIDs(limit: Int = 0): Iterable[SimpleDeployDetail] = DB readOnly { implicit session =>
    val limitSQL = if (limit == 0) sqls"" else sqls"LIMIT $limit"
    sql"SELECT id, content->>'startTime' FROM deploy ORDER BY content.startTime $limitSQL".map(res => Json.parse(res.string(1)).as[SimpleDeployDetail]).single.apply()
  }

  override def countDeploys(filter: Option[DeployFilter]): Int = DB readOnly { implicit session =>
    val whereFilters = filter.map(_.postgresFilters).getOrElse(List.empty)
    sql"SELECT count(*) FROM deploy $whereFilters".map(_.int(1)).single.apply().get
  }

  override def getCompleteDeploysOlderThan(dateTime: DateTime): Iterable[SimpleDeployDetail] = DB readOnly { implicit session =>
    sql"SELECT id, content->>'startTime' FROM deploy WHERE (content->>'startTime')::TIMESTAMP < $dateTime::TIMESTAMP AND (content->>'summarised') IS NOT NULL"
      .map(res => Json.parse(res.string(1)).as[SimpleDeployDetail]).list.apply()
  }

  //TODO: Figure out how to do the update
  override def addMetaData(uuid: UUID, metaData: Map[String, String]): Unit = DB localTx { implicit session =>
    val update = metaData.map { case (tag, value) =>
      sqls"content->>'{parameters,tags,$tag}'" -> value
    }
    if (update.nonEmpty) sql"UPDATE deploy SET content || $update".update.apply()
  }

  override def findProjects(): Either[Throwable, List[String]] = logExceptions(None) {
    DB readOnly { implicit session =>
      sql"SELECT DISTINCT ON (content->'{parameters.projectName}') content FROM deploy".map(_.string(1)).list.apply()
    }
  }

  override def addStringUUID(uuid: UUID): Unit = DB localTx { implicit session =>
    sql"UPDATE deploy SET stringUUID = ${uuid.toString} WHERE id = $uuid".update.apply()
  }

  override def getDeployUUIDsWithoutStringUUIDs: Iterable[SimpleDeployDetail] = DB readOnly { implicit session =>
    sql"SELECT id, content->>'startTime' FROM deploy WHERE (content->>'stringUUID') IS NULL"
      .map(res => Json.parse(res.string(1)).as[SimpleDeployDetail]).list.apply()
  }

  override def getLastCompletedDeploys(projectName: String): Map[String,UUID] = DB readOnly { implicit session =>
    val threshold = new DateTime().minus(new Period().withDays(90))

    ???
  }

  override def summariseDeploy(uuid: UUID): Unit = DB localTx { implicit session =>
    sql"UPDATE deploy SET content->>'summarised' = 'true' WHERE id = $uuid".update.apply()
    sql"DELETE FROM deployLog WHERE id = $uuid".update.apply()
  }

  override def deleteDeployLog(uuid: UUID): Unit = DB localTx { implicit session =>
    sql"DELETE FROM deploy WHERE id = $uuid".update.apply()
    sql"DELETE FROM deployLog WHERE id = $uuid".update.apply()
  }

  // Table: deployLog(id: String, content: jsonb)
  override def writeLog(log: LogDocument): Unit = DB localTx { implicit session =>
    sql"INSERT INTO deployLog (id, content) VALUES (${log.id}, $log::jsonb)".update.apply()
  }

  override def readLogs(uuid: UUID): Iterable[LogDocument] = DB readOnly { implicit session =>
    sql"SELECT content FROM deployLog WHERE id = ${uuid.toString}".map(res =>
      Json.parse(res.string(1)).as[LogDocument]
    ).list.apply()
  }
}

object PostgresDatastore {
  def buildDatastore() = {
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton(Configuration.postgres.url, Configuration.postgres.user, Configuration.postgres.password)

    new PostgresDatastore
  }
}