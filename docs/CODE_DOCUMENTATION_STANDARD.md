# Code Documentation Standard

> **Purpose.** This document is the single normative documentation convention for
> every newly-authored file in the CardDemo migration trees — `services/**`,
> `ui/**`, `data-migration/**`, `infra/**`, every Dockerfile, every SQL migration
> and every new CI workflow. It states the obligation once per language and gives
> a conforming example for each, so that "document the why" is a checkable
> requirement rather than an aspiration.
>
> **Source of truth.** Two sources, and no others. The first is the project's
> single user-specified rule, **Rule 1 "Explainability"**, whose four sections are
> reproduced verbatim below. The second is the convention this repository already
> applies to its own test suite in [`tests/README.md`](../tests/README.md) §12
> (L544–L549), which imposes an identical obligation naming the same four
> justification categories. This standard extends that established house
> convention to a polyglot tree; it does not invent a new one.
>
> **Precedence.** `tests/README.md` opens by stating that where it and a runner
> script disagree, *the script* is authoritative. The same principle governs here:
> where this standard and a linter configuration disagree, **the linter
> configuration is authoritative**, and this document is the thing that gets
> fixed. Every gate named below was read from the configuration that implements
> it, not assumed.


## Why this document exists

This is the one artifact in `docs/` whose existence is owed to a user-specified
rule rather than to any migration requirement. Nothing in the work of moving
COBOL programs to services would produce it. It exists so that Rule 1's closing
sentence — code missing its documentation fails review — is enforced by a build
step in every language of the new tree, instead of resting on a reviewer's
memory.

Its job is narrow and complete: state the obligation once for every language in
the new tree, give a conforming example per language, and name the mechanical
gate that checks it.

Four other artifacts depend on this document and link to this exact path, so the
filename is part of its contract:

* [`CONTRIBUTING.md`](../CONTRIBUTING.md) carries a code-documentation section
  that points here for the full convention.
* [`README.md`](../README.md) and `MIGRATION_README.md` both reference this path
  from their migration sections.
* `.gitignore` is written so that this file can never be ignored — it is tracked,
  always.

Because those references are literal, the filename is `CODE_DOCUMENTATION_STANDARD.md`
at the `docs/` root. A single character of drift breaks four links.


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

Where a migrated implementation deliberately behaves differently from the
baseline, that divergence is registered in
`docs/architecture/cobol-to-service-traceability.md`. This standard's only
interest in such a divergence is that it must be documented at the point of
implementation under one of the four categories below.


## The rule

Exactly **one** user-specified rule governs this project. The four sections that
follow are Rule 1 reproduced verbatim, with its own section names preserved so
the mapping from standard to rule is one-to-one and auditable. Rule 1's headline
reads:

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

**Alternatives Considered — the examples named in the format clause.** Rule 1's
own clause offers one example docstring format per language, and one of the
languages it names is used nowhere in this repository; nothing in the migration
introduces it. The clause is reproduced above with that single inapplicable
example dropped and every example that applies retained. Keeping it would have
created a per-language section governing zero files, which is worse than silence:
it invites a future reader to conclude that a component in that language exists
somewhere in the tree, and to go looking for it.

**Alternatives Considered — extending the obligation to languages that have no
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

These four category names are spelled exactly as above everywhere they appear:
**Alternatives Considered**, **Refactoring Rationale**, **Assumptions**,
**Trade-offs**.

**Singular and plural forms are the same four categories.** In-code comment
blocks abbreviate them to the singular — `Assumption:` and `Trade-off:` — and
that abbreviation is already established in this repository: the header block of
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml) tags each of its
design-decision bullets that way (L19–L43). Both forms are correct and they
denote one convention. Nothing turns on which is used; the requirement is that a
category is named at all.


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

Seven languages are covered, in this order: **Java, TypeScript, Python, HCL, SQL,
Dockerfile, YAML**. Three of them have a docstring construct and the rule applies
directly. Four have none, and carry the analogous obligation established above.
Two shorter notes close the section — shell entry points, and Markdown itself.

