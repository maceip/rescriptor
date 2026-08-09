@file:OptIn(ExperimentalUuidApi::class)

package com.wasmo.wasm.endive

import java.security.SecureRandom
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import wasmo.app.Platform
import wasmo.downloader.TransferRequest
import wasmo.http.Header
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.json.JsonLiteral
import wasmo.mediation.MediatedPlatform
import wasmo.objectstore.DeleteObjectRequest
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.GetObjectResponse
import wasmo.objectstore.ListObjectsRequest
import wasmo.objectstore.ListObjectsResponse
import wasmo.objectstore.PutObjectRequest
import wasmo.sql.SqlBinder
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlRow

/** Entropy is journaled when this execution is running on a [MediatedPlatform]. */
internal class JournaledRandomSource(
  private val platform: Platform,
  private val secureRandom: SecureRandom = SecureRandom(),
) {
  fun bytes(length: Int): ByteArray {
    require(length >= 0) { "random byte length must be non-negative" }
    fun live() = ByteArray(length).also(secureRandom::nextBytes).toByteString().base64()
    val base64 = if (platform is MediatedPlatform) {
      platform.session.callSync(
        capability = "random",
        method = "bytes",
        request = jsonObjectOf("length" to JsonPrimitive(length)),
      ) { JsonPrimitive(live()) }.jsonPrimitive.content
    } else {
      live()
    }
    return base64.decodeBase64()?.toByteArray() ?: error("invalid recorded random bytes")
  }
}

/**
 * JSON dispatch for the complete Wasmo [Platform] capability surface.
 *
 * One host serves one invocation, and owns any SQL handles that invocation opened. Closing it
 * closes them.
 */
