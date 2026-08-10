/**
 * Pick a capability, see the exact journal key it produces.
 *
 * The key is not illustrated, it is computed by the same `journalKey` this page's console uses,
 * which is a port of CapabilityJournal.key. The request bodies are representative shapes; the
 * contract the ABI actually fixes is canonical JSON with base64 for binary values.
 */
import { useMemo, useState } from 'react'
import { capabilities } from '../content/site.js'
import { journalKey } from '../lib/audit.js'

const GROUPS = ['All', 'Time', 'Entropy', 'Network', 'Storage', 'Jobs', 'SQL']

const REQUESTS = {
  'clock.now': {},
  'random.bytes': { length: 32 },
  'http.fetch': { method: 'GET', url: 'https://api.example.test/rates', headers: {} },
  'objectstore.put': { key: 'attachments/3f2a91.jpg', valueBase64: '/9j/4AAQSkZJRg…' },
  'objectstore.get': { key: 'attachments/3f2a91.jpg' },
  'objectstore.delete': { key: 'attachments/3f2a91.jpg' },
  'objectstore.list': { prefix: 'attachments/', limit: 100 },
  'downloader.download': { url: 'https://cdn.example.test/cover.png', key: 'covers/74.png' },
  'jobs.enqueue': { queue: 'publish', payloadBase64: 'eyJlbnRyeSI6Ijc0In0=' },
  'jobs.cancel': { jobId: 'job_01JQZK4M' },
  'sql.exec': {
    sql: 'UPDATE entry SET title = ? WHERE id = ?',
    bindings: [{ type: 'text', value: 'Field notes' }, { type: 'long', value: 74 }],
  },
  'sql.query': {
    sql: 'SELECT id, title, updated_at FROM entry ORDER BY updated_at DESC LIMIT ?',
    bindings: [{ type: 'long', value: 20 }],
    columns: ['text', 'text', 'long'],
  },
}

export default function CapabilityMatrix() {
  const [group, setGroup] = useState('All')
  const [selected, setSelected] = useState('sql.query')

  const shown = useMemo(
    () => capabilities.filter((entry) => group === 'All' || entry.group === group),
    [group],
  )

  const active = capabilities.find((entry) => entry.name === selected) ?? capabilities[0]
  const [capability, method] = active.name.split('.')
  const key = journalKey(capability, method, REQUESTS[active.name] ?? {})

  return (
    <div className="matrix">
      <div className="matrix__filters" role="group" aria-label="Filter capabilities">
        {GROUPS.map((name) => (
          <button
            key={name}
            type="button"
            className={`btn btn--sm ${group === name ? 'btn--default' : 'btn--ghost'}`}
            aria-pressed={group === name}
            onClick={() => setGroup(name)}
          >
            {name}
          </button>
        ))}
      </div>

      <div className="matrix__grid">
        {shown.map((entry) => (
          <button
            key={entry.name}
            type="button"
            className="card glass glass--subtle card--interactive matrix__cell"
            aria-selected={selected === entry.name}
            onClick={() => setSelected(entry.name)}
          >
            <span className="matrix__name">{entry.name}</span>
            <span className="matrix__group">{entry.group}</span>
          </button>
        ))}
      </div>

      <div className="matrix__detail well">
        <p className="matrix__note">{active.note}</p>
        <p className="matrix__label">Journal key</p>
        <pre className="code matrix__key">{key}</pre>
        <p className="matrix__foot">
          Recording appends this key with its result. Replay recomputes it and refuses to continue
          if a single byte of the canonical request differs.
        </p>
      </div>
    </div>
  )
}
