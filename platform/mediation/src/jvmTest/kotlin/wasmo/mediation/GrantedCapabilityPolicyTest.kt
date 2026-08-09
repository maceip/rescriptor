package wasmo.mediation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.wasmo.identifiers.Capability
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.http.HttpRequest
import wasmo.objectstore.GetObjectRequest

class GrantedCapabilityPolicyTest {
  @Test
  fun undeclaredCapabilityIsDenied() = runTest {
    val session = recordingSession(CapabilityGrant(Capability.Clock))
    val platform = MediatedPlatform(TestPlatforms.working(), session)

    platform.clock.now()
    val failure = assertFailsWith<CapabilityDeniedException> {
      platform.httpService.execute(HttpRequest(url = "https://upstream.test/data"))
    }

    assertThat(failure.capability).isEqualTo("http.fetch")
    assertThat(failure.reason).contains("does not declare the 'http' capability")
  }

  @Test
  fun allowListNarrowsACapabilityToTheHostsItNamed() = runTest {
    val session = recordingSession(
      CapabilityGrant(Capability.Http, allow = listOf("https://api.test/**")),
    )
    val platform = MediatedPlatform(TestPlatforms.working(), session)

    platform.httpService.execute(HttpRequest(url = "https://api.test/v1/forecast"))
    val failure = assertFailsWith<CapabilityDeniedException> {
      platform.httpService.execute(HttpRequest(url = "https://api.test.evil.test/v1/forecast"))
    }

    assertThat(failure.reason).contains("is not in the 'http' allow list")
  }

  @Test
  fun objectStoreGrantsAreScopedByKey() = runTest {
    val session = recordingSession(
      CapabilityGrant(Capability.ObjectStore, allow = listOf("photos/**")),
    )
    val platform = MediatedPlatform(TestPlatforms.working(), session)

    platform.objectStore.get(GetObjectRequest("photos/2026/a.jpg"))
    assertFailsWith<CapabilityDeniedException> {
      platform.objectStore.get(GetObjectRequest("passwords/vault"))
    }
  }

  /**
   * Everything an app can do with a database is reachable only by opening it, so the gate carries
   * the whole check and the statements below it are not separately constrained.
   */
  @Test
  fun sqlCallsBelowTheGateInheritTheGatesDecision() = runTest {
    val session = recordingSession(CapabilityGrant(Capability.Sql, allow = listOf("notes")))
    val platform = MediatedPlatform(TestPlatforms.working(), session)

    val database = platform.sqlService.getOrCreate("notes")
    val connection = database.newConnection()
    connection.execute("INSERT INTO notes(text) VALUES (?)") { bindString(0, "hello") }

    assertFailsWith<CapabilityDeniedException> {
      platform.sqlService.getOrCreate("passwords")
    }
  }

  @Test
  fun deniedCallsAreJournaledSoADeniedInvocationReplaysWithoutThePolicy() = runTest {
    val recording = recordingSession(CapabilityGrant(Capability.Clock))
    val live = TestPlatforms.working()
    MediatedPlatform(live, recording).clock.now()
    assertFailsWith<CapabilityDeniedException> {
      MediatedPlatform(live, recording).httpService.execute(HttpRequest(url = "https://a.test/"))
    }

    assertThat(recording.audit.snapshot().map { it.kind })
      .containsExactly("cap.live", "cap.denied")
    assertThat(recording.journal.snapshot().map { it.failure?.type }).containsExactly(
      null,
      CapabilityDeniedException::class.qualifiedName,
    )

    // The replay session has no policy at all, and still reproduces the denial.
    val replay = CapabilitySession.replay(
      id = "replay",
      caller = TestCaller,
      entries = recording.journal.snapshot(),
    )
    val replayed = MediatedPlatform(SealedPlatform, replay)
    replayed.clock.now()
    val failure = assertFailsWith<ReplayedCapabilityFailureException> {
      replayed.httpService.execute(HttpRequest(url = "https://a.test/"))
    }
    assertThat(failure.recordedFailure.type)
      .isEqualTo(CapabilityDeniedException::class.qualifiedName)
  }

