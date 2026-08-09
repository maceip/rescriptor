package com.wasmo.installedapps

import wasmo.mediation.AuditChain
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.CapabilityInvocationReader
import wasmo.mediation.CapabilityInvocationStore
import wasmo.mediation.InvocationQuery
import wasmo.mediation.InvocationSummary

/** An in-memory stand-in for the OS's PostgreSQL invocation storage. */
class FakeCapabilityInvocationStore :
  CapabilityInvocationStore,
  CapabilityInvocationReader {
  val saved: MutableList<CapabilityInvocation> = mutableListOf()

  override suspend fun save(invocation: CapabilityInvocation) {
    saved += invocation
  }

  override suspend fun load(id: String): CapabilityInvocation? = saved.firstOrNull { it.id == id }

  override suspend fun list(query: InvocationQuery): List<InvocationSummary> {
    val startedBefore = query.startedBefore
    return saved
      .asReversed()
      .filter { query.appSlug == null || it.appSlug == query.appSlug }
      .filter { startedBefore == null || it.startedAt < startedBefore }
      .take(query.limit)
    .map {
      InvocationSummary(
        id = it.id,
        kind = it.kind,
        appSlug = it.appSlug,
        appVersion = it.appVersion,
        callerJson = it.callerJson,
        startedAt = it.startedAt,
        completedAt = it.completedAt,
        failureType = it.failure?.type,
        journalCount = it.journal.size.toLong(),
        auditCount = it.audit.size.toLong(),
        auditHead = it.audit.lastOrNull()?.hash ?: AuditChain.Genesis,
      )
    }
  }
}
