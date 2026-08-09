package com.wasmo.wasm.endive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonNull
import okio.ByteString.Companion.toByteString

class EndiveModuleCacheTest {
  @Test
  fun theSameReleaseIsParsedOnce() {
    val cache = EndiveModuleCache()
    val wasm = emptyModule().toByteString()

    val first = cache.get(wasm)
    val second = cache.get(wasm)

    assertSame(first, second)
    assertEquals(1L, cache.statistics.misses)
    assertEquals(1L, cache.statistics.hits)
  }

  @Test
  fun aNewReleaseIsNotServedFromTheOldOne() {
    val cache = EndiveModuleCache()

    val first = cache.get(emptyModule().toByteString())
    val second = cache.get(exportingModule().toByteString())

    assertNotSame(first, second)
    assertEquals(2, cache.statistics.size)
  }

  @Test
  fun theCacheIsBounded() {
    val cache = EndiveModuleCache(maximumEntries = 1)

    cache.get(emptyModule().toByteString())
    cache.get(exportingModule().toByteString())

    assertEquals(1, cache.statistics.size)
  }

  /**
   * The module is shared; the instance never is. Two runtimes built from one cached module must not
   * see each other, or one request would read another's linear memory.
   */
  @Test
  fun runtimesBuiltFromOneCachedModuleDoNotShareState() {
    val cache = EndiveModuleCache()
    val wasm = exportingModule().toByteString()
    val module = cache.get(wasm)

    val first = EndiveRuntime(module, { JsonNull }, ::zeroBytes)
    val second = EndiveRuntime(cache.get(wasm), { JsonNull }, ::zeroBytes)
    try {
      assertNotSame(first, second)
      assertEquals(true, first.hasFunctionExport("run"))
      assertEquals(true, second.hasFunctionExport("run"))
    } finally {
      first.close()
      second.close()
    }
  }

  private fun zeroBytes(length: Int) = ByteArray(length)

  /** `(module)` */
  private fun emptyModule(): ByteArray = bytes(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
  )

  /** `(module (func (export "run")))` */
  private fun exportingModule(): ByteArray = bytes(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
    0x03, 0x02, 0x01, 0x00,
    0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6e, 0x00, 0x00,
    0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b,
  )

  private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
