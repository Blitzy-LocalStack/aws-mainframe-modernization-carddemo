# Contributing Guidelines

Thank you for your interest in contributing to our project. Whether it's a bug report, new feature, correction, or additional
documentation, we greatly value feedback and contributions from our community.

Please read through this document before submitting any issues or pull requests to ensure we have all the necessary
information to effectively respond to your bug report or contribution.


## Reporting Bugs/Feature Requests

We welcome you to use the GitHub issue tracker to report bugs or suggest features.

When filing an issue, please check existing open, or recently closed, issues to make sure somebody else hasn't already
reported the issue. Please try to include as much information as you can. Details like these are incredibly useful:

* A reproducible test case or series of steps
* The version of our code being used
* Any modifications you've made relevant to the bug
* Anything unusual about your environment or deployment


## Contributing via Pull Requests
Contributions via pull requests are much appreciated. Before sending us a pull request, please ensure that:

1. You are working against the latest source on the *main* branch.
2. You check existing open, and recently merged, pull requests to make sure someone else hasn't addressed the problem already.
3. You open an issue to discuss any significant work - we would hate for your time to be wasted.

To send us a pull request, please:

1. Fork the repository.
2. Modify the source; please focus on the specific change you are contributing. If you also reformat all the code, it will be hard for us to focus on your change.
3. Ensure local tests pass.
4. Commit to your fork using clear commit messages.
5. Send us a pull request, answering any default questions in the pull request interface.
6. Pay attention to any automated CI failures reported in the pull request, and stay involved in the conversation.
7. Ensure any new or modified code satisfies the documentation requirements in *Code documentation and explainability* below.

