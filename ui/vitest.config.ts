/**
 * @file Vitest configuration for the CardDemo SPA component test suite.
 *
 * Configures the browser-like environment, transform pipeline, collection
 * patterns, and isolation rules used by the UI component suite.
 *
 * Two commands declared in `ui/package.json` load it: `npm test` (`vitest
 * run`, a single pass) and `npm run test:watch` (`vitest`, watching).
 * `.github/workflows/ui-ci.yml` loads it a third way, in its "Run component tests
 * once and emit JUnit XML" step, which appends `--reporter=junit
 * --outputFile=../ui-reports/vitest.xml` so the run also produces an ingestible
 * report.
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
 * - `ui/src/test/setup.ts` is loaded before every test file. It registers the
 *   jest-dom matchers by importing `@testing-library/jest-dom/vitest`, the
 *   subpath whose module augmentation extends Vitest's own `Assertion`
 *   interface, so `toBeInTheDocument` and `toHaveAttribute` both execute and
 *   type-check without any ambient global.
 * - `ui/tsconfig.json` keeps its `types` list EMPTY, which is what makes
 *   `globals: false` below a contract rather than a preference: no test API is
 *   reachable as an ambient global, so every test names what it uses.
 * - `ui/tsconfig.node.json` type-checks this file and lends it the Node type
 *   definitions that `ui/tsconfig.json` withholds from `ui/src`.
 * - `ui/package.json` owns the watching / non-watching split, so no `watch`
 *   option appears here.
 * - `.github/workflows/ui-ci.yml` selects the reporter on the command line, so
 *   no `reporters` option appears here either.
 *
 * Measured state
 * --------------
 * Every artifact named above exists, `ui/.prettierrc` included, and `npm test`
 * passes: Vitest loads `ui/src/test/setup.ts` and collects three files --
 * `ui/src/routes/cards.test.ts`, `ui/src/api/client.test.ts` and
 * `ui/src/screens/cardScreens.test.tsx` -- for twelve tests in total.
 * `npm run format` runs against the committed Prettier profile and reports no
 * drift.
 *
 * Refactoring Rationale: two successive revisions of this block went stale, which
 * is why it is now written as a measurement rather than as a checkpoint note. The
 * first described the setup module, the test tree and the CI workflow as
 * later-index artifacts and warned that `npm test` could not pass; the second
 * corrected that but recorded one test file with three tests and `ui/.prettierrc`
 * as absent, both of which the tree had already overtaken. A comment that counts
 * tests is a comment that goes stale on the next test, so the counts here are
 * stated as what was measured and the authority is the suite itself -- nothing
 * compiles a comment, and that is exactly why it must be re-read rather than
 * trusted.
 */

// Assumptions: importing from vitest/config adds the typed test block to Vite's
// configuration surface, so misspelled runner options fail type checking.
import { defineConfig, mergeConfig } from 'vitest/config';

// Assumptions: bundler resolution matches the esbuild loader used by Vitest.
import viteConfig from './vite.config';

/**
 * Resolved Vitest configuration for the CardDemo single-page application.
 *
 * Alternatives Considered: a mode callback adds an unused parameter because no
 * test option varies by build mode; the static object keeps the contract direct.
 */
