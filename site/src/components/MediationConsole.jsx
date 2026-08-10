/**
 * The record / replay / audit console.
 *
 * This is not a mock: the journal keys are built the way CapabilityJournal.key builds them, and
 * every hash is a real SHA-256 over the same field order AuditChain uses. Tampering with a row
 * therefore fails verification here for exactly the reason it fails on the server.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { appendAudit, hashingAvailable, journalKey, resultHash, shortHash, verifyChain } from '../lib/audit.js'

const CALLER = 'account:jesse12'

/** One app invocation: a journal app listing entries and publishing one. */
const CALLS = [
  { capability: 'clock', method: 'now', request: {}, result: { epochMillis: 1786060800000 } },
  {
    capability: 'sql',
    method: 'query',
    request: {
      sql: 'SELECT id, title, updated_at FROM entry ORDER BY updated_at DESC LIMIT ?',
      bindings: [{ type: 'long', value: 20 }],
      columns: ['text', 'text', 'long'],
    },
    result: { rows: 3 },
  },
  {
    capability: 'objectstore',
    method: 'get',
    request: { key: 'attachments/3f2a91.jpg' },
    result: { bytes: 40960 },
  },
  {
    capability: 'http',
    method: 'fetch',
    request: { method: 'GET', url: 'https://api.example.test/publish' },
    result: { status: 200 },
  },
  {
    capability: 'jobs',
    method: 'enqueue',
    request: { queue: 'publish', payloadBase64: 'eyJlbnRyeSI6Ijc0In0=' },
    result: { jobId: 'job_01JQZK4M' },
  },
]

/** The same guest, one binding different. Replay must refuse it. */
const DIVERGENT = CALLS.map((call, index) =>
  index === 1
    ? { ...call, request: { ...call.request, bindings: [{ type: 'long', value: 50 }] } }
    : call,
)

const EMPTY = { journal: [], audit: [], verdict: null, touched: 0 }

