# Code Documentation Standard

> **Purpose.** This document is the single normative documentation convention for
> every newly-authored file in the CardDemo migration trees — `services/**`,
> `ui/**`, `data-migration/**`, `infra/**`, every Dockerfile, every SQL migration
> and every new CI workflow. It states the obligation once per language and gives
> a conforming example for each, so that "document the why" is a stated
> requirement with a named owner — a tool where a tool can decide it, a human
> reviewer where none can — rather than an aspiration.
>
> **Source of truth.** Two sources, and no others. The first is the project's
> single user-specified rule, **Rule 1 "Explainability"**, whose four sections are
> restated below clause for clause, with one worked example dropped for the reason
> given under [The rule](#the-rule); the restatement preserves the rule's scope
> exactly and narrows nothing. The second is the convention this repository
> already applies to its own test suite in
> [`tests/README.md`](../tests/README.md) §12 (L544–L549), which imposes an
> identical obligation naming the same four justification categories. This
> standard extends that established house convention to a polyglot tree; it does
> not invent a new one.
>
> **Precedence.** The AAP and Rule 1 are authoritative. This standard explains
> that contract, while the language configurations encode only its
> machine-checkable subset. Where prose and configuration disagree, both must be
> corrected to match the AAP and Rule 1; a linter setting may never narrow or
> override the governing requirement. A prose rule that claims more than the
> checked gate delivers is not an enforcement mechanism, so the gap remains a
> review failure until the configuration or review process enforces it.
>
> **Current state.** The Java Checkstyle, TypeScript/JavaScript ESLint, Python
> Ruff, Terraform lint/documentation, and workflow validation gates are present
> and build-failing. The four migration workflows under `.github/workflows/`
> run those checks with pinned actions. All sixteen Terraform module READMEs and
> both environment-root READMEs are generated; the bootstrap README documents
> the separately applied state backend. Machine success covers presence and
> syntax only—semantic accuracy and rationale quality remain mandatory Rule 1
> review obligations.


## Why this document exists

This is the one artifact in `docs/` whose existence is owed to a user-specified
rule rather than to any migration requirement. Nothing in the work of moving
COBOL programs to services would produce it. It exists so that Rule 1's closing
sentence — code missing its documentation fails review — has one written owner
per language, with as much of it as a tool can decide delegated to a build step
rather than resting on a reviewer's memory.

Its job is narrow and complete: state the obligation once for every language in
the new tree, give a conforming example per language, and name — separately — the
part of the obligation a gate can decide and the part that only review can.

Assumptions: **the machine half and the review half are not the same size, and
saying so is the point of this section.** A linter can decide whether a docstring
is *present* and whether its at-clauses *cover* the members they must cover. No
linter in any of these languages can decide whether the prose in a docstring is
*true*, whether a `WHY` comment names a real consequence, or whether the decision
it justifies was the non-obvious one on that line. Rule 1's Forbidden Patterns
are three-quarters semantic for exactly that reason: "restates what the code
does", "vague rationales", and "a non-obvious choice left undocumented when a
reasonable alternative exists" are all judgements. Every per-language section
below therefore states its gate **and its gate's blind spot**, because a standard
that advertises full mechanical enforcement teaches reviewers to stop reading —
which removes the only check that covers three of the four forbidden patterns.

Four other artifacts depend on this document and link to this exact path, so
the filename is part of its contract:

* [`CONTRIBUTING.md`](../CONTRIBUTING.md) points here for the complete
  polyglot convention.
* [`README.md`](../README.md) and
  [`MIGRATION_README.md`](../MIGRATION_README.md) link here from their migration
  guidance.
* [`.gitignore`](../.gitignore) names generated output, Terraform state, local
  environment files, private keys, and credential stores narrowly enough that
  this tracked standard and public CA bundles remain trackable.

Because those references are literal, the filename is
`CODE_DOCUMENTATION_STANDARD.md` at the `docs/` root. A single character of
drift breaks all four links.


## Scope

The following boundary is not negotiable, and it answers both halves of the
scope question — what the rule covers, and what it must never be applied to:

> The rule applies only to newly authored code. The COBOL baseline under `app/**`
> is reference-only, so no retrofitting of Javadoc-equivalent commentary into
> `app/cbl/*.cbl` is required — or permitted. The existing test suite under
> `tests/**` already satisfies the equivalent house convention and is likewise
> untouched.

Read "reference-only" precisely. The COBOL baseline is the behavioural
specification this migration encodes, and it is also a working system that keeps
running; the migration adds a path, it does not remove one. `app/**` carries no
documentation obligation because it is authoritative source material to be cited
by path and line, never edited — not because it is deprecated.

The same reference status applies to `scripts/**` and to
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml). That workflow is
**unchanged** by this migration: the existing COBOL suite keeps its own pipeline,
and the documentation gates described here are wired into new workflows beside
it.

**The migration is additive, and exactly three pre-existing files may be
modified.** They are `README.md`, `CONTRIBUTING.md` and `.gitignore`, and no
fourth exists — every other artifact of this migration is a new file in a new
tree. That boundary is stated here because it is what makes the reference status
above checkable rather than merely intended: a change to any file outside those
three that is not a new file is out of scope by construction, not by judgement.
All three permitted updates are present: the root README links the migration
guide, CONTRIBUTING links this standard, and `.gitignore` protects generated and
credential-bearing local artifacts without hiding deliverable configuration.

Where a migrated implementation deliberately behaves differently from the
baseline, that divergence is registered in
`docs/architecture/cobol-to-service-traceability.md`. This standard's only
interest in such a divergence is that it must be documented at the point of
implementation under one of the four categories below.


## The rule

Exactly **one** user-specified rule governs this project. The four sections that
follow are Rule 1 **restated clause for clause**, with its own section names
preserved so the mapping from standard to rule is one-to-one and auditable. The
restatement is not a paraphrase — every clause appears with its own wording — but
it is not a verbatim reproduction either, and the one difference is declared
rather than buried: the format clause's list of example docstring formats is
reproduced with **one inapplicable example removed**, for the reason recorded
immediately after it. Nothing else is added, dropped, narrowed or reordered, and
the rule's own text remains available in full through `review_rules`. Rule 1's
headline reads:

> Generate code with documentation that explains both WHAT the code does and WHY
> specific implementation decisions were made, using docstrings and inline
> comments.


### Docstring Requirements:

* Every new or modified function, class, and module entry point must include a docstring
* Each docstring must specify:
  * Purpose: What the function or class does
  * Parameters: Name, type, and description for each parameter
  * Return values: Type and description of what is returned
  * Exceptions or errors: Any that may be raised (where applicable)
* Follow the language's standard docstring format (JSDoc for JavaScript/TypeScript, Javadoc for Java, docstrings for Python, etc.)
* Trivial accessors (getters/setters with no logic) may use a single-line docstring

The four element names — **Purpose**, **Parameters**, **Return values**,
**Exceptions or errors** — are the normative ones. `tests/README.md` §12 uses the
short form "Purpose, Parameters, Returns, and Exceptions" for the same
obligation; the two denote one requirement, and the rule's labels win where the
wording matters.

