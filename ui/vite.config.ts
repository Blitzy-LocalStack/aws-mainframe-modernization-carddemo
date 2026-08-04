/**
 * @file Vite build and dev-server configuration for the CardDemo SPA.
 *
 * Purpose
 * -------
 * Configures the build tool of the CardDemo browser front end: the React 19
 * and TypeScript application that replaces all 21 CICS/BMS online screens,
 * being the 17 base mapsets under `app/bms` plus the 4 extension mapsets.
 * This module settles four things and deliberately nothing else -- which
 * plugin runs, which environment variables may reach the browser, where the
 * production bundle lands, and how the dev server behaves.
 *
 * Three commands declared in `ui/package.json` load it: `npm run dev`
 * (`vite`), `npm run build` (`tsc --noEmit -p tsconfig.json && vite build`)
 * and `npm run preview` (`vite preview`).
 *
 * Cross-file contracts
 * --------------------
 * - `ui/index.html` IS the build input. Vite parses that document, follows
 *   its `<script type="module" src="/src/main.tsx">` reference and derives
 *   the entire module graph from there, which is why no build input is
 *   declared below.
 * - `ui/.env.example` names every variable the SPA reads, and each one
 *   carries the `envPrefix` set below. The two strings must agree.
 * - `ui/tsconfig.node.json` type-checks this file and lends it the Node type
 *   definitions that `ui/tsconfig.json` withholds from `ui/src`.
 * - `build.outDir` opens a three-file chain: `ui/Dockerfile` copies that
 *   directory into the nginx stage that serves it, and `ui/nginx.conf`
 *   returns its `index.html` as a history fallback, so all 21 client-side
 *   routes resolve to one document.
 * - `ui/vitest.config.ts` owns the test configuration, so no `test` block
 *   appears here.
 *
 * State at this checkpoint
 * ------------------------
 * This module is authored ahead of most of the tree it builds, and the
 * migration lands its artifacts in plan order, so of the files named above
 * only `ui/index.html`, `ui/.env.example`, `ui/package.json` and
 * `ui/tsconfig.node.json` exist today. `ui/src/main.tsx`, `ui/tsconfig.json`,
 * `ui/vitest.config.ts`, `ui/Dockerfile` and `ui/nginx.conf` are authored at
 * later indexes of the same plan. One consequence follows and it is not a
 * defect: `npm run build` cannot succeed yet, because its first half
 * type-checks against an absent `ui/tsconfig.json` and `vite build` would
 * then resolve an absent entry module. Nothing here asserts that a build of
 * this tree currently succeeds. The configuration is authored now because the
 * four things it settles -- above all the environment-variable boundary --
 * are decisions the later modules have to be written against, and relocating
 * that boundary once screens read from it means auditing every one of them.
 */

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Resolved Vite configuration for the CardDemo single-page application.
 *
 * Alternatives Considered: the callback form,
 * `defineConfig(({ mode }) => ({ ... }))`, was evaluated and rejected. No
 * option below branches on the build mode, and `ui/.env.example` records why
 * the application needs no such branch either -- Vite exposes the mode to
 * client code as `import.meta.env.MODE`, so a hand-maintained copy would be a
 * second source of truth able to disagree with the build it came from. The
 * callback would also introduce a function, and with it a parameter and a
 * return value that this project's documentation rule obliges every future
 * editor to keep accurate, in exchange for a parameter nothing reads.
 */
