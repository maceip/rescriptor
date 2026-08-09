package com.wasmo.packaging

import com.wasmo.identifiers.Capability
import kotlinx.serialization.Serializable

@Serializable
data class AppManifest(
  val target: String,
  val version: Long,
  val external_resource: List<ExternalResource> = listOf(),
  val launcher: Launcher? = null,
  val capability: List<CapabilityDeclaration> = listOf(),
) {
  /**
   * Whether this app has told the OS what it needs.
   *
   * Manifests written before capability declarations existed say nothing, and the OS keeps running
   * them with every capability. Declaring even one capability opts the app into deny-by-default:
   * from then on the manifest is the complete list of what it may do.
   */
  val declaresCapabilities: Boolean
    get() = capability.isNotEmpty()
}

/**
 * One capability an app asks for, and the resources it promises to keep within.
 *
 * [name] is a [Capability] id and [allow] narrows it to matching targets; an omitted or empty
 * [allow] asks for the whole capability. See `docs/platform/capabilities.md` for the manifest
 * syntax and the pattern language.
 */
@Serializable
data class CapabilityDeclaration(
  val name: String,
  val allow: List<String> = listOf(),
) {
  /** Null when [name] is not a capability this version of Wasmo mediates. */
  val capability: Capability?
    get() = Capability.findById(name)
}

@Serializable
data class ExternalResource(
  val from: String,
  val to: String,
  val include: List<String> = listOf(),
)

@Serializable
data class Launcher(
  val label: String? = null,
  val maskable_icon_path: String? = null,
  val home_path: String? = null,
)
