package org.justcards.server.user_manager

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.testkit.TestProbe
import org.justcards.commons._
import org.justcards.commons.AppError._
import org.justcards.server.knowledge_engine.KnowledgeEngine.{GameExistenceRequest, GameExistenceResponse}
import org.justcards.server.user_manager.UserManagerMessage.LogOutAndExitFromLobby

class UserManagerLobbyTest extends WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  import UserManagerLobbyTest._

  private implicit val system = ActorSystem("UserManagerLobbyTest")
  private val knowledgeEngine = system.actorOf(createKnowledgeEngine(_ => true))
  private var tempActors: Set[ActorRef] = Set()

  after {
    tempActors foreach {
      _ ! PoisonPill
    }
    tempActors = tempActors.empty
  }

  override def afterAll: Unit = {
    system terminate()
  }

  "The user manager" when {

    "created" should {

      "not contain any lobby" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        expectNoLobby(userManager)
      }

    }

    "is at runtime" should {

      "allow to create a lobby" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        userManager ! CreateLobby(GameId(GAME_TEST))
        me.expectMsgType[LobbyCreated]
      }

      "not allow to create a lobby if not logged" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        userManager ! CreateLobby(GameId(GAME_TEST))
        me expectMsg(ErrorOccurred(USER_NOT_LOGGED))
      }

      "not allow to create a lobby if the game doesn't exist" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val knowledgeEngineWithNoGames = system.actorOf(createKnowledgeEngine(_ => false))
        val myUserManager = system.actorOf(UserManager(knowledgeEngineWithNoGames))
        this.tempActors = this.tempActors ++ Set(knowledgeEngineWithNoGames, myUserManager)

        doLogIn(myUserManager, TEST_USERNAME)
        myUserManager ! CreateLobby(GameId(GAME_TEST))
        me expectMsg(ErrorOccurred(GAME_NOT_EXISTING))
      }

      "allow to see the available lobbies" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        val msg = createLobby(userManager)
        expectLobbies(userManager, (msg.lobby, Set(UserId(1, TEST_USERNAME))))
      }

      "not allow to see the available lobbies if not logged" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        createLobby(userManager)
        userManager ! LogOut(TEST_USERNAME)
        userManager ! RetrieveAvailableLobbies()
        me expectMsg(ErrorOccurred(USER_NOT_LOGGED))
      }

      "not allow to see a lobby if it's full" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        fillLobby(lobbyInfo.lobby)(userManager)
        expectNoLobby(userManager)
      }

      "allow to join a lobby" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        val joiner = createJoinerAndLogIn(userManager, JOINER_USERNAME)
        this.tempActors = this.tempActors + joiner
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        joiner ! JoinLobby(lobbyInfo.lobby)
        val users = Set(UserId(1, TEST_USERNAME), UserId(1, JOINER_USERNAME))
        val messages = me receiveN(2)
        messages should contain (LobbyJoined(lobbyInfo.lobby, users))
      }

      "inform all the members of a lobby when a new user joins" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        val joiner1 = createJoinerAndLogIn(userManager, JOINER_USERNAME)
        val joiner2 = createJoinerAndLogIn(userManager, JOINER_USERNAME + "2")
        this.tempActors = this.tempActors ++ Set(joiner1,joiner2)
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        joiner1 ! JoinLobby(lobbyInfo.lobby)
        me receiveN(2)
        joiner2 ! JoinLobby(lobbyInfo.lobby)
        val users = Set(UserId(1, TEST_USERNAME), UserId(1, JOINER_USERNAME), UserId(1, JOINER_USERNAME + 2))
        me expectMsgAllOf(
          LobbyJoined(lobbyInfo.lobby, users),
          LobbyUpdate(lobbyInfo.lobby, users),
          LobbyUpdate(lobbyInfo.lobby, users)
        )
      }

      "not allow to join a lobby if not logged" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        userManager ! JoinLobby(LOBBY_TEST)
        me expectMsg(ErrorOccurred(USER_NOT_LOGGED))
      }

      "not allow to join a lobby if the lobby doesn't exist" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        userManager ! JoinLobby(LOBBY_TEST)
        me expectMsg(ErrorOccurred(LOBBY_NOT_EXISTING))
      }

      "not allow to join a lobby if the user is already in another lobby" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        val lobbyId = createLobby(userManager)
        userManager ! JoinLobby(lobbyId.lobby)
        me expectMsg(ErrorOccurred(USER_ALREADY_IN_A_LOBBY))
      }

      "not allow to join a lobby if it reached maximum capacity" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        /* create lobby and let enter n-1 people */
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        this.tempActors = this.tempActors ++ fillLobby(lobbyInfo.lobby)(userManager)
        /* now the lobby is full */
        val lastJoiner = createJoinerAndLogIn(userManager, JOINER_USERNAME + "-illegal")
        this.tempActors = this.tempActors + lastJoiner
        lastJoiner ! JoinLobby(lobbyInfo.lobby)
        me expectMsg(ErrorOccurred(LOBBY_FULL))
      }

      "return all the current members of a lobby" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        val joiner = createJoinerAndLogIn(userManager, JOINER_USERNAME)
        this.tempActors = this.tempActors + joiner
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        joiner ! JoinLobby(lobbyInfo.lobby)
        me receiveN(2)
        userManager ! RetrieveAvailableLobbies()
        val tuple = lobbyInfo.lobby -> Set(UserId(1, TEST_USERNAME), UserId(1, JOINER_USERNAME))
        me expectMsg(AvailableLobbies(Set(tuple)))
      }

      "remove a user from a lobby if it logs out" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        val joiner = createJoinerAndLogIn(userManager, JOINER_USERNAME)
        this.tempActors = this.tempActors + joiner
        val lobbyInfo = createLobby(userManager)
        joiner ! JoinLobby(lobbyInfo.lobby)
        me receiveN(2)
        userManager ! LogOutAndExitFromLobby(TEST_USERNAME, lobbyInfo.lobby)
        me expectMsg(LobbyUpdate(lobbyInfo.lobby, Set(UserId(1, JOINER_USERNAME))))
      }

      "delete a lobby if it remains empty" in {
        implicit val me = TestProbe()
        implicit val myRef = me.ref
        val userManager = system.actorOf(UserManager(knowledgeEngine))
        doLogIn(userManager, TEST_USERNAME)
        val lobbyInfo = createLobby(userManager)
        userManager ! LogOutAndExitFromLobby(TEST_USERNAME, lobbyInfo.lobby)
        me receiveN 1
        doLogIn(userManager, TEST_USERNAME)
        expectNoLobby(userManager)
      }

    }

  }

  private def doLogIn(userManager: ActorRef, username: String)(implicit me: TestProbe): Unit = {
    implicit val myRef = me.ref
    userManager ! LogIn(username)
    me receiveN(1)
  }

  private def createJoinerAndLogIn(userManager: ActorRef, username: String)(implicit me: TestProbe): ActorRef = {
    implicit val myRef = me.ref
    val joiner = system.actorOf(createActor(myRef, userManager))
    joiner ! LogIn(username)
    me receiveN(1)
    joiner
  }

  private def createLobby(userManager: ActorRef)(implicit me: TestProbe): LobbyCreated = {
    implicit val myRef = me.ref
    userManager ! CreateLobby(GameId(GAME_TEST))
    me.receiveN(1).map(_.asInstanceOf[LobbyCreated]).head
  }

  private def fillLobby(lobbyInfo: LobbyId)(userManager: ActorRef)(implicit me: TestProbe): Seq[ActorRef] = {
    for(
      n <- 0 until Lobby.MAX_LOBBY_MEMBERS - 1;
      joiner = createJoinerAndLogIn(userManager, JOINER_USERNAME + n);
      _ = joiner ! JoinLobby(lobbyInfo);
      _ = me.receiveN(n + 2)
    ) yield joiner
  }

  private def expectLobbies(userManager: ActorRef, lobbies: (LobbyId, Set[UserId])*)(implicit me: TestProbe): Unit = {
    expectLobbies(userManager, lobbies.toSet)
  }

  private def expectLobbies(userManager: ActorRef, lobbies: Set[(LobbyId, Set[UserId])])(implicit me: TestProbe): Unit = {
    implicit val myRef = me.ref
    userManager ! RetrieveAvailableLobbies()
    me expectMsg(AvailableLobbies(lobbies))
  }

  private def expectNoLobby(userManager: ActorRef)(implicit me: TestProbe): Unit = expectLobbies(userManager)

}

object UserManagerLobbyTest {

  val TEST_USERNAME: String = "test-username"
  val JOINER_USERNAME: String = "joiner-username"
  val GAME_TEST: String = "Beccaccino"
  val LOBBY_TEST: LobbyId = LobbyId(1, TEST_USERNAME, GameId(GAME_TEST))

  def createActor(testActor: ActorRef, userManager: ActorRef): Props =
    Props(classOf[SimpleActor], testActor: ActorRef, userManager)

  def createKnowledgeEngine(operation: GameExistenceRequest => Boolean): Props =
    Props(classOf[KnowledgeEngineStub], operation)

  private[this] class SimpleActor(testActor: ActorRef, userManager: ActorRef) extends Actor {
    override def receive: Receive = {
      case a: Logged => testActor ! a
      case a: LobbyJoined => testActor ! a
      case a: LobbyUpdate => testActor ! a
      case a: ErrorOccurred => testActor ! a
      case msg => userManager ! msg
    }
  }

  private[this] class KnowledgeEngineStub(acceptMessage: GameExistenceRequest => Boolean) extends Actor {
    override def receive: Receive = {
      case msg: GameExistenceRequest => sender() ! GameExistenceResponse(acceptMessage(msg))
    }
  }

}
