/**
 * @file Negative probes for the TypeScript documentation gate declared in `ui/eslint.config.js`.
 *
 * Purpose
 * -------
 * `ui/eslint.config.js` is the mechanical half of user Rule 1 (Explainability) for this
 * package, and `npm run lint` running clean is the evidence normally offered that the gate
 * works. That evidence is one-sided: a run reports what the configuration DID find and says
 * nothing about what it CANNOT find. Every gate defect this file was written in response to was
 * of the second kind -- the gate was green while a module-private function carried no
 * docstring, while a `@param` carried no type, while an `eslint-disable` comment switched
 * the whole thing off for a file, and while nine modules carried no module-entry docstring at
 * all. Each probe below asserts that ONE prohibited shape is reported, so a future relaxation of
 * the configuration fails here instead of passing silently.
 *
 * Refactoring Rationale: the module-entry clause is the newest of those four and the reason the
 * last three probes exist. It was for a time left to review rather than to the linter, and review
 * did not catch it -- which is the general case this file argues from, so the probes were added
 * with the rule rather than after it.
 *
 * Why this belongs in the test suite rather than in review
 * -------------------------------------------------------
 * `docs/CODE_DOCUMENTATION_STANDARD.md` records that no linter can judge docstring ACCURACY and
 * leaves that half to review. Presence and tag coverage are the half a linter can judge, and
 * this file pins them, so review attention is never spent re-deriving whether the mechanical
 * half is switched on.
 *
 * How the probes are executed
 * ---------------------------
 * Every probe source is written to disk under `ui/src` BEFORE any lint runs, then each is linted
 * through the ESLint Node API against the package's OWN configuration -- not a copy of it and not
 * a reconstruction of its rules -- and the directory is removed once.
 *
 * Assumptions: the sources have to exist under `ui/src`, and they all have to exist before the
 * FIRST lint. Both halves were measured rather than assumed. `ui/eslint.config.js` gives
 * `@typescript-eslint/parser` an explicit `parserOptions.project` list, so a path outside
 * `ui/tsconfig.json`'s `include: ["src"]` is reported as belonging to no project and the run dies
 * with a parser error before any `jsdoc/*` rule is reached -- which is exactly what
 * `ESLint#lintText` with a synthetic `filePath` produces. And the parser resolves that project's
 * file list ONCE per process and reuses it, so a source created after the first lint is reported
 * with the same "not found in any of the provided project(s)" parser error even though it is
 * sitting in the right directory. Writing every probe up front is what makes each probe report
 * the rule it is measuring instead of that error.
 *
 * Alternatives Considered: committing the probe sources as permanent fixtures under a directory
 * added to `globalIgnores`. Rejected because an ignored fixture is linted by no configuration at
 * all, so the probes would assert against a default rule set rather than against the gate -- the
 * one thing they exist to measure. Committing them WITHOUT ignoring them is worse still: `npm
 * run lint` would then fail on the fixtures by design, and a gate whose own repository cannot
 * pass it teaches a reader to ignore its output.
 *
 * Trade-offs: these probes mutate the working tree for the duration of this test file, which no
 * other test in this suite does. The cost is accepted because every alternative measures
 * something other than the gate. It is bounded three ways: the directory is removed in an
 * `afterAll` that runs whatever the outcome, its name is fixed and unmistakably not authored
 * source, and the repository's `.gitignore` carries that name so an interrupted run can neither
 * be committed nor break a later `npm run lint`.
 *
 * Why this module sits at the package root rather than under `ui/src/test`
 * ----------------------------------------------------------------------
 * Assumptions: it needs `node:fs`, `node:path` and `node:url`, and `ui/tsconfig.json` sets
 * `"types": []`. That empty list is load-bearing rather than incidental — ambient types are
 * declared per PROJECT, so pulling Node's declarations into the project that covers `src` would
 * hand `process` and `Buffer` to all 21 screens and let a browser bundle reference them and still
 * compile. This module is therefore a member of `ui/tsconfig.node.json`, the project that already
 * owns `vite.config.ts` and `vitest.config.ts` and already sets `"types": ["node"]`, and
 * `ui/vitest.config.ts` names it explicitly in `include` so collection stays a named list rather
 * than a root-level glob.
 * Alternatives Considered: keeping the module under `src/test` and reaching for Node types there.
 * Both available forms were rejected. Adding `"node"` to `ui/tsconfig.json`'s `types` is the leak
 * described above. A file-local `/// <reference types="node" />` looks narrower but is not: a
 * triple-slash reference pulls the declaration file into the whole PROGRAM, so the screens in that
 * same project acquire the globals anyway, and the narrowing would be appearance only.
 * Trade-offs: grouping a test with the build tooling reads oddly beside the screen tests, and it
 * is the honest grouping — what this module tests is the lint configuration, not the application.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ESLint } from 'eslint';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

/**
 * Absolute path of the package root, resolved from this module rather than from the process
 * working directory.
 *
 * Assumptions: the runner's working directory is the package root today, but a probe depending
 * on that would break the moment the suite were invoked from the repository root. This module
 * sits AT the package root, so its own directory is `ui/`.
 */
