package test

import java.util.UUID

import deployment.DeployRecord
import gnieh.diffson.playJson._
import magenta._
import magenta.input.All
import org.joda.time.DateTime
import persistence.{LogDocument, RecordConverter}
import play.api.libs.json.Json

trait Utilities {
  def compareJson(from: String, to: String) = JsonDiff.diff(Json.parse(from), Json.parse(to), remember = true)
}

trait PersistenceTestInstances {
  val testTime = new DateTime()
  lazy val testUUID = UUID.fromString("90013e69-8afc-4ba2-80a8-d7b063183d13")
  lazy val parameters = DeployParameters(Deployer("Tester"),
                                         Build("test-project", "1"),
                                         Stage("CODE"),
                                         RecipeName("test-recipe"),
                                         selector = All)
  lazy val testParamsWithHosts = parameters.copy(hostList = List("host1", "host2"))
  lazy val testRecord = DeployRecord(testTime, testUUID, parameters, Map("branch" -> "master"), messageWrappers)
  lazy val testDocument = RecordConverter(testRecord).deployDocument

  lazy val comprehensiveDeployRecord = {
    val time = new DateTime(2012, 11, 8, 17, 20, 0)
    val uuid = UUID.fromString("39320f5b-7837-4f47-85f7-bc2d780e19f6")
    val parameters =
      DeployParameters(Deployer("Tester"),
                       Build("test::project", "1"),
                       Stage("TEST"),
                       RecipeName("test-recipe"),
                       Nil,
                       List("testhost1", "testhost2"))
    DeployRecord(time, uuid, parameters, Map("branch" -> "test"), messageWrappers)
  }

  def stack(messages: Message*): MessageStack = {
    stack(testTime, messages: _*)
  }

  def stack(time: DateTime, messages: Message*): MessageStack = {
    MessageStack(messages.toList, time)
  }

  val deploy = Deploy(parameters)
  val startDeploy = StartContext(deploy)
  val infoMsg = Info("$ echo hello")
  val startInfo = StartContext(infoMsg)
  val cmdOut = CommandOutput("hello")
  val verbose = Verbose("return value 0")
  val finishDep = FinishContext(deploy)
  val finishInfo = FinishContext(infoMsg)
  val failInfo = FailContext(infoMsg)
  val failDep = FailContext(deploy)
  val warning = Warning("deprecation")
  val messageStacks: List[MessageStack] =
    stack(startDeploy) ::
      stack(startInfo, deploy) ::
      stack(cmdOut, infoMsg, deploy) ::
      stack(verbose, infoMsg, deploy) ::
      stack(finishInfo, deploy) ::
      stack(finishDep) ::
      Nil

  val deployMessageId = UUID.randomUUID()
  val infoMessageId = UUID.randomUUID()

  def wrapper(id: UUID, parentId: Option[UUID], stack: MessageStack): MessageWrapper = {
    MessageWrapper(MessageContext(testUUID, parameters, parentId), id, stack)
  }

  def wrapper(parentId: Option[UUID], stack: MessageStack): MessageWrapper = {
    MessageWrapper(MessageContext(testUUID, parameters, parentId), UUID.randomUUID(), stack)
  }

  val startDeployWrapper = wrapper(deployMessageId, None, stack(startDeploy))
  val finishDeployWrapper = wrapper(UUID.randomUUID(), Some(deployMessageId), stack(finishDep, deploy))

  val messageWrappers: List[MessageWrapper] =
    startDeployWrapper ::
      wrapper(infoMessageId, Some(deployMessageId), stack(startInfo, deploy)) ::
      wrapper(Some(infoMessageId), stack(cmdOut, infoMsg, deploy)) ::
      wrapper(Some(infoMessageId), stack(verbose, infoMsg, deploy)) ::
      wrapper(Some(infoMessageId), stack(finishInfo, infoMsg, deploy)) ::
      finishDeployWrapper ::
      Nil

  val logDocuments: List[LogDocument] = messageWrappers.map { wrapper =>
    LogDocument(wrapper.context.deployId,
                wrapper.messageId,
                wrapper.context.parentId,
                wrapper.stack.top,
                wrapper.stack.time)
  }
}
