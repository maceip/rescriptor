export default function Panel({ id, index, title, lede, children }) {
  return (
    <section id={id} className="panel plate" aria-labelledby={`${id}-title`}>
      <header className="panel__head">
        <span className="panel__num">{String(index).padStart(2, '0')}</span>
        <h2 className="panel__title engraved" id={`${id}-title`}>
          {title}
        </h2>
      </header>
      {lede ? <p className="panel__lede">{lede}</p> : null}
      {children}
    </section>
  )
}
