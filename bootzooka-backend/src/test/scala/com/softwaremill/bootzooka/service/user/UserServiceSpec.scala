package com.softwaremill.bootzooka.service.user

import com.softwaremill.bootzooka.dao.user.{InMemoryUserDao, UserDao}
import com.softwaremill.bootzooka.domain.User
import com.softwaremill.bootzooka.service.email.EmailScheduler
import com.softwaremill.bootzooka.service.templates.{EmailContentWithSubject, EmailTemplatingEngine}
import org.mockito.Matchers
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FlatSpec}
import scala.concurrent.ExecutionContext.Implicits.global

class UserServiceSpec extends FlatSpec with scalatest.Matchers with MockitoSugar with BeforeAndAfter with ScalaFutures {
  def prepareUserDaoMock: UserDao = {
    val dao = new InMemoryUserDao
    dao.add(User("Admin", "admin@sml.com", "pass", "salt", "token1"))
    dao.add(User("Admin2", "admin2@sml.com", "pass", "salt", "token2"))
    dao
  }

  val registrationDataValidator: RegistrationDataValidator = mock[RegistrationDataValidator]
  val emailScheduler = mock[EmailScheduler]
  val emailTemplatingEngine = mock[EmailTemplatingEngine]
  var userDao: UserDao = _
  var userService: UserService = _

  before {
    userDao = prepareUserDaoMock
    userService = new UserService(userDao, registrationDataValidator, emailScheduler, emailTemplatingEngine)
  }

  // this test is silly :\
  "findByEmail" should "return user for admin@sml.pl" in {
    val userOpt = userService.findByEmail("admin@sml.com").futureValue

    userOpt.map(_.login) should be (Some("Admin"))
  }

  "findByEmail" should  "return user for uppercased ADMIN@SML.PL" in {
    val userOpt = userService.findByEmail("ADMIN@SML.COM").futureValue

    userOpt.map(_.login) should be (Some("Admin"))
  }

  "checkExistence" should "don't find given user login and e-mail" in {
    val userExistence: Either[String, Unit] = userService.checkUserExistenceFor("newUser", "newUser@sml.com")
      .futureValue
    userExistence.isRight should be (true)
  }

  "checkExistence" should "find duplicated login" in {
    val userExistence: Either[String, Unit] = userService.checkUserExistenceFor("Admin", "newUser@sml.com")
      .futureValue

    userExistence.isLeft should be (true)
    userExistence.left.get.equals("Login already in use!")
  }

  "checkExistence" should "find duplicated login written as upper cased string" in {
    val userExistence: Either[String, Unit] = userService.checkUserExistenceFor("ADMIN", "newUser@sml.com")
      .futureValue

    userExistence.isLeft should be (true)
    userExistence.left.get.equals("Login already in use!")
  }

  "checkExistence" should "find duplicated email" in {
    val userExistence: Either[String, Unit] = userService.checkUserExistenceFor("newUser", "admin@sml.com")
      .futureValue

    userExistence.isLeft should be (true)
    userExistence.left.get.equals("E-mail already in use!")
  }

  "checkExistence" should "find duplicated email written as upper cased string" in {
    val userExistence: Either[String, Unit] = userService.checkUserExistenceFor("newUser", "ADMIN@sml.com")
      .futureValue

    userExistence.isLeft should be (true)
    userExistence.left.get.equals("E-mail already in use!")
  }


  "registerNewUser" should "add user with unique lowercased login info" in {
    // When
    userService.registerNewUser("John", "newUser@sml.com", "password").futureValue

    // Then
    val userOpt: Option[User] = userDao.findByLowerCasedLogin("John").futureValue
    userOpt.isDefined should be (true)
    val user = userOpt.get

    user.login should be ("John")
    user.loginLowerCased should be ("john")
    verify(emailTemplatingEngine).registrationConfirmation(Matchers.eq("John"))
    verify(emailScheduler)
      .scheduleEmail(Matchers.eq("newUser@sml.com"), any[EmailContentWithSubject])
  }

  "registerNewUser" should "not schedule an email on existing login" in {
    // When
    try {
      userService.registerNewUser("Admin", "secondEmail@sml.com", "password").futureValue
    }
    catch {
      case e: Exception =>
    }
    // Then
    verify(emailScheduler, never()).scheduleEmail(Matchers.eq("secondEmail@sml.com"), any[EmailContentWithSubject])
  }

  "changeEmail" should "change email for specified user" in {
    val user = userDao.findByLowerCasedLogin("admin").futureValue
    val userEmail = user.get.email
    val newEmail = "new@email.com"
    userService.changeEmail(userEmail, newEmail).futureValue should be ('right)
    userDao.findByEmail(newEmail).futureValue match {
      case Some(cu) =>
      case None => fail("User not found. Maybe e-mail wasn't really changed?")
    }
  }

  "changeEmail" should "not change email if already used by someone else" in {
    userService.changeEmail("admin@sml.com", "admin2@sml.com").futureValue should be ('left)
  }

  "changeLogin" should "change login for specified user" in {
    val user = userDao.findByLowerCasedLogin("admin").futureValue
    val userLogin = user.get.login
    val newLogin = "newadmin"
    userService.changeLogin(userLogin, newLogin).futureValue should be ('right)
    userDao.findByLowerCasedLogin(newLogin).futureValue match {
      case Some(cu) =>
      case None => fail("User not found. Maybe login wasn't really changed?")
    }
  }

  "changeLogin" should "not change login if already used by someone else" in {
    userService.changeLogin("admin", "admin2").futureValue should be ('left)
  }


  "changePassword" should "change password if current is correct and new is present" in {
    // Given
    val user = userDao.findByLowerCasedLogin("admin").futureValue.get
    val currentPassword = "pass"
    val newPassword = "newPass"

    // When
    val changePassResult = userService.changePassword(user.token, currentPassword, newPassword).futureValue

    // Then
    changePassResult should be ('right)
    userDao.findByLowerCasedLogin("admin").futureValue match {
      case Some(cu) => cu.password should be (User.encryptPassword(newPassword, cu.salt))
      case None => fail("Something bad happened, maybe mocked Dao is broken?")
    }
  }

  "changePassword" should "not change password if current is incorrect" in {
    // Given
    val user = userDao.findByLowerCasedLogin("admin").futureValue.get

    // When, Then
    userService.changePassword(user.token, "someillegalpass", "newpass").futureValue should be ('left)
  }

  "changePassword" should "complain when user cannot be found" in {
    userService.changePassword("someirrelevanttoken", "pass", "newpass").futureValue should be ('left)
  }
}
