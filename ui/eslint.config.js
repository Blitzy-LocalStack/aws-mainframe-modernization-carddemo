/**
 * @file ESLint flat configuration for the CardDemo browser front end, and the
 * machine half of this project's single user-specified rule.
 *
 * Purpose
 * -------
 * This module exists to enforce Rule 1 "Explainability", and that is its whole
 * reason for being. Nothing in the work of replacing the 21 CICS/BMS online
 * screens with a React single-page application would produce it: the migration
 * plan lists this exact path among the artifacts the rule forces into scope, so
 * that the rule's validation gate is machine-checked rather than aspirational.
 * Rule 1 closes by saying that code missing either its docstring or its decision
 * rationale fails review. The docstring half of that sentence is decided here, by
 * `eslint-plugin-jsdoc`, on every build.
 *
 * Who runs it
 * -----------
 * The `lint` script declared in `ui/package.json`, which is exactly
 * `eslint . --max-warnings=0`, and `.github/workflows/ui-ci.yml`, whose "Run
 * ESLint and the JSDoc documentation gate" step invokes that same script with no
 * tolerance -- no `continue-on-error`, no `|| true`. Nothing configured here is
 * advisory in either caller.
 *
 * Refactoring Rationale: the workflow was described here as "later-index" work
 * that "is required to invoke" the script -- a forward-looking form written before
 * it existed. It exists and it runs, so the statement is written in the present
 * tense; a gate documented as pending is one a reader assumes they may still be
 * ahead of.
 *
 * Rule 1's four docstring elements, and the rule that decides each
 * ---------------------------------------------------------------
 * - Purpose -- `jsdoc/require-description`.
 * - Parameters (name, type and description for each) --
 *   `jsdoc/require-param`, `jsdoc/require-param-name` and
 *   `jsdoc/require-param-description`, plus `jsdoc/require-param-type` in
 *   BOTH languages.
 * - Return values (type and description) -- `jsdoc/require-returns` and
 *   `jsdoc/require-returns-description`, plus `jsdoc/require-returns-type` in
 *   BOTH languages.
 * - Exceptions or errors -- `jsdoc/require-throws`.
 *
 * Refactoring Rationale: the two type entries above are stated as covering both
 * languages because that is what this file configures, and an earlier version of
 * this list said "in plain JavaScript only" -- which described a superseded
 * arrangement and contradicted the TypeScript block, where `jsdoc/no-types` is
 * off and `jsdoc/require-param-type`, `jsdoc/require-returns-type` and
 * `jsdoc/require-property-type` are all set to error. A header that describes a
 * gate more narrowly than the gate is configured is worse than no header: a
 * reader auditing coverage stops at the summary and concludes a clause is
 * unenforced when it is enforced, or removes the entries as inconsistent with the
 * documented intent. The mechanism differs by language -- the TypeScript block
 * switches the preset's type prohibition off to make the requirement satisfiable,
 * while the JavaScript block inherits the requirement from its preset -- and that
 * difference is argued in full beside `jsdoc/require-param` below. What does not
 * differ is the obligation, which AAP section 0.8.1 states for every language it
 * names, TypeScript included.
 *
 * Two more rules guard the block itself rather than one of its elements.
 * `jsdoc/require-jsdoc` decides whether a block is present at all, and
 * `jsdoc/check-param-names` catches a block that has drifted away from the
 * signature it documents -- which is worse than a missing block, because a
 * reader who trusts it is actively misled.
 *
 * What this gate does not decide
 * ------------------------------
 * Three of Rule 1's four Forbidden Patterns are semantic, and no linter in any
 * language can decide them: whether a comment restates the code, whether a
 * rationale is specific rather than vague, and whether the decision a comment
 * justifies was the non-obvious one on that line. Rule 1's "module entry point"
 * clause is left to review here as well -- see the note beside
 * `jsdoc/require-file-overview` in the TypeScript block for the measured reason.
 * These limits are stated rather than left implied, because a configuration that
 * appears to decide everything teaches reviewers to stop reading, and that
 * removes the only check covering the semantic three.
 *
 * Why the gate is settled before the tree it governs
 * --------------------------------------------------
 * A documentation gate retrofitted after 21 screens exist is one that gets
 * narrowed to fit whatever those screens already happen to do, which inverts the
 * relationship between the rule and its enforcement. Every rule below is
 * therefore chosen from Rule 1's own clauses and then verified against the
 * installed plugin by probe, rather than derived from what the tree currently
 * contains. Where a limit is the plugin's rather than a choice, it is named as
 * such at the rule it constrains -- see `jsdoc/require-jsdoc`, where the
 * relational selectors that reach a function in every position are recorded
 * together with the one convention that covers what no selector can.
 *
 * Governed surface: everything under `ui/src` -- entry module, router, screens,
 * typed API modules, route helpers, message catalog, theme bridge and test setup
 * -- plus all THREE root-level TypeScript modules: `vite.config.ts`,
 * `vitest.config.ts` and `documentationGate.test.ts`, the last being the negative
 * probe that lints deliberately non-conforming sources against this very
 * configuration. Every one of the 21 screen routes the plan assigns is admitted by
 * the rules below rather than by an exemption written for it, which is what keeps
 * the gate from being narrowed to fit whatever it is pointed at.
 *
 * Refactoring Rationale: this statement named two root modules and omitted
 * `documentationGate.test.ts`. The omission was the wrong one to leave: that file
 * is the only committed check that this configuration has not been relaxed, so a
 * reader taking the surface statement literally would have concluded the probe sat
 * outside the gate it guards. It is governed by `TYPESCRIPT_FILES` like every other
 * module, and always was -- only the description was short.
 */

// Alternatives Considered: the conventional first line of a flat config is
// `import js from '@eslint/js'`, for ESLint's core recommended preset. It is
// absent here because it cannot be installed: eslint 10.8.0 no longer depends on
// `@eslint/js`, and `ui/package-lock.json` carries no entry for it, so `npm ci`
// never puts it on disk. Verified directly -- `extends: ['js/recommended']`
// fails with `Plugin "js" not found.` Importing it anyway would leave
// `npm run lint` unable to resolve a module on a clean checkout, and adding a
// dependency is not this file's to do. What replaces the preset is
// CORE_JAVASCRIPT_RULES below, which names only the core checks TypeScript does
// not already decide and gives each one its own consequence.
import { defineConfig, globalIgnores } from 'eslint/config';

// WHY : Assumptions: the plugin decides docstring PRESENCE and TAG COVERAGE, and
//       nothing beyond that. `docs/CODE_DOCUMENTATION_STANDARD.md` states the
//       same boundary for every language it covers, and this file is that
//       document's mechanical half for TypeScript, so the two have to agree
//       about where the machine stops and review begins.
import jsdoc from 'eslint-plugin-jsdoc';