Two decisions about the format clause are recorded here because both are
load-bearing and neither is obvious.

Alternatives Considered: the examples named in the format clause. Rule 1's
own clause offers one example docstring format per language, and one of the
languages it names is used nowhere in this repository; nothing in the migration
introduces it. The clause is reproduced above with that single inapplicable
example dropped and every example that applies retained — the one and only
difference between the restatement above and the rule's own text. Keeping it would
have created a per-language section governing zero files, which is worse than
silence: it invites a future reader to conclude that a component in that language
exists somewhere in the tree, and to go looking for it. The drop removes an
example, never an obligation: the clause's "etc." already extends the requirement
to any language the migration authors, and the section below discharges it for all
seven.

Alternatives Considered: **extending the obligation to languages that have no
docstring construct.** The clause ends in "etc.", and that trailing "etc." is the
textual authority for the second half of this standard. Four of the seven
languages below — HCL, SQL, Dockerfile and YAML — have no docstring construct at
all, so an analogous obligation is defined for each. The alternative was to read
the rule as silent on those languages and exempt them. That was rejected because
it would leave `infra/**`, every SQL migration, every Dockerfile and every CI
workflow entirely ungoverned, and those files hold the largest single body of
non-obvious decisions in the new tree — capacity choices, index ordering,
base-image pins, job ordering. Exempting them would exempt precisely the decisions
most likely to be reversed by someone who did not know why they were made. The
analogous obligations are therefore **normative, not advisory**.


### Inline Comment Requirements:

* Place comments adjacent to the code they explain
* Comments must explain WHY a decision was made, not WHAT the code does (the code already shows that)
* Each non-obvious implementation decision should document at least one of the following:
  * **Alternatives Considered:** What other approaches were evaluated and why this one was chosen
  * **Refactoring Rationale:** When replacing existing code, what was wrong with the old approach
  * **Assumptions:** What external contracts, data formats, or behaviors this code depends on
  * **Trade-offs:** What compromises were accepted (performance vs. readability, simplicity vs. flexibility, etc.)

The bold in those four bullets is the quoted rule's own typography and is **not**
part of a label's written form. The next section states the one form a label takes
in this tree, and every example of a wrong form there is quoted in backticks so
that a literal search for a canonical label never matches a prohibition.

#### The four labels, and their one permitted written form

Every rationale in the new trees is tagged with one of exactly four labels,
written character-for-character as follows and in no other form:

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

Four properties of that form are load-bearing, and each one is a way the label has
actually been written wrongly:

* **Plural where the rule writes it plural.** `Assumption:` and `Trade-off:` are
  not permitted abbreviations of `Assumptions:` and `Trade-offs:`.
* **Unparenthesised.** `WHY (Assumptions):`, `WHY ... (Assumption):` and a bare
  `(Trade-offs)` are all wrong; the label opens the rationale itself.
* **Colon retained.** A label with the colon dropped — `(Assumptions) the domain
  is closed` — names the category but does not read as a label.
* **No emphasis markup.** `**Assumptions:**` and `*(Assumptions.)*` are wrong in
  Markdown as well as in code. Markdown emphasis is presentation; the label is
  data.

Assumptions: the label is read by grep before it is read by a person. A reviewer
auditing this tree against Rule 1's validation gate has to find every rationale in
seven languages, and the only mechanism that works across all seven is a literal
string search — no linter parses prose in a Dockerfile, a `.tf` file or a SQL
migration. One spelling makes that search complete; four spellings of the same
category make it silently partial, and a rationale a search cannot find is a
rationale a review cannot count.

Trade-offs: this diverges from the singular idiom that predominates in the
repository's existing test suite, whose header blocks tag design-decision bullets
`Assumption:` and `Trade-off:` — for example
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml) L19–L43. That
suite is reference-only and is not retyped, so the two trees do read differently.
The cost is accepted because the plural is the form Rule 1 itself uses at its
lines 31–34, and its validation gate at line 43 makes that wording the sentence
this tree is audited against; matching the majority idiom would read more
consistently with the old tree while failing to match the rule actually enforced.
The forms are never mixed inside one file.

A prose sentence may still use these words as ordinary English — "the assumptions
this decision rests on" — and that is not a label. A label is the token followed
immediately by its colon, opening the rationale it introduces.


### Forbidden Patterns:

* Writing comments that restate what the code does (e.g., "// increment counter" next to counter++)
* Adding docstrings that omit parameters, return values, or purpose
* Leaving a non-obvious implementation choice undocumented when a reasonable alternative exists
* Using vague rationales ("this is better", "for performance") without specific justification

The fourth pattern is the one most often violated in good faith, and it is the
reason the worked examples later in this document are written the way they are. A
rationale is specific when a reader can tell what would go wrong under the
alternative — which cents differ, which rows are skipped, which build fails.
"For performance" names no consequence and therefore documents nothing.


### Validation Gate:

> Every new or modified function must have a docstring with purpose, parameters,
> and return values. Every non-obvious implementation decision must have an
> inline comment explaining why that approach was chosen using at least one of
> the categories above. Code missing either fails review.


## Language conventions

Seven **implementation** languages are covered, in this order: **Java,
TypeScript, Python, HCL, SQL, Dockerfile, YAML**. Three of them have a docstring
construct and the rule applies directly. Four have none, and carry the analogous
obligation established above. The declarative data formats are covered by the note
immediately below, and two shorter notes close the section — shell entry points and
Markdown itself.

Assumptions: **why seven, and what "seven" is a count of.** Seven is the number
of languages in which this migration authors **implementation or configuration
logic**: code that computes, provisions, builds or gates something, and in which a
non-obvious decision can therefore hide. Every one of the seven governs real files
in `services/**`, `ui/**`, `data-migration/**`, `infra/**` or
`.github/workflows/**`; none is listed that governs none. The count is stated
because a documentation standard that lists aspirational languages teaches readers
to skim it.

**Seven is not, however, a count of every file format the migration authors**, and
conflating the two would leave real files ungoverned. The migration also authors
**XML** (`services/pom.xml` and the nine module POMs, `config/checkstyle/*.xml`),
**JSON** (`ui/package.json` and `ui/package-lock.json`), **TOML**
(`data-migration/pyproject.toml`), **HTML** (`ui/index.html`), **Markdown**
(everything under `docs/**` and every new `README.md`), plain **text and environment
templates** (`ui/.env.example`, `data-migration/requirements.txt`, the new test
fixture records) and the **ignore file** (`.gitignore`). These carry the analogous
obligation in the form the format admits, and the form is the same one the four
docstring-less languages use:

* a **file-header comment block** stating purpose, and a why-comment adjacent to
  each non-obvious value — for every format that has a comment syntax. XML uses
  `<!-- -->`, TOML and text and ignore files use `#`, HTML uses `<!-- -->`. All of
  them are governed exactly as HCL is, and the POMs, the Checkstyle ruleset,
  `pyproject.toml`, `requirements.txt`, `.env.example` and `.gitignore` in this
  repository already carry that form.