export default mergeConfig(
  // Refactoring Rationale: merging the build configuration gives tests the same
  // React transform as production without duplicating the plugin list.
  viteConfig,
  defineConfig({
    test: {
      // Assumptions: field attributes, keyboard events, and rendered messages
      // require a DOM implementation. Node cannot render components, while
      // Happy DOM would add an otherwise unnecessary dependency.
      environment: 'jsdom',

      // Refactoring Rationale: the runner injects `describe`, `it` and `expect`,
      // and the boundary that matters is enforced ONCE, in the type layer, rather
      // than twice. The hazard is real and worth stating: ambient test globals are
      // declared per PROJECT, not per directory, so declaring them would hand those
      // names to every file `ui/tsconfig.json` covers -- all 21 screens, the theme
      // bridge, the API clients and the message catalog -- and a screen could then
      // call `expect` or `vi` and compile cleanly against a runner that is absent
      // from a deployed browser bundle. What closes that hole is `ui/tsconfig.json`
      // keeping `"types": []`, which is the half of the pair that can actually
      // discriminate, because it governs what the COMPILER believes. This option
      // governs only what exists at run time inside the runner, where no screen is
      // ever loaded, so switching it off added no protection the empty types list
      // was not already providing -- it duplicated a guard at the one layer unable
      // to tell a test from a screen.
      // Trade-offs: the two settings are therefore deliberately on OPPOSITE sides,
      // injected at run time and undeclared at compile time, which is the
      // combination that makes a stray `expect` in a screen a named typecheck
      // failure -- `Cannot find name 'expect'` -- rather than something the runner
      // has an opinion about. The cost is that this pairing looks inconsistent read
      // one line at a time, which is why it is written out here.
      // Assumptions: every test file continues to import what it uses by name, for
      // instance `import { describe, it, expect } from "vitest"`, and that is not
      // made redundant by the injection. The imports are what satisfy the compiler
      // under the empty types list, and they keep a test's dependencies visible to
      // the same lint rules that govern the rest of the tree, where an ambient
      // global is invisible to them. The injection's role is narrower: a file that
      // omits an import fails at typecheck on a named symbol instead of surviving
      // to throw a bare ReferenceError inside a worker mid-suite.
      globals: true,

      // Assumptions: `ui/src/test/setup.ts` imports
      // `@testing-library/jest-dom/vitest`, and the `/vitest` subpath is the
      // load-bearing part. That entry point registers the matchers AND augments
      // Vitest's own `Assertion` interface, so `toBeInTheDocument` and
      // `toHaveAttribute` are typed through the same import that installs them.
      // Registration and declaration therefore cannot drift apart the way they
      // could when a tsconfig `types` entry declared the matchers independently
      // of whether anything had registered them: that arrangement type-checked an
      // assertion and then failed at run time as an undefined matcher, which
      // reads as a broken assertion and sends the reader to the test file instead
      // of to this line. The augmentation reaches every test without a tsconfig
      // entry because this file lives under `ui/src` and is part of that project.
      setupFiles: ['./src/test/setup.ts'],

      // Trade-offs: collection is restricted to TypeScript tests under src so
      // package-root scratch files cannot join the suite accidentally.
      // Refactoring Rationale: one package-root file is named EXPLICITLY beside
      // those two globs rather than admitted by widening either of them.
      // `documentationGate.test.ts` is the negative-probe suite for the JSDoc gate
      // in `eslint.config.js`, and it lives at the root because it needs `node:fs`
      // and `ui/tsconfig.json` withholds Node types from the project covering
      // `src` on purpose -- see the include list in `ui/tsconfig.node.json`, which
      // owns it. Naming the single file keeps the stated property above intact: a
      // root-level glob such as `*.test.ts` would readmit exactly the scratch
      // files that restriction exists to exclude, whereas a literal entry admits
      // one known suite and nothing else.
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'documentationGate.test.ts'],

      // Trade-offs: jsdom has no layout engine, so tests assert semantics and
      // attributes rather than computed styling or the retired 24x80 geometry.
      css: false,

      // Refactoring Rationale: clearing calls and restoring spies prevents
      // order-dependent tests, matching the parity suite's isolation contract.
      clearMocks: true,
      restoreMocks: true,

      // Assumptions: no `reporters` entry appears here, and that omission leaves
      // the choice to the caller. `.github/workflows/ui-ci.yml` takes it, running
      // `npm test -- --reporter=junit --outputFile=../ui-reports/vitest.xml`,
      // while a developer at a terminal wants the default human-readable output. A
      // list set here would be the thing that flag has to override, so leaving it
      // unset serves both callers from one configuration instead of pitting them
      // against each other. Refactoring Rationale: this note called that
      // invocation "planned" and the workflow "later-index"; both have landed, and
      // a comment that describes a live gate as pending invites someone to satisfy
      // it differently.

      // Alternatives Considered: coverage is omitted because package.json pins
      // no provider; enabling it is a manifest and gate decision, not a local
      // configuration toggle.

      // Assumptions: package.json owns run-once versus watch behavior; pinning
      // watch here would override one of those two intentional commands.

      // Alternatives Considered: passWithNoTests stays disabled because a green
      // run with zero UI assertions would violate the repository's no-hidden-
      // skips doctrine; an empty suite must fail loudly.

      // Alternatives Considered: default test and hook timeouts remain until
      // measured evidence justifies a change; larger guesses conceal hangs.
    },
  }),
);
