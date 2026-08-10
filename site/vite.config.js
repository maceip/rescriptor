import babel from '@rolldown/plugin-babel'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import reactCompilerReport from './plugins/react-compiler-report.js'

// GitHub Pages serves a project site from /<repo>/. The workflow overrides this for other hosts.
const base = process.env.SITE_BASE || '/rescriptor/'

export default defineConfig({
  base,
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    reactCompilerReport(),
  ],
  build: {
    target: 'es2022',
    cssMinify: 'lightningcss',
    assetsInlineLimit: 2048,
    reportCompressedSize: true,
    rollupOptions: {
      output: {
        // React gets its own long-lived cache line; three stays in the lazy 3D chunk.
        manualChunks(id) {
          if (/node_modules\/(react|react-dom|scheduler)\//.test(id)) return 'react'
          return undefined
        },
      },
    },
  },
})
