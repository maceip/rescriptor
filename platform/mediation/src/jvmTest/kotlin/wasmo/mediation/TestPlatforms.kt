package wasmo.mediation

import kotlin.time.Clock
import kotlin.time.Instant
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.Platform
import wasmo.downloader.Downloader
import wasmo.downloader.TransferRequest
import wasmo.downloader.TransferResponse
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
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

/** A live platform that answers everything, for tests that care about the boundary, not the data. */
object TestPlatforms {
  fun working(): Platform = WorkingPlatform()

  private class WorkingPlatform : Platform {
    override val clock = object : Clock {
      override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }

    override val httpService = object : HttpService {
      override suspend fun execute(request: HttpRequest) =
        HttpResponse(body = "body:${request.url}".encodeUtf8())
    }

    override val objectStore = object : ObjectStore {
      override suspend fun put(request: PutObjectRequest) = PutObjectResponse("put-etag")

      override suspend fun get(request: GetObjectRequest) =
        GetObjectResponse("v".encodeUtf8(), etag = "get-etag", contentType = "text/plain")

      override suspend fun delete(request: DeleteObjectRequest) = DeleteObjectResponse

      override suspend fun list(request: ListObjectsRequest) =
        ListObjectsResponse(entries = listOf(), nextRequest = null)
    }

    override val downloader = object : Downloader {
      override suspend fun download(transferRequest: TransferRequest) =
        TransferResponse(httpResponse = HttpResponse(code = 200), etag = "download-etag")
    }

    override val sqlService = object : SqlService {
      override suspend fun getOrCreate(name: String): SqlDatabase = WorkingSqlDatabase
      override fun close() = Unit
    }

    override val jobQueueFactory = object : JobQueue.Factory {
      override fun get(name: String): JobQueue = object : JobQueue {
        override suspend fun enqueue(job: ByteString, executeAt: Instant?) = Unit
        override suspend fun cancel(job: ByteString) = Unit
      }
    }
  }

  private object WorkingSqlDatabase : SqlDatabase {
    override suspend fun newConnection(): SqlConnection = WorkingSqlConnection
    override fun close() = Unit
  }

  private object WorkingSqlConnection : SqlConnection {
    override suspend fun execute(sql: String, bindParameters: (SqlBinder.() -> Unit)?): Long {
      bindParameters?.invoke(DiscardingBinder)
      return 1L
    }

    override suspend fun executeQuery(
      sql: String,
      bindParameters: (SqlBinder.() -> Unit)?,
    ): RowIterator {
      bindParameters?.invoke(DiscardingBinder)
      return EmptyRowIterator
    }

    override fun close() = Unit
  }

  private object EmptyRowIterator : RowIterator {
    override suspend fun next(): SqlRow? = null
    override fun close() = Unit
  }

  private object DiscardingBinder : SqlBinder {
    override fun bindBool(index: Int, value: Boolean?) = Unit
    override fun bindS32(index: Int, value: Int?) = Unit
    override fun bindS64(index: Int, value: Long?) = Unit
    override fun bindF32(index: Int, value: Float?) = Unit
    override fun bindF64(index: Int, value: Double?) = Unit
    override fun bindInstant(index: Int, value: Instant?) = Unit
    override fun bindString(index: Int, value: String?) = Unit
    override fun bindBytes(index: Int, value: ByteString?) = Unit
    override fun bindUuid(index: Int, value: kotlin.uuid.Uuid?) = Unit
    override fun bindJson(index: Int, value: wasmo.json.JsonLiteral?) = Unit
  }
}
