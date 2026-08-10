import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

// Latin subsets only. The full imports ship seven unicode ranges per weight, which triples the
// artifact for glyphs this page never renders.
import '@fontsource/ibm-plex-mono/latin-400.css'
import '@fontsource/ibm-plex-mono/latin-500.css'
import '@fontsource/ibm-plex-mono/latin-600.css'

import './styles/tokens.css'
import './styles/aluminum.css'
import './styles/glass.css'
import './styles/layout.css'
import './styles/app.css'

import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
