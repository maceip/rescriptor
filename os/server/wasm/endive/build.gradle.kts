import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.metro)
  id("wasmo-build")
}

wasmoBuild {
  libraryJvm()
}

val endiveSourceDirectory = rootProject.layout.projectDirectory.dir("third_party/endive")
val endiveRuntimeJar = endiveSourceDirectory.file("runtime/target/runtime-999-SNAPSHOT.jar")
val endiveWasmJar = endiveSourceDirectory.file("wasm/target/wasm-999-SNAPSHOT.jar")

val buildEndiveSource by tasks.registering(Exec::class) {
  group = "build"
  description = "Builds the pinned Endive alpha runtime from source."
  workingDir(endiveSourceDirectory)
  commandLine(
    "./mvnw",
    "-q",
    "-pl",
    "runtime",
    "-am",
    "-DskipTests",
    "-Dspotless.apply.skip=true",
    "package",
  )
  inputs.files(
    fileTree(endiveSourceDirectory) {
      include("pom.xml", ".mvn/**", "mvnw", "runtime/src/**", "runtime/pom.xml", "wasm/src/**", "wasm/pom.xml")
      exclude("**/target/**")
    },
  )
  outputs.files(endiveRuntimeJar, endiveWasmJar)
}

val endiveSourceJars = files(endiveRuntimeJar, endiveWasmJar).builtBy(buildEndiveSource)
val endiveProbeWasm = rootProject.layout.projectDirectory.file(
  "apps/endive-probe/build/compileSync/wasmWasi/main/productionExecutable/kotlin/wasmo-apps-endive-probe.wasm",
)

kotlin {
  sourceSets {
    val jvmMain by getting {
      dependencies {
        implementation(endiveSourceJars)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okio)
        implementation(projects.identifiers)
        implementation(projects.os.server.identifiers)
        implementation(projects.os.server.wasm.api)
        implementation(projects.os.server.wasm.jvm)
        implementation(projects.platform.api)
        implementation(projects.platform.mediation)
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.platform.mediation)
      }
    }
  }
}

tasks.named<Test>("jvmTest") {
  dependsOn(":apps:endive-probe:compileProductionExecutableKotlinWasmWasi")
  systemProperty("wasmo.endive.probe", endiveProbeWasm.asFile.absolutePath)
}
