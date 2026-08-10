/**
 * A browser port of the two algorithms in platform/mediation.
 *
 * `journalKey` mirrors `CapabilityJournal.key`, and `appendAudit` / `verifyChain` mirror
 * `AuditChain`: each row hashes `previousHash \n timestamp \n kind \n caller \n detail \n
 * resultHash` with SHA-256, starting from a 64 zero genesis. Keeping the exact wire shape is the
 * point, so the console below computes the same digests the JVM does.
 */

export const GENESIS = '0'.repeat(64)

export const hashingAvailable = typeof crypto !== 'undefined' && !!crypto.subtle

/** Canonical JSON: object keys sorted, no incidental whitespace. Matches `stableStringify`. */
export function stableStringify(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const keys = Object.keys(value).sort()
  const body = keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`)
  return `{${body.join(',')}}`
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export const journalKey = (capability, method, request) =>
  `${capability}.${method}:${stableStringify(request)}`

export const resultHash = (result) => sha256Hex(stableStringify(result))

const rowHash = ({ previousHash, timestampMillis, kind, caller, detail, resultHash: hash }) =>
  sha256Hex([previousHash, String(timestampMillis), kind, caller, detail, hash].join('\n'))

/** Returns a new chain with one row appended. Never mutates the input. */
export async function appendAudit(entries, { kind, caller, detail, resultHash: hash, at }) {
  const previousHash = entries.length === 0 ? GENESIS : entries[entries.length - 1].hash
  const timestampMillis = at
  const entry = {
    seq: entries.length + 1,
    timestampMillis,
    kind,
    caller,
    detail,
    resultHash: hash,
    previousHash,
    hash: await rowHash({ previousHash, timestampMillis, kind, caller, detail, resultHash: hash }),
  }
  return [...entries, entry]
}

/** Mirrors `AuditChain.verify`: returns `{valid:true, head}` or `{valid:false, seq, reason}`. */
export async function verifyChain(entries) {
  let previousHash = GENESIS
  for (const entry of entries) {
    if (entry.previousHash !== previousHash) {
      return { valid: false, seq: entry.seq, reason: 'previous hash mismatch' }
    }
    if ((await rowHash({ ...entry, previousHash })) !== entry.hash) {
      return { valid: false, seq: entry.seq, reason: 'hash mismatch' }
    }
    previousHash = entry.hash
  }
  return { valid: true, entries: entries.length, head: previousHash }
}

export const shortHash = (hash) => `${hash.slice(0, 12)}…${hash.slice(-4)}`