internal class PlatformCapabilityHost(
  private val platform: Platform,
  private val randomSource: JournaledRandomSource = JournaledRandomSource(platform),
  /**
   * The ceiling on a single capability result, in the encoding the guest will receive.
   *
   * The runtime also checks this after the fact, but by then the host has already built the value.
   * Checking here, against sizes that are known before anything is encoded, is what stops a guest
   * from exhausting the OS's heap with one oversized read.
   */
  private val maxResultBytes: Int = EndiveLimits().maxCapabilityResultBytes,
) : EndiveCapabilityHost, AutoCloseable {
  /**
   * SQL handles held open for the life of this invocation.
   *
   * Opening a database and a connection per statement made an app's tenth query cost the same as
   * its first: a provisioning lookup and a fresh connection every time, plus a journal entry for
   * each. Holding them per invocation is both faster and a truer record — the app opened the
   * database once, so the journal says so once.
   */
  private val databases = linkedMapOf<String, SqlDatabase>()
  private val connections = linkedMapOf<String, wasmo.sql.SqlConnection>()
  private var closed = false

  override fun call(request: JsonElement): JsonElement = runBlocking {
    check(!closed) { "capability host is closed" }
    val envelope = request.jsonObject
    val capability = envelope.required("cap").jsonPrimitive.content
    val method = envelope.required("method").jsonPrimitive.content
    val body = envelope["req"] ?: JsonNull
    when ("$capability.$method") {
      "clock.now" -> JsonPrimitive(platform.clock.now().toEpochMilliseconds())
      "random.bytes" -> JsonPrimitive(
        randomSource.bytes(body.objectValue("length").jsonPrimitive.int).toByteString().base64(),
      )
      "http.fetch" -> platform.httpService.execute(body.toHttpRequest())
        .also { requireBinarySize("HTTP response body", it.body.size) }
        .toJson()
      "objectStore.put" -> platform.objectStore.put(body.toPutObjectRequest()).let {
        jsonObjectOf("etag" to JsonPrimitive(it.etag))
      }
      "objectStore.get" -> platform.objectStore.get(
        GetObjectRequest(body.objectValue("key").jsonPrimitive.content),
      ).also { requireBinarySize("object", it.value?.size ?: 0) }.toJson()
      "objectStore.delete" -> {
        platform.objectStore.delete(
          DeleteObjectRequest(body.objectValue("key").jsonPrimitive.content),
        )
        JsonNull
      }
      "objectStore.list" -> platform.objectStore.list(body.toListObjectsRequest()).toJson()
      "downloader.download" -> platform.downloader.download(
        TransferRequest(
          httpRequest = body.objectValue("httpRequest").toHttpRequest(),
          objectStoreKey = body.objectValue("objectStoreKey").jsonPrimitive.content,
        ),
      ).also { requireBinarySize("download response body", it.httpResponse.body.size) }
        .let { response ->
          jsonObjectOf(
            "etag" to response.etag.toJson(),
            "httpResponse" to response.httpResponse.toJson(),
          )
        }
      "jobs.enqueue" -> {
        val queue = platform.jobQueueFactory.get(body.stringOrDefault("queue"))
        queue.enqueue(
          body.requiredBase64("jobBase64").toByteString(),
          body.nullableLong("executeAtMillis")?.let(Instant::fromEpochMilliseconds),
        )
        JsonNull
      }
      "jobs.cancel" -> {
        platform.jobQueueFactory.get(body.stringOrDefault("queue"))
          .cancel(body.requiredBase64("jobBase64").toByteString())
        JsonNull
      }
      "sql.exec" -> sqlExec(body)
      "sql.query" -> sqlQuery(body)
      else -> throw UnsupportedOperationException(
        "Endive guest requested unsupported capability $capability.$method",
      )
    }
  }

  private suspend fun sqlExec(request: JsonElement): JsonElement = with(connection(request)) {
    JsonPrimitive(
      execute(request.objectValue("sql").jsonPrimitive.content) {
        applyBindings(request.arrayOrEmpty("bindings"))
      },
    )
  }

  /**
   * Reads a result set under a running byte budget.
   *
   * A row cap alone bounds nothing useful, because a row can be a megabyte. Measuring each row as
   * it is encoded and stopping at the ceiling means an oversized query fails after one row instead
   * of after ten thousand, and the host never holds more than the guest was ever allowed to see.
   */
  private suspend fun sqlQuery(request: JsonElement): JsonElement = with(connection(request)) {
    val columns = request.objectValue("columns").jsonArray.map { it.jsonPrimitive.content }
    val iterator = executeQuery(request.objectValue("sql").jsonPrimitive.content) {
      applyBindings(request.arrayOrEmpty("bindings"))
    }
    try {
      val rows = mutableListOf<JsonElement>()
      var bytes = 0L
      while (true) {
        val row = iterator.next() ?: break
        require(rows.size < MaxSqlRows) { "Endive SQL result exceeds $MaxSqlRows rows" }
        val encoded = JsonArray(columns.mapIndexed { index, type -> row.readColumn(index, type) })
        bytes += encoded.toString().length.toLong()
        require(bytes <= maxResultBytes) {
          "Endive SQL result exceeds $maxResultBytes bytes at row ${rows.size}"
        }
        rows.add(encoded)
      }
      jsonObjectOf("rows" to JsonArray(rows))
    } finally {
      iterator.close()
    }
  }

  /** Returns this invocation's connection to the named database, opening it the first time. */
  private suspend fun connection(request: JsonElement): wasmo.sql.SqlConnection {
    val name = request.stringOrDefault("database")
    connections[name]?.let { return it }
    val database = databases.getOrPut(name) { platform.sqlService.getOrCreate(name) }
    return database.newConnection().also { connections[name] = it }
  }

  private fun requireBinarySize(kind: String, size: Int) {
    // Base64 costs four bytes for every three, and the guest is charged for the encoded form.
    val encoded = (size.toLong() + 2) / 3 * 4
    require(encoded <= maxResultBytes) {
      "Endive $kind is $size bytes, which exceeds the $maxResultBytes byte capability result limit"
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    for (connection in connections.values) connection.close()
    for (database in databases.values) database.close()
    connections.clear()
    databases.clear()
  }

  private fun SqlBinder.applyBindings(bindings: JsonArray) {
    bindings.forEachIndexed { defaultIndex, element ->
      val binding = element.jsonObject
      val index = binding["index"]?.jsonPrimitive?.int ?: defaultIndex
      val type = binding.required("type").jsonPrimitive.content
      val value = binding["value"] ?: JsonNull
      when (type) {
        "bool" -> bindBool(index, value.nullable { jsonPrimitive.boolean })
        "s32" -> bindS32(index, value.nullable { jsonPrimitive.int })
        "s64" -> bindS64(index, value.nullable { jsonPrimitive.long })
        "f32" -> bindF32(index, value.nullable { jsonPrimitive.float })
        "f64" -> bindF64(index, value.nullable { jsonPrimitive.double })
        "instant" -> bindInstant(index, value.nullable { Instant.parse(jsonPrimitive.content) })
        "string" -> bindString(index, value.nullable { jsonPrimitive.content })
        "bytes" -> bindBytes(index, value.nullable {
          jsonPrimitive.content.decodeBase64() ?: error("invalid base64 SQL binding")
        })
        "uuid" -> bindUuid(index, value.nullable { Uuid.parse(jsonPrimitive.content) })
        "json" -> bindJson(index, value.nullable { JsonLiteral(toString()) })
        else -> error("unsupported Endive SQL binding type: $type")
      }
    }
  }

  private fun SqlRow.readColumn(index: Int, type: String): JsonElement = when (type) {
    "bool" -> getBool(index).toJson()
    "s32" -> getS32(index).toJson()
    "s64" -> getS64(index).toJson()
    "f32" -> getF32(index)?.toString().toJson()
    "f64" -> getF64(index)?.toString().toJson()
    "instant" -> getInstant(index)?.toString().toJson()
    "string" -> getString(index).toJson()
    "bytes" -> getBytes(index)?.base64().toJson()
    "uuid" -> getUuid(index)?.toString().toJson()
    "json" -> getJson(index)?.json?.let(Json::parseToJsonElement) ?: JsonNull
    else -> error("unsupported Endive SQL column type: $type")
  }

  private companion object {
    const val MaxSqlRows = 10_000
  }
}

private fun JsonElement.toHttpRequest(): HttpRequest = HttpRequest(
  method = stringOrDefault("method", "GET"),
  url = objectValue("url").jsonPrimitive.content,
  headers = arrayOrEmpty("headers").toHeaders(),
  body = when (val encoded = objectValueOrNull("bodyBase64")) {
    null, JsonNull -> null
    else -> encoded.jsonPrimitive.content.decodeBase64() ?: error("invalid HTTP body base64")
  },
)

private fun HttpResponse.toJson(): JsonObject = jsonObjectOf(
  "bodyBase64" to JsonPrimitive(body.base64()),
  "code" to JsonPrimitive(code),
  "headers" to headers.toJson(),
)

private fun JsonArray.toHeaders(): List<Header> = map { element ->
  Header(
    name = element.objectValue("name").jsonPrimitive.content,
    value = element.objectValue("value").jsonPrimitive.content,
  )
}

private fun List<Header>.toJson(): JsonArray = JsonArray(map { header ->
  jsonObjectOf(
    "name" to JsonPrimitive(header.name),
    "value" to JsonPrimitive(header.value),
  )
})

private fun JsonElement.toPutObjectRequest(): PutObjectRequest = PutObjectRequest(
  key = objectValue("key").jsonPrimitive.content,
  value = requiredBase64("valueBase64").toByteString(),
  contentType = nullableString("contentType"),
)

private fun GetObjectResponse.toJson(): JsonObject = jsonObjectOf(
  "contentType" to contentType.toJson(),
  "etag" to etag.toJson(),
  "valueBase64" to value?.base64().toJson(),
)

private fun JsonElement.toListObjectsRequest(): ListObjectsRequest = ListObjectsRequest(
  prefix = nullableString("prefix"),
  delimiter = nullableString("delimiter"),
  continuationToken = nullableString("continuationToken"),
)

private fun ListObjectsResponse.toJson(): JsonObject = jsonObjectOf(
  "entries" to JsonArray(entries.map { entry ->
    when (entry) {
      is ListObjectsResponse.CommonPrefix -> jsonObjectOf(
        "kind" to JsonPrimitive("prefix"),
        "prefix" to JsonPrimitive(entry.prefix),
      )
      is ListObjectsResponse.Object -> jsonObjectOf(
        "etag" to JsonPrimitive(entry.etag),
        "key" to JsonPrimitive(entry.key),
        "kind" to JsonPrimitive("object"),
        "size" to JsonPrimitive(entry.size),
      )
    }
  }),
  "nextRequest" to nextRequest?.let { request ->
    jsonObjectOf(
      "continuationToken" to request.continuationToken.toJson(),
      "delimiter" to request.delimiter.toJson(),
      "prefix" to request.prefix.toJson(),
    )
  }.orNull(),
)

private fun JsonElement.requiredBase64(name: String): ByteArray =
  objectValue(name).jsonPrimitive.content.decodeBase64()?.toByteArray()
    ?: error("invalid base64 property: $name")

private fun JsonElement.objectValue(name: String): JsonElement = jsonObject.required(name)

private fun JsonElement.objectValueOrNull(name: String): JsonElement? = jsonObject[name]

private fun JsonObject.required(name: String): JsonElement = this[name]
  ?: error("missing Endive capability property: $name")

private fun JsonElement.arrayOrEmpty(name: String): JsonArray =
  objectValueOrNull(name)?.jsonArray ?: JsonArray(emptyList())

private fun JsonElement.stringOrDefault(name: String, default: String = ""): String =
  nullableString(name) ?: default

private fun JsonElement.nullableString(name: String): String? =
  objectValueOrNull(name)?.let { if (it === JsonNull) null else it.jsonPrimitive.content }

private fun JsonElement.nullableLong(name: String): Long? =
  objectValueOrNull(name)?.let { if (it === JsonNull) null else it.jsonPrimitive.long }

private inline fun <T> JsonElement.nullable(block: JsonElement.() -> T): T? =
  if (this === JsonNull) null else block()

private fun Any?.toJson(): JsonElement = when (this) {
  null -> JsonNull
  is Boolean -> JsonPrimitive(this)
  is Number -> JsonPrimitive(this)
  is String -> JsonPrimitive(this)
  else -> JsonPrimitive(toString())
}

private fun JsonElement?.orNull(): JsonElement = this ?: JsonNull

private fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
  JsonObject(linkedMapOf(*entries))