* **JSON has no comment syntax at all**, which is a real gap rather than an
  exemption. The obligation is discharged beside the file instead: a manifest's
  reasoning belongs in the package `README.md` and in the sibling documents that
  cite it, and a pinned version whose pin is non-obvious is justified wherever a
  comment *can* live — which is why the TypeScript compiler pin, for instance, is
  justified in this document and in `ui/tsconfig.node.json` rather than inside
  `ui/package.json`. Trade-offs: the alternative was to adopt a
  comment-tolerant JSON dialect so the reasoning could sit inline. It was rejected
  because `npm` and every consumer of a lockfile read strict JSON, so the dialect
  would have to be stripped before use and the reasoning would be lost at exactly
  the moment the file was consumed.
* **Markdown** is governed by its own section below.


### Java

**Required form.** Javadoc on every type, every module entry point (the
`package-info.java` charter), every constructor and **every method regardless of
visibility — public, protected, package-private and private alike, and including
every `@Override`**. `@param` for each parameter, `@return` where a value is
returned, `@throws` for each exception the caller must handle. Trivial accessors
may use the single-line form.

Assumptions: Rule 1 attaches the obligation to "every new or modified function,
class and module entry point" and names no visibility at all, so visibility cannot
narrow it. A private helper is precisely where a non-obvious decision tends to
live — it exists because the logic was worth naming — and an `@Override` still has
to state what *this* implementation does with the contract it inherits, which the
supertype's Javadoc cannot say. Trade-offs: this is stricter than the Checkstyle
default, whose `MissingJavadocMethod` exempts anything annotated `@Override` and
whose `scope` attribute can be set to skip private members entirely. The
configuration in `config/checkstyle/checkstyle.xml` therefore sets `scope="private"`
and clears `allowedAnnotations`, accepting more Javadoc to write in exchange for a
gate whose coverage matches the rule instead of a subset of it.

Assumptions: **the scope is all visibilities, and the ruleset splits it in a way
worth knowing exactly.** Rule 1 says "every new or modified function, class, and
module entry point" and attaches no visibility qualifier, so a private method is in
scope exactly as a public one is. The Checkstyle configuration implements that scope
in two modules that divide the work, and the division is the difference between what
the build rejects and what review must catch:

* `MissingJavadocMethod` is configured `scope="private"` with
  `allowedAnnotations=""`, so **presence** is enforced at every visibility. Because
  Checkstyle's `scope` property is inclusive downwards, `private` admits public,
  protected, package-private and private alike; a private method with no Javadoc at
  all now **fails the build** rather than deferring to review. Clearing
  `allowedAnnotations` matters independently: it defaults to `Override`, so leaving
  it unset would exempt every overriding method — the population that most often
  narrows or strengthens an inherited contract.
* `JavadocMethod` is configured `accessModifiers="public, protected, package,
  private"` with `allowMissingParamTags` and `allowMissingReturnTag` both false and
  `validateThrows` true, so wherever a Javadoc block **does** exist — private
  methods included — its `@param`, `@return` and `@throws` coverage is enforced.

Refactoring Rationale: two earlier wordings of this paragraph are corrected here
rather than overwritten silently, because each would mislead a reader in the same
direction. The first named public and package-private methods only. The second
claimed presence stopped at package scope and left private methods to review, which
is now false in the configuration and was the more damaging of the two: a reader who
trusted it would leave private methods undocumented and be rejected at `validate`,
with the failure pointing at a check the prose said did not apply to them. The
ruleset is the authority under this document's precedence clause — where this prose
and the configuration could be read differently, the configuration wins — so the
prose is what moves.

**Mechanical gate — live today.**
[`config/checkstyle/checkstyle.xml`](../config/checkstyle/checkstyle.xml) with
its companion
[`config/checkstyle/suppressions.xml`](../config/checkstyle/suppressions.xml),
driven by `maven-checkstyle-plugin` and bound to the Maven **`validate`** phase
in [`services/pom.xml`](../services/pom.xml) under the execution id
`checkstyle-documentation-gate`. Binding to `validate` rather than to a
verification phase is deliberate: the gate runs on **every local build**, before
compilation, so a missing Javadoc surfaces on the developer's machine rather
than only in CI. The TypeScript, Python, HCL, and workflow gates described below
are also live; this one is distinguished by running at Maven's earliest
validation phase.

**What this gate cannot decide.** It decides presence and coverage: that a Javadoc
block exists on each audited element, that `@param` names every parameter, that
`@return` accompanies a returning method, and that no at-clause body is empty. It
decides **nothing** about whether the prose is accurate, whether a `WHY` comment
names a specific consequence, or whether the line it sits above was the non-obvious
one. Those are the review half, and they are the half Rule 1's Forbidden Patterns
are mostly about. Two further limits are recorded in the ruleset itself and are not
repeated in full here: `validateThrows` holds no model of the exception type
hierarchy and skips a `throw` inside a `try` that has a `catch`, and neither module
inspects local classes, anonymous classes or lambda bodies. Whatever they miss is
carried by review.

The example below is an **excerpt**: the method body is elided as `...` because the
subject of the example is the documentation, not the arithmetic. It is not
compilable as written.

```java
/**
 * Computes one month of interest for a transaction-category balance.
 *
 * <p>The product is formed at full precision before the division, mirroring the
 * order the baseline COBOL uses. WHY : Assumptions: the arithmetic order is part
 * of the behavioural contract, not an implementation detail — dividing first and
 * multiplying second yields a different final cent on many balances, and the
 * golden-master comparison would flag it as a parity failure.
 *
 * <p>WHY the rounding mode is the general one here too. Assumptions: this path is
 * not an exception to the money contract. Every reduction to cents in this
 * migration is scale 2 with {@code RoundingMode.HALF_UP}, and accrual differs from
 * the others only in the ORDER of its operations. The baseline does behave
 * differently: the accrual paragraph in {@code app/cbl/CBACT04C.cbl} stores its
 * result into {@code PIC S9(09)V99} and carries no {@code ROUNDED} phrase — and no
 * statement anywhere in that program does — so the reference truncates toward zero.
 * That one-cent difference is a registered behavioural divergence rather than a
 * second rounding mode, and it is recorded as {@code C-ROUNDING} beside the other
 * intentional divergences; a package that contradicted the contract would not be
 * auditable, whereas a registered divergence is.
 *
 * @param categoryBalance the category balance to accrue against; never {@code null}
 * @param annualRatePercent the annual disclosure-group rate as a percentage
 * @return the monthly interest, scaled to two decimal places, half-up
 * @throws IllegalArgumentException if either argument is negative
 */
public Money monthlyInterest(Money categoryBalance, BigDecimal annualRatePercent) {
    ...
}
```

Two library non-adoptions follow directly from this gate, and both are recorded
so that adding either one is understood as removing the gate:

* **Lombok is not used.** Alternatives Considered: generated accessors and
  constructors cannot carry the Javadoc this standard requires, so a Lombok-built
  class either fails the gate or is suppressed out of it. Java `record` types with
  explicit constructors give the same brevity with members that can be documented.