// ===========================================================================
// THE TYPESCRIPT COMPILER CEILING -- read this before raising any version.
// ---------------------------------------------------------------------------
// Trade-offs: `typescript-eslint` 8.65.0 peer-declares `typescript >=4.8.4
// <6.1.0`. `ui/package.json` therefore pins `typescript` at 6.0.3 and
// deliberately NOT at 7.x, trading the newer compiler's build speed for a
// documentation gate that actually executes.
//
// Assumptions: that pin is load-bearing rather than routine version
// conservatism, and the FAILURE MODE is what makes it so. Raising the compiler
// past the peer bound does not fail loudly. It stops the type-aware pass -- the
// pass that supplies the parser services every rule below is evaluated under --
// and the build stays green while the gate quietly stops running. A
// documentation gate that cannot fail is worse than no gate at all, because it
// emits a green signal that means nothing, which is exactly the outcome Rule 1
// exists to prevent.
//
// Consequence: `typescript` and `typescript-eslint` move together or not at all.
// Raising `typescript` requires first finding a `typescript-eslint` release whose
// peer range admits the new compiler, and then confirming that
// `npx eslint --print-config` still lists the `jsdoc/require-*` rules alongside a
// type-aware rule such as `@typescript-eslint/no-floating-promises`. Bumping
// `typescript` on its own leaves the manifest installable and this file inert.
// ===========================================================================
import tseslint from 'typescript-eslint';

// WHY : Alternatives Considered: `eslint-config-prettier` is the customary fourth
//       entry, with `eslint-plugin-react`, `eslint-plugin-react-hooks`,
//       `eslint-plugin-import` and `eslint-plugin-unicorn` close behind. Not one of
//       them appears in `ui/package.json`, and `npm ci` installs strictly from
//       `ui/package-lock.json`, so importing any of them would leave `npm run lint`
//       unable to resolve a module on a clean checkout -- the gate would be broken,
//       not stricter, and the documentation-gate step would fail for a reason that
//       has nothing to do with documentation. The Prettier compatibility config is
//       additionally unnecessary on its own terms: no rule configured below decides
//       CODE layout, and the only layout rules in play govern the inside of a
//       docstring, which Prettier does not reformat. Formatting stays where
//       `ui/package.json` puts it, in the separate `format` script.
// WHY : Trade-offs: the minimality is a choice and not only a consequence of the
//       dependency set. Every plugin added here imposes its rules on all 21
//       screens and on every test beside them, and each rule then needs its own
//       justification in this file under the same rule it enforces. The set is
//       therefore held to what enforces Rule 1 plus what the type system cannot
//       decide on its own.

// WHY : Assumptions: `tseslint.configs.recommendedTypeChecked` ships three blocks
//       and two of them declare no `files` key of their own -- the base block
//       that installs the TypeScript parser, and the 50-rule type-checked block.
//       Left unscoped, both would also apply to this configuration file, which
//       is JavaScript and belongs to no TypeScript project, and every type-aware
//       rule would report that it could not find a program for it. Naming the set
//       once and reusing it keeps the blocks that need it from drifting apart.
const TYPESCRIPT_FILES = ['**/*.ts', '**/*.tsx'];

