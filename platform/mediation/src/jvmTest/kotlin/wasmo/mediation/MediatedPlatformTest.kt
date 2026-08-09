@file:OptIn(ExperimentalUuidApi::class)

package wasmo.mediation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.wasmo.identifiers.AppSlug
import com.wasmo.wasm.JvmAppLoader
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
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
import wasmo.json.JsonLiteral
import wasmo.objectstore.DeleteObjectRequest
import wasmo.objectstore.DeleteObjectResponse
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.GetObjectResponse
import wasmo.objectstore.ListObjectsRequest
import wasmo.objectstore.ListObjectsResponse
import wasmo.objectstore.ObjectStore
import wasmo.objectstore.PutObjectRequest
import wasmo.objectstore.PutObjectResponse
import wasmo.sql.RowIterator
import wasmo.sql.SqlBinder
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlRow
import wasmo.sql.SqlService

class MediatedPlatformTest {
  @Test
  fun unchangedJvmAppRecordsAndReplaysWithoutRepeatingEffects() = runTest {
    val live = TestPlatform()
    val recording = CapabilitySession.recording(
      id = "session-1",
      caller = Caller,
      audit = AuditChain { 1234L },
    )
    val loader = JvmAppLoader(mapOf(AppSlug to TestApp.Factory))

    val recordedApp = loader.load(MediatedPlatform(live, recording), AppSlug, wasm = null)!!
    val recordedResponse = recordedApp.httpService!!.execute(HttpRequest(url = "https://app.test/"))

    assertThat(recordedResponse.body.utf8()).isEqualTo("1700000000000:upstream")
    assertThat(live.clockCalls).isEqualTo(1)
    assertThat(live.httpCalls).isEqualTo(1)
    assertThat(recording.journal.snapshot().map { it.key }).containsExactly(
      "clock.now:null",
      "http.fetch:{\"body\":null,\"headers\":[],\"method\":\"GET\",\"url\":\"https://upstream.test/data\"}",
    )
    assertThat(recording.audit.verify()).isInstanceOf(AuditVerification.Valid::class)

    val replayTarget = TestPlatform(failOnUse = true)
    val replay = CapabilitySession.replay(
      id = "session-1-replay",
      caller = Caller,
      entries = recording.journal.snapshot(),
      audit = AuditChain { 5678L },
    )
    val replayedApp = loader.load(MediatedPlatform(replayTarget, replay), AppSlug, wasm = null)!!
    val replayedResponse = replayedApp.httpService!!.execute(HttpRequest(url = "https://app.test/"))

    assertThat(replayedResponse).isEqualTo(recordedResponse)
    assertThat(replayTarget.clockCalls).isEqualTo(0)
    assertThat(replayTarget.httpCalls).isEqualTo(0)
    replay.journal.requireFullyReplayed()
    assertThat(replay.audit.snapshot().map { it.kind }).containsExactly("cap.replay", "cap.replay")
    assertThat(replay.audit.verify()).isInstanceOf(AuditVerification.Valid::class)
  }

  @Test
  fun replayDetectsChangedCapabilityRequest() = runTest {
    val recorded = CapabilitySession.recording("session-2", Caller)
    val live = MediatedPlatform(TestPlatform(), recorded)
    live.clock.now()
    live.httpService.execute(HttpRequest(url = "https://upstream.test/data"))

    val replay = CapabilitySession.replay("session-2-replay", Caller, recorded.journal.snapshot())
    val platform = MediatedPlatform(TestPlatform(failOnUse = true), replay)
    platform.clock.now()

    val failure = assertFailsWith<ReplayMismatchException> {
      platform.httpService.execute(HttpRequest(url = "https://upstream.test/changed"))
    }
    assertThat(failure.index).isEqualTo(1)
    assertThat(failure.expected.contains("https://upstream.test/data")).isTrue()
    assertThat(failure.got.contains("https://upstream.test/changed")).isTrue()
  }

  @Test
  fun sqlWritesAndObservedRowsReplayWithoutDatabaseAccess() = runTest {
    val live = TestPlatform()
    val recording = CapabilitySession.recording("session-sql", Caller)
    val recorded = sqlProgram(MediatedPlatform(live, recording))

    assertThat(recorded).isEqualTo("3:hello:7")
    assertThat(live.sqlService.calls).isEqualTo(4)

    val replayTarget = TestPlatform(failOnUse = true)
    val replay = CapabilitySession.replay(
      id = "session-sql-replay",
      caller = Caller,
      entries = recording.journal.snapshot(),
    )
    val replayed = sqlProgram(MediatedPlatform(replayTarget, replay))

    assertThat(replayed).isEqualTo(recorded)
    assertThat(replayTarget.sqlService.calls).isEqualTo(0)
    replay.journal.requireFullyReplayed()
  }