const PACKAGE_ROOT: string = dirname(fileURLToPath(import.meta.url));

/**
 * Directory the probe sources are written into, relative to the package root.
 *
 * Assumptions: it is under `src` because that is what `ui/tsconfig.json` includes, and the
 * double-underscore name matches the entry in the repository's `.gitignore` so an interrupted run
 * leaves nothing committable.
 */
const PROBE_DIR = 'src/__documentation_gate_probe__';

/**
 * The probe sources, keyed by file name, each held as one array entry per line.
 *
 * Assumptions: the map is a module constant rather than nine inline literals so that
 * {@link beforeAll} can write every one of them before the first lint, which the parser's
 * once-per-process project resolution requires. Each entry is annotated with the single rule it
 * exists to provoke.
 *
 * Assumptions: every negative probe carries a conforming `@file` block of its own, even though
 * none of them is measuring the module-entry rule except the three that say so. Without it each
 * probe would report two rules -- the one it exists to provoke and a missing module overview --
 * and a probe that reports two findings no longer isolates the one it names.
 */
const PROBE_SOURCES: ReadonlyMap<string, readonly string[]> = new Map([
  // jsdoc/require-jsdoc on a module-private function. Assumptions: the helper is referenced by an
  // export so that no unused-symbol rule can report it instead, which would let the probe pass
  // for the wrong reason.
  [
    'privateFunction.ts',
    [
      '/**',
      ' * @file Probe: a module-private function that carries no docstring.',
      ' */',
      '',
      'function normalise(value: string): string {',
      '  return value.trim();',
      '}',
      '',
      'export const surface = { normalise };',
    ],
  ],

  // jsdoc/require-jsdoc on an anonymous callback ARGUMENT. Assumptions: the arrow is a bare
  // CallExpression argument, which is the position the positional selectors of an earlier
  // configuration could not match -- the exact shape that escaped the gate once already, in the
  // message catalog.
  [
    'anonymousCallback.ts',
    [
      '/**',
      ' * @file Probe: an anonymous callback argument that carries no docstring.',
      ' */',
      '',
      '/**',
      ' * Maps every entry to its trimmed form.',
      ' *',
      ' * @param {string[]} values - The values to trim.',
      ' * @returns {string[]} The trimmed values.',
      ' */',
      'export function trimAll(values: string[]): string[] {',
      '  return values.map((value) => {',
      '    return value.trim();',
      '  });',
      '}',
    ],
  ],

  // jsdoc/require-param-type: a documented parameter whose tag carries no type.
  [
    'untypedParam.ts',
    [
      '/**',
      ' * @file Probe: a documented parameter whose tag carries no type.',
      ' */',
      '',
      '/**',
      ' * Doubles a number.',
      ' *',
      ' * @param value - The number to double.',
      ' * @returns {number} The doubled number.',
      ' */',
      'export function double(value: number): number {',
      '  return value * 2;',
      '}',
    ],
  ],

  // jsdoc/require-returns-type: a documented return value whose tag carries no type.
  [
    'untypedReturn.ts',
    [
      '/**',
      ' * @file Probe: a documented return value whose tag carries no type.',
      ' */',
      '',
      '/**',
      ' * Doubles a number.',
      ' *',
      ' * @param {number} value - The number to double.',
      ' * @returns The doubled number.',
      ' */',
      'export function double(value: number): number {',
      '  return value * 2;',
      '}',
    ],
  ],

  // linterOptions.noInlineConfig: the directive must neither suppress the rule nor survive the
  // run looking load-bearing.
  [
    'inlineDisable.ts',
    [
      '/**',
      ' * @file Probe: an inline disable directive aimed at the docstring gate.',
      ' */',
      '',
      '/* eslint-disable jsdoc/require-jsdoc */',
      'function normalise(value: string): string {',
      '  return value.trim();',
      '}',
      '',
      'export const surface = { normalise };',
    ],
  ],

  // jsdoc/require-file-overview, `mustExist`: a module whose functions are fully documented but
  // which carries no module overview. Assumptions: the leading block is an untagged DESCRIPTION
  // rather than no comment at all, because that is the exact shape the gate was once left off
  // for -- `src/messages/messages.ts` opened this way -- so the probe measures the decision that
  // reversed it rather than a straw man. Every function here is documented, so the only clause
  // this file can breach is the module-entry one.
  [
    'missingFileOverview.ts',
    [
      '/**',
      ' * An overview that reads like one and carries no tag.',
      ' */',
      '',
      '/**',
      ' * Trims a value.',
      ' *',
      ' * @param {string} value - The value to trim.',
      ' * @returns {string} The trimmed value.',
      ' */',
      'export function trimOne(value: string): string {',
      '  return value.trim();',
      '}',
    ],
  ],

  // jsdoc/require-file-overview, `initialCommentsOnly`: the tag exists but does not head the file.
  // Assumptions: an exported constant precedes it rather than a function, so `jsdoc/require-jsdoc`
  // cannot report this file instead and let the probe pass for the wrong reason.
  [
    'lateFileOverview.ts',
    [
      'export const PROBE_VERSION = 1;',
      '',
      '/**',
      ' * @file An overview that arrives after the first statement.',
      ' */',
    ],
  ],

  // jsdoc/require-file-overview, `preventDuplicates`: two module overviews in one file, which is
  // two answers to one question and therefore no answer.
  [
    'duplicateFileOverview.ts',
    [
      '/**',
      ' * @file The first overview.',
      ' */',
      '',
      '/**',
      ' * @file The second overview, disagreeing with the first by existing.',
      ' */',
      '',
      'export const PROBE_MARKER = 1;',
    ],
  ],

  // The positive control. Assumptions: this is what makes the eight negative probes meaningful --
  // a configuration that failed every file would satisfy all of them while enforcing nothing
  // usable, and only a clean run on conforming source rules that out. It carries the module
  // overview as well as the function ones, so it is also the positive side of the module-entry
  // gate: the three probes above prove the rule reports each way of getting the tag wrong, and
  // this one proves it accepts the tag done right.
  [
    'conforming.ts',
    [
      '/**',
      ' * @file Probe: a module that satisfies every clause the gate decides.',
      ' */',
      '',
      '/**',
      ' * Trims every entry of a list.',
      ' *',
      ' * @param {string[]} values - The values to trim.',
      ' * @returns {string[]} The trimmed values.',
      ' */',
      'export function trimAll(values: string[]): string[] {',
      '  /**',
      '   * Trims one entry.',
      '   *',
      '   * @param {string} value - The entry to trim.',
      '   * @returns {string} The trimmed entry.',
      '   */',
      '  const trim = (value: string): string => value.trim();',
      '',
      '  return values.map(trim);',
      '}',
    ],
  ],
]);

