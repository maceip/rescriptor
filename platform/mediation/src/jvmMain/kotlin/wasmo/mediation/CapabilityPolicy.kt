package wasmo.mediation

import com.wasmo.identifiers.Capability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One mediated effect, described before it is allowed to reach the platform.
 *
 * [capability] and [method] are the journal's identifiers, so a policy decision and a journal entry
 * always describe exactly the same call.
 */
data class CapabilityCall(
  val capability: String,
  val method: String,
  val request: JsonElement,
) {
  val id: String
    get() = "$capability.$method"

  /** The capability family this call belongs to, or null if the call is outside the vocabulary. */
  val family: Capability?
    get() = FamiliesByPrefix[capability]

  /**
   * Whether an allow list is checked against this call.
   *
   * Only the calls that first hand a named resource to an app are gates. Everything downstream of a
   * gate — a SQL connection, a statement on that connection, a column read from a row of that
   * statement's results — is reachable only by passing through the gate, so checking the gate is
   * both necessary and sufficient.
   */
  val isGate: Boolean
    get() = id in GateIds

  /**
   * The single value an allow list constrains for this call.
   *
   * Null on a call that is not a gate means "already constrained upstream". Null on a call that
   * *is* a gate means the request did not carry the argument this capability is scoped by, which
   * only a malformed request can produce — and which [GrantedCapabilityPolicy] denies rather than
   * waves through.
   */
  val target: String?
    get() = when (id) {
      "http.fetch" -> request.stringMember("url")
      "downloader.download" -> request.member("httpRequest")?.stringMember("url")
      "objectStore.put", "objectStore.get", "objectStore.delete" -> request.stringMember("key")
      // A list with no prefix walks the whole store, which only an unrestricted grant allows.
      "objectStore.list" -> request.stringMember("prefix") ?: ""
      "jobs.enqueue", "jobs.cancel" -> request.stringMember("queue")
      "sql.getOrCreate" -> request.stringMember("name")
      else -> null
    }

  private companion object {
    /**
     * Journal capability prefixes to the family a person sees. `sql.row` is the journal's name for
     * reads from a result set, which is part of the app's SQL capability.
     */
    val FamiliesByPrefix: Map<String, Capability> = mapOf(
      "clock" to Capability.Clock,
      "random" to Capability.Random,
      "http" to Capability.Http,
      "objectStore" to Capability.ObjectStore,
      "downloader" to Capability.Downloader,
      "jobs" to Capability.Jobs,
      "sql" to Capability.Sql,
      "sql.row" to Capability.Sql,
    )

    val GateIds: Set<String> = setOf(
      "http.fetch",
      "downloader.download",
      "objectStore.put",
      "objectStore.get",
      "objectStore.delete",
      "objectStore.list",
      "jobs.enqueue",
      "jobs.cancel",
      "sql.getOrCreate",
    )
  }
}

sealed interface CapabilityDecision {
  data object Allow : CapabilityDecision

  /** [reason] is written into the audit chain and returned to the app, so it must be specific. */
  data class Deny(val reason: String) : CapabilityDecision
}

fun interface CapabilityPolicy {
  fun decide(call: CapabilityCall): CapabilityDecision

  companion object {
    /** Grants everything. Correct only for built-in apps that the OS already trusts. */
    val AllowAll = CapabilityPolicy { CapabilityDecision.Allow }
  }
}

class CapabilityDeniedException(
  val capability: String,
  val reason: String,
) : IllegalStateException("capability denied: $capability ($reason)")

/**
 * One capability an app declared, optionally narrowed to the resources it named.
 *
 * An empty [allow] grants the whole family. A non-empty [allow] grants only calls whose target
 * matches one of its patterns.
 */
