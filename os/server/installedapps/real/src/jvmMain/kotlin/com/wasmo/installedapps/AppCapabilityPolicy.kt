package com.wasmo.installedapps

import com.wasmo.packaging.AppManifest
import wasmo.mediation.CapabilityGrant
import wasmo.mediation.CapabilityPolicy
import wasmo.mediation.GrantedCapabilityPolicy

/**
 * The policy that holds an app to what its manifest declared.
 *
 * A manifest that declares nothing keeps the behaviour apps had before capabilities existed: the OS
 * grants everything. Declaring even one capability is the app's statement that the list is
 * complete, and the OS then denies everything else.
 *
 * Unknown capability names are dropped rather than granted. `AppManifestChecker` already rejects
 * them at package time, so reaching here means either a manifest that skipped validation or one
 * written for a newer Wasmo — and in both cases refusing is the safe reading.
 */
fun AppManifest.capabilityPolicy(): CapabilityPolicy = when {
  !declaresCapabilities -> CapabilityPolicy.AllowAll
  else -> GrantedCapabilityPolicy(
    capability.mapNotNull { declaration ->
      declaration.capability?.let { CapabilityGrant(capability = it, allow = declaration.allow) }
    },
  )
}
