import { useEffect, useLayoutEffect, useRef } from 'react'
import Icon from './Icons.jsx'
import { useRailDrift, useScrollProgress } from '../lib/hooks.js'
import { repo } from '../content/site.js'

/**
 * One nav for three form factors. The marker is positioned from measurements rather than from a
 * per-breakpoint stylesheet guess, so it lands correctly whichever axis the rail is running on.
 */
export default function Nav({ sections, active }) {
  const navRef = useRef(null)
  const markerRef = useRef(null)
  const items = useRef(new Map())
  const progress = useScrollProgress()

  useRailDrift(navRef)

  useLayoutEffect(() => {
    const marker = markerRef.current
    const link = items.current.get(active)
    if (!marker || !link) return
    const nav = navRef.current
    nav.style.setProperty('--marker-x', `${link.offsetLeft}px`)
    nav.style.setProperty('--marker-y', `${link.offsetTop}px`)
    nav.style.setProperty('--marker-w', `${link.offsetWidth}px`)
    nav.style.setProperty('--marker-h', `${link.offsetHeight}px`)
    marker.dataset.ready = 'true'
  }, [active])

  // Keep the active item in view on the mobile bar and the icon rail.
  useEffect(() => {
    items.current.get(active)?.scrollIntoView({ block: 'nearest', inline: 'center' })
  }, [active])

  return (
    <nav
      ref={navRef}
      className="nav glass glass--prominent is-lens"
      aria-label="Sections"
      style={{ '--rail-drift': '0px' }}
    >
      <div className="nav__brand engraved">
        <span aria-hidden="true">▚</span>
        <span className="nav__brand-name">rescriptor</span>
      </div>

      <div className="nav__scroll">
        <span className="nav__marker" ref={markerRef} aria-hidden="true" />
        <ul className="nav__list">
          {sections.map((section, index) => (
            <li key={section.id} className="nav__item">
              <a
                ref={(node) => {
                  if (node) items.current.set(section.id, node)
                  else items.current.delete(section.id)
                }}
                className="nav__link"
                href={`#${section.id}`}
                data-label={section.label}
                aria-current={active === section.id}
                onPointerMove={(event) => {
                  const rect = event.currentTarget.getBoundingClientRect()
                  event.currentTarget.style.setProperty('--mx', `${event.clientX - rect.left}px`)
                  event.currentTarget.style.setProperty('--my', `${event.clientY - rect.top}px`)
                }}
              >
                <span className="nav__icon">
                  <Icon name={section.icon} />
                </span>
                <span className="nav__label">{section.label}</span>
                <span className="nav__index">{String(index + 1).padStart(2, '0')}</span>
              </a>
            </li>
          ))}
        </ul>
      </div>

      <div className="nav__progress progress" aria-hidden="true">
        <i style={{ transform: `scaleX(${progress})` }} />
      </div>

      <div className="nav__foot">
        <a href={repo}>github ↗</a>
        <br />
        Apache-2.0
      </div>
    </nav>
  )
}
