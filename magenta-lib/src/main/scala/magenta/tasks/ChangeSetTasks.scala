package magenta.tasks

import magenta.artifact.S3Path
import magenta.tasks.UpdateCloudFormationTask._
import magenta.{DeployReporter, KeyRing, Region}
import software.amazon.awssdk.services.cloudformation.model.ChangeSetStatus._
import software.amazon.awssdk.services.cloudformation.model.{Change, DeleteChangeSetRequest, DescribeChangeSetRequest, ExecuteChangeSetRequest}
import software.amazon.awssdk.services.s3.S3Client

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class CreateChangeSetTask(
                           region: Region,
                           templatePath: S3Path,
                           stackLookup: CloudFormationStackMetadata,
                           val unresolvedParameters: CloudFormationParameters
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = if (!stopFlag) {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
    val stsClient = STS.makeSTSclient(keyRing, region)
    val accountNumber = STS.getAccountNumber(stsClient)

    val templateString = templatePath.fetchContentAsString.right.getOrElse(
      reporter.fail(s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}")
    )

    val (stackName, changeSetType) = stackLookup.lookup(reporter, cfnClient)

    val template = S3.withS3Client(keyRing, region)(client => processTemplate(stackName, templateString, client, stsClient, region, reporter))
    val parameters = unresolvedParameters.resolve(template, accountNumber, changeSetType, reporter, cfnClient)

    reporter.info("Creating Cloudformation change set")
    reporter.info(s"Stack name: $stackName")
    reporter.info(s"Change set name: ${stackLookup.changeSetName}")
    reporter.info(s"Parameters: $parameters")

    CloudFormation.createChangeSet(reporter, stackLookup.changeSetName, changeSetType, stackName, unresolvedParameters.stackTags, template, parameters, cfnClient)
  }

  def description = s"Create change set ${stackLookup.changeSetName} for stack ${stackLookup.strategy} with ${templatePath.fileName}"
  def verbose = description
}

class CheckChangeSetCreatedTask(
                                 region: Region,
                                 stackLookup: CloudFormationStackMetadata,
                                 override val duration: Long
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task with RepeatedPollingCheck {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    check(reporter, stopFlag) {
      val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

      val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      val request = DescribeChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
      val response = cfnClient.describeChangeSet(request)

      shouldStopWaiting(response.status.toString, response.statusReason, response.changes.asScala, reporter)
    }
  }

  def shouldStopWaiting(status: String, statusReason: String, changes: Iterable[Change], reporter: DeployReporter): Boolean = {
    Try(valueOf(status)) match {
      case Success(CREATE_COMPLETE) => true
      case Success(FAILED) if changes.isEmpty => true
      case Success(FAILED) => reporter.fail(statusReason)
      case Success(CREATE_IN_PROGRESS | CREATE_PENDING) =>
        reporter.verbose(status)
        false
      case _ =>
        reporter.fail(s"Unexpected change set status $status")
    }
  }

  def description = s"Checking change set ${stackLookup.changeSetName} creation for stack ${stackLookup.strategy}"
  def verbose = description
}

class ExecuteChangeSetTask(
                            region: Region,
                            stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
    val changeSetName = stackLookup.changeSetName

    val describeRequest = DescribeChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
    val describeResponse = cfnClient.describeChangeSet(describeRequest)

    if(describeResponse.changes.isEmpty) {
      reporter.info(s"No changes to perform for $changeSetName on stack $stackName")
    } else {
      describeResponse.changes.asScala.foreach { change =>
        reporter.verbose(s"${change.`type`} - ${change.resourceChange}")
      }

      val request = ExecuteChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
      cfnClient.executeChangeSet(request)
    }
  }

  def description = s"Execute change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
  def verbose = description
}

class DeleteChangeSetTask(
                           region: Region,
                           stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
    val changeSetName = stackLookup.changeSetName

    val request = DeleteChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
    cfnClient.deleteChangeSet(request)
  }

  def description = s"Delete change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
  def verbose = description
}