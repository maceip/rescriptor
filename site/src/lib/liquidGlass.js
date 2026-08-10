/**
 * liquidGL integration.
 *
 * The library rasterizes the page and refracts it through WebGL. That is worth paying for on the
 * nav rail, which floats over the brushed chassis for the whole scroll, and not worth it anywhere
 * else: liquidGL takes over the element's own painting, so it suits a control surface rather than
 * a panel that has to render its own content. Everything else stays on the CSS glass in glass.css,
 * which is also what runs when WebGL is missing or the device asked us to go easy.
 */

export function hasWebGL() {
  try {
    const canvas = document.createElement('canvas')
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'))
  } catch {
    return false
  }
}

export function prefersLightweight() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return true
  if (navigator.connection?.saveData) return true
  if (typeof navigator.deviceMemory === 'number' && navigator.deviceMemory <= 2) return true
  return false
}

/** Resolves to true when liquidGL is driving the lenses. */
export async function attachLiquidGlass({ target = '.is-lens' } = {}) {
  if (prefersLightweight() || !hasWebGL()) return false
  if (document.querySelectorAll(target).length === 0) return false

  try {
    // Wait for webfonts so the snapshot is not taken mid-swap.
    if (document.fonts?.ready) await document.fonts.ready
    const module = await import('liquid-gl')
    const liquidGL = module.default ?? window.liquidGL
    if (typeof liquidGL !== 'function') return false

    liquidGL({
      target,
      snapshot: 'body',
      resolution: Math.min(2, window.devicePixelRatio || 1),
      // Restrained: this is a control surface over machined metal, not a lava lamp.
      refraction: 0.012,
      // Any chromatic aberration reads as a rendering fault against straight machined edges.
      aberration: 0,
      bevelDepth: 0.06,
      bevelWidth: 0.12,
      frost: 0.04,
      shadow: true,
      specular: true,
      reveal: 'fade',
      tilt: false,
      magnify: 1,
    })
    return true
  } catch (error) {
    console.warn('liquidGL unavailable, staying on CSS glass', error)
    return false
  }
}
