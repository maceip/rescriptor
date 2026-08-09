package com.wasmo.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.time.Instant
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import wasmo.mediation.AuditEntry
import wasmo.mediation.CapabilityInvocation
import wasmo.mediation.InvocationJson
import wasmo.mediation.InvocationVerification
import wasmo.mediation.verifyInvocation

/**
 * Checks what an app did, without trusting the computer that recorded it.
 *
 * An invocation exported from `GET /.wasmo/invocations/{id}` carries its own proof: the audit chain
 * and the capability journal have to agree with each other and with the recorded outcome. That
 * check needs no server, no database, and no network, which is the point — the person who owns a
 * Wasmo can run it on their own laptop and get an answer the operator cannot influence.
 */
class AuditCommand : CliktCommand(name = "audit") {
  override fun run() = Unit
}

class AuditVerifyCommand : CliktCommand(name = "verify") {
  private val invocationFile: Path by argument()
    .path(mustExist = true, canBeDir = false)
    .help("an invocation exported from /.wasmo/invocations/{id}")

  private val quiet: Boolean by option("--quiet", "-q", help = "print nothing on success")
    .flag()

  override fun run() {
    val invocation = invocationFile.readInvocation()
    when (val verification = verifyInvocation(invocation)) {
      is InvocationVerification.Valid -> {
        if (!quiet) {
          echo("verified ${invocation.id}")
          echo("  app             ${invocation.appSlug} version ${invocation.appVersion}")
          echo("  capability calls ${verification.capabilityCalls}")
          echo("  audit entries    ${verification.auditEntries}")
          echo("  head             ${verification.head}")
        }
      }

      is InvocationVerification.Invalid -> {
        echo("FAILED ${invocation.id}", err = true)
        val at = verification.seq?.let { " at audit entry $it" } ?: ""
        echo("  ${verification.reason}$at", err = true)
        throw ProgramResult(1)
      }
    }
  }
}

class AuditShowCommand : CliktCommand(name = "show") {
  private val invocationFile: Path by argument()
    .path(mustExist = true, canBeDir = false)
    .help("an invocation exported from /.wasmo/invocations/{id}")

  override fun run() {
    val invocation = invocationFile.readInvocation()
    echo("${invocation.id}  ${invocation.kind}  ${invocation.appSlug}@${invocation.appVersion}")
    echo("caller  ${invocation.callerJson}")
    echo("started ${invocation.startedAt}")
    echo("ended   ${invocation.completedAt}")
    invocation.failure?.let { echo("failed  ${it.type}: ${it.message.orEmpty()}") }
    echo("")

    for (entry in invocation.audit) {
      echo("${entry.timestamp()}  ${entry.kind.padEnd(MaxKindWidth)}  ${entry.describe()}")
    }
    echo("")
    echo("head ${invocation.audit.lastOrNull()?.hash ?: "(none)"}")

    // A timeline nobody checked is just a story the server told, so never print one alone.
    when (val verification = verifyInvocation(invocation)) {
      is InvocationVerification.Valid -> echo("verified: chain and journal agree")
      is InvocationVerification.Invalid -> {
        echo("NOT VERIFIED: ${verification.reason}", err = true)
        throw ProgramResult(1)
      }
    }
  }

  private fun AuditEntry.timestamp(): String =
    Instant.fromEpochMilliseconds(timestampMillis).toString()

  /** Audit details are `capability.method:request`; the request is usually the uninteresting half. */
  private fun AuditEntry.describe(): String {
    val summary = detail.substringBefore(':').ifEmpty { detail }
    return "$summary  ${resultHash.take(ShortHashLength)}"
  }

  private companion object {
    const val MaxKindWidth = 18
    const val ShortHashLength = 12
  }
}

private fun Path.readInvocation(): CapabilityInvocation {
  val json = FileSystem.SYSTEM.read(toOkioPath()) { readUtf8() }
  return try {
    InvocationJson.decodeFromString(json)
  } catch (failure: IllegalArgumentException) {
    throw CliktError("$this is not a Wasmo invocation: ${failure.message}")
  }
}

fun auditCommand(): CliktCommand = AuditCommand().subcommands(
  AuditVerifyCommand(),
  AuditShowCommand(),
)
