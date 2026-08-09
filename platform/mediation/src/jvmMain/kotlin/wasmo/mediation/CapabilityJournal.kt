package wasmo.mediation

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonElement

data class JournalEntry(
  val key: String,
  val result: JsonElement? = null,
  val failure: JournalFailure? = null,
) {
  init {
    require((result == null) != (failure == null)) {
      "journal entry must contain exactly one of result or failure"
    }
  }
}

data class JournalFailure(
  val type: String,
  val message: String?,
)

enum class JournalSource {
  Live,
  Replay,
}

class ReplayMismatchException(
  val index: Int,
  val expected: String,
  val got: String,
) : IllegalStateException(
  "nondeterministic replay at journal entry $index: expected $expected, got $got",
)

class ReplayExhaustedException(
  val index: Int,
  val got: String,
) : IllegalStateException(
  "nondeterministic replay made an unrecorded capability call at journal entry $index: $got",
)

class ReplayIncompleteException(
  val consumed: Int,
  val total: Int,
) : IllegalStateException(
  "nondeterministic replay stopped after $consumed of $total journal entries",
)

class ReplayedCapabilityFailureException(
  val index: Int,
  val key: String,
  val recordedFailure: JournalFailure,
) : IllegalStateException(
  "recorded capability failure at journal entry $index ($key): " +
    "${recordedFailure.type}: ${recordedFailure.message.orEmpty()}",
)

class CapabilityJournal private constructor(
  private val recording: Boolean,
  initialEntries: List<JournalEntry>,
) {
  private val entries = initialEntries.toMutableList()
  private var cursor = 0

  val isReplay: Boolean
    get() = !recording

  val position: Int
    get() = cursor

  fun snapshot(): List<JournalEntry> = entries.toList()

  suspend fun call(
    capability: String,
    method: String,
    request: JsonElement,
    live: suspend () -> JsonElement,
  ): JournalCall = call(key(capability, method, request), live)

  fun callSync(
    capability: String,
    method: String,
    request: JsonElement,
    live: () -> JsonElement,
  ): JournalCall = callSync(key(capability, method, request), live)

  fun requireFullyReplayed() {
    if (isReplay && cursor != entries.size) {
      throw ReplayIncompleteException(consumed = cursor, total = entries.size)
    }
  }

  private suspend fun call(
    key: String,
    live: suspend () -> JsonElement,
  ): JournalCall {
    if (recording) {
      try {
        val result = live()
        entries += JournalEntry(key = key, result = result)
        cursor += 1
        return JournalCall(result, JournalSource.Live)
      } catch (failure: CancellationException) {
        throw failure
      } catch (failure: Exception) {
        entries += JournalEntry(key = key, failure = failure.toJournalFailure())
        cursor += 1
        throw failure
      }
    }
    return replay(key)
  }

  private fun callSync(
    key: String,
    live: () -> JsonElement,
  ): JournalCall {
    if (recording) {
      try {
        val result = live()
        entries += JournalEntry(key = key, result = result)
        cursor += 1
        return JournalCall(result, JournalSource.Live)
      } catch (failure: Exception) {
        entries += JournalEntry(key = key, failure = failure.toJournalFailure())
        cursor += 1
        throw failure
      }
    }
    return replay(key)
  }

  private fun replay(key: String): JournalCall {
    if (cursor == entries.size) {
      throw ReplayExhaustedException(index = cursor, got = key)
    }
    val index = cursor
    val entry = entries[cursor++]
    if (entry.key != key) {
      throw ReplayMismatchException(index = index, expected = entry.key, got = key)
    }
    entry.failure?.let {
      throw ReplayedCapabilityFailureException(index = index, key = key, recordedFailure = it)
    }
    return JournalCall(entry.result!!, JournalSource.Replay)
  }

  companion object {
    fun recording(): CapabilityJournal = CapabilityJournal(
      recording = true,
      initialEntries = emptyList(),
    )

    fun replay(entries: List<JournalEntry>): CapabilityJournal = CapabilityJournal(
      recording = false,
      initialEntries = entries,
    )

    fun key(
      capability: String,
      method: String,
      request: JsonElement,
    ): String = "$capability.$method:${stableStringify(request)}"
  }
}

data class JournalCall(
  val result: JsonElement,
  val source: JournalSource,
)

private fun Exception.toJournalFailure() = JournalFailure(
  type = failureType(),
  message = message,
)