**Assumptions — why seven and not more.** The count is exactly the set of
languages in which this migration authors files. Every language named here
governs real files in `services/**`, `ui/**`, `data-migration/**`, `infra/**` or
`.github/workflows/**`; no language is listed that governs none. That is the whole
selection criterion, and it is worth stating because a documentation standard that
lists aspirational languages teaches readers to skim it.


### Java

**Required form.** Javadoc on every class, every public and package-private
method, and every module entry point. `@param` for each parameter, `@return`
where a value is returned, `@throws` for each exception the caller must handle.
Trivial accessors may use the single-line form.

**Mechanical gate.** [`config/checkstyle/checkstyle.xml`](../config/checkstyle/checkstyle.xml)
with its companion `config/checkstyle/suppressions.xml`, driven by
`maven-checkstyle-plugin` and bound to the Maven **`validate`** phase in
[`services/pom.xml`](../services/pom.xml) under the execution id
`checkstyle-documentation-gate`. Binding to `validate` rather than to a
verification phase is deliberate: the gate runs on **every local build**, before
compilation, so a missing Javadoc surfaces on the developer's machine rather than
in CI.

```java
/**
 * Computes one month of interest for a transaction-category balance.
 *
 * <p>The product is formed at full precision before the division, mirroring the
 * order the baseline COBOL uses. WHY (Assumptions): the arithmetic order is part
 * of the behavioural contract, not an implementation detail — dividing first and
 * multiplying second yields a different final cent on many balances, and the
 * golden-master comparison would flag it as a parity failure.
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

* **Lombok is not used. Alternatives Considered:** generated accessors and
  constructors cannot carry the Javadoc this standard requires, so a Lombok-built
  class either fails the gate or is suppressed out of it. Java `record` types with
  explicit constructors give the same brevity with members that can be documented.
* **MapStruct is not used. Alternatives Considered:** copybook-to-DTO mapping is
  not mechanical. It drops `FILLER`, masks the primary account number to its last
  four digits, suppresses the card verification value entirely, encrypts the
  national and government-issued identifiers, and renames three misspelled
  baseline fields. Each of those needs a justification at the mapping site, and a
  generated mapper has nowhere to hold one.


### TypeScript

**Required form.** JSDoc or TSDoc on every exported component and every exported
function, with `@param`, `@returns` and `@throws` as applicable. Internal helpers
that are not exported are covered by the inline-comment requirement rather than by
the docstring requirement.

**Mechanical gate.** `eslint-plugin-jsdoc` rules configured in
`ui/eslint.config.js`, run by the `lint` script in
[`ui/package.json`](../ui/package.json).

```tsx
/**
 * Renders the persistent function-key bar.
 *
 * Actions are bound to real keyboard events as well as buttons. WHY
 * (Assumptions): the 3270 original was keyboard-only, so keyboard operation is a
 * fidelity requirement — rendering buttons alone would take the existing
 * workflow away from users who already have it.
 *
 * @param props.actions the enabled key actions for the current screen
 * @param props.onInvoke called with the action a user triggered, by key or click
 * @returns the key bar element
 */