* **MapStruct is not used.** Alternatives Considered: copybook-to-DTO mapping is
  not mechanical. It drops `FILLER`, masks the primary account number to its last
  four digits, suppresses the card verification value entirely, encrypts the
  national and government-issued identifiers, and renames three misspelled
  baseline fields. Each of those needs a justification at the mapping site, and a
  generated mapper has nowhere to hold one.


### TypeScript

**Required form.** JSDoc or TSDoc on **every function, every class and every
module entry point**, exported or not, with `@param`, `@returns` and `@throws` as
applicable. Trivial accessors may use the single-line form. The inline-comment
requirement applies **in addition**, never instead: a module-private helper that
makes a non-obvious choice owes its WHY comment as well as its block.

Refactoring Rationale: **an earlier wording of this section scoped the block
requirement to exported symbols, and it was withdrawn.** It argued from AAP
§0.2.1.6, which lists `ui/eslint.config.js` as "`eslint-plugin-jsdoc` requiring
JSDoc on exported components and functions", and from §0.8.1, which repeats that
verbatim. The argument failed on one step: it read a **minimum as an exclusion**.
Both sentences say what the gate must require; neither says what it may not
require, so a gate that additionally reaches a module-private helper satisfies
both of them literally. Set against that, Rule 1 asks for a docstring on "every
new or modified function, class, and module entry point" and attaches no
visibility qualifier at all. Only one reading satisfies both documents at once,
which is why the wider scope is not a divergence from the AAP — the narrower one
was the reading that had to add a qualifier the specification does not contain.

Assumptions: **the widened scope costs the landed tree nothing, which was
measured rather than asserted.** With `publicOnly: false` in force, `npm run
lint` reports zero violations across `ui/**`, so nothing in this section is
aspirational. Trade-offs: **every inline closure now owes a block** — an
`onClick`, a `.map` body, a `useMemo` factory. Rule 1 allows a trivial function
"a single-line docstring", an allowance about length and not about presence, so a
one-line closure costs one line. The exchange goes this way because the
alternative was measured too: under the narrower scope, an undocumented
module-private `function` and an undocumented anonymous `.map` callback inside an
exported component were both reported clean, and this tree had already been
bitten by exactly that gap once, in `ui/src/messages/messages.ts`.

**Every `@param` and `@returns` carries its type in the tag, in TypeScript
exactly as in JavaScript** — `@param {string} accountId - ...`,
`@returns {AccountView} The account view ...`. Rule 1's Parameters element asks
for "name, type and description for each parameter" and its Return values element
for the "type and description of what is returned"; both are addressed to the
DOCUMENTATION, and neither is qualified by language.

Refactoring Rationale: **an earlier wording of this section made the `{Type}`
annotation permitted but never required in TypeScript, and it was withdrawn.** Its
argument was a good observation — the signature already states the type, and the
compiler checks it, so the tag duplicates a checked fact with unchecked prose —
that answered a question Rule 1 does not ask. Rule 1 draws no distinction between
a language whose signature happens to carry the type and one whose signature does
not, and it grants no exemption on the ground that another artifact states the
same fact more reliably. A gate asking for two thirds of an element while
reporting a pass is the shape of failure Rule 1's validation clause exists to
catch, and a reader cannot tell it apart from one asking for three.

Trade-offs: **a `{Type}` tag is a second textual source of truth about a type, and
that cost is accepted rather than argued away.** The two can disagree, and when
they do only the annotation can be wrong. This migration has a specific reason to
care: field widths and decimal scale are transcribed from the copybooks under
Transformation Rule T1, and a drifted annotation is exactly how a `string` money
field acquires a documented `number`. Two things bound the cost.
`jsdoc/no-undefined-types` stays on, so a `{Accont}` typo is a build failure; and
`typescript-eslint`'s type-aware rules plus `tsc --noEmit` keep the signature
authoritative for compilation, so a drifted annotation misleads a reader without
ever misleading the compiler. `jsdoc/check-param-names` still fails the build when
a documented parameter no longer exists.

Assumptions: **the obligation is now identical in both languages, and
`jsdoc/no-types` stays off for exactly that reason.** In plain JavaScript the
JSDoc types *are* the type system; in TypeScript they document a type the compiler
also holds. The plugin's TypeScript preset would REJECT a `{Type}` in a `.ts`
docstring outright, so leaving `jsdoc/no-types` on would forbid the very tags
`jsdoc/require-param-type` and `jsdoc/require-returns-type` now require. The two
settings are one mechanism and neither works without the other; `no-types` being
off admits the tag, and the `require-*-type` rules oblige it.

Assumptions: **the change costs the landed tree nothing, and that was measured
before it was adopted.** `ui/src/**` already carries 90 `@param {` and 97
`@returns {` tags and not one bare tag, so raising the three rules reports zero
new violations today and binds every docstring written after it.

**Mechanical gate — live today.** `eslint-plugin-jsdoc` rules configured in
[`ui/eslint.config.js`](../ui/eslint.config.js), run by the `lint` script
declared in [`ui/package.json`](../ui/package.json) as `eslint .
--max-warnings=0`. That threshold is what makes the gate a gate: every JSDoc
rule this document relies on is set to `error`, and even a warning would fail
the run. Exactly ONE `jsdoc/*` entry is deliberately set to `off` —
`jsdoc/no-types`, and only so that the `{Type}` tags the three `require-*-type`
rules oblige are admitted at all, as the section above records. It relaxes no
element of Rule 1. Two `linterOptions` complete the gate: `noInlineConfig` is
`true`, so an `/* eslint-disable */` comment in a source file is ignored rather
than honoured, and `reportUnusedDisableDirectives` is `error`, so such a comment
cannot survive a run looking load-bearing. Every prohibited shape this paragraph
claims is caught has a committed negative probe in
[`ui/src/test/documentationGate.test.ts`](../ui/src/test/documentationGate.test.ts),
which lints deliberately non-conforming sources through the ESLint Node API
against this very configuration, so a future relaxation fails a test rather than
passing quietly. The plugin's dependency is pinned in `ui/package.json`,
which fixes the version the gate runs at. `.github/workflows/ui-ci.yml` invokes
that same script as a required step, so the gate runs both locally and on every
push.

**What that gate does and does not decide.** `eslint-plugin-jsdoc` decides
presence and tag coverage on the declarations its rule configuration selects. Two
limits are stated in that configuration, and are stated here so the standard does
not read as promising more than the tool delivers. First, its `require-jsdoc`
contexts must select **all** function and class declarations, not only exported
ones — which is why they are written as relational selectors
(`* > ArrowFunctionExpression`, `*:not(MethodDefinition) > FunctionExpression`)
that match a function in every position, including a bare callback argument, rather
than as positional selectors that silently implement the narrowed scope this section
has just rejected. Second, even so configured it decides nothing about docstring
*accuracy* or `WHY`-comment quality — the same blind spot the Java gate has, and the
reason the review half of Rule 1 governs TypeScript exactly as it governs Java.