GitHub provides additional document on [forking a repository](https://help.github.com/articles/fork-a-repo/) and
[creating a pull request](https://help.github.com/articles/creating-a-pull-request/).


## Code documentation and explainability

Every contribution to the migrated code in this repository must document both *what* the code does and *why* particular implementation decisions were made. This is a hard review gate, not a preference. Code missing either its required docstring (or analogous file header) or its adjacent rationale fails review, and the mechanical checks listed further down fail the build.

The authoritative, per-language form of this convention -- with a conforming example for each language in the tree -- is [docs/CODE_DOCUMENTATION_STANDARD.md](docs/CODE_DOCUMENTATION_STANDARD.md). This section states the obligation; that document is where the worked examples live. Read the two together, and read the linters as a floor rather than a ceiling: they implement only the subset of the convention a tool can decide, so passing them is necessary but never sufficient. Review still owns whether the prose is accurate and the rationale genuine, because no linter can determine whether a sentence is true.

The convention itself is not new. It generalises the rule the COBOL test suite already applies, recorded in [tests/README.md](tests/README.md), which asks for exactly the same docstring elements and exactly the same four rationale categories.

Alternatives Considered: writing a second, separate convention for the migrated trees. We extended the existing one instead, because two overlapping conventions would let a contributor or a reviewer satisfy whichever of them happened to be weaker, and a rule that can be satisfied by picking the weaker of two readings is not a gate. There is one convention here, and it now covers every language in the repository.

**Where it applies.** All newly authored code and configuration:

* `services/**` -- Java
* `ui/**` -- TypeScript and TSX
* `data-migration/**` -- Python
* `infra/**` -- Terraform/HCL
* SQL migrations under `services/*/src/main/resources/db/migration/**` and `data-migration/sql/**`
* every `Dockerfile`
* the migration workflows `.github/workflows/services-ci.yml`, `.github/workflows/ui-ci.yml`, `.github/workflows/infra-ci.yml` and `.github/workflows/deploy.yml`
* the migration documentation under `docs/**`

**Where it does not apply.** The COBOL baseline under `app/**` -- programs, copybooks, BMS mapsets, JCL, the CSD (CICS resource definitions), and the seed data -- is reference-only and is never modified, so no retrofitting of Javadoc-equivalent commentary into `app/cbl/*.cbl` is required -- or permitted. The convention likewise changes nothing under `tests/**` or `scripts/**`.

Assumptions: that baseline is the behavioural oracle against which functional parity is verified, and it has to stay byte-identical for the comparison to mean anything. It carries no documentation obligation because it is authoritative source material to be cited by path and line, not because it is deprecated -- which is also why a citation into `app/**` may safely use a line number while a citation into a sibling of the file you are editing should name a stable anchor instead. The existing test suite is excluded on the same reasoning: it already satisfies the equivalent house convention, keeps its pinned dependencies exactly as they are, and continues to run unchanged on its own pipeline, `.github/workflows/tests.yml`, so re-typing it under this section would risk the oracle for no gain in coverage.

**Docstrings and API comments.** Every new or modified function, class, and module entry point must carry a docstring -- expressed in the language's standard API-comment form where applicable -- containing all of these elements:

* **Purpose** -- what the function, class, or entry point does and why it exists
* **Parameters** -- the name, type, and description of each parameter
* **Return values** -- the type and description of what is returned
* **Exceptions or errors** -- anything that may be raised or reported, where applicable

Trivial accessors -- getters or setters with no logic -- may use a single-line docstring.

Use the language's standard format:

* Java uses Javadoc (`/** ... */`) with `@param`, `@return`, and `@throws` where applicable.
* TypeScript and TSX use JSDoc/TSDoc-compatible comments (`/** ... */`) with `@param` and `@returns`.
* Python uses module, class, and function docstrings with `Args:`, `Returns:`, and `Raises:` sections where applicable.

**Inline rationale.** Place comments adjacent to the code they explain, and use them to explain *why* a decision was made rather than *what* the code already says. Statement-level comments that merely narrate what the next line does do not satisfy the rule. Every non-obvious decision must document at least one of the same four categories the COBOL suite already uses:

* **Alternatives Considered** -- which other approaches were evaluated and why this one was chosen
* **Refactoring Rationale** -- when replacing code, what was wrong with the old approach
* **Assumptions** -- which external contracts, data formats, or behaviours the decision depends on
* **Trade-offs** -- which compromises were accepted and why

The bold in the four bullets above is this document's typography for naming the categories, and it is not part of a label's written form. When you actually tag a rationale, write the label character-for-character as one of these four and in no other form:

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

Four properties of that form are load-bearing, and each is a way the label has actually been written wrongly in this repository. It is **plural** where the rule writes it plural, so `Assumption:` and `Trade-off:` are not accepted abbreviations. It is **unparenthesised**, so `WHY (Assumptions):` and a bare `(Trade-offs)` are wrong -- the label opens the rationale itself. It **keeps its colon**, so `Assumptions.` names the category without reading as a label. And it carries **no emphasis markup**, so `**Assumptions:**` is wrong in Markdown exactly as it is in code. Assumptions: the label is read by a literal search before it is read by a person, because no linter parses prose in a Dockerfile, a `.tf` file or a SQL migration, so a cross-language audit has nothing but text search to work with; one spelling makes that search complete, while four spellings of one category make it silently partial. Never mix two forms inside one file. Using these words as ordinary English -- "the assumptions this rests on" -- is not a label and needs no special form.

**Purpose goes in the docstring, not on the statement.** The `# WHAT:` / `# WHY :` pair belongs to fenced command blocks in prose -- this file, every `README.md`, and `docs/runbooks/**` -- and nowhere else. No `.java`, `.ts`, `.tsx`, `.py`, `.tf`, `.hcl`, `.sql`, `.xml`, `.yml`, `.yaml`, `.json`, `.gitignore`, `.env.example`, `Dockerfile` or `pom.xml` may carry a statement-level `WHAT:` comment. A `WHAT:` inside a file-header or module-header comment block is the documented exception and stays: that header *is* the docstring analogue for a format that has none. Assumptions: a `WHAT:` line above a statement restates what the statement already says, which is the first forbidden pattern below; a shell command in a fenced block is the different case that earns the pair, having no docstring construct available and often being a pipeline whose effect is genuinely not evident from its tokens. In code and configuration, therefore, purpose lives in the Javadoc, JSDoc, docstring or header block, and an inline comment carries one canonical label, the reason, and what differs under the alternative.

**Formats without docstrings.** Where a language or file format has no docstring construct, provide the analogous information in its native comments or metadata:

* Each Terraform/HCL `.tf` file starts with a file-header block. Every `variable` and `output` has a `description`, non-obvious resource arguments have adjacent why-comments, and every module directory and environment root has a `README.md`.
* Each SQL migration starts with a header naming its purpose and source copybook or table. Adjacent why-comments explain every non-obvious constraint, check, or index, including the `ON DELETE RESTRICT` foreign key that preserves legacy referential behaviour and the descending-order index that matches the legacy fraud index.
* Every `Dockerfile` starts with a header and explains the base-image pin and layer-ordering decisions.
* Each migration CI workflow YAML file starts with a header and explains job ordering, caching strategy, the least-privilege `permissions` block, and why every action is pinned to an immutable commit SHA.
* Every newly authored shell entry point in an in-scope path starts with a header block describing its purpose, inputs, outputs, failure behaviour, and non-obvious choices.

**Forbidden patterns.** Review rejects all of the following:

* comments that restate the code, such as `increment counter` next to `counter++`
* docstrings that omit purpose, parameters, or return values
* a non-obvious implementation choice left undocumented when a reasonable alternative exists
* vague rationales such as `this is better` or `for performance` without specific justification

**Mechanical validation gates.** The prose requirement is enforced mechanically at five points:

1. Java uses `config/checkstyle/checkstyle.xml` and `config/checkstyle/suppressions.xml` through `services/pom.xml`, where Checkstyle is bound to Maven's `validate` phase. Trade-offs: binding the gate to `validate` rather than to a CI-only step costs a marginally slower local build, and buys a contributor seeing missing Javadoc before pushing rather than after a pipeline round trip.
2. TypeScript and TSX use `eslint-plugin-jsdoc` through `ui/eslint.config.js`. Its scope is **every declaration, not only exported ones**: the rule set matches `**/*.ts` and `**/*.tsx` and sets `jsdoc/require-jsdoc` with `publicOnly: false`, so a module-private helper, an inline `onClick`, a `.map` callback and a `useMemo` factory each owe a block, and `jsdoc/check-param-names` additionally rejects a block that has drifted from the signature it documents. Assumptions: the wider scope is stated here rather than paraphrased as "exported components and functions", because that narrower wording is what a contributor would test their code against — and it passes an undocumented module-private function and an undocumented anonymous callback that the configured gate rejects. A contributor who trusts the narrower description reads a clean local lint run as compliance and meets the real gate in CI. Rule 1 allows a trivial function a single-line docstring, so the cost of the wider scope is one line per closure rather than a paragraph; `ui/eslint.config.js` carries the full reasoning beside the setting itself.
3. Python's documentation gate has two halves, because one tool does not cover the requirement. The pydocstyle `D` family selected under `[tool.ruff.lint]` in `data-migration/pyproject.toml` checks the formatting of every docstring and requires one on every **public** module, class and function; `data-migration/tests/test_docstring_gate.py` then asserts presence on **every** declaration at any visibility and any nesting depth. Assumptions: `D101`, `D102`, `D103` and `D106` are public-declaration checks, so a privately named declaration, a public member of a privately named class, and anything declared inside a function body raise nothing — measured against the pinned ruff, not inferred from its rule table. Do not remove either half; each is the only enforcement for what the other cannot see.
4. Terraform/HCL uses `infra/.tflint.hcl` for documented variables and outputs and `infra/.terraform-docs.yml` for generated-documentation drift checks.
5. CI repeats the gates as required steps in `.github/workflows/services-ci.yml`, `.github/workflows/ui-ci.yml`, and `.github/workflows/infra-ci.yml`. The existing `.github/workflows/tests.yml` remains unchanged because the test suite already carries the equivalent convention.

**A test must not read the machine it runs on.** A test that resolves configuration — anything using `ApplicationContextRunner`, `WebApplicationContextRunner`, `ConfigDataApplicationContextInitializer`, or the shared `ProfileConfiguration` harness — must state which environment variables exist for it, including the case of none. Replace the environment rather than inheriting it:

```java
sources.replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(/* only what this case needs */)));
```

Assumptions: two properties of Spring's resolution make this necessary rather than merely tidy, and both are easy to reason past. First, only `SystemEnvironmentPropertySource` performs relaxed name matching, so a real `CARDDEMO_ENVIRONMENT` variable supplies `carddemo.environment` while the same name passed to `withPropertyValues` does **not** — it registers a plain map under the literal key. Second, that system source sits **ahead** of the documents a config-data import appends. So passing an environment-variable-shaped key to `withPropertyValues` is not equivalent to setting the variable: wherever the real variable is set, the machine's value wins and the assertion silently compares the shipped file against the developer's shell. The documented local runtime (`/opt/carddemo-tools/svc-common.env`, `svc-aws.env`) sets roughly thirty-four `CARDDEMO_*` names plus `SPRING_PROFILES_ACTIVE`, so this is the normal condition, not an edge case. Trade-offs: substituting the whole source rather than removing one variable costs a line of setup and buys immunity to every future variable, including one added to the runtime long after the test was written.

When a case asserts that something is **withheld** until a deployment names a value, an empty substituted environment is the only way to state that precondition — relying on the variable being unset makes the case pass or fail according to who runs it. Conversely, when a case supplies a value in order to be exercised, prefer the **canonical** property name in `withPropertyValues`; reach for the environment-variable form only when the relaxed mapping itself is what is under test, and then supply it through a substituted source so it is an input rather than an inheritance. Assumptions: making a substituted environment hermetic can also make an assertion toothless — if the substituted variable supplies the very property the shipped file was supposed to map, the file is no longer being consulted. Pair such a case with one that supplies **nothing**, where only the file can produce a value; that pairing is what detects a file that stops reading the variable.

Do not bump the TypeScript pin in `ui/package.json` without first checking the upper bound on the compiler version accepted by the type-aware lint toolchain. Trade-offs: the pin is deliberately held below the newest compiler release, giving up its build-speed improvement to keep gate 2 above running at all. Crossing that bound leaves the manifest installable while silently preventing the type-aware ESLint pass, and therefore the JSDoc gate, from running -- a documentation rule failing open with no error to read. Verify the compatible range and upgrade procedure in [the documentation standard](docs/CODE_DOCUMENTATION_STANDARD.md) before changing the pin.


## Finding contributions to work on
Looking at the existing issues is a great way to find something to contribute on. As our projects, by default, use the default GitHub issue labels (enhancement/bug/duplicate/help wanted/invalid/question/wontfix), looking at any 'help wanted' issues is a great place to start.


## Code of Conduct
This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct).
For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact
opensource-codeofconduct@amazon.com with any additional questions or comments.


## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.


## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.
