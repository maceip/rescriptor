package com.wasmo.wasm

import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.ByteString
import wasmo.app.Platform
import wasmo.app.WasmoApp

/**
 * This app loader requires the `WamsoApp.Factory` instance is callable in-process.
 */
@Inject
@SingleIn(OsScope::class)
class JvmAppLoader(
  private val factories: Map<AppSlug, WasmoApp.Factory>,
) : AppLoader {
  override suspend fun load(
    platform: Platform,
    appSlug: AppSlug,
    wasm: ByteString?,
  ): WasmoApp? {
    require(wasm == null) {
      "JvmAppLoader cannot execute app.wasm; route Wasm packages through EndiveAppLoader"
    }
    return factories[appSlug]?.create(platform)
  }

  suspend fun load(
    platform: Platform,
    appSlug: AppSlug,
  ): WasmoApp? = load(platform, appSlug, wasm = null)
}