export function PfKeyBar({ actions, onInvoke }: PfKeyBarProps): JSX.Element {
  ...
}
```

**Trade-offs — the compiler version is constrained by this gate.** The TypeScript
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

**Mechanical gate.** The pydocstyle **`D`** rule family, selected under
`[tool.ruff.lint]` in
[`data-migration/pyproject.toml`](../data-migration/pyproject.toml). That single
`select` entry is the entire mechanical enforcement of this standard for Python;
narrowing it, or letting a convention setting switch off its presence checks,
switches off a review gate for a whole language rather than tidying a lint
configuration.

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
    # WHY (Assumptions): the sign lives in the high-order nibble of the final
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

**Mechanical gate.** `infra/.tflint.hcl` for lint, and
`infra/.terraform-docs.yml` for generated module documentation whose freshness is
drift-checked in CI — a module whose generated documentation no longer matches its
variables fails the check. The prose half of the obligation is a `README.md` in
**each of the sixteen `infra/modules/*` directories** and in **both
`infra/envs/*` roots**.

```hcl
# =============================================================================
# infra/modules/sqs/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Request and reply queues replacing the baseline message-queue pairs, each
#   with a dead-letter queue and encryption at rest.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: a standard queue was rejected for the
#     authorization pair. Ordering per card is observable behaviour in the
#     baseline, and only a FIFO queue preserves it.
# =============================================================================

variable "max_receive_count" {
  description = "Deliveries attempted before a message is moved to the dead-letter queue."
  type        = number
}

resource "aws_sqs_queue" "request" {
  # WHY (Assumptions): the group identifier is the card number because ordering
  # is required per card and not globally. Using a constant group would serialise
  # every card behind one another and collapse throughput to a single consumer.
  fifo_queue = true
}
```


### SQL

**Required form**, as the analogous obligation: *a header block plus a why-comment
on each non-obvious constraint or index.* This applies to
`services/*/src/main/resources/db/migration/**` and to `data-migration/sql/**`.

**Mechanical gate.** Review, backed by the header convention above. HCL has a
linter and SQL does not, so this is the one place in the standard where the
obligation rests on the header block being present and on the review gate in
Rule 1's closing sentence. Two canonical cases must always carry their reasoning,
because both look like ordinary schema noise and both encode a preserved
behavioural contract:

* the `ON DELETE RESTRICT` foreign key from transaction categories to
  transaction types, which preserves the legacy `XTRNTYCAT` semantic so that
  deleting a referenced type is refused rather than cascading; and
* the descending fraud index, whose column order matches the legacy `XAUTHFRD`
  index so that the most recent authorization for a card is still the first row
  read.

```sql
-- =============================================================================
-- V1__reference.sql
-- -----------------------------------------------------------------------------
-- Purpose: reference-data tables for transaction types and categories.
-- =============================================================================

ALTER TABLE reference.transaction_categories
  ADD CONSTRAINT fk_category_type
  FOREIGN KEY (type_cd) REFERENCES reference.transaction_types (type_cd)
  -- WHY (Assumptions): RESTRICT, not CASCADE. The legacy XTRNTYCAT relationship
  -- refuses the delete of a referenced type, and the screen that offers the
  -- delete surfaces that refusal to the user. CASCADE would silently remove the
  -- dependent categories instead, which is a different observable outcome.
  ON DELETE RESTRICT;
```


### Dockerfile

**Required form**, as the analogous obligation: *a header plus a justification on
the base-image pin and on layer ordering.*

**Mechanical gate.** Review, plus the image build itself: a pin that does not
exist fails the build, which is exactly why the pin carries its reasoning.

```dockerfile
# =============================================================================
# services/auth-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose: build and package the auth service as a non-root container image.
#
# WHY (non-obvious design decisions):
#   - Assumption: the runtime tag is the headless Amazon Linux variant. The
#     publisher ships no Alpine variant of this image, so the intuitive
#     `-alpine` suffix is not a smaller alternative — it is a tag that does not
#     resolve, and it fails every build.
# =============================================================================

# WHY (Trade-offs): dependencies are resolved in their own layer before the
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

**Mechanical gate.** Review, plus the workflows' own required steps. The header
template is the one already established by
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml) (L1–L51) and is
reproduced verbatim in shape:

```yaml
# =============================================================================
# .github/workflows/services-ci.yml
# -----------------------------------------------------------------------------
# Purpose:
#   <what this file is for, wrapped>
#
# WHY (non-obvious design decisions):
#   - Assumption: <...>
#   - Trade-off: <...>
#   - Trade-off: <...>
# =============================================================================

# WHY: <justification placed directly above the key it explains>
on:
  push:
    branches: [main]
```

Note the two comment positions in that template, both of which the existing
workflow uses: the framed block at the top of the file, and a bare `# WHY:` line
placed **directly above** the key it explains.


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

This document complies with its own requirement. Its header names Rule 1 and
`tests/README.md` §12 as its two sources, and each non-obvious choice it makes
about itself is justified where the choice is made: dropping the one inapplicable
example from the format clause (**Alternatives Considered**), covering exactly
seven languages (**Assumptions**), making the analogous obligations normative
rather than advisory (**Alternatives Considered**), and using one consistent ASCII
spelling of the category names even where a quoted source typesets a hyphen
differently (**Assumptions** — a downstream search for a category name has to
match every occurrence).


## The `# WHAT:` / `# WHY :` idiom

Fenced command blocks — in this document, in every new `README.md`, and in
`docs/runbooks/**` — annotate themselves with two labels:

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
place or the other, and it is the same form used for the single-line
justifications placed directly above a YAML key. Prescribe the aligned
`# WHAT:` / `# WHY :` pair for fenced command blocks in the new trees; the bare
`# WHY:` remains correct for a one-line justification attached to a single
statement.

Inside a block, tag the justification with a category when the reason is a
decision rather than a mechanic — for example `# WHY : Trade-offs: ...`. The
category names are the four from the rule, in either their plural or singular
form.


## Worked examples: what a good "why" looks like

Rule 1 is hardest to satisfy honestly where a decision looks arbitrary, and
easiest to fail by writing a rationale that names no consequence. Each entry
below is a decision this migration actually makes, tagged with the category it
falls under and stating specifically what differs under the alternative. These
are the reference standard for the level of detail expected at the point of use;
each one belongs in the code that implements it, not only here.

1. **Interest is computed multiply-before-divide.** *(Assumptions.)* The baseline
   forms the product of balance and rate before dividing. The migrated code
   multiplies at full precision and only then divides with an explicit scale and
   rounding mode. Dividing first and multiplying second yields a **different final
   cent** on many balances, so the arithmetic order is part of the behavioural
   contract rather than a style choice.

2. **EBCDIC is decoded per field, never per record.** *(Assumptions.)* Decoding
   happens at exactly one boundary, in binary mode, one fixed-width field at a
   time. Decoding a whole record passes sign-overpunch bytes, packed nibbles and
   embedded low values through a text decoder, which replaces them. The result is
   data that **looks almost right** — the failure is silent, which is what makes
   this worth a comment at the decode site.

3. **Money crosses the wire as a JSON string, never a JSON number.**
   *(Assumptions, Trade-offs.)* Most clients parse a JSON number into an
   IEEE-754 double, which destroys exactness at the boundary the user actually
   reads. Money is exact fixed point at every hop instead: a fixed-scale numeric
   column, a scale-2 decimal in Java, a `Decimal` in Python, a string in JSON.
   `float` and `double` are forbidden in the money path and the prohibition is
   architecture-tested. The trade-off accepted is that clients must parse the
   string themselves.

4. **Lists page by key, not by offset.** *(Alternatives Considered.)* Offset
   pagination was evaluated and rejected: under concurrent inserts it **skips and
   repeats rows**, which changes behaviour that the baseline's browse-by-key does
   not have. Keyset pagination over the same key columns reproduces the original
   page boundaries.

5. **Transaction posting keeps one atomic commit, via a narrowly-scoped
   cross-schema grant.** *(Alternatives Considered, Trade-offs.)* Posting writes
   the transaction, the category balance and the account as a single unit of work.
   A saga was considered and rejected because it replaces one atomic commit with
   committed steps plus compensating reversals, making intermediate states
   **observable that do not exist in the baseline** — a posted transaction with an
   unposted balance, for one. The trade-off accepted is a documented, deliberate
   exception to strict schema-per-service isolation, held to write grants on only
   the two schemas the unit of work touches.

6. **The runtime base image is pinned to the headless Amazon Linux variant.**
   *(Assumptions.)* The publisher ships **no Alpine variant** of that image, so an
   intuitive `-alpine` tag is not a smaller alternative — it resolves to nothing
   and fails every image build. The comment exists because the absence is
   invisible from the Dockerfile.

7. **The TypeScript compiler is pinned below the newest major release.**
   *(Trade-offs.)* The type-aware lint toolchain that runs the JSDoc gate declares
   an upper bound on the compiler version. Crossing it does not fail loudly; it
   **stops the documentation gate from running**. Compile speed is traded for a gate
   that actually executes.

8. **Routing depends on the core router package, and the companion `-dom` package
   is absent on purpose.** *(Alternatives Considered.)* The companion package has
   no release at the current major version and is a thin shim over an older one, so
   depending on it would **silently pin routing a major version behind** while
   looking like the conventional choice. Every screen and the router module import
   from the core package directly.

9. **No resilience library is added at all.** *(Alternatives Considered,
   Trade-offs.)* The application framework's own core retry support covers the
   need, so an external library would add a dependency that duplicates it. A
   circuit breaker is separately and deliberately omitted: the only synchronous
   service-to-service hops are inside the private network behind an internal load
   balancer with bounded connect and read timeouts, so a breaker would **add a
   failure mode — an open circuit — without removing one**. The durable retry tier
   comes from queue redelivery with a dead-letter queue and from per-state
   orchestration retry.


## Enforcement summary

| Language | Required form | Gate configuration | Where it runs |
|---|---|---|---|
| Java | Javadoc; `@param`, `@return`, `@throws` | `config/checkstyle/checkstyle.xml` + `config/checkstyle/suppressions.xml`, bound to the Maven `validate` phase in `services/pom.xml` | every local build, and the documentation-gate step in `.github/workflows/services-ci.yml` |
| TypeScript | JSDoc/TSDoc on exported components and functions | `eslint-plugin-jsdoc` rules in `ui/eslint.config.js` | the lint step in `.github/workflows/ui-ci.yml` |
| Python | module, class and function docstrings; Args / Returns / Raises | pydocstyle `D` family under `[tool.ruff.lint]` in `data-migration/pyproject.toml` | the lint step in `.github/workflows/services-ci.yml` |
| HCL | file header, `description` on every `variable` and `output`, why-comment per non-obvious argument | `infra/.tflint.hcl`, `infra/.terraform-docs.yml`, plus a `README.md` in each of the sixteen `infra/modules/*` directories and both `infra/envs/*` roots | `.github/workflows/infra-ci.yml`, including the generated-documentation drift check |
| SQL | header block, why-comment per non-obvious constraint or index | header convention; review gate | review |
| Dockerfile | header, justification on the base-image pin and layer ordering | header convention; the image build itself rejects an unresolvable pin | review, plus the image build in `.github/workflows/services-ci.yml` |
| YAML | header, justification on job ordering and caching, least-privilege `permissions`, SHA-pinned actions | header convention; review gate | review |

The existing COBOL suite's pipeline,
[`.github/workflows/tests.yml`](../.github/workflows/tests.yml), is **unchanged**
and appears in this table nowhere. It keeps its own workflow, and the gates above
are wired into new workflows beside it.

One boundary is worth stating plainly, because a table of gates can read as a
claim about a running system: the infrastructure code is authored and statically
validated — formatting, validation, plan, lint and policy scan. Applying it to a
live account is an operator action outside this scope, so nothing here asserts
that a provisioned environment exists.


## Conflicts

**None.** Rule 1 names the same four justification categories that
[`tests/README.md`](../tests/README.md) §12 already names, and requires the same
docstring elements that section already requires of every test, fixture builder,
helper, mock and runner routine. This standard therefore generalises an
established house convention to the new polyglot tree rather than imposing a
competing one, and no clause of it had to be traded against another requirement.

Where a future linter configuration and this document disagree, the configuration
is authoritative and this document is what gets corrected.
