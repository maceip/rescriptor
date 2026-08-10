/**
 * Every claim on this page is sourced from the repository it describes:
 * docs/code/web_assembly_runtimes.md, platform/mediation/README.md, os/server/wasm/endive/README.md
 * and README.md. Update this file when those change.
 */

export const repo = 'https://github.com/maceip/rescriptor'
const blob = `${repo}/blob/main`

export const sections = [
  { id: 'overview', label: 'Overview', icon: 'plate' },
  { id: 'system', label: 'System', icon: 'layers' },
  { id: 'runtime', label: 'Runtime', icon: 'chip' },
  { id: 'mediation', label: 'Mediation', icon: 'shield' },
  { id: 'console', label: 'Console', icon: 'terminal' },
  { id: 'capabilities', label: 'Capabilities', icon: 'grid' },
  { id: 'limits', label: 'Limits', icon: 'gauge' },
  { id: 'proof', label: 'Proof', icon: 'check' },
]

export const sectionIds = sections.map((section) => section.id)

export const nameplate = {
  eyebrow: [
    { label: 'wasmo · webassembly cloud computer', tone: 'ok' },
    { label: 'public preview targeted july 2026', tone: 'warn' },
  ],
  title: 'rescriptor',
  sub:
    'Deterministic capability mediation for Wasmo. Every effect an app causes is journalled, ' +
    'replayable without touching the world again, and sealed into a SHA-256 hash chain.',
  meta: [
    { term: 'Runtime', value: 'Endive, pinned alpha source' },
    { term: 'Host ABI', value: 'wasmo_host_v1' },
    { term: 'Schema', value: 'version 5' },
    { term: 'License', value: 'Apache-2.0' },
  ],
}

export const overview = {
  lede:
    'Wasmo is a cloud computer that runs each app in its own WebAssembly sandbox. rescriptor is ' +
    'the layer underneath that: the single boundary every app effect has to cross.',
  paragraphs: [
    'An app never reaches the clock, the network, object storage, the job queue, or SQL directly. ' +
      'It reaches MediatedPlatform, which wraps the real Platform and keeps an ordered journal of ' +
      'every call it delegates.',
    'Recording mode delegates once and journals the result or the failure. Strict replay returns ' +
      'journalled results without touching the delegate at all, and fails on a changed, missing, ' +
      'or unconsumed call. A CapabilityPolicy can deny a call before delegation, and every ' +
      'success, failure, replay, and denial is appended to the invocation audit chain.',
  ],
  links: [
    { label: 'platform/mediation', href: `${blob}/platform/mediation/README.md` },
    { label: 'os/server/wasm/endive', href: `${blob}/os/server/wasm/endive/README.md` },
    { label: 'docs/code/web_assembly_runtimes.md', href: `${blob}/docs/code/web_assembly_runtimes.md` },
  ],
}

export const runtime = {
  lede:
    'Endive is the mandatory runtime for every installed package containing app.wasm. It is not ' +
    'resolved from Maven Central.',
  cards: [
    {
      key: 'Pinned source build',
      value:
        'The third_party/endive gitlink is built by Gradle and the JVM compilation consumes its ' +
        '999-SNAPSHOT output directly. The integration test asserts the loaded Endive class came ' +
        'from that jar.',
      tag: 'buildEndiveSource',
    },
    {
      key: 'No silent fallback',
      value:
        'EndiveAppLoader is the production AppLoader. If an installed app contains app.wasm, a ' +
        'load or parse failure is fatal. JvmAppLoader runs only when there is no Wasm payload.',
      tag: 'EndiveAppLoader',
    },
    {
      key: 'Small crossing',
      value:
        'A core-Wasm ABI with six imports. Component Model support can arrive later as an ' +
        'interface layer without blocking the runtime.',
      tag: 'wasmo_host_v1',
    },
    {
      key: 'Caller-bound sessions',
      value:
        'HTTP ingress binds the authenticated Caller. Job ingress binds an OS service principal ' +
        'naming the job and job ID, so background work is never misattributed to a person.',
      tag: 'CapabilitySession',
    },
  ],
  abi: [
    ['input_len()', 'Length of the JSON invocation waiting for the guest.'],
    ['input_read(ptr)', 'Copies the invocation into guest memory.'],
    ['cap_call(ptr, len)', 'Crosses the capability boundary with a canonical JSON request.'],
    ['cap_read(ptr)', 'Copies the capability result back into guest memory.'],
    ['output_write(ptr, len)', 'Returns the JSON result of the invocation.'],
    ['random_get', 'WASI Preview 1 entropy, supplied by the host and mediated like any effect.'],
  ],
  exports: [
    ['wasmo_http', 'required', 'Handles a dynamic HTTP request.'],
    ['wasmo_job', 'optional', 'Receives a queue name and base64 payload for durable queue work.'],
  ],
}

