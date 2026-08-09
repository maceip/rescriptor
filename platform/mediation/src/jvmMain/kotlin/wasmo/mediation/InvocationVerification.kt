package wasmo.mediation

import kotlinx.serialization.json.JsonNull

sealed interface InvocationVerification {
  data class Valid(
    val capabilityCalls: Int,
    val auditEntries: Int,
    val head: String,
  ) : InvocationVerification

  data class Invalid(
    /** The audit sequence number the problem was found at, or null if it is not one row's fault. */
    val seq: Long?,
    val reason: String,
  ) : InvocationVerification
}

/**
 * Proves offline that [invocation] is an honest record of what an app did.
 *
 * Verifying the hash chain alone only proves the chain is internally consistent. This also proves
 * the chain describes *this* journal: every capability the app used appears in the audit in the
 * same order, with a result hash over the value the journal says it received, and the invocation's
 * own start and outcome are bound in too. Rewriting a stored result therefore cannot be hidden by
 * recomputing the chain — the journal and the chain would have to be forged consistently, and the
 * head published at the time would still not match.
 *
 * This needs nothing but the exported invocation, so the person who owns the computer can run it
 * on hardware the operator does not control.
 */
fun verifyInvocation(invocation: CapabilityInvocation): InvocationVerification {
  when (val chain = AuditChain.verify(invocation.audit)) {
    is AuditVerification.Invalid -> return InvocationVerification.Invalid(
      seq = chain.badSequence,
      reason = chain.reason,
    )
    is AuditVerification.Valid -> Unit
  }

  val audit = invocation.audit
  if (audit.isEmpty()) {
    return InvocationVerification.Invalid(seq = null, reason = "invocation has no audit entries")
  }

  audit.firstOrNull { it.caller != invocation.callerJson }?.let {
    return InvocationVerification.Invalid(
      seq = it.seq,
      reason = "audit entry caller does not match the invocation caller",
    )
  }

  val start = audit.first()
  if (start.kind != "invocation.start") {
    return InvocationVerification.Invalid(
      seq = start.seq,
      reason = "first audit entry is '${start.kind}', expected 'invocation.start'",
    )
  }
  if (start.resultHash != AuditChain.resultHash(invocation.id)) {
    return InvocationVerification.Invalid(
      seq = start.seq,
      reason = "'invocation.start' does not commit to this invocation's id",
    )
  }
  val expectedStartDetail = invocationDetail(invocation) +
    ":${stableStringify(invocation.input)}"
  if (start.detail != expectedStartDetail) {
    return InvocationVerification.Invalid(
      seq = start.seq,
      reason = "'invocation.start' does not commit to this invocation's input",
    )
  }

  val outcome = audit.last()
  val expectedOutcomeKind = when (invocation.failure) {
    null -> "invocation.success"
    else -> "invocation.failure"
  }
  if (outcome.kind != expectedOutcomeKind) {
    return InvocationVerification.Invalid(
      seq = outcome.seq,
      reason = "last audit entry is '${outcome.kind}', expected '$expectedOutcomeKind'",
    )
  }
  if (outcome.detail != invocationDetail(invocation)) {
    return InvocationVerification.Invalid(
      seq = outcome.seq,
      reason = "'${outcome.kind}' does not commit to this invocation's app and version",
    )
  }
  val expectedOutcomeHash = when (val failure = invocation.failure) {
    null -> AuditChain.resultHash(stableStringify(invocation.output ?: JsonNull))
    else -> AuditChain.resultHash(
      stableStringify(JournalFailure(failure.type, failure.message).toJson()),
    )
  }
  if (outcome.resultHash != expectedOutcomeHash) {
    return InvocationVerification.Invalid(
      seq = outcome.seq,
      reason = "'${outcome.kind}' does not commit to this invocation's outcome",
    )
  }

  val capabilityEntries = audit.filter { it.kind.startsWith("cap.") }
  if (capabilityEntries.size != invocation.journal.size) {
    return InvocationVerification.Invalid(
      seq = null,
      reason = "audit describes ${capabilityEntries.size} capability calls but the journal has " +
        "${invocation.journal.size}",
    )
  }

  for ((index, entry) in capabilityEntries.withIndex()) {
    val journalEntry = invocation.journal[index]
    if (entry.detail != journalEntry.key) {
      return InvocationVerification.Invalid(
        seq = entry.seq,
        reason = "audit entry describes '${entry.detail}' but journal entry $index is " +
          "'${journalEntry.key}'",
      )
    }
    val expected = when (val failure = journalEntry.failure) {
      null -> AuditChain.resultHash(stableStringify(journalEntry.result!!))
      else -> AuditChain.resultHash(stableStringify(failure.toJson()))
    }
    if (entry.resultHash != expected) {
      return InvocationVerification.Invalid(
        seq = entry.seq,
        reason = "audit entry does not commit to the result recorded in journal entry $index",
      )
    }
    val expectedKinds = when (journalEntry.failure) {
      null -> SuccessKinds
      else -> FailureKinds
    }
    if (entry.kind !in expectedKinds) {
      return InvocationVerification.Invalid(
        seq = entry.seq,
        reason = "audit entry kind '${entry.kind}' disagrees with journal entry $index",
      )
    }
  }

  return InvocationVerification.Valid(
    capabilityCalls = invocation.journal.size,
    auditEntries = audit.size,
    head = audit.last().hash,
  )
}

internal fun invocationDetail(invocation: CapabilityInvocation): String =
  "${invocation.kind}:${invocation.appSlug}:${invocation.appVersion}"

private val SuccessKinds = setOf("cap.live", "cap.replay")
private val FailureKinds = setOf("cap.live.error", "cap.replay.error", "cap.denied")
