package com.wasmo.wasm.endive

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import run.endive.runtime.HostFunction
import run.endive.runtime.Instance
import run.endive.runtime.Store
import run.endive.wasm.Parser
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
) {
  init {
    require(maxInstructions > 0)
    require(maxMemoryPages in 1..MemoryLimits.MAX_PAGES)
    require(timeoutMillis > 0)
    require(maxInputBytes > 0)
    require(maxOutputBytes > 0)
    require(maxCapabilityRequestBytes > 0)
    require(maxCapabilityResultBytes > 0)
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

/**
 * One isolated, bounded Endive instance of a Wasmo app module.
 *
 * Endive is built from the pinned source checkout in `third_party/endive`; this class has no
 * dependency on a published Endive artifact or alternate runtime.
 */
class EndiveRuntime(
  wasm: ByteArray,
  private val capabilityHost: EndiveCapabilityHost,
  private val randomBytes: (Int) -> ByteArray,
  private val limits: EndiveLimits = EndiveLimits(),
) : AutoCloseable {
  private val lock = Any()
  private val instructionCount = AtomicLong()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "wasmo-endive").apply { isDaemon = true }
  }
  private var input = ByteArray(0)
  private var capabilityResult = ByteArray(0)
  private var output: String? = null
  private var closed = false
  private val instance: Instance
  private val functionExports: Set<String>

  init {
    val module = Parser.parse(wasm)
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
    listOf(
      HostFunction(
        HostModule,
        "input_len",
        FunctionType.of(emptyList(), listOf(i32)),
      ) { _, _ -> longArrayOf(input.size.toLong()) },
      HostFunction(
        HostModule,
        "input_read",
        FunctionType.of(listOf(i32), emptyList()),
      ) { endiveInstance, arguments ->
        endiveInstance.memory().write(arguments[0].toInt(), input)
        null
      },
      HostFunction(
        HostModule,
        "cap_call",
        FunctionType.of(listOf(i32, i32), listOf(i32)),
      ) { endiveInstance, arguments ->
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
        endiveInstance.memory().write(arguments[0].toInt(), capabilityResult)
        null
      },
      HostFunction(
        HostModule,
        "output_write",
        FunctionType.of(listOf(i32, i32), emptyList()),
      ) { endiveInstance, arguments ->
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
        val length = arguments[1].toInt()
        requirePayload("random request", length, limits.maxCapabilityResultBytes)
        val bytes = randomBytes(length)
        check(bytes.size == length) { "random source returned ${bytes.size} bytes, expected $length" }
        endiveInstance.memory().write(arguments[0].toInt(), bytes)
        longArrayOf(0L)
      },
    ).forEach(store::addFunction)

    instance = try {
      runBounded {
        store.instantiate("wasmo-app") { importValues ->
          var builder = Instance.builder(module)
            .withImportValues(importValues)
            .withUnsafeExecutionListener { _, _ ->
              if (Thread.currentThread().isInterrupted) throw InterruptedException()
              if (instructionCount.incrementAndGet() > limits.maxInstructions) {
                throw EndiveInstructionLimitExceededException(limits.maxInstructions)
              }
            }
          if (boundedMemory != null) builder = builder.withMemoryLimits(boundedMemory)
          builder.build()
        }
      }
    } catch (failure: Throwable) {
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

  private fun <T> runBounded(block: () -> T): T {
    instructionCount.set(0L)
    val future = executor.submit<T> { block() }
    try {
      return future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      future.cancel(true)
      closed = true
      executor.shutdownNow()
      throw EndiveExecutionTimeoutException(limits.timeoutMillis)
    } catch (failure: ExecutionException) {
      throw failure.cause ?: failure
    } catch (failure: InterruptedException) {
      future.cancel(true)
      Thread.currentThread().interrupt()
      throw failure
    }
  }

  override fun close() = synchronized(lock) {
    if (closed) return@synchronized
    closed = true
    instance.close()
    executor.shutdownNow()
  }

  companion object {
    const val HostModule = "wasmo_host_v1"
    const val WasiPreview1 = "wasi_snapshot_preview1"
  }
}

private fun requirePayload(kind: String, actual: Int, maximum: Int) {
  if (actual < 0 || actual > maximum) {
    throw EndivePayloadLimitExceededException(kind, actual, maximum)
  }
}
