# Endive runtime

This module is Wasmo's mandatory runtime for every installed package containing `app.wasm`.
`EndiveAppLoader` is bound as the server's `AppLoader`; it uses `JvmAppLoader` only when no Wasm
payload exists, preserving current built-in JVM apps during migration.

Endive is not resolved from Maven Central. `buildEndiveSource` builds the gitlink-pinned
`third_party/endive` checkout and the JVM compilation consumes its `999-SNAPSHOT` output directly.
The integration test also verifies the Endive class code source points at that local jar.

The host-call shape was salvaged from `rescriptor_failed_celld/endive-host`, but the boundary is now
owned by Wasmo and named `wasmo_host_v1`. It dispatches clock, entropy, HTTP, object storage,
downloader, SQL, and jobs through the invocation's `MediatedPlatform`. A Kotlin/Wasm fixture
exercises HTTP input/output plus mediated record/replay.

Runtime instances enforce instruction, memory, wall-clock, and ABI payload limits. HTTP and job
ingress create caller-bound sessions; schema version 5 atomically persists the resulting invocation,
capability journal, and hash-chain audit rows.

```shell
git submodule update --init third_party/endive
./gradlew :os:server:wasm:endive:jvmTest
```

This remains a core-Wasm ABI. Component Model support can evolve as a later interface layer without
blocking the current Endive runtime.