  @Test
  fun objectStoreDownloaderAndJobsReplayWithoutRepeatingEffects() = runTest {
    val live = TestPlatform()
    val recording = CapabilitySession.recording("session-peripherals", Caller)
    val recorded = peripheralProgram(MediatedPlatform(live, recording))

    assertThat(recorded).isEqualTo("put-etag:value.txt:1:download-etag")
    assertThat(live.objectStoreDelegate.calls).isEqualTo(4)
    assertThat(live.downloaderDelegate.calls).isEqualTo(1)
    assertThat(live.jobQueueFactoryDelegate.calls).isEqualTo(3)

    val replayTarget = TestPlatform(failOnUse = true)
    val replay = CapabilitySession.replay(
      id = "session-peripherals-replay",
      caller = Caller,
      entries = recording.journal.snapshot(),
    )
    val replayed = peripheralProgram(MediatedPlatform(replayTarget, replay))

    assertThat(replayed).isEqualTo(recorded)
    assertThat(replayTarget.objectStoreDelegate.calls).isEqualTo(0)
    assertThat(replayTarget.downloaderDelegate.calls).isEqualTo(0)
    assertThat(replayTarget.jobQueueFactoryDelegate.calls).isEqualTo(0)
    replay.journal.requireFullyReplayed()
  }

  @Test
  fun deniedCapabilityIsAuditedBeforeDelegation() = runTest {
    val live = TestPlatform()
    val session = CapabilitySession.recording(
      id = "session-denied",
      caller = Caller,
      policy = CapabilityPolicy { it != "http.fetch" },
      audit = AuditChain { 99L },
    )

    val failure = assertFailsWith<CapabilityDeniedException> {
      MediatedPlatform(live, session).httpService.execute(HttpRequest(url = "https://denied.test/"))
    }

    assertThat(failure.capability).isEqualTo("http.fetch")
    assertThat(live.httpCalls).isEqualTo(0)
    assertThat(session.journal.snapshot().isEmpty()).isTrue()
    assertThat(session.audit.snapshot().map { it.kind }).containsExactly("cap.denied")
    assertThat(session.audit.verify()).isInstanceOf(AuditVerification.Valid::class)
  }

  @Test
  fun capabilityFailureIsJournaledAndReplayedWithoutRepeatingTheCall() = runTest {
    val live = TestPlatform(failOnUse = true)
    val recording = CapabilitySession.recording("session-failure", Caller)

    assertFailsWith<IllegalStateException> {
      MediatedPlatform(live, recording).httpService.execute(
        HttpRequest(url = "https://upstream.test/failure"),
      )
    }
    assertThat(live.httpCalls).isEqualTo(1)
    assertThat(recording.journal.snapshot().single().failure?.message)
      .isEqualTo("HTTP touched during replay")
    assertThat(recording.audit.snapshot().map { it.kind }).containsExactly("cap.live.error")

    val replayTarget = TestPlatform(failOnUse = true)
    val replay = CapabilitySession.replay(
      id = "session-failure-replay",
      caller = Caller,
      entries = recording.journal.snapshot(),
    )
    assertFailsWith<ReplayedCapabilityFailureException> {
      MediatedPlatform(replayTarget, replay).httpService.execute(
        HttpRequest(url = "https://upstream.test/failure"),
      )
    }
    assertThat(replayTarget.httpCalls).isEqualTo(0)
    assertThat(replay.audit.snapshot().map { it.kind }).containsExactly("cap.replay.error")
  }

  @Test
  fun auditVerificationFindsTampering() {
    val audit = AuditChain { 100L }
    audit.append("cap.live", "caller", "one", AuditChain.resultHash("result-1"))
    audit.append("cap.live", "caller", "two", AuditChain.resultHash("result-2"))

    val tampered = audit.snapshot().mapIndexed { index, entry ->
      if (index == 0) entry.copy(detail = "changed") else entry
    }
    val verification = AuditChain.verify(tampered)

    assertThat(verification).isEqualTo(AuditVerification.Invalid(1L, "hash mismatch"))
  }

  @Test
  fun canonicalJsonSortsNestedObjectKeysWithoutChangingArrayOrder() {
    val value = JsonObject(
      linkedMapOf(
        "z" to JsonPrimitive(1),
        "a" to JsonObject(linkedMapOf("y" to JsonPrimitive(2), "b" to JsonPrimitive(3))),
        "list" to JsonArray(listOf(JsonPrimitive("second"), JsonPrimitive("first"))),
      ),
    )

    assertThat(stableStringify(value)).isEqualTo(
      "{\"a\":{\"b\":3,\"y\":2},\"list\":[\"second\",\"first\"],\"z\":1}",
    )
  }

