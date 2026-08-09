package com.wasmo.installedapps

import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import com.wasmo.identifiers.Deployment
import com.wasmo.identifiers.DistributionShortCode
import com.wasmo.wasm.AppLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.FakePlatform
import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.sql.FakeSqlService

class RealInstalledAppServiceTest {
  @Test
  fun appPassesPackagedWasmToLoader() = runTest {
    val packagedWasm = "wasm bytes".encodeUtf8()
    val expectedApp = object : WasmoApp() {}
    var loadedPlatform: Platform? = null
    var loadedWasm: ByteString? = null
    val platform = FakePlatform(FakeSqlService("loader_test"))
    val loader = object : AppLoader {
      override suspend fun load(
        platform: Platform,
        appSlug: AppSlug,
        wasm: ByteString?,
      ): WasmoApp {
        assertEquals(AppSlug, appSlug)
        loadedPlatform = platform
        loadedWasm = wasm!!
        return expectedApp
      }
    }
    val resources = object : ResourceLoader {
      override suspend fun loadOrNull(resourcePath: String) = when (resourcePath) {
        "/app.wasm" -> packagedWasm
        else -> error("unexpected resource: $resourcePath")
      }
    }
    val service = RealInstalledAppService(
      deployment = Deployment(
        baseUrl = "https://wasmo.test/".toHttpUrl(),
        sendFromEmailAddress = "test@wasmo.test",
        distributionShortCode = DistributionShortCode("ft"),
      ),
      computerSlug = ComputerSlug("computer"),
      httpServiceProvider = lazy { error("unused") },
      loader = loader,
      resourceLoaderFactory = object : ResourceLoader.Factory {
        override fun create(): ResourceLoader = resources
      },
      slug = AppSlug,
      appManifestLoader = object : AppManifestLoader {
        override suspend fun load() = error("unused")
      },
      platform = platform,
    )

    assertSame(expectedApp, service.app(platform))
    assertSame(platform, loadedPlatform)
    assertEquals(packagedWasm, loadedWasm)
  }

  private companion object {
    val AppSlug = AppSlug("endiveprobe")
  }
}