The example below is an **excerpt**: the component body is elided as `...` because
the subject of the example is the documentation. It is not compilable as written.

```tsx
/**
 * Renders the persistent function-key bar.
 *
 * Actions are bound to real keyboard events as well as buttons.
 * Assumptions: the 3270 original was keyboard-only, so keyboard operation is a
 * fidelity requirement — rendering buttons alone would take the existing
 * workflow away from users who already have it.
 *
   * @param {PfKeyBarProps} props the component's props, destructured below
   * @param {readonly KeyAction[]} props.actions the enabled key actions for the
   *   current screen
   * @param {(action: KeyAction) => void} props.onInvoke called with the action a
   *   user triggered, by key or click
   * @returns {ReactElement} the key bar element
 */
export function PfKeyBar({ actions, onInvoke }: PfKeyBarProps): ReactElement {
  ...
}
```

Note the destructured root. `props` is documented in its own right as well as each
member, because the gate checks destructured roots: documenting only the members
leaves the parameter itself undescribed, and documenting only the parameter says
nothing about the values the component actually reads.

Trade-offs: the compiler version is constrained by this gate. The TypeScript
compiler is pinned below the newest major release, and the reason is not a
compiler preference. The type-aware lint toolchain that runs the JSDoc gate
declares an upper bound on the compiler version it accepts; raising TypeScript
past that bound does not fail loudly — it stops the type-aware pass, and with it
the documentation gate, from running at all. Build speed is therefore traded for
an enforceable documentation rule. The pin lives in `ui/package.json` with its own
inline justification at the point of use. It is recorded here as well so that
nobody upgrades it later without understanding what breaks.


### Python

**Required form.** A module docstring on every module, and a docstring on every
class and function, using the Args / Returns / Raises structure. Purpose first,
then each parameter with its type, then the return value, then anything raised.

**Mechanical gate — two configured, build-failing halves, together still narrower than
the requirement above.** The first half is the pydocstyle **`D`** rule family, selected
under `[tool.ruff.lint]` in
[`data-migration/pyproject.toml`](../data-migration/pyproject.toml); narrowing that
`select` entry, or letting a convention setting switch off its presence checks, switches
off most of a review gate for a whole language rather than tidying a lint configuration.
The second half is
[`data-migration/tests/test_docstring_gate.py`](../data-migration/tests/test_docstring_gate.py),
which walks the package and its suite with the standard library's `ast` and asserts a
non-blank docstring on every module, class and function at **every** visibility and
**every** nesting depth. `.github/workflows/services-ci.yml` installs the hash-locked
development toolchain from `data-migration/requirements-dev.txt`, runs `ruff check`, and
runs this package's pytest suite with no tolerance, so both halves execute locally and on
every covered push.

**Why the second half exists, measured rather than assumed.** `D101`, `D102`, `D103` and
`D106` are *public*-declaration checks. Verified against the pinned ruff: a declaration is
invisible to them when its own name carries a single leading underscore, when any
enclosing class is privately named — a plainly public method of a `_Private` class raises
no `D102` — or when it is declared inside a function body, at any depth and any
visibility. A module follows the same single-underscore convention, so `_x.py` raises no
`D100` while `__init__.py` is public and does raise `D104`. Measured **when that gate was
added**, and deliberately not carried as a standing figure: **90 of the 221** declarations
in the package source were invisible to those checks, and **32 of the 107** in its suite;
the live figures are whatever the gate reports, because a count written into prose goes
stale on the next authored function and the staleness is invisible. The formatting rules
(`D400`, `D403`, `D205` and the rest) were measured to reach every docstring ruff finds
regardless of visibility or nesting, so the `ast` gate checks presence only and duplicates
none of them. Both halves are load-bearing: removing either leaves an unenforced half of
the same obligation.

**What neither half can decide, stated precisely because the gap is wide.** The `D` family
checks that a docstring **exists** on a public declaration and checks its formatting —
one-line summary, imperative mood, blank lines, closing quotes — and the `ast` gate
extends only the existence half to the rest. Neither checks that the Args, Returns and
Raises sections are present or complete: a single-line docstring on a five-parameter
function satisfies every `D` rule, satisfies the presence gate, and still violates Rule 1's
Docstring Requirements and its second Forbidden Pattern. Assumptions: pydocstyle has no
rule that cross-checks a docstring's parameter list against a signature, and a presence
walker cannot judge content at all, so that half of the obligation cannot be delegated and
is a **required human-review check** on every Python change. It is called out here rather
than left implied because a configured `D` family plus a green test run is easy to mistake
for full coverage.

The example below is an **excerpt**: the function body is elided as `...` after the
comment that is the point of the example.

```python
def decode_zoned(raw: bytes, scale: int) -> Decimal:
    """Decode one zoned-decimal field with sign overpunch to an exact Decimal.

    Args:
        raw: the field's bytes, already sliced to its fixed width.
        scale: number of implied decimal places from the PICTURE clause.

    Returns:
        The exact value as a Decimal; never a float.

    Raises:
        ValueError: if the trailing byte is not a recognised overpunch.
    """
    # WHY : Assumptions: the sign lives in the high-order nibble of the final
    # byte, so this decodes bytes rather than str. Passing the field through a
    # text decoder first turns the overpunch into a replacement character and
    # silently loses the sign on negative balances.
    ...
```


### HCL (Terraform)

**Required form**, as the analogous obligation for a language with no docstring
construct: *a file-header comment block in every `.tf` file, a `description` on
every `variable` and `output`, and a why-comment on each non-obvious resource
argument.*

**Mechanical gate — configured and build-failing.**
[`infra/.tflint.hcl`](../infra/.tflint.hcl) for lint, and
[`infra/.terraform-docs.yml`](../infra/.terraform-docs.yml) for generated module
documentation whose freshness is drift-checked in CI — a module whose generated
documentation no longer matches its variables fails the check. The prose half of
the obligation is a `README.md` in **each `infra/modules/*` directory** and in
**both `infra/envs/*` roots**. All sixteen module READMEs and both environment
READMEs exist, and `infra/bootstrap/README.md` documents the separately applied
remote-state backend. `.github/workflows/infra-ci.yml` checks all nineteen
documented directories with terraform-docs 0.20.0.

**What these two gates cannot decide.**
TFLint decides that a `description` is **present** on every `variable` and
`output`, and terraform-docs decides that the generated table **matches** the HCL
beside it. Neither assesses whether a description is informative, and neither reads
a `WHY` comment at all — TFLint's one comment-related rule checks comment *syntax*,
not comment *content*. The file-header block and the per-argument why-comment are
therefore **review obligations** with no machine backing, which is precisely why the
per-directory README is required as the prose half. The gates are green for the
authored tree; that result proves structural consistency, not rationale quality.

The example below is an **excerpt** — a header block, one variable and one resource
lifted out of a module — not a complete, applyable configuration.

