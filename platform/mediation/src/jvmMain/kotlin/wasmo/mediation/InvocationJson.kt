package wasmo.mediation

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The portable form of a recorded invocation.
 *
 * This is the artifact a person can be handed: it carries everything [verifyInvocation] and
 * [InvocationReplayer] need, so what the OS claims about an app's behaviour can be checked away
 * from the OS that made the claim. It is a stable format — [Version] changes only if the fields do.
 *
 * Values are written verbatim, never re-canonicalized on the way out. `callerJson` in particular is
 * carried as the exact string the audit chain committed to.
 */
object InvocationJson {
  const val Version: Int = 1

  class MalformedInvocationException(
    message: String,
    cause: Throwable? = null,
  ) : IllegalArgumentException(message, cause)

  fun encode(invocation: CapabilityInvocation): JsonObject = jsonObjectOf(
    "audit" to JsonArray(invocation.audit.map { it.toJson() }),
    "invocation" to jsonObjectOf(
      "appSlug" to JsonPrimitive(invocation.appSlug),
      "appVersion" to JsonPrimitive(invocation.appVersion),
      "callerJson" to JsonPrimitive(invocation.callerJson),
      "completedAt" to JsonPrimitive(invocation.completedAt.toString()),
      "failure" to (invocation.failure?.toJson() ?: JsonNull),
      "id" to JsonPrimitive(invocation.id),
      "input" to invocation.input,
      "kind" to JsonPrimitive(invocation.kind.name),
      "output" to (invocation.output ?: JsonNull),
      "startedAt" to JsonPrimitive(invocation.startedAt.toString()),
    ),
    "journal" to JsonArray(invocation.journal.map { it.toJson() }),
    "version" to JsonPrimitive(Version),
  )

  fun encodeToString(invocation: CapabilityInvocation): String = stableStringify(encode(invocation))

  fun decodeFromString(json: String): CapabilityInvocation = decode(
    try {
      Json.parseToJsonElement(json)
    } catch (failure: Exception) {
      throw MalformedInvocationException("not valid JSON", failure)
    },
  )

  fun decode(element: JsonElement): CapabilityInvocation {
    val root = element.asObject("<root>")
    val version = root.member("version").asInt("version")
    if (version != Version) {
      throw MalformedInvocationException(
        "unsupported invocation format version $version, expected $Version",
      )
    }
    val invocation = root.member("invocation").asObject("invocation")
    val failure = invocation.member("failure").takeIf { it !== JsonNull }
      ?.asObject("invocation.failure")
      ?.let {
        InvocationFailure(
          type = it.member("type").asString("invocation.failure.type"),
          message = it.optional("message")?.asString("invocation.failure.message"),
        )
      }

    return try {
      CapabilityInvocation(
        id = invocation.member("id").asString("invocation.id"),
        kind = invocation.member("kind").asString("invocation.kind").toInvocationKind(),
        appSlug = invocation.member("appSlug").asString("invocation.appSlug"),
        appVersion = invocation.member("appVersion").asLong("invocation.appVersion"),
        callerJson = invocation.member("callerJson").asString("invocation.callerJson"),
        startedAt = invocation.member("startedAt").asInstant("invocation.startedAt"),
        completedAt = invocation.member("completedAt").asInstant("invocation.completedAt"),
        input = invocation.member("input"),
        output = invocation.member("output").takeIf { failure == null },
        failure = failure,
        journal = root.member("journal").asArray("journal").mapIndexed { index, entry ->
          entry.toJournalEntry("journal[$index]")
        },
        audit = root.member("audit").asArray("audit").mapIndexed { index, entry ->
          entry.toAuditEntry("audit[$index]")
        },
      )
    } catch (failure: IllegalArgumentException) {
      if (failure is MalformedInvocationException) throw failure
      throw MalformedInvocationException(failure.message ?: "malformed invocation", failure)
    }
  }
}

private fun InvocationFailure.toJson(): JsonObject = jsonObjectOf(
  "message" to message.toJson(),
  "type" to JsonPrimitive(type),
)

private fun JournalEntry.toJson(): JsonObject = jsonObjectOf(
  "failure" to (failure?.toJson() ?: JsonNull),
  "key" to JsonPrimitive(key),
  "result" to (result ?: JsonNull),
)

private fun AuditEntry.toJson(): JsonObject = jsonObjectOf(
  "caller" to JsonPrimitive(caller),
  "detail" to JsonPrimitive(detail),
  "hash" to JsonPrimitive(hash),
  "kind" to JsonPrimitive(kind),
  "previousHash" to JsonPrimitive(previousHash),
  "resultHash" to JsonPrimitive(resultHash),
  "seq" to JsonPrimitive(seq),
  "timestampMillis" to JsonPrimitive(timestampMillis),
)

private fun JsonElement.toJournalEntry(path: String): JournalEntry {
  val entry = asObject(path)
  val failure = entry.member("failure").takeIf { it !== JsonNull }?.asObject("$path.failure")
  return JournalEntry(
    key = entry.member("key").asString("$path.key"),
    result = entry.member("result").takeIf { failure == null },
    failure = failure?.let {
      JournalFailure(
        type = it.member("type").asString("$path.failure.type"),
        message = it.optional("message")?.asString("$path.failure.message"),
      )
    },
  )
}

private fun JsonElement.toAuditEntry(path: String): AuditEntry {
  val entry = asObject(path)
  return AuditEntry(
    seq = entry.member("seq").asLong("$path.seq"),
    timestampMillis = entry.member("timestampMillis").asLong("$path.timestampMillis"),
    kind = entry.member("kind").asString("$path.kind"),
    caller = entry.member("caller").asString("$path.caller"),
    detail = entry.member("detail").asString("$path.detail"),
    resultHash = entry.member("resultHash").asString("$path.resultHash"),
    previousHash = entry.member("previousHash").asString("$path.previousHash"),
    hash = entry.member("hash").asString("$path.hash"),
  )
}

private fun String.toInvocationKind(): InvocationKind = InvocationKind.entries
  .firstOrNull { it.name == this }
  ?: throw InvocationJson.MalformedInvocationException("unknown invocation kind '$this'")

private fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject
  ?: throw InvocationJson.MalformedInvocationException("$path is not a JSON object")

private fun JsonElement.asArray(path: String): JsonArray = this as? JsonArray
  ?: throw InvocationJson.MalformedInvocationException("$path is not a JSON array")

private fun JsonObject.member(name: String): JsonElement = this[name]
  ?: throw InvocationJson.MalformedInvocationException("missing property: $name")

private fun JsonObject.optional(name: String): JsonElement? = this[name]?.takeIf { it !== JsonNull }

private fun JsonElement.asString(path: String): String = (this as? JsonPrimitive)
  ?.takeIf { it.isString }?.content
  ?: throw InvocationJson.MalformedInvocationException("$path is not a string")

private fun JsonElement.asLong(path: String): Long = (this as? JsonPrimitive)?.content?.toLongOrNull()
  ?: throw InvocationJson.MalformedInvocationException("$path is not an integer")

private fun JsonElement.asInt(path: String): Int = (this as? JsonPrimitive)?.content?.toIntOrNull()
  ?: throw InvocationJson.MalformedInvocationException("$path is not an integer")

private fun JsonElement.asInstant(path: String): Instant = try {
  Instant.parse(asString(path))
} catch (failure: IllegalArgumentException) {
  throw InvocationJson.MalformedInvocationException("$path is not a timestamp", failure)
}
