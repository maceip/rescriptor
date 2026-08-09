package com.wasmo.wasm.endive

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import run.endive.runtime.HostFunction
import run.endive.runtime.Instance
import run.endive.runtime.Store
import run.endive.wasm.WasmModule
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.ValType

fun interface EndiveCapabilityHost {
  fun call(request: JsonElement): JsonElement
}

data class EndiveLimits(
  val maxInstructions: Long = 10_000_000L,
  val maxMemoryPages: Int = 1_024,
  val timeoutMillis: Long = 5_000L,
  val maxInputBytes: Int = 1 * 1_024 * 1_024,
  val maxOutputBytes: Int = 1 * 1_024 * 1_024,
  val maxCapabilityRequestBytes: Int = 1 * 1_024 * 1_024,
  val maxCapabilityResultBytes: Int = 1 * 1_024 * 1_024,
  /**
   * How many instructions may run between checks for a cancelled invocation.
   *
   * The execution listener runs on the interpreter's hot path for every instruction, so what it
   * does there is a direct multiplier on guest speed. Counting is unavoidable, but polling the
   * thread's interrupt flag is not: doing it on a power-of-two boundary keeps the check to a mask
   * and a branch while still bounding how long a cancelled guest can keep running to a few
   * microseconds.
   */
  val interruptCheckInstructions: Int = 8_192,
) {
  init {
    require(maxInstructions > 0)
    require(maxMemoryPages in 1..MemoryLimits.MAX_PAGES)
    require(timeoutMillis > 0)
    require(maxInputBytes > 0)
    require(maxOutputBytes > 0)
    require(maxCapabilityRequestBytes > 0)
    require(maxCapabilityResultBytes > 0)
    require(interruptCheckInstructions > 0 && interruptCheckInstructions.countOneBits() == 1) {
      "interruptCheckInstructions must be a power of two but was $interruptCheckInstructions"
    }
  }
}

class EndiveInstructionLimitExceededException(limit: Long) :
  IllegalStateException("Endive instruction limit exceeded: $limit")

class EndiveExecutionTimeoutException(limitMillis: Long) :
  IllegalStateException("Endive execution timed out after ${limitMillis}ms")

class EndiveMemoryLimitExceededException(initialPages: Int, maximumPages: Int) :
  IllegalStateException(
    "Endive module requires $initialPages initial memory pages; limit is $maximumPages",
  )

class EndivePayloadLimitExceededException(kind: String, actual: Int, maximum: Int) :
  IllegalArgumentException("Endive $kind is $actual bytes; limit is $maximum")

/** Thrown when a guest that outlived its invocation tries to keep using the host. */
class EndiveFencedException :
  IllegalStateException("Endive guest is fenced; its invocation has already ended")

/**
 * One isolated, bounded Endive instance of a Wasmo app module.
 *
 * Endive is built from the pinned source checkout in `third_party/endive`; this class has no
 * dependency on a published Endive artifact or alternate runtime.
 *
 * The [module] may be shared with other runtimes — it is immutable — but the [Instance] built from
 * it never is. One runtime serves one invocation.
 */