/**
 * Per-probe timeout, in milliseconds.
 *
 * Refactoring Rationale: the probes first ran on the suite's 5-second default and the FIRST one
 * timed out while the other five passed. The cost is not in the assertion but in the first
 * `lintFiles` call of the process, which builds a TypeScript program for the whole `src` project
 * before a single rule executes -- measured at roughly four seconds on its own, and longer while
 * the rest of the suite occupies the same cores. Every later probe reuses that program and
 * finishes in milliseconds, so the timeout is a property of the first call rather than of the
 * work being timed.
 * Alternatives Considered: raising `testTimeout` in `ui/vitest.config.ts`. Rejected because that
 * loosens the bound for every screen test as well, and a screen test that hangs should still fail
 * in five seconds; the cost belongs to these probes and the allowance is scoped to them.
 * Trade-offs: the value is set well above the measured cost rather than close to it, because a
 * timeout tuned tight to one machine's timing is a test that fails on a loaded runner for no
 * reason a reader could act on. A genuinely hung probe still fails, just later.
 */
const PROBE_TIMEOUT_MS = 60_000;

/**
 * Writes every probe source to disk.
 *
 * Assumptions: this runs before the first lint because the parser resolves the TypeScript
 * project's file list once per process; a source created later is reported as belonging to no
 * project regardless of where it sits.
 */
