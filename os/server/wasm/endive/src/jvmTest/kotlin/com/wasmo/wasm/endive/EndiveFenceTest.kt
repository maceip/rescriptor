package com.wasmo.wasm.endive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonNull
import run.endive.wasm.Parser

/**
 * A wall-clock timeout has to end an invocation's *effects*, not just stop waiting for it.
 *
 * Interrupting a guest only works between instructions. A guest parked inside a host call — an HTTP
 * fetch, a slow query — never sees the interrupt, so without a fence it would wake up after its
 * invocation had already been sealed and persisted and carry on calling capabilities. Those calls
 * would be real effects that appear in no audit record, which is the one thing the record is
 * supposed to make impossible.
 */
class EndiveFenceTest {
  @Test
  fun aGuestThatOutlivesItsTimeoutCannotUseTheHostAgain() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()

    val runtime = EndiveRuntime(
      module = Parser.parse(twoCapabilityCallsModule()),
      capabilityHost = {
        calls.incrementAndGet()
        entered.countDown()
        // Models host work that does not answer to interrupts, such as a socket read.
        var released = false
        while (!released) {
          released = try {
            release.await(20, TimeUnit.MILLISECONDS)
          } catch (_: InterruptedException) {
            false
          }
        }
        JsonNull
      },
      randomBytes = { ByteArray(it) },
      limits = EndiveLimits(timeoutMillis = 100L),
    )

    assertFailsWith<EndiveExecutionTimeoutException> { runtime.invoke("run", "") }

    assertEquals(true, entered.await(2, TimeUnit.SECONDS))
    assertEquals(1, calls.get())

    // The guest wakes up and tries its second capability call. It must not land.
    release.countDown()
    Thread.sleep(200L)
    assertEquals(1, calls.get())
  }

  @Test
  fun aClosedRuntimeRefusesFurtherWork() {
    val runtime = EndiveRuntime(
      module = Parser.parse(twoCapabilityCallsModule()),
      capabilityHost = { JsonNull },
      randomBytes = { ByteArray(it) },
    )
    runtime.close()

    assertFailsWith<IllegalStateException> { runtime.invoke("run", "") }
  }

  /**
   * ```wat
   * (module
   *   (import "wasmo_host_v1" "cap_call" (func $cap (param i32 i32) (result i32)))
   *   (memory 1)
   *   (data (i32.const 0) "{}")
   *   (func (export "run")
   *     i32.const 0 i32.const 2 call $cap drop
   *     i32.const 0 i32.const 2 call $cap drop))
   * ```
   */
  private fun twoCapabilityCallsModule(): ByteArray = bytes(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    // Types: (i32, i32) -> i32, and () -> ().
    0x01, 0x0a, 0x02, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f, 0x60, 0x00, 0x00,
    // Import: wasmo_host_v1.cap_call
    0x02, 0x1a, 0x01,
    0x0d, 0x77, 0x61, 0x73, 0x6d, 0x6f, 0x5f, 0x68, 0x6f, 0x73, 0x74, 0x5f, 0x76, 0x31,
    0x08, 0x63, 0x61, 0x70, 0x5f, 0x63, 0x61, 0x6c, 0x6c,
    0x00, 0x00,
    // One local function of type () -> ().
    0x03, 0x02, 0x01, 0x01,
    // One page of memory, so the host has somewhere to read the request from.
    0x05, 0x03, 0x01, 0x00, 0x01,
    // Export "run".
    0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6e, 0x00, 0x01,
    // Body: two capability calls, each result dropped.
    0x0a, 0x12, 0x01, 0x10, 0x00,
    0x41, 0x00, 0x41, 0x02, 0x10, 0x00, 0x1a,
    0x41, 0x00, 0x41, 0x02, 0x10, 0x00, 0x1a,
    0x0b,
    // Data: the two byte request '{}'.
    0x0b, 0x08, 0x01, 0x00, 0x41, 0x00, 0x0b, 0x02, 0x7b, 0x7d,
  )

  private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
