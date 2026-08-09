package com.wasmo.installedapps

import com.wasmo.framework.ActionRegistration
import com.wasmo.db.mediation.SqlCapabilityInvocationStore
import com.wasmo.identifiers.HostnamePatterns
import com.wasmo.identifiers.JobName
import com.wasmo.identifiers.OsScope
import com.wasmo.jobs.JobRegistration
import com.wasmo.jobs.OsJobQueue
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import wasmo.mediation.CapabilityInvocationReader
import wasmo.mediation.CapabilityInvocationStore

@BindingContainer
abstract class InstalledAppBindings {
  @Binds
  abstract fun bindInstalledAppStore(real: RealInstalledAppStore): InstalledAppStore

  @Binds
  abstract fun bindCapabilityInvocationStore(
    real: SqlCapabilityInvocationStore,
  ): CapabilityInvocationStore

  @Binds
  abstract fun bindCapabilityInvocationReader(
    real: SqlCapabilityInvocationStore,
  ): CapabilityInvocationReader

  companion object {
    private val ApplicationJobName = JobName<ApplicationJob, Unit>("ApplicationJob")

    @Provides
    @ElementsIntoSet
    @SingleIn(OsScope::class)
    fun provideActionRegistrations(
      hostnamePatterns: HostnamePatterns,
    ): List<ActionRegistration> = listOf(
      ActionRegistration.Http(
        host = hostnamePatterns.appRegex,
        action = CallAppAction::class,
      ),
    )

    @Provides
    @SingleIn(OsScope::class)
    fun provideApplicationJobQueue(
      jobQueueFactory: OsJobQueue.Factory,
    ): OsJobQueue<ApplicationJob> = jobQueueFactory.create(ApplicationJobName)

    @Provides
    @IntoSet
    @SingleIn(OsScope::class)
    fun provideApplicationJobRegistration(
      applicationJobHandler: ApplicationJobHandler,
    ): JobRegistration<*, *> = JobRegistration(ApplicationJobName, applicationJobHandler)
  }
}
