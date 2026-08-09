package wasmo.mediation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.http.HttpRequest

/**
 * The offline half of the guarantee.
 *
 * These tests stand in for someone who does not trust the machine that produced the record: given
 * only the exported bytes, can a lie be detected? Each one tampers with a different part and
 * expects the tampering to be named.
 */
class InvocationVerificationTest {
  @Test
  fun anHonestRecordVerifies() = runTest {
    val invocation = record()

    val verification = verifyInvocation(invocation)

    assertThat(verification).isInstanceOf(InvocationVerification.Valid::class)
    verification as InvocationVerification.Valid
    assertThat(verification.capabilityCalls).isEqualTo(2)
    assertThat(verification.head).isEqualTo(invocation.audit.last().hash)
  }

  @Test
  fun rewritingAJournalResultIsDetectedEvenThoughTheChainStillVerifies() = runTest {
    val invocation = record()
    val tampered = invocation.copy(
      journal = invocation.journal.mapIndexed { index, entry ->
        when (index) {
          0 -> entry.copy(result = JsonPrimitive(0L))
          else -> entry
        }
      },
    )

    // The hash chain alone is happy: nothing in it was touched.
    assertThat(AuditChain.verify(tampered.audit)).isInstanceOf(AuditVerification.Valid::class)

    val verification = verifyInvocation(tampered)
    assertThat(verification).isInstanceOf(InvocationVerification.Invalid::class)
    assertThat((verification as InvocationVerification.Invalid).reason)
      .contains("does not commit to the result recorded in journal entry 0")
  }

  @Test
  fun droppingACapabilityCallFromTheJournalIsDetected() = runTest {
    val invocation = record()
    val tampered = invocation.copy(journal = invocation.journal.drop(1))

    val verification = verifyInvocation(tampered)

    assertThat((verification as InvocationVerification.Invalid).reason)
      .contains("audit describes 2 capability calls but the journal has 1")
  }

  @Test
  fun rewritingAnAuditRowIsDetected() = runTest {
    val invocation = record()
    val tampered = invocation.copy(
      audit = invocation.audit.mapIndexed { index, entry ->
        when (index) {
          1 -> entry.copy(detail = "clock.now:\"forged\"")
          else -> entry
        }
      },
    )

    val verification = verifyInvocation(tampered)

    assertThat(verification).isEqualTo(
      InvocationVerification.Invalid(seq = 2L, reason = "hash mismatch"),
    )
  }

  @Test
  fun rewritingTheRecordedOutputIsDetected() = runTest {
    val invocation = record()
    val tampered = invocation.copy(output = JsonPrimitive("a different answer"))

    val verification = verifyInvocation(tampered)

    assertThat((verification as InvocationVerification.Invalid).reason)
      .contains("does not commit to this invocation's outcome")
  }

  @Test
  fun rewritingTheRecordedInputIsDetected() = runTest {
    val invocation = record()
    val tampered = invocation.copy(input = JsonPrimitive("a different question"))

    val verification = verifyInvocation(tampered)

    assertThat((verification as InvocationVerification.Invalid).reason)
      .contains("does not commit to this invocation's input")
  }

  @Test
  fun attributingAnInvocationToADifferentCallerIsDetected() = runTest {
    val invocation = record()
    val tampered = invocation.copy(callerJson = """{"userId":999}""")

    val verification = verifyInvocation(tampered)

    assertThat((verification as InvocationVerification.Invalid).reason)
      .contains("caller does not match")
  }

  @Test
  fun anExportedRecordSurvivesARoundTrip() = runTest {
    val invocation = record()

    val decoded = InvocationJson.decodeFromString(InvocationJson.encodeToString(invocation))

    assertThat(decoded).isEqualTo(invocation)
    assertThat(verifyInvocation(decoded)).isInstanceOf(InvocationVerification.Valid::class)
  }

  @Test
  fun aDeniedCallIsPartOfTheRecordRatherThanMissingFromIt() = runTest {
    val saved = mutableListOf<CapabilityInvocation>()
    val recorder = CapabilityInvocationRecorder(
      store = { saved += it },
      clock = SteppingClock(),
      idFactory = { "denied-1" },
    )
    runCatching {
      recorder.record<JsonElement>(
        kind = InvocationKind.Http,
        appSlug = "notes",
        appVersion = 1L,
        caller = TestCaller,
        input = JsonPrimitive("in"),
        encodeOutput = { it },
        policy = GrantedCapabilityPolicy(listOf()),
      ) { session ->
        MediatedPlatform(TestPlatforms.working(), session).clock.now()
        JsonPrimitive("unreachable")
      }
    }

    val invocation = saved.single()
    assertThat(invocation.audit.map { it.kind })
      .isEqualTo(listOf("invocation.start", "cap.denied", "invocation.failure"))
    assertThat(verifyInvocation(invocation))
      .isInstanceOf(InvocationVerification.Valid::class)
  }

  private suspend fun record(): CapabilityInvocation {
    val saved = mutableListOf<CapabilityInvocation>()
    val recorder = CapabilityInvocationRecorder(
      store = { saved += it },
      clock = SteppingClock(),
      idFactory = { "invocation-1" },
    )
    recorder.record<JsonElement>(
      kind = InvocationKind.Http,
      appSlug = "notes",
      appVersion = 3L,
      caller = TestCaller,
      input = JsonPrimitive("in"),
      encodeOutput = { it },
    ) { session ->
      val platform = MediatedPlatform(TestPlatforms.working(), session)
      val now = platform.clock.now().toEpochMilliseconds()
      val body = platform.httpService.execute(HttpRequest(url = "https://a.test/")).body.utf8()
      JsonPrimitive("$now:$body")
    }
    return saved.single()
  }

  private class SteppingClock : Clock {
    private var millis = 1_000L
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis++)
  }

  private companion object {
    val TestCaller = Caller(
      userId = 7L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "test",
      ip = "127.0.0.1",
    )
  }
}
