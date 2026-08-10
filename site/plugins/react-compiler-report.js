import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * Proves the React Compiler actually ran, instead of asking anyone to take it on faith.
 *
 * The plugin runs its `transform` with `enforce: 'post'`, so it observes module source *after*
 * `@rolldown/plugin-babel` has applied `babel-plugin-react-compiler`. Compiled output imports the
 * memo cache as `react/compiler-runtime` and allocates one cache per compiled function via `_c(n)`,
 * so both are counted here.
 *
 * The tally is then substituted into the emitted bundle in `generateBundle`, which runs after
 * minification, so the number the page displays is measured by the build that produced it.
 * `scripts/verify-compiler.mjs` fails the build if the tally is missing or zero.
 */
const TOKEN = '__RESCRIPTOR_REACT_COMPILER__'
const RUNTIME_IMPORT = 'react/compiler-runtime'
const CACHE_CALL = /\b_c\d*\(\s*\d+\s*\)/g
const SOURCE_FILE = /\.[cm]?[jt]sx?(?:$|\?)/

export default function reactCompilerReport() {
  /** @type {Map<string, number>} */
  const compiledModules = new Map()
  const reportPath = 'react-compiler-report.json'

  const summarize = () => {
    const modules = compiledModules.size
    let functions = 0
    for (const count of compiledModules.values()) functions += count
    return { modules, functions, runtime: RUNTIME_IMPORT, measuredAt: new Date().toISOString() }
  }

  return {
    name: 'rescriptor:react-compiler-report',
    enforce: 'post',

    transform: {
      filter: { id: SOURCE_FILE },
      handler(code, id) {
        if (id.includes('/node_modules/')) return null
        if (!code.includes(RUNTIME_IMPORT)) return null
        compiledModules.set(id, (code.match(CACHE_CALL) || []).length)
        return null
      },
    },

    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: reportPath,
        source: `${JSON.stringify(summarize(), null, 2)}\n`,
      })
    },

    /*
     * Substitution happens on disk rather than on the in-memory chunk, because the bundler
     * minifies after generateBundle and would discard a mutation made there.
     */
    async writeBundle(options, bundle) {
      const report = summarize()
      // A quoted JSON payload, so the runtime side is a plain JSON.parse of a string literal.
      const payload = JSON.stringify(JSON.stringify(report))
      const dir = options.dir ?? 'dist'
      let patched = 0

      for (const file of Object.values(bundle)) {
        if (file.type !== 'chunk') continue
        const path = join(dir, file.fileName)
        const code = await readFile(path, 'utf8')
        if (!code.includes(TOKEN)) continue
        // The minifier is free to re-quote the literal, including as a template string.
        const next = ['"', "'", '`'].reduce(
          (source, quote) => source.split(`${quote}${TOKEN}${quote}`).join(payload),
          code,
        )
        if (next === code) {
          this.error(
            `found ${TOKEN} in ${file.fileName} but not as a quoted literal; the report cannot ` +
              'be substituted',
          )
        }
        await writeFile(path, next)
        patched += 1
      }

      this.info(
        `react compiler: ${report.functions} memoized function(s) across ${report.modules} ` +
          `module(s); report inlined into ${patched} chunk(s)`,
      )
    },
  }
}
