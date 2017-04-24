package test

import com.gu.googleauth.UserIdentity
import com.gu.googleauth.UserIdentity
import org.scalatest.{Matchers, FlatSpec}
import com.mongodb.casbah.Imports._
import controllers.{AuthorisationValidator, AuthorisationRecord}
import org.joda.time.DateTime

class AuthenticationTest extends FlatSpec with Matchers {
  "AuthorisationRecord" should "serialise and deserialise" in {
    val dateTime = new DateTime()
    val auth = AuthorisationRecord("test@test.com", "Test Person", dateTime)
    val dbo = auth.toDBO
    dbo.as[String]("_id") should be("test@test.com")
    dbo.as[String]("approvedBy") should be("Test Person")
    dbo.as[DateTime]("approvedDate") should be(dateTime)
    AuthorisationRecord.fromDBO(dbo) should be(Some(auth))
  }

  "AuthorisationValidator" should "allow any domain when not configured" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = Nil
      def emailWhitelistEnabled = false
      def emailWhitelistContains(email: String) = false
    }
    val id = UserIdentity("", "test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "allow configured whitelisted domains" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = List("guardian.co.uk")
      def emailWhitelistEnabled = false
      def emailWhitelistContains(email: String) = false
    }
    val id = UserIdentity("", "test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "disallow domains not configured for whitelisting" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = List("guardian.co.uk")
      def emailWhitelistEnabled = false
      def emailWhitelistContains(email: String) = false
    }
    val id = UserIdentity("", "test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be(
      "The e-mail address domain you used to login to Riff-Raff (test@test.com) is not in the configured whitelist.  Please try again with another account or contact the Riff-Raff administrator.")
  }

  it should "allow a whitelisted e-mail address" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = List("guardian.co.uk")
      def emailWhitelistEnabled = true
      def emailWhitelistContains(email: String) = email == "test@guardian.co.uk"
    }
    val id = UserIdentity("", "test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "disallow a whitelisted e-mail address in a non-whitelisted domain" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = List("guardian.co.uk")
      def emailWhitelistEnabled = true
      def emailWhitelistContains(email: String) = email == "test@test.com"
    }
    val id = UserIdentity("", "test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be(
      "The e-mail address domain you used to login to Riff-Raff (test@test.com) is not in the configured whitelist.  Please try again with another account or contact the Riff-Raff administrator.")
  }

  it should "disallow a non-whitelisted e-mail address in a whitelisted domain" in {
    val validator = new AuthorisationValidator {
      def emailDomainWhitelist = List("guardian.co.uk")
      def emailWhitelistEnabled = true
      def emailWhitelistContains(email: String) = false
    }
    val id = UserIdentity("", "test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be(
      "The e-mail address you used to login to Riff-Raff (test@guardian.co.uk) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator.")
  }

}
