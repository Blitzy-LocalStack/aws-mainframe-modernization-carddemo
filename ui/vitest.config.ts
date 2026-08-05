/**
 * @file Vitest configuration for the CardDemo SPA component test suite.
 *
 * Configures the browser-like environment, transform pipeline, collection
 * patterns, and isolation rules used by the UI component suite.
 *
 * Two commands declared in `ui/package.json` load it: `npm test` (`vitest
 * run`, a single pass) and `npm run test:watch` (`vitest`, watching). The
 * later-index CI workflow is required to load it a third way, adding
 * `--reporter=junit --outputFile=<path>` so the run also produces an
 * ingestible report.
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
 * State at this checkpoint
 * ------------------------
 * Every artifact named above exists, and `npm test` passes: Vitest loads
 * `ui/src/test/setup.ts`, collects `ui/src/routes/cards.test.ts` and runs its
 * three tests. `ui/.prettierrc` is the one file named anywhere in this package's
 * configuration that remains absent, so `npm run format` runs Prettier on its
 * defaults rather than on a committed profile.
 *
 * Refactoring Rationale: an earlier revision of this block described the setup
 * module, the test tree and the CI workflow as later-index artifacts and warned
 * that `npm test` could not pass. All three have landed, so the warning had
 * become the opposite of the truth -- the shape of stale claim that survives
 * unnoticed because nothing compiles a comment.
 */

// Assumptions: importing from vitest/config adds the typed test block to Vite's
// configuration surface, so misspelled runner options fail type checking.
import { defineConfig, mergeConfig } from "vitest/config";

// Assumptions: bundler resolution matches the esbuild loader used by Vitest.
import viteConfig from "./vite.config";

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
      environment: "jsdom",

      // Refactoring Rationale: injecting `describe`, `it` and `expect` as
      // ambient globals is refused, and the reason is a boundary rather than a
      // style preference. Ambient test globals are declared per PROJECT, not per
      // directory, so the `"vitest/globals"` types entry that used to accompany
      // this option handed those names to every file `ui/tsconfig.json` covers --
      // all 21 screens, the theme bridge, the API clients and the message
      // catalog included. A screen could then call `expect` or `vi` and compile
      // cleanly, having imported a runner that is absent from a deployed browser
      // bundle. Turning the injection off is what lets that types list be empty,
      // which is what closes the hole; the two settings move together or not at
      // all.
      // Trade-offs: every test file now imports what it uses by name, for
      // instance `import { describe, it, expect } from 'vitest'`. That is a line
      // per file across 21 screen tests, accepted because an explicit import is
      // also what makes a test's dependencies visible to the same lint rules that
      // govern the rest of the tree, where an ambient global is invisible to
      // them.
      globals: false,

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
      setupFiles: ["./src/test/setup.ts"],

      // Trade-offs: collection is restricted to TypeScript tests under src so
      // package-root scratch files cannot join the suite accidentally.
      include: ["src/**/*.test.ts", "src/**/*.test.tsx"],

      // Trade-offs: jsdom has no layout engine, so tests assert semantics and
      // attributes rather than computed styling or the retired 24x80 geometry.
      css: false,

      // Refactoring Rationale: clearing calls and restoring spies prevents
      // order-dependent tests, matching the parity suite's isolation contract.
      clearMocks: true,
      restoreMocks: true,

      // Assumptions: no `reporters` entry appears here, and that omission leaves
      // the later-index CI workflow free to choose one. The planned invocation
      // is `vitest run --reporter=junit --outputFile=<path>`, while a developer
      // at a terminal wants the default human-readable output. A list set here
      // would be the thing that flag has to override, so leaving it unset serves
      // both callers from one configuration instead of pitting them against
      // each other.

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
