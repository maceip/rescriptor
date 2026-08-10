#!/usr/bin/env node
// Build gate: fail loudly if the React Compiler silently stopped running.
//
// A misconfigured Babel preset does not break the build, it just quietly ships unoptimized
// components. This reads the report emitted by plugins/react-compiler-report.js and asserts that
// the placeholder was substituted into the shipped bundle.
import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const dist = fileURLToPath(new URL('../dist', import.meta.url))
const TOKEN = '__RESCRIPTOR_REACT_COMPILER__'

const fail = (message) => {
  console.error(`✗ react compiler check: ${message}`)
  process.exit(1)
}

const walk = async (dir) => {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = await Promise.all(
    entries.map((entry) => {
      const path = join(dir, entry.name)
      return entry.isDirectory() ? walk(path) : Promise.resolve([path])
    }),
  )
  return files.flat()
}

let report
try {
  report = JSON.parse(await readFile(join(dist, 'react-compiler-report.json'), 'utf8'))
} catch {
  fail('dist/react-compiler-report.json is missing; the report plugin did not run')
}

if (!report.functions) {
  fail('no components were compiled; check reactCompilerPreset() in vite.config.js')
}

const scripts = (await walk(dist)).filter((file) => file.endsWith('.js'))
if (scripts.length === 0) fail('no JavaScript was emitted into dist/')

for (const file of scripts) {
  if ((await readFile(file, 'utf8')).includes(TOKEN)) {
    fail(`placeholder ${TOKEN} survived into ${file}`)
  }
}

console.log(
  `✓ react compiler: ${report.functions} memoized function(s) across ` +
    `${report.modules} module(s) via ${report.runtime}`,
)
