@file:OptIn(ExperimentalUuidApi::class)

package wasmo.mediation

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import wasmo.app.Platform
import wasmo.downloader.Downloader
import wasmo.downloader.TransferRequest
import wasmo.downloader.TransferResponse
import wasmo.http.Header
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
import wasmo.json.JsonLiteral
import wasmo.objectstore.DeleteObjectRequest
import wasmo.objectstore.DeleteObjectResponse
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.GetObjectResponse
import wasmo.objectstore.ListObjectsRequest
import wasmo.objectstore.ListObjectsResponse
import wasmo.objectstore.ObjectStore
import wasmo.objectstore.PutObjectRequest
import wasmo.objectstore.PutObjectResponse
import wasmo.sql.RowIterator
import wasmo.sql.SqlBinder
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlRow
import wasmo.sql.SqlService

/** A transparent [Platform] whose effects are mediated by a single invocation [session]. */
class MediatedPlatform(
  delegate: Platform,
  val session: CapabilitySession,
) : Platform {
  override val clock: Clock = MediatedClock(delegate.clock, session)
  override val httpService: HttpService = MediatedHttpService(delegate.httpService, session)
  override val objectStore: ObjectStore = MediatedObjectStore(delegate.objectStore, session)
  override val downloader: Downloader = MediatedDownloader(delegate.downloader, session)
  override val sqlService: SqlService = MediatedSqlService(delegate.sqlService, session)
  override val jobQueueFactory: JobQueue.Factory =
    MediatedJobQueueFactory(delegate.jobQueueFactory, session)
}

private class MediatedClock(
  private val delegate: Clock,
  private val session: CapabilitySession,
) : Clock {
  override fun now(): Instant {
    val result = session.callSync("clock", "now", JsonNull) {
      JsonPrimitive(delegate.now().toEpochMilliseconds())
    }
    return Instant.fromEpochMilliseconds((result as JsonPrimitive).content.toLong())
  }
}

private class MediatedHttpService(
  private val delegate: HttpService,
  private val session: CapabilitySession,
) : HttpService {
  override suspend fun execute(request: HttpRequest): HttpResponse {
    val result = session.call("http", "fetch", request.toJson()) {
      delegate.execute(request).toJson()
    }
    return result.toHttpResponse()
  }
}

private class MediatedObjectStore(
  private val delegate: ObjectStore,
  private val session: CapabilitySession,
) : ObjectStore {
  override suspend fun put(request: PutObjectRequest): PutObjectResponse {
    val result = session.call("objectStore", "put", request.toJson()) {
      delegate.put(request).toJson()
    }
    return PutObjectResponse(etag = result.objectValue("etag").string())
  }

  override suspend fun get(request: GetObjectRequest): GetObjectResponse {
    val result = session.call("objectStore", "get", request.toJson()) {
      delegate.get(request).toJson()
    }
    return result.toGetObjectResponse()
  }

  override suspend fun delete(request: DeleteObjectRequest): DeleteObjectResponse {
    session.call("objectStore", "delete", request.toJson()) {
      delegate.delete(request)
      JsonNull
    }
    return DeleteObjectResponse
  }

  override suspend fun list(request: ListObjectsRequest): ListObjectsResponse {
    val result = session.call("objectStore", "list", request.toJson()) {
      delegate.list(request).toJson()
    }
    return result.toListObjectsResponse()
  }
}

private class MediatedDownloader(
  private val delegate: Downloader,
  private val session: CapabilitySession,
) : Downloader {
  override suspend fun download(transferRequest: TransferRequest): TransferResponse {
    val result = session.call("downloader", "download", transferRequest.toJson()) {
      delegate.download(transferRequest).toJson()
    }
    return result.toTransferResponse()
  }
}

private class MediatedJobQueueFactory(
  private val delegate: JobQueue.Factory,
  private val session: CapabilitySession,
) : JobQueue.Factory {
  override fun get(name: String): JobQueue = MediatedJobQueue(
    delegate = if (session.journal.isReplay) null else delegate.get(name),
    session = session,
    name = name,
  )
}

private class MediatedJobQueue(
  private val delegate: JobQueue?,
  private val session: CapabilitySession,
  private val name: String,
) : JobQueue {
  override suspend fun enqueue(job: ByteString, executeAt: Instant?) {
    val request = jsonObjectOf(
      "executeAtMillis" to executeAt?.toEpochMilliseconds().toJson(),
      "job" to JsonPrimitive(job.base64()),
      "queue" to JsonPrimitive(name),
    )
    session.call("jobs", "enqueue", request) {
      delegate!!.enqueue(job, executeAt)
      JsonNull
    }
  }

  override suspend fun cancel(job: ByteString) {
    val request = jsonObjectOf(
      "job" to JsonPrimitive(job.base64()),
      "queue" to JsonPrimitive(name),
    )
    session.call("jobs", "cancel", request) {
      delegate!!.cancel(job)
      JsonNull
    }
  }
}