  private suspend fun sqlProgram(platform: Platform): String {
    val database = platform.sqlService.getOrCreate("notes")
    val connection = database.newConnection()
    val written = connection.execute("INSERT INTO notes(text) VALUES (?)") {
      bindString(0, "hello")
    }
    val rows = connection.executeQuery("SELECT text, n FROM notes WHERE n = ?") {
      bindS64(0, 7L)
    }
    val row = rows.next()!!
    val result = "$written:${row.getString(0)}:${row.getS64(1)}"
    assertThat(rows.next() == null).isTrue()
    rows.close()
    connection.close()
    database.close()
    return result
  }

  private suspend fun peripheralProgram(platform: Platform): String {
    val put = platform.objectStore.put(
      PutObjectRequest(
        key = "value.txt",
        value = "value".encodeUtf8(),
        contentType = "text/plain",
      ),
    )
    val get = platform.objectStore.get(GetObjectRequest("value.txt"))
    val list = platform.objectStore.list(ListObjectsRequest(prefix = "value"))
    platform.objectStore.delete(DeleteObjectRequest("value.txt"))
    val download = platform.downloader.download(
      TransferRequest(
        httpRequest = HttpRequest(url = "https://download.test/file"),
        objectStoreKey = "download.txt",
      ),
    )
    val queue = platform.jobQueueFactory.get("events")
    queue.enqueue("job".encodeUtf8(), Instant.fromEpochMilliseconds(2_000L))
    queue.cancel("job".encodeUtf8())
    val listed = list.entries.single() as ListObjectsResponse.Object
    return "${put.etag}:${listed.key}:${get.value?.size}:${download.etag}"
  }

  private class TestApp(
    private val platform: Platform,
  ) : WasmoApp() {
    override val httpService = object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse {
        val now = platform.clock.now().toEpochMilliseconds()
        val upstream = platform.httpService.execute(HttpRequest(url = "https://upstream.test/data"))
        return HttpResponse(body = "$now:${upstream.body.utf8()}".encodeUtf8())
      }
    }

