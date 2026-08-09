package wasmo.mediation

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import wasmo.access.Caller

enum class InvocationKind {
  Http,
  Job,
}

data class InvocationFailure(
  val type: String,
  val message: String?,
)

/** One durable execution envelope. Child journal and audit rows are saved with this atomically. */
data class CapabilityInvocation(
  val id: String,
  val kind: InvocationKind,
  val appSlug: String,
  val appVersion: Long,
  val callerJson: String,
  val startedAt: Instant,
  val completedAt: Instant,
  val input: JsonElement,
  val output: JsonElement?,
  val failure: InvocationFailure?,
  val journal: List<JournalEntry>,
  val audit: List<AuditEntry>,
) {
  init {
    require((output == null) != (failure == null)) {
      "invocation must contain exactly one of output or failure"
    }
  }
}

fun interface CapabilityInvocationStore {
  /** Implementations must atomically save the invocation and all of its child rows. */
  suspend fun save(invocation: CapabilityInvocation)
}

/**
 * Creates one caller-bound mediation session and durably seals its result, journal, and audit chain.
 *
 * The persistence write happens in [NonCancellable], so cancellation is recorded too. If both app
 * execution and persistence fail, the persistence error is suppressed on the original failure.
 */
class CapabilityInvocationRecorder(
  private val store: CapabilityInvocationStore,
  private val clock: Clock,
  private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
  suspend fun <T> record(
    kind: InvocationKind,
    appSlug: String,
    appVersion: Long,
    caller: Caller,
    input: JsonElement,
    encodeOutput: (T) -> JsonElement,
    policy: CapabilityPolicy = CapabilityPolicy.AllowAll,
    block: suspend (CapabilitySession) -> T,
  ): T {
    val id = idFactory()
    val startedAt = clock.now()
    val callerJson = canonicalCaller(caller)
    val audit = AuditChain { clock.now().toEpochMilliseconds() }
    val session = CapabilitySession.recording(
      id = id,
      caller = caller,
      policy = policy,
      audit = audit,
    )
    val detail = "$kind:$appSlug:$appVersion"
    audit.append(
      kind = "invocation.start",
      caller = callerJson,
      detail = "$detail:${stableStringify(input)}",
      resultHash = AuditChain.resultHash(id),
    )

    val (result, output) = try {
      val value = block(session)
      value to encodeOutput(value)
    } catch (failure: Throwable) {
      val failureRecord = failure.toInvocationFailure()
      audit.append(
        kind = "invocation.failure",
        caller = callerJson,
        detail = detail,
        resultHash = AuditChain.resultHash(stableStringify(failure.toFailureJson())),
      )
      val invocation = CapabilityInvocation(
        id = id,
        kind = kind,
        appSlug = appSlug,
        appVersion = appVersion,
        callerJson = callerJson,
        startedAt = startedAt,
        completedAt = clock.now(),
        input = input,
        output = null,
        failure = failureRecord,
        journal = session.journal.snapshot(),
        audit = audit.snapshot(),
      )
      try {
        withContext(NonCancellable) { store.save(invocation) }
      } catch (persistenceFailure: Throwable) {
        failure.addSuppressed(persistenceFailure)
      }
      throw failure
    }

    audit.append(
      kind = "invocation.success",
      caller = callerJson,
      detail = detail,
      resultHash = AuditChain.resultHash(stableStringify(output)),
    )
    val invocation = CapabilityInvocation(
      id = id,
      kind = kind,
      appSlug = appSlug,
      appVersion = appVersion,
      callerJson = callerJson,
      startedAt = startedAt,
      completedAt = clock.now(),
      input = input,
      output = output,
      failure = null,
      journal = session.journal.snapshot(),
      audit = audit.snapshot(),
    )
    withContext(NonCancellable) { store.save(invocation) }
    return result
  }
}

private fun Throwable.toInvocationFailure() = InvocationFailure(
  type = failureType(),
  message = message,
)
