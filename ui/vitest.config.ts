/**
 * @file Vitest configuration for the CardDemo SPA component test suite.
 *
 * Purpose
 * -------
 * Configures the test runner for the CardDemo browser front end: the React 19
 * and TypeScript application that replaces all 21 CICS/BMS online screens,
 * being the 17 base mapsets under `app/bms` plus the 4 extension mapsets. This
 * module settles four things and deliberately nothing else -- which transform
 * pipeline compiles a component under test, which environment renders it, what
 * counts as a test file, and what state may survive from one test to the next.
 *
 * Two commands declared in `ui/package.json` load it: `npm test` (`vitest
 * run`, a single pass) and `npm run test:watch` (`vitest`, watching). CI loads
 * it a third way, adding `--reporter=junit --outputFile=<path>` on the command
 * line so the run also produces an ingestible report.
 *
 * Why this suite carries the whole online migration
 * -------------------------------------------------
 * The COBOL parity oracle under `tests/` cannot reach these screens. Its three
 * layers cover the batch chain, and it records the reason in as many words:
 * the online `CO*` programs use the CICS command-level API and "cannot run
 * end-to-end without a CICS runtime", which no runner has (`tests/README.md`
 * section 1.1). No golden master therefore exists for a single one of the 21
 * screens, so the per-screen assertions this configuration enables are the
 * only verification those screens receive. They cover three contracts, and
 * every option below exists to keep one of them assertable:
 *
 * 1. Field constraints. Each input's `maxLength` is the width its BMS field
 *    declares (`LENGTH=` on a `DFHMDF`, 902 of them across the 17 base
 *    mapsets) and its copybook `PICTURE` fixes, so the assertion reads a real
 *    attribute back off a rendered element.
 * 2. PF-key behaviour. Enter submits, PF3 goes back, PF4 clears, PF5 saves,
 *    PF7 pages backward, PF8 pages forward and PF12 cancels or signs off, with
 *    PF13-PF24 aliased onto PF01-PF12 -- an aliasing the baseline performs as
 *    a literal 12-way duplicate `EVALUATE` (`app/cpy/CSSTRPFY.cpy` L54-L77,
 *    folding `DFHPF13` back onto `CCARD-AID-PFK01` and so on up to `DFHPF24`).
 *    Asserting it means dispatching genuine keyboard events.
 * 3. Message text. Transformation rule T8 carries every message constant
 *    across character-for-character, so a test compares rendered text against
 *    `ui/src/messages/messages.ts` rather than against a paraphrase of it.
 *
 * Cross-file contracts
 * --------------------
 * - `ui/vite.config.ts` supplies the entire transform pipeline, merged in
 *   below rather than restated.
 * - `ui/src/test/setup.ts` is loaded before every test file and registers the
 *   `@testing-library/jest-dom` matchers.
 * - `ui/tsconfig.json` lists `vitest/globals` and `@testing-library/jest-dom`
 *   in its `types`, which is what makes `globals` and `setupFiles` below
 *   contracts rather than preferences.
 * - `ui/tsconfig.node.json` type-checks this file and lends it the Node type
 *   definitions that `ui/tsconfig.json` withholds from `ui/src`.
 * - `ui/package.json` owns the watching / non-watching split, so no `watch`
 *   option appears here.
 * - `.github/workflows/ui-ci.yml` selects the reporter on the command line, so
 *   no `reporters` option appears here either.
 *
 * State at this checkpoint
 * ------------------------
 * This module is authored ahead of most of the tree it tests, and the
 * migration lands its artifacts in plan order. Present today: the merged
 * `ui/vite.config.ts`, `ui/package.json`, `ui/tsconfig.json`,
 * `ui/tsconfig.node.json` and `ui/src/messages/messages.ts`. Authored at later
 * indexes of the same plan: `ui/src/test/setup.ts`, every file the `include`
 * globs below are written to match, `ui/eslint.config.js`, `ui/.prettierrc`
 * and `.github/workflows/ui-ci.yml`. One consequence follows and it is not a
 * defect: `npm test` cannot pass yet, because `vitest run` resolves an absent
 * setup file and then collects no test files. Nothing here asserts that a test
 * run of this tree currently succeeds. The configuration is authored now
 * because the four things it settles are decisions all 21 screen tests have to
 * be written against, and relocating the environment or the test-file
 * convention once those tests exist means editing every one of them.
 */

// Assumptions: both helpers come from `vitest/config` rather than from `vite`,
// and the distinction is load-bearing rather than stylistic. Vitest's own
// config entry point declares `interface UserConfig { test?: VitestInlineConfig
// }` as an augmentation of the `vite` module, so importing from it is what puts
// the `test` block below into the type system at all. Taken from `vite`
// instead, `defineConfig` would treat `test` as an unchecked excess property
// and a misspelled option would configure nothing while still compiling --
// exactly the failure `ui/tsconfig.node.json` enables strictness to catch.
// Verified against this pin: renaming `environment` to `environmnet` is
// reported as TS2769 naming the correct spelling, and the build stops.
import { defineConfig, mergeConfig } from 'vitest/config';

