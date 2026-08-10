/**
 * Reads the tally that plugins/react-compiler-report.js substitutes into the production bundle.
 * In `vite dev` the placeholder is still a placeholder, which is reported honestly rather than
 * guessed at.
 */
const TOKEN = '__RESCRIPTOR_REACT_COMPILER__'

export function readCompilerReport() {
  try {
    const parsed = JSON.parse(TOKEN)
    if (parsed && typeof parsed.functions === 'number') return { measured: true, ...parsed }
  } catch {
    // Development build: the placeholder is only substituted at bundle time.
  }
  return { measured: false, functions: 0, modules: 0, runtime: 'react/compiler-runtime' }
}
