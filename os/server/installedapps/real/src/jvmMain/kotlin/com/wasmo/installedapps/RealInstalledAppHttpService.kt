package com.wasmo.installedapps

import com.wasmo.framework.ContentTypeDatabase
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.Response
import com.wasmo.framework.ResponseBody
import com.wasmo.identifiers.ForInstalledApp
import com.wasmo.identifiers.InstalledAppScope
import com.wasmo.packaging.AppManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.serialization.json.JsonNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.http.Header as PlatformHeader
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.mediation.CapabilityInvocationReader
import wasmo.mediation.CapabilityInvocationRecorder
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.InvocationKind
import wasmo.mediation.InvocationReplayer
import wasmo.mediation.MediatedPlatform
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.ObjectStore

/**
 * Attempts to satisfy an HTTP request according to our routing precedence rules.
 */
@Inject
@SingleIn(InstalledAppScope::class)
class RealInstalledAppHttpService(
  private val installedAppService: InstalledAppService,
  private val resourceLoaderFactory: ResourceLoader.Factory,
  private val contentTypeDatabase: ContentTypeDatabase,
  @ForInstalledApp private val objectStore: ObjectStore,
  invocationStore: CapabilityInvocationStore,
  invocationReader: CapabilityInvocationReader,
  clock: Clock,
) : InstalledAppHttpService {
  private val invocationRecorder = CapabilityInvocationRecorder(invocationStore, clock)

  private val invocationHttpService = InvocationHttpService(
    reader = invocationReader,
    replayer = InvocationReplayer(invocationReader),
    appSlug = installedAppService.slug.value,
    executorFactory = { kind ->
      when (kind) {
        InvocationKind.Http -> httpInvocationExecutor(installedAppService)
        InvocationKind.Job -> jobInvocationExecutor(installedAppService)
      }
    },
  )

  override suspend fun execute(
    caller: Caller,
    request: Request,
  ): Response<ResponseBody> {
    val encodedUrlPath = request.url.encodedPath

    // The OS answers its own reserved paths before the app sees anything, so an app can neither
    // serve nor observe requests for its own audit record.
    if (encodedUrlPath.startsWith(InvocationHttpService.ReservedPathPrefix)) {
      return invocationHttpService.execute(caller, request, encodedUrlPath)
    }

    val urlPath = when {
      encodedUrlPath.endsWith("/") -> "${encodedUrlPath}index.html"
      else -> encodedUrlPath
    }

    val publicObject = loadObjectOrNull(urlPath, "www-public")
    if (publicObject != null) return publicObject

    val publicResource = loadResourceOrNull(urlPath, "www-public")
    if (publicResource != null) return publicResource

    if (caller.computerAccess == ComputerAccess.Owner) {
      val privateObject = loadObjectOrNull(urlPath, "www")
      if (privateObject != null) return privateObject

      val privateResource = loadResourceOrNull(urlPath, "www")
      if (privateResource != null) return privateResource

      // TODO: pass the computerAccess to the HTTP service.
      val callHttpServiceResponse = callHttpService(caller, request)
      if (callHttpServiceResponse != null) return callHttpServiceResponse
    }

    throw NotFoundUserException()
  }

  private suspend fun loadResourceOrNull(
    urlPath: String,
    prefix: String,
  ): Response<ResponseBody>? {
    val resourceLoader = resourceLoaderFactory.create()
    val privateResource = resourceLoader.loadOrNull("/$prefix$urlPath")
      ?: return null

    return Response(
      headers = listOf(),
      contentType = contentTypeDatabase[urlPath],
      body = ResponseBody {
        it.write(privateResource)
      },
    )
  }

  private suspend fun loadObjectOrNull(
    urlPath: String,
    prefix: String,
  ): Response<ResponseBody>? {
    val response = objectStore.get(
      GetObjectRequest(key = "$prefix$urlPath"),
    )
    val value = response.value ?: return null
    return Response(
      contentType = response.contentType?.toMediaTypeOrNull()
        ?: contentTypeDatabase[urlPath],
      body = ResponseBody {
        it.write(value)
      },
    )
  }

  private suspend fun callHttpService(
    caller: Caller,
    request: Request,
  ): Response<ResponseBody>? {
    val applicationRequest = request.toApplicationHttpRequest()
    val manifest: AppManifest = installedAppService.appManifestLoader.load()
    val httpResponse = invocationRecorder.record(
      kind = InvocationKind.Http,
      appSlug = installedAppService.slug.value,
      appVersion = manifest.version,
      caller = caller,
      input = InvocationPayloads.encode(applicationRequest),
      encodeOutput = { it?.let(InvocationPayloads::encode) ?: JsonNull },
      policy = manifest.capabilityPolicy(),
    ) { session ->
      val app = installedAppService.app(
        MediatedPlatform(installedAppService.platform, session),
      ) ?: return@record null
      try {
        val httpService = app.httpService ?: return@record null
        httpService.execute(applicationRequest)
      } finally {
        (app as? AutoCloseable)?.close()
      }
    } ?: return null
    return httpResponse.toOsHttpResponse()
  }

  private fun Request.toApplicationHttpRequest() = HttpRequest(
    method = method,
    url = url.toString(),
    headers = headers.map { PlatformHeader(it.name, it.value) },
    body = body,
  )

  private fun HttpResponse.toOsHttpResponse(): Response<ResponseBody> = Response(
    status = this.code,
    headers = this.headers,
    contentType = this.contentType?.toMediaTypeOrNull(),
    body = ResponseBody { sink -> sink.write(body) },
  )
}
