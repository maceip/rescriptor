package wasmo.mediation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical JSON format used in capability journal keys and audit hashes.
 *
 * This is the Kotlin port of rescriptor's `stableStringify`: object keys are sorted, arrays retain
 * their order, and no insignificant whitespace is emitted.
 */
fun stableStringify(value: JsonElement): String = when (value) {
  is JsonObject -> value.entries
    .sortedBy { it.key }
    .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, element) ->
      "${JsonPrimitive(key)}:${stableStringify(element)}"
    }

  is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
    stableStringify(it)
  }

  is JsonPrimitive -> value.toString()
  JsonNull -> "null"
}

internal fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
  JsonObject(linkedMapOf(*entries))

internal fun String?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Long?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Boolean?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun JsonElement.stringOrNull(): String? =
  if (this === JsonNull) null else (this as JsonPrimitive).content

internal fun JsonElement.longOrNull(): Long? =
  if (this === JsonNull) null else (this as JsonPrimitive).content.toLong()

internal fun JsonElement.booleanOrNull(): Boolean? =
  if (this === JsonNull) null else (this as JsonPrimitive).content.toBooleanStrict()
