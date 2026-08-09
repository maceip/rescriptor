package com.wasmo.db.mediation

import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import wasmo.mediation.AuditChain
import wasmo.mediation.AuditEntry
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.CapabilityInvocationReader
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.InvocationFailure
import wasmo.mediation.InvocationKind
import wasmo.mediation.InvocationQuery
import wasmo.mediation.InvocationSummary
import wasmo.mediation.JournalEntry
import wasmo.mediation.JournalFailure
import wasmo.mediation.stableStringify
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase
import wasmox.sql.list
import wasmox.sql.singleOrNull
import wasmox.sql.transaction
import wasmox.sql.withConnection

/**
 * PostgreSQL persistence for complete invocations.
 *
 * A write commits the envelope, journal, and audit rows as one transaction, and a read returns them
 * as one consistent snapshot. Both halves matter: an audit row whose journal is missing proves
 * nothing, and a journal that cannot be read back cannot be replayed.
 */
@Inject
@SingleIn(OsScope::class)
class SqlCapabilityInvocationStore(
  private val database: SqlDatabase,
) : CapabilityInvocationStore, CapabilityInvocationReader {
  override suspend fun save(invocation: CapabilityInvocation) {
    database.transaction {
      insertCapabilityInvocation(invocation)
    }
  }

  override suspend fun load(id: String): CapabilityInvocation? = database.transaction {
    selectCapabilityInvocation(id)
  }

  override suspend fun list(query: InvocationQuery): List<InvocationSummary> =
    database.withConnection {
      selectCapabilityInvocations(query)
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

context(connection: SqlConnection)
private suspend fun selectCapabilityInvocation(id: String): CapabilityInvocation? {
  val header = connection.executeQuery(
    """
    SELECT
      kind,
      app_slug,
      app_version,
      caller_json,
      started_at,
      completed_at,
      input_json,
      output_json,
      failure_type,
      failure_message
    FROM CapabilityInvocation
    WHERE id = $1
    """,
  ) {
    bindString(0, id)
  }.singleOrNull {
    InvocationHeader(
      kind = getString(0)!!.toInvocationKind(),
      appSlug = getString(1)!!,
      appVersion = getS64(2)!!,
      callerJson = getString(3)!!,
      startedAt = getInstant(4)!!,
      completedAt = getInstant(5)!!,
      input = getString(6)!!.toJsonElement(),
      output = getString(7)?.toJsonElement(),
      failure = getString(8)?.let { InvocationFailure(type = it, message = getString(9)) },
    )
  } ?: return null

  val journal = connection.executeQuery(
    """
    SELECT capability_key, result_json, failure_type, failure_message
    FROM CapabilityJournalEntry
    WHERE invocation_id = $1
    ORDER BY seq ASC
    """,
  ) {
    bindString(0, id)
  }.list {
    val failureType = getString(2)
    JournalEntry(
      key = getString(0)!!,
      result = if (failureType == null) getString(1)!!.toJsonElement() else null,
      failure = failureType?.let { JournalFailure(type = it, message = getString(3)) },
    )
  }

  val audit = connection.executeQuery(
    """
    SELECT
      seq,
      timestamp_millis,
      kind,
      caller_json,
      detail,
      result_hash,
      previous_hash,
      hash
    FROM CapabilityAuditEntry
    WHERE invocation_id = $1
    ORDER BY seq ASC
    """,
  ) {
    bindString(0, id)
  }.list {
    AuditEntry(
      seq = getS64(0)!!,
      timestampMillis = getS64(1)!!,
      kind = getString(2)!!,
      caller = getString(3)!!,
      detail = getString(4)!!,
      resultHash = getString(5)!!,
      previousHash = getString(6)!!,
      hash = getString(7)!!,
    )
  }

  return CapabilityInvocation(
    id = id,
    kind = header.kind,
    appSlug = header.appSlug,
    appVersion = header.appVersion,
    callerJson = header.callerJson,
    startedAt = header.startedAt,
    completedAt = header.completedAt,
    input = header.input,
    output = header.output,
    failure = header.failure,
    journal = journal,
    audit = audit,
  )
}

context(connection: SqlConnection)
private suspend fun selectCapabilityInvocations(
  query: InvocationQuery,
): List<InvocationSummary> = connection.executeQuery(
  """
  SELECT
    id,
    kind,
    app_slug,
    app_version,
    caller_json,
    started_at,
    completed_at,
    failure_type,
    journal_count,
    audit_count,
    audit_head
  FROM CapabilityInvocation
  WHERE ($1::TEXT IS NULL OR app_slug = $1)
    AND ($2::TIMESTAMPTZ IS NULL OR started_at < $2)
  ORDER BY started_at DESC, id DESC
  LIMIT $3
  """,
) {
  bindString(0, query.appSlug)
  bindInstant(1, query.startedBefore)
  bindS64(2, query.limit.toLong())
}.list {
  InvocationSummary(
    id = getString(0)!!,
    kind = getString(1)!!.toInvocationKind(),
    appSlug = getString(2)!!,
    appVersion = getS64(3)!!,
    callerJson = getString(4)!!,
    startedAt = getInstant(5)!!,
    completedAt = getInstant(6)!!,
    failureType = getString(7),
    journalCount = getS64(8)!!,
    auditCount = getS64(9)!!,
    auditHead = getString(10)!!,
  )
}

private class InvocationHeader(
  val kind: InvocationKind,
  val appSlug: String,
  val appVersion: Long,
  val callerJson: String,
  val startedAt: kotlin.time.Instant,
  val completedAt: kotlin.time.Instant,
  val input: JsonElement,
  val output: JsonElement?,
  val failure: InvocationFailure?,
)

private fun String.toInvocationKind(): InvocationKind = InvocationKind.entries
  .firstOrNull { it.name == this }
  ?: error("unknown invocation kind stored in CapabilityInvocation: $this")

private fun String.toJsonElement(): JsonElement = Json.parseToJsonElement(this)
