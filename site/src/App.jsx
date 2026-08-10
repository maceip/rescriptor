import { useEffect, useState, version as reactVersion } from 'react'
import CapabilityMatrix from './components/CapabilityMatrix.jsx'
import LimitsLab from './components/LimitsLab.jsx'
import MediationConsole from './components/MediationConsole.jsx'
import Nameplate from './components/Nameplate.jsx'
import Nav from './components/Nav.jsx'
import Panel from './components/Panel.jsx'
import SystemExplorer from './components/SystemExplorer.jsx'
import { limits, mediation, overview, proof, repo, runtime, sectionIds, sections } from './content/site.js'
import { useGlassCapability, useScrollSpy } from './lib/hooks.js'
import { attachLiquidGlass } from './lib/liquidGlass.js'
import { readCompilerReport } from './lib/compilerReport.js'

const THEMES = ['system', 'light', 'dark']
const compiler = readCompilerReport()

function ThemeToggle() {
  const [theme, setTheme] = useState('system')

  useEffect(() => {
    if (theme === 'system') delete document.documentElement.dataset.theme
    else document.documentElement.dataset.theme = theme
  }, [theme])

  return (
    <button
      type="button"
      className="btn btn--sm btn--outline"
      onClick={() => setTheme(THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length])}
    >
      finish: {theme === 'system' ? 'auto' : theme === 'light' ? 'mill' : 'anodized'}
    </button>
  )
}

export default function App() {
  const active = useScrollSpy(sectionIds)
  const [glass, setGlass] = useGlassCapability()

  useEffect(() => {
    let cancelled = false
    attachLiquidGlass().then((attached) => {
      if (attached && !cancelled) setGlass('full')
    })
    return () => {
      cancelled = true
    }
  }, [setGlass])

  return (
    <div className="shell">
      <a className="skip btn btn--sm btn--default" href="#overview">
        Skip to content
      </a>

      <Nav sections={sections} active={active} />

      <main className="content" id="main">
        <Nameplate />

        <Panel id="overview" index={1} title="Overview" lede={overview.lede}>
          {overview.paragraphs.map((paragraph) => (
            <p key={paragraph.slice(0, 32)}>{paragraph}</p>
          ))}
          <p className="panel__links">
            {overview.links.map((link) => (
              <a key={link.href} href={link.href} className="badge badge--outline">
                {link.label} ↗
              </a>
            ))}
          </p>
        </Panel>

        <Panel
          id="system"
          index={2}
          title="The system, end to end"
          lede="Every component, and the one boundary they all fold through. Pick a mode to see where a request actually stops."
        >
          <SystemExplorer />
        </Panel>

        <Panel id="runtime" index={3} title="Runtime" lede={runtime.lede}>
          <div className="cards">
            {runtime.cards.map((card) => (
              <article key={card.key} className="card glass glass--subtle">
                <h3 className="card__title">{card.key}</h3>
                <p className="card__description">{card.value}</p>
                <p className="card__footer mono">{card.tag}</p>
              </article>
            ))}
          </div>

          <h3 className="panel__subtitle">Host ABI</h3>
          <div className="spec__wrap well">
            <table className="spec">
              <thead>
                <tr>
                  <th scope="col">Import</th>
                  <th scope="col">Purpose</th>
                </tr>
              </thead>
              <tbody>
                {runtime.abi.map(([name, purpose]) => (
                  <tr key={name}>
                    <th scope="row" className="mono">
                      {name}
                    </th>
                    <td>{purpose}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <h3 className="panel__subtitle">Guest exports</h3>
          <div className="spec__wrap well">
            <table className="spec">
              <thead>
                <tr>
                  <th scope="col">Export</th>
                  <th scope="col">Required</th>
                  <th scope="col">Receives</th>
                </tr>
              </thead>
              <tbody>
                {runtime.exports.map(([name, required, receives]) => (
                  <tr key={name}>
                    <th scope="row" className="mono">
                      {name}
                    </th>
                    <td>
                      <span className={`badge ${required === 'required' ? 'badge--accent' : ''}`}>
                        {required}
                      </span>
                    </td>
                    <td>{receives}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel id="mediation" index={4} title="Record, replay, audit" lede={mediation.lede}>
          <div className="cards">
            {mediation.steps.map((step) => (
              <article key={step.key} className="card glass glass--subtle">
                <h3 className="card__title">{step.key}</h3>
                <p className="card__description">{step.value}</p>
                <p className="card__footer mono">{step.tag}</p>
              </article>
            ))}
          </div>
          <p className="panel__note">{mediation.boundary}</p>
        </Panel>

        <Panel
          id="console"
          index={5}
          title="Run it"
          lede="Real SHA-256 over the same field order the JVM uses. Record an invocation, replay it, change one binding, or edit a row and watch verification fail."
        >
          <MediationConsole />
        </Panel>

        <Panel
          id="capabilities"
          index={6}
          title="Capability surface"
          lede="Everything an app can cause to happen, and the journal key each call turns into."
        >
          <CapabilityMatrix />
        </Panel>

        <Panel
          id="limits"
          index={7}
          title="Resource limits"
          lede="Host-enforced defaults for every Endive instance. Drag the multiplier to see which ceiling a runaway guest meets first."
        >
          <LimitsLab />
          <div className="spec__wrap well">
            <table className="spec">
              <thead>
                <tr>
                  <th scope="col">Limit</th>
                  <th scope="col">Default</th>
                  <th scope="col">Notes</th>
                </tr>
              </thead>
              <tbody>
                {limits.map(([name, value, note]) => (
                  <tr key={name}>
                    <th scope="row">{name}</th>
                    <td className="mono">{value}</td>
                    <td>{note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel id="proof" index={8} title="Executable proof" lede={proof.lede}>
          <div className="cards">
            {proof.blocks.map((block) => (
              <article key={block.title} className="card glass glass--subtle">
                <h3 className="card__title">{block.title}</h3>
                <pre className="code well">
                  {block.lines.map((line) => (
                    <div key={line}>
                      <span className="prompt">$ </span>
                      {line}
                    </div>
                  ))}
                </pre>
              </article>
            ))}
          </div>
          <p className="panel__note">{proof.note}</p>
        </Panel>

        <footer className="foot glass glass--subtle">
          <div className="badges">
            <span className="badge">react {reactVersion}</span>
            <span className="badge badge--accent">
              react compiler{' '}
              {compiler.measured ? `· ${compiler.functions} memoized` : '· dev build'}
            </span>
            <span className="badge">glass: {glass}</span>
          </div>
          <div className="foot__links">
            <a href={repo}>repository</a>
            <a href={`${repo}/blob/main/LICENSE.txt`}>Apache-2.0</a>
            <ThemeToggle />
          </div>
        </footer>
      </main>
    </div>
  )
}
