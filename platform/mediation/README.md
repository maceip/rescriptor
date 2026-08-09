# Platform capability mediation

Every effect a Wasmo app has on the world crosses this module. It wraps an existing `Platform`; JVM
apps receive the same API and Endive guests reach it through the host ABI.

What that boundary buys, which a sandbox alone does not:

- **Least privilege you can read.** An app declares capabilities in its manifest, and
  `GrantedCapabilityPolicy` holds it to them, down to the URLs and object keys it named. See
  [`docs/platform/capabilities.md`](../../docs/platform/capabilities.md).
- **Replay.** Any recorded invocation can be re-run from its journal on a `SealedPlatform` where
  every live effect throws — no re-fetching, no re-sending, no database that has moved on.
- **Receipts.** Each invocation carries a hash-chained audit that commits to its own journal, in a
  portable format anyone can check without the OS that produced it.

The implementation ports two algorithms from the failed rescriptor/celld prototype:

- canonical JSON and ordered journal replay from `bridge/src/runner-core.js`;
- SHA-256 hash chaining and verification from `bridge/src/audit.js`.

## Recording

`MediatedPlatform` covers every service exposed by `Platform`: clock, outbound HTTP, object storage,
downloader, SQL (including bind values and observed row getters), and job queues. Recording mode
delegates once and journals the result or failure. Strict replay returns journaled results without
touching the delegate and fails on a changed, missing, or unconsumed call.

A denial is journaled like any other failed call, not dropped. That is what lets a denied invocation
replay without re-consulting a policy that may since have changed.

`CapabilityInvocationRecorder` seals one invocation — outcome, journal, and audit — and hands it to a
`CapabilityInvocationStore` to persist atomically.

## Reading it back

`CapabilityInvocationReader` returns what was stored. `InvocationReplayer` re-runs an invocation
through an `InvocationExecutor` supplied by the OS and reports either `Deterministic` or `Divergent`
with the reason: a changed request, an unrecorded call, a skipped one, a different output, or a
different outcome.

A `Divergent` report is not a replay bug. It says the app is no longer a function of its recorded
inputs.

## Verifying it offline

`verifyInvocation` checks more than the hash chain. A chain alone proves only that it is internally
consistent — anyone who can rewrite the database can recompute one. This also proves the chain
describes *that* journal: every capability call in order, each committing to the value the journal
says was returned, plus the invocation's own input, caller, and outcome.

`InvocationJson` is the portable form, and the `moose audit verify` / `moose audit show` commands
read it. Nothing in that path needs a server or a network.

## Executable proof

```shell
./gradlew :platform:mediation:jvmTest
```

Records live calls and replays them against a `SealedPlatform`; detects a changed request, an
unrecorded call, a skipped call, a changed output, and a changed outcome; detects a rewritten
journal result, a dropped journal entry, a rewritten audit row, a rewritten input, a rewritten
output, and a re-attributed caller; and proves an undeclared capability is denied, journaled,
chained, and replayable.

The Endive integration adds a second proof:

```shell
git submodule update --init third_party/endive
./gradlew :os:server:wasm:endive:jvmTest
```

That test compiles and executes a Kotlin/Wasm guest under the pinned source-built Endive alpha and
proves its `clock.now` import records and replays through this module.

## Known limits

- Replaying a recorded failure raises `ReplayedCapabilityFailureException` rather than reconstructing
  the original exception type. An app whose control flow branches on a specific exception class can
  therefore be reported as divergent when it is not. `InvocationReplayer` unwraps the recorded type
  when comparing outcomes, which covers propagation but not a `catch` on a specific type.
- The audit chain is tamper-evident against anyone who cannot rewrite the whole invocation
  consistently. It is not yet signed or externally anchored, so an operator who controls the database
  and has never published a head can still forge a self-consistent record. Signing per-invocation
  heads with a computer-scoped key, and checkpointing them somewhere the operator does not control,
  is the remaining work.
- The journal grows without a per-invocation ceiling on call count or total bytes.

`CallerSigner` from the failed forwarder was not copied. wasmo already owns the authenticated
`wasmo.access.Caller` (including user agent and IP), so signing is only needed if a later transport
crosses a process or trust boundary.