```hcl
# =============================================================================
# infra/modules/sqs/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Request and reply queues replacing the baseline message-queue pairs, each
#   with a dead-letter queue and encryption at rest.
#
# Non-obvious design decisions:
#   - Alternatives Considered: a standard queue was rejected for the
#     authorization pair. Ordering per card is observable behaviour in the
#     baseline, and only a FIFO queue preserves it.
# =============================================================================

variable "max_receive_count" {
  description = "Deliveries attempted before a message is moved to the dead-letter queue."
  type        = number
}

resource "aws_sqs_queue" "request" {
  # WHY : Assumptions: the group identifier is the card number because ordering
  # is required per card and not globally. Using a constant group would serialise
  # every card behind one another and collapse throughput to a single consumer.
  fifo_queue = true
}
```


### SQL

**Required form**, as the analogous obligation: *a header block plus a why-comment
on each non-obvious constraint or index.* This applies to
`services/*/src/main/resources/db/migration/**` and to `data-migration/sql/**`.

**Mechanical gate — none. This obligation is review-only, in full.** HCL has a
linter and SQL does not, so this is the one place in the standard where the whole
obligation — presence *and* content — rests on the header block being written and on
the review gate in Rule 1's closing sentence. Nothing in the build inspects a
migration's comments. Two canonical cases must always carry their reasoning,
because both look like ordinary schema noise and both encode a preserved
behavioural contract:

* the `ON DELETE RESTRICT` foreign key from transaction categories to
  transaction types, which preserves the legacy `XTRNTYCAT` semantic so that
  deleting a referenced type is refused rather than cascading; and
* the descending fraud index, whose column order matches the legacy `XAUTHFRD`
  index so that the most recent authorization for a card is still the first row
  read.

The example below is an **excerpt** from a migration rather than a complete one: a
real migration also creates the two tables the constraint references, and the
`ALTER TABLE` shown here would fail on its own.

```sql
-- =============================================================================
-- V1__reference.sql
-- -----------------------------------------------------------------------------
-- Purpose: reference-data tables for transaction types and categories.
-- =============================================================================

ALTER TABLE reference.transaction_categories
  ADD CONSTRAINT fk_category_type
  FOREIGN KEY (type_cd) REFERENCES reference.transaction_types (type_cd)
  -- WHY : Assumptions: RESTRICT, not CASCADE. The legacy XTRNTYCAT relationship
  -- refuses the delete of a referenced type, and the screen that offers the
  -- delete surfaces that refusal to the user. CASCADE would silently remove the
  -- dependent categories instead, which is a different observable outcome.
  ON DELETE RESTRICT;
```


### Dockerfile

**Required form**, as the analogous obligation: *a header plus a justification on
the base-image pin and on layer ordering.*

**Mechanical gate — none for the documentation; the build checks only the pin.**
Review carries the whole documentation obligation. The image build contributes one
narrow guarantee and it is worth naming exactly: a base-image tag that does not
resolve fails the build, so the *pin* is checked even though the *justification*
beside it is not. That is why the pin is the one line in a Dockerfile this standard
insists on a comment for.

The example below is an **excerpt** — a header block and the two instructions that
carry the layer-ordering justification — not a buildable Dockerfile. A real one also
declares its base images, its non-root user and its health check.

```dockerfile
# =============================================================================
# services/auth-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose: build and package the auth service as a non-root container image.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the runtime tag is the headless Amazon Linux variant. The
#     publisher ships no Alpine variant of this image, so the intuitive
#     `-alpine` suffix is not a smaller alternative — it is a tag that does not
#     resolve, and it fails every build.
# =============================================================================

# WHY : Trade-offs: dependencies are resolved in their own layer before the
# sources are copied, so a source-only change reuses the cached dependency layer.
# The cost is one extra layer in the final history, which is accepted.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
```


### YAML

**Required form**, as the analogous obligation for CI workflow files: *a header
plus justification on job ordering and caching.* Two further requirements apply to
every new workflow: a least-privilege `permissions` block, and third-party actions
pinned to immutable commit SHAs rather than to moving tags.

**Mechanical gate — none for the documentation semantics.** Review carries it.
The workflows' required steps gate the code they run, while YAML parsing,
immutable-action checks, and least-privilege review protect their executable
shape. The four migration workflows are present under `.github/workflows/`.
Their header template follows the one already established by
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml) (L1–L51) and is
reproduced below in shape.

The block below is a **template**, not a workflow: the angle-bracketed spans are
placeholders to be replaced, and it declares no `jobs`, so it would not run.

```yaml
# =============================================================================
# .github/workflows/services-ci.yml
# -----------------------------------------------------------------------------
# Purpose:
#   <what this file is for, wrapped>
#
# WHY (non-obvious design decisions):
#   - Assumptions: <...>
#   - Trade-offs: <...>
#   - Trade-offs: <...>
# =============================================================================

# Assumptions: <justification placed directly above the key it explains>
on:
  push:
    branches: [main]
```

Note the two comment positions in that template, both of which the existing
workflow uses: the framed block at the top of the file, and a single labelled
rationale line placed **directly above** the key it explains.


### Shell entry points

Shell scripts are not a language section of their own, because the new tree
authors few of them and the obligation is a single item: a header comment block
stating purpose, and a why-comment on any non-obvious ordering or flag. The
existing runners under `scripts/**` already model this form, and they remain
unchanged.


### Markdown and documentation

Markdown has no docstring construct either, and this standard is bound by its own
rule. Every document under `docs/**` and every new `README.md` therefore:

* **opens with a header stating its purpose and its source of truth**, so a reader
  can tell in one screen what the document governs and what it derives from;
* **carries reasoning for every non-obvious assertion**, under at least one of the
  four category names spelled exactly as in the rule; and
* **uses the `# WHAT:` / `# WHY :` idiom in every fenced command block**, as
  described in the next section.

A further requirement applies to every document in the new trees and this document
is bound by it too: **a path to a document that does not exist yet is written as a
plain code span, never as a link.** A link that resolves to nothing is a
documentation defect that a reader discovers by clicking, and a document authored
ahead of its siblings would otherwise publish a page of them. The sibling
architecture documents therefore appear in `docs/**` as
`` `docs/architecture/<name>.md` `` until the file exists, at which point the code
span becomes a link.

This document complies with its own requirement. Its header names Rule 1 and
`tests/README.md` §12 as its two sources, and each non-obvious choice it makes
about itself is justified where the choice is made: dropping the one inapplicable
example from the format clause, under Alternatives Considered; counting seven
implementation languages and governing the remaining authored formats separately
under Assumptions; making the analogous obligations normative rather than advisory,
under Alternatives Considered; separating each gate's machine-checkable subset from
the review obligation it cannot cover, under Trade-offs, since a standard that overstates
its automation costs the review attention that three of Rule 1's four forbidden
patterns depend on), and using one consistent ASCII spelling of the category names
even where a quoted source typesets a hyphen differently, under Assumptions, since a
downstream search for a category name has to match every occurrence.


## The `# WHAT:` / `# WHY :` idiom — prose command blocks only

