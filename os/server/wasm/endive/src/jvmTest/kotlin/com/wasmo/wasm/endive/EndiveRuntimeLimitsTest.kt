package com.wasmo.wasm.endive

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonNull
import run.endive.wasm.Parser

class EndiveRuntimeLimitsTest {
  @Test
  fun rejectsModuleWhoseInitialMemoryExceedsBudget() {
    assertFailsWith<EndiveMemoryLimitExceededException> {
      EndiveRuntime(
        module = Parser.parse(memoryModule(initialPages = 2)),
        capabilityHost = EndiveCapabilityHost { JsonNull },
        randomBytes = ::zeroBytes,
        limits = EndiveLimits(maxMemoryPages = 1),
      )
    }
  }

  @Test
  fun interruptsGuestAtInstructionBudget() {
    val runtime = EndiveRuntime(
      module = Parser.parse(infiniteLoopModule()),
      capabilityHost = EndiveCapabilityHost { JsonNull },
      randomBytes = ::zeroBytes,
      limits = EndiveLimits(maxInstructions = 100, timeoutMillis = 1_000),
    )
    try {
      assertFailsWith<EndiveInstructionLimitExceededException> {
        runtime.invoke("run", "")
      }
    } finally {
      runtime.close()
    }
  }

  @Test
  fun interruptsGuestAtWallClockBudget() {
    val runtime = EndiveRuntime(
      module = Parser.parse(infiniteLoopModule()),
      capabilityHost = EndiveCapabilityHost { JsonNull },
      randomBytes = ::zeroBytes,
      limits = EndiveLimits(maxInstructions = Long.MAX_VALUE, timeoutMillis = 20),
    )
    assertFailsWith<EndiveExecutionTimeoutException> {
      runtime.invoke("run", "")
    }
  }

  private fun zeroBytes(length: Int) = ByteArray(length)

  /** `(module (memory 2) (func (export "run")))` */
  private fun memoryModule(initialPages: Int): ByteArray = bytes(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
    0x03, 0x02, 0x01, 0x00,
    0x05, 0x03, 0x01, 0x00, initialPages,
    0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6e, 0x00, 0x00,
    0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b,
  )

  /** `(module (func (export "run") (loop br 0)))` */
  private fun infiniteLoopModule(): ByteArray = bytes(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
    0x03, 0x02, 0x01, 0x00,
    0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6e, 0x00, 0x00,
    0x0a, 0x09, 0x01, 0x07, 0x00, 0x03, 0x40, 0x0c, 0x00, 0x0b, 0x0b,
  )

  private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