// Assumptions: this is `ui/vite.config.ts`, imported whole for the merge
// below. The extensionless specifier resolves because `ui/tsconfig.node.json`
// sets `moduleResolution: "bundler"`, and because Vitest bundles this file
// with esbuild before executing it rather than handing it to Node's own ESM
// resolver -- the same reason that project does not use `nodenext`.
import viteConfig from './vite.config';

/**
 * Resolved Vitest configuration for the CardDemo single-page application.
 *
 * Alternatives Considered: the plain object form is used rather than the
 * callback form `defineConfig(({ mode }) => ({ ... }))`. Nothing below branches
 * on the build mode, so the callback would add a function -- and with it a
 * parameter and a return value that this project's documentation rule obliges
 * every future editor to keep accurate -- in exchange for an argument nothing
 * reads. `ui/vite.config.ts` rejects the callback form on the same grounds, so
 * the two configuration files in this directory keep one shape between them.
 */
export default mergeConfig(
  // Refactoring Rationale: the build configuration is composed in rather than
  // recreated here. `ui/vite.config.ts` already declares the one plugin that
  // turns TSX into something a runtime can execute, and a second, independent
  // plugin list would let the two drift -- a component would then compile one
  // way under `npm test` and another under `npm run build`, at which point a
  // passing test stops being evidence about the artifact that ships. The
  // damage runs in both directions: a green suite over a broken bundle, or a
  // red suite over a sound one. Merging cannot drift, because one list exists.
  //
  // Alternatives Considered: importing `@vitejs/plugin-react` here and
  // registering it a second time, which Vitest also documents. It reads more
  // explicitly at the cost of reintroducing the second source of truth this
  // merge exists to remove -- two lists that agree only for as long as
  // somebody remembers to edit both.
  viteConfig,
  defineConfig({
    test: {
      // Assumptions: the three contracts named in this file's overview are all
      // asserted against a document object model, so one has to exist. The
      // `maxLength` a screen puts on an input is an attribute read back off a
      // rendered element; the PF-key contract is verified by dispatching real
      // keyboard events through `@testing-library/user-event`, including the
      // aliased PF13-PF24 range that `app/cpy/CSSTRPFY.cpy` L54-L77 folds onto
      // PF01-PF12; and a verbatim message is matched against text a component
      // actually rendered. jsdom supplies all three.
      //
      // Alternatives Considered: `'node'`, which is Vitest's own default and
      // so is what this line overrides. It cannot render a component at all,
      // so all 21 screen tests would fail on the render call before reaching
      // an assertion, and the suite would report a configuration fault as 21
      // component defects. The lighter-weight Happy DOM implementation was
      // rejected too: it is faster, and it is absent from the pinned dependency
      // set in `ui/package.json`, so adopting it would mean adding a package to
      // buy speed on a suite whose cost is not its bottleneck -- and because
      // `npm ci` installs strictly from the lock file, naming an environment
      // the lock does not carry would fail the CI install outright.
      environment: 'jsdom',

      // Assumptions: this is a contract with `ui/tsconfig.json`, which lists
      // `"vitest/globals"` in its `types` array. That entry declares
      // `describe`, `it`, `expect` and the lifecycle hooks as ambient, and it
      // is only truthful while this option is on. Turning it off would not
      // fail here -- it would leave that type entry asserting an API the
      // runner no longer injects, so every test file would need explicit
      // imports of those names while the compiler went on believing the
      // tsconfig. The two settings move together or not at all.
      globals: true,

      // Assumptions: `ui/src/test/setup.ts` registers the
      // `@testing-library/jest-dom` matchers, and the second entry in
      // `ui/tsconfig.json`'s `types` array -- `"@testing-library/jest-dom"` --
      // is what declares those matchers to the compiler. The two have to hold
      // together: with this file unloaded, `toBeInTheDocument` and
      // `toHaveAttribute` still type-check against that declaration and then
      // fail at run time as undefined matchers, which reads as a broken
      // assertion rather than as a missing registration and sends the reader
      // to the test file instead of to this line.
      setupFiles: ['./src/test/setup.ts'],

      // Assumptions: two globs rather than one, and both rooted at `src`. The
      // migration plan puts the per-screen tests at `ui/src/test/*.test.tsx`,
      // which the second glob covers, and the pattern is deliberately wider
      // than that one directory so a test may instead sit beside the screen it
      // exercises -- `src/screens/accountUpdate/index.test.tsx` is collected
      // without this line being touched, which matters for a tree of 21
      // screens whose largest carries 128 fields. Both extensions are listed
      // because a hook, an API-client or a message-catalog test needs no JSX.
      //
      // Trade-offs: replacing Vitest's default pattern narrows collection to
      // `src`, and that narrowing is the point rather than a side effect. The
      // default matches any `*.test.*` the exclusions do not stop, so a
      // scratch file left at the package root would join the suite silently.
      // The compromise accepted is the mirror image -- a test authored outside
      // `src` is skipped rather than reported -- which is precisely why the
      // absence of a `passWithNoTests` escape, recorded below, matters.
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],

      // Trade-offs: stylesheets are not processed for a test run, which bounds
      // what a test may assert to semantics and attributes -- an element's
      // role, its accessible name, its text, its `maxLength`, its disabled
      // state -- and never a computed colour, width or position. Two reasons
      // make that the correct bound rather than a limitation. jsdom implements
      // no cascade and no layout engine, so a computed-style assertion reads
      // back the declaration it was handed rather than anything a browser
      // would paint, making it a test of the test. And antd 6 themes through
      // CSS variables that `ui/src/theme/antdTheme` applies once through a
      // single provider, so the values a colour assertion would chase are
      // injected at run time by the component library rather than authored
      // anywhere in this tree.
      //
      // Assumptions: this bound is also what makes gap G1 acceptable. The
      // migration replaces the fixed 24x80 character grid every mapset
      // declares with a responsive layout on purpose, preserving field
      // grouping, reading order and tab order while abandoning
      // pixel-for-character positioning. A position-based assertion would
      // therefore be testing the one property the design deliberately gave
      // up, and closing this option removes the means to write one. Vitest
      // already defaults to processing no CSS; the value is written out so
      // that the bound does not rest on a framework default a future major
      // version could move, which is the same reason `ui/vite.config.ts`
      // writes out its own environment-variable prefix.
      css: false,

      // Assumptions: no mock state survives a test. `clearMocks` empties every
      // recorded call and instance before each test and `restoreMocks` puts a
      // spied original implementation back, so a stubbed axios client or a
      // spied navigation hook cannot carry a queued response or a call count
      // into the next test. Both default to off, so both are real overrides
      // rather than restatements.
      //
      // Refactoring Rationale: this is the direct analogue of the isolation
      // the COBOL parity oracle already enforces on the batch side, where each
      // test provisions its own workspace and tears it down so nothing is
      // shared, and a green parallel run is what proves it (`tests/README.md`
      // section 11). The failure avoided is specific and expensive: leaked
      // mock state makes a suite order-dependent, so it passes in file order
      // and fails under a shuffled or filtered run, and the test that fails is
      // never the test that caused it.
      clearMocks: true,
      restoreMocks: true,

      // Assumptions: no `reporters` entry appears here, and that omission is
      // what leaves CI free to choose one. `.github/workflows/ui-ci.yml`
      // invokes `vitest run --reporter=junit --outputFile=<path>` so the run
      // yields an ingestible report, while a developer at a terminal wants the
      // default human-readable output. A list set here would be the thing that
      // flag has to override, so leaving it unset serves both callers from one
      // configuration instead of pitting them against each other.

      // Alternatives Considered: a `coverage` block was evaluated and left
      // out. Coverage requires a provider package, and none is in the pinned
      // dependency set in `ui/package.json` -- so with `npm ci` installing
      // strictly from the lock file, naming one here would fail the install
      // rather than measure anything, and no coverage gate exists in
      // `.github/workflows/ui-ci.yml` for it to feed. Enabling coverage is
      // therefore a dependency decision belonging to the manifest, not a
      // switch this file may flip on its own.

      // Assumptions: no `watch` entry appears here because `ui/package.json`
      // already owns that split -- its `test` script runs `vitest run` for a
      // single pass and `test:watch` runs `vitest` to watch. Vitest's own
      // default is to watch, so pinning it here would answer in one place a
      // question those two scripts answer differently, and a CI job that
      // inherited watching would not fail: it would hold a runner open,
      // reporting nothing, until the job timed out.

      // Alternatives Considered: `passWithNoTests` was evaluated and
      // deliberately not enabled, even though it would make a run at this
      // checkpoint exit 0 while the files the globs above match are still
      // being authored. House doctrine is explicitly the other way: the parity
      // oracle gates a run in which nothing executed as a failure, having
      // found that a suite verifying nothing used to exit 0 with every test
      // "merely SKIPPED -- a misleading 'green' that proved nothing"
      // (`tests/README.md` section 6). A configuration that reported success
      // over zero assertions about 21 screens would be that same defect
      // wearing this file's name, so an empty run is left to fail loudly and
      // be answered by authoring the tests.

      // Alternatives Considered: neither `testTimeout` nor `hookTimeout` is
      // overridden. Both keep Vitest's defaults, and raising either without
      // having watched a test exceed it would encode a guess as configuration
      // -- worse, a raised timeout conceals the slow render or unresolved
      // promise it was raised for instead of surfacing it, so the symptom
      // returns later as flakiness with its cause now hidden by this file.
    },
  }),
);