**Scope, stated before the form, because getting this backwards is the single most
common way to fail Rule 1's forbidden-patterns clause.** The twin idiom below
belongs to **fenced command blocks in prose** — this document, every new
`README.md`, and `docs/runbooks/**` — and **nowhere else**. It is explicitly
**forbidden on a statement in any code or configuration file**: no `.java`,
`.ts`, `.tsx`, `.py`, `.tf`, `.hcl`, `.sql`, `.xml`, `.yml`, `.yaml`, `.json`,
`.gitignore`, `.env.example`, `Dockerfile` or `pom.xml` may carry a
statement-level `WHAT:` comment.

In those files:

* **Purpose belongs in the language-standard documentation** — the Javadoc, the
  JSDoc, the Python docstring, or, for a language with none, the file-header
  comment block. A module-level or file-header `WHAT:` inside that header block is
  therefore correct and is not what this prohibition is about.
* **Inline comments carry a canonical WHY rationale and nothing else.** One label
  from the four, the reason, and the consequence under the alternative.

Assumptions: a `WHAT:` line sitting above a statement restates what the statement
already says, and Rule 1's first forbidden pattern is exactly that — "writing
comments that restate what the code does". A shell command inside a fenced block is
a different case: it has no docstring construct available and is often a long
pipeline whose effect is genuinely not evident from the tokens, which is what earns
the twin form there and only there. Trade-offs: two comment conventions now exist
in one repository, one for prose command blocks and one for code, and a reader has
to know which file they are in. That is accepted because collapsing them the other
way — allowing the twin form in code — reintroduces the narration the rule forbids
on every statement it touches.

Within a fenced command block, the form is:

```text
# WHAT: <what the command does>
#       <continuation aligned under the text>
# WHY : <why it is done this way, or why in this order>
#       <continuation aligned under the text>
```

The spacing is not cosmetic and it is easy to get wrong. **`# WHAT:` has no space
before the colon; `# WHY :` has one space before the colon.** That single space
makes both labels eight characters wide, so their text starts in the same column
and continuation lines — `#` followed by seven spaces — align under both. Reading
a run of blocks, the reader's eye tracks one left margin instead of two.

This is the established house form: it appears throughout
[`tests/README.md`](../tests/README.md) §5.1–§5.6 and §9. One block in that file,
in §11, uses the bare `# WHY:` instead. That variant is real, not a typo in one
place or the other. Prescribe the aligned `# WHAT:` / `# WHY :` pair for fenced
command blocks in the new trees, and use a bare labelled rationale line — the
canonical label, its colon and the reason — for a one-line justification attached
to a single statement or configuration key.

Inside a block, tag the justification with a category when the reason is a
decision rather than a mechanic — for example `# WHY : Trade-offs: ...`. The
category names are the four from the rule, in the one written form defined above:
plural, unparenthesised, colon retained, no emphasis markup. A singular
abbreviation is not an accepted variant here either.


## Worked examples: what a good "why" looks like

Rule 1 is hardest to satisfy honestly where a decision looks arbitrary, and
easiest to fail by writing a rationale that names no consequence. Each entry
below is a decision this migration actually makes, tagged with the category it
falls under and stating specifically what differs under the alternative. These
are the reference standard for the level of detail expected at the point of use;
each one belongs in the code that implements it, not only here.

1. **Interest is computed multiply-before-divide.** Assumptions: The baseline
   forms the product of balance and rate before dividing. The migrated code
   multiplies at full precision and only then divides with an explicit scale and
   rounding mode. Dividing first and multiplying second yields a **different final
   cent** on many balances, so the arithmetic order is part of the behavioural
   contract rather than a style choice.

2. **EBCDIC is decoded per field, never per record.** Assumptions: Decoding
   happens at exactly one boundary, in binary mode, one fixed-width field at a
   time. Decoding a whole record passes sign-overpunch bytes, packed nibbles and
   embedded low values through a text decoder, which replaces them. The result is
   data that **looks almost right** — the failure is silent, which is what makes
   this worth a comment at the decode site.

3. **Money crosses the wire as a JSON string, never a JSON number.**
   Assumptions: Most clients parse a JSON number into an IEEE-754 double, which
   destroys exactness at the boundary the user actually reads. Money is exact
   fixed point at every hop instead: a fixed-scale numeric column, a scale-2
   decimal in Java, a `Decimal` in Python, a string in JSON. `float` and `double`
   are forbidden in the money path and the prohibition is architecture-tested.
   Trade-offs: clients must parse the string themselves.

4. **Lists page by key, not by offset.** Alternatives Considered: Offset
   pagination was evaluated and rejected: under concurrent inserts it **skips and
   repeats rows**, which changes behaviour that the baseline's browse-by-key does
   not have. Keyset pagination over the same key columns reproduces the original
   page boundaries.

5. **Transaction posting keeps one atomic commit, via a narrowly-scoped
   cross-schema grant.** Alternatives Considered: Posting writes the
   transaction, the category balance and the account as a single unit of work. A
   saga was evaluated and rejected because it replaces one atomic commit with
   committed steps plus compensating reversals, making intermediate states
   **observable that do not exist in the baseline** — a posted transaction with an
   unposted balance, for one. Trade-offs: a documented, deliberate exception to
   strict schema-per-service isolation, held to write grants on only the two
   schemas the unit of work touches.

6. **The runtime base image is pinned to the headless Amazon Linux variant.**
   Assumptions: The publisher ships **no Alpine variant** of that image, so an
   intuitive `-alpine` tag is not a smaller alternative — it resolves to nothing
   and fails every image build. The comment exists because the absence is
   invisible from the Dockerfile.

7. **The TypeScript compiler is pinned below the newest major release.**
   Trade-offs: The type-aware lint toolchain that runs the JSDoc gate declares
   an upper bound on the compiler version. Crossing it does not fail loudly; it
   **stops the documentation gate from running**. Compile speed is traded for a gate
   that actually executes.

8. **Routing depends on the core router package, and the companion `-dom` package
   is absent on purpose.** Alternatives Considered: The companion package has
   no release at the current major version and is a thin shim over an older one, so
   depending on it would **silently pin routing a major version behind** while
   looking like the conventional choice. Every screen and the router module import
   from the core package directly.

9. **No resilience library is declared, and no first-party class uses one.**
   Alternatives Considered: the application framework's own core retry support
   covers the need, so an external library would add a dependency that duplicates
   it. Assumptions: the claim is about declaration and use rather than about the
   classpath, and the distinction is part of what makes this a worked example —
   the older retry project arrives as a compile-scoped transitive of the queue
   starter in four modules and cannot be excluded without breaking that
   integration's own polling back-off, so an unqualified "added at all" would be
   a documented decision that a dependency report contradicts. An architecture
   rule forbids first-party code from depending on it, which is what turns the
   narrower claim into something a build can check. Trade-offs: a
   circuit breaker is separately and deliberately omitted: the only synchronous
   service-to-service hops are inside the private network behind an internal load
   balancer with bounded connect and read timeouts, so a breaker would **add a
   failure mode, an open circuit, without removing one**. The durable retry tier
   comes from queue redelivery with a dead-letter queue and from per-state
   orchestration retry.


