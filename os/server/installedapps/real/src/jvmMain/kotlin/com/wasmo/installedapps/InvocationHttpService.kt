package com.wasmo.installedapps

import com.wasmo.framework.ArgumentUserException
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.Response
import com.wasmo.framework.ResponseBody
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.mediation.CapabilityInvocationReader
import wasmo.mediation.Divergence
import wasmo.mediation.InvocationJson
import wasmo.mediation.InvocationQuery
import wasmo.mediation.InvocationReplayer
import wasmo.mediation.InvocationSummary
import wasmo.mediation.InvocationVerification
import wasmo.mediation.ReplayReport
import wasmo.mediation.stableStringify

/**
 * The owner's view of what an app actually did.
 *
 * Wasmo records every effect an app has, but a recording nobody can reach is not accountability.
 * These paths hand the record back to the person who owns the computer, in the same format the
 * `moose audit` commands read, so they can check the OS's claims on hardware the OS does not run on:
 *
 *  * `GET /.wasmo/invocations` lists what the app has done, newest first.
 *  * `GET /.wasmo/invocations/{id}` exports one complete, independently verifiable record.
 *  * `POST /.wasmo/invocations/{id}/replay` re-runs it against a sealed platform and reports
 *    whether the app is still the same program.
 *
 * Everything here is owner-only, and reads exactly like a missing page to anyone else.
 */
class InvocationHttpService(
  private val reader: CapabilityInvocationReader,
  private val replayer: InvocationReplayer,
  private val appSlug: String,
  private val executorFactory: InvocationExecutorFactory,
) {
  suspend fun execute(
    caller: Caller,
    request: Request,
    urlPath: String,
  ): Response<ResponseBody> {
    if (caller.computerAccess != ComputerAccess.Owner) throw NotFoundUserException()

    val rest = urlPath.removePrefix(ReservedPathPrefix)
    val segments = rest.split("/").filter { it.isNotEmpty() }
    return when {
      segments == listOf("invocations") && request.method == "GET" -> list(request)

      segments.size == 2 && segments[0] == "invocations" && request.method == "GET" ->
        export(segments[1])

      segments.size == 3 &&
        segments[0] == "invocations" &&
        segments[2] == "replay" &&
        request.method == "POST" -> replay(segments[1])

      else -> throw NotFoundUserException()
    }
  }

  private suspend fun list(request: Request): Response<ResponseBody> {
    val limit = request.url.queryParameter("limit")?.let {
      it.toIntOrNull()?.takeIf { value -> value in 1..InvocationQuery.MaxLimit }
        ?: throw ArgumentUserException("limit must be an integer in 1..${InvocationQuery.MaxLimit}")
    } ?: DefaultLimit
    val before = request.url.queryParameter("before")?.let {
      try {
        Instant.parse(it)
      } catch (_: IllegalArgumentException) {
        throw ArgumentUserException("before must be an ISO-8601 timestamp")
      }
    }

    val summaries = reader.list(
      InvocationQuery(appSlug = appSlug, startedBefore = before, limit = limit),
    )
    return json(
      jsonObjectOf(
        "invocations" to JsonArray(summaries.map { it.toJson() }),
      ),
    )
  }

  private suspend fun export(invocationId: String): Response<ResponseBody> {
    val invocation = reader.load(invocationId)?.takeIf { it.appSlug == appSlug }
      ?: throw NotFoundUserException()
    return json(InvocationJson.encode(invocation))
  }

  private suspend fun replay(invocationId: String): Response<ResponseBody> {
    val invocation = reader.load(invocationId)?.takeIf { it.appSlug == appSlug }
      ?: throw NotFoundUserException()
    val report = replayer.replay(invocation, executorFactory.create(invocation.kind))
    return json(report.toJson())
  }

  private fun json(body: JsonElement): Response<ResponseBody> = Response(
    contentType = ApplicationJson,
    body = ResponseBody { sink -> sink.writeUtf8(stableStringify(body)) },
  )

  companion object {
    /**
     * Reserved for the OS on every app's own host.
     *
     * The OS answers these paths before it looks at the app's static files or hands the request to
     * the app, so an app cannot shadow, forge, or observe requests for its own audit record.
     */
    const val ReservedPathPrefix = "/.wasmo/"

    private const val DefaultLimit = 50
    private val ApplicationJson = "application/json; charset=utf-8".toMediaType()
  }
}

/** Rebuilds the app-side execution of a recorded invocation so the replayer can re-run it. */
fun interface InvocationExecutorFactory {
  fun create(kind: wasmo.mediation.InvocationKind): wasmo.mediation.InvocationExecutor
}

private fun InvocationSummary.toJson(): JsonObject = jsonObjectOf(
  "appSlug" to JsonPrimitive(appSlug),
  "appVersion" to JsonPrimitive(appVersion),
  "auditCount" to JsonPrimitive(auditCount),
  "auditHead" to JsonPrimitive(auditHead),
  "callerJson" to JsonPrimitive(callerJson),
  "capabilityCalls" to JsonPrimitive(journalCount),
  "completedAt" to JsonPrimitive(completedAt.toString()),
  "failureType" to (failureType?.let(::JsonPrimitive) ?: JsonNull),
  "id" to JsonPrimitive(id),
  "kind" to JsonPrimitive(kind.name),
  "startedAt" to JsonPrimitive(startedAt.toString()),
)

private fun ReplayReport.toJson(): JsonObject = jsonObjectOf(
  "divergence" to when (this) {
    is ReplayReport.Deterministic -> JsonNull
    is ReplayReport.Divergent -> divergence.toJson()
  },
  "invocationId" to JsonPrimitive(invocationId),
  "outcome" to JsonPrimitive(
    when (this) {
      is ReplayReport.Deterministic -> "deterministic"
      is ReplayReport.Divergent -> "divergent"
    },
  ),
  "replayAuditHead" to when (this) {
    is ReplayReport.Deterministic -> JsonPrimitive(replayAuditHead)
    is ReplayReport.Divergent -> JsonNull
  },
  "replayedCalls" to when (this) {
    is ReplayReport.Deterministic -> JsonPrimitive(replayedCalls)
    is ReplayReport.Divergent -> JsonNull
  },
  "verification" to verification.toJson(),
)

private fun Divergence.toJson(): JsonObject = jsonObjectOf(
  "kind" to JsonPrimitive(
    when (this) {
      is Divergence.ChangedRequest -> "changed_request"
      is Divergence.UnrecordedCall -> "unrecorded_call"
      is Divergence.UnusedJournalEntries -> "unused_journal_entries"
      is Divergence.ChangedOutput -> "changed_output"
      is Divergence.ChangedOutcome -> "changed_outcome"
    },
  ),
  "message" to JsonPrimitive(message),
)

private fun InvocationVerification.toJson(): JsonObject = when (this) {
  is InvocationVerification.Valid -> jsonObjectOf(
    "auditEntries" to JsonPrimitive(auditEntries),
    "capabilityCalls" to JsonPrimitive(capabilityCalls),
    "head" to JsonPrimitive(head),
    "valid" to JsonPrimitive(true),
  )
  is InvocationVerification.Invalid -> jsonObjectOf(
    "reason" to JsonPrimitive(reason),
    "seq" to (seq?.let(::JsonPrimitive) ?: JsonNull),
    "valid" to JsonPrimitive(false),
  )
}

private fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
  JsonObject(linkedMapOf(*entries))
