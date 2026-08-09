@file:OptIn(ExperimentalUuidApi::class)

package com.wasmo.db.mediation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import wasmo.json.JsonLiteral
import wasmo.mediation.AuditChain
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.InvocationKind
import wasmo.mediation.JournalEntry
import wasmo.sql.RowIterator
import wasmo.sql.SqlBinder
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase

class SqlCapabilityInvocationStoreTest {
  @Test
  fun writesEnvelopeJournalAndAuditInOneTransaction() = runBlocking {
    val database = RecordingDatabase()
    val audit = AuditChain { 1_234L }.apply {
      append("invocation.success", "caller", "detail", AuditChain.resultHash("result"))
    }
    SqlCapabilityInvocationStore(database).save(
      CapabilityInvocation(
        id = "00000000-0000-0000-0000-000000000001",
        kind = InvocationKind.Http,
        appSlug = "notes",
        appVersion = 1L,
        callerJson = "{}",
        startedAt = Instant.fromEpochMilliseconds(1_000L),
        completedAt = Instant.fromEpochMilliseconds(2_000L),
        input = JsonNull,
        output = JsonPrimitive("ok"),
        failure = null,
        journal = listOf(JournalEntry("clock.now:null", result = JsonPrimitive(1_500L))),
        audit = audit.snapshot(),
      ),
    )

    assertEquals("BEGIN", database.statements.first())
    assertEquals("COMMIT", database.statements.last())
    assertTrue(database.statements.any { it.startsWith("INSERT INTO CapabilityInvocation(") })
    assertTrue(database.statements.any { it.startsWith("INSERT INTO CapabilityJournalEntry(") })
    assertTrue(database.statements.any { it.startsWith("INSERT INTO CapabilityAuditEntry(") })
  }

  private class RecordingDatabase : SqlDatabase {
    val statements = mutableListOf<String>()

    override suspend fun newConnection() = object : SqlConnection {
      override suspend fun execute(
        sql: String,
        bindParameters: (SqlBinder.() -> Unit)?,
      ): Long {
        statements += sql.trim()
        bindParameters?.invoke(NoOpBinder)
        return 1L
      }

      override suspend fun executeQuery(
        sql: String,
        bindParameters: (SqlBinder.() -> Unit)?,
      ): RowIterator = error("unused")

      override fun close() = Unit
    }

    override fun close() = Unit
  }

  private object NoOpBinder : SqlBinder {
    override fun bindBool(index: Int, value: Boolean?) = Unit
    override fun bindS32(index: Int, value: Int?) = Unit
    override fun bindS64(index: Int, value: Long?) = Unit
    override fun bindF32(index: Int, value: Float?) = Unit
    override fun bindF64(index: Int, value: Double?) = Unit
    override fun bindInstant(index: Int, value: Instant?) = Unit
    override fun bindString(index: Int, value: String?) = Unit
    override fun bindBytes(index: Int, value: ByteString?) = Unit
    override fun bindUuid(index: Int, value: Uuid?) = Unit
    override fun bindJson(index: Int, value: JsonLiteral?) = Unit
  }
}
