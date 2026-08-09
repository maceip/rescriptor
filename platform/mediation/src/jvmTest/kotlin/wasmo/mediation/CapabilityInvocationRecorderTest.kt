package wasmo.mediation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller
import wasmo.access.ComputerAccess

class CapabilityInvocationRecorderTest {
  @Test
  fun savesSuccessfulInvocationWithCallerJournalAndVerifiedAudit() = runTest {
    val saved = mutableListOf<CapabilityInvocation>()
    val recorder = CapabilityInvocationRecorder(
      store = CapabilityInvocationStore(saved::add),
      clock = IncrementingClock(),
      idFactory = { "invocation-1" },
    )

    val result = recorder.record(
      kind = InvocationKind.Http,
      appSlug = "notes",
      appVersion = 7L,
      caller = Caller,
      input = JsonPrimitive("request"),
      encodeOutput = ::JsonPrimitive,
    ) { session ->
      session.callSync("clock", "now", JsonNull) { JsonPrimitive(123L) }
      "response"
    }

    assertThat(result).isEqualTo("response")
    val invocation = saved.single()
    assertThat(invocation.id).isEqualTo("invocation-1")
    assertThat(invocation.callerJson).isEqualTo(canonicalCaller(Caller))
    assertThat(invocation.journal.map { it.key }).containsExactly("clock.now:null")
    assertThat(invocation.audit.map { it.kind }).containsExactly(
      "invocation.start",
      "cap.live",
      "invocation.success",
    )
    assertThat(AuditChain.verify(invocation.audit)).isInstanceOf(AuditVerification.Valid::class)
  }

  @Test
  fun savesFailedInvocationBeforeRethrowing() = runTest {
    val saved = mutableListOf<CapabilityInvocation>()
    val recorder = CapabilityInvocationRecorder(
      store = CapabilityInvocationStore(saved::add),
      clock = IncrementingClock(),
      idFactory = { "invocation-2" },
    )

    assertFailsWith<IllegalStateException> {
      recorder.record(
        kind = InvocationKind.Job,
        appSlug = "notes",
        appVersion = 8L,
        caller = Caller,
        input = JsonNull,
        encodeOutput = { JsonNull },
      ) { error("guest failed") }
    }

    val invocation = saved.single()
    assertThat(invocation.failure?.message).isEqualTo("guest failed")
    assertThat(invocation.audit.map { it.kind }).containsExactly(
      "invocation.start",
      "invocation.failure",
    )
    assertThat(AuditChain.verify(invocation.audit)).isInstanceOf(AuditVerification.Valid::class)
  }

  private class IncrementingClock : Clock {
    private var millis = 1_000L
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis++)
  }

  private companion object {
    val Caller = Caller(
      userId = 7L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "invocation-test",
      ip = "127.0.0.1",
    )
  }
}
