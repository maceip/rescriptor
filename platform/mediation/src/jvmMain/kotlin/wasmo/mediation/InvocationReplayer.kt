package wasmo.mediation

import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import wasmo.app.Platform

/** Reads durably recorded invocations back out of storage. */
interface CapabilityInvocationReader {
  /** Returns the complete invocation, including its journal and audit rows, or null. */
  suspend fun load(id: String): CapabilityInvocation?

  /** Returns invocation headers, newest first. */
  suspend fun list(query: InvocationQuery): List<InvocationSummary>
}

data class InvocationQuery(
  val appSlug: String? = null,
  /** Pages backwards through time; pass the previous page's oldest [InvocationSummary.startedAt]. */
  val startedBefore: Instant? = null,
  val limit: Int = 50,
) {
  init {
    require(limit in 1..MaxLimit) { "limit must be in 1..$MaxLimit but was $limit" }
  }

  companion object {
    const val MaxLimit = 1_000
  }
}

/** An invocation without its journal or audit rows, which are large and usually not needed. */
data class InvocationSummary(
  val id: String,
  val kind: InvocationKind,
  val appSlug: String,
  val appVersion: Long,
  val callerJson: String,
  val startedAt: Instant,
  val completedAt: Instant,
  val failureType: String?,
  val journalCount: Long,
  val auditCount: Long,
  val auditHead: String,
)

/** Re-runs one recorded invocation's app against a platform the replayer controls. */
fun interface InvocationExecutor {
  /**
   * Runs the recorded [input] on [platform] and returns the app's output in exactly the encoding
   * the original recording used, or throws what the app threw.
   */
  suspend fun execute(platform: Platform, input: JsonElement): JsonElement
}

sealed interface ReplayReport {
  val invocationId: String

  /** Whether the stored record was internally honest, independent of what the replay did. */
  val verification: InvocationVerification

  /**
   * The app re-ran from its journal and produced the recorded outcome, touching nothing live.
   *
   * [replayAuditHead] is the head of a chain built during the replay. It is deliberately not
   * expected to equal the recorded head: audit rows commit to wall-clock timestamps, and a replay
   * happens at a different time.
   */
  data class Deterministic(
    override val invocationId: String,
    override val verification: InvocationVerification,
    val replayedCalls: Int,
    val replayAuditHead: String,
  ) : ReplayReport

  data class Divergent(
    override val invocationId: String,
    override val verification: InvocationVerification,
    val divergence: Divergence,
  ) : ReplayReport
}

/** Why a replay did not reproduce its recording. Each case names a real class of app defect. */
sealed interface Divergence {
  val message: String

  /** The app asked for something different this time: unjournaled state leaked into a decision. */
  data class ChangedRequest(
    val index: Int,
    val recorded: String,
    val replayed: String,
  ) : Divergence {
    override val message: String
      get() = "capability call $index changed from '$recorded' to '$replayed'"
  }

  /** The app made a call the recording never made. */
  data class UnrecordedCall(
    val index: Int,
    val replayed: String,
  ) : Divergence {
    override val message: String
      get() = "capability call $index was not recorded: '$replayed'"
  }

  /** The app stopped early, so part of what it originally did never happened. */
  data class UnusedJournalEntries(
    val consumed: Int,
    val total: Int,
  ) : Divergence {
    override val message: String
      get() = "replay used $consumed of $total recorded capability calls"
  }

  data class ChangedOutput(
    val recorded: String,
    val replayed: String,
  ) : Divergence {
    override val message: String
      get() = "output changed from '$recorded' to '$replayed'"
  }

  data class ChangedOutcome(
    val recorded: String,
    val replayed: String,
  ) : Divergence {
    override val message: String
      get() = "outcome changed from $recorded to $replayed"
  }
}

