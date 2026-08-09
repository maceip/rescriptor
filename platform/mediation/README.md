# Platform capability mediation

This module is the first wasmo-only beachhead for deterministic capability execution. It wraps an
existing `Platform`; JVM apps receive the same API and Endive guests reach it through the host ABI.

The implementation ports two algorithms from the failed rescriptor/celld prototype:

- canonical JSON and ordered journal replay from `bridge/src/runner-core.js`;
- SHA-256 hash chaining and verification from `bridge/src/audit.js`.

`MediatedPlatform` currently covers every service exposed by `Platform`: clock, outbound HTTP,
object storage, downloader, SQL (including bind values and observed row getters), and job queues.
Recording mode delegates once and journals the result or failure. Strict replay returns journaled
results without touching the delegate and fails on a changed, missing, or unconsumed call. A
`CapabilityPolicy` can deny calls before delegation, and every success, failure, replay, or denial
is appended to the invocation's `AuditChain`.

The executable proof is:

```shell
./gradlew :platform:mediation:jvmTest
```

It loads an unchanged JVM app through `JvmAppLoader`, records live calls, replays against delegates
that fail if touched, exercises all current `Platform` services, detects a divergent request, and
detects audit-chain tampering.

The Endive integration adds a second executable proof:

```shell
./gradlew :os:server:wasm:endive:jvmTest
```

That test compiles and executes a Kotlin/Wasm guest under the pinned source-built Endive alpha and
proves its `clock.now` import records and replays through this module.

## Integration boundary

This module intentionally does not claim durable replay yet. `CapabilitySession` owns in-memory
journal and audit snapshots. The next runtime integration must persist both the complete journal
outcome and audit rows at wasmo's authenticated request/job ingress, along with the app release and
input needed to recreate the invocation. An audit table containing only `result_hash` is not enough
to replay a session.

`CallerSigner` from the failed forwarder was not copied. wasmo already owns the authenticated
`wasmo.access.Caller` (including user agent and IP), so signing is only needed if a later transport
crosses a process or trust boundary.