    object Factory : WasmoApp.Factory {
      override suspend fun create(platform: Platform): WasmoApp = TestApp(platform)
    }
  }

  private class TestPlatform(
    private val failOnUse: Boolean = false,
  ) : Platform {
    var clockCalls = 0
    var httpCalls = 0

    override val clock = object : Clock {
      override fun now(): Instant {
        clockCalls += 1
        check(!failOnUse) { "clock touched during replay" }
        return Instant.fromEpochMilliseconds(1_700_000_000_000L)
      }
    }

    override val httpService = object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse {
        httpCalls += 1
        check(!failOnUse) { "HTTP touched during replay" }
        return HttpResponse(body = "upstream".encodeUtf8())
      }
    }

    val objectStoreDelegate = TestObjectStore(failOnUse)
    val downloaderDelegate = TestDownloader(failOnUse)
    val jobQueueFactoryDelegate = TestJobQueueFactory(failOnUse)

    override val objectStore: ObjectStore = objectStoreDelegate
    override val downloader: Downloader = downloaderDelegate
    override val jobQueueFactory: JobQueue.Factory = jobQueueFactoryDelegate
    override val sqlService = TestSqlService(failOnUse)
  }

  private class TestObjectStore(
    private val failOnUse: Boolean,
  ) : ObjectStore {
    var calls = 0

    override suspend fun put(request: PutObjectRequest): PutObjectResponse = used {
      assertThat(request.key).isEqualTo("value.txt")
      PutObjectResponse("put-etag")
    }

    override suspend fun get(request: GetObjectRequest): GetObjectResponse = used {
      GetObjectResponse("v".encodeUtf8(), etag = "get-etag", contentType = "text/plain")
    }

    override suspend fun delete(request: DeleteObjectRequest): DeleteObjectResponse = used {
      DeleteObjectResponse
    }

    override suspend fun list(request: ListObjectsRequest): ListObjectsResponse = used {
      ListObjectsResponse(
        entries = listOf(ListObjectsResponse.Object("value.txt", "list-etag", 1L)),
        nextRequest = ListObjectsRequest(prefix = "next"),
      )
    }

    private fun <T> used(block: () -> T): T {
      calls += 1
      check(!failOnUse) { "object store touched during replay" }
      return block()
    }
  }

  private class TestDownloader(
    private val failOnUse: Boolean,
  ) : Downloader {
    var calls = 0

    override suspend fun download(transferRequest: TransferRequest): TransferResponse {
      calls += 1
      check(!failOnUse) { "downloader touched during replay" }
      return TransferResponse(
        httpResponse = HttpResponse(code = 202, body = ByteString.EMPTY),
        etag = "download-etag",
      )
    }
  }

  private class TestJobQueueFactory(
    private val failOnUse: Boolean,
  ) : JobQueue.Factory {
    var calls = 0

    override fun get(name: String): JobQueue {
      calls += 1
      check(!failOnUse) { "job queue factory touched during replay" }
      return object : JobQueue {
        override suspend fun enqueue(job: ByteString, executeAt: Instant?) {
          calls += 1
          check(!failOnUse) { "job queue touched during replay" }
        }

        override suspend fun cancel(job: ByteString) {
          calls += 1
          check(!failOnUse) { "job queue touched during replay" }
        }
      }
    }
  }

  private class TestSqlService(
    private val failOnUse: Boolean,
  ) : SqlService {
    var calls = 0

    override suspend fun getOrCreate(name: String): SqlDatabase {
      calls += 1
      check(!failOnUse) { "SQL touched during replay" }
      assertThat(name).isEqualTo("notes")
      return object : SqlDatabase {
        override suspend fun newConnection(): SqlConnection {
          calls += 1
          return TestSqlConnection { calls += 1 }
        }

        override fun close() = Unit
      }
    }

    override fun close() = Unit
  }

  private class TestSqlConnection(
    private val onCall: () -> Unit,
  ) : SqlConnection {
    override suspend fun execute(
      sql: String,
      bindParameters: (SqlBinder.() -> Unit)?,
    ): Long {
      onCall()
      assertThat(sql).isEqualTo("INSERT INTO notes(text) VALUES (?)")
      val binder = TestBinder()
      bindParameters?.invoke(binder)
      assertThat(binder.values).containsExactly("string:0=hello")
      return 3L
    }

    override suspend fun executeQuery(
      sql: String,
      bindParameters: (SqlBinder.() -> Unit)?,
    ): RowIterator {
      onCall()
      assertThat(sql).isEqualTo("SELECT text, n FROM notes WHERE n = ?")
      val binder = TestBinder()
      bindParameters?.invoke(binder)
      assertThat(binder.values).containsExactly("s64:0=7")
      return object : RowIterator {
        private var emitted = false

        override suspend fun next(): SqlRow? {
          if (emitted) return null
          emitted = true
          return TestSqlRow
        }

        override fun close() = Unit
      }
    }

    override fun close() = Unit
  }

  private class TestBinder : SqlBinder {
    val values = mutableListOf<String>()

    override fun bindBool(index: Int, value: Boolean?) = add("bool", index, value)
    override fun bindS32(index: Int, value: Int?) = add("s32", index, value)
    override fun bindS64(index: Int, value: Long?) = add("s64", index, value)
    override fun bindF32(index: Int, value: Float?) = add("f32", index, value)
    override fun bindF64(index: Int, value: Double?) = add("f64", index, value)
    override fun bindInstant(index: Int, value: Instant?) = add("instant", index, value)
    override fun bindString(index: Int, value: String?) = add("string", index, value)
    override fun bindBytes(index: Int, value: ByteString?) = add("bytes", index, value)
    override fun bindUuid(index: Int, value: Uuid?) = add("uuid", index, value)
    override fun bindJson(index: Int, value: JsonLiteral?) = add("json", index, value)

    private fun add(type: String, index: Int, value: Any?) {
      values += "$type:$index=$value"
    }
  }

  private object TestSqlRow : SqlRow {
    override fun getString(index: Int): String? = if (index == 0) "hello" else null
    override fun getS64(index: Int): Long? = if (index == 1) 7L else null
    override fun getBool(index: Int): Boolean? = error("unused")
    override fun getS32(index: Int): Int? = error("unused")
    override fun getF32(index: Int): Float? = error("unused")
    override fun getF64(index: Int): Double? = error("unused")
    override fun getInstant(index: Int): Instant? = error("unused")
    override fun getBytes(index: Int): ByteString? = error("unused")
    override fun getUuid(index: Int): Uuid? = error("unused")
    override fun getJson(index: Int): JsonLiteral? = error("unused")
  }

  companion object {
    private val AppSlug = AppSlug("mediation")
    private val Caller = Caller(
      userId = 42L,
      computerAccess = ComputerAccess.Owner,
      userAgent = "test",
      ip = "127.0.0.1",
    )
  }
}
