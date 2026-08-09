package com.wasmo.installedapps

import com.wasmo.api.InstalledAppSnapshot
import com.wasmo.framework.ContentTypeDatabase
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.Response
import com.wasmo.framework.ResponseBody
import com.wasmo.identifiers.AppSlug
import com.wasmo.packaging.AppManifest
import com.wasmo.packaging.CapabilityDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.app.FakePlatform
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.mediation.InvocationJson
import wasmo.mediation.InvocationVerification
import wasmo.mediation.verifyInvocation
import wasmo.objectstore.FakeObjectStore
import wasmo.sql.FakeSqlService

/**
 * The record the OS keeps is only worth keeping if the person it is about can reach it, so these
 * tests exercise the whole path: run an app, list what it did, export the record, check the export
 * offline, and replay it.
 */
class InvocationHttpServiceTest {
  @Test
  fun theOwnerCanListExportVerifyAndReplayWhatTheAppDid() = runTest {
    val fixture = Fixture()

    fixture.service.execute(fixture.owner, request("https://notes.test/action"))

    val listed = Json.parseToJsonElement(fixture.get("/.wasmo/invocations"))
      .jsonObject.getValue("invocations").jsonArray
    assertEquals(1, listed.size)
    val id = listed.single().jsonObject.getValue("id").jsonPrimitive.content
    assertEquals("notes", listed.single().jsonObject.getValue("appSlug").jsonPrimitive.content)

    // The export carries its own proof, and checking it needs nothing but the bytes.
    val exported = InvocationJson.decodeFromString(fixture.get("/.wasmo/invocations/$id"))
    assertEquals(id, exported.id)
    assertTrue(verifyInvocation(exported) is InvocationVerification.Valid)

    val report = Json.parseToJsonElement(fixture.post("/.wasmo/invocations/$id/replay")).jsonObject
    assertEquals("deterministic", report.getValue("outcome").jsonPrimitive.content)
    assertEquals("1", report.getValue("replayedCalls").jsonPrimitive.content)
    assertEquals("true", report.getValue("verification").jsonObject.getValue("valid").toString())
  }

  /** An app must not be able to serve, forge, or even see requests for its own audit record. */
  @Test
  fun theReservedPrefixNeverReachesTheApp() = runTest {
    val fixture = Fixture()

    assertFailsWith<NotFoundUserException> {
      fixture.service.execute(fixture.owner, request("https://notes.test/.wasmo/anything"))
    }

    assertEquals(0, fixture.appCalls)
  }

  @Test
  fun anAnonymousCallerSeesNothing() = runTest {
    val fixture = Fixture()
    fixture.service.execute(fixture.owner, request("https://notes.test/action"))

    assertFailsWith<NotFoundUserException> {
      fixture.service.execute(fixture.anonymous, request("https://notes.test/.wasmo/invocations"))
    }
  }

  @Test
  fun anInvocationBelongingToAnotherAppIsNotFound() = runTest {
    val fixture = Fixture()
    fixture.service.execute(fixture.owner, request("https://notes.test/action"))
    val stored = fixture.invocations.saved.single()
    fixture.invocations.saved[0] = stored.copy(appSlug = "passwords")

    assertFailsWith<NotFoundUserException> {
      fixture.get("/.wasmo/invocations/${stored.id}")
    }
  }

  /** A denial is part of the record, so it has to survive the round trip into the export too. */
  @Test
  fun aDeniedInvocationIsRecordedAndStillVerifies() = runTest {
    val fixture = Fixture(
      capabilities = listOf(CapabilityDeclaration(name = "object_store")),
    )

    runCatching { fixture.service.execute(fixture.owner, request("https://notes.test/action")) }

    val invocation = fixture.invocations.saved.single()
    assertEquals(listOf("cap.denied"), invocation.audit.map { it.kind }.filter { it != "invocation.start" && it != "invocation.failure" })
    assertTrue(verifyInvocation(invocation) is InvocationVerification.Valid)
  }

  private fun request(url: String) = Request(
    method = "POST",
    url = url.toHttpUrl(),
    body = "request".encodeUtf8(),
  )

  private class Fixture(
    capabilities: List<CapabilityDeclaration> = listOf(CapabilityDeclaration(name = "clock")),
  ) {
    val invocations = FakeCapabilityInvocationStore()
    var appCalls = 0

    private val platform = FakePlatform(FakeSqlService("invocation_http_test"))

    private val installedAppService = object : InstalledAppService {
      override val slug = AppSlug("notes")
      override val appManifestLoader = object : AppManifestLoader {
        override suspend fun load() = AppManifest(
          target = "wasmWasi",
          version = 9L,
          capability = capabilities,
        )
      }
      override val url = "https://notes.test/".toHttpUrl()
      override val httpService: InstalledAppHttpService
        get() = error("unused")
      override val platform: Platform = this@Fixture.platform

      override suspend fun app(platform: Platform): WasmoApp = object : WasmoApp() {
        override val httpService = object : HttpService {
          override suspend fun execute(request: HttpRequest): HttpResponse {
            appCalls += 1
            val now = platform.clock.now().toEpochMilliseconds()
            return HttpResponse(body = "$now:${request.body?.utf8()}".encodeUtf8())
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
      invocationStore = invocations,
      invocationReader = invocations,
      clock = platform.clock,
    )

    val owner = Caller(
      userId = 41L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "http-test",
      ip = "192.0.2.1",
    )

    val anonymous = owner.copy(userId = null, computerAccess = ComputerAccess.Anonymous)

    suspend fun get(path: String): String = service.execute(
      owner,
      Request(method = "GET", url = "https://notes.test$path".toHttpUrl()),
    ).text()

    suspend fun post(path: String): String = service.execute(
      owner,
      Request(method = "POST", url = "https://notes.test$path".toHttpUrl()),
    ).text()

    private fun Response<ResponseBody>.text(): String =
      Buffer().also { body.write(it) }.readUtf8()
  }
}
