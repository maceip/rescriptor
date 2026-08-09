package com.wasmo.wasm.endive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.Platform
import wasmo.downloader.Downloader
import wasmo.downloader.TransferRequest
import wasmo.downloader.TransferResponse
import wasmo.http.Header
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
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlService

class PlatformCapabilityHostTest {
  @Test
  fun dispatchesHttpObjectStoreDownloaderJobsAndRandom() {
    val platform = AbiPlatform()
    val host = PlatformCapabilityHost(platform)

    val http = host.call(
      request(
        "http",
        "fetch",
        """{"url":"https://upstream.test/","method":"POST","headers":[],"bodyBase64":"aGk="}""",
      ),
    ).jsonObject
    assertEquals("202", http.getValue("code").jsonPrimitive.content)
    assertEquals("hi", platform.httpRequest?.body?.utf8())

    val put = host.call(
      request(
        "objectStore",
        "put",
        """{"key":"note.txt","valueBase64":"dmFsdWU=","contentType":"text/plain"}""",
      ),
    ).jsonObject
    assertEquals(platform.objectEtag, put.getValue("etag").jsonPrimitive.content)

    val get = host.call(request("objectStore", "get", """{"key":"note.txt"}""")).jsonObject
    assertEquals("dmFsdWU=", get.getValue("valueBase64").jsonPrimitive.content)

    val list = host.call(request("objectStore", "list", """{"prefix":"note"}""")).jsonObject
    assertEquals(1, list.getValue("entries").toString().count { it == '{' })

    host.call(
      request(
        "downloader",
        "download",
        """{"httpRequest":{"url":"https://download.test/","headers":[]},"objectStoreKey":"download.bin"}""",
      ),
    )
    assertEquals("download.bin", platform.downloadKey)

    host.call(
      request(
        "jobs",
        "enqueue",
        """{"queue":"events","jobBase64":"am9i","executeAtMillis":2000}""",
      ),
    )
    host.call(request("jobs", "cancel", """{"queue":"events","jobBase64":"am9i"}"""))
    assertEquals(listOf("enqueue:events:job:2000", "cancel:events:job"), platform.jobs)

    val random = host.call(request("random", "bytes", """{"length":16}"""))
      .jsonPrimitive.content
    assertEquals(16, random.decodeBase64()!!.size)

    host.call(request("objectStore", "delete", """{"key":"note.txt"}"""))
    assertNull(platform.objectValue)
  }

  private fun request(capability: String, method: String, body: String): JsonElement =
    Json.parseToJsonElement("""{"cap":"$capability","method":"$method","req":$body}""")

  private class AbiPlatform : Platform {
    var httpRequest: HttpRequest? = null
    var objectValue: ByteString? = null
    var downloadKey: String? = null
    val jobs = mutableListOf<String>()
    val objectEtag = "object-etag"

    override val clock = Clock.System
    override val httpService = object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse {
        httpRequest = request
        return HttpResponse(
          code = 202,
          headers = listOf(Header("content-type", "text/plain")),
          body = "upstream".encodeUtf8(),
        )
      }
    }
    override val objectStore = object : ObjectStore {
      override suspend fun put(request: PutObjectRequest): PutObjectResponse {
        objectValue = request.value
        return PutObjectResponse(objectEtag)
      }

      override suspend fun get(request: GetObjectRequest) = GetObjectResponse(
        value = objectValue,
        etag = objectEtag,
        contentType = "text/plain",
      )

      override suspend fun delete(request: DeleteObjectRequest): DeleteObjectResponse {
        objectValue = null
        return DeleteObjectResponse
      }

      override suspend fun list(request: ListObjectsRequest) = ListObjectsResponse(
        entries = listOf(ListObjectsResponse.Object("note.txt", objectEtag, 5)),
      )
    }
    override val downloader = object : Downloader {
      override suspend fun download(transferRequest: TransferRequest): TransferResponse {
        downloadKey = transferRequest.objectStoreKey
        return TransferResponse(HttpResponse(code = 204), "download-etag")
      }
    }
    override val jobQueueFactory = object : JobQueue.Factory {
      override fun get(name: String) = object : JobQueue {
        override suspend fun enqueue(job: ByteString, executeAt: Instant?) {
          jobs += "enqueue:$name:${job.utf8()}:${executeAt?.toEpochMilliseconds()}"
        }

        override suspend fun cancel(job: ByteString) {
          jobs += "cancel:$name:${job.utf8()}"
        }
      }
    }
    override val sqlService = object : SqlService {
      override suspend fun getOrCreate(name: String): SqlDatabase = error("unused")
      override fun close() = Unit
    }
  }
}
