package wasmo.mediation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller

/**
 * One app invocation's capability boundary.
 *
 * Every effect an app has on the world passes through [call] or [callSync]. Each one is checked
 * against the app's [CapabilityPolicy], journaled so the invocation can be replayed exactly, and
 * appended to a hash-chained [AuditChain].
 *
 * A denial is journaled like any other failed call. That is what lets a replay reproduce a denied
 * invocation without re-consulting the policy, which may since have changed.
 */
class CapabilitySession private constructor(
  val id: String,
  /** The canonical JSON identity of whoever caused this invocation. */
  val callerJson: String,
  val journal: CapabilityJournal,
  val audit: AuditChain,
  private val policy: CapabilityPolicy,
) {
  suspend fun call(
    capability: String,
    method: String,
    request: JsonElement,
    live: suspend () -> JsonElement,
  ): JsonElement {
    val capabilityCall = CapabilityCall(capability, method, request)
    val decision = decide(capabilityCall)
    try {
      val journalCall = journal.call(capability, method, request) {
        decision.requireAllowed(capabilityCall)
        live()
      }
      auditCall(capabilityCall, journalCall)
      return journalCall.result
    } catch (failure: Exception) {
      auditFailure(capabilityCall, failure)
      throw failure
    }
  }

  fun callSync(
    capability: String,
    method: String,
    request: JsonElement,
    live: () -> JsonElement,
  ): JsonElement {
    val capabilityCall = CapabilityCall(capability, method, request)
    val decision = decide(capabilityCall)
    try {
      val journalCall = journal.callSync(capability, method, request) {
        decision.requireAllowed(capabilityCall)
        live()
      }
      auditCall(capabilityCall, journalCall)
      return journalCall.result
    } catch (failure: Exception) {
      auditFailure(capabilityCall, failure)
      throw failure
    }
  }

  /** A replay is authorized by its journal, which already holds the original decisions. */
  private fun decide(call: CapabilityCall): CapabilityDecision = when {
    journal.isReplay -> CapabilityDecision.Allow
    else -> policy.decide(call)
  }

  private fun CapabilityDecision.requireAllowed(call: CapabilityCall) {
    if (this is CapabilityDecision.Deny) {
      throw CapabilityDeniedException(capability = call.id, reason = reason)
    }
  }

  private fun auditCall(
    call: CapabilityCall,
    journalCall: JournalCall,
  ) {
    audit.append(
      kind = when (journalCall.source) {
        JournalSource.Live -> "cap.live"
        JournalSource.Replay -> "cap.replay"
      },
      caller = callerJson,
      detail = CapabilityJournal.key(call.capability, call.method, call.request),
      resultHash = AuditChain.resultHash(stableStringify(journalCall.result)),
    )
  }

  private fun auditFailure(
    call: CapabilityCall,
    failure: Exception,
  ) {
    audit.append(
      kind = when {
        failure is CapabilityDeniedException -> "cap.denied"
        journal.isReplay -> "cap.replay.error"
        else -> "cap.live.error"
      },
      caller = callerJson,
      detail = CapabilityJournal.key(call.capability, call.method, call.request),
      resultHash = AuditChain.resultHash(stableStringify(failure.toFailureJson())),
    )
  }

  companion object {
    fun recording(
      id: String,
      caller: Caller,
      policy: CapabilityPolicy = CapabilityPolicy.AllowAll,
      audit: AuditChain = AuditChain(),
    ) = CapabilitySession(
      id = id,
      callerJson = canonicalCaller(caller),
      journal = CapabilityJournal.recording(),
      audit = audit,
      policy = policy,
    )

    fun replay(
      id: String,
      caller: Caller,
      entries: List<JournalEntry>,
      audit: AuditChain = AuditChain(),
    ) = replay(
      id = id,
      callerJson = canonicalCaller(caller),
      entries = entries,
      audit = audit,
    )

    /**
     * Replays a durably recorded invocation, whose caller was already canonicalized when it ran.
     */
    fun replay(
      id: String,
      callerJson: String,
      entries: List<JournalEntry>,
      audit: AuditChain = AuditChain(),
    ) = CapabilitySession(
      id = id,
      callerJson = callerJson,
      journal = CapabilityJournal.replay(entries),
      audit = audit,
      policy = CapabilityPolicy.AllowAll,
    )
  }
}

/** The canonical failure shape used by both journal entries and audit result hashes. */
internal fun Throwable.toFailureJson(): JsonObject = jsonObjectOf(
  "message" to message.toJson(),
  "type" to JsonPrimitive(failureType()),
)

internal fun JournalFailure.toJson(): JsonObject = jsonObjectOf(
  "message" to message.toJson(),
  "type" to JsonPrimitive(type),
)

internal fun Throwable.failureType(): String =
  this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"

fun canonicalCaller(caller: Caller): String = stableStringify(caller.toJson())

fun Caller.toJson() = jsonObjectOf(
  "computerAccess" to JsonPrimitive(computerAccess.name),
  "ip" to ip.toJson(),
  "service" to service.toJson(),
  "userAgent" to userAgent.toJson(),
  "userId" to userId.toJson(),
)
