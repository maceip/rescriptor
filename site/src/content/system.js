/**
 * The whole system as a graph, laid out in tiers. Positions are authored rather than solved so the
 * diagram stays readable: x spreads a tier, y is the tier, z adds a little depth.
 *
 * Sourced from settings.gradle.kts, docs/code/system_model.md, docs/code/web_assembly_runtimes.md
 * and the module READMEs.
 */

export const TIERS = [
  { y: 2.9, label: 'Client', kind: 'client' },
  { y: 1.45, label: 'OS server', kind: 'os' },
  { y: 0, label: 'Invocation', kind: 'runtime' },
  { y: -1.45, label: 'Mediation', kind: 'mediation' },
  { y: -2.9, label: 'Services', kind: 'service' },
]

export const NODES = [
  {
    id: 'client.app',
    tier: 0,
    x: -1.15,
    z: 0,
    label: 'client',
    short: 'client',
    title: 'Kotlin/JS client',
    module: 'os/client',
    body:
      'The frontend application of the OS. Typesafe navigation comes from support/router and the ' +
      'shared HTTP models come from os/api, so the client and server cannot drift apart.',
  },
  {
    id: 'client.passkeys',
    tier: 0,
    x: 1.15,
    z: 0,
    label: 'passkeys',
    short: 'passkeys',
    title: 'Passkey authentication',
    module: 'os/client/passkeys · os/server/passkeys',
    body:
      'Accounts sign in with passkeys. The authenticated result is a wasmo.access.Caller that ' +
      'already carries the user agent and IP, which is why mediation never needed its own signer.',
  },
  {
    id: 'os.ktor',
    tier: 1,
    x: -2.5,
    z: 0.35,
    label: 'ktor',
    short: 'ktor',
    title: 'HTTP ingress',
    module: 'os/server/ktor · os/routes',
    body:
      'Kotlin and Ktor terminate the request. Routes are values, not strings, so the same URL ' +
      'shapes are encoded and decoded on both sides.',
  },
  {
    id: 'os.accounts',
    tier: 1,
    x: -0.85,
    z: -0.35,
    label: 'accounts',
    short: 'accounts',
    title: 'Accounts and invites',
    module: 'os/server/accounts',
    body:
      'Accounts, invitations, and email address linking. An account can create invites that ' +
      'another account claims.',
  },
  {
    id: 'os.computers',
    tier: 1,
    x: 0.85,
    z: -0.35,
    label: 'computers',
    short: 'computers',
    title: 'Computers and billing',
    module: 'os/server/computers · os/catalog · os/server/payments',
    body:
      'A computer is the unit of billing. A ComputerSpec is paid for to materialize a Computer, ' +
      'reachable at its own hostname, and access is granted per account.',
  },
  {
    id: 'os.installedapps',
    tier: 1,
    x: 2.5,
    z: 0.35,
    label: 'installed apps',
    short: 'apps',
    title: 'Installed apps',
    module: 'os/server/installedapps',
    body:
      'Installing a published app onto a computer produces an installed app with its own ' +
      'hostname. Maintenance jobs hold calls with delays and 503s until in-flight work drains.',
  },
  {
    id: 'invocation.session',
    tier: 2,
    x: -1.7,
    z: 0,
    label: 'session',
    short: 'session',
    title: 'CapabilitySession',
    module: 'platform/mediation',
    body:
      'A fresh caller-bound session per invocation. HTTP ingress binds the authenticated Caller; ' +
      'job ingress binds an OS service principal naming the job and job ID, so background work is ' +
      'never misattributed to a person.',
  },
  {
    id: 'invocation.abi',
    tier: 2,
    x: 0,
    z: 0.45,
    label: 'wasmo_host_v1',
    short: 'host abi',
    title: 'Host ABI',
    module: 'os/server/wasm/endive',
    body:
      'Six imports and two exports. input_len and input_read hand the guest its JSON invocation, ' +
      'cap_call and cap_read cross the capability boundary, output_write returns the result, and ' +
      'WASI random_get supplies entropy.',
  },
  {
    id: 'runtime.endive',
    tier: 2,
    x: 1.7,
    z: 0,
    label: 'endive',
    short: 'endive',
    title: 'Endive runtime',
    module: 'third_party/endive · os/server/wasm/endive',
    body:
      'The mandatory runtime for any package containing app.wasm, built from a pinned source ' +
      'checkout rather than Maven Central. Instruction, memory, and wall-clock limits are enforced ' +
      'by the host on a dedicated thread.',
  },
  {
    id: 'mediation.platform',
    tier: 3,
    x: -2.2,
    z: 0.3,
    label: 'MediatedPlatform',
    short: 'platform',
    title: 'MediatedPlatform',
    module: 'platform/mediation',
    body:
      'Wraps the real Platform and covers every service it exposes. JVM apps see the same API and ' +
      'Endive guests reach it through the host ABI, so there is one boundary rather than two.',
  },
  {
    id: 'mediation.policy',
    tier: 3,
    x: -0.75,
    z: -0.3,
    label: 'CapabilityPolicy',
    short: 'policy',
    title: 'CapabilityPolicy',
    module: 'platform/mediation',
    body: 'Denies a call before delegation. A denial is an outcome, and it is audited like any other.',
  },
  {
    id: 'mediation.journal',
    tier: 3,
    x: 0.75,
    z: -0.3,
    label: 'CapabilityJournal',
    short: 'journal',
    title: 'CapabilityJournal',
    module: 'platform/mediation',
    body:
      'Ordered record of every call, keyed by capability.method plus canonical JSON. Recording ' +
      'delegates once; strict replay answers from the journal and fails on a changed, missing, or ' +
      'unconsumed call.',
  },
  {
    id: 'mediation.audit',
    tier: 3,
    x: 2.2,
    z: 0.3,
    label: 'AuditChain',
    short: 'audit',
    title: 'AuditChain',
    module: 'platform/mediation',
    body:
      'SHA-256 hash chain over every success, failure, replay, and denial. Each row commits to the ' +
      'previous hash, so editing history breaks verification from that sequence onward.',
  },
  {
    id: 'svc.sql',
    tier: 4,
    x: -2.75,
    z: 0.3,
    label: 'sql',
    short: 'sql',
    title: 'SQL',
    module: 'os/server/sql · wasmox/wasmox-sqldelight',
    body:
      'Typed bindings travel with the request, and query requests carry ordered result column ' +
      'types because the platform SqlRow API has no runtime column metadata.',
  },
  {
    id: 'svc.objectstore',
    tier: 4,
    x: -1.4,
    z: -0.3,
    label: 'object store',
    short: 'objects',
    title: 'Object store',
    module: 'os/server/objectstore',
    body: 'put, get, delete, and list against S3 or the filesystem. Binary values are base64 on the wire.',
  },
  {
    id: 'svc.jobs',
    tier: 4,
    x: 0,
    z: 0.4,
    label: 'jobs',
    short: 'jobs',
    title: 'Durable jobs',
    module: 'os/server/jobs · support/absurd',
    body:
      'Queue work runs on the Absurd durable workflow system. An app exports wasmo_job to receive ' +
      'a queue name and base64 payload.',
  },
  {
    id: 'svc.http',
    tier: 4,
    x: 1.4,
    z: -0.3,
    label: 'http · downloader',
    short: 'http',
    title: 'Outbound HTTP and downloader',
    module: 'os/server/okhttpclient · os/server/downloader',
    body: 'Outbound fetches and downloads straight into the object store, both journalled like any effect.',
  },
  {
    id: 'svc.db',
    tier: 4,
    x: 2.75,
    z: 0.3,
    label: 'postgres',
    short: 'postgres',
    title: 'PostgreSQL, schema 5',
    module: 'os/server/db',
    body:
      'Stores the invocation envelope plus ordered journal and audit rows. A completed invocation ' +
      'is one transaction, so a partial journal cannot be observed.',
  },
]

