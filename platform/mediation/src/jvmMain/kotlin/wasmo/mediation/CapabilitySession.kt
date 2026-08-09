package wasmo.mediation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller

fun interface CapabilityPolicy {
  fun isAllowed(capability: String): Boolean

  companion object {
    val AllowAll = CapabilityPolicy { true }
  }
}

class CapabilityDeniedException(
  val capability: String,
) : IllegalStateException("capability denied: $capability")

class CapabilitySession private constructor(
  val id: String,
  val caller: Caller,
  val journal: CapabilityJournal,
  val audit: AuditChain,
  private val policy: CapabilityPolicy,
) {
  private val canonicalCaller = canonicalCaller(caller)

  suspend fun call(
    capability: String,
    method: String,
    request: JsonElement,
    live: suspend () -> JsonElement,
  ): JsonElement {
    val id = "$capability.$method"
    checkAllowed(id, request)
    try {
      val call = journal.call(capability, method, request, live)
      auditCall(id, request, call)
      return call.result
    } catch (failure: Exception) {
      auditFailure(id, request, failure)
      throw failure
    }
  }

  fun callSync(
    capability: String,
    method: String,
    request: JsonElement,
    live: () -> JsonElement,
  ): JsonElement {
    val id = "$capability.$method"
    checkAllowed(id, request)
    try {
      val call = journal.callSync(capability, method, request, live)
      auditCall(id, request, call)
      return call.result
    } catch (failure: Exception) {
      auditFailure(id, request, failure)
      throw failure
    }
  }

  private fun checkAllowed(capability: String, request: JsonElement) {
    if (policy.isAllowed(capability)) return
    audit.append(
      kind = "cap.denied",
      caller = canonicalCaller,
      detail = CapabilityJournal.key(
        capability = capability.substringBeforeLast('.'),
        method = capability.substringAfterLast('.'),
        request = request,
      ),
      resultHash = AuditChain.resultHash("denied"),
    )
    throw CapabilityDeniedException(capability)
  }

  private fun auditCall(
    capability: String,
    request: JsonElement,
    call: JournalCall,
  ) {
    audit.append(
      kind = when (call.source) {
        JournalSource.Live -> "cap.live"
        JournalSource.Replay -> "cap.replay"
      },
      caller = canonicalCaller,
      detail = "$capability:${stableStringify(request)}",
      resultHash = AuditChain.resultHash(stableStringify(call.result)),
    )
  }

  private fun auditFailure(
    capability: String,
    request: JsonElement,
    failure: Exception,
  ) {
    val result = jsonObjectOf(
      "message" to failure.message.toJson(),
      "type" to JsonPrimitive(failure::class.qualifiedName ?: "Exception"),
    )
    audit.append(
      kind = if (journal.isReplay) "cap.replay.error" else "cap.live.error",
      caller = canonicalCaller,
      detail = "$capability:${stableStringify(request)}",
      resultHash = AuditChain.resultHash(stableStringify(result)),
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
      caller = caller,
      journal = CapabilityJournal.recording(),
      audit = audit,
      policy = policy,
    )

    fun replay(
      id: String,
      caller: Caller,
      entries: List<JournalEntry>,
      policy: CapabilityPolicy = CapabilityPolicy.AllowAll,
      audit: AuditChain = AuditChain(),
    ) = CapabilitySession(
      id = id,
      caller = caller,
      journal = CapabilityJournal.replay(entries),
      audit = audit,
      policy = policy,
    )
  }
}

fun canonicalCaller(caller: Caller): String = stableStringify(caller.toJson())

fun Caller.toJson() = jsonObjectOf(
  "computerAccess" to JsonPrimitive(computerAccess.name),
  "ip" to ip.toJson(),
  "service" to service.toJson(),
  "userAgent" to userAgent.toJson(),
  "userId" to userId.toJson(),
)
