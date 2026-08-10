/**
 * The interactive system view.
 *
 * Everything here works without WebGL: the tier legend selects nodes, the mode tabs explain the
 * routing, and the detail card is plain DOM. The 3D canvas is an enhancement that loads on demand
 * once it is near the viewport, and never loads itself on a device that asked for less.
 */
import { Suspense, lazy, useEffect, useRef, useState } from 'react'
import { NODES, NODE_BY_ID, ROUTES, TIERS } from '../content/system.js'
import { useNearViewport, useThemeColors } from '../lib/hooks.js'
import { prefersLightweight } from '../lib/liquidGlass.js'

const SystemScene = lazy(() => import('./SystemScene.jsx'))

const MODES = ['record', 'replay', 'denied']
const COLOR_VARS = [
  '--accent',
  '--primary-foreground',
  '--foreground',
  '--subtle-foreground',
  '--metal-1',
  '--card',
  '--destructive',
  '--border-strong',
]

export default function SystemExplorer() {
  const holder = useRef(null)
  const near = useNearViewport(holder)
  const [mode, setMode] = useState('record')
  const [selected, setSelected] = useState('mediation.journal')
  const [enabled, setEnabled] = useState(false)
  const [paused, setPaused] = useState(false)
  const vars = useThemeColors(COLOR_VARS)

  useEffect(() => {
    if (near && !prefersLightweight()) setEnabled(true)
  }, [near])

  // Stop rendering while the section is off screen. Nothing animates that nobody is watching.
  useEffect(() => {
    const node = holder.current
    if (!node || !enabled) return undefined
    const observer = new IntersectionObserver((entries) => setPaused(!entries[0].isIntersecting), {
      threshold: 0.01,
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [enabled])

  const palette = {
    accent: vars['--accent'],
    accentInk: vars['--primary-foreground'],
    label: vars['--foreground'],
    dim: vars['--subtle-foreground'],
    metal: vars['--metal-1'],
    glass: vars['--card'],
    bad: vars['--destructive'],
    edge: vars['--border-strong'],
  }

  const route = ROUTES[mode]
  const node = NODE_BY_ID[selected]

  return (
    <div className="explorer">
      <div className="explorer__tabs" role="tablist" aria-label="Invocation mode">
        {MODES.map((key) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={mode === key}
            className={`btn btn--sm ${mode === key ? 'btn--default' : 'btn--secondary'}`}
            onClick={() => setMode(key)}
          >
            {ROUTES[key].label}
          </button>
        ))}
        <p className="explorer__caption">{route.caption}</p>
      </div>

      <div className="explorer__stage well" ref={holder} data-liquid-ignore>
        {enabled ? (
          <Suspense fallback={<p className="explorer__loading">Loading the 3D view…</p>}>
            <SystemScene
              mode={mode}
              selected={selected}
              onSelect={(id) => setSelected(id ?? selected)}
              paused={paused}
              palette={palette}
            />
          </Suspense>
        ) : (
          <div className="explorer__loading">
            <p>
              The 3D view is held back on reduced-motion, low-memory, and data-saver devices. The
              legend below drives the same content.
            </p>
            <button type="button" className="btn btn--sm btn--outline" onClick={() => setEnabled(true)}>
              Load the 3D view anyway
            </button>
          </div>
        )}
      </div>

      <div className="explorer__legend">
        {TIERS.map((tier, index) => (
          <div key={tier.label} className="explorer__tier">
            <span className="explorer__tier-name">{tier.label}</span>
            <div className="explorer__tier-nodes">
              {NODES.filter((candidate) => candidate.tier === index).map((candidate) => (
                <button
                  key={candidate.id}
                  type="button"
                  className="badge badge--outline explorer__pill"
                  aria-pressed={selected === candidate.id}
                  data-cold={route.dims.includes(candidate.id) || undefined}
                  onClick={() => setSelected(candidate.id)}
                >
                  {candidate.label}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

      <article className="card glass glass--subtle explorer__detail" aria-live="polite">
        <div className="card__header">
          <h3 className="card__title">{node.title}</h3>
          <span className="badge badge--accent">{TIERS[node.tier].label}</span>
        </div>
        <p className="card__description">{node.body}</p>
        <p className="card__footer mono">{node.module}</p>
      </article>
    </div>
  )
}
