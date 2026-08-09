@file:OptIn(ExperimentalUuidApi::class)

package com.wasmo.installedapps

import com.wasmo.accounts.Client
import com.wasmo.api.InstalledAppSnapshot
import com.wasmo.db.installedapps.DbInstalledApp
import com.wasmo.db.installedapps.DbInstalledAppRelease
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import com.wasmo.identifiers.InstalledAppId
import com.wasmo.identifiers.JobName
import com.wasmo.jobs.OsJobHandler
import com.wasmo.jobs.StepHandle
import com.wasmo.packaging.AppManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.FakePlatform
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.jobs.JobHandler
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.MediatedPlatform
import wasmo.sql.RowIterator
import wasmo.sql.SqlBinder
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase
import wasmo.sql.FakeSqlService
import wasmox.sql.SqlTransaction

class ApplicationJobHandlerMediationTest {
  @Test
  fun jobIdOwnsFreshPersistedServicePrincipalSession() = runTest {
    val platform = FakePlatform(FakeSqlService("job_mediation_test"))
    val saved = mutableListOf<CapabilityInvocation>()
    var loadedPlatform: Platform? = null
    var handledData = ""
    val service = object : InstalledAppService {
      override val slug = AppSlug("notes")
      override val appManifestLoader = object : AppManifestLoader {
        override suspend fun load() = AppManifest(target = "wasmWasi", version = 12L)
      }
      override val url = "https://notes.test/".toHttpUrl()
      override val httpService: InstalledAppHttpService
        get() = error("unused")
      override val platform: Platform = platform

      override suspend fun app(platform: Platform): WasmoApp {
        loadedPlatform = platform
        return object : WasmoApp() {
          override val jobHandlerFactory = object : JobHandler.Factory {
            override fun get(queueName: String) = object : JobHandler {
              override suspend fun handle(job: okio.ByteString) {
                platform.clock.now()
                handledData = "$queueName:${job.utf8()}"
              }
            }
          }
        }
      }

      override suspend fun homeUrl(): HttpUrl = error("unused")
      override suspend fun maskableIconUrl(): HttpUrl = error("unused")
      override suspend fun snapshot(): InstalledAppSnapshot = error("unused")
    }
    val handler = ApplicationJobHandler(
      wasmoDb = TransactionOnlyDatabase,
      installedAppStore = SingleInstalledAppStore(service),
      invocationStore = CapabilityInvocationStore(saved::add),
      clock = Clock.System,
    )
    val jobId = Uuid.parse("00000000-0000-0000-0000-000000000123")
    val context = TestJobContext(jobId)

    context(context) {
      handler.handle(
        ApplicationJob(
          installedAppId = InstalledAppId(5L),
          queueName = "events",
          data = "payload".encodeUtf8(),
          executeAt = null,
        ),
      )
    }

    assertTrue(loadedPlatform is MediatedPlatform)
    assertEquals("events:payload", handledData)
    val invocation = saved.single()
    assertTrue(invocation.callerJson.contains("os-job:ApplicationJob:$jobId"))
    assertTrue(invocation.callerJson.contains("\"userId\":null"))
    assertEquals(listOf("clock.now:null"), invocation.journal.map { it.key })
  }

  private class SingleInstalledAppStore(
    private val service: InstalledAppService,
  ) : InstalledAppStore {
    context(sqlTransaction: SqlTransaction)
    override suspend fun get(installedAppId: InstalledAppId): InstalledAppService = service

    context(sqlTransaction: SqlTransaction)
    override suspend fun getHttpServiceAndAccessOrNull(
      client: Client,
      computerSlug: ComputerSlug,
      appSlug: AppSlug,
    ) = error("unused")

    context(sqlTransaction: SqlTransaction)
    override suspend fun getOrNull(client: Client, computerSlug: ComputerSlug, appSlug: AppSlug) =
      error("unused")

    context(sqlTransaction: SqlTransaction)
    override suspend fun get(
      installedApp: DbInstalledApp,
      installedAppRelease: DbInstalledAppRelease?,
    ) = error("unused")

    override suspend fun get(
      computerSlug: ComputerSlug,
      installedApp: DbInstalledApp,
      installedAppRelease: DbInstalledAppRelease?,
    ) = error("unused")
  }

  private object TransactionOnlyDatabase : SqlDatabase {
    override suspend fun newConnection() = object : SqlConnection {
      override suspend fun execute(
        sql: String,
        bindParameters: (SqlBinder.() -> Unit)?,
      ) = 0L

      override suspend fun executeQuery(
        sql: String,
        bindParameters: (SqlBinder.() -> Unit)?,
      ): RowIterator = error("unused")

      override fun close() = Unit
    }

    override fun close() = Unit
  }

  private class TestJobContext(
    override val jobId: Uuid,
  ) : OsJobHandler.Context() {
    override val jobName = JobName<ApplicationJob, Unit>("ApplicationJob")

    override suspend fun <T> step(
      name: String,
      serializer: KSerializer<T>,
      block: suspend () -> T,
    ): T = error("unused")

    override suspend fun <T> beginStep(name: String, serializer: KSerializer<T>): StepHandle<T> =
      error("unused")

    override suspend fun <T> awaitEvent(
      eventName: String,
      serializer: KSerializer<T>,
      stepName: String,
      timeout: Duration?,
    ): T = error("unused")

    override suspend fun sleepFor(stepName: String, duration: Duration) = error("unused")
    override suspend fun sleepUntil(stepName: String, wakeAt: Instant) = error("unused")
  }
}