// WHY : Refactoring Rationale: an earlier shape of this file spelled these rules
//       out separately in the TypeScript block and in the JavaScript block. Two
//       copies of one obligation drift -- a rule tightened in one place and not
//       the other produces a tree where the same missing docstring is a build
//       failure in a `.tsx` screen and a pass in a `.js` config file. Sharing one
//       object makes that impossible. The four JSDoc TYPE-TAG settings are
//       deliberately absent from this object because the MECHANISM that delivers
//       them differs by language, not because the obligation does: the JavaScript
//       block inherits them from its preset, while the TypeScript block sets all
//       four explicitly -- `jsdoc/no-types` off plus `require-param-type`,
//       `require-returns-type` and `require-property-type` at error -- because that
//       preset forbids type expressions outright. Both languages end up requiring
//       the type. Refactoring Rationale: this note previously said each language's
//       "own preset decides them", which was true of an earlier arrangement and is
//       not true of the TypeScript block as configured; leaving it would have told
//       a reader the type half was unenforced in `.ts`. See the note under
//       `jsdoc/require-param` below for the argument, and the TypeScript block for
//       the four entries.
const JSDOC_DOCUMENTATION_RULES = {
  // Rule 1, block presence: "Every new or modified function, class, and module
  // entry point must include a docstring."
  'jsdoc/require-jsdoc': [
    'error',
    {
      // Refactoring Rationale: the scope of this gate is EVERY function and class,
      // and an earlier value here was `true` -- exported symbols only. That value
      // argued from AAP §0.2.1.6, which lists this file as "`eslint-plugin-jsdoc`
      // requiring JSDoc on exported components and functions", and from §0.8.1,
      // which repeats it in the same words. The argument was withdrawn because it
      // read a MINIMUM as an EXCLUSION. Both sentences say what the gate must
      // require; neither says what it may not require, and a gate that additionally
      // reaches a module-private helper still satisfies both of them literally. Set
      // against that, Rule 1 states "every new or modified function, class, and
      // module entry point must include a docstring" and attaches no visibility
      // qualifier at all. Only one of the two readings satisfies both documents at
      // once, so `false` is not a departure from the AAP -- `true` was the reading
      // that had to add a qualifier the specification does not contain. Measured
      // against the landed tree at the moment of the change, the wider scope reports
      // ZERO new violations, so nothing here is aspirational.
      // Assumptions: this value is stated explicitly rather than left to the plugin
      // default so that a search for `publicOnly` finds a decision with its
      // authority attached rather than an absence.
      // Trade-offs: every inline closure now owes a block -- every `onClick`, every
      // `.map` body, every `useMemo` factory -- and the cost of that is accepted
      // rather than argued away. Rule 1 allows a trivial function "a single-line
      // docstring", an allowance about LENGTH and not about presence, so a one-line
      // closure costs one line. The alternative was measured too, and it is the
      // reason the exchange goes this way: probed against the narrower value, an
      // undocumented module-private `function` and an undocumented anonymous `.map`
      // callback inside an exported component were BOTH reported as clean. This tree
      // has already been bitten by exactly that gap once -- `ui/src/messages/`
      // carried an anonymous `.map` callback that branches, returns four different
      // values and throws -- and a gate that a reviewer has to compensate for by
      // hand is the shape of gate Rule 1's validation clause forbids.
      publicOnly: false,

      require: {
        // Assumptions: the two expression kinds are `false` HERE and covered by the
        // relational selectors in `contexts` below. This is not the exemption an
        // earlier version of this file recorded, and the difference is measurable
        // rather than a matter of wording. The plugin honours these two settings
        // only where the expression already sits in a variable declaration,
        // assignment, property or export, so on its own each is strictly WEAKER
        // than the corresponding `contexts` entry, which matches the node in every
        // position including a bare callback argument. Setting both to `true`
        // alongside those entries was measured against a probe file and produced
        // two identical reports for one node, so the weaker setting is switched off
        // and the stronger selector carries the whole obligation.
        // Refactoring Rationale: the previous shape set both to `false` for a
        // different and wrong reason -- that expressions were "selected through
        // `contexts`" by NAME, via `VariableDeclarator > ArrowFunctionExpression`,
        // and that covering them all would be unsatisfiable across 21 screens.
        // Rule 1 states no exemption for any function KIND, so that was an
        // exemption dressed as a scoping choice, and the gap it left was real:
        // `ui/src/messages/messages.ts` carried an anonymous `.map` callback that
        // branches, returns four different values and throws, matched by no
        // selector in the list.
        // Trade-offs: the cost the old comment described is accepted rather than
        // argued away. Every inline closure -- an `onClick` handler, a `.map` body,
        // a `useMemo` factory -- now needs a block. Rule 1 allows a trivial
        // function "a single-line docstring", an allowance about LENGTH and not
        // about presence, so a one-line closure costs one line; that is the same
        // reading already applied to accessors below. The alternative, leaving the
        // exemption and trusting review to catch a non-trivial closure, is what
        // already failed once here.
        ArrowFunctionExpression: false,
        FunctionExpression: false,
        ClassDeclaration: true,
        ClassExpression: true,
        FunctionDeclaration: true,
        MethodDefinition: true,
      },

      // Refactoring Rationale: this list previously carried four POSITIONAL
      // selectors -- `VariableDeclarator >` and `ExportDefaultDeclaration >`
      // against each expression kind -- and those were the reason an anonymous
      // callback escaped the gate. Each required the expression to be the direct
      // child of a NAME, so `[...].map((part) => { ... })`, whose arrow is a child
      // of a CallExpression, matched none of them. The two entries below match the
      // node under any parent, which is what closes the gap.
      // Assumptions: the leading `* > ` is load-bearing and is not decoration. A
      // BARE `ArrowFunctionExpression` selector is short-circuited by the plugin
      // into the same declaration-position-only check as the `require` setting
      // above -- measured against a probe file, where the bare selector reported
      // nothing on an undocumented callback argument and `* > ` reported it. Any
      // relational form works; `* > ` is chosen because it states "in every
      // position" rather than enumerating the positions and inviting the same
      // omission again.
      // Trade-offs: `*:not(MethodDefinition) > FunctionExpression` excludes exactly
      // one parent, and only to stop a duplicate. A class method's body IS a
      // FunctionExpression, and `require.MethodDefinition` above already reports it,
      // so without the exclusion one undocumented method produced two identical
      // errors. Nothing is exempted by this: measured on a probe, a class with an
      // undocumented method still reports twice -- once for the class, once for the
      // method -- and an object-literal `{ m: function () {} }`, whose parent is a
      // Property rather than a MethodDefinition, is still reported.
      // Assumptions: `TSDeclareFunction` is the one entry neither mechanism can
      // express. An ambient declaration or an overload signature has no body, so it
      // is neither a declaration with a block nor an expression, and its docstring
      // is the only place a reader can learn what it means.
      contexts: [
        '* > ArrowFunctionExpression',
        '*:not(MethodDefinition) > FunctionExpression',
        'TSDeclareFunction',
      ],

      // Assumptions: Rule 1 allows a trivial accessor "a single-line docstring",
      // which is an allowance about LENGTH and not an exemption from having one.
      // Accessors are therefore checked here. Note that the single-line form is
      // NOT sufficient for a getter: `jsdoc/require-returns` further down keeps
      // `checkGetters` on, so a getter must also state its return value. The
      // trade recorded there is deliberate.
      checkGetters: true,
      checkSetters: true,

      // Assumptions: Rule 1 names "every new or modified function, class and
      // module entry point", and AAP section 0.8.1 restates the artifact list with
      // CONSTRUCTORS named explicitly, so a constructor is one of the kinds the
      // rule enumerates rather than an incidental method. It is checked here.
      checkConstructors: true,

      // Refactoring Rationale: this was `true`, the plugin's own default, and that
      // is a documented exemption for an artifact kind Rule 1 names. Measured
      // against the installed plugin rather than read off the option name: with
      // `exemptEmptyConstructors: true` the rule skips a constructor that has NO
      // parameters AND no return value, so a bare `constructor() { ... }` needs no
      // block at all however much its body does. That is the wrong boundary --
      // emptiness of the SIGNATURE says nothing about whether the body made a
      // decision, and a parameterless constructor that wires a singleton, seeds a
      // cache or registers a listener is exactly the code a reader needs the
      // reasoning for.
      // Alternatives Considered: leaving the default and relying on review for
      // parameterless constructors, which is what the previous rationale amounted
      // to. Rejected because Rule 1's validation gate is meant to be machine-
      // checked here, and an exemption keyed on an unrelated property is the
      // quietest kind of bypass: nothing in a diff shows that a constructor was
      // never asked for documentation.
      // Trade-offs: an author who genuinely has a do-nothing constructor now
      // writes one line saying so. That cost is accepted, and it is the same cost
      // `exemptEmptyFunctions: false` below already accepts for a deliberate
      // no-op function body, for the same reason -- "deliberately empty" is
      // information a reader cannot get from the code.
      // Assumptions: this changes no current verdict. Measured across `ui/src`:
      // the tree contains exactly one class, `NoopResizeObserver` in
      // `src/test/setup.ts`, and it declares no constructor at all, so the flip is
      // gate hardening with no authored source affected today.
      exemptEmptyConstructors: false,

      // Trade-offs: an empty function body is NOT exempt. A `() => {}` passed as
      // a deliberate no-op is precisely the case where a reader needs to be told
      // it is deliberate rather than unfinished, and this migration has at least
      // one such case by design -- the interest program's fee paragraph is
      // carried across as an explicitly documented no-op extension point.
      exemptEmptyFunctions: false,

      // Assumptions: the fixer inserts a block with the right tags and no prose.
      // That satisfies this rule's shape while satisfying none of Rule 1's four
      // elements, and `jsdoc/require-description` would report it one edit
      // later anyway. A report that a human answers is worth more than a fix
      // that cannot know the "why".
      enableFixer: false,
    },
  ],

  // Rule 1, element 1 (Purpose: what the function or class does). The plugin's
  // TypeScript preset leaves this rule OFF, so without this entry a block made
  // up of nothing but `@param` and `@returns` tags would pass the gate: tag
  // coverage complete, purpose absent. That is the precise shape Rule 1's
  // Forbidden Patterns call out as "docstrings that omit ... purpose".
  'jsdoc/require-description': [
    'error',
    {
      // Assumptions: this list mirrors the coverage of `jsdoc/require-jsdoc`
      // above. The rule's own default covers function declarations and
      // expressions only, so a class or a method would be required to HAVE a
      // block by one rule and permitted to leave it purposeless by the other --
      // and a purposeless block is the one outcome worse than no block, because
      // it looks answered.
      contexts: [
        'ArrowFunctionExpression',
        'ClassDeclaration',
        'ClassExpression',
        'FunctionDeclaration',
        'FunctionExpression',
        'MethodDefinition',
        'TSDeclareFunction',
      ],
      checkConstructors: true,
      checkGetters: true,
      checkSetters: true,

      // Alternatives Considered: `descriptionStyle: 'any'`, which would also
      // accept the purpose supplied through a `@description` tag. Rejected
      // because it admits two spellings of the same element with nothing to
      // choose between them, and because `docs/CODE_DOCUMENTATION_STANDARD.md`
      // and every landed module in this tree put the purpose in the block body.
      descriptionStyle: 'body',
    },
  ],

  // Rule 1, element 2 (Parameters: name, type and description for each). Three
  // rules split that one clause -- that a tag exists per parameter, that it
  // names the parameter, and that it describes it.
  //
  // ⚠ Refactoring Rationale: the TYPE half of the clause is REQUIRED in TypeScript
  // as well as in JavaScript, and an earlier arrangement of this file left it off
  // in `.ts`. That arrangement argued that the signature already carries the type
  // where the compiler checks it, so a `{Type}` tag beside it is unchecked prose
  // duplicating a checked fact. The observation is true and the conclusion drawn
  // from it was still wrong, because it answered a question Rule 1 does not ask.
  // Rule 1's Parameters element requires the DOCUMENTATION to state "each
  // parameter with its name and type", and its Return values element the "type and
  // description of what is returned"; it draws no distinction between a language
  // whose signature happens to hold the type and one whose signature does not, and
  // it grants no exemption on the ground that another artifact states the same fact
  // more reliably. A gate that asks for less than the rule is a gate that reports
  // success while enforcing less than it claims, which is the specific shape of
  // failure Rule 1's validation clause exists to catch. The three
  // `jsdoc/require-*-type` rules are therefore `error` in the TypeScript block
  // below, exactly as the JavaScript preset has them.
  //
  // Trade-offs: the accepted cost is a second textual source of truth about types,
  // and it is a real cost rather than a nominal one -- an annotation can disagree
  // with the signature, and when it does only the annotation can be wrong. This
  // migration has a specific reason to care: field widths and decimal scale are
  // transcribed from the copybooks under Transformation Rule T1, so a drifted tag
  // is exactly how a `string` money field acquires a documented `number`. Two
  // things bound that cost. `jsdoc/no-undefined-types` stays on, so a `{Accont}`
  // typo fails the build; and `typescript-eslint`'s type-aware rules plus
  // `tsc --noEmit` keep the signature itself authoritative for compilation, so a
  // drifted annotation misleads a reader without ever misleading the compiler.
  // What is bought is that Rule 1's Parameters and Return values elements are
  // enforced whole rather than in the two thirds a reader cannot tell apart from
  // three.
  //
  // Assumptions: the obligation is now identical in both languages, which is what
  // makes the file readable as one decision. In `.js` the JSDoc types ARE the type
  // system; in `.ts` they are documentation of a type the compiler also holds. The
  // rule is the same in both, and `jsdoc/no-types` stays off in the TypeScript
  // block precisely so that the tags this rule now requires are admitted there --
  // see that block below, where the four settings are stated together with what
  // each one does.
  'jsdoc/require-param': [
    'error',
    {
      // Assumptions: a React component's props usually arrive as one destructured
      // parameter, so documenting the parameter alone would let a screen say
      // "the props" and nothing about the values it actually reads -- 128 of them
      // on the account-update screen alone. Each destructured member is therefore
      // documented individually.
      checkDestructured: true,

      // Trade-offs: MEASURED, not assumed, and it settles the one place this
      // configuration knowingly disagrees with a sibling document. Setting
      // `checkDestructuredRoots: false` -- which reads like a harmless way to
      // avoid a placeholder tag for a pattern that has no name in the source --
      // was tried against a real component and makes the rule require NOTHING
      // for a destructured parameter: a component whose props are destructured in
      // its signature passed with no `@param` tag at all. That would exempt every
      // one of the 21 screens' props from Rule 1's Parameters clause through an
      // option that looks cosmetic, so the root check stays on.
      checkDestructuredRoots: true,

      // Assumptions: a destructuring pattern has no name, so the plugin
      // synthesises one and `unnamedRootBase` decides what it is called. It is
      // set to `props` -- giving `@param props0`, `@param props0.actions` -- so
      // the synthesised name reads as what it is instead of the default `root0`.
      // Two spellings were verified to pass: that one, and the cleaner
      // `@param props`, `@param props.actions` obtained by naming the parameter
      // (`(props: Props)`) and destructuring in the body.
      // Trade-offs: the example in `docs/CODE_DOCUMENTATION_STANDARD.md` uses
      // `@param props.actions` over a destructured SIGNATURE, which this
      // configuration rejects because the root tag is missing. That document
      // states its own precedence for exactly this case -- where it and a linter
      // configuration disagree, the configuration is authoritative and the
      // document is what gets fixed -- and the reason to exercise that here is
      // the measurement above: accepting the example's form means accepting a
      // gate that asks nothing of any screen's props.
      unnamedRootBase: ['props'],

      checkGetters: true,
      checkSetters: true,
      enableFixer: false,
    },
  ],
  // Assumptions: these two are the other two thirds of the Parameters clause and
  // are restated because a tag that exists satisfies `jsdoc/require-param` on its
  // own. A bare `@param` with no name is attached to nothing, and a named tag with
  // no text records that a parameter exists -- which the signature already said --
  // while answering none of what a reader needs from it.
  'jsdoc/require-param-name': 'error',
  'jsdoc/require-param-description': 'error',

  // Rule 1, element 3 (Return values: type and description of what is returned).
  'jsdoc/require-returns': [
    'error',
    {
      // Refactoring Rationale: this was `false`, on the reasoning that Rule 1's
      // trivial-accessor allowance -- "trivial accessors may use a single line" --
      // becomes unusable if a getter must carry a `@returns` tag, since a
      // one-line block cannot hold one. The reasoning was sound and the
      // conclusion was still wrong, because it read an allowance about LENGTH as
      // an exemption from an ELEMENT. Rule 1 requires the docstring to state the
      // return value; the single-line form satisfies that only when the one line
      // happens to state it, and no linter can decide whether a sentence of prose
      // does. Switching the check off therefore left the one element a getter
      // exists to deliver -- what it returns -- decided by nothing.
      // Alternatives Considered: (1) keeping `false` and carrying the getter
      // return value as a review obligation, rejected because R1-12 is precisely
      // the finding that an unenforced element documented as enforced is a gate
      // gap; (2) `checkGetters: "no-setter"`, which the sibling `require-jsdoc`
      // rule accepts, rejected because it is not in this rule's schema -- measured
      // against the installed plugin, `require-returns.checkGetters` is a plain
      // boolean, so there is no partial setting to reach for.
      // Trade-offs: the trivial-accessor allowance is knowingly traded away for
      // getters specifically, and only for getters. An author writes a two-line
      // block -- a description and a `@returns` -- where Rule 1 would have
      // permitted one line. The allowance remains available everywhere else,
      // including on setters, which have no return value to state and are
      // therefore untouched by this rule.
      // Assumptions: this changes no current verdict. Measured across `ui/src`:
      // there is no `get` accessor anywhere in the authored tree, so the flip is
      // gate hardening with no authored source affected today.
      checkGetters: true,

      // Assumptions: a constructor returns its instance and saying so adds
      // nothing a reader does not already know from the `new`.
      checkConstructors: false,

      // Trade-offs: `forceRequireReturn` is left off, so a function that returns
      // nothing needs no `@returns`. Requiring one would produce
      // `@returns nothing` on every void handler in 21 screens -- a line that
      // restates the signature, which is the first thing Rule 1 forbids.
      forceRequireReturn: false,

      // Assumptions: an `async` function whose body returns no value still returns
      // a promise, and that promise is not worth a tag either; what matters about
      // it is caught by `@typescript-eslint/no-floating-promises` at the CALL
      // site, not by prose at the declaration.
      forceReturnsWithAsync: false,
      enableFixer: false,
    },
  ],
  // Assumptions: the description is the half of the Return values clause that
  // carries meaning here, since the type is in the signature. A bare `@returns`
  // says only that something comes back; on a keyset browse it is the difference
  // between "the page rows" and "the page rows plus the cursor keys the previous
  // and next controls are enabled from".
  'jsdoc/require-returns-description': 'error',

  // Rule 1, element 4 (Exceptions or errors: any that may be raised, where
  // applicable). The plugin's TypeScript preset leaves this OFF, and it is the
  // element most often dropped because a `throw` is usually written last. In this
  // tree the throws are load-bearing: the shared API client turns a non-2xx
  // response into one, and the screen that catches it is what puts text into the
  // 75-character message band that the baseline's `CCARD-ERROR-MSG` contract
  // fixes the width of. An undocumented throw is a caller that does not know it
  // has to catch, and a message band that stays blank where the 3270 screen
  // showed an error.
  // Assumptions: this is the one element whose TYPE half must live in the
  // docstring even in TypeScript, and the plugin's TypeScript preset already
  // requires it -- `jsdoc/require-throws-type` is on there, so the accepted form
  // is `@throws {Error} when ...`. That is not an exception to the reasoning
  // beside `jsdoc/require-param` above but a consequence of it: a TypeScript
  // signature has no throws clause, so unlike a parameter or a return there is
  // no typed declaration for the docstring to duplicate.
  'jsdoc/require-throws': 'error',

  // Assumptions: `check-param-names` is the drift detector, and drift is the
  // failure this gate is least able to survive. A block whose `@param` names a
  // parameter that was renamed or removed is worse than a missing block: the
  // missing one prompts a reader to go and look, while the stale one answers the
  // question wrongly and is believed. Every other rule in this object checks that
  // documentation EXISTS; this is the only one that checks it still describes the
  // code beside it.
  'jsdoc/check-param-names': [
    'error',
    {
      checkDestructured: true,
      // Trade-offs: a documented member that no longer exists is reported rather
      // than tolerated, which is the whole point of the rule; the cost is that
      // renaming a prop forces the docstring to be edited in the same commit.
      // That cost is the mechanism, not a side effect.
      disableExtraPropertyReporting: false,
      enableFixer: false,
    },
  ],

  // Assumptions: this rule decides where blank lines may sit inside a block, and
  // it is restated because the plugin's default arbitrates a purely cosmetic
  // choice on which this repository's own two sources disagree --
  // `src/messages/messages.ts` puts no blank line between a block's description
  // and its first tag, while the TypeScript example in
  // `docs/CODE_DOCUMENTATION_STANDARD.md` puts one. `startLines: null` accepts
  // either, so the gate stops adjudicating house style.
  // Trade-offs: the structural half is kept. `never` still rejects a blank line
  // BETWEEN tags, which is the case that matters: a stray blank line there ends
  // the preceding tag's description early, so a `@param` whose text continues
  // after it silently loses that text, and the tag still counts as present.
  'jsdoc/tag-lines': ['error', 'never', { startLines: null }],

  // Assumptions: the two rules below decide that a block is WELL FORMED, which is
  // the precondition for every rule above being able to decide anything at all. A
  // misaligned continuation line or a type expression that does not parse is not
  // recognised as the tag it was meant to be, so the plugin skips it -- and a
  // missing element then passes as present. They are restated here rather than
  // left to be inherited because that failure is silent, and because an inherited
  // preset's contents can change between releases while this file's dependence on
  // them does not.
  'jsdoc/check-alignment': 'error',
  'jsdoc/valid-types': 'error',

  // Assumptions: both presets leave this rule OFF, so switching it on is a
  // decision rather than a restatement, and it is load-bearing in BOTH languages
  // because the TypeScript block below leaves `jsdoc/no-types` off, which PERMITS
  // a type expression in a `.ts` docstring without requiring one. That is what
  // makes this rule matter here: `.ts` files in this tree do carry type
  // expressions -- 188 of them -- and without this rule a name in
  // `@param {Accont}` -- a typo for `Account` -- would be
  // accepted as documentation of a type that does not exist. The rule resolves
  // those names against the file's real declarations, so a locally declared
  // interface and a built-in construction such as
  // `Readonly<Record<string, string>>` both satisfy it while a misspelling does
  // not. In the `.js` half it is substantive for the same reason and then some,
  // because there the JSDoc types ARE the type system rather than a restatement
  // of it. Trade-offs: this is the rule that makes an OPTIONAL type tag safe to
  // permit instead of merely tolerated, at the cost of one more place a renamed
  // type has to be updated -- which is the point, since the alternative is an
  // annotation nobody checks that reads as though somebody had.
  'jsdoc/no-undefined-types': 'error',
};

