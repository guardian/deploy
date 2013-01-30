package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta._
import persistence._
import controllers.Logging
import java.util.UUID

class MappingTest extends FlatSpec with ShouldMatchers with Utilities with PersistenceTestInstances with Logging {
  lazy val graters = new RiffRaffGraters {
    def loader = Some(getClass.getClassLoader)
  }

  "RecordV2Converter" should "transform a deploy record into a deploy document" in {
    RecordConverter(testRecordV2).deployDocument should be(
      DeployRecordDocument(
        testUUID,
        Some(testUUID.toString),
        testTime,
        ParametersDocument("Tester", "Deploy", "test-project", "1", "CODE", "test-recipe", Nil, Map("branch"->"master")),
        RunState.Completed
      )
    )
  }

  it should "transform a deploy record into a set of log documents" in {
    val logDocuments = RecordConverter(testRecordV2).logDocuments
    logDocuments should have size 6
  }

  it should "build a set of log documents that are a valid tree" in {
    val logDocuments = RecordConverter(testRecordV2).logDocuments
    val tree = LogDocumentTree(logDocuments)
    tree.roots should have size 1

    val treeDocuments = tree.traverseTree(tree.roots.head)
    treeDocuments should have size logDocuments.size

    treeDocuments.toSet should be(logDocuments.toSet)
  }

  it should "transfer the deploy UUID into the log documents" in {
    val logDocuments = RecordConverter(testRecordV2).logDocuments
    logDocuments.foreach{ doc =>
      doc.deploy should be(testRecordV2.uuid)
    }
  }

  "LogDocumentTree" should "identify the root" in {
    val tree = LogDocumentTree(logDocuments)
    tree.roots.size should be(1)
    tree.roots.head match {
      case LogDocument(_, _, None, DeployDocument(),_) =>
      case _ => fail("Didn't get the expected document when trying to locate the root")
    }
  }

  it should "list children of a given node" in {
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    children should have size 2
  }

  it should "list parents of child nodes" in {
    val tree = LogDocumentTree(logDocuments)
    val children = tree.childrenOf(tree.roots.head)
    tree.parentOf(children.head) should be(Some(tree.roots.head))
  }

  "DocumentConverter" should "create a skeleton record from just a DeployRecordDocument" in {
    val deployRecordDocument = DeployRecordDocument(
      testUUID,
      Some(testUUID.toString),
      testTime,
      ParametersDocument(
        "test",
        "Deploy",
        "testProject",
        "test",
        "TEST",
        "default",
        Nil,
        Map.empty
      ),
      RunState.Completed
    )
    val record = DocumentConverter(deployRecordDocument, Nil).deployRecord
    record.uuid should be(testUUID)
    record.time should be(testTime)
    record.parameters.deployer should be(Deployer("test"))
    record.recordState should be(Some(RunState.Completed))
  }

  it should "create a message wrapper" in {
    val id = UUID.randomUUID()
    val deployRecordDocument = DeployRecordDocument(
      testUUID,
      Some(testUUID.toString),
      testTime,
      ParametersDocument(
        "test",
        "Deploy",
        "testProject",
        "test",
        "TEST",
        "default",
        Nil,
        Map.empty
      ),
      RunState.Completed
    )
    val logDocument = LogDocument(testUUID, id, None, Info("test"), testTime)
    val wrapper = DocumentConverter(deployRecordDocument, List(logDocument)).deployRecord.messages.head
    wrapper.context.deployId should be(testUUID)
    wrapper.messageId should be(id)
  }

  it should "invert the action of RecordConverter" in {
    val converter = RecordConverter(testRecordV2)
    val record = DocumentConverter(converter.deployDocument, converter.logDocuments).deployRecord
    record should be(testRecordV2.copy(recordState = Some(RunState.Completed)))
  }
}