private class MediatedSqlService(
  private val delegate: SqlService,
  private val session: CapabilitySession,
) : SqlService {
  override suspend fun getOrCreate(name: String): SqlDatabase {
    var database: SqlDatabase? = null
    session.call(
      capability = "sql",
      method = "getOrCreate",
      request = jsonObjectOf("name" to JsonPrimitive(name)),
    ) {
      database = delegate.getOrCreate(name)
      JsonNull
    }
    return MediatedSqlDatabase(
      delegate = database,
      session = session,
      scope = "$name@${session.journal.position - 1}",
    )
  }

  override fun close() {
    if (!session.journal.isReplay) delegate.close()
  }
}

private class MediatedSqlDatabase(
  private val delegate: SqlDatabase?,
  private val session: CapabilitySession,
  private val scope: String,
) : SqlDatabase {
  private var connectionCount = 0

  override suspend fun newConnection(): SqlConnection {
    var connection: SqlConnection? = null
    val connectionId = connectionCount++
    session.call(
      capability = "sql",
      method = "newConnection",
      request = jsonObjectOf(
        "connection" to JsonPrimitive(connectionId),
        "database" to JsonPrimitive(scope),
      ),
    ) {
      connection = delegate!!.newConnection()
      JsonNull
    }
    return MediatedSqlConnection(
      delegate = connection,
      session = session,
      scope = "$scope/$connectionId",
    )
  }

  override fun close() {
    delegate?.close()
  }
}

private class MediatedSqlConnection(
  private val delegate: SqlConnection?,
  private val session: CapabilitySession,
  private val scope: String,
) : SqlConnection {
  private var queryCount = 0

  override suspend fun execute(
    sql: String,
    bindParameters: (SqlBinder.() -> Unit)?,
  ): Long {
    val bindings = CapturingSqlBinder().also { bindParameters?.invoke(it) }
    val request = sqlRequest(sql, bindings)
    val result = session.call("sql", "exec", request) {
      JsonPrimitive(delegate!!.execute(sql) { bindings.applyTo(this) })
    }
    return (result as JsonPrimitive).content.toLong()
  }

  override suspend fun executeQuery(
    sql: String,
    bindParameters: (SqlBinder.() -> Unit)?,
  ): RowIterator {
    val bindings = CapturingSqlBinder().also { bindParameters?.invoke(it) }
    var iterator: RowIterator? = null
    val queryId = queryCount++
    val request = sqlRequest(sql, bindings)
    session.call("sql", "query", request) {
      iterator = delegate!!.executeQuery(sql) { bindings.applyTo(this) }
      JsonNull
    }
    return MediatedRowIterator(
      delegate = iterator,
      session = session,
      scope = "$scope/$queryId",
    )
  }

  private fun sqlRequest(sql: String, bindings: CapturingSqlBinder): JsonObject = jsonObjectOf(
    "bindings" to bindings.toJson(),
    "connection" to JsonPrimitive(scope),
    "sql" to JsonPrimitive(sql),
  )

  override fun close() {
    delegate?.close()
  }
}

private class MediatedRowIterator(
  private val delegate: RowIterator?,
  private val session: CapabilitySession,
  private val scope: String,
) : RowIterator {
  private var rowIndex = 0

  override suspend fun next(): SqlRow? {
    var row: SqlRow? = null
    val request = jsonObjectOf(
      "query" to JsonPrimitive(scope),
      "row" to JsonPrimitive(rowIndex),
    )
    val result = session.call("sql.row", "next", request) {
      row = delegate!!.next()
      JsonPrimitive(row != null)
    }
    if (!(result as JsonPrimitive).content.toBooleanStrict()) return null
    return MediatedSqlRow(
      delegate = row,
      session = session,
      scope = "$scope/${rowIndex++}",
    )
  }

  override fun close() {
    delegate?.close()
  }
}

