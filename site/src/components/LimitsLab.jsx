/**
 * Scale a sample invocation and watch which host limit trips first.
 *
 * Budgets are the documented Endive defaults. The baseline is a plausible journal-app request; the
 * multiplier is the interesting part, because it shows that instruction metering, not memory, is
 * what usually stops a runaway guest.
 */
import { useState } from 'react'

const BUDGETS = [
  { id: 'instructions', label: 'Instructions', budget: 10_000_000, base: 940_000, unit: '' },
  { id: 'memory', label: 'Memory pages', budget: 1024, base: 96, unit: ' pages' },
  { id: 'wall', label: 'Wall clock', budget: 5000, base: 210, unit: ' ms' },
  { id: 'payload', label: 'Capability result', budget: 1_048_576, base: 92_000, unit: ' B' },
  { id: 'rows', label: 'SQL rows', budget: 10_000, base: 640, unit: '' },
]

// Memory and payload grow sublinearly with work; instructions and time do not.
const GROWTH = { instructions: 1, memory: 0.42, wall: 0.92, payload: 0.55, rows: 0.86 }

const format = (value) =>
  value >= 1_000_000
    ? `${(value / 1_000_000).toFixed(2)}M`
    : value >= 1000
      ? `${(value / 1000).toFixed(1)}k`
      : String(Math.round(value))

export default function LimitsLab() {
  const [scale, setScale] = useState(4)

  const used = BUDGETS.map((limit) => {
    const value = limit.base * scale ** GROWTH[limit.id]
    return { ...limit, value, ratio: value / limit.budget }
  })
  const tripped = used.filter((limit) => limit.ratio > 1).sort((a, b) => b.ratio - a.ratio)

  return (
    <div className="lab">
      <label className="lab__control">
        <span>
          Workload multiplier <strong>{scale}×</strong>
        </span>
        <input
          type="range"
          min="1"
          max="24"
          step="1"
          value={scale}
          onChange={(event) => setScale(Number(event.target.value))}
        />
      </label>

      <ul className="lab__meters">
        {used.map((limit) => (
          <li key={limit.id} className="lab__meter">
            <span className="lab__meter-name">{limit.label}</span>
            <span className="progress lab__meter-track">
              <i
                style={{ transform: `scaleX(${Math.min(1, limit.ratio)})` }}
                data-over={limit.ratio > 1 || undefined}
              />
            </span>
            <span className="lab__meter-value">
              {format(limit.value)}
              {limit.unit} / {format(limit.budget)}
              {limit.unit}
            </span>
          </li>
        ))}
      </ul>

      <p className="console__verdict" data-tone={tripped.length > 0 ? 'bad' : 'ok'}>
        {tripped.length === 0 ? (
          <>
            Within budget at {scale}×. Every limit failure is an ordinary invocation failure, so it
            still lands in durable audit state.
          </>
        ) : (
          <>
            <strong>{tripped[0].label}</strong> trips first at {scale}×
            {tripped.length > 1 ? `, followed by ${tripped[1].label.toLowerCase()}` : ''}. The guest
            is interrupted on its own daemon thread and the failure is audited like any other outcome.
          </>
        )}
      </p>
    </div>
  )
}
