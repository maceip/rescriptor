package com.wasmo.installedapps

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import wasmo.app.Platform
import wasmo.http.Header
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.mediation.InvocationExecutor

/**
 * The canonical encoding of what goes into and comes out of an app invocation.
 *
 * Recording and replay have to agree here exactly. If a replay reconstructed a request even
 * slightly differently — a header dropped, a null body encoded as an empty one — the app would ask
 * the journal for something it never recorded and a correct app would be reported as divergent.
 * Keeping both directions in one object is what makes that agreement checkable rather than hoped
 * for.
 */
internal object InvocationPayloads {
  fun encode(request: HttpRequest): JsonObject = JsonObject(
    linkedMapOf(
      "bodyBase64" to (request.body?.base64()?.let(::JsonPrimitive) ?: JsonNull),
      "headers" to request.headers.encode(),
      "method" to JsonPrimitive(request.method),
      "url" to JsonPrimitive(request.url),
    ),
  )

  fun decodeHttpRequest(input: JsonElement): HttpRequest = HttpRequest(
    method = input.string("method"),
    url = input.string("url"),
    headers = input.jsonObject.getValue("headers").jsonArray.decodeHeaders(),
    body = input.base64OrNull("bodyBase64"),
  )

  fun encode(response: HttpResponse): JsonObject = JsonObject(
    linkedMapOf(
      "bodyBase64" to JsonPrimitive(response.body.base64()),
      "code" to JsonPrimitive(response.code),
      "headers" to response.headers.encode(),
    ),
  )

  fun encodeJob(
    job: ApplicationJob,
    jobId: String,
  ): JsonObject = JsonObject(
    linkedMapOf(
      "dataBase64" to JsonPrimitive(job.data.base64()),
      "executeAtMillis" to (
        job.executeAt?.toEpochMilliseconds()?.let(::JsonPrimitive) ?: JsonNull
        ),
      "jobId" to JsonPrimitive(jobId),
      "queue" to JsonPrimitive(job.queueName),
    ),
  )

  fun decodeJob(input: JsonElement): DecodedJob = DecodedJob(
    queueName = input.string("queue"),
    data = input.base64OrNull("dataBase64") ?: ByteString.EMPTY,
    executeAt = (input.jsonObject["executeAtMillis"]?.takeIf { it !== JsonNull })
      ?.jsonPrimitive?.content?.toLong()?.let(Instant::fromEpochMilliseconds),
  )

  data class DecodedJob(
    val queueName: String,
    val data: ByteString,
    val executeAt: Instant?,
  )

  private fun List<Header>.encode(): JsonArray = JsonArray(
    map { header ->
      JsonObject(
        linkedMapOf(
          "name" to JsonPrimitive(header.name),
          "value" to JsonPrimitive(header.value),
        ),
      )
    },
  )

  private fun JsonArray.decodeHeaders(): List<Header> = map { element ->
    Header(
      name = element.string("name"),
      value = element.string("value"),
    )
  }

  private fun JsonElement.string(name: String): String =
    jsonObject.getValue(name).jsonPrimitive.content

  private fun JsonElement.base64OrNull(name: String): ByteString? =
    jsonObject[name]?.takeIf { it !== JsonNull }?.jsonPrimitive?.content
      ?.let { it.decodeBase64() ?: error("invalid base64 in invocation input: $name") }
}

/**
 * Re-runs a recorded HTTP invocation of [service] on a platform the replayer controls.
 *
 * A missing app or missing HTTP service encodes as JSON null, exactly as the original recording
 * did, so an app that has since been uninstalled diverges rather than silently passing.
 */
internal fun httpInvocationExecutor(service: InstalledAppService) =
  InvocationExecutor { platform: Platform, input: JsonElement ->
    val request = InvocationPayloads.decodeHttpRequest(input)
    val app = service.app(platform) ?: return@InvocationExecutor JsonNull
    try {
      val httpService = app.httpService ?: return@InvocationExecutor JsonNull
      InvocationPayloads.encode(httpService.execute(request))
    } finally {
      (app as? AutoCloseable)?.close()
    }
  }

/** Re-runs a recorded job invocation of [service]. Job handlers have no output to compare. */
internal fun jobInvocationExecutor(service: InstalledAppService) =
  InvocationExecutor { platform: Platform, input: JsonElement ->
    val job = InvocationPayloads.decodeJob(input)
    val app = service.app(platform) ?: return@InvocationExecutor JsonNull
    try {
      val factory = app.jobHandlerFactory ?: return@InvocationExecutor JsonNull
      factory.get(job.queueName).handle(job.data)
      JsonNull
    } finally {
      (app as? AutoCloseable)?.close()
    }
  }
