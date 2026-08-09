package wasmo.mediation

import okio.ByteString.Companion.encodeUtf8

data class AuditEntry(
  val seq: Long,
  val timestampMillis: Long,
  val kind: String,
  val caller: String,
  val detail: String,
  val resultHash: String,
  val previousHash: String,
  val hash: String,
)

sealed interface AuditVerification {
  data class Valid(
    val entries: Int,
    val head: String,
  ) : AuditVerification

  data class Invalid(
    val badSequence: Long,
    val reason: String,
  ) : AuditVerification
}

/** In-memory chain construction. Persistence adapters can store [AuditEntry] without changing it. */
class AuditChain(
  private val timestampMillis: () -> Long = System::currentTimeMillis,
) {
  private val entries = mutableListOf<AuditEntry>()

  fun snapshot(): List<AuditEntry> = entries.toList()

  fun append(
    kind: String,
    caller: String,
    detail: String,
    resultHash: String,
  ): AuditEntry {
    val previousHash = entries.lastOrNull()?.hash ?: Genesis
    val timestamp = timestampMillis()
    val entry = AuditEntry(
      seq = entries.size.toLong() + 1L,
      timestampMillis = timestamp,
      kind = kind,
      caller = caller,
      detail = detail,
      resultHash = resultHash,
      previousHash = previousHash,
      hash = rowHash(previousHash, timestamp, kind, caller, detail, resultHash),
    )
    entries += entry
    return entry
  }

  fun verify(): AuditVerification = verify(entries)

  companion object {
    val Genesis: String = "0".repeat(64)

    fun verify(entries: List<AuditEntry>): AuditVerification {
      var previousHash = Genesis
      for (entry in entries) {
        if (entry.previousHash != previousHash) {
          return AuditVerification.Invalid(entry.seq, "previous hash mismatch")
        }
        val expected = rowHash(
          previousHash = previousHash,
          timestampMillis = entry.timestampMillis,
          kind = entry.kind,
          caller = entry.caller,
          detail = entry.detail,
          resultHash = entry.resultHash,
        )
        if (entry.hash != expected) {
          return AuditVerification.Invalid(entry.seq, "hash mismatch")
        }
        previousHash = entry.hash
      }
      return AuditVerification.Valid(entries.size, previousHash)
    }

    fun resultHash(result: String): String = result.encodeUtf8().sha256().hex()

    private fun rowHash(
      previousHash: String,
      timestampMillis: Long,
      kind: String,
      caller: String,
      detail: String,
      resultHash: String,
    ): String = listOf(
      previousHash,
      timestampMillis.toString(),
      kind,
      caller,
      detail,
      resultHash,
    ).joinToString("\n").encodeUtf8().sha256().hex()
  }
}
