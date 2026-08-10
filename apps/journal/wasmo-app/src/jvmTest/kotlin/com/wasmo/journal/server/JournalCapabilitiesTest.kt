package com.wasmo.journal.server

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.wasmo.identifiers.Capability
import com.wasmo.journal.api.EntrySnapshot
import com.wasmo.journal.api.RequestPublishRequest
import com.wasmo.journal.api.SaveEntryRequest
import com.wasmo.journal.api.Visibility
import com.wasmo.journal.server.publishing.SitePublisher
import com.wasmo.packaging.AppManifest
import com.wasmo.packaging.AppManifestChecker
import com.wasmo.packaging.WasmoToml
import com.wasmo.packaging.capabilityPolicy
import com.wasmo.support.issues.IssueCollector
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.app.FakePlatform
import wasmo.mediation.AuditVerification
import wasmo.mediation.CapabilitySession
import wasmo.mediation.MediatedPlatform
import wasmo.sql.FakeSqlService

/**
 * Holds this app's manifest to the truth.
 *
 * A capability declaration is a promise to whoever installs the app, and a promise nobody checks
 * rots: someone adds an outbound fetch or a second queue, the manifest still says what it always
 * said, and the first person to find out is a user staring at a denied request in production.
 *
 * So this runs the real app against the real manifest, through the same boundary and the same
 * policy the OS uses, and fails if the two disagree in either direction — a capability used but not
 * declared, or declared but never used.
 */
class JournalCapabilitiesTest {
  @Test
  fun theManifestIsValidAndDeclaresCapabilities() {
    val manifest = loadManifest()

    val issues = IssueCollector.collect {
      AppManifestChecker(allowExternalResources = true).check(manifest)
    }

    assertThat(issues).isEmpty()
    assertThat(manifest.declaresCapabilities).isTrue()
    assertThat(manifest.capability.map { it.capability }).containsExactlyInAnyOrder(
      Capability.Clock,
      Capability.Sql,
      Capability.ObjectStore,
      Capability.Jobs,
    )
  }

  @Test
  fun theAppNeverAsksForAnythingItDidNotDeclare() = runTest {
    val session = recordingSession()

    exerciseApp(session)

    val denied = session.audit.snapshot().filter { it.kind == "cap.denied" }
    assertThat(denied.map { it.detail }).isEmpty()
    assertThat(session.audit.verify()).isInstanceOf(AuditVerification.Valid::class)
  }

  /**
   * The other direction. An allow list nobody exercises is not least privilege, it is a guess, and
   * a stale entry is exactly how one grows into a hole.
   */
  @Test
  fun everythingTheManifestDeclaresIsActuallyUsed() = runTest {
    val session = recordingSession()

    exerciseApp(session)

    val used = session.journal.snapshot()
      .map { it.key.substringBefore(':') }
      .mapNotNull { id -> Families[id.substringBeforeLast('.')] }
      .toSet()

    assertThat(used).isEqualTo(
      setOf(Capability.Clock, Capability.Sql, Capability.ObjectStore, Capability.Jobs),
    )
  }

  /** Removing a declaration has to break something, or the declaration was never load-bearing. */
  @Test
  fun droppingADeclaredCapabilityDeniesTheApp() = runTest {
    val manifest = loadManifest()
    val withoutObjectStore = manifest.copy(
      capability = manifest.capability.filterNot { it.name == Capability.ObjectStore.id },
    )
    val session = CapabilitySession.recording(
      id = "journal-without-object-store",
      caller = TestCaller,
      policy = withoutObjectStore.capabilityPolicy(),
    )

    runCatching { exerciseApp(session) }

    val denied = session.audit.snapshot().filter { it.kind == "cap.denied" }
    assertThat(denied.isNotEmpty()).isTrue()
  }

  private fun recordingSession() = CapabilitySession.recording(
    id = "journal-capabilities",
    caller = TestCaller,
    policy = loadManifest().capabilityPolicy(),
  )

  /**
   * Drives every part of the app that touches the platform: the clock and its database on every
   * request, object storage for attachments, and the publish queue.
   */
  private suspend fun exerciseApp(session: CapabilitySession) {
    FakeSqlService(databaseName = "journal_capabilities_test").use { sqlService ->
      val platform = MediatedPlatform(FakePlatform(sqlService), session)
      val app = JournalWasmoApp.Factory(prettyPrint = true).create(platform)
      // Not closed, like JournalAppTester: WasmoSqlDriver.close() is still a TODO. Closing the
      // FakeSqlService below is what actually releases the database.
      app.afterInstall(oldVersion = 0L, newVersion = 1L)

      app.httpService.saveEntryAction().save(
        entryToken = EntryToken,
        request = SaveEntryRequest(
          entry = EntrySnapshot(
            token = EntryToken,
            visibility = Visibility.Published,
            slug = "hello",
            title = "Hello",
            date = Instant.fromEpochSeconds(0L),
            body = "<p>Hello</p>",
          ),
        ),
      )

      app.httpService.postAttachmentAction().post(
        entryToken = EntryToken,
        attachmentToken = AttachmentToken,
        request = "an attachment".encodeUtf8(),
        contentType = "text/plain",
      )

      app.httpService.requestPublishAction().requestPublish(RequestPublishRequest)

      // The queued work runs on the same boundary, so its effects are checked too.
      app.jobHandlerFactory.get(SitePublisher.QueueName).handle("".encodeUtf8())
    }
  }

  private fun loadManifest(): AppManifest = WasmoToml.decodeFromString(
    FileSystem.SYSTEM.read(ManifestPath.toPath()) { readUtf8() },
  )

  private companion object {
    /** Gradle runs tests from the module directory, where the packaged app source lives. */
    const val ManifestPath = "journal.wasmo/wasmo-manifest.toml"

    const val EntryToken = "aaaaabbbbbcccccdddddeeeee"
    const val AttachmentToken = "fffffggggghhhhhiiiiijjjjj"

    val TestCaller = Caller(
      userId = 1L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "journal-capabilities-test",
      ip = "127.0.0.1",
    )

    /** Journal capability prefixes back to the family a manifest names. */
    val Families = mapOf(
      "clock" to Capability.Clock,
      "random" to Capability.Random,
      "http" to Capability.Http,
      "objectStore" to Capability.ObjectStore,
      "downloader" to Capability.Downloader,
      "jobs" to Capability.Jobs,
      "sql" to Capability.Sql,
      "sql.row" to Capability.Sql,
    )
  }
}
