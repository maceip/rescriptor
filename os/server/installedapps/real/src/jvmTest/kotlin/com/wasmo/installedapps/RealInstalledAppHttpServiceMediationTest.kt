package com.wasmo.installedapps

import com.wasmo.api.InstalledAppSnapshot
import com.wasmo.framework.ContentTypeDatabase
import com.wasmo.framework.Request
import com.wasmo.identifiers.AppSlug
import com.wasmo.packaging.AppManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.app.FakePlatform
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.MediatedPlatform
import wasmo.objectstore.FakeObjectStore
import wasmo.sql.FakeSqlService

class RealInstalledAppHttpServiceMediationTest {
  @Test
  fun authenticatedCallerOwnsFreshPersistedHttpSession() = runTest {
    val platform = FakePlatform(FakeSqlService("http_mediation_test"))
    val saved = mutableListOf<CapabilityInvocation>()
    var loadedPlatform: Platform? = null
    val installedAppService = object : InstalledAppService {
      override val slug = AppSlug("notes")
      override val appManifestLoader = object : AppManifestLoader {
        override suspend fun load() = AppManifest(target = "wasmWasi", version = 9L)
      }
      override val url = "https://notes.test/".toHttpUrl()
      override val httpService: InstalledAppHttpService
        get() = error("unused")
      override val platform: Platform = platform

      override suspend fun app(platform: Platform): WasmoApp {
        loadedPlatform = platform
        return object : WasmoApp() {
          override val httpService = object : HttpService {
            override suspend fun execute(request: HttpRequest): HttpResponse {
              val now = platform.clock.now().toEpochMilliseconds()
              return HttpResponse(body = "$now:${request.body?.utf8()}".encodeUtf8())
            }
          }
        }
      }

      override suspend fun homeUrl(): HttpUrl = error("unused")
      override suspend fun maskableIconUrl(): HttpUrl = error("unused")
      override suspend fun snapshot(): InstalledAppSnapshot = error("unused")
    }
    val service = RealInstalledAppHttpService(
      installedAppService = installedAppService,
      resourceLoaderFactory = object : ResourceLoader.Factory {
        override fun create() = object : ResourceLoader {
          override suspend fun loadOrNull(resourcePath: String) = null
        }
      },
      contentTypeDatabase = object : ContentTypeDatabase {
        override fun get(fileName: String) = null
      },
      objectStore = FakeObjectStore(),
      invocationStore = CapabilityInvocationStore(saved::add),
      clock = platform.clock,
    )
    val caller = Caller(
      userId = 41L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "http-test",
      ip = "192.0.2.1",
    )

    service.execute(
      caller = caller,
      request = Request(
        method = "POST",
        url = "https://notes.test/action".toHttpUrl(),
        body = "request".encodeUtf8(),
      ),
    )

    assertTrue(loadedPlatform is MediatedPlatform)
    val invocation = saved.single()
    assertEquals("notes", invocation.appSlug)
    assertEquals(9L, invocation.appVersion)
    assertTrue(invocation.callerJson.contains("\"userId\":41"))
    assertEquals(listOf("clock.now:null"), invocation.journal.map { it.key })
  }
}
