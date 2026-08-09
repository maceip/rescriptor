package com.wasmo.packaging

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.wasmo.identifiers.Capability
import com.wasmo.support.issues.IssueCollector
import kotlin.test.Test

class CapabilityDeclarationTest {
  private val manifest = AppManifest(
    target = TargetSdk1,
    version = 1,
  )

  @Test
  fun aManifestThatDeclaresNothingKeepsWorking() {
    assertThat(manifest.declaresCapabilities).isFalse()
    assertThat(IssueCollector.collect { AppManifestChecker().check(manifest) }).containsExactly()
  }

  @Test
  fun declaredCapabilitiesAreAccepted() {
    val declared = manifest.copy(
      capability = listOf(
        CapabilityDeclaration(name = "clock"),
        CapabilityDeclaration(name = "http", allow = listOf("https://api.weather.test/**")),
        CapabilityDeclaration(name = "sql"),
      ),
    )

    assertThat(declared.declaresCapabilities).isTrue()
    assertThat(IssueCollector.collect { AppManifestChecker().check(declared) }).containsExactly()
    assertThat(declared.capability[1].capability).isEqualTo(Capability.Http)
  }

  @Test
  fun anUnknownCapabilityIsRejected() {
    assertThat(
      manifest.copy(capability = listOf(CapabilityDeclaration(name = "filesystem"))),
    ).failsValidation(
      message = """
        |unknown capability 'filesystem'
        |expected one of ${Capability.ids}
      """.trimMargin(),
      href = "capability[0].name",
    )
  }

  @Test
  fun aDuplicateCapabilityIsRejected() {
    assertThat(
      manifest.copy(
        capability = listOf(
          CapabilityDeclaration(name = "http"),
          CapabilityDeclaration(name = "http", allow = listOf("https://elsewhere.test/**")),
        ),
      ),
    ).failsValidation(
      message = "capability 'http' is declared more than once",
      href = "capability[1].name",
    )
  }

  @Test
  fun anAllowListOnACapabilityWithNothingToNarrowIsRejected() {
    assertThat(
      manifest.copy(
        capability = listOf(CapabilityDeclaration(name = "clock", allow = listOf("anything"))),
      ),
    ).failsValidation(
      message = "the 'clock' capability cannot be narrowed by an allow list",
      href = "capability[0].allow",
    )
  }

  @Test
  fun aBlankPatternIsRejected() {
    assertThat(
      manifest.copy(
        capability = listOf(CapabilityDeclaration(name = "object_store", allow = listOf(" "))),
      ),
    ).failsValidation(
      message = "expected a object key pattern",
      href = "capability[0].allow[0]",
    )
  }

  @Test
  fun everyCapabilityCanBeNamedInAManifest() {
    val declared = manifest.copy(
      capability = Capability.entries.map { CapabilityDeclaration(name = it.id) },
    )

    assertThat(IssueCollector.collect { AppManifestChecker().check(declared) }).containsExactly()
  }
}
