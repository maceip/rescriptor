import { useEffect, useRef, useState } from 'react'

/** Which section is currently under the reading position. */
export function useScrollSpy(ids) {
  const [active, setActive] = useState(ids[0])

  useEffect(() => {
    const seen = new Map()
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) seen.set(entry.target.id, entry)
        const visible = [...seen.values()]
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
        if (visible.length > 0) setActive(visible[0].target.id)
      },
      { rootMargin: '-25% 0px -60% 0px', threshold: [0, 0.25, 1] },
    )
    for (const id of ids) {
      const element = document.getElementById(id)
      if (element) observer.observe(element)
    }
    return () => observer.disconnect()
  }, [ids])

  return active
}

/** How far through the document the reader is, 0 to 1. */
export function useScrollProgress() {
  const [progress, setProgress] = useState(0)

  useEffect(() => {
    let frame = 0
    const measure = () => {
      frame = 0
      const scrollable = document.documentElement.scrollHeight - window.innerHeight
      setProgress(scrollable <= 0 ? 0 : Math.min(1, Math.max(0, window.scrollY / scrollable)))
    }
    const onScroll = () => {
      if (frame === 0) frame = requestAnimationFrame(measure)
    }
    measure()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    }
  }, [])

  return progress
}

/**
 * The desktop rail drifts down as the page scrolls and eases back when scrolling stops, so it
 * feels like it is floating rather than pinned. Writes --rail-drift; CSS owns the rest.
 */
export function useRailDrift(ref, { max = 26, factor = 0.035 } = {}) {
  useEffect(() => {
    const node = ref.current
    if (!node) return undefined
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return undefined
    if (!window.matchMedia('(min-width: 1080px)').matches) return undefined

    let current = 0
    let frame = 0

    const step = () => {
      const target = Math.min(max, window.scrollY * factor)
      current += (target - current) * 0.075
      node.style.setProperty('--rail-drift', `${current.toFixed(2)}px`)
      frame = Math.abs(target - current) > 0.05 ? requestAnimationFrame(step) : 0
    }
    const onScroll = () => {
      if (frame === 0) frame = requestAnimationFrame(step)
    }

    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
      node.style.removeProperty('--rail-drift')
    }
  }, [ref, max, factor])
}

/**
 * Resolves the glass capability tier the way RikkaUI does, and publishes it on <html data-glass>
 * so the stylesheet can pick the right treatment.
 *
 *   full    liquidGL has a WebGL renderer attached and owns the refraction
 *   limited CSS backdrop-filter only, with tint and highlight boosted
 *   none    opaque themed surfaces with a border
 */
export function useGlassCapability() {
  const [capability, setCapability] = useState('limited')

  useEffect(() => {
    const blur =
      CSS.supports('backdrop-filter', 'blur(1px)') ||
      CSS.supports('-webkit-backdrop-filter', 'blur(1px)')
    setCapability((current) => (current === 'full' ? current : blur ? 'limited' : 'none'))
  }, [])

  useEffect(() => {
    document.documentElement.dataset.glass = capability
  }, [capability])

  return [capability, setCapability]
}

/** True once the element has been near the viewport, so heavy work can wait until it matters. */
export function useNearViewport(ref, rootMargin = '400px') {
  const [near, setNear] = useState(false)

  useEffect(() => {
    const node = ref.current
    if (!node || near) return undefined
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) setNear(true)
      },
      { rootMargin },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [ref, near, rootMargin])

  return near
}

/** Reads a CSS custom property off :root, so WebGL materials can follow the page theme. */
export function useThemeColors(names, deps = []) {
  const [colors, setColors] = useState(() => Object.fromEntries(names.map((n) => [n, '#888888'])))
  const namesRef = useRef(names)

  useEffect(() => {
    const read = () => {
      const style = getComputedStyle(document.documentElement)
      setColors(
        Object.fromEntries(
          namesRef.current.map((name) => [name, style.getPropertyValue(name).trim() || '#888888']),
        ),
      )
    }
    read()
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener('change', read)
    return () => media.removeEventListener('change', read)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return colors
}
