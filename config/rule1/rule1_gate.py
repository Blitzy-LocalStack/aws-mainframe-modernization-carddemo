#!/usr/bin/env python3
# =============================================================================
# config/rule1/rule1_gate.py
# -----------------------------------------------------------------------------
# WHAT: the repository-wide, fail-closed lexical gate for the two clauses of
#       Rule 1 "Explainability" that no language linter in this repository can
#       decide -- the one permitted written form of a rationale label, and the
#       prohibition on a statement-level WHAT comment -- plus a consistency
#       assertion holding docs/CODE_DOCUMENTATION_STANDARD.md to the gate
#       configuration it describes.
#
# WHY : Assumptions: Checkstyle, ESLint, Ruff, TFLint and terraform-docs are all
#       presence-and-shape checks over ONE language each, and none of them reads a
#       comment's wording. A parenthesised, singular or emphasis-wrapped label and
#       a statement-level WHAT therefore pass every configured gate, which is why
#       the standard's own enforcement summary listed both as review-only. This
#       module closes exactly those two, across every language at once, so the
#       property survives a reviewer who does not notice.
#       Trade-offs: a lexical gate decides FORM, never truth. It cannot tell a
#       rationale that names a consequence from one that says "for performance",
#       and no claim to the contrary is made here or in the standard.
#       Alternatives Considered: a per-language plugin for each of the five
#       linters. Rejected -- five configurations expressing one rule drift apart,
#       three of the seven governed languages (SQL, Dockerfile, YAML) have no
#       documentation linter to host a plugin at all, and the shared preprocessing
#       that makes the check correct (fenced-block masking, code-span masking,
#       soft-wrap joining) would have to be reimplemented five times.
# =============================================================================
"""Enforce Rule 1's canonical rationale labels and its statement-WHAT prohibition.

This module is executable and self-testing. ``--self-test`` proves the detectors
still fire on each prohibited form and stay silent on each permitted one, so a
regression that silently disables a check fails the run instead of reporting a
vacuous pass.

Checks, all fail-closed:

``labels``
    Every rationale label must be written in exactly one form -- ``Assumptions:``,
    ``Alternatives Considered:``, ``Refactoring Rationale:`` or ``Trade-offs:``.
    Parenthesised, emphasis-wrapped, colon-dropped and singular variants are
    rejected.

``what``
    A ``WHAT:`` comment is permitted only inside a file's leading header block. A
    statement-level one restates the statement it sits above, which is Rule 1's
    first forbidden pattern.

``consistency``
    Each gate that ``docs/CODE_DOCUMENTATION_STANDARD.md`` claims is live must be
    present in the configuration file the standard names for it.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path

# The four labels in their one permitted written form. Ordered longest-first so a
# scan for "Alternatives Considered" is never satisfied by a prefix of it.
CANONICAL_LABELS: tuple[str, ...] = (
    "Alternatives Considered",
    "Refactoring Rationale",
    "Assumptions",
    "Trade-offs",
)

# WHY : Assumptions: the two PROHIBITED tokens are assembled from fragments rather
#       than written out, and that is deliberate rather than fussy. A gate whose
#       own source carried a literal singular label or a literal statement-level
#       WHAT would either flag itself or need a self-exemption, and a gate that
#       exempts itself is a gate nobody can audit by running it over the tree.
#       Trade-offs: the fragments are marginally harder to read than the tokens,
#       in exchange for the gate being subject to its own rules with no exclusion.
_SINGULAR_STEMS: tuple[str, ...] = ("Assumption", "Trade-off")
_WHAT_TOKEN: str = "WHAT" + ":"

# Trees this gate does not read. app/, tests/, scripts/ and samples/ are the
# reference-only COBOL baseline, its parity-oracle suite, that suite's runners and
# the mainframe build samples: they predate Rule 1, must stay byte-identical, and
# are owned by .github/workflows/tests.yml. The remainder are build output,
# dependency closures and tool state, none of which is authored.
EXCLUDED_PREFIXES: tuple[str, ...] = (
    "app/",
    "tests/",
    "scripts/",
    "samples/",
    "blitzy/",
    "diagrams/",
    ".git/",
    ".venv/",
    "node_modules/",
    "target/",
    "dist/",
    "build/",
    ".terraform/",
    "__pycache__/",
    ".pytest_cache/",
    ".ruff_cache/",
)

# WHY : Assumptions: .github/workflows/tests.yml is the COBOL parity oracle's own
#       pipeline and is reference-only alongside the trees it drives, so it is
#       excluded by exact path rather than by prefix -- the other three workflows in
#       the same directory ARE governed. It predates Rule 1 and carries the singular
#       label form its own suite uses; editing it to satisfy this gate would modify a
#       reference artifact the migration is required to leave byte-identical.
REFERENCE_FILES: frozenset[str] = frozenset({".github/workflows/tests.yml"})

EXCLUDED_DIR_NAMES: frozenset[str] = frozenset(
    {
        ".git",
        ".venv",
        "node_modules",
        "target",
        "dist",
        "build",
        ".terraform",
        "__pycache__",
        ".pytest_cache",
        ".ruff_cache",
        ".mypy_cache",
    }
)

# WHY : Assumptions: these two files quote Rule 1's own text, and the quotation
#       carries the rule's typography -- bold category names, and a backtick-quoted
#       counter-example naming a prohibited form so a reader can recognise it. Both
#       files state in adjacent prose that the emphasis is the document's typography
#       and is not part of a label's written form, so the occurrences are
#       definitionally not labels.
#       Alternatives Considered: rewriting the quotations so no prohibited form
#       appears anywhere. Rejected -- a standard that cannot show the form it
#       prohibits cannot teach it, and a reader who has met the form in the wild
#       needs to find it here.
ALLOWLISTED_PATHS: frozenset[str] = frozenset(
    {
        "CONTRIBUTING.md",
        "docs/CODE_DOCUMENTATION_STANDARD.md",
    }
)

# Suffixes carrying authored prose or code that Rule 1 governs. Chosen by
# extension rather than by content sniffing so the governed set is legible in one
# glance and a new file type is an explicit decision.
LABEL_SUFFIXES: frozenset[str] = frozenset(
    {
        ".java",
        ".ts",
        ".tsx",
        ".js",
        ".mjs",
        ".cjs",
        ".py",
        ".tf",
        ".tfvars",
        ".hcl",
        ".sql",
        ".yml",
        ".yaml",
        ".toml",
        ".xml",
        ".sh",
        ".md",
        ".conf",
        ".properties",
    }
)

# The WHAT check governs code and configuration only. Markdown is excluded because
# the aligned WHAT/WHY pair IS correct there, inside a fenced command block: a
# shell pipeline has no docstring construct to carry its purpose.
WHAT_SUFFIXES: frozenset[str] = LABEL_SUFFIXES - {".md"}

# Filenames with no suffix that the checks still govern.
EXTENSIONLESS_NAMES: frozenset[str] = frozenset({"Dockerfile", "Makefile"})

# Comment openers, longest-first per language family, used to find the leading
# header block and to decide whether a line is a comment at all.
COMMENT_PREFIXES: tuple[str, ...] = ("///", "//", "/*", "*/", "*", "#", "--", "<!--")

_EMPHASIS = r"(?:\*\*|__|\*|_)"

# The canonical labels, tolerant of the line wrap that can fall between the two
# words of "Alternatives Considered".
_LABEL_ALTERNATION = "|".join(re.escape(label).replace(r"\ ", r"\s+") for label in CANONICAL_LABELS)

# WHY : Assumptions: the parenthesised, why-parenthesised and emphasised patterns
#       match the SINGULAR stems as well as the canonical labels, while the
#       colon-dropped pattern does not. A singular stem inside brackets or emphasis
#       is unambiguously an attempt at a label -- there is no ordinary prose reason
#       to write `(Assumption)` -- whereas a bracketed or emphasised singular word
#       with no colon after it is often just a sentence, and flagging that would
#       make the gate unusable in prose.
_ANY_FORM_ALTERNATION = "|".join(
    (_LABEL_ALTERNATION, *(re.escape(stem) for stem in _SINGULAR_STEMS))
)

# The first word of each label, used only when deciding whether a leading marker is
# a list bullet or the emphasis around a label. A hard wrap can split a two-word
# label, so the full alternation would not recognise the opening half.
_LABEL_OPENERS = "|".join(
    sorted({re.escape(label.split(" ", 1)[0]) for label in CANONICAL_LABELS}, reverse=True)
)


@dataclass(frozen=True)
class Violation:
    """One prohibited occurrence, located precisely enough to fix without searching.

    Args:
        path: Repository-relative path of the offending file.
        line: One-based line number of the offending line.
        kind: Short identifier of the prohibited form that matched.
        excerpt: The offending text, trimmed for a single-line report.
    """

    path: str
    line: int
    kind: str
    excerpt: str

    def render(self) -> str:
        """Format this violation as one reviewable report line.

        Returns:
            A ``path:line: kind :: excerpt`` string.
        """
        return f"{self.path}:{self.line}: {self.kind} :: {self.excerpt}"


def _mask_code_regions(text: str) -> str:
    """Blank out spans where a prohibited form is a quotation rather than a label.

    Three span kinds are masked, each for its own reason: a fenced block holds a
    command or a code sample; a backtick span is how this repository quotes a
    prohibited form while naming it; and a Javadoc ``{@code}`` or ``{@literal}``
    span is the Java equivalent of a backtick span.

    Args:
        text: The whole file contents.

    Returns:
        The same text with those spans replaced by spaces, so every line number and
        every column offset is preserved.
    """

    def blank(match: re.Match[str]) -> str:
        # Newlines survive so a masked span never merges two lines into one and
        # shifts every line number after it.
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    masked = re.sub(r"```.*?```", blank, text, flags=re.S)
    masked = re.sub(r"~~~.*?~~~", blank, masked, flags=re.S)
    masked = re.sub(r"\{@(?:code|literal)\b.*?\}", blank, masked, flags=re.S)
    masked = re.sub(r"`[^`\n]*`", blank, masked)
    return masked


def _strip_comment_prefixes(line: str) -> str:
    """Remove the comment, list and block-quote markers that can precede a label.

    Markers are stripped repeatedly because a real line reaches a label through
    several of them at once -- a Javadoc continuation inside a block quote inside a
    list item, for instance.

    Args:
        line: One raw line.

    Returns:
        The line with leading whitespace and markers removed.
    """
    stripped = line.strip()
    changed = True
    while changed:
        changed = False
        for marker in (*COMMENT_PREFIXES, ">", "-", "*", "+"):
            if stripped.startswith(marker):
                candidate = stripped[len(marker) :].lstrip()
                # A bare "*" or "_" opening an emphasised label must survive, or the
                # emphasis patterns below would never see the marker they match on.
                # The test uses the label's first word rather than the whole label
                # because a hard wrap splits "Alternatives Considered" in two, and it
                # admits NO whitespace between marker and word: markdown emphasis is
                # written against the word it emphasises, whereas a Javadoc
                # continuation star and a list bullet are followed by a space. With a
                # space admitted, every canonical label on a Javadoc line would read
                # as emphasised and the gate would reject the form it exists to
                # require.
                if marker in {"*", "-", "+", "_"} and re.match(
                    rf"{_EMPHASIS}(?:{_LABEL_OPENERS})", stripped
                ):
                    continue
                stripped = candidate
                changed = True
                break
    return stripped


_LABEL_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "parenthesised",
        re.compile(rf"\(\s*(?:{_ANY_FORM_ALTERNATION})\s*\)"),
    ),
    (
        "why-parenthesised",
        re.compile(rf"\bWHY\b[^()\n]{{0,90}}\(\s*(?:{_ANY_FORM_ALTERNATION})"),
    ),
    (
        # No whitespace is admitted between the opening emphasis marker and the
        # label: CommonMark does not open emphasis before whitespace, so "* Label"
        # is a bullet and "*Label" is emphasis. Admitting a space here would flag
        # every canonical label written on a Javadoc continuation line.
        "emphasised",
        re.compile(
            rf"{_EMPHASIS}(?:{_ANY_FORM_ALTERNATION})\s*(?:{_EMPHASIS}\s*:|:\s*{_EMPHASIS})"
        ),
    ),
    (
        # The lookbehind keeps a hyphenated or run-on word from reading as a label,
        # and the trailing "\s*" catches the form written with a space before the
        # colon.
        "singular",
        re.compile(
            r"(?<![A-Za-z-])(?:" + "|".join(re.escape(s) for s in _SINGULAR_STEMS) + r")\s*:"
        ),
    ),
)

# Line-anchored on purpose: an emphasised label whose colon was dropped is
# recognisable only at the start of its own line, because mid-sentence emphasis
# around one of these words is ordinary prose.
_COLON_DROPPED_PATTERN = re.compile(
    rf"^{_EMPHASIS}(?:{_LABEL_ALTERNATION})[.,]?\s*{_EMPHASIS}(?!\s*:)"
)


def iter_label_violations(path: str, text: str) -> Iterator[Violation]:
    """Yield every non-canonical rationale label in one file.

    Two passes run over the same masked text. The per-line pass catches a label
    written on one line. The joined pass catches a label a hard wrap split across
    two lines, which the per-line pass cannot see and which is the most common way
    an emphasised label survives review.

    Args:
        path: Repository-relative path, reported with each violation.
        text: The file's contents.

    Yields:
        One :class:`Violation` per prohibited occurrence.
    """
    masked = _mask_code_regions(text)
    lines = masked.splitlines()
    reported: set[tuple[int, str]] = set()

    for index, raw in enumerate(lines, start=1):
        body = _strip_comment_prefixes(raw)
        if not body:
            continue
        for kind, pattern in _LABEL_PATTERNS:
            if pattern.search(body) and (index, kind) not in reported:
                reported.add((index, kind))
                yield Violation(path, index, kind, body[:110])
        dropped = "emphasised-colon-dropped"
        if _COLON_DROPPED_PATTERN.search(body) and (index, dropped) not in reported:
            reported.add((index, dropped))
            yield Violation(path, index, dropped, body[:110])

    for index in range(len(lines) - 1):
        first = _strip_comment_prefixes(lines[index])
        joined = f"{first} {_strip_comment_prefixes(lines[index + 1])}"
        if not joined.strip():
            continue
        for kind, pattern in _LABEL_PATTERNS:
            if kind == "singular":
                # A singular label cannot be split across a wrap: the colon that
                # makes it a label sits against the word.
                continue
            match = pattern.search(joined)
            if match is None:
                continue
            first_len = len(first)
            # Report only a match that genuinely straddles the wrap; a match wholly
            # inside either line was already reported by the per-line pass.
            if match.start() < first_len < match.end() and (index + 1, kind) not in reported:
                reported.add((index + 1, kind))
                yield Violation(path, index + 1, f"{kind}-wrapped", joined[:110])


def _header_block_end(lines: Sequence[str]) -> int:
    """Find the last line of a file's leading header comment block.

    The header block is the contiguous run of comment lines beginning at line one,
    terminated by the first blank line or the first line that is not a comment. A
    shebang and a Java package or import statement do not open a header block, so a
    file whose first line is one of those has no header block at all.

    Args:
        lines: The file's lines, in order.

    Returns:
        The one-based number of the block's last line, or ``0`` when the file opens
        with something other than a comment.
    """
    end = 0
    for index, raw in enumerate(lines, start=1):
        stripped = raw.strip()
        if index == 1 and stripped.startswith("#!"):
            end = index
            continue
        if not stripped:
            break
        if not any(stripped.startswith(marker) for marker in COMMENT_PREFIXES):
            break
        end = index
    return end


def iter_what_violations(path: str, text: str) -> Iterator[Violation]:
    """Yield every statement-level ``WHAT:`` comment in one code or config file.

    Args:
        path: Repository-relative path, reported with each violation.
        text: The file's contents.

    Yields:
        One :class:`Violation` per prohibited occurrence.
    """
    lines = text.splitlines()
    permitted_through = _header_block_end(lines)
    masked_lines = _mask_code_regions(text).splitlines()

    for index, raw in enumerate(masked_lines, start=1):
        if index <= permitted_through:
            continue
        stripped = raw.strip()
        if not any(stripped.startswith(marker) for marker in COMMENT_PREFIXES):
            continue
        if _WHAT_TOKEN in stripped:
            yield Violation(path, index, "statement-what", stripped[:110])


def _is_governed(relative: str, suffixes: frozenset[str]) -> bool:
    """Decide whether one path is inside this gate's remit for a given check.

    Args:
        relative: Repository-relative path, using forward slashes.
        suffixes: The suffix set the calling check governs.

    Returns:
        ``True`` when the file must be read, ``False`` when it is excluded.
    """
    if relative in ALLOWLISTED_PATHS or relative in REFERENCE_FILES:
        return False
    if any(relative.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
        return False
    name = relative.rsplit("/", 1)[-1]
    if name in EXTENSIONLESS_NAMES:
        return True
    suffix = Path(relative).suffix
    return suffix in suffixes


def iter_repository_files(root: Path) -> Iterator[Path]:
    """Walk the repository, skipping excluded directories without descending them.

    Args:
        root: Repository root.

    Yields:
        Every candidate file path, absolute.
    """
    stack = [root]
    while stack:
        current = stack.pop()
        for entry in sorted(current.iterdir()):
            if entry.is_symlink():
                continue
            if entry.is_dir():
                if entry.name in EXCLUDED_DIR_NAMES:
                    continue
                stack.append(entry)
            elif entry.is_file():
                yield entry


def scan_tree(root: Path) -> list[Violation]:
    """Run both lexical checks over every governed file in the tree.

    Args:
        root: Repository root.

    Returns:
        Every violation found, ordered by path then line.

    Raises:
        OSError: If a governed file cannot be read.
    """
    violations: list[Violation] = []
    for absolute in iter_repository_files(root):
        relative = absolute.relative_to(root).as_posix()
        governed_for_labels = _is_governed(relative, LABEL_SUFFIXES)
        governed_for_what = _is_governed(relative, WHAT_SUFFIXES)
        if not (governed_for_labels or governed_for_what):
            continue
        try:
            text = absolute.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # A governed suffix carrying non-UTF-8 bytes holds no authored prose to
            # check; skipping it is safe and keeps the gate from failing on a
            # binary fixture that happens to share an extension.
            continue
        if governed_for_labels:
            violations.extend(iter_label_violations(relative, text))
        if governed_for_what:
            violations.extend(iter_what_violations(relative, text))
    return sorted(violations, key=lambda item: (item.path, item.line, item.kind))


# Each row asserts one claim the standard makes about a live gate against the file
# that would have to carry it. A claim whose mechanism has been deleted, renamed or
# never wired fails here rather than being believed.
CONSISTENCY_CLAIMS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    (
        "Java Javadoc presence at every visibility",
        "config/checkstyle/checkstyle.xml",
        (
            "MissingJavadocMethod",
            "MissingJavadocType",
            "JavadocMethod",
            '<property name="scope" value="private"/>',
        ),
    ),
    (
        "Checkstyle bound to the Maven validate phase over the named ruleset",
        "services/pom.xml",
        ("maven-checkstyle-plugin", "config/checkstyle/checkstyle.xml", "<phase>validate</phase>"),
    ),
    (
        "TypeScript JSDoc gate including the file-overview clause",
        "ui/eslint.config.js",
        ("jsdoc", "require-jsdoc", "require-file-overview"),
    ),
    (
        "TypeScript gate run at zero tolerance",
        "ui/package.json",
        ("eslint-plugin-jsdoc", "--max-warnings=0"),
    ),
    (
        "Python pydocstyle family selected",
        "data-migration/pyproject.toml",
        ("[tool.ruff.lint]", '"D"'),
    ),
    (
        "Python presence gate at every visibility and nesting depth",
        "data-migration/tests/test_docstring_gate.py",
        ("def test_", "docstring"),
    ),
    (
        "HCL description presence and file-set declaration",
        "infra/.tflint.hcl",
        ("terraform_documented_variables", "terraform_documented_outputs"),
    ),
    (
        "HCL generated-document freshness",
        "infra/.terraform-docs.yml",
        ("output",),
    ),
    (
        "Java and Python gates wired into services CI",
        ".github/workflows/services-ci.yml",
        ("validate", "ruff check", "rule1_gate.py"),
    ),
    (
        "TypeScript gate wired into UI CI",
        ".github/workflows/ui-ci.yml",
        ("lint", "rule1_gate.py"),
    ),
    (
        "HCL gates wired into infrastructure CI",
        ".github/workflows/infra-ci.yml",
        ("tflint", "terraform-docs", "rule1_gate.py"),
    ),
    (
        "The standard states the four labels in their canonical form",
        "docs/CODE_DOCUMENTATION_STANDARD.md",
        CANONICAL_LABELS,
    ),
)


def check_consistency(root: Path) -> list[str]:
    """Hold the standard's live-gate claims to the configuration that carries them.

    Args:
        root: Repository root.

    Returns:
        One human-readable failure line per unmet claim; empty when every claim holds.
    """
    failures: list[str] = []
    for claim, relative, required in CONSISTENCY_CLAIMS:
        target = root / relative
        if not target.is_file():
            failures.append(f"{relative}: missing, but the standard claims: {claim}")
            continue
        text = target.read_text(encoding="utf-8")
        missing = [needle for needle in required if needle not in text]
        if missing:
            failures.append(f"{relative}: {claim} -- not found: {', '.join(missing)}")
    return failures


# WHY : Assumptions: each fixture is ASSEMBLED from fragments for the same reason
#       the prohibited tokens above are -- a fixture written out in full would be a
#       real prohibited form sitting in this file, and the gate would then have to
#       exempt its own path to pass. Every fragment boundary below is placed so that
#       no line of this source contains a complete prohibited form.
_PROHIBITED_FIXTURES: tuple[tuple[str, str], ...] = (
    ("parenthesised", "// WHY : (" + "Assumptions" + ") the pool is bounded.\n"),
    ("why-parenthesised", "# WHY (" + "Refactoring Rationale" + "): the loop was inverted.\n"),
    ("emphasised", "> **" + "Trade-offs" + ":** two encodings cost a discriminator.\n"),
    ("emphasised-colon-dropped", "**" + "Assumptions" + ".** the extract is fixed width.\n"),
    ("singular", "-- " + "Assumption" + ": the view exists.\n"),
    ("emphasised-wrapped", "* *" + "Alternatives" + "\n* " + "Considered" + "*: a shared list.\n"),
)

_PERMITTED_FIXTURES: tuple[str, ...] = (
    "// WHY : Assumptions: the pool is bounded by the connection budget.\n",
    "# Alternatives Considered: one shared list. Rejected on blast radius.\n",
    "-- Trade-offs: an extra index in exchange for a keyset page.\n",
    "# Refactoring Rationale: the module was split so the gate can see it.\n",
    " * Assumptions: **the emphasis here surrounds the sentence, not the label.**\n",
    "> The words assumption and trade-off in ordinary prose are not labels.\n",
    "`(" + "Assumptions" + ")` quoted as a counter-example is not a label.\n",
    "```\n# " + "WHAT" + ": a fenced command block may carry one.\n```\n",
)


def run_self_test() -> list[str]:
    """Prove both detectors still fire and still stay silent, before trusting a pass.

    Returns:
        One failure line per broken expectation; empty when every expectation holds.
    """
    failures: list[str] = []

    for expected_kind, sample in _PROHIBITED_FIXTURES:
        kinds = {violation.kind for violation in iter_label_violations("sample.md", sample)}
        if not any(kind.startswith(expected_kind.removesuffix("-wrapped")) for kind in kinds):
            failures.append(f"label detector missed {expected_kind}: {sample.strip()!r} -> {kinds}")

    for sample in _PERMITTED_FIXTURES:
        found = list(iter_label_violations("sample.md", sample))
        if found:
            failures.append(f"label detector false positive: {sample.strip()!r} -> {found[0].kind}")

    header_only = (
        f"# {_WHAT_TOKEN} the module's purpose.\n# WHY : Assumptions: stated once.\n\nx = 1\n"
    )
    if list(iter_what_violations("sample.py", header_only)):
        failures.append("what detector rejected a permitted header WHAT")

    statement_level = f"x = 1\n\n# {_WHAT_TOKEN} increment the counter.\nx += 1\n"
    if not list(iter_what_violations("sample.py", statement_level)):
        failures.append("what detector missed a statement-level WHAT")

    detached = f"#!/bin/sh\n# {_WHAT_TOKEN} the script's purpose.\n"
    if list(iter_what_violations("sample.sh", detached)):
        failures.append("what detector rejected a header WHAT beneath a shebang")

    return failures


def _report(title: str, lines: Iterable[str]) -> int:
    """Print one check's result and return its contribution to the exit status.

    Args:
        title: Human-readable name of the check.
        lines: Failure lines, empty when the check passed.

    Returns:
        ``0`` when the check passed, ``1`` when it failed.
    """
    collected = list(lines)
    if not collected:
        print(f"PASS  {title}")
        return 0
    print(f"FAIL  {title} -- {len(collected)} finding(s)")
    for line in collected:
        print(f"        {line}")
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    """Run the selected checks and return a fail-closed exit status.

    Args:
        argv: Command-line arguments, defaulting to :data:`sys.argv`.

    Returns:
        ``0`` when every selected check passed, ``1`` otherwise.
    """
    parser = argparse.ArgumentParser(
        description=(
            "Fail-closed lexical gate for Rule 1 rationale labels and statement WHAT comments."
        )
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root to scan; defaults to the root this file sits in.",
    )
    parser.add_argument(
        "--check",
        choices=("all", "labels", "what", "consistency", "self-test"),
        default="all",
        help="Run one check instead of all of them.",
    )
    arguments = parser.parse_args(argv)
    root: Path = arguments.root.resolve()

    status = 0
    selected = arguments.check

    if selected in {"all", "self-test"}:
        status |= _report("self-test: both detectors fire and stay silent", run_self_test())

    if selected in {"all", "labels", "what"}:
        violations = scan_tree(root)
        if selected in {"all", "labels"}:
            label_findings = [v.render() for v in violations if v.kind != "statement-what"]
            status |= _report("labels: rationale labels are in canonical form", label_findings)
        if selected in {"all", "what"}:
            what_findings = [v.render() for v in violations if v.kind == "statement-what"]
            status |= _report("what: no statement-level WHAT outside a header block", what_findings)

    if selected in {"all", "consistency"}:
        status |= _report(
            "consistency: the standard's live-gate claims match the configuration",
            check_consistency(root),
        )

    return status


if __name__ == "__main__":
    sys.exit(main())