// WHY : Alternatives Considered: extending `@eslint/js`'s recommended set, which
//       is the normal way to get these. It is not available -- see the note on
//       the `eslint/config` import above. The list below is therefore curated
//       rather than copied, and it is deliberately short: it names only core
//       checks that the TypeScript compiler does not already decide under the
//       strict settings in `ui/tsconfig.json`, so nothing here duplicates a
//       compiler diagnostic. `typescript-eslint`'s own `eslint-recommended` block
//       covers the complementary half by switching OFF the core rules TypeScript
//       decides better (`no-undef`, `no-dupe-keys`, `no-unreachable` and twenty
//       more) and switching on `no-var`, `prefer-const`, `prefer-rest-params` and
//       `prefer-spread`.
// WHY : Trade-offs: every rule added here is a new obligation for the whole tree,
//       so each one below has to name a consequence specific to this migration
//       rather than a general preference. Anything that could only be argued for
//       on style grounds was left out.
const CORE_JAVASCRIPT_RULES = {
  // Assumptions: money crosses every boundary in this tree as a STRING, because a
  // JSON number is parsed into an IEEE-754 double by most clients and stops being
  // exact. Loose equality is what makes that decision dangerous: `'0.00' == 0`
  // and `'' == 0` are both true, so a balance compared loosely against a numeric
  // zero reports "no balance" for a string the code never converted. Strict
  // equality turns that into a type error the compiler catches instead.
  eqeqeq: ['error', 'always'],

  // Assumptions: a money or identifier string that fails to parse yields NaN, and
  // `value === NaN` is always false -- so a guard written that way silently
  // classifies every unparsable amount as valid and lets it through to the
  // request body.
  'use-isnan': 'error',

  // Assumptions: a mistyped comparand (`typeof x === 'sting'`) is always false, so
  // a type guard written that way takes the wrong branch every time without ever
  // throwing. The screens narrow values arriving from the API this way.
  'valid-typeof': 'error',

  // Assumptions: `(row?.field).value` throws at run time even though the optional
  // chain looks defensive, and optional chaining is exactly how a partly-filled
  // browse page is read -- `noUncheckedIndexedAccess` in `ui/tsconfig.json` makes
  // every row slot optional precisely because the final page of a keyset browse
  // fills only some of its fixed number of rows.
  'no-unsafe-optional-chaining': 'error',

  // Assumptions: an empty `catch` swallows the error the API client threw, and the
  // 75-character message band then shows nothing where the 3270 screen showed a
  // message. `allowEmptyCatch` is left at its default of false for that reason: a
  // deliberately ignored error has to say so in a comment, which is the same thing
  // Rule 1 asks of every other non-obvious decision.
  'no-empty': 'error',

  // Assumptions: a `debugger` statement is not a compile error, so it reaches the
  // production bundle that CloudFront serves and halts the page for every user
  // with developer tools open.
  'no-debugger': 'error',

  // Assumptions: a validation guard that has collapsed to a constant -- an
  // `if (someObject)` where the value is always truthy, a comparison left behind
  // after a refactor -- takes the same branch every time. The screens transcribe
  // COBOL validation chains branch for branch, so a guard that cannot vary is a
  // transcribed rule that is no longer being applied.
  'no-constant-condition': 'error',

  // Assumptions: every user-visible string in this tree is transcribed verbatim
  // from a copybook constant or a COBOL program literal, and the migration treats
  // that text as a contract. An irregular whitespace character -- a non-breaking
  // space, a zero-width character -- inside such a literal renders identically to
  // a normal space while making the string differ from `app/cpy/CSMSG01Y.cpy` byte
  // for byte, which is a fidelity failure no test asserting on visible text would
  // catch. `skipStrings` is therefore overridden to false, which is the only
  // setting that inspects the literals this matters for; the default true would
  // check everything except them.
  'no-irregular-whitespace': [
    'error',
    {
      skipStrings: false,
      skipTemplates: false,
      skipComments: false,
      skipRegExps: false,
      skipJSXText: false,
    },
  ],
};