data class CapabilityGrant(
  val capability: Capability,
  val allow: List<String> = listOf(),
) {
  init {
    require(allow.isEmpty() || capability.scope.isScopable) {
      "the '${capability.id}' capability cannot be narrowed by an allow list"
    }
    require(allow.none { it.isBlank() }) {
      "the '${capability.id}' allow list contains a blank pattern"
    }
  }

  /** For example, "make outbound HTTP requests to https://api.test, and below". */
  fun describe(): String = when {
    allow.isEmpty() -> capability.summary
    else -> "${capability.summary} to ${allow.joinToString(", ")}"
  }
}

/**
 * Denies every capability an app did not declare.
 *
 * This is the runtime half of the manifest's `[[capability]]` blocks. It fails closed: an unknown
 * call, an undeclared family, and a target outside a declared allow list are all denials, and each
 * one is journaled and hash-chained rather than silently dropped.
 */
class GrantedCapabilityPolicy(
  val grants: List<CapabilityGrant>,
) : CapabilityPolicy {
  /** Null patterns mean the whole family was granted. */
  private val patterns: Map<Capability, List<CapabilityPattern>?> = buildMap {
    for (grant in grants) {
      require(grant.capability !in this) {
        "the '${grant.capability.id}' capability is declared more than once"
      }
      put(
        grant.capability,
        grant.allow.takeIf { it.isNotEmpty() }?.map(::CapabilityPattern),
      )
    }
  }

  override fun decide(call: CapabilityCall): CapabilityDecision {
    val family = call.family
      ?: return CapabilityDecision.Deny("'${call.id}' is not a Wasmo capability")

    if (family !in patterns) {
      return CapabilityDecision.Deny(
        "the app manifest does not declare the '${family.id}' capability",
      )
    }

    val allowed = patterns[family] ?: return CapabilityDecision.Allow
    if (!call.isGate) return CapabilityDecision.Allow

    val scope = family.scope.label ?: "target"
    val target = call.target
      ?: return CapabilityDecision.Deny("'${call.id}' carries no $scope to check")
    if (allowed.any { it.matches(target) }) return CapabilityDecision.Allow

    return CapabilityDecision.Deny(
      "$scope '$target' is not in the '${family.id}' allow list",
    )
  }
}

/**
 * A glob over one capability target.
 *
 * A `*` matches any run of characters within a path segment, and a `**` segment matches any number
 * of segments including none. Matching is case-sensitive and anchored at both ends, so a pattern
 * that does not describe a target exactly denies it. That direction matters: at a security
 * boundary an ambiguous pattern has to fail closed.
 *
 * The same syntax reads naturally for URLs and for object keys, because neither wildcard crosses a
 * path separator unintentionally. See `docs/platform/capabilities.md` for worked examples.
 */
class CapabilityPattern(
  val pattern: String,
) {
  init {
    require(pattern.isNotBlank()) { "capability pattern must not be blank" }
  }

  // 'https://api.test' + '/**' matches 'https://api.test/v1/x' but not 'https://api.test.evil/',
  // and 'photos' + '/**' matches 'photos/2026/a.jpg' but not 'photosets/a.jpg'.
  private val regex: Regex = Regex(
    when (pattern) {
      // The one pattern that is not a sequence of segments: it names every target there is.
      "**" -> ".*"
      else -> buildString {
        var first = true
        for (segment in pattern.split("/")) {
          // '**' absorbs the '/' beside it, so it must not also advance past the first segment.
          if (segment == "**") {
            append(if (first) "(.*/)?" else "(/.*)?")
            continue
          }
          if (!first) append("/")
          for ((index, part) in segment.split("*").withIndex()) {
            if (index > 0) append("[^/]*")
            if (part.isNotEmpty()) append(Regex.escape(part))
          }
          first = false
        }
      }
    },
  )

  fun matches(target: String): Boolean = regex.matches(target)

  override fun toString(): String = pattern
}

private fun JsonElement.member(name: String): JsonElement? =
  (this as? JsonObject)?.get(name)?.takeIf { it !== JsonNull }

private fun JsonElement.stringMember(name: String): String? =
  (member(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
