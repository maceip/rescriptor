package com.wasmo.wasm.endive

import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.OsScope
import com.wasmo.wasm.AppLoader
import com.wasmo.wasm.JvmAppLoader
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.Header
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.jobs.JobHandler

/** Uses Endive for every installed app that contains `app.wasm`; JVM factories are legacy-only. */
@Inject
@SingleIn(OsScope::class)
class EndiveAppLoader(
  private val legacyJvmLoader: JvmAppLoader,
  private val moduleCache: EndiveModuleCache,
) : AppLoader {
  override suspend fun load(
    platform: Platform,
    appSlug: AppSlug,
    wasm: ByteString?,
  ): WasmoApp? {
    if (wasm == null) return legacyJvmLoader.load(platform, appSlug, wasm = null)
    val randomSource = JournaledRandomSource(platform)
    val capabilityHost = PlatformCapabilityHost(platform, randomSource)
    val runtime = try {
      EndiveRuntime(
        module = moduleCache.get(wasm),
        capabilityHost = capabilityHost,
        randomBytes = randomSource::bytes,
      )
    } catch (failure: Throwable) {
      capabilityHost.close()
      throw failure
    }
    return EndiveWasmoApp(
      appSlug = appSlug,
      runtime = runtime,
      capabilityHost = capabilityHost,
    )
  }
}

class EndiveWasmoApp internal constructor(
  private val appSlug: AppSlug,
  private val runtime: EndiveRuntime,
  private val capabilityHost: PlatformCapabilityHost,
) : WasmoApp(), AutoCloseable {
  override val httpService: HttpService = object : HttpService {
    override suspend fun execute(request: HttpRequest): HttpResponse {
      val response = Json.parseToJsonElement(
        runtime.invoke(HttpExport, request.toGuestJson().toString()),
      ).jsonObject
      return HttpResponse(
        code = response.required("code").jsonPrimitive.int,
        headers = response.required("headers").jsonArray.map { header ->
          Header(
            name = header.jsonObject.required("name").jsonPrimitive.content,
            value = header.jsonObject.required("value").jsonPrimitive.content,
          )
        },
        body = response.required("body").jsonPrimitive.content.encodeUtf8(),
      )
    }
  }

  override val jobHandlerFactory: JobHandler.Factory? =
    if (runtime.hasFunctionExport(JobExport)) {
      object : JobHandler.Factory {
        override fun get(queueName: String): JobHandler = object : JobHandler {
          override suspend fun handle(job: ByteString) {
            runtime.invoke(
              JobExport,
              JsonObject(
                linkedMapOf(
                  "dataBase64" to JsonPrimitive(job.base64()),
                  "queue" to JsonPrimitive(queueName),
                ),
              ).toString(),
            )
          }
        }
      }
    } else {
      null
    }

  /** The runtime is fenced before its SQL handles close, so a timed-out guest cannot race them. */
  override fun close() {
    try {
      runtime.close()
    } finally {
      capabilityHost.close()
    }
  }

  override fun toString(): String = "EndiveWasmoApp($appSlug)"

  private companion object {
    const val HttpExport = "wasmo_http"
    const val JobExport = "wasmo_job"
  }
}

private fun HttpRequest.toGuestJson(): JsonObject = JsonObject(
  linkedMapOf(
    "bodyBase64" to (body?.base64()?.let(::JsonPrimitive) ?: JsonNull),
    "headers" to JsonArray(headers.map { header ->
      JsonObject(
        linkedMapOf(
          "name" to JsonPrimitive(header.name),
          "value" to JsonPrimitive(header.value),
        ),
      )
    }),
    "method" to JsonPrimitive(method),
    "url" to JsonPrimitive(url),
  ),
)

private fun JsonObject.required(name: String): JsonElement = this[name]
  ?: error("Endive app response is missing '$name'")
