WebAssembly Runtime
===================

Decision
--------

Wasmo executes packaged `app.wasm` modules with **Endive built from pinned alpha source**. The
checkout is the `third_party/endive` git submodule; Gradle invokes Endive's Maven wrapper and links
the resulting `runtime-999-SNAPSHOT.jar` and `wasm-999-SNAPSHOT.jar` directly. A released Maven
artifact is not an acceptable substitute.

`EndiveAppLoader` is the production `AppLoader` binding. If an installed app contains `app.wasm`,
loading or parsing failure is fatal and never falls back to another runtime. The old JVM factory
path remains only for built-in apps that do not yet package Wasm.

Chicory is not a runtime option for this integration. Its version-catalog entries and the unused
testing dependency have been removed.


Host ABI
--------

The `wasmo_host_v1` core-Wasm ABI keeps the low-level crossing deliberately small:

 * `input_len()` and `input_read(pointer)` provide a JSON invocation to the guest.
 * `cap_call(pointer, length)` and `cap_read(pointer)` cross the Wasmo capability boundary.
 * `output_write(pointer, length)` returns a JSON result.
 * WASI Preview 1 `random_get` is supplied by the host and participates in mediation.

`cap_call` dispatches the complete current `Platform` surface: `clock.now`, `random.bytes`,
`http.fetch`, object-store `put`/`get`/`delete`/`list`, `downloader.download`, job
`enqueue`/`cancel`, and SQL `exec`/`query`. Payloads are canonical JSON; binary values are base64.
SQL requests carry typed bindings. Query requests also carry the ordered result column types,
because the platform `SqlRow` API intentionally has no runtime column metadata.

Apps export `wasmo_http` for dynamic requests and may export `wasmo_job` for durable queue work.
The latter receives canonical JSON containing the queue name and base64 job payload; absence of the
export means the app has no job handler.

Every effect crosses the same `MediatedPlatform`. This includes WASI entropy: `random_get` does not
use a deterministic process-global PRNG and replay never asks the operating system for new bytes.


Resource limits
---------------

Each Endive instance has host-enforced defaults of 10,000,000 interpreted instructions, 1,024
WebAssembly pages (64 MiB), and five seconds for instantiation or an exported call. Endive's alpha
instruction listener supplies metering on the interpreter hot path; execution happens on a
dedicated daemon thread so wall-clock expiry can interrupt a guest. The module's declared initial
memory is rejected if it exceeds the budget and the first memory's maximum is clamped to it.

Invocation input, output, capability requests, and capability results are each limited to 1 MiB.
SQL query results are additionally limited to 10,000 rows. Limit failures are ordinary invocation
failures and are therefore included in durable audit state.


Invocation sessions and persistence
-----------------------------------

Dynamic app HTTP ingress constructs a fresh mediation session using the authenticated `Caller`
after static-resource routing. Application-job ingress constructs a fresh session using an explicit
OS service principal whose stable name contains the job name and job ID; a background job is never
misattributed to a human caller. The invocation-scoped `MediatedPlatform` is passed into app loading,
so both JVM-transition apps and Endive guests use the same boundary.

Schema version 5 stores an invocation envelope plus ordered `CapabilityJournalEntry` and
`CapabilityAuditEntry` rows. Successful calls, guest failures, and cancellation are sealed with
start/outcome audit entries and saved in one PostgreSQL transaction. A persistence failure fails an
otherwise successful request; if execution has already failed, a persistence failure is attached
as a suppressed exception without hiding the original error. Completed invocations are atomic, but
an OS process crash before the final transaction cannot leave a partial journal and also cannot
recover that in-flight invocation.


Executable proof
----------------

Run:

```
./gradlew :os:server:wasm:endive:jvmTest :platform:mediation:jvmTest
./gradlew :os:server:installedapps:real:jvmTest \
  --tests '*MediationTest' --tests '*RealInstalledAppServiceTest'
./gradlew :os:server:db:jvmTest \
  --tests com.wasmo.db.mediation.SqlCapabilityInvocationStoreTest
```

The test compiles `apps/endive-probe` to Wasm, builds Endive from the pinned source checkout,
asserts the loaded Endive class came from that source-built snapshot jar, executes the guest HTTP
export, records its clock call, and replays it against a Platform whose live clock fails if touched.
Additional executable tests cover ABI dispatch, instruction/memory/wall-clock limits, successful
and failed durable envelopes, audit-chain verification, and installed-app production wiring.


Background
----------

Component Model and stack switching support remain useful later, but are not part of this core-Wasm
ABI or a gate for the current Wasmo runtime.

[Endive]: https://endive.run/
[Bytecode Alliance Announcement]: https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm
