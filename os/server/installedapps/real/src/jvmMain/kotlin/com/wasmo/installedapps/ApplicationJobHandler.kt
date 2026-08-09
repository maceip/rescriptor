package com.wasmo.installedapps

import com.wasmo.identifiers.OsScope
import com.wasmo.jobs.OsJobHandler
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.mediation.CapabilityInvocationRecorder
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.InvocationKind
import wasmo.mediation.MediatedPlatform
import wasmo.sql.SqlDatabase
import wasmox.sql.transaction

/**
 * TODO: This drops jobs if the app isn't currently runnable. For example, if the jobs are eligible
 *   for execution when the app is upgrading.
 */
@Inject
@SingleIn(OsScope::class)
class ApplicationJobHandler(
  private val wasmoDb: SqlDatabase,
  private val installedAppStore: InstalledAppStore,
  invocationStore: CapabilityInvocationStore,
  clock: Clock,
) : OsJobHandler<ApplicationJob, Unit> {
  private val invocationRecorder = CapabilityInvocationRecorder(invocationStore, clock)

  context(context: OsJobHandler.Context)
  override suspend fun handle(job: ApplicationJob) {
    val installedAppService = wasmoDb.transaction {
      installedAppStore.get(job.installedAppId)
    }

    if (installedAppService == null) return
    val manifest = installedAppService.appManifestLoader.load()
    val caller = Caller(
      userId = null,
      computerAccess = ComputerAccess.Owner,
      userAgent = null,
      ip = null,
      service = "os-job:${context.jobName.value}:${context.jobId}",
    )
    invocationRecorder.record(
      kind = InvocationKind.Job,
      appSlug = installedAppService.slug.value,
      appVersion = manifest.version,
      caller = caller,
      input = InvocationPayloads.encodeJob(job, jobId = context.jobId.toString()),
      encodeOutput = { JsonNull },
      policy = manifest.capabilityPolicy(),
    ) { session ->
      val app = installedAppService.app(
        MediatedPlatform(installedAppService.platform, session),
      ) ?: return@record
      try {
        val jobHandlerFactory = app.jobHandlerFactory ?: return@record
        val jobHandler = jobHandlerFactory.get(job.queueName)

        // TODO: handle dead letter
        jobHandler.handle(job.data)
      } finally {
        (app as? AutoCloseable)?.close()
      }
    }
  }
}
