#!/usr/bin/env node
/**
 * Post-build optimizer and budget gate. No dependencies, so it runs identically in CI and locally.
 *
 *   1. Collapses whitespace and strips comments from the emitted HTML.
 *   2. Copies index.html to 404.html so deep links keep the page's look on GitHub Pages.
 *   3. Writes .nojekyll, without which Pages drops files and folders beginning with an underscore.
 *   4. Reports raw and gzip sizes, and fails the build if the entry payload blows its budget.
 */
import { gzipSync } from 'node:zlib'
import { readdir, readFile, stat, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const dist = fileURLToPath(new URL('../dist', import.meta.url))

// Budgets cover what the browser must fetch before first paint. three.js is lazy loaded and is
// deliberately outside the entry budget; the total budget keeps the whole page honest.
const BUDGETS = { entryKb: 260, totalKb: 1800 }

const kb = (bytes) => bytes / 1024
const fmt = (bytes) => `${kb(bytes).toFixed(1)} kB`

const walk = async (dir) => {
  const entries = await readdir(dir, { withFileTypes: true })
  const nested = await Promise.all(
    entries.map((entry) => {
      const path = join(dir, entry.name)
      return entry.isDirectory() ? walk(path) : Promise.resolve([path])
    }),
  )
  return nested.flat()
}

/** Conservative HTML minification: never touches pre, textarea, script, or style contents. */
const MARK = '@@rs-keep@@'
const RESTORE = /@@rs-keep@@(\d+)@@rs-keep@@/g

function minifyHtml(html) {
  // Stash verbatim blocks behind a sentinel that cannot occur in real markup, squeeze the
  // rest, then put them back untouched.
  const keep = []
  const stash = html.replace(
    /<(pre|textarea|script|style)\b[\s\S]*?<\/\1>/gi,
    (match) => `${MARK}${keep.push(match) - 1}${MARK}`,
  )
  const squeezed = stash
    .replace(/<!--(?!\[if)[\s\S]*?-->/g, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/>\s+</g, '><')
    .trim()
  return squeezed.replace(RESTORE, (_, index) => keep[Number(index)])
}

const files = await walk(dist)

// 1 + 2 + 3: HTML.
let htmlSaved = 0
for (const file of files.filter((path) => path.endsWith('.html'))) {
  const original = await readFile(file, 'utf8')
  const minified = minifyHtml(original)
  htmlSaved += Buffer.byteLength(original) - Buffer.byteLength(minified)
  await writeFile(file, minified)
  if (file.endsWith('index.html')) await writeFile(join(dist, '404.html'), minified)
}
await writeFile(join(dist, '.nojekyll'), '')

// 4: size report and budget.
const assets = await walk(dist)
const rows = await Promise.all(
  assets.map(async (path) => {
    const raw = await readFile(path)
    const { size } = await stat(path)
    return {
      name: relative(dist, path),
      size,
      gzip: /\.(js|css|html|svg|json|map)$/.test(path) ? gzipSync(raw).length : size,
    }
  }),
)
rows.sort((a, b) => b.gzip - a.gzip)

const isEntry = (name) =>
  name.endsWith('.html') || /assets\/(index|react|main)[.-][\w-]*\.(js|css)$/.test(name)

const entryGzip = rows.filter((row) => isEntry(row.name)).reduce((sum, row) => sum + row.gzip, 0)
const totalGzip = rows.reduce((sum, row) => sum + row.gzip, 0)

const table = [
  '| Asset | Raw | Gzip |',
  '| :-- | --: | --: |',
  ...rows.slice(0, 18).map((row) => `| \`${row.name}\` | ${fmt(row.size)} | ${fmt(row.gzip)} |`),
].join('\n')

const report = `## Site build

${rows.length} files · entry **${fmt(entryGzip)}** gzip (budget ${BUDGETS.entryKb} kB) · total \
**${fmt(totalGzip)}** gzip (budget ${BUDGETS.totalKb} kB) · HTML minified by ${fmt(htmlSaved)}

${table}
`

await writeFile(join(dist, 'build-report.md'), report)
console.log(report)

const failures = []
if (kb(entryGzip) > BUDGETS.entryKb) {
  failures.push(`entry payload ${fmt(entryGzip)} gzip exceeds ${BUDGETS.entryKb} kB`)
}
if (kb(totalGzip) > BUDGETS.totalKb) {
  failures.push(`total payload ${fmt(totalGzip)} gzip exceeds ${BUDGETS.totalKb} kB`)
}
if (failures.length > 0) {
  console.error(`✗ size budget: ${failures.join('; ')}`)
  process.exit(1)
}
console.log('✓ size budget')
