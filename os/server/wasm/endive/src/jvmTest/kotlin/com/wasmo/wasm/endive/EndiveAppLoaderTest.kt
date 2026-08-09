package com.wasmo.wasm.endive

import com.wasmo.identifiers.AppSlug
import com.wasmo.wasm.JvmAppLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import run.endive.runtime.Instance
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.downloader.Downloader
import wasmo.downloader.TransferRequest
import wasmo.downloader.TransferResponse
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
import wasmo.mediation.CapabilitySession
import wasmo.mediation.MediatedPlatform
import wasmo.objectstore.DeleteObjectRequest
import wasmo.objectstore.DeleteObjectResponse
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.GetObjectResponse
import wasmo.objectstore.ListObjectsRequest
import wasmo.objectstore.ListObjectsResponse
import wasmo.objectstore.ObjectStore
import wasmo.objectstore.PutObjectRequest
import wasmo.objectstore.PutObjectResponse
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlService

class EndiveAppLoaderTest {
  @Test
  fun wasmPayloadNeverFallsBackToLegacyJvmFactory() = runTest {
    var legacyCreates = 0
    val legacyFactory = WasmoApp.Factory {
      legacyCreates += 1
      object : WasmoApp() {}
    }
    val loader = EndiveAppLoader(JvmAppLoader(mapOf(AppSlug to legacyFactory)))

    assertFails {
      loader.load(ClockOnlyPlatform(), AppSlug, "not a wasm module".encodeUtf8())
    }
    assertEquals(0, legacyCreates)

    assertNotNull(loader.load(ClockOnlyPlatform(), AppSlug, wasm = null))
    assertEquals(1, legacyCreates)
  }

  @Test
  fun kotlinWasmProbeRunsInSourceBuiltEndiveAndReplaysItsCapabilityCall() = runTest {
    val sourceJar = Instance::class.java.protectionDomain.codeSource.location.toExternalForm()
    assertTrue(
      sourceJar.endsWith("/third_party/endive/runtime/target/runtime-999-SNAPSHOT.jar"),
      "Endive runtime must come from the pinned source build, but was $sourceJar",
    )

    val wasm = Files.readAllBytes(Path.of(assertNotNull(System.getProperty("wasmo.endive.probe"))))
      .toByteString()
    val loader = EndiveAppLoader(JvmAppLoader(emptyMap()))
    val live = ClockOnlyPlatform()
    val recording = CapabilitySession.recording("endive-record", Caller)
    val recordedApp = assertNotNull(
      loader.load(MediatedPlatform(live, recording), AppSlug, wasm),
    )

    val recorded = recordedApp.httpService!!.execute(
      HttpRequest(url = "https://endive-probe.test/request"),
    )

    assertEquals(200, recorded.code)
    assertEquals("endive-alpha", recorded.headers.single().value)
    assertEquals("endive:1700000000000:true", recorded.body.utf8())
    assertNotNull(recordedApp.jobHandlerFactory).get("events").handle("job".encodeUtf8())
    assertEquals(2, live.clockCalls)
    assertEquals(
      listOf("clock.now:null", "clock.now:null"),
      recording.journal.snapshot().map { it.key },
    )
    (recordedApp as AutoCloseable).close()

    val replayTarget = ClockOnlyPlatform(failOnClock = true)
    val replay = CapabilitySession.replay(
      id = "endive-replay",
      caller = Caller,
      entries = recording.journal.snapshot(),
    )
    val replayedApp = assertNotNull(
      loader.load(MediatedPlatform(replayTarget, replay), AppSlug, wasm),
    )

    val replayed = replayedApp.httpService!!.execute(
      HttpRequest(url = "https://endive-probe.test/request"),
    )
    assertNotNull(replayedApp.jobHandlerFactory).get("events").handle("job".encodeUtf8())

    assertEquals(recorded, replayed)
    assertEquals(0, replayTarget.clockCalls)
    replay.journal.requireFullyReplayed()
    assertEquals(listOf("cap.replay", "cap.replay"), replay.audit.snapshot().map { it.kind })
    (replayedApp as AutoCloseable).close()
  }

  private class ClockOnlyPlatform(
    private val failOnClock: Boolean = false,
  ) : Platform {
    var clockCalls = 0

    override val clock = object : Clock {
      override fun now(): Instant {
        clockCalls += 1
        check(!failOnClock) { "live clock touched during replay" }
        return Instant.fromEpochMilliseconds(1_700_000_000_000L)
      }
    }
    override val httpService = object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse = error("unused")
    }
    override val objectStore = object : ObjectStore {
      override suspend fun put(request: PutObjectRequest): PutObjectResponse = error("unused")
      override suspend fun get(request: GetObjectRequest): GetObjectResponse = error("unused")
      override suspend fun delete(request: DeleteObjectRequest): DeleteObjectResponse = error("unused")
      override suspend fun list(request: ListObjectsRequest): ListObjectsResponse = error("unused")
    }
    override val downloader = object : Downloader {
      override suspend fun download(transferRequest: TransferRequest): TransferResponse = error("unused")
    }
    override val sqlService = object : SqlService {
      override suspend fun getOrCreate(name: String): SqlDatabase = error("unused")
      override fun close() = Unit
    }
    override val jobQueueFactory = object : JobQueue.Factory {
      override fun get(name: String): JobQueue = error("unused")
    }
  }

  private companion object {
    val AppSlug = AppSlug("endiveprobe")
    val Caller = Caller(
      userId = 42L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "endive-test",
      ip = "127.0.0.1",
    )
  }
}
