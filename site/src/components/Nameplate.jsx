import { nameplate } from '../content/site.js'

export default function Nameplate() {
  return (
    <header className="nameplate plate plate--bright">
      <div className="nameplate__eyebrow">
        {nameplate.eyebrow.map((item) => (
          <span
            key={item.label}
            className={`badge ${item.tone === 'ok' ? 'badge--success' : 'badge--warning'}`}
          >
            <i className="badge__dot" aria-hidden="true" />
            {item.label}
          </span>
        ))}
      </div>

      <h1 className="nameplate__title engraved engraved--strong">{nameplate.title}</h1>
      <p className="nameplate__sub">{nameplate.sub}</p>

      <dl className="nameplate__meta">
        {nameplate.meta.map((cell) => (
          <div key={cell.term} className="nameplate__cell">
            <dt>{cell.term}</dt>
            <dd className="engraved">{cell.value}</dd>
          </div>
        ))}
      </dl>
    </header>
  )
}