export default defineConfig({
  // Alternatives Considered: this plugin list is closed at one entry.
  // `vite-tsconfig-paths` is the omission most likely to look like an
  // oversight, and it is unnecessary because this tree declares no path
  // aliases at all -- every module under `ui/src` is reached by a relative
  // specifier, so that plugin would have no mapping to resolve. A
  // progressive-web-app, legacy-browser, compression, bundle-visualiser or
  // SVG-as-component plugin would each add a supply-chain dependency and a
  // rationale to maintain while answering no requirement of this
  // application; the one SVG in the tree, `ui/public/favicon.svg`, is served
  // as a static asset rather than imported as a component.
  plugins: [react()],

  // Assumptions: this single string is the mechanical boundary between
  // configuration that may ship to a browser and values that must never
  // leave the server. Vite inlines ONLY variables whose names match
  // `envPrefix` into the client bundle, reachable there as
  // `import.meta.env`, and withholds every other variable present in the
  // build environment -- which is what stops a server-side credential that
  // happens to be exported in a developer's shell from being compiled into a
  // public JavaScript file. The value is written here rather than inherited
  // even though it is also Vite's own default, because `ui/.env.example`
  // requires this file to set the same string its variable names carry, and
  // a boundary that a framework default could move under a future major
  // version is not one this project should hold implicitly.
  //
  // Assumptions: the corollary is what makes `ui/.env.example` carry no
  // values at all. Anything named with this prefix ends up in the public
  // bundle and is readable by anyone who loads the page, so the prefix marks
  // a variable as non-secret, publicly discoverable configuration and as
  // nothing else. Widening it -- to the empty string, to an added entry such
  // as a cloud-provider prefix, or to a second prefix of this project's own
  // -- is the one edit to this file that could publish a credential inside a
  // shipped artifact, and it would do so silently, because a leaked variable
  // still produces a working build.
  envPrefix: 'VITE_',

  build: {
    // Assumptions: `ui/Dockerfile` copies this exact directory out of its
    // build stage into the nginx stage that serves it, and the root
    // `.gitignore` ignores an unanchored `dist/`, which is what keeps a built
    // bundle untracked. Neither link is checked while this file is read, so a
    // different value here breaks the image build with no error reported from
    // this side: the build succeeds, the copy finds nothing, and the served
    // image is empty.
    outDir: 'dist',

    // Assumptions: this matches the `target` of both TypeScript projects in
    // the tree -- `ui/tsconfig.json` for `ui/src` and `ui/tsconfig.node.json`
    // for this file -- and the agreement is the property that matters rather
    // than the level itself. When the checker and the bundler disagree, `tsc`
    // accepts syntax that esbuild then down-levels on its own terms, and the
    // divergence surfaces as behaviour in the shipped bundle that no type
    // error predicted.
    //
    // Alternatives Considered: Vite 8 defaults this to
    // `baseline-widely-available`, which selects a set of browsers rather
    // than a language level. That default is the better choice for an
    // application with an unknown audience, and the worse choice here
    // precisely because a browser set cannot be held equal to a `tsconfig`
    // target, leaving the check and the bundle free to drift apart. The
    // browsers in that baseline set support this level, so pinning it
    // surrenders no reach.
    target: 'es2022',

    // Trade-offs: source maps are published beside the bundle. That makes a
    // production stack trace readable against the original TypeScript instead
    // of against minified output, and it costs the readability of this
    // application's own sources to anyone who fetches them. The cost is
    // accepted here on two specific grounds. The bundle holds nothing secret
    // to expose: every value in it arrived through `import.meta.env` and is
    // public by construction, per the prefix boundary above. And the delivery
    // path is a content-delivery distribution in front of a private bucket
    // reached by an origin access control, so a map is served only as a named
    // request rather than advertised by a listable directory. The
    // alternative, omitting them, keeps the sources private and leaves every
    // production stack trace pointing at generated column offsets -- a poor
    // exchange for a system whose business rules are transcribed from COBOL
    // and whose defects therefore have to be read back against that
    // transcription.
    sourcemap: true,

    // Assumptions: every build starts from an empty output directory, so an
    // asset emitted by an earlier build under a different content hash cannot
    // survive into a later bundle and then be served indefinitely. Vite
    // already defaults this on when the output directory sits inside the
    // project root, which it does here; writing it keeps the guarantee from
    // resting on that relationship, because relocating `outDir` outside the
    // root would revoke the default silently. Both the container build stage
    // and CI begin from a fresh checkout where the directory is absent
    // anyway, so the flag earns its place on repeated local builds.
    emptyOutDir: true,

    // Assumptions: no `rollupOptions.input` is declared, and the absence is a
    // decision rather than an omission. `ui/index.html` is the build input:
    // Vite parses that document, follows its `<script type="module">`
    // reference and derives the whole module graph from it, a contract that
    // file states from its own side. Naming an entry here as well would put
    // the same contract in a second place free to drift from the first, and
    // the copy that drifted would be the one that won.
    //
    // Alternatives Considered: `rollupOptions.output.manualChunks` was
    // evaluated and rejected. Hand-splitting chunks changes what a browser
    // fetches, and no measurement of this application exists to say the
    // default split is wrong; tuning it on expectation alone would be a
    // change whose only available justification is that it sounds faster.
    // Vite's default chunking therefore stands until a measured load profile
    // argues otherwise.
  },

  server: {
    // Assumptions: 5173 is Vite's own default port, restated so that the
    // strictness below has a named port to be strict about, and so that a
    // developer's bookmark and this project's own documentation can cite one
    // number for which this file is the source.
    port: 5173,

    // Trade-offs: a port collision fails the command outright instead of
    // being absorbed. Vite's documented behaviour without this flag is to try
    // the next available port automatically, which exchanges a loud failure
    // for a quiet one: the server starts, reports a port nobody asked for,
    // and the bookmark or allow-list naming the expected port then appears to
    // be the broken thing. The compromise accepted is that a developer with
    // something already bound to the port has to free it rather than being
    // moved elsewhere, which is much the shorter of the two diagnoses.
    strictPort: true,

    // Assumptions: the dev server binds the loopback interface only. This is
    // Vite's default, and it is written out because the alternative is a
    // security decision rather than a convenience: binding every interface
    // would publish an unauthenticated dev server, along with whatever a
    // developer's own untracked `ui/.env` points it at, to every host that
    // can reach the machine. Nothing in this project's workflow needs that,
    // because the container image serves the built bundle through nginx
    // rather than through this server.
    host: 'localhost',

    // Alternatives Considered: a dev-only `proxy` entry forwarding an API
    // path prefix to a backend was evaluated and rejected. The SPA addresses
    // the API through the absolute base URL supplied as `VITE_API_BASE_URL`,
    // which is how it reaches the gateway that fronts the internal load
    // balancer in every deployed environment. A proxy would make development
    // the one topology where that is untrue: requests would leave as
    // same-origin, so a cross-origin response header missing at the gateway
    // would stay invisible until a deployment, and the one configuration
    // value this application cannot run without would go untested locally.
    // Keeping development on the deployed path means such a misconfiguration
    // surfaces on a developer's machine instead of in an environment.
  },

  // Assumptions: no `preview` block accompanies the server settings above,
  // because Vite resolves the two decisions that matter by falling back to
  // them -- `preview.strictPort` defaults to `server.strictPort` and
  // `preview.host` defaults to `server.host`, so both propagate without being
  // restated. Only the port differs, defaulting to 4173, and that difference
  // is deliberate on Vite's part: it lets `vite preview` serve a built bundle
  // while `vite dev` still holds 5173. Repeating the inherited fields would
  // create two places to edit for one decision.

  // Assumptions: no `resolve.alias`, no `optimizeDeps.include` and no
  // `resolve.dedupe` entry appears here, and each absence is deliberate. antd
  // 6 needs no React 19 compatibility shim: the patch package its previous
  // major required was removed in version 6, whose published peer range asks
  // only for React 18 or later, which the React version pinned in
  // `ui/package.json` satisfies. There is therefore no patch to alias and
  // nothing for a shim to intercept.
  //
  // Trade-offs: dependency pre-bundling and de-duplication are left at their
  // defaults for antd, dayjs, react and react-dom alike. An entry for any of
  // them would have to name the symptom it fixes -- a duplicated React
  // instance, a pre-bundling failure on one import -- and none has been
  // observed in this tree. Adding one pre-emptively pins behaviour to a
  // problem nobody has, and it then reads identically to a fix for a real
  // one, so the next reader cannot tell whether removing it is safe.

  // Assumptions: three further options are absent by decision. No `define`
  // entry is used, because it substitutes a literal at build time and so
  // bypasses the prefix boundary above entirely, leaving the mechanism that
  // decides what a public bundle may contain answerable to two rules instead
  // of one. `base` stays at its default root, which is where both the
  // content-delivery distribution and `ui/nginx.conf` serve the bundle from,
  // and any other value would break each of them. And no `css` block appears,
  // because antd 6 themes through CSS variables and `ui/src/theme/antdTheme`
  // applies those tokens once through a single provider -- a preprocessor
  // variable here would be a second, competing source of design values.
});