// WHY : Assumptions: the plan states this discipline in prose, and prose does not
//       survive contact with an editor's auto-import. A lint rule is the only
//       thing that turns "we decided not to use that package" into "that package
//       cannot come back".
const IMPORT_DISCIPLINE_RULES = {
  'no-restricted-imports': [
    'error',
    {
      paths: [
        {
          // Alternatives Considered: `react-router-dom` is the conventional
          // choice and is absent from `ui/package.json` on purpose -- it has no
          // release at major version 8 at all, and its newest release is a thin
          // re-export shim whose own dependency is `react-router` 7.18.1. Adding
          // it back would silently pin routing a full major version behind while
          // looking like the safer, more idiomatic option.
          // Assumptions: the reason to ban it rather than rely on its absence is
          // the shape of the failure. It is not an install error -- npm would
          // fetch it happily -- and it is not a type error either. Two copies of
          // the router mean two router contexts, so a component rendered under
          // one provider and calling a hook from the other gets `undefined` back
          // at run time, on a screen, with no build-time signal at all. All 21
          // screens and `src/router.tsx` import from `react-router` directly.
          name: 'react-router-dom',
          message:
            'Import from "react-router" instead. react-router-dom has no 8.x release; it is a shim over react-router 7.18.1 and a second copy of the router yields a second context whose hooks return undefined at run time.',
        },
      ],
      patterns: [
        {
          // Assumptions: antd 6 themes through CSS variables, injected once by the
          // `ConfigProvider` in `src/theme/antdTheme.ts`, and a component reached
          // through its package root participates in that injection. A deep
          // submodule path bypasses it, so the component renders with antd's
          // default token values instead of the BMS-derived ones -- the
          // turquoise-to-`colorInfo` and neutral-to-`colorTextSecondary` snaps
          // measured from the mapsets -- and the mismatch shows up as a colour, on
          // one component, with nothing failing.
          // Trade-offs: `antd/dist` is deliberately NOT in this list even though it
          // is also a deep path. That directory is where antd publishes its CSS
          // reset, which the application entry point legitimately imports; banning
          // it would block a real import to prevent a theoretical one.
          group: ['antd/es/*', 'antd/lib/*', 'antd/es', 'antd/lib'],
          message:
            'Import antd components from the package root ("antd") so the CSS-variables theme applied by ConfigProvider reaches them. A deep submodule path bypasses the BMS-derived design tokens.',
        },
      ],
    },
  ],
};