export const mediation = {
  lede:
    'Recording, replay, and audit are three views of the same ordered journal. The console below ' +
    'runs the real algorithms.',
  steps: [
    {
      key: '01 · Record',
      value:
        'Each call is keyed as capability.method plus its canonical JSON request, delegated once, ' +
        'and appended with its result or its failure type and message.',
      tag: 'CapabilityJournal.recording()',
    },
    {
      key: '02 · Replay',
      value:
        'The journal is replayed in order against a delegate that fails if touched. A different ' +
        'key raises ReplayMismatch, an extra call raises ReplayExhausted, and stopping early ' +
        'raises ReplayIncomplete.',
      tag: 'CapabilityJournal.replay()',
    },
    {
      key: '03 · Audit',
      value:
        'Every outcome appends a row hashing the previous hash, timestamp, kind, caller, detail, ' +
        'and result hash. Editing any row breaks the chain from that sequence onward.',
      tag: 'AuditChain.verify()',
    },
  ],
  boundary:
    'The module does not claim durable replay on its own. CapabilitySession holds the journal and ' +
    'audit snapshots in memory; schema version 5 persists the invocation envelope, the ordered ' +
    'journal, and the audit rows in one PostgreSQL transaction at HTTP and job ingress. An audit ' +
    'table containing only result_hash is not enough to replay a session.',
}

export const capabilities = [
  { name: 'clock.now', group: 'Time', note: 'Wall clock, journalled per call.' },
  { name: 'random.bytes', group: 'Entropy', note: 'WASI random_get; replay never asks the OS.' },
  { name: 'http.fetch', group: 'Network', note: 'Outbound request and response.' },
  { name: 'objectstore.put', group: 'Storage', note: 'Binary values are base64 in the payload.' },
  { name: 'objectstore.get', group: 'Storage', note: 'Reads are journalled like writes.' },
  { name: 'objectstore.delete', group: 'Storage', note: 'Deletes are recorded effects.' },
  { name: 'objectstore.list', group: 'Storage', note: 'Listing order is part of the journal key.' },
  { name: 'downloader.download', group: 'Storage', note: 'Fetch straight into the object store.' },
  { name: 'jobs.enqueue', group: 'Jobs', note: 'Durable queue work.' },
  { name: 'jobs.cancel', group: 'Jobs', note: 'Cancellation is sealed in the audit chain.' },
  { name: 'sql.exec', group: 'SQL', note: 'Typed bindings travel with the request.' },
  { name: 'sql.query', group: 'SQL', note: 'Requests carry ordered result column types.' },
]

export const limits = [
  ['Interpreted instructions', '10,000,000', 'Metered on the interpreter hot path.'],
  ['Linear memory', '1,024 pages · 64 MiB', 'Declared initial memory above the budget is rejected.'],
  ['Wall clock', '5 s', 'Per instantiation or exported call, on a dedicated daemon thread.'],
  ['Invocation input', '1 MiB', 'JSON handed to the guest.'],
  ['Invocation output', '1 MiB', 'JSON returned by the guest.'],
  ['Capability request', '1 MiB', 'Per cap_call payload.'],
  ['Capability result', '1 MiB', 'Per cap_read payload.'],
  ['SQL query result', '10,000 rows', 'Beyond the shared 1 MiB payload ceiling.'],
]

export const proof = {
  lede: 'The claims above are executable. Each command is the test that backs the section it sits under.',
  blocks: [
    {
      title: 'Runtime and mediation',
      lines: [
        'git submodule update --init third_party/endive',
        './gradlew :os:server:wasm:endive:jvmTest :platform:mediation:jvmTest',
      ],
    },
    {
      title: 'Production wiring',
      lines: [
        "./gradlew :os:server:installedapps:real:jvmTest \\",
        "  --tests '*MediationTest' --tests '*RealInstalledAppServiceTest'",
      ],
    },
    {
      title: 'Durable envelopes',
      lines: [
        './gradlew :os:server:db:jvmTest \\',
        '  --tests com.wasmo.db.mediation.SqlCapabilityInvocationStoreTest',
      ],
    },
  ],
  note:
    'The Endive test compiles apps/endive-probe to Wasm, builds Endive from the pinned checkout, ' +
    'executes the guest HTTP export, records its clock call, and replays it against a Platform ' +
    'whose live clock fails if touched.',
}
