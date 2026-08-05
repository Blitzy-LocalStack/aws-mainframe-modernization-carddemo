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
 * The current `lint` script declared in `ui/package.json`, which is exactly
 * `eslint . --max-warnings=0`. The later-index
 * `.github/workflows/ui-ci.yml` is required to invoke that script as a
 * build-failing documentation gate. Nothing configured here is advisory when
 * the current script runs.
 *
 * Rule 1's four docstring elements, and the rule that decides each
 * ---------------------------------------------------------------
 * - Purpose -- `jsdoc/require-description`.
 * - Parameters (name, type and description for each) --
 *   `jsdoc/require-param`, `jsdoc/require-param-name` and
 *   `jsdoc/require-param-description`, plus `jsdoc/require-param-type` in plain
 *   JavaScript only. See the type note beside JSDOC_DOCUMENTATION_RULES for why
 *   the type half is split by language.
 * - Return values (type and description) -- `jsdoc/require-returns` and
 *   `jsdoc/require-returns-description`, plus `jsdoc/require-returns-type` in
 *   plain JavaScript only.
 * - Exceptions or errors -- `jsdoc/require-throws`.
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
 * State at this checkpoint: this configuration governs everything under
 * `ui/src` -- the entry module, the router, the three card screens, the two
 * typed API modules, the route helpers, the message catalog, the theme bridge
 * and the test setup -- plus `vite.config.ts` and `vitest.config.ts`. It runs
 * from `npm run lint` at `--max-warnings=0` and from the same script in
 * `.github/workflows/ui-ci.yml`. The remaining 18 screen routes of the 21 in
 * the plan are later-index work and will be admitted by the same rules rather
 * than by an exemption added for them.
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
import { defineConfig, globalIgnores } from "eslint/config";

// WHY : Assumptions: the plugin decides docstring PRESENCE and TAG COVERAGE, and
//       nothing beyond that. `docs/CODE_DOCUMENTATION_STANDARD.md` states the
//       same boundary for every language it covers, and this file is that
//       document's mechanical half for TypeScript, so the two have to agree
//       about where the machine stops and review begins.
import jsdoc from "eslint-plugin-jsdoc";

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
import tseslint from "typescript-eslint";

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
const TYPESCRIPT_FILES = ["**/*.ts", "**/*.tsx"];

