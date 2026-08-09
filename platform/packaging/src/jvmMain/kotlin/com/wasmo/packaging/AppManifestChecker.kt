package com.wasmo.packaging

import com.wasmo.identifiers.Capability
import com.wasmo.support.issues.IssueCollector
import com.wasmo.support.issues.issueCheck

/**
 * Validates a manifest against our spec.
 *
 * We do validation here and not in `TOML` parsing because we want to aggregate all errors before
 * reporting any errors.
 *
 * We also want to exactly identify where the problems are, such as `route[3].resource_path` which
 * isn't possible in simple constructor argument checks.
 */
class AppManifestChecker(
  val allowExternalResources: Boolean = false,
) {
  context(issueCollector: IssueCollector)
  fun check(manifest: AppManifest) {
    context(issueCollector.href("target")) {
      issueCheck(manifest.target in SupportedTargets) {
        """
        |unsupported target '${manifest.target}'
        |expected one of $SupportedTargets
        """.trimMargin()
      }
    }

    context(issueCollector.href("version")) {
      issueCheck(manifest.version >= 1) {
        """
        |unexpected version ${manifest.version}
        |expected a positive integer
        """.trimMargin()
      }
    }

    if (allowExternalResources) {
      for ((index, resource) in manifest.external_resource.withIndex()) {
        context(issueCollector.href("external_resource[$index]")) {
          check(resource)
        }
      }
    } else {
      context(issueCollector.href("external_resource")) {
        issueCheck(manifest.external_resource.isEmpty()) {
          "external resources are not permitted for this manifest"
        }
      }
    }

    val declaredNames = mutableSetOf<String>()
    for ((index, capability) in manifest.capability.withIndex()) {
      context(issueCollector.href("capability[$index]")) {
        check(capability, declaredNames)
      }
    }

    val launcher = manifest.launcher
    if (launcher != null) {
      context(issueCollector.href("launcher")) {
        check(launcher)
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(
    declaration: CapabilityDeclaration,
    declaredNames: MutableSet<String>,
  ) {
    val capability = declaration.capability

    context(issueCollector.href("name")) {
      issueCheck(capability != null) {
        """
        |unknown capability '${declaration.name}'
        |expected one of ${Capability.ids}
        """.trimMargin()
      }

      issueCheck(declaredNames.add(declaration.name)) {
        "capability '${declaration.name}' is declared more than once"
      }
    }

    if (capability == null) return

    if (declaration.allow.isNotEmpty() && !capability.scope.isScopable) {
      context(issueCollector.href("allow")) {
        issueCheck(false) {
          "the '${capability.id}' capability cannot be narrowed by an allow list"
        }
      }
      return
    }

    for ((index, pattern) in declaration.allow.withIndex()) {
      context(issueCollector.href("allow[$index]")) {
        issueCheck(pattern.isNotBlank()) {
          "expected a ${capability.scope.label} pattern"
        }
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(externalResource: ExternalResource) {
    context(issueCollector.href("to")) {
      issueCheck(".." !in externalResource.to.split("/")) {
        "target directory must not contain '..' path traversal operators"
      }

      issueCheck(externalResource.to.startsWith("/")) {
        "target directory must start with '/'"
      }

      for ((index, include) in externalResource.include.withIndex()) {
        context(issueCollector.href("include[$index]")) {
          issueCheck(!include.startsWith("/")) {
            "include must not start with '/'"
          }
        }
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(launcher: Launcher) {
    val maskableIconPath = launcher.maskable_icon_path
    if (maskableIconPath != null) {
      context(issueCollector.href("maskable_icon_path")) {
        checkPath(
          path = maskableIconPath,
        )
      }
    }

    val homePath = launcher.home_path
    if (homePath != null) {
      context(issueCollector.href("home_path")) {
        checkPath(
          path = homePath,
        )
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun checkPath(
    path: String,
    allowTrailingWildcard: Boolean = false,
    requireTrailingWildcard: Boolean = false,
  ) {
    issueCheck(path.startsWith("/")) {
      "string must start with /"
    }

    issueCheck(!path.startsWith("//")) {
      "string must not start with //"
    }

    issueCheck(!requireTrailingWildcard || path.endsWith("/**")) {
      "string must end with '/**'"
    }

    if (allowTrailingWildcard) {
      issueCheck("*" !in path.removeSuffix("/**")) {
        "string may not contain '*', except in a wildcard at the end"
      }
    } else {
      issueCheck("*" !in path) {
        "string may not contain '*'"
      }
    }
  }
}

const val TargetSdk1 = "https://wasmo.com/sdk/1"
internal val SupportedTargets = setOf(TargetSdk1)
internal val SupportedAccessValues = setOf(
  "public",
  "private",
)
