package persistence

import magenta._
import deployment.{PaginationView, DeployFilter}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.mongodb.casbah.MongoCursor
import org.joda.time.DateMidnight

object `package` {
  implicit def deployFilter2Criteria(filter: DeployFilter) = new {
    def criteria: DBObject = {
      val criteriaList: List[(String, Any)] = Nil ++
        filter.projectName.map(p => ("parameters.projectName", ("(?i)%s" format p).r)) ++
        filter.stage.map(("parameters.stage", _)) ++
        filter.deployer.map(("parameters.deployer", _)) ++
        filter.status.map(s => ("status", s.toString)) ++
        filter.task.map(t => ("parameters.deployType", t.toString))
      filter.maxDaysAgo match {
        case None => MongoDBObject(criteriaList)
        case Some(days) => MongoDBObject(criteriaList) ++ ("startTime" $gt (new DateMidnight()).minusDays(days).toDate)
      }
    }
  }

  implicit def mongoCursor2pagination(cursor: MongoCursor) = new {
    def pagination(p: PaginationView): MongoCursor = {
      p.pageSize.map(l => cursor.skip(p.skip.get).limit(l)).getOrElse(cursor)
    }
  }

  implicit def message2MessageDocument(message: Message) = new {
    def asMessageDocument: MessageDocument = MessageDocument(message)
  }
}