private class MediatedSqlRow(
  private val delegate: SqlRow?,
  private val session: CapabilitySession,
  private val scope: String,
) : SqlRow {
  override fun getBool(index: Int): Boolean? = get("getBool", index, { it.toJson() }) {
    booleanOrNull()
  }

  override fun getS32(index: Int): Int? = get("getS32", index, { it?.let(::JsonPrimitive) ?: JsonNull }) {
    longOrNull()?.toInt()
  }

  override fun getS64(index: Int): Long? = get("getS64", index, { it.toJson() }) {
    longOrNull()
  }

  override fun getF32(index: Int): Float? = get("getF32", index, ::floatingPointToJson) {
    stringOrNull()?.toFloat()
  }

  override fun getF64(index: Int): Double? = get("getF64", index, ::floatingPointToJson) {
    stringOrNull()?.toDouble()
  }

  override fun getInstant(index: Int): Instant? = get("getInstant", index, {
    it?.toString().toJson()
  }) {
    stringOrNull()?.let(Instant::parse)
  }

  override fun getString(index: Int): String? = get("getString", index, { it.toJson() }) {
    stringOrNull()
  }

  override fun getBytes(index: Int): ByteString? = get("getBytes", index, {
    it?.base64().toJson()
  }) {
    stringOrNull()?.decodeBase64() ?: if (this === JsonNull) null else error("invalid base64")
  }

  override fun getUuid(index: Int): Uuid? = get("getUuid", index, {
    it?.toString().toJson()
  }) {
    stringOrNull()?.let(Uuid::parse)
  }

  override fun getJson(index: Int): JsonLiteral? = get("getJson", index, {
    it?.json.toJson()
  }) {
    stringOrNull()?.let(::JsonLiteral)
  }

  private fun <T> get(
    method: String,
    index: Int,
    encode: (T) -> JsonElement,
    decode: JsonElement.() -> T,
  ): T {
    val request = jsonObjectOf(
      "column" to JsonPrimitive(index),
      "row" to JsonPrimitive(scope),
    )
    val result = session.callSync("sql.row", method, request) {
      @Suppress("UNCHECKED_CAST")
      val value: T = when (method) {
        "getBool" -> delegate!!.getBool(index)
        "getS32" -> delegate!!.getS32(index)
        "getS64" -> delegate!!.getS64(index)
        "getF32" -> delegate!!.getF32(index)
        "getF64" -> delegate!!.getF64(index)
        "getInstant" -> delegate!!.getInstant(index)
        "getString" -> delegate!!.getString(index)
        "getBytes" -> delegate!!.getBytes(index)
        "getUuid" -> delegate!!.getUuid(index)
        "getJson" -> delegate!!.getJson(index)
        else -> error("unsupported SQL row method: $method")
      } as T
      encode(value)
    }
    return decode(result)
  }
}

private class CapturingSqlBinder : SqlBinder {
  private val bindings = mutableListOf<SqlBinding>()

  override fun bindBool(index: Int, value: Boolean?) = add(index, "bool", value, value.toJson())
  override fun bindS32(index: Int, value: Int?) =
    add(index, "s32", value, value?.let(::JsonPrimitive) ?: JsonNull)
  override fun bindS64(index: Int, value: Long?) = add(index, "s64", value, value.toJson())
  override fun bindF32(index: Int, value: Float?) = add(index, "f32", value, floatingPointToJson(value))
  override fun bindF64(index: Int, value: Double?) = add(index, "f64", value, floatingPointToJson(value))
  override fun bindInstant(index: Int, value: Instant?) =
    add(index, "instant", value, value?.toString().toJson())
  override fun bindString(index: Int, value: String?) = add(index, "string", value, value.toJson())
  override fun bindBytes(index: Int, value: ByteString?) =
    add(index, "bytes", value, value?.base64().toJson())
  override fun bindUuid(index: Int, value: Uuid?) =
    add(index, "uuid", value, value?.toString().toJson())
  override fun bindJson(index: Int, value: JsonLiteral?) =
    add(index, "json", value, value?.json.toJson())

  private fun add(index: Int, type: String, value: Any?, json: JsonElement) {
    bindings += SqlBinding(index, type, value, json)
  }

  fun toJson(): JsonArray = JsonArray(bindings.map {
    jsonObjectOf(
      "index" to JsonPrimitive(it.index),
      "type" to JsonPrimitive(it.type),
      "value" to it.json,
    )
  })

  fun applyTo(binder: SqlBinder) {
    for (binding in bindings) with(binder) {
      @Suppress("UNCHECKED_CAST")
      when (binding.type) {
        "bool" -> bindBool(binding.index, binding.value as Boolean?)
        "s32" -> bindS32(binding.index, binding.value as Int?)
        "s64" -> bindS64(binding.index, binding.value as Long?)
        "f32" -> bindF32(binding.index, binding.value as Float?)
        "f64" -> bindF64(binding.index, binding.value as Double?)
        "instant" -> bindInstant(binding.index, binding.value as Instant?)
        "string" -> bindString(binding.index, binding.value as String?)
        "bytes" -> bindBytes(binding.index, binding.value as ByteString?)
        "uuid" -> bindUuid(binding.index, binding.value as Uuid?)
        "json" -> bindJson(binding.index, binding.value as JsonLiteral?)
        else -> error("unsupported SQL binding: ${binding.type}")
      }
    }
  }
}