// WHY : Assumptions: two of the three are already `error` in
//       `tseslint.configs.recommendedTypeChecked` and are restated here anyway,
//       with their options pinned. The reason is that this tree's dependence on
//       them is specific and the preset's contents are not this file's to
//       control: a rule inherited invisibly can be relaxed by a dependency
//       upgrade, and the consequence -- stated beside each one below -- would
//       then be lost with no edit to this file to show it. The third is not in the
//       preset at all.
const TYPESCRIPT_ANALYSIS_RULES = {
  // Assumptions: the seven typed API clients under `src/api` exist to carry the DTO
  // shapes derived from the copybook record layouts -- field names, widths and
  // decimal scale -- into the screens. A single `any` in that path discards all of
  // it silently: the property accesses still compile, the wrong field name still
  // compiles, and a money field typed `any` can be handed to arithmetic that turns
  // an exact decimal string into a double. Typing those clients is the entire
  // reason they are hand-written rather than untyped fetch calls.
  '@typescript-eslint/no-explicit-any': 'error',

  // Assumptions: an unawaited API call in a screen handler rejects with nobody
  // listening, so the failure surfaces as an unhandled rejection in the console
  // and as an empty message band on the screen -- where the 3270 original
  // displayed the error text that `CCARD-ERROR-MSG` reserves 75 characters for.
  // Trade-offs: `ignoreVoid` is overridden to false, which is stricter than the
  // preset's default of true. Left at the default, `void loadAccount(id)` would be
  // an accepted way to discard a rejection -- a one-keyword escape hatch from the
  // rule, and one that reads as deliberate to a reviewer. The cost is that a
  // genuinely fire-and-forget call has to be written with an explicit `.catch()`
  // that says what it does with the error, which is the outcome wanted anyway.
  '@typescript-eslint/no-floating-promises': ['error', { ignoreVoid: false, ignoreIIFE: false }],

  // Assumptions: `ui/tsconfig.json` sets `verbatimModuleSyntax`, so a type-only
  // import written without the `type` keyword is emitted as a real runtime import.
  // If the module it names exports no value under that name -- an `interface`, a
  // `type` alias, both of which the DTO layer is full of -- the browser fails when
  // it evaluates the module, not when anything checks it. This rule is not in the
  // preset, so without this entry the compiler flag would be enforcing the
  // constraint only where it can see a violation, and the fix would have to be
  // found at run time.
  '@typescript-eslint/consistent-type-imports': [
    'error',
    { prefer: 'type-imports', fixStyle: 'separate-type-imports' },
  ],
};