export const EDGES = [
  ['client.app', 'os.ktor'],
  ['client.passkeys', 'os.accounts'],
  ['client.app', 'os.installedapps'],
  ['os.ktor', 'os.accounts'],
  ['os.accounts', 'os.computers'],
  ['os.computers', 'os.installedapps'],
  ['os.ktor', 'invocation.session'],
  ['os.installedapps', 'runtime.endive'],
  ['invocation.session', 'invocation.abi'],
  ['invocation.abi', 'runtime.endive'],
  ['invocation.session', 'mediation.platform'],
  ['runtime.endive', 'mediation.platform'],
  ['mediation.platform', 'mediation.policy'],
  ['mediation.policy', 'mediation.journal'],
  ['mediation.journal', 'mediation.audit'],
  ['mediation.journal', 'svc.sql'],
  ['mediation.journal', 'svc.objectstore'],
  ['mediation.journal', 'svc.jobs'],
  ['mediation.journal', 'svc.http'],
  ['mediation.audit', 'svc.db'],
]

/** Where a request goes, per mode. The turnaround point is the whole story. */
export const ROUTES = {
  record: {
    label: 'Record',
    tone: 'accent',
    path: [
      'client.app',
      'os.ktor',
      'os.installedapps',
      'invocation.session',
      'runtime.endive',
      'mediation.platform',
      'mediation.policy',
      'mediation.journal',
      'svc.sql',
    ],
    caption:
      'The guest asks for sql.query. Policy allows it, the journal delegates once, and the result ' +
      'plus an audit row are written before the response goes back.',
    dims: [],
  },
  replay: {
    label: 'Replay',
    tone: 'success',
    path: [
      'client.app',
      'os.ktor',
      'os.installedapps',
      'invocation.session',
      'runtime.endive',
      'mediation.platform',
      'mediation.policy',
      'mediation.journal',
    ],
    caption:
      'The same invocation replayed. The journal answers from its own rows, so nothing below the ' +
      'mediation boundary is touched at all — a delegate that is called during replay fails the test.',
    dims: ['svc.sql', 'svc.objectstore', 'svc.jobs', 'svc.http'],
  },
  denied: {
    label: 'Denied',
    tone: 'destructive',
    path: [
      'client.app',
      'os.ktor',
      'os.installedapps',
      'invocation.session',
      'runtime.endive',
      'mediation.platform',
      'mediation.policy',
    ],
    caption:
      'CapabilityPolicy refuses the call before delegation. The guest sees a failure, and the ' +
      'denial is appended to the audit chain exactly like a success would be.',
    dims: ['mediation.journal', 'svc.sql', 'svc.objectstore', 'svc.jobs', 'svc.http', 'svc.db'],
  },
}

export const NODE_BY_ID = Object.fromEntries(NODES.map((node) => [node.id, node]))

export const position = (node) => [node.x, TIERS[node.tier].y, node.z]
