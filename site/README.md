rescriptor site
===============

The GitHub Pages site for this repository. It is a single page describing the system, with three
interactive pieces that run the real algorithms rather than illustrations of them.

```shell
npm install
npm run dev      # http://localhost:5173/rescriptor/
npm run build    # vite build -> React Compiler gate -> optimizer and budget check
npm run preview
```


Deployment
----------

`.github/workflows/pages.yml` builds, optimizes, and deploys on every push to **main**. GitHub Pages
hosts one live deployment; `concurrency: github-pages` with `cancel-in-progress` cancels a
superseded run so the newest commit to `main` is the one that lands.

`SITE_BASE` is derived from the repository name in CI, because a project site is served from
`/<repo>/`. Building for a different origin is a matter of setting it:

```shell
SITE_BASE=/ npm run build
```

Enable Pages once, under **Settings → Pages → Build and deployment → GitHub Actions**.


React Compiler
--------------

The compiler runs through `reactCompilerPreset()` from `@vitejs/plugin-react`, applied by
`@rolldown/plugin-babel`. Two pieces keep it honest:

* `plugins/react-compiler-report.js` counts modules whose post-Babel source imports
  `react/compiler-runtime` and how many memo caches were allocated, then substitutes that tally into
  the shipped bundle and writes `dist/react-compiler-report.json`.
* `scripts/verify-compiler.mjs` fails the build if the tally is missing, zero, or never substituted.

The footer of the page reports the number the build measured. A misconfigured preset therefore
breaks the build instead of quietly shipping unoptimized components.


Design
------

Two materials, layered deliberately:

* **Chassis — brushed aluminum** (`src/styles/aluminum.css`). A seamless anisotropic-noise grain
  over a directional falloff, with a one pixel bevel. This is what the glass refracts.
* **Components — glass** (`src/styles/glass.css`), following RikkaUI's `GlassSurface` layer stack:
  graded and blurred backdrop, tint wash, inner shadow for slab thickness, specular rim, drop
  shadow. Semantic tokens use the shadcn/RikkaUI vocabulary, and nested glass halves its blur and
  drops its shadow exactly as RikkaUI does.

Three capability tiers mirror RikkaUI's `GlassCapability`, resolved at runtime onto
`<html data-glass>`:

| Tier      | Condition                        | Treatment                                       |
| :-------- | :------------------------------- | :---------------------------------------------- |
| `full`    | liquidGL attached over WebGL     | Real refraction; CSS blur is switched off        |
| `limited` | `backdrop-filter` only           | CSS blur, with tint and highlight boosted        |
| `none`    | neither                          | Opaque themed surface with a border              |

[liquidGL] drives the nav rail only. It takes over an element's own painting, which suits a control
surface floating over the chassis and does not suit a panel that has to render its own content.

Radii are tight everywhere (4px, 3px for small parts). Type is IBM Plex Mono, self-hosted from
`@fontsource`, latin subsets only.


Navigation
----------

One component tree, three form factors:

| Width           | Device                       | Navigation                                            |
| :-------------- | :--------------------------- | :---------------------------------------------------- |
| `< 600px`       | phones, fold cover screens   | Docked bottom bar, 48px targets, safe-area aware      |
| `600–1079px`    | unfolded foldables, tablets  | 60px icon rail on the left edge, with tooltips        |
| `>= 1080px`     | desktop                      | 236px labelled rail that drifts with scroll and tracks the pointer |

Hinged devices are handled separately: `horizontal-viewport-segments: 2` gives the rail the whole
left segment and keeps the fold gap empty, and `vertical-viewport-segments: 2` docks the bar in the
bottom segment. Nothing interactive is ever bisected by a hinge.

The active marker is positioned from measurements, so it lands correctly on whichever axis the rail
is running.


Interactive pieces
------------------

* **System** (`components/SystemExplorer.jsx`, `components/SystemScene.jsx`) — every component in
  tiers, with a packet running the route for the selected mode. Record reaches the services; replay
  turns around at the journal and the service tier goes cold; denied stops at the policy. three.js
  is lazy loaded, paused when off screen, and skipped entirely on reduced-motion, low-memory, and
  data-saver devices, where the DOM legend carries the same content.
* **Console** (`components/MediationConsole.jsx`) — journal keys built the way
  `CapabilityJournal.key` builds them, and real SHA-256 hash chaining over the same field order
  `AuditChain` uses. Changing one SQL binding raises a replay mismatch; editing a row fails
  verification from that sequence onward.
* **Capabilities** (`components/CapabilityMatrix.jsx`) — pick a capability, see the exact journal
  key it produces, computed live.
* **Limits** (`components/LimitsLab.jsx`) — scale a sample invocation against the documented Endive
  budgets and see which ceiling trips first.


Content
-------

`src/content/site.js` and `src/content/system.js` hold every claim the page makes, sourced from
`README.md`, `docs/code/web_assembly_runtimes.md`, `docs/code/system_model.md`,
`platform/mediation/README.md`, and `os/server/wasm/endive/README.md`. Update those files when the
system changes.

[liquidGL]: https://github.com/naughtyduk/liquidGL
