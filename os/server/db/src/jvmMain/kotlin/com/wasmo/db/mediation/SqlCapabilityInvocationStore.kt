package com.wasmo.db.mediation

import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import wasmo.mediation.AuditChain
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.stableStringify
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase
import wasmox.sql.transaction

/** PostgreSQL persistence for a complete invocation, committed as one transaction. */
@Inject
@SingleIn(OsScope::class)
class SqlCapabilityInvocationStore(
  private val database: SqlDatabase,
) : CapabilityInvocationStore {
  override suspend fun save(invocation: CapabilityInvocation) {
    database.transaction {
      insertCapabilityInvocation(invocation)
    }
  }
}

context(connection: SqlConnection)
private suspend fun insertCapabilityInvocation(invocation: CapabilityInvocation) {
  connection.execute(
    """
    INSERT INTO CapabilityInvocation(
      id,
      kind,
      app_slug,
      app_version,
      caller_json,
      started_at,
      completed_at,
      input_json,
      output_json,
      failure_type,
      failure_message,
      journal_count,
      audit_count,
      audit_head
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
    """,
  ) {
    bindString(0, invocation.id)
    bindString(1, invocation.kind.name)
    bindString(2, invocation.appSlug)
    bindS64(3, invocation.appVersion)
    bindString(4, invocation.callerJson)
    bindInstant(5, invocation.startedAt)
    bindInstant(6, invocation.completedAt)
    bindString(7, stableStringify(invocation.input))
    bindString(8, invocation.output?.let(::stableStringify))
    bindString(9, invocation.failure?.type)
    bindString(10, invocation.failure?.message)
    bindS64(11, invocation.journal.size.toLong())
    bindS64(12, invocation.audit.size.toLong())
    bindString(13, invocation.audit.lastOrNull()?.hash ?: AuditChain.Genesis)
  }

  invocation.journal.forEachIndexed { index, entry ->
    connection.execute(
      """
      INSERT INTO CapabilityJournalEntry(
        invocation_id,
        seq,
        capability_key,
        result_json,
        failure_type,
        failure_message
      ) VALUES ($1, $2, $3, $4, $5, $6)
      """,
    ) {
      bindString(0, invocation.id)
      bindS64(1, index.toLong())
      bindString(2, entry.key)
      bindString(3, entry.result?.let(::stableStringify))
      bindString(4, entry.failure?.type)
      bindString(5, entry.failure?.message)
    }
  }

  invocation.audit.forEach { entry ->
    connection.execute(
      """
      INSERT INTO CapabilityAuditEntry(
        invocation_id,
        seq,
        timestamp_millis,
        kind,
        caller_json,
        detail,
        result_hash,
        previous_hash,
        hash
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
      """,
    ) {
      bindString(0, invocation.id)
      bindS64(1, entry.seq)
      bindS64(2, entry.timestampMillis)
      bindString(3, entry.kind)
      bindString(4, entry.caller)
      bindString(5, entry.detail)
      bindString(6, entry.resultHash)
      bindString(7, entry.previousHash)
      bindString(8, entry.hash)
    }
  }
}