export default defineConfig([
  // WHY : Assumptions: both are generated rather than authored, and both are
  //       git-ignored by the root `.gitignore` -- `node_modules/` at L98 and
  //       `dist/` at L103, each left unanchored there so it matches under `ui/`.
  //       Linting a bundle reports errors in code nobody wrote and nobody can fix
  //       in place, and the minified output of a build is also the one input on
  //       which the rules above produce noise instead of signal. `node_modules`
  //       is ignored by ESLint by default; it is named anyway so the boundary is
  //       visible in the file that owns it rather than resting on a default.
  // WHY : Assumptions: `src/test` is deliberately NOT here, and its absence is the
  //       decision. Rule 1 says "every new or modified function" with no test
  //       exemption, and the per-screen tests are where the three fidelity
  //       contracts this migration is judged on are actually asserted -- the
  //       copybook-derived `maxLength` on each field, the PF-key bindings
  //       including the PF13-to-PF24 aliasing, and the verbatim message text. A
  //       test whose helper does not say which contract it is asserting is the
  //       least useful undocumented function in the tree, not the most
  //       forgivable. The sibling Java tree already settled this the same way:
  //       `services/pom.xml` sets `includeTestSourceDirectory` to true so its
  //       Checkstyle gate audits `src/test` as well, and
  //       `config/checkstyle/suppressions.xml` narrows only generated sources and
  //       fixture RESOURCES rather than test code. Matching that keeps one
  //       obligation across the two languages instead of two.
  globalIgnores(['dist/**', 'node_modules/**'], 'carddemo/generated-output'),

  {
    name: 'carddemo/gate-integrity',

    // Assumptions: this block configures the LINTER rather than any rule, so it
    // carries no `files` key and applies to every file the run visits. It is a
    // separate named block, and not a key folded into the TypeScript block below,
    // because it governs both language blocks equally and a reader auditing "can
    // this gate be switched off from inside a source file?" should find one answer
    // in one place.
    linterOptions: {
      // Refactoring Rationale: an earlier version of this file configured no
      // `linterOptions` at all, so the default `noInlineConfig: false` was in
      // force and any authored file could switch off any rule for a line, a block
      // or its whole length with an `/* eslint-disable */` comment. That left the
      // documentation gate technically present and structurally optional: a
      // missing JSDoc block could be made to pass by adding a comment saying so,
      // and nothing in the run distinguished that outcome from a documented
      // symbol. Rule 1's validation clause makes a missing docstring a review
      // failure, so a mechanism for declaring one acceptable in-file contradicts
      // it directly. Set to true, an inline directive is IGNORED rather than
      // honoured, so the only way to satisfy a rule is to satisfy it.
      // Assumptions: nothing is being taken away from the tree as it stands --
      // measured at the moment of the change, `ui/` contains ZERO
      // `eslint-disable` directives of any form, so this closes a door nobody has
      // walked through rather than breaking a current practice.
      // Trade-offs: a genuine future need for a one-line exemption now has to be
      // expressed in THIS file, as a scoped `files` block with a rationale beside
      // it, instead of as a comment in the file that wants the exemption. That is
      // more work per exemption, and deliberately so: an exemption recorded here
      // is reviewable in one place and shows up in the diff of the gate, whereas
      // a comment in a screen is invisible to anyone auditing the gate.
      noInlineConfig: true,

      // Assumptions: this is the companion half of `noInlineConfig` rather than a
      // separate idea. With inline configuration ignored, a directive comment left
      // behind in a source file is inert text that still LOOKS load-bearing to a
      // reader, who would reasonably conclude the rule above it is suppressed.
      // Reporting it as an error means such a comment cannot survive a run, so the
      // file cannot come to disagree with the gate about what is enforced.
      reportUnusedDisableDirectives: 'error',
    },
  },

  {
    name: 'carddemo/typescript',

    // Assumptions: this is the whole authored surface of the package apart from
    // this configuration file -- `src` (the 21 screens, the app shell, the theme
    // bridge, the API clients, the message catalog, the hooks and the tests) plus
    // all three root-level TypeScript modules: the Vite config, the Vitest config
    // and `documentationGate.test.ts`. Refactoring Rationale: this comment said
    // "the two root-level Vite and Vitest config files", which undercounted the
    // root by one and, worse, left out the probe that guards this very file. Both
    // surface statements in this configuration now name the same three modules, so
    // a reader comparing them cannot be told two different things.
    files: TYPESCRIPT_FILES,

    extends: [
      // Assumptions: the TYPE-CHECKED variant, not the plain `recommended` one.
      // The plain set would ignore the projects wired up below entirely, and with
      // it every rule that needs to know what a value actually is --
      // `no-floating-promises`, `no-misused-promises`, the `no-unsafe-*` family.
      // Those are the rules that decide whether a promise or an `any` reaches a
      // screen, so choosing the plain set would leave the projects configured and
      // unused.
      tseslint.configs.recommendedTypeChecked,

      // Assumptions: the TypeScript variant of the plugin's recommended set, and
      // the `-error` one. Severity first: every rule here is `error`, so a bare
      // `npx eslint .` fails on a missing docstring on its own rather than only
      // under the `--max-warnings=0` that `ui/package.json`'s `lint` script
      // supplies. Trade-offs: the `warn` variants plus that flag reach the same
      // verdict in CI, but they make the gate's teeth a property of the command
      // line -- drop the flag from one workflow step and the gate keeps reporting
      // while nothing fails. Encoding the severity in the configuration means the
      // flag is defence in depth rather than the whole defence.
      // ⚠ Assumptions: choosing the TypeScript variant over the plain one is what
      // turns the three JSDoc `require-*-type` rules off, and that is the stance
      // this project wants -- the type belongs to the signature, as argued at
      // `jsdoc/require-param` above. Only ONE of the five rules the variant moves
      // is reversed here: `jsdoc/no-types` is set back off in this block's own
      // `rules` below, so a type expression is permitted rather than forbidden.
      // `jsdoc/no-undefined-types` is set back on too, but from
      // JSDOC_DOCUMENTATION_RULES rather than from here, because it applies in both
      // languages -- so a permitted type tag is still checked against real
      // declarations. Read the preset as supplying both the shared body of rules
      // and the type-tag stance, with those two entries adjusting how strictly the
      // stance is applied rather than reversing it.
      //
      // ⚠ Alternatives Considered: extending `flat/recommended-error` -- the plain
      // variant -- instead. The two presets were diffed against the installed
      // plugin rather than assumed about, and they turn out to differ in EXACTLY
      // five rules, every one of them part of the type-tag stance: the plain
      // variant has `no-types` off with `require-param-type`,
      // `require-returns-type`, `require-property-type` and `no-undefined-types`
      // on, and the TypeScript variant inverts all five. The plain variant is
      // therefore the WRONG base here in three of the five -- it would require the
      // duplicated types this file deliberately does not -- so extending it would
      // need three overrides where the TypeScript variant needs two. It also loses
      // for a second, independent reason: this is the TypeScript block, and a
      // future plugin release that adds a genuinely TypeScript-specific adjustment
      // will add it to the TypeScript preset, which the plain variant would not
      // inherit. Trade-offs: the cost is the four explicit type-tag entries below
      // and one indirection for a reader learning the exact stance -- paid so the
      // language-specific preset stays the base for the language-specific block.
      jsdoc.configs['flat/recommended-typescript-error'],
    ],

    languageOptions: {
      parserOptions: {
        // Alternatives Considered: `projectService: true`, which is the newer and
        // usually preferable way to give the rules type information, was
        // evaluated and rejected HERE for a measured reason. The project service
        // resolves each file against the nearest `tsconfig.json`; for
        // `ui/vite.config.ts` that is `ui/tsconfig.json`, whose `include` is
        // `src` and which therefore does not contain it. The service would report
        // that the file belongs to no project, and the honest fixes -- listing it
        // under `allowDefaultProject`, or reaching for the non-type-aware preset
        // -- either type it against no project at all or switch the type-aware
        // pass off. Naming both projects explicitly avoids the question: each
        // file is found in whichever of the two includes it.
        //
        // ⚠ Assumptions: BOTH projects have to be named. `./tsconfig.json` covers
        // `src`; `./tsconfig.node.json` is the only project that includes
        // `vite.config.ts` and `vitest.config.ts`. Listing only the first would
        // not produce an obviously broken gate -- it would leave those two files
        // outside type-aware linting while everything else still passed, so two
        // TypeScript module entry points would fall out of Rule 1's reach with
        // nothing to show it. If a new root-level `.ts` file is ever added, it
        // belongs in `tsconfig.node.json`'s literal include list; reaching for
        // `disableTypeChecked` to silence the resulting parser error would be the
        // same silent failure the compiler ceiling above warns about, arrived at
        // from the other direction.
        project: ['./tsconfig.json', './tsconfig.node.json'],

        // Assumptions: the two paths above are resolved against this file's own
        // directory rather than the process working directory, so the gate behaves
        // the same whether it is invoked from `ui/` by `npm run lint` or from the
        // repository root by a CI step that has not changed directory.
        tsconfigRootDir: import.meta.dirname,
      },
    },

    settings: {
      // Assumptions: the plugin's TypeScript mode, which is what makes it read
      // `interface`, `type` and generic parameters as declarations rather than as
      // syntax it cannot parse. It is set HERE and not left to the preset because
      // the preset does not set it -- verified: `flat/recommended-typescript-error`
      // ships only `name`, `plugins` and `rules`, so without this line the plugin
      // would fall back to its default `jsdoc` mode while every rule name in it
      // still said "typescript".
      jsdoc: { mode: 'typescript' },
    },

    rules: {
      ...JSDOC_DOCUMENTATION_RULES,
      ...CORE_JAVASCRIPT_RULES,
      ...TYPESCRIPT_ANALYSIS_RULES,
      ...IMPORT_DISCIPLINE_RULES,

      // Assumptions: `typed: true` is the TypeScript form of this rule -- it
      // additionally rejects the tags that only make sense when JSDoc is carrying
      // the types, which in a `.ts` file it is not. Restated here with that option
      // because the JavaScript block below inherits the untyped form from its own
      // preset, and the difference between the two is the language, not an
      // oversight.
      'jsdoc/check-tag-names': ['error', { typed: true }],

      // ⚠ Rule 1, element 2 and element 3, the TYPE half of each. In TypeScript a
      // `{Type}` tag is REQUIRED, exactly as it is in plain JavaScript, so the three
      // `require-*-type` rules are raised from the plugin's TypeScript-aware default
      // of off to `error`. The reasoning is argued in full at `jsdoc/require-param`
      // in the shared rule object above; the four entries below are what implement
      // it. They are one decision and are close to meaningless read apart, which is
      // why they are stated together.
      //
      // Refactoring Rationale: an earlier arrangement held all three at `off` and
      // argued that the signature carries the type where the compiler checks it, so
      // the tag would only duplicate a checked fact. Rule 1 asks the DOCUMENTATION
      // for each parameter's name and type and for the return value's type, without
      // qualifying by language, so that arrangement enforced two thirds of an
      // element while reporting a pass -- and a reader cannot tell a gate that
      // enforces two thirds from one that enforces three. Raising the three rules
      // was measured against the landed tree before it was adopted: `ui/src/**`
      // already carries 90 `@param {` and 97 `@returns {` tags and NOT ONE bare tag,
      // so the change reports zero new violations today and binds every docstring
      // written after it.
      //
      // Assumptions: `jsdoc/no-types` stays off, and it is the one entry here that
      // is off DELIBERATELY rather than by inheritance. The preset's TypeScript form
      // rejects a `{Type}` in a `.ts` docstring outright; leaving it on would forbid
      // the very tags the three rules above now require, so the two settings are one
      // mechanism and neither works without the other. It relaxes no Rule 1 element:
      // `jsdoc/no-undefined-types` stays on, so a `{Accont}` typo is still a build
      // failure, and `jsdoc/check-param-names` still fails when a documented
      // parameter no longer exists.
      //
      // Assumptions: `jsdoc/require-property-type` moves WITH the other two, which
      // is what keeps the answer to "must a documented member carry its type?" a
      // single yes rather than a case analysis. Trade-offs: it changes no current
      // verdict, and that is measured rather than assumed -- `check-tag-names` with
      // `typed: true`, set just above, already rejects `@typedef` and `@property`
      // outright in a `.ts` file as redundant beside a real type system, so a `.ts`
      // file cannot legally reach this rule at all and the authored tree contains
      // zero of either tag. It is stated explicitly rather than inherited so that
      // the rule set answers the question directly instead of obliging a reader to
      // reason about which tag another rule forbids first.
      'jsdoc/no-types': 'off',
      'jsdoc/require-param-type': 'error',
      'jsdoc/require-returns-type': 'error',
      'jsdoc/require-property-type': 'error',

      // Assumptions: `jsdoc/require-file-overview` is the only rule that could
      // mechanise Rule 1's "module entry point" clause, and it is left off after
      // measuring the tree rather than on principle: it insists on a literal
      // `@file` tag, and of the two modules measured `vite.config.ts` carries
      // one while `src/messages/messages.ts` opens with an untagged description
      // block. Switching it on would report a violation against a module whose
      // documentation is present and complete, which teaches a reader that the
      // gate's reports are noise. The clause stays a review obligation, and it is
      // named here -- and in the blind-spot note at the top of this file -- so its
      // absence reads as a decision rather than a gap. It is written in backticks
      // rather than as a configured entry so that a grep for a disabled
      // `jsdoc/require-*` rule in this file finds nothing.
    },
  },

  {
    // Assumptions: this block exists for exactly one file -- this one. It is the
    // only JavaScript in the package, and Rule 1 binds it as a module entry point
    // like any other, so the gate is not exempt from the gate. The rules are the
    // same shared object the TypeScript block uses; only the preset differs.
    name: 'carddemo/javascript-config',
    files: ['**/*.js'],

    extends: [
      // Alternatives Considered: `flat/recommended-error`, the plugin's plain
      // JavaScript set. Rejected in favour of the typescript-FLAVOR variant
      // because the two differ on exactly the point that matters here: the flavor
      // variant requires `@param` and `@returns` TYPES in the docstring and
      // permits type expressions in it, which is correct for a `.js` module where
      // there is no typed signature to carry them. That is the other half of the
      // type decision recorded beside `jsdoc/require-param` -- one Rule 1 clause,
      // satisfied by the DOCSTRING in both languages, and reached by a different
      // route in each: inherited from this preset here, and obtained in the
      // TypeScript block by switching `jsdoc/no-types` off so that
      // `jsdoc/require-param-type` and `jsdoc/require-returns-type` can be set to
      // error. Refactoring Rationale: this sentence previously read "satisfied by
      // the signature in TypeScript", which described the superseded arrangement
      // and disagreed with both the TypeScript block and the note beside
      // `jsdoc/require-param`; a reader who trusted it would have concluded the
      // type half was unenforced in `.ts` and removed the entries that enforce it.
      // Assumptions: no `typescript-eslint` config is extended in this block. This
      // file belongs to no TypeScript project by design, so a type-aware rule
      // would have no program to consult; the type-aware pass is scoped to the
      // TypeScript block above rather than switched off anywhere.
      jsdoc.configs['flat/recommended-typescript-flavor-error'],
    ],

    settings: {
      // Assumptions: TypeScript mode here too, for the same reason the flavor
      // preset was chosen over the plain one -- the JSDoc types in a file governed
      // by this block are TypeScript-flavoured, so they have to be parsed as such.
      // The presets set no `settings` of their own, so leaving this out would drop
      // the block to the plugin's default mode.
      jsdoc: { mode: 'typescript' },
    },

    rules: {
      ...JSDOC_DOCUMENTATION_RULES,
      ...CORE_JAVASCRIPT_RULES,

      // Assumptions: the import bans apply here as well, even though the packages
      // they name are browser dependencies that a Node-loaded configuration file
      // has no reason to reach for. A ban that covers only the files where the
      // mistake is likely is a ban with a documented gap; covering the whole tree
      // costs nothing and leaves none.
      ...IMPORT_DISCIPLINE_RULES,
    },
  },
]);
