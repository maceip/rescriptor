package wasmo.mediation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.app.Platform
import wasmo.http.HttpRequest

class InvocationReplayerTest {
  @Test
  fun aRecordedInvocationReplaysOnASealedPlatform() = runTest {
    val recorded = record { platform, input ->
      val now = platform.clock.now().toEpochMilliseconds()
      val upstream = platform.httpService.execute(HttpRequest(url = input.url()))
      JsonPrimitive("$now:${upstream.body.utf8()}")
    }

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, input ->
      val now = platform.clock.now().toEpochMilliseconds()
      val upstream = platform.httpService.execute(HttpRequest(url = input.url()))
      JsonPrimitive("$now:${upstream.body.utf8()}")
    }

    assertThat(report).isInstanceOf(ReplayReport.Deterministic::class)
    report as ReplayReport.Deterministic
    assertThat(report.replayedCalls).isEqualTo(2)
    assertThat(report.verification).isInstanceOf(InvocationVerification.Valid::class)
  }

  @Test
  fun anAppThatAsksForSomethingElseIsReportedAsDivergent() = runTest {
    val recorded = record { platform, input ->
      JsonPrimitive(platform.httpService.execute(HttpRequest(url = input.url())).body.utf8())
    }

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      JsonPrimitive(
        platform.httpService.execute(HttpRequest(url = "https://elsewhere.test/")).body.utf8(),
      )
    }

    val divergence = (report as ReplayReport.Divergent).divergence
    assertThat(divergence).isInstanceOf(Divergence.ChangedRequest::class)
    assertThat(divergence.message).contains("elsewhere.test")
  }

  @Test
  fun anAppThatMakesAnExtraCallIsReportedAsDivergent() = runTest {
    val recorded = record { platform, _ -> JsonPrimitive(platform.clock.now().toString()) }

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      val now = platform.clock.now()
      platform.httpService.execute(HttpRequest(url = "https://extra.test/"))
      JsonPrimitive(now.toString())
    }

    assertThat((report as ReplayReport.Divergent).divergence)
      .isInstanceOf(Divergence.UnrecordedCall::class)
  }

  /** The same answer reached by doing less is still a different program. */
  @Test
  fun anAppThatSkipsARecordedCallIsReportedAsDivergent() = runTest {
    val recorded = record { platform, _ ->
      platform.clock.now()
      platform.httpService.execute(HttpRequest(url = "https://a.test/"))
      JsonPrimitive("constant")
    }

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      platform.clock.now()
      JsonPrimitive("constant")
    }

    val divergence = (report as ReplayReport.Divergent).divergence
    assertThat(divergence).isInstanceOf(Divergence.UnusedJournalEntries::class)
    assertThat(divergence.message).contains("1 of 2")
  }

  @Test
  fun anAppThatReturnsSomethingElseFromTheSameCallsIsReportedAsDivergent() = runTest {
    val recorded = record { platform, _ -> JsonPrimitive(platform.clock.now().toString()) }

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      platform.clock.now()
      JsonPrimitive("something else")
    }

    val divergence = (report as ReplayReport.Divergent).divergence
    assertThat(divergence).isInstanceOf(Divergence.ChangedOutput::class)
    assertThat(divergence.message).contains("something else")
  }

  @Test
  fun aRecordedFailureReplaysAsTheSameFailure() = runTest {
    assertFailsWith<IllegalStateException> {
      record { platform, _ ->
        platform.clock.now()
        error("app exploded")
      }
    }
    val recorded = saved.single()

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      platform.clock.now()
      error("app exploded")
    }

    assertThat(report).isInstanceOf(ReplayReport.Deterministic::class)
    assertThat(recorded.failure).isNotNull()
  }

  @Test
  fun anAppThatNoLongerFailsIsReportedAsDivergent() = runTest {
    assertFailsWith<IllegalStateException> {
      record { platform, _ ->
        platform.clock.now()
        error("app exploded")
      }
    }
    val recorded = saved.single()

    val report = InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
      JsonPrimitive(platform.clock.now().toString())
    }

    val divergence = (report as ReplayReport.Divergent).divergence
    assertThat(divergence).isInstanceOf(Divergence.ChangedOutcome::class)
    assertThat(divergence.message).contains("success")
  }

  @Test
  fun replayingAnUnknownInvocationFails() = runTest {
    val reader = object : CapabilityInvocationReader {
      override suspend fun load(id: String): CapabilityInvocation? = null
      override suspend fun list(query: InvocationQuery) = listOf<InvocationSummary>()
    }
    assertFailsWith<InvocationReplayer.UnknownInvocationException> {
      InvocationReplayer(reader).replay("missing") { _, _ -> JsonPrimitive("x") }
    }
  }

  /**
   * The sealed platform is the proof, not the decoration: replaying against a platform that would
   * answer would not show that the journal was sufficient.
   */
  @Test
  fun replayNeverReachesTheLivePlatform() = runTest {
    val recorded = record { platform, _ -> JsonPrimitive(platform.clock.now().toString()) }

    assertThat(
      InvocationReplayer(StoreOf(recorded)).replay(recorded.id) { platform, _ ->
        JsonPrimitive(platform.clock.now().toString())
      },
    ).isInstanceOf(ReplayReport.Deterministic::class)

    assertFailsWith<SealedPlatformException> { SealedPlatform.clock.now() }
  }

  private val saved = mutableListOf<CapabilityInvocation>()

  /** Records one invocation the way the OS's HTTP ingress does, and returns what was persisted. */
  private suspend fun record(
    block: suspend (Platform, JsonElement) -> JsonElement,
  ): CapabilityInvocation {
    saved.clear()
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
      input = Input,
      encodeOutput = { it },
    ) { session ->
      block(MediatedPlatform(TestPlatforms.working(), session), Input)
    }
    return saved.single()
  }

  private fun JsonElement.url(): String =
    ((this as JsonObject).getValue("url") as JsonPrimitive).content

  private class StoreOf(
    private val invocation: CapabilityInvocation,
  ) : CapabilityInvocationReader {
    override suspend fun load(id: String) = invocation.takeIf { it.id == id }

    override suspend fun list(query: InvocationQuery) = listOf<InvocationSummary>()
  }

  private class SteppingClock : Clock {
    private var millis = 1_000L
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis++)
  }

  private companion object {
    val Input = JsonObject(linkedMapOf("url" to JsonPrimitive("https://upstream.test/data")))

    val TestCaller = Caller(
      userId = 7L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "test",
      ip = "127.0.0.1",
    )
  }
}