## Enforcement summary

The table has four columns on purpose. **"Machine-checkable subset"** is what a tool
decides; **"Human-review obligation"** is what remains and is required all the same.
No row has an empty review column, because no tool in any of these languages
decides whether prose is true.

| Language | Required form | Machine-checkable subset | Human-review obligation | Status |
|---|---|---|---|---|
| Java | Javadoc on every class, every method at every visibility, every module entry point; `@param`, `@return`, `@throws` | presence at **every** visibility including private, with the default `Override` exemption cleared; at-clause coverage and non-empty bodies at all visibilities — `config/checkstyle/checkstyle.xml` + `config/checkstyle/suppressions.xml`, bound to the Maven `validate` phase in `services/pom.xml` and covering test sources | accuracy of the prose; `WHY` comments present, specific and on the non-obvious line | **live** locally and in `.github/workflows/services-ci.yml` |
| TypeScript | JSDoc/TSDoc on every function, class and module entry point, exported or not; `@param`, `@returns`, `@throws` | docstring presence and tag coverage on the selected declaration contexts, including function and arrow **expressions** in every position — `eslint-plugin-jsdoc` rules in `ui/eslint.config.js`, run at `--max-warnings=0` | accuracy and `WHY` quality; keeping the rule contexts un-narrowed and free of suppression comments | **live** through `npm run lint` and `.github/workflows/ui-ci.yml` |
| Python | module, class and function docstrings; Args / Returns / Raises | docstring **presence** and formatting only, in two halves: formatting everywhere plus presence on **public** declarations — pydocstyle `D` family under `[tool.ruff.lint]` in `data-migration/pyproject.toml`; presence at **every** visibility and **every** nesting depth — `data-migration/tests/test_docstring_gate.py` | **Args / Returns / Raises completeness** (no `D` rule checks a docstring against a signature, and a presence walker cannot judge content), accuracy, `WHY` quality | **live** locally and in `.github/workflows/services-ci.yml` |
| HCL | file header, `description` on every `variable` and `output`, why-comment per non-obvious argument | `description` presence and the declared file set — `infra/.tflint.hcl`; generated-table freshness — `infra/.terraform-docs.yml` | the file-header block and every why-comment; the prose in all sixteen module READMEs, both environment READMEs, and the bootstrap README | **live** through TFLint and terraform-docs checks in `.github/workflows/infra-ci.yml` |
| SQL | header block, why-comment per non-obvious constraint or index | **none** | the whole obligation | review only |
| Dockerfile | header, justification on the base-image pin and layer ordering | that the base-image pin **resolves**, and that the image builds at all — the eight service images are built by the `java-services` job under a count assertion, so a broken pin or a failed build fails the run | the header and every justification, including the pin's | **live** for the eight service Dockerfiles through `.github/workflows/services-ci.yml`; review only for `data-migration/Dockerfile` and `ui/Dockerfile`, which no workflow builds |
| YAML | header, justification on job ordering and caching, least-privilege `permissions`, SHA-pinned actions | **none for the documentation semantics.** GitHub itself rejects a syntactically invalid workflow at dispatch, and a mistyped `uses:` reference fails the step that runs it — but neither is a gate this repository configures, and NO linter, action-pin checker or policy scanner runs over `.github/**` (measured: no `actionlint`, `zizmor`, `ratchet` or equivalent appears anywhere under `.github/`) | the header and every rationale; least-privilege review of each `permissions` block; and confirming by inspection that every `uses:` carries a 40-character commit SHA rather than a moving tag | review only — the four migration workflows exist and each carries the required header, `permissions` block and SHA pins, but nothing mechanically enforces that they keep doing so |

**What the gates in that table do NOT check.** Assumptions: every gate above is a
presence-and-shape check, and none of them reads prose. Specifically:

* No gate anywhere verifies that a rationale label is written in the one canonical
  form. Checkstyle, ESLint, Ruff, TFLint and `terraform fmt` all pass on a
  singular, parenthesised or emphasis-wrapped label.
* No gate anywhere distinguishes a rationale that names a consequence from one that
  says "for performance". Rule 1's fourth forbidden pattern is unenforceable
  mechanically.
* No gate anywhere detects a statement-level `WHAT:` comment restating its own
  statement.
* SQL, Dockerfile and YAML have no documentation linter at all; their obligation
  rests on the header convention plus review.

Trade-offs: a green pipeline is therefore evidence about the gates' coverage and
**not** evidence of Rule 1 compliance, and this table must never be cited as
though it were the latter. The residual surface is closed by review against this
standard, which is why the Precedence note at the head of this document makes the
rule authoritative over the configuration rather than the reverse.

The existing COBOL suite's pipeline,
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml), is **unchanged**
and appears in this table nowhere. It keeps its own workflow, and the gates above
are wired into new workflows beside it.

Two boundaries are worth stating plainly, because a table of gates can read as a
claim about a running system.

**The infrastructure boundary.** All sixteen modules, both environment roots,
and the state bootstrap are authored and statically validated. Formatting,
initialisation without a backend, validation, recursive lint, generated-document
drift, and policy checks are build gates. Applying the configuration to a live
AWS account remains an operator action outside this scope, so static success is
not represented as evidence that a provisioned environment exists.

**The gate-coverage boundary.** Java, TypeScript/JavaScript, Python and HCL have
build-failing machine checks for docstring presence. YAML does **not**: no linter,
action-pin checker or policy scanner runs over `.github/**` in this repository, so
workflow rationale, `permissions` scoping and SHA pinning are review obligations —
what the workflows gate is the code they run, not their own shape. SQL rationale,
Dockerfile rationale beyond the image build, and every language's semantic `WHY`
quality also require review. No machine gate is represented as proving the parts of
Rule 1 it cannot read.

Refactoring Rationale: an earlier wording of this paragraph listed "workflow syntax"
among the languages with build-failing machine checks, and the YAML row of the table
above claimed "workflow syntax and pinned-action validation" with a status of
**live**. Neither was true, and this is the one direction of inaccuracy a
gate-coverage summary must not point: a reader auditing Rule 1 would have counted
YAML as mechanically covered and stopped reading it, which is exactly how an
unpinned action or a widened `permissions` block reaches the default branch. The
absence was measured — no `actionlint`, `zizmor`, `ratchet` or equivalent appears
anywhere under `.github/` — and is now stated as an absence in both places.


## Conflicts

**None.** Rule 1 names the same four justification categories that
[`tests/README.md`](../tests/README.md) §12 already names, and requires the same
docstring elements that section already requires of every test, fixture builder,
helper, mock and runner routine. This standard therefore generalises an
established house convention to the new polyglot tree rather than imposing a
competing one, and no clause of it had to be traded against another requirement.

Where a future linter configuration, this document and Rule 1 disagree, **Rule 1
and the Agent Action Plan decide**, and the configuration or this document —
whichever fell short — is corrected upward to meet the rule. A gate is evidence
of compliance, never the definition of it; if a configuration cannot express a
clause, the clause still stands and review enforces the remainder.