private data class SqlBinding(
  val index: Int,
  val type: String,
  val value: Any?,
  val json: JsonElement,
)

private fun floatingPointToJson(value: Number?): JsonElement = value?.toString().toJson()

private fun HttpRequest.toJson() = jsonObjectOf(
  "body" to body?.base64().toJson(),
  "headers" to headers.toJson(),
  "method" to JsonPrimitive(method),
  "url" to JsonPrimitive(url),
)

private fun HttpResponse.toJson() = jsonObjectOf(
  "body" to JsonPrimitive(body.base64()),
  "code" to JsonPrimitive(code),
  "headers" to headers.toJson(),
)

private fun JsonElement.toHttpResponse(): HttpResponse = HttpResponse(
  code = objectValue("code").int(),
  headers = objectValue("headers").toHeaders(),
  body = objectValue("body").string().decodeBase64() ?: error("invalid HTTP body base64"),
)

private fun List<Header>.toJson(): JsonArray = JsonArray(map {
  jsonObjectOf(
    "name" to JsonPrimitive(it.name),
    "value" to JsonPrimitive(it.value),
  )
})

private fun JsonElement.toHeaders(): List<Header> = (this as JsonArray).map {
  Header(
    name = it.objectValue("name").string(),
    value = it.objectValue("value").string(),
  )
}

private fun PutObjectRequest.toJson() = jsonObjectOf(
  "contentType" to contentType.toJson(),
  "key" to JsonPrimitive(key),
  "value" to JsonPrimitive(value.base64()),
)

private fun PutObjectResponse.toJson() = jsonObjectOf("etag" to JsonPrimitive(etag))

private fun GetObjectRequest.toJson() = jsonObjectOf("key" to JsonPrimitive(key))

private fun GetObjectResponse.toJson() = jsonObjectOf(
  "contentType" to contentType.toJson(),
  "etag" to etag.toJson(),
  "value" to value?.base64().toJson(),
)

private fun JsonElement.toGetObjectResponse() = GetObjectResponse(
  value = objectValue("value").stringOrNull()?.decodeBase64(),
  etag = objectValue("etag").stringOrNull(),
  contentType = objectValue("contentType").stringOrNull(),
)

private fun DeleteObjectRequest.toJson() = jsonObjectOf("key" to JsonPrimitive(key))

private fun ListObjectsRequest.toJson() = jsonObjectOf(
  "continuationToken" to continuationToken.toJson(),
  "delimiter" to delimiter.toJson(),
  "prefix" to prefix.toJson(),
)

private fun ListObjectsResponse.toJson() = jsonObjectOf(
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
  "nextRequest" to nextRequest?.toJson().orNull(),
)

private fun JsonElement.toListObjectsResponse(): ListObjectsResponse = ListObjectsResponse(
  entries = (objectValue("entries") as JsonArray).map { entry ->
    when (entry.objectValue("kind").string()) {
      "prefix" -> ListObjectsResponse.CommonPrefix(
        prefix = entry.objectValue("prefix").string(),
      )
      "object" -> ListObjectsResponse.Object(
        key = entry.objectValue("key").string(),
        etag = entry.objectValue("etag").string(),
        size = entry.objectValue("size").long(),
      )
      else -> error("unknown object-store entry")
    }
  },
  nextRequest = objectValue("nextRequest").let {
    if (it === JsonNull) null else ListObjectsRequest(
      prefix = it.objectValue("prefix").stringOrNull(),
      delimiter = it.objectValue("delimiter").stringOrNull(),
      continuationToken = it.objectValue("continuationToken").stringOrNull(),
    )
  },
)

private fun TransferRequest.toJson() = jsonObjectOf(
  "httpRequest" to httpRequest.toJson(),
  "objectStoreKey" to JsonPrimitive(objectStoreKey),
)

private fun TransferResponse.toJson() = jsonObjectOf(
  "etag" to etag.toJson(),
  "httpResponse" to httpResponse.toJson(),
)

private fun JsonElement.toTransferResponse() = TransferResponse(
  httpResponse = objectValue("httpResponse").toHttpResponse(),
  etag = objectValue("etag").stringOrNull(),
)

private fun JsonElement?.orNull(): JsonElement = this ?: JsonNull

private fun JsonElement.objectValue(name: String): JsonElement = (this as JsonObject)[name]
  ?: error("missing JSON property: $name")

private fun JsonElement.string(): String = (this as JsonPrimitive).content

private fun JsonElement.int(): Int = string().toInt()

private fun JsonElement.long(): Long = string().toLong()