/**
 * Re-runs a recorded invocation and reports whether it is still the same program.
 *
 * This is the payoff of mediating every effect. Given only what the OS already stores, an
 * invocation from any point in the retention window can be re-executed on a [SealedPlatform] and
 * checked against its recording, byte for byte — no staging environment, no traffic capture, no
 * re-issuing the side effects the original run had.
 *
 * A [ReplayReport.Divergent] result is not a replay bug. It says the app is no longer a function of
 * its recorded inputs: it read a clock the boundary does not mediate, a static field left over from
 * another request, or a value that was not in the journal.
 */
class InvocationReplayer(
  private val reader: CapabilityInvocationReader,
  /** Overridable so a test can prove the replay would have failed had it reached the delegate. */
  private val platform: Platform = SealedPlatform,
) {
  class UnknownInvocationException(
    val invocationId: String,
  ) : IllegalArgumentException("no such invocation: $invocationId")

  suspend fun replay(
    invocationId: String,
    executor: InvocationExecutor,
  ): ReplayReport {
    val invocation = reader.load(invocationId) ?: throw UnknownInvocationException(invocationId)
    return replay(invocation, executor)
  }

  suspend fun replay(
    invocation: CapabilityInvocation,
    executor: InvocationExecutor,
  ): ReplayReport {
    val verification = verifyInvocation(invocation)
    val session = CapabilitySession.replay(
      id = "${invocation.id}/replay",
      callerJson = invocation.callerJson,
      entries = invocation.journal,
    )

    val output = try {
      executor.execute(MediatedPlatform(platform, session), invocation.input)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      val divergence = failure.toDivergence(invocation)
        ?: return report(invocation, verification, session)
      return ReplayReport.Divergent(invocation.id, verification, divergence)
    }

    if (invocation.failure != null) {
      return ReplayReport.Divergent(
        invocationId = invocation.id,
        verification = verification,
        divergence = Divergence.ChangedOutcome(
          recorded = "failure ${invocation.failure.type}",
          replayed = "success",
        ),
      )
    }

    val recordedOutput = stableStringify(invocation.output ?: JsonNull)
    val replayedOutput = stableStringify(output)
    if (recordedOutput != replayedOutput) {
      return ReplayReport.Divergent(
        invocationId = invocation.id,
        verification = verification,
        divergence = Divergence.ChangedOutput(recordedOutput, replayedOutput),
      )
    }

    return report(invocation, verification, session)
  }

  /** Reaching here means the app agreed with its recording, so only leftover journal can differ. */
  private fun report(
    invocation: CapabilityInvocation,
    verification: InvocationVerification,
    session: CapabilitySession,
  ): ReplayReport {
    val consumed = session.journal.position
    if (consumed != invocation.journal.size) {
      return ReplayReport.Divergent(
        invocationId = invocation.id,
        verification = verification,
        divergence = Divergence.UnusedJournalEntries(consumed, invocation.journal.size),
      )
    }
    return ReplayReport.Deterministic(
      invocationId = invocation.id,
      verification = verification,
      replayedCalls = consumed,
      replayAuditHead = session.audit.snapshot().lastOrNull()?.hash ?: AuditChain.Genesis,
    )
  }

  /**
   * Classifies what a replay threw.
   *
   * A [ReplayedCapabilityFailureException] is unwrapped before comparing, because it is the replay
   * of a failure the recording also saw: the app failed the same way for the same reason.
   */
  private fun Exception.toDivergence(invocation: CapabilityInvocation): Divergence? = when (this) {
    is ReplayMismatchException -> Divergence.ChangedRequest(index, expected, got)
    is ReplayExhaustedException -> Divergence.UnrecordedCall(index, got)
    is ReplayIncompleteException -> Divergence.UnusedJournalEntries(consumed, total)
    else -> {
      val replayedType = when (this) {
        is ReplayedCapabilityFailureException -> recordedFailure.type
        else -> failureType()
      }
      when (invocation.failure?.type) {
        replayedType -> null
        null -> Divergence.ChangedOutcome(
          recorded = "success",
          replayed = "failure $replayedType",
        )
        else -> Divergence.ChangedOutcome(
          recorded = "failure ${invocation.failure.type}",
          replayed = "failure $replayedType",
        )
      }
    }
  }
}