// WHY : Refactoring Rationale: an earlier shape of this file spelled these rules
//       out separately in the TypeScript block and in the JavaScript block. Two
//       copies of one obligation drift -- a rule tightened in one place and not
//       the other produces a tree where the same missing docstring is a build
//       failure in a `.tsx` screen and a pass in a `.js` config file. Sharing one
//       object makes that impossible. The four settings that legitimately DO
//       differ by language are the JSDoc type tags, and they are deliberately
//       absent from this object so each language's own preset decides them --
//       see the note under `jsdoc/require-param` below.
const JSDOC_DOCUMENTATION_RULES = {
  // Rule 1, block presence: "Every new or modified function, class, and module
  // entry point must include a docstring."
  "jsdoc/require-jsdoc": [
    "error",
    {
      // Alternatives Considered: `publicOnly: true`, which would require a block
      // only on exported symbols, was evaluated and rejected. It is the exact
      // reading that `docs/CODE_DOCUMENTATION_STANDARD.md` retracts in its
      // TypeScript section: Rule 1 says "every new or modified function, class,
      // and module entry point" with no visibility qualifier, and an un-exported
      // helper is where this migration's non-obvious logic actually lands -- a
      // copybook field slice, a sign-overpunch normaliser, a keyset-cursor
      // comparator. Exempting those would exempt the code most in need of a
      // docstring. That document also warns that a configuration written the
      // narrow way "will silently implement the narrowed scope this section has
      // just rejected", so the value is stated explicitly instead of left to the
      // default: a grep for `publicOnly` then finds a decision rather than an
      // omission.
      // Trade-offs: what `publicOnly` was reaching for -- keeping the rule off
      // every throwaway closure -- is bought by `contexts` below instead, which
      // selects DECLARATIONS rather than export visibility. That draws the line
      // where a docstring is meaningful (a named function) rather than where a
      // module's public surface happens to end.
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
        "* > ArrowFunctionExpression",
        "*:not(MethodDefinition) > FunctionExpression",
        "TSDeclareFunction",
      ],

      // Assumptions: Rule 1 allows a trivial accessor "a single-line docstring",
      // which is an allowance about LENGTH and not an exemption from having one.
      // Accessors are therefore checked here, and the single-line form is made
      // sufficient by `jsdoc/require-returns` further down.
      checkGetters: true,
      checkSetters: true,

      // Assumptions: a constructor that only assigns its parameters tells a
      // reader nothing the signature does not; one with a body makes a decision
      // and has to explain it.
      checkConstructors: true,
      exemptEmptyConstructors: true,

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
  "jsdoc/require-description": [
    "error",
    {
      // Assumptions: this list mirrors the coverage of `jsdoc/require-jsdoc`
      // above. The rule's own default covers function declarations and
      // expressions only, so a class or a method would be required to HAVE a
      // block by one rule and permitted to leave it purposeless by the other --
      // and a purposeless block is the one outcome worse than no block, because
      // it looks answered.
      contexts: [
        "ArrowFunctionExpression",
        "ClassDeclaration",
        "ClassExpression",
        "FunctionDeclaration",
        "FunctionExpression",
        "MethodDefinition",
        "TSDeclareFunction",
      ],
      checkConstructors: true,
      checkGetters: true,
      checkSetters: true,

      // Alternatives Considered: `descriptionStyle: 'any'`, which would also
      // accept the purpose supplied through a `@description` tag. Rejected
      // because it admits two spellings of the same element with nothing to
      // choose between them, and because `docs/CODE_DOCUMENTATION_STANDARD.md`
      // and every landed module in this tree put the purpose in the block body.
      descriptionStyle: "body",
    },
  ],

  // Rule 1, element 2 (Parameters: name, type and description for each). Three
  // rules split that one clause -- that a tag exists per parameter, that it
  // names the parameter, and that it describes it.
  //
  // Refactoring Rationale -- the TYPE half of the clause. It is ENFORCED, in
  // TypeScript as well as in JavaScript, by `jsdoc/require-param-type` and
  // `jsdoc/require-returns-type` in the TypeScript block below. It previously was
  // not: the type was held to be satisfied by the typed signature, and the two
  // rules were left at the plugin's TypeScript-aware default of off. The argument
  // for that was real -- a second copy of the type inside the docstring is
  // unchecked prose that can drift from the signature -- but it answered a
  // question Rule 1 does not ask. Rule 1's Parameters element requires the
  // docstring to state "name, type and description for each parameter", and AAP
  // section 0.8.1 restates it as "each parameter with its name and type" for every
  // language it names, TypeScript included. A docstring that omits the type does
  // not satisfy that clause merely because the information exists elsewhere in the
  // file, and Rule 1's own Forbidden Patterns list "docstrings that omit
  // parameters, return values, or purpose" without qualification.
  //
  // Trade-offs: the drift the old rationale worried about is real and is accepted,
  // because it is bounded by two things that were already in place. The signature
  // remains the compiler's truth, so a drifted docstring cannot cause a type
  // error to be missed -- it can only mislead a reader. And `jsdoc/check-param-names`
  // below already fails the build when a documented parameter no longer exists,
  // which catches the most common shape of drift. Weighed against that, leaving
  // the clause unenforced meant the gate silently implemented a narrower rule than
  // the one it exists to enforce.
  //
  // Assumptions: this also removes a divergence that was itself hard to justify.
  // The clause was previously satisfied one way in `.ts` and the opposite way in
  // `.js`, because the JavaScript block extends the plugin's typescript-flavor
  // preset, which turns both rules on. One Rule 1 clause is now enforced by one
  // mechanism in both languages, so a reader moving between them meets one
  // convention rather than two. Enforcing it in TypeScript requires switching
  // `jsdoc/no-types` off there, which the TypeScript block does explicitly and
  // explains at that line.
  "jsdoc/require-param": [
    "error",
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
      unnamedRootBase: ["props"],

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
  "jsdoc/require-param-name": "error",
  "jsdoc/require-param-description": "error",

  // Rule 1, element 3 (Return values: type and description of what is returned).
  "jsdoc/require-returns": [
    "error",
    {
      // Assumptions: this is where Rule 1's trivial-accessor allowance is
      // honoured. The rule checks getters by default, so a getter documented in
      // the single line the rule permits -- `/** The current page cursor. */` --
      // would be reported for a missing `@returns`, making the allowance
      // unusable. Switching the getter check off is the narrowest change that
      // keeps the allowance available; the getter still needs a block, because
      // `jsdoc/require-jsdoc` above keeps `checkGetters` on.
      checkGetters: false,

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
  "jsdoc/require-returns-description": "error",

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
  "jsdoc/require-throws": "error",

  // Assumptions: `check-param-names` is the drift detector, and drift is the
  // failure this gate is least able to survive. A block whose `@param` names a
  // parameter that was renamed or removed is worse than a missing block: the
  // missing one prompts a reader to go and look, while the stale one answers the
  // question wrongly and is believed. Every other rule in this object checks that
  // documentation EXISTS; this is the only one that checks it still describes the
  // code beside it.
  "jsdoc/check-param-names": [
    "error",
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
  // choice on which this repository's own two sources disagree -- the landed
  // `src/messages/messages.ts` puts no blank line between a block's description
  // and its first tag, while the TypeScript example in
  // `docs/CODE_DOCUMENTATION_STANDARD.md` puts one. `startLines: null` accepts
  // either, so the gate stops adjudicating house style.
  // Trade-offs: the structural half is kept. `never` still rejects a blank line
  // BETWEEN tags, which is the case that matters: a stray blank line there ends
  // the preceding tag's description early, so a `@param` whose text continues
  // after it silently loses that text, and the tag still counts as present.
  "jsdoc/tag-lines": ["error", "never", { startLines: null }],

  // Assumptions: the two rules below decide that a block is WELL FORMED, which is
  // the precondition for every rule above being able to decide anything at all. A
  // misaligned continuation line or a type expression that does not parse is not
  // recognised as the tag it was meant to be, so the plugin skips it -- and a
  // missing element then passes as present. They are restated here rather than
  // left to be inherited because that failure is silent, and because an inherited
  // preset's contents can change between releases while this file's dependence on
  // them does not.
  "jsdoc/check-alignment": "error",
  "jsdoc/valid-types": "error",

  // Assumptions: both presets leave this rule OFF, so switching it on is a
  // decision rather than a restatement, and it is load-bearing in BOTH languages
  // because the TypeScript block below turns `jsdoc/no-types` off in order to
  // require types on `@param` and `@returns`. That is what makes this rule matter
  // here: a `.ts` file now carries type expressions in its JSDoc, and without
  // this rule a name in `@param {Accont}` -- a typo for `Account` -- would be
  // accepted as documentation of a type that does not exist. The rule resolves
  // those names against the file's real declarations, so a locally declared
  // interface and a built-in construction such as
  // `Readonly<Record<string, string>>` both satisfy it while a misspelling does
  // not. In the `.js` half it is substantive for the same reason and then some,
  // because there the JSDoc types ARE the type system rather than a restatement
  // of it. Trade-offs: this is the rule that makes the typed-tag requirement
  // meaningful instead of merely present, at the cost of one more place a
  // renamed type has to be updated -- which is the point, since the alternative
  // is an annotation nobody checks that reads as though somebody had.
  "jsdoc/no-undefined-types": "error",
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
  eqeqeq: ["error", "always"],

  // Assumptions: a money or identifier string that fails to parse yields NaN, and
  // `value === NaN` is always false -- so a guard written that way silently
  // classifies every unparsable amount as valid and lets it through to the
  // request body.
  "use-isnan": "error",

  // Assumptions: a mistyped comparand (`typeof x === 'sting'`) is always false, so
  // a type guard written that way takes the wrong branch every time without ever
  // throwing. The screens narrow values arriving from the API this way.
  "valid-typeof": "error",

  // Assumptions: `(row?.field).value` throws at run time even though the optional
  // chain looks defensive, and optional chaining is exactly how a partly-filled
  // browse page is read -- `noUncheckedIndexedAccess` in `ui/tsconfig.json` makes
  // every row slot optional precisely because the final page of a keyset browse
  // fills only some of its fixed number of rows.
  "no-unsafe-optional-chaining": "error",

  // Assumptions: an empty `catch` swallows the error the API client threw, and the
  // 75-character message band then shows nothing where the 3270 screen showed a
  // message. `allowEmptyCatch` is left at its default of false for that reason: a
  // deliberately ignored error has to say so in a comment, which is the same thing
  // Rule 1 asks of every other non-obvious decision.
  "no-empty": "error",

  // Assumptions: a `debugger` statement is not a compile error, so it reaches the
  // production bundle that CloudFront serves and halts the page for every user
  // with developer tools open.
  "no-debugger": "error",

  // Assumptions: a validation guard that has collapsed to a constant -- an
  // `if (someObject)` where the value is always truthy, a comparison left behind
  // after a refactor -- takes the same branch every time. The screens transcribe
  // COBOL validation chains branch for branch, so a guard that cannot vary is a
  // transcribed rule that is no longer being applied.
  "no-constant-condition": "error",

  // Assumptions: every user-visible string in this tree is transcribed verbatim
  // from a copybook constant or a COBOL program literal, and the migration treats
  // that text as a contract. An irregular whitespace character -- a non-breaking
  // space, a zero-width character -- inside such a literal renders identically to
  // a normal space while making the string differ from `app/cpy/CSMSG01Y.cpy` byte
  // for byte, which is a fidelity failure no test asserting on visible text would
  // catch. `skipStrings` is therefore overridden to false, which is the only
  // setting that inspects the literals this matters for; the default true would
  // check everything except them.
  "no-irregular-whitespace": [
    "error",
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
  "no-restricted-imports": [
    "error",
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
          name: "react-router-dom",
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
          group: ["antd/es/*", "antd/lib/*", "antd/es", "antd/lib"],
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
  "@typescript-eslint/no-explicit-any": "error",

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
  "@typescript-eslint/no-floating-promises": [
    "error",
    { ignoreVoid: false, ignoreIIFE: false },
  ],

  // Assumptions: `ui/tsconfig.json` sets `verbatimModuleSyntax`, so a type-only
  // import written without the `type` keyword is emitted as a real runtime import.
  // If the module it names exports no value under that name -- an `interface`, a
  // `type` alias, both of which the DTO layer is full of -- the browser fails when
  // it evaluates the module, not when anything checks it. This rule is not in the
  // preset, so without this entry the compiler flag would be enforcing the
  // constraint only where it can see a violation, and the fix would have to be
  // found at run time.
  "@typescript-eslint/consistent-type-imports": [
    "error",
    { prefer: "type-imports", fixStyle: "separate-type-imports" },
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
  globalIgnores(["dist/**", "node_modules/**"], "carddemo/generated-output"),

  {
    name: "carddemo/typescript",

    // Assumptions: this is the whole authored surface of the package apart from
    // this configuration file -- `src` (the 21 screens, the app shell, the theme
    // bridge, the API clients, the message catalog, the hooks and the tests) plus
    // the two root-level Vite and Vitest config files.
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
      // ⚠ Assumptions: choosing the TypeScript variant over the plain one is
      // what turns the JSDoc type tags off and `jsdoc/no-types` on -- and that
      // stance is DELIBERATELY OVERRIDDEN in this block's own `rules` below, which
      // sets `jsdoc/no-types` back off and requires the types instead. Read the
      // preset here as supplying the shared body of rules, NOT the type-tag
      // stance; the stance is decided at `jsdoc/require-param-type`, where the
      // reason is recorded.
      //
      // ⚠ Alternatives Considered: extending `flat/recommended-error` -- the
      // plain variant -- instead, and dropping the overrides. The two presets were
      // diffed against the installed plugin rather than assumed about, and they
      // turn out to differ in EXACTLY five rules, every one of them part of the
      // type-tag stance: the plain variant has `no-types` off with
      // `require-param-type`, `require-returns-type`, `require-property-type` and
      // `no-undefined-types` on, and the TypeScript variant inverts all five. So
      // the plain variant's stance is precisely the one this project wants, and
      // extending it would need no overrides at all. It still lost, for one
      // reason: this is the TypeScript block, and a future plugin release that
      // adds a genuinely TypeScript-specific adjustment will add it to the
      // TypeScript preset, which this block would then not inherit. Trade-offs:
      // the cost of that choice is the four override entries below and a reader
      // having to follow one indirection to learn the real stance -- paid so the
      // language-specific preset stays the base for the language-specific block.
      // The alternative traded four visible entries for a silent divergence later.
      jsdoc.configs["flat/recommended-typescript-error"],
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
        project: ["./tsconfig.json", "./tsconfig.node.json"],

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
      jsdoc: { mode: "typescript" },
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
      "jsdoc/check-tag-names": ["error", { typed: true }],

      // Rule 1, element 2 and element 3, the TYPE half of each: `@param {Type}`
      // and `@returns {Type}` are REQUIRED in TypeScript, not only in JavaScript.
      // The reasoning is argued in full at `jsdoc/require-param` in the shared
      // rule object above; the four entries here are what implement it.
      //
      // Assumptions: `jsdoc/no-types` has to be switched off for the other three
      // to be satisfiable at all, because the preset's TypeScript form rejects a
      // type expression in a `.ts` docstring outright -- so with it on, adding the
      // type Rule 1 asks for would itself fail the build. The four settings are
      // one decision and are meaningless apart: measured against the installed
      // plugin, a typed `@param` reports `jsdoc/no-types` under the preset and
      // `jsdoc/require-param-type` under its absence, so exactly one of the two
      // arrangements can pass.
      // Assumptions: this is the ONE `off` in this file that touches a `jsdoc/*`
      // rule, and it is not a relaxation -- it removes a prohibition in order to
      // ENABLE a requirement, and the net effect is strictly more enforcement than
      // before. It is written as an explicit entry rather than left to the preset
      // so that a reader auditing the gate can see the trade in one place.
      //
      // ⚠ Refactoring Rationale: `jsdoc/require-property-type` is here for
      // CONSISTENCY of the rule set, and the honest scope of it is narrow. The
      // asymmetry it removes is real: measured against the installed plugin, the
      // TypeScript preset leaves `jsdoc/require-property` and
      // `jsdoc/require-property-description` at error while turning ONLY the type
      // off, so without this entry a `@typedef` would have to document each
      // property and describe it yet could omit its type, in a block where
      // `@param` and `@returns` must carry one. That was coherent while `no-types`
      // forbade every type expression uniformly; switching `no-types` off is what
      // turns it into an inconsistency, which is why it belongs to this decision
      // and not a separate one. Trade-offs: it is DEFENCE IN DEPTH rather than the
      // primary guard, and claiming more would overstate it -- `check-tag-names`
      // with `typed: true`, set just above, already rejects `@typedef` and
      // `@property` outright in a `.ts` file as redundant beside a real type
      // system, so a `.ts` file cannot legally reach this rule at all. It is kept
      // because the cost is one line and the benefit is that a reader auditing
      // "must every documented member carry its type?" gets one answer from the
      // rule set instead of having to reason about which tag another rule happens
      // to forbid first. Assumptions: the authored tree contains zero `@typedef`
      // and zero `@property` today -- TypeScript expresses those shapes as
      // `interface` and `type` declarations, which is exactly why
      // `check-tag-names` can afford to forbid the tags -- so this entry changes
      // no current verdict, and both rules were probed against a deliberately
      // untyped `@property` to confirm which one reports it.
      "jsdoc/no-types": "off",
      "jsdoc/require-param-type": "error",
      "jsdoc/require-returns-type": "error",
      "jsdoc/require-property-type": "error",

      // Assumptions: `jsdoc/require-file-overview` is the only rule that could
      // mechanise Rule 1's "module entry point" clause, and it is left off after
      // measuring the tree rather than on principle: it insists on a literal
      // `@file` tag, and of the two modules landed here `vite.config.ts` carries
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
    name: "carddemo/javascript-config",
    files: ["**/*.js"],

    extends: [
      // Alternatives Considered: `flat/recommended-error`, the plugin's plain
      // JavaScript set. Rejected in favour of the typescript-FLAVOR variant
      // because the two differ on exactly the point that matters here: the flavor
      // variant requires `@param` and `@returns` TYPES in the docstring and
      // permits type expressions in it, which is correct for a `.js` module where
      // there is no typed signature to carry them. That is the other half of the
      // type decision recorded beside `jsdoc/require-param` -- the same Rule 1
      // clause, satisfied by the signature in TypeScript and by the docstring
      // here.
      // Assumptions: no `typescript-eslint` config is extended in this block. This
      // file belongs to no TypeScript project by design, so a type-aware rule
      // would have no program to consult; the type-aware pass is scoped to the
      // TypeScript block above rather than switched off anywhere.
      jsdoc.configs["flat/recommended-typescript-flavor-error"],
    ],

    settings: {
      // Assumptions: TypeScript mode here too, for the same reason the flavor
      // preset was chosen over the plain one -- the JSDoc types in a file governed
      // by this block are TypeScript-flavoured, so they have to be parsed as such.
      // The presets set no `settings` of their own, so leaving this out would drop
      // the block to the plugin's default mode.
      jsdoc: { mode: "typescript" },
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