function writeEveryProbeSource(): void {
  const absoluteDir = join(PACKAGE_ROOT, PROBE_DIR);
  mkdirSync(absoluteDir, { recursive: true });
  for (const [fileName, sourceLines] of PROBE_SOURCES) {
    writeFileSync(join(absoluteDir, fileName), `${sourceLines.join('\n')}\n`, 'utf8');
  }
}

/**
 * Removes the probe directory, whatever the outcome of the probes.
 *
 * Assumptions: the removal is unconditional and recursive. A probe that threw mid-assertion would
 * otherwise leave a deliberately non-conforming source under `src`, so the next `npm run lint`
 * would fail inside the gate's own package on a file nobody authored.
 */
function removeProbeDirectory(): void {
  rmSync(join(PACKAGE_ROOT, PROBE_DIR), { recursive: true, force: true });
}

/**
 * Lints one probe source against the package's own ESLint configuration.
 *
 * @param {string} fileName - The probe's file name, which must be a key of
 *   {@link PROBE_SOURCES} so that {@link writeEveryProbeSource} has already created it.
 * @returns {Promise<string[]>} The rule identifier of every message the gate produced, in report
 *   order, with a message carrying no rule identifier rendered as `(directive)` so an inert
 *   inline directive is distinguishable from a rule report.
 */
async function lintProbe(fileName: string): Promise<string[]> {
  // Assumptions: `cwd` is the package root so ESLint discovers `ui/eslint.config.js` by its own
  // resolution. Passing a config path explicitly was rejected -- it would let these probes keep
  // passing against a file the real `npm run lint` no longer uses.
  const eslint = new ESLint({ cwd: PACKAGE_ROOT });
  const results = await eslint.lintFiles([join(PROBE_DIR, fileName)]);

  const ruleIds: string[] = [];
  for (const result of results) {
    for (const message of result.messages) {
      ruleIds.push(message.ruleId ?? '(directive)');
    }
  }
  return ruleIds;
}

/**
 * Asserts a module-private function with no docstring is reported.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnUndocumentedPrivateFunction(): Promise<void> {
  expect(await lintProbe('privateFunction.ts')).toContain('jsdoc/require-jsdoc');
}

/**
 * Asserts an anonymous callback argument with no docstring is reported.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnUndocumentedCallback(): Promise<void> {
  expect(await lintProbe('anonymousCallback.ts')).toContain('jsdoc/require-jsdoc');
}

/**
 * Asserts a documented parameter whose tag carries no type is reported.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnUntypedParameterTag(): Promise<void> {
  expect(await lintProbe('untypedParam.ts')).toContain('jsdoc/require-param-type');
}

/**
 * Asserts a documented return value whose tag carries no type is reported.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnUntypedReturnTag(): Promise<void> {
  expect(await lintProbe('untypedReturn.ts')).toContain('jsdoc/require-returns-type');
}

/**
 * Asserts an inline disable directive neither suppresses the rule nor survives the run.
 *
 * Assumptions: both halves of `linterOptions.noInlineConfig` are asserted. The directive must not
 * suppress the rule, and the now-inert directive must itself be reported so it cannot survive a
 * run still looking load-bearing. The second half arrives as a message with a null rule
 * identifier, which {@link lintProbe} renders as `(directive)`, so the two stay distinguishable.
 *
 * @returns {Promise<void>} Resolves once both assertions have run.
 */