export default function MediationConsole() {
  const [state, setState] = useState(EMPTY)
  const [busy, setBusy] = useState(false)
  const alive = useRef(true)

  useEffect(() => () => {
    alive.current = false
  }, [])

  const record = useCallback(async () => {
    setBusy(true)
    let journal = []
    let audit = await appendAudit([], {
      kind: 'invocation.start',
      caller: CALLER,
      detail: 'wasmo_http journal.wasmo',
      resultHash: await resultHash({ mode: 'record' }),
      at: 1786060800000,
    })
    setState({ journal, audit, verdict: null, touched: 0 })

    for (const [index, call] of CALLS.entries()) {
      const key = journalKey(call.capability, call.method, call.request)
      journal = [...journal, { key, result: call.result, source: 'Live' }]
      audit = await appendAudit(audit, {
        kind: 'capability.record',
        caller: CALLER,
        detail: key,
        resultHash: await resultHash(call.result),
        at: 1786060800000 + (index + 1) * 17,
      })
      if (!alive.current) return
      setState({ journal, audit, verdict: null, touched: index + 1 })
      await new Promise((resolve) => setTimeout(resolve, 130))
    }

    audit = await appendAudit(audit, {
      kind: 'invocation.outcome',
      caller: CALLER,
      detail: 'ok',
      resultHash: await resultHash({ status: 200 }),
      at: 1786060800000 + 120,
    })
    setState({
      journal,
      audit,
      touched: CALLS.length,
      verdict: {
        tone: 'ok',
        text: `Recorded ${CALLS.length} calls. The delegate ran ${CALLS.length} times and the chain head is ${shortHash(audit[audit.length - 1].hash)}.`,
      },
    })
    setBusy(false)
  }, [])

  const replay = useCallback(
    async (calls, label) => {
      setBusy(true)
      const recorded = state.journal
      let journal = []
      let audit = state.audit
      let touched = 0

      for (const [index, call] of calls.entries()) {
        const key = journalKey(call.capability, call.method, call.request)
        const entry = recorded[index]

        if (!entry) {
          setState((current) => ({
            ...current,
            journal,
            verdict: {
              tone: 'bad',
              text: `ReplayExhaustedException at entry ${index}: the guest made an unrecorded call, ${key}.`,
            },
          }))
          setBusy(false)
          return
        }

        if (entry.key !== key) {
          journal = [...journal, { key, result: null, source: 'Mismatch', expected: entry.key }]
          audit = await appendAudit(audit, {
            kind: 'capability.replay.mismatch',
            caller: CALLER,
            detail: key,
            resultHash: await resultHash({ expected: entry.key }),
            at: 1786060800000 + 400 + index,
          })
          setState({
            journal,
            audit,
            touched,
            verdict: {
              tone: 'bad',
              text: `ReplayMismatchException at entry ${index}. Expected ${entry.key.slice(0, 58)}… and the guest asked for a different request. Nothing was delegated.`,
            },
          })
          setBusy(false)
          return
        }

        journal = [...journal, { ...entry, source: 'Replay' }]
        audit = await appendAudit(audit, {
          kind: 'capability.replay',
          caller: CALLER,
          detail: key,
          resultHash: await resultHash(entry.result),
          at: 1786060800000 + 400 + index,
        })
        if (!alive.current) return
        setState({ journal, audit, touched, verdict: null })
        await new Promise((resolve) => setTimeout(resolve, 110))
      }

      setState({
        journal,
        audit,
        touched,
        verdict: {
          tone: 'ok',
          text: `${label}: ${calls.length} calls answered from the journal. The delegate was touched ${touched} times — a live Platform here would fail the test if it were called at all.`,
        },
      })
      setBusy(false)
    },
    [state.journal, state.audit],
  )

  const tamper = useCallback(async () => {
    const target = Math.min(3, state.audit.length - 1)
    if (target < 1) return
    const audit = state.audit.map((entry, index) =>
      index === target ? { ...entry, detail: `${entry.detail} LIMIT 500` } : entry,
    )
    const result = await verifyChain(audit)
    setState((current) => ({
      ...current,
      audit,
      verdict: result.valid
        ? { tone: 'ok', text: 'Chain verified.' }
        : {
            tone: 'bad',
            text: `Row ${target + 1} was edited in place. AuditChain.verify reports ${result.reason} at sequence ${result.seq}; every row after it is unverifiable too.`,
          },
    }))
  }, [state.audit])

  const verify = useCallback(async () => {
    const result = await verifyChain(state.audit)
    setState((current) => ({
      ...current,
      verdict: result.valid
        ? {
            tone: 'ok',
            text: `Chain valid across ${result.entries} rows. Head ${shortHash(result.head)}.`,
          }
        : { tone: 'bad', text: `Invalid at sequence ${result.seq}: ${result.reason}.` },
    }))
  }, [state.audit])

  if (!hashingAvailable) {
    return (
      <p className="console__empty">
        This console needs Web Crypto, which browsers only expose on a secure origin. Open the page
        over HTTPS to run it.
      </p>
    )
  }

  const hasJournal = state.journal.length > 0 && state.journal.every((row) => row.source !== 'Mismatch')

  return (
    <div className="console">
      <div className="console__controls">
        <button type="button" className="btn btn--default" onClick={record} disabled={busy}>
          Record
        </button>
        <button
          type="button"
          className="btn btn--secondary"
          onClick={() => replay(CALLS, 'Strict replay')}
          disabled={busy || !hasJournal}
        >
          Replay
        </button>
        <button
          type="button"
          className="btn btn--secondary"
          onClick={() => replay(DIVERGENT, 'Divergent replay')}
          disabled={busy || !hasJournal}
        >
          Replay a changed guest
        </button>
        <button
          type="button"
          className="btn btn--outline"
          onClick={tamper}
          disabled={busy || state.audit.length < 2}
        >
          Tamper with a row
        </button>
        <button
          type="button"
          className="btn btn--outline"
          onClick={verify}
          disabled={busy || state.audit.length === 0}
        >
          Verify
        </button>
        <button
          type="button"
          className="btn btn--ghost"
          onClick={() => setState(EMPTY)}
          disabled={busy || state.audit.length === 0}
        >
          Reset
        </button>
      </div>

      <div className="console__grid">
        <section className="console__pane well">
          <h3>
            Capability journal
            <span className="badge">{state.journal.length}</span>
          </h3>
          {state.journal.length === 0 ? (
            <p className="console__empty">Press Record to run one invocation through the boundary.</p>
          ) : (
            <ol className="console__rows">
              {state.journal.map((row, index) => (
                <li
                  key={`${row.key}-${index}`}
                  className={`row ${row.source === 'Live' ? 'row--live' : row.source === 'Replay' ? 'row--replay' : 'row--bad'}`}
                >
                  <span className="row__seq">{index}</span>
                  <span>
                    <span className="row__key">{row.key}</span>
                    {row.expected ? (
                      <span className="row__note">expected {row.expected.slice(0, 64)}…</span>
                    ) : (
                      <span className="row__hash">{row.source.toLowerCase()}</span>
                    )}
                  </span>
                </li>
              ))}
            </ol>
          )}
        </section>

        <section className="console__pane well">
          <h3>
            Audit chain
            <span className="badge">{state.audit.length}</span>
          </h3>
          {state.audit.length === 0 ? (
            <p className="console__empty">Rows appear as outcomes are sealed.</p>
          ) : (
            <ol className="console__rows">
              {state.audit.map((entry) => (
                <li key={entry.seq} className="row">
                  <span className="row__seq">{entry.seq}</span>
                  <span>
                    <span className="row__key">{entry.kind}</span>
                    <span className="row__hash">{shortHash(entry.hash)}</span>
                  </span>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>

      {state.verdict ? (
        <p className="console__verdict" data-tone={state.verdict.tone}>
          {state.verdict.text}
        </p>
      ) : null}
    </div>
  )
}
