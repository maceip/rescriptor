@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)

package com.wasmo.endiveprobe

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@WasmImport("wasmo_host_v1", "input_len")
private external fun inputLength(): Int

@WasmImport("wasmo_host_v1", "input_read")
private external fun inputRead(pointer: Int)

@WasmImport("wasmo_host_v1", "cap_call")
private external fun capabilityCall(pointer: Int, length: Int): Int

@WasmImport("wasmo_host_v1", "cap_read")
private external fun capabilityRead(pointer: Int)

@WasmImport("wasmo_host_v1", "output_write")
private external fun outputWrite(pointer: Int, length: Int)

private fun readInput(): String {
  val length = inputLength()
  if (length == 0) return ""
  return withScopedMemoryAllocator { allocator ->
    val pointer = allocator.allocate(length)
    inputRead(pointer.address.toInt())
    ByteArray(length) { index -> (pointer + index).loadByte() }.decodeToString()
  }
}

private fun callCapability(request: String): String = withScopedMemoryAllocator { allocator ->
  val requestBytes = request.encodeToByteArray()
  val requestPointer = allocator.allocate(requestBytes.size)
  for (index in requestBytes.indices) {
    (requestPointer + index).storeByte(requestBytes[index])
  }
  val resultLength = capabilityCall(requestPointer.address.toInt(), requestBytes.size)
  val resultPointer = allocator.allocate(resultLength.coerceAtLeast(1))
  capabilityRead(resultPointer.address.toInt())
  ByteArray(resultLength) { index -> (resultPointer + index).loadByte() }.decodeToString()
}

private fun writeOutput(output: String) = withScopedMemoryAllocator { allocator ->
  val bytes = output.encodeToByteArray()
  val pointer = allocator.allocate(bytes.size.coerceAtLeast(1))
  for (index in bytes.indices) {
    (pointer + index).storeByte(bytes[index])
  }
  outputWrite(pointer.address.toInt(), bytes.size)
}

@WasmExport("wasmo_http")
fun wasmoHttp() {
  val input = readInput()
  val now = callCapability("""{"cap":"clock","method":"now","req":null}""")
  val sawRequest = input.contains("endive-probe.test")
  writeOutput(
    """{"code":200,"headers":[{"name":"x-wasmo-runtime","value":"endive-alpha"}],"body":"endive:$now:$sawRequest"}""",
  )
}

@WasmExport("wasmo_job")
fun wasmoJob() {
  readInput()
  callCapability("""{"cap":"clock","method":"now","req":null}""")
  writeOutput("null")
}

fun main() {
}
