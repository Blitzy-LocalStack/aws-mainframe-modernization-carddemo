# Rule 1 lexical gate

`rule1_gate.py` is the repository-wide, fail-closed gate for the three parts of
Rule 1 "Explainability" that no per-language linter in this repository decides.
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
remains the authority on the rule itself; this directory holds the mechanism, and
where the two disagree the standard wins and the mechanism is corrected upward.

## What it checks

| Check | Decides | Fails on |
|---|---|---|
| `labels` | that every rationale label is written in its one permitted form | a parenthesised, `WHY (...)`-parenthesised, emphasis-wrapped, emphasis-wrapped-with-the-colon-dropped, or singular label — including one a hard wrap split across two lines |
| `what` | that a `WHAT:` comment appears only in a file's leading header block | a `WHAT:` comment above a statement, in any governed code or configuration file |
| `consistency` | that each gate the standard calls live is present in the file the standard names for it | a claim whose mechanism has been deleted, renamed, or never wired |
| `self-test` | that the two detectors still fire and still stay silent | a detector that has stopped detecting, which would otherwise report a vacuous pass |

The four permitted labels are `Assumptions:`, `Alternatives Considered:`,
`Refactoring Rationale:` and `Trade-offs:`.

## Running it

```bash
# WHAT: run every check over the whole repository, from the repository root.
# WHY : Assumptions: the gate resolves its own root from this file's location, so
#       it needs no argument and cannot be pointed at a subtree by accident; a
#       partial scan that reported PASS would be the one failure mode worth
#       preventing here. Pass --root only to scan an exported copy of the tree.
python config/rule1/rule1_gate.py

# WHAT: run one check, for a faster loop while fixing a specific class of finding.
python config/rule1/rule1_gate.py --check labels
python config/rule1/rule1_gate.py --check what
python config/rule1/rule1_gate.py --check consistency
python config/rule1/rule1_gate.py --check self-test
```

Exit status is `0` only when every selected check passed. There is no tolerance
flag, no `--fix` and no allow-list argument: a finding is fixed in the file that
carries it.

The gate imports nothing outside the standard library, so it runs on any Python
3.13 interpreter with no installed dependencies. It is linted and formatted with
the repository's own Ruff configuration:

```bash
# WHAT: lint and format-check this directory against the house Python gate.
# WHY : Assumptions: data-migration/pyproject.toml is reused rather than a second
#       configuration being added here, because two Ruff configurations in one
#       repository drift apart and then one of them is wrong about the house style.
ruff check --config data-migration/pyproject.toml config/rule1
ruff format --check --config data-migration/pyproject.toml config/rule1
```

## What it deliberately does not read

* **`app/`, `tests/`, `scripts/`, `samples/` and `.github/workflows/tests.yml`.**
  These are the reference-only COBOL baseline, its parity-oracle suite, that
  suite's runners, the mainframe build samples and the oracle's own pipeline. They
  predate Rule 1 and must stay byte-identical, so editing them to satisfy this
  gate is not available. `tests.yml` is excluded by exact path, not by prefix: the
  other three workflows beside it **are** governed.
* **`CONTRIBUTING.md` and `docs/CODE_DOCUMENTATION_STANDARD.md`.** Both quote
  Rule 1's own text, which carries the rule's typography and names a prohibited
  form so a reader can recognise it. Each states in adjacent prose that the
  emphasis is the document's typography rather than part of a label, so the
  occurrences are definitionally not labels.
* **Build output and dependency closures** — `target/`, `dist/`, `build/`,
  `node_modules/`, `.venv/`, `.terraform/`, `__pycache__/` and the tool caches.
* **Markdown, for the `what` check only.** The aligned `# WHAT:` / `# WHY :` pair
  is correct inside a fenced command block in prose: a shell pipeline has no
  docstring construct to carry its purpose.

## What it cannot decide

A lexical gate decides form, never truth. It cannot tell a rationale that names a
consequence from one that says "for performance", it cannot tell whether a
docstring describes what the function does, and it cannot tell whether an
inventory in a comment still matches the tree. Those remain review obligations,
enumerated per language in the standard's enforcement summary. A green run of this
gate is evidence about form and must never be cited as evidence of Rule 1
compliance.

## Where it runs

As a required step in
[`services-ci.yml`](../../.github/workflows/services-ci.yml),
[`ui-ci.yml`](../../.github/workflows/ui-ci.yml) and
[`infra-ci.yml`](../../.github/workflows/infra-ci.yml). All three run the whole
tree rather than the paths that changed, because a label form is a repository-wide
property and a path-scoped run would let a prohibited form reach the default
branch through a workflow whose filter did not name its file.
