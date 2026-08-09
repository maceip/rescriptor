package com.wasmo.wasm.endive

import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicLong
import okio.ByteString
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule

/**
 * Parses each app release's Wasm once instead of once per request.
 *
 * Wasm parsing and validation is the expensive half of starting a guest, and it is pure: a
 * [WasmModule] is immutable and is designed to be instantiated many times. Instances are emphatically
 * *not* cached — each invocation still gets its own linear memory, globals, and tables, because
 * sharing those between requests would leak one caller's data into another's.
 *
 * Entries are keyed by the release's exact bytes, so publishing a new release is enough to
 * invalidate it; there is no version to keep in sync and no way to serve a stale module.
 */
@Inject
@SingleIn(OsScope::class)
class EndiveModuleCache(
  private val maximumEntries: Int = DefaultMaximumEntries,
) {
  private val hits = AtomicLong()
  private val misses = AtomicLong()

  /**
   * Access-ordered so eviction drops the app nobody is using, and synchronized rather than
   * concurrent because a hit is a hash lookup while a miss is milliseconds of parsing: contention
   * on the map is not the cost that matters here.
   */
  private val modules = object : LinkedHashMap<ByteString, WasmModule>(
    16,
    0.75f,
    true,
  ) {
    override fun removeEldestEntry(eldest: Map.Entry<ByteString, WasmModule>): Boolean =
      size > maximumEntries
  }

  val statistics: Statistics
    get() = Statistics(hits = hits.get(), misses = misses.get(), size = synchronized(this) { modules.size })

  fun get(wasm: ByteString): WasmModule {
    synchronized(this) { modules[wasm] }?.let {
      hits.incrementAndGet()
      return it
    }

    // Parsed outside the lock: a slow parse of one app must not stall every other app's requests.
    // Two concurrent misses for the same release parse twice and agree on the result, which is
    // cheaper than holding a global lock for the duration of a parse.
    val module = Parser.parse(wasm.toByteArray())
    misses.incrementAndGet()
    synchronized(this) { modules.putIfAbsent(wasm, module) }
    return module
  }

  data class Statistics(
    val hits: Long,
    val misses: Long,
    val size: Int,
  )

  companion object {
    const val DefaultMaximumEntries: Int = 64
  }
}