async function ignoresAnInlineDisableDirective(): Promise<void> {
  const ruleIds = await lintProbe('inlineDisable.ts');

  expect(ruleIds).toContain('jsdoc/require-jsdoc');
  expect(ruleIds).toContain('(directive)');
}

/**
 * Asserts a module carrying no module-entry overview is reported.
 *
 * Assumptions: the probe's own functions are fully documented, so a report here can only be the
 * module-entry clause. This is the probe that pins the decision to switch `require-file-overview`
 * on: the rule was once off because a leading untagged description was thought to be enough, and
 * this asserts that the shape which prompted that reasoning is now reported.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAModuleWithNoOverview(): Promise<void> {
  expect(await lintProbe('missingFileOverview.ts')).toContain('jsdoc/require-file-overview');
}

/**
 * Asserts a module overview that does not head its file is reported.
 *
 * Assumptions: this pins `initialCommentsOnly`, which is the sub-check that keeps the tag a MODULE
 * overview rather than a comment that happens to appear somewhere in the module. Without it a tag
 * buried beneath the code would satisfy the rule while documenting nothing a reader opens the file
 * to find.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnOverviewThatDoesNotHeadTheFile(): Promise<void> {
  expect(await lintProbe('lateFileOverview.ts')).toContain('jsdoc/require-file-overview');
}

/**
 * Asserts a second module overview in one file is reported.
 *
 * Assumptions: this pins `preventDuplicates`. Two overviews are two answers to the question the
 * rule asks, and they are free to drift apart, so the rule treats the second as a defect rather
 * than as extra documentation.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsADuplicateModuleOverview(): Promise<void> {
  expect(await lintProbe('duplicateFileOverview.ts')).toContain('jsdoc/require-file-overview');
}

/**
 * Asserts a fully documented module is reported clean.
 *
 * Assumptions: this is the positive side of every gate this file measures, the module-entry rule
 * included -- the probe carries its own `@file` block, so a clean result proves the rule accepts a
 * correct overview rather than merely reporting every file it sees.
 *
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function acceptsAConformingModule(): Promise<void> {
  expect(await lintProbe('conforming.ts')).toEqual([]);
}

/**
 * Registers the probes.
 *
 * Assumptions: each probe is a named, documented function passed to `it` rather than an inline
 * arrow, matching the house style already used by `ui/src/routes/cards.test.ts`. That style is
 * what keeps this file itself conforming to the gate it measures, which would otherwise report
 * every anonymous callback here.
 */
function theDocumentationGateRejectsEveryProhibitedShape(): void {
  beforeAll(writeEveryProbeSource);
  afterAll(removeProbeDirectory);

  it(
    'reports a module-private function that carries no docstring',
    reportsAnUndocumentedPrivateFunction,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports an anonymous callback argument that carries no docstring',
    reportsAnUndocumentedCallback,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports a documented parameter whose tag carries no type',
    reportsAnUntypedParameterTag,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports a documented return value whose tag carries no type',
    reportsAnUntypedReturnTag,
    PROBE_TIMEOUT_MS,
  );
  it(
    'ignores an inline disable directive and reports it as inert',
    ignoresAnInlineDisableDirective,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports a module that carries no module-entry overview',
    reportsAModuleWithNoOverview,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports a module overview that does not head its file',
    reportsAnOverviewThatDoesNotHeadTheFile,
    PROBE_TIMEOUT_MS,
  );
  it(
    'reports a duplicate module-entry overview',
    reportsADuplicateModuleOverview,
    PROBE_TIMEOUT_MS,
  );
  it('accepts a fully documented module', acceptsAConformingModule, PROBE_TIMEOUT_MS);
}

describe(
  'the TypeScript documentation gate rejects every prohibited shape',
  theDocumentationGateRejectsEveryProhibitedShape,
);