class EndiveRuntime(
  module: WasmModule,
  private val capabilityHost: EndiveCapabilityHost,
  private val randomBytes: (Int) -> ByteArray,
  private val limits: EndiveLimits = EndiveLimits(),
) : AutoCloseable {
  private val lock = Any()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "wasmo-endive").apply { isDaemon = true }
  }
  private var input = ByteArray(0)
  private var capabilityResult = ByteArray(0)
  private var output: String? = null
  private var closed = false

  /**
   * Set before this runtime stops waiting for a guest, and read by the guest's own thread.
   *
   * A wall-clock timeout interrupts the guest, but an interrupt is only observed between
   * instructions: a guest parked inside a host call keeps running until that call returns. Without
   * this fence such a guest could go on issuing capability calls after its invocation had already
   * been sealed and persisted, producing effects that appear in no audit record. Fencing closes
   * that window, so the record of an invocation is complete even when the invocation timed out.
   */
  @Volatile private var fenced = false

  /**
   * Only ever written by the single execution thread, and read by it alone. The happens-before edge
   * from submitting each task publishes the reset, so this does not need to be atomic — and on the
   * interpreter's hot path, not being atomic is the point.
   */
  private var instructionCount = 0L

  private var instanceOrNull: Instance? = null
  private val instance: Instance
    get() = instanceOrNull ?: error("Endive runtime was never instantiated")
  private val functionExports: Set<String>

  init {
    functionExports = buildSet {
      val exports = module.exportSection()
      for (index in 0 until exports.exportCount()) {
        val export = exports.getExport(index)
        if (export.exportType() == ExternalType.FUNCTION) add(export.name())
      }
    }
    val moduleMemory = module.memorySection().orElse(null)?.let { section ->
      require(section.memoryCount() <= 1) { "Wasmo ABI supports at most one guest memory" }
      section.takeIf { it.memoryCount() == 1 }?.getMemory(0)?.limits()
    }
    if (moduleMemory != null && moduleMemory.initialPages() > limits.maxMemoryPages) {
      throw EndiveMemoryLimitExceededException(
        initialPages = moduleMemory.initialPages(),
        maximumPages = limits.maxMemoryPages,
      )
    }
    val boundedMemory = moduleMemory?.let {
      MemoryLimits(
        it.initialPages(),
        minOf(it.maximumPages(), limits.maxMemoryPages),
        it.shared(),
      )
    }

    val i32 = ValType.I32
    val store = Store()
    val interruptCheckMask = (limits.interruptCheckInstructions - 1).toLong()
    listOf(
      HostFunction(
        HostModule,
        "input_len",
        FunctionType.of(emptyList(), listOf(i32)),
      ) { _, _ ->
        requireNotFenced()
        longArrayOf(input.size.toLong())
      },
      HostFunction(
        HostModule,
        "input_read",
        FunctionType.of(listOf(i32), emptyList()),
      ) { endiveInstance, arguments ->
        requireNotFenced()
        endiveInstance.memory().write(arguments[0].toInt(), input)
        null
      },
      HostFunction(
        HostModule,
        "cap_call",
        FunctionType.of(listOf(i32, i32), listOf(i32)),
      ) { endiveInstance, arguments ->
        requireNotFenced()
        val requestLength = arguments[1].toInt()
        requirePayload("capability request", requestLength, limits.maxCapabilityRequestBytes)
        val request = endiveInstance.memory().readString(
          arguments[0].toInt(),
          requestLength,
        )
        val result = capabilityHost.call(Json.parseToJsonElement(request).jsonObject)
        capabilityResult = result.toString().encodeToByteArray()
        requirePayload(
          "capability result",
          capabilityResult.size,
          limits.maxCapabilityResultBytes,
        )
        longArrayOf(capabilityResult.size.toLong())
      },
      HostFunction(
        HostModule,
        "cap_read",
        FunctionType.of(listOf(i32), emptyList()),
      ) { endiveInstance, arguments ->
        requireNotFenced()
        endiveInstance.memory().write(arguments[0].toInt(), capabilityResult)
        null
      },
      HostFunction(
        HostModule,
        "output_write",
        FunctionType.of(listOf(i32, i32), emptyList()),
      ) { endiveInstance, arguments ->
        requireNotFenced()
        val outputLength = arguments[1].toInt()
        requirePayload("output", outputLength, limits.maxOutputBytes)
        output = endiveInstance.memory().readString(
          arguments[0].toInt(),
          outputLength,
        )
        null
      },
      HostFunction(
        WasiPreview1,
        "random_get",
        FunctionType.of(listOf(i32, i32), listOf(i32)),
      ) { endiveInstance, arguments ->
        requireNotFenced()
        val length = arguments[1].toInt()
        requirePayload("random request", length, limits.maxCapabilityResultBytes)
        val bytes = randomBytes(length)
        check(bytes.size == length) { "random source returned ${bytes.size} bytes, expected $length" }
        endiveInstance.memory().write(arguments[0].toInt(), bytes)
        longArrayOf(0L)
      },
    ).forEach(store::addFunction)

    instanceOrNull = try {
      runBounded {
        store.instantiate("wasmo-app") { importValues ->
          var builder = Instance.builder(module)
            .withImportValues(importValues)
            .withUnsafeExecutionListener { _, _ ->
              val count = ++instructionCount
              if (count > limits.maxInstructions) {
                throw EndiveInstructionLimitExceededException(limits.maxInstructions)
              }
              if (count and interruptCheckMask == 0L && Thread.currentThread().isInterrupted) {
                throw InterruptedException()
              }
            }
          if (boundedMemory != null) builder = builder.withMemoryLimits(boundedMemory)
          builder.build()
        }
      }
    } catch (failure: Throwable) {
      fenced = true
      executor.shutdownNow()
      throw failure
    }
  }

  fun invoke(
    export: String,
    input: String,
  ): String = synchronized(lock) {
    check(!closed) { "Endive runtime is closed" }
    val inputBytes = input.encodeToByteArray()
    requirePayload("input", inputBytes.size, limits.maxInputBytes)
    this.input = inputBytes
    capabilityResult = ByteArray(0)
    output = null
    runBounded { instance.export(export).apply() }
    output ?: error("Endive export '$export' returned without writing output")
  }

  fun hasFunctionExport(name: String): Boolean = name in functionExports

  private fun requireNotFenced() {
    if (fenced) throw EndiveFencedException()
  }

  private fun <T> runBounded(block: () -> T): T {
    instructionCount = 0L
    val future = executor.submit<T> { block() }
    try {
      return future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      // Fence first. Whatever the guest is doing, it stops being able to affect anything the moment
      // this is set, which matters because the interrupt below may not be observed for a while.
      fenced = true
      future.cancel(true)
      closed = true
      shutdownAndCloseInstance()
      throw EndiveExecutionTimeoutException(limits.timeoutMillis)
    } catch (failure: ExecutionException) {
      throw failure.cause ?: failure
    } catch (failure: InterruptedException) {
      fenced = true
      future.cancel(true)
      Thread.currentThread().interrupt()
      throw failure
    }
  }

  override fun close() = synchronized(lock) {
    if (closed) return@synchronized
    closed = true
    fenced = true
    shutdownAndCloseInstance()
  }

  /**
   * Releases the instance once its thread has stopped.
   *
   * Closing an instance that another thread is still interpreting would be a use-after-free, so a
   * guest that ignores its interrupt keeps its memory until the process ends. It cannot do anything
   * with it — it is fenced — and trading a bounded leak for a torn instance is the right way round.
   */
  private fun shutdownAndCloseInstance() {
    executor.shutdownNow()
    if (executor.awaitTermination(InstanceCloseTimeoutMillis, TimeUnit.MILLISECONDS)) {
      instanceOrNull?.close()
    }
  }

  companion object {
    const val HostModule = "wasmo_host_v1"
    const val WasiPreview1 = "wasi_snapshot_preview1"

    private const val InstanceCloseTimeoutMillis = 250L
  }
}

private fun requirePayload(kind: String, actual: Int, maximum: Int) {
  if (actual < 0 || actual > maximum) {
    throw EndivePayloadLimitExceededException(kind, actual, maximum)
  }
}
