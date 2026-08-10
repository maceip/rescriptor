// 18px stroke glyphs for the nav rail. Drawn on a 24 grid so strokes stay crisp when scaled.

const base = {
  width: 18,
  height: 18,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
  focusable: false,
}

const glyphs = {
  plate: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M3 9h18M8 13h9M8 16.5h6" />
    </>
  ),
  chip: (
    <>
      <rect x="6.5" y="6.5" width="11" height="11" rx="1.5" />
      <path d="M10 3v3.5M14 3v3.5M10 17.5V21M14 17.5V21M3 10h3.5M3 14h3.5M17.5 10H21M17.5 14H21" />
    </>
  ),
  layers: (
    <>
      <path d="M12 3 3 7.5 12 12l9-4.5L12 3Z" />
      <path d="m3 12.5 9 4.5 9-4.5M3 16.5 12 21l9-4.5" />
    </>
  ),
  terminal: (
    <>
      <rect x="3" y="4.5" width="18" height="15" rx="2" />
      <path d="m7.5 10 2.75 2.5L7.5 15M13.5 15.5h3.5" />
    </>
  ),
  grid: (
    <>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.4" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.4" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.4" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.4" />
    </>
  ),
  gauge: (
    <>
      <path d="M4 17a8 8 0 1 1 16 0" />
      <path d="m12 17 4.2-5.4" />
      <circle cx="12" cy="17" r="1.2" />
    </>
  ),
  check: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m8.2 12.2 2.6 2.6 5-5.4" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3.2 5 6v5.4c0 4 2.9 7.4 7 9.4 4.1-2 7-5.4 7-9.4V6l-7-2.8Z" />
      <path d="M9.2 12.1 11 14l3.9-4" />
    </>
  ),
}

export default function Icon({ name }) {
  const glyph = glyphs[name]
  if (!glyph) return null
  return <svg {...base}>{glyph}</svg>
}