  @Test
  fun aGrantCannotNarrowACapabilityThatHasNothingToNarrow() {
    val failure = assertFailsWith<IllegalArgumentException> {
      CapabilityGrant(Capability.Clock, allow = listOf("anything"))
    }
    assertThat(failure.message!!).contains("cannot be narrowed")
  }

  @Test
  fun duplicateGrantsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      GrantedCapabilityPolicy(
        listOf(CapabilityGrant(Capability.Clock), CapabilityGrant(Capability.Clock)),
      )
    }
  }

  @Test
  fun patternsAreAnchoredAndDoNotCrossPathSeparatorsByAccident() {
    val api = CapabilityPattern("https://api.test/**")
    assertThat(api.matches("https://api.test/v1/x")).isTrue()
    assertThat(api.matches("https://api.test")).isTrue()
    assertThat(api.matches("https://api.test.evil.test/v1")).isEqualTo(false)
    assertThat(api.matches("https://api.test@evil.test/v1")).isEqualTo(false)
    assertThat(api.matches("https://evil.test/?u=https://api.test/v1")).isEqualTo(false)

    val singleSegment = CapabilityPattern("photos/*.jpg")
    assertThat(singleSegment.matches("photos/a.jpg")).isTrue()
    assertThat(singleSegment.matches("photos/2026/a.jpg")).isEqualTo(false)

    assertThat(CapabilityPattern("**").matches("anything/at/all")).isTrue()
  }

  @Test
  fun theCallDescribesWhatItWouldTouch() {
    val fetch = CapabilityCall(
      capability = "http",
      method = "fetch",
      request = JsonObject(linkedMapOf("url" to JsonPrimitive("https://api.test/x"))),
    )
    assertThat(fetch.family).isEqualTo(Capability.Http)
    assertThat(fetch.target).isEqualTo("https://api.test/x")

    val rowRead = CapabilityCall("sql.row", "getString", JsonNull)
    assertThat(rowRead.family).isEqualTo(Capability.Sql)
    assertThat(rowRead.isGate).isEqualTo(false)
    assertThat(rowRead.target).isEqualTo(null)

    assertThat(CapabilityCall("mystery", "call", JsonNull).family).isEqualTo(null)
  }

  /** A gate whose scoping argument cannot be read must fail closed, not fall through. */
  @Test
  fun aGateWithoutItsArgumentIsDenied() {
    val policy = GrantedCapabilityPolicy(
      listOf(CapabilityGrant(Capability.Http, allow = listOf("https://api.test/**"))),
    )

    val decision = policy.decide(CapabilityCall("http", "fetch", JsonObject(linkedMapOf())))

    assertThat(decision).isInstanceOf(CapabilityDecision.Deny::class)
    assertThat((decision as CapabilityDecision.Deny).reason).contains("carries no URL")
  }

  @Test
  fun anUnknownCapabilityIsDeniedRatherThanIgnored() {
    val policy = GrantedCapabilityPolicy(listOf(CapabilityGrant(Capability.Http)))
    val decision = policy.decide(CapabilityCall("mystery", "call", JsonNull))
    assertThat(decision).isInstanceOf(CapabilityDecision.Deny::class)
  }

  @Test
  fun grantsDescribeThemselvesForPeople() {
    assertThat(
      CapabilityGrant(Capability.Http, allow = listOf("https://api.test/**")).describe(),
    ).isEqualTo("make outbound HTTP requests to https://api.test/**")
    assertThat(CapabilityGrant(Capability.Clock).describe()).isEqualTo("read the current time")
  }

  private fun recordingSession(vararg grants: CapabilityGrant) = CapabilitySession.recording(
    id = "session",
    caller = TestCaller,
    policy = GrantedCapabilityPolicy(grants.toList()),
  )

  private companion object {
    val TestCaller = Caller(
      userId = 1L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "test",
      ip = "127.0.0.1",
    )
  }
}
