package wasmo.mediation

import kotlin.time.Clock
import kotlin.time.Instant
import okio.ByteString
import wasmo.app.Platform
import wasmo.downloader.Downloader
import wasmo.downloader.TransferRequest
import wasmo.http.HttpRequest
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
import wasmo.objectstore.DeleteObjectRequest
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.ListObjectsRequest
import wasmo.objectstore.ObjectStore
import wasmo.objectstore.PutObjectRequest
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlService

class SealedPlatformException(
  val operation: String,
) : IllegalStateException(
  "replay reached the live platform at '$operation'; a replay must be satisfied entirely by its " +
    "capability journal",
)

/**
 * A [Platform] on which every effect fails.
 *
 * Wrapping this in a replaying [MediatedPlatform] is the executable proof of the property the
 * capability journal exists to provide: a recorded invocation can be re-run anywhere — another
 * machine, another datacenter, a laptop with no network — and produce the same bytes without
 * re-sending an email, re-charging a card, or re-reading a database that has since moved on.
 *
 * A replaying [MediatedPlatform] never dereferences its delegate, so reaching this object at all
 * means the journal was incomplete.
 */
object SealedPlatform : Platform {
  override val clock: Clock = object : Clock {
    override fun now(): Instant = throw SealedPlatformException("clock.now")
  }

  override val httpService: HttpService = object : HttpService {
    override suspend fun execute(request: HttpRequest) = throw SealedPlatformException("http.fetch")
  }

  override val objectStore: ObjectStore = object : ObjectStore {
    override suspend fun put(request: PutObjectRequest) =
      throw SealedPlatformException("objectStore.put")

    override suspend fun get(request: GetObjectRequest) =
      throw SealedPlatformException("objectStore.get")

    override suspend fun delete(request: DeleteObjectRequest) =
      throw SealedPlatformException("objectStore.delete")

    override suspend fun list(request: ListObjectsRequest) =
      throw SealedPlatformException("objectStore.list")
  }

  override val downloader: Downloader = object : Downloader {
    override suspend fun download(transferRequest: TransferRequest) =
      throw SealedPlatformException("downloader.download")
  }

  override val sqlService: SqlService = object : SqlService {
    override suspend fun getOrCreate(name: String): SqlDatabase =
      throw SealedPlatformException("sql.getOrCreate")

    override fun close() = Unit
  }

  override val jobQueueFactory: JobQueue.Factory = object : JobQueue.Factory {
    override fun get(name: String): JobQueue = object : JobQueue {
      override suspend fun enqueue(job: ByteString, executeAt: Instant?) =
        throw SealedPlatformException("jobs.enqueue")

      override suspend fun cancel(job: ByteString) = throw SealedPlatformException("jobs.cancel")
    }
  }
}
