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

This module is executable and self-testing. ``--check self-test`` proves the
detectors still fire on each prohibited form and stay silent on each permitted one,
and that each structural extractor reads the active configuration rather than a
comment about it, so a regression that silently disables a check fails the run
instead of reporting a vacuous pass. The self-test also runs as part of the default
``--check all``, so an ordinary invocation with no arguments cannot skip it.

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
import ast
import json
import re
import sys
import tomllib
import xml.etree.ElementTree as ElementTree
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

# WHY : Assumptions: this file is GENERATED by `npm ci` / `npm install` and carries
#       no authored prose at all -- it is roughly twenty thousand lines of resolved
#       versions, integrity hashes and transitive metadata. It is excluded by exact
#       path rather than by suffix so that its authored sibling ui/package.json,
#       which does carry rationale and is the reason .json is governed at all, stays
#       in scope. Excluding the lockfile by suffix would have excluded both.
GENERATED_FILES: frozenset[str] = frozenset({"ui/package-lock.json"})

# WHY : Assumptions: a path segment rather than a list of files, because these trees
#       hold FIXED-WIDTH RECORD FIXTURES -- 350-byte daily-transaction rows,
#       300-byte account rows and the like -- whose .txt extension is incidental and
#       whose bytes are a copybook layout rather than prose. Measured when .txt was
#       brought into scope: 128 of the 131 non-reference .txt files in the tree sit
#       under this segment and are fixtures, and the remaining three are the
#       data-migration requirements manifests, which DO carry rationale for every pin
#       and are governed.
#       Alternatives Considered: naming those three manifests in an allow-list
#       instead. Rejected because it is an enumeration -- a fourth manifest would
#       silently go ungoverned while the gate still reported coverage, which is the
#       same hazard the terraform-docs and TFLint invocations avoid by globbing. A
#       segment rule generalises in the safe direction: a new manifest is governed by
#       default and only a new FIXTURE tree needs a decision here.
FIXTURE_PATH_SEGMENT: str = "/src/test/resources/fixtures/"

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
#
# WHY : Refactoring Rationale: .json, .html, .txt and .example were ABSENT from
#       this set, and their absence was not a decision -- it left five
#       rationale-dense files ungoverned while the gate reported repository-wide
#       coverage. Measured at the time they were added: ui/package.json carries 12
#       labels, ui/tsconfig.json 29, ui/tsconfig.node.json 21, ui/index.html 16 and
#       ui/.env.example 16, and the three data-migration requirements manifests
#       carry rationale for every pin. The worst of them is ui/package.json, whose
#       own $note states that its label keys "retain the trailing colon, so that the
#       four canonical labels ... appear here as exactly the literal,
#       colon-terminated strings a fixed-string audit searches for" -- a file that
#       explicitly assumes it is audited and was not.
#       Trade-offs: JSON admits no comment syntax at all, so a label inside a JSON
#       file can only be a string value. That is exactly the case ui/package.json
#       relies on, so governing .json is the only way to hold the one file whose
#       rationale has nowhere else to live; the accepted cost is that a JSON string
#       that merely quotes a variant form would be reported, which the allowlist
#       mechanism already exists to handle if it ever happens.
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
        ".json",
        ".html",
        ".txt",
        ".example",
    }
)

# The WHAT check governs code and configuration only. Markdown is excluded because
# the aligned WHAT/WHY pair IS correct there, inside a fenced command block: a
# shell pipeline has no docstring construct to carry its purpose.
#
# WHY : Assumptions: .json is excluded from the WHAT check as well, and for a
#       different reason from Markdown's. The check permits the token only inside a
#       file's LEADING HEADER BLOCK, and it finds that block by reading leading
#       comment lines -- of which a strict-JSON file has none, so every header a
#       JSON file could have is zero lines long and any occurrence anywhere in it
#       would be reported as statement-level. The standard's own JSON section says the
#       obligation is discharged in the form the format admits, and for JSON that
#       form is a string value rather than a header block, so applying a
#       header-relative rule to it would be applying a rule about a construct the
#       format does not have. The label check still governs .json, because a label's
#       written form does not depend on where a header ends.
WHAT_SUFFIXES: frozenset[str] = LABEL_SUFFIXES - {".md", ".json"}

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
        """Replace one matched span with blanks, keeping its newlines.

        Args:
            match: The span a masking pattern matched, which may cover several lines.

        Returns:
            A string of the same length as the matched text, with every character
            replaced by a space except the newlines, which are kept.

        Raises:
            None. Every branch is a character substitution.
        """
        # Assumptions: newlines survive so a masked span never merges two lines into
        #   one and shifts every line number after it. Every finding this gate reports
        #   names a line, so the substitution has to be length- AND line-preserving
        #   rather than merely emptying the span.
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
    if relative in GENERATED_FILES:
        return False
    if any(relative.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
        return False
    if FIXTURE_PATH_SEGMENT in f"/{relative}":
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
                # Assumptions: a Python packaging metadata directory is matched by
                #   SUFFIX rather than by name, because its name is derived from the
                #   distribution -- carddemo_migration.egg-info here -- so no fixed
                #   name can exclude it. It is generated by an editable or wheel
                #   install and holds no authored prose; before it was excluded, the
                #   widened .txt governance reached five of its generated manifests.
                if entry.name.endswith(".egg-info"):
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


def _active_xml(text: str) -> str:
    """Render an XML document's ACTIVE elements as one searchable line each.

    Args:
        text: The document's contents.

    Returns:
        One line per element: the local tag name, then each attribute as
        ``name=value`` in name order, then ``:text=`` and the element's stripped text
        when it has any. Comments are absent, because the parser discards them.

    Raises:
        ElementTree.ParseError: If the document is not well-formed, which is a real
            failure of the file rather than of this gate.
    """
    root = ElementTree.fromstring(text)
    lines: list[str] = []
    for element in root.iter():
        tag = re.sub(r"^\{[^}]*}", "", element.tag)
        rendered = tag
        for name, value in sorted(element.attrib.items()):
            rendered += f" {re.sub(r'^\\{[^}]*}', '', name)}={value}"
        body = (element.text or "").strip()
        if body:
            rendered += f" :text={body}"
        lines.append(rendered)
    return "\n".join(lines) + "\n"


def _flatten(prefix: str, value: object, out: list[str]) -> None:
    """Append one ``dotted.path=value`` line per leaf of a parsed document.

    Args:
        prefix: Dotted path accumulated so far; empty at the root.
        value: The node to flatten.
        out: Accumulator the lines are appended to.

    Returns:
        None. The lines are appended to ``out``.
    """
    if isinstance(value, dict):
        for key, child in value.items():
            out_prefix = f"{prefix}.{key}" if prefix else str(key)
            _flatten(out_prefix, child, out)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _flatten(f"{prefix}[{index}]", child, out)
    else:
        out.append(f"{prefix}={value}")


def _active_json(text: str) -> str:
    """Render a JSON document's leaves as one ``dotted.path=value`` line each.

    Args:
        text: The document's contents.

    Returns:
        The flattened document. JSON admits no comments, so every line is active by
        construction; what the path prefix buys is that a needle can be anchored to
        the KEY it must appear under rather than matching any string in the file.

    Raises:
        json.JSONDecodeError: If the document is not valid JSON.
    """
    lines: list[str] = []
    _flatten("", json.loads(text), lines)
    return "\n".join(lines) + "\n"


def _active_toml(text: str) -> str:
    """Render a TOML document's leaves as one ``dotted.path=value`` line each.

    Args:
        text: The document's contents.

    Returns:
        The flattened document, with list members indexed, so a needle can require a
        value to be a MEMBER of a named list rather than a substring of the file.

    Raises:
        tomllib.TOMLDecodeError: If the document is not valid TOML.
    """
    lines: list[str] = []
    _flatten("", tomllib.loads(text), lines)
    return "\n".join(lines) + "\n"


def _active_python(text: str) -> str:
    """Render a Python module's declared names as one line each.

    Args:
        text: The module's source.

    Returns:
        One ``def <name>`` or ``class <name>`` line per declaration at any nesting
        depth, in source order. A commented-out declaration contributes nothing,
        which is the whole point of parsing rather than searching.

    Raises:
        SyntaxError: If the source does not parse.
    """
    tree = ast.parse(text)
    lines: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            lines.append(f"def {node.name}")
        elif isinstance(node, ast.ClassDef):
            lines.append(f"class {node.name}")
    return "\n".join(sorted(lines)) + "\n"


def _uncommented(text: str, line_markers: tuple[str, ...], blocks: bool) -> str:
    """Blank the comment lines of a document whose language has no stdlib parser.

    Args:
        text: The document's contents.
        line_markers: The openers that begin a whole-line comment IN THIS LANGUAGE.
        blocks: Whether the language also has ``/* ... */`` block comments.

    Returns:
        The same text with each comment line replaced by an empty line, so line
        positions survive for a reader comparing the two.

    Raises:
        None.
    """
    # WHY : Assumptions: the marker set is per LANGUAGE and never a union of all of
    #   them, which is the opposite of the obvious implementation and is load-bearing.
    #   A union was tried first and corrupted two of the six documents it was applied
    #   to. `--` opens a comment in SQL and HCL but a continued command-line flag in a
    #   workflow run block, so treating it as a comment everywhere blanked the
    #   `--output-check` line of a real gate; and `/* ... */` spans nothing in YAML,
    #   where a path glob such as `infra/envs/*/` supplies a `/*` that a later `*/`
    #   closes, so a DOTALL block strip deleted the whole span between two unrelated
    #   paths. Both failures were silent in the direction that matters: the needle
    #   vanished and the claim reported as unwired.
    body = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL) if blocks else text
    kept: list[str] = []
    for line in body.split("\n"):
        kept.append("" if line.lstrip().startswith(line_markers) else line)
    return "\n".join(kept)


def _active_yaml(text: str) -> str:
    """Blank the comment lines of a YAML document, whose only comment opener is ``#``.

    Args:
        text: The document's contents.

    Returns:
        The document with its comment lines blanked.

    Raises:
        None.
    """
    return _uncommented(text, ("#",), blocks=False)


def _active_javascript(text: str) -> str:
    """Blank the comment lines and block comments of a JavaScript module.

    Args:
        text: The module's source.

    Returns:
        The source with ``//`` lines, Javadoc continuation lines and ``/* ... */``
        spans removed.

    Raises:
        None.
    """
    return _uncommented(text, ("//", "*"), blocks=True)


def _active_hcl(text: str) -> str:
    """Blank the comment lines and block comments of an HCL document.

    Args:
        text: The document's contents.

    Returns:
        The document with ``#`` and ``//`` lines and ``/* ... */`` spans removed.

    Raises:
        None.
    """
    return _uncommented(text, ("#", "//"), blocks=True)


def _active_prose(text: str) -> str:
    """Return a prose document unchanged, because all of it is the artifact.

    Args:
        text: The document's contents.

    Returns:
        The text as given. Markdown has no active-versus-commented distinction: a
        sentence in a standard IS the standard, so there is nothing to strip.

    Raises:
        None.
    """
    return text


# WHY : Refactoring Rationale: every claim below used to be tested by asking whether
#       a fixed string appeared ANYWHERE in the named file, and these files are the
#       most heavily commented in the repository -- checkstyle.xml alone carries
#       several hundred lines of rationale naming every module it configures AND
#       every module it deliberately excludes. A needle satisfied by a comment about
#       a mechanism reads exactly like a needle satisfied by the mechanism, so the
#       check would have gone on passing after a module was withdrawn, a plugin
#       removed or a CI step deleted, as long as the prose explaining it survived.
#       That is not a hypothetical shape here: this file's own EXCLUDED MODULES
#       prose names RedundantImport and UnusedImports, two modules that ARE
#       withdrawn, so a claim about either would have passed on the withdrawal note.
#       Alternatives Considered: stripping comments from every file uniformly and
#       keeping the substring test. Rejected because it is right for the four
#       languages with no stdlib parser and WRONG for the four that have one -- XML
#       attribute order, JSON key nesting, TOML list membership and Python
#       declaration presence are all structure a text scan cannot see, and a
#       comment-stripped scan of pom.xml still cannot tell a configLocation from an
#       activation predicate that happens to name the same path.
#       Trade-offs: the text-based extractors strip WHOLE-LINE comments only, so a
#       needle hidden in a trailing inline comment would still satisfy them.
#       Accepted because this repository's convention puts rationale on its own
#       lines in every governed format, and because truncating an inline comment
#       marker would corrupt any value legitimately containing one -- a URL
#       fragment, a shell string or a Terraform path glob.
_EXTRACTORS: dict[str, object] = {
    "xml": _active_xml,
    "json": _active_json,
    "toml": _active_toml,
    "python": _active_python,
    "yaml": _active_yaml,
    "javascript": _active_javascript,
    "hcl": _active_hcl,
    "prose": _active_prose,
}


# Each row asserts one claim the standard makes about a live gate against the file
# that would have to carry it, tested against that file's ACTIVE configuration
# rather than its text. A claim whose mechanism has been deleted, renamed or never
# wired fails here rather than being believed. The third element selects the
# extractor; the fourth holds regular expressions, each of which must match.
CONSISTENCY_CLAIMS: tuple[tuple[str, str, str, tuple[str, ...]], ...] = (
    (
        "Java Javadoc presence at every visibility",
        "config/checkstyle/checkstyle.xml",
        "xml",
        (
            r"(?m)^module name=MissingJavadocMethod$",
            r"(?m)^module name=MissingJavadocType$",
            r"(?m)^module name=JavadocMethod$",
            r"(?m)^property name=scope value=private$",
        ),
    ),
    (
        "Checkstyle bound to the Maven validate phase over the named ruleset",
        "services/pom.xml",
        "xml",
        (
            r"(?m)^artifactId :text=maven-checkstyle-plugin$",
            r"(?m)^phase :text=validate$",
            # Assumptions: the ruleset is named INDIRECTLY, through a property, so the
            #   claim needs both halves -- the configLocation that resolves the
            #   property, and the property whose value ends at the checkstyle
            #   directory. The literal path config/checkstyle/checkstyle.xml also
            #   appears in two profile activation predicates, which name the file
            #   without binding the plugin to it, so a needle for the bare path would
            #   have passed on an activation alone.
            r"(?m)^configLocation :text=\$\{carddemo\.checkstyle\.config\.dir}/checkstyle\.xml$",
            r"(?m)^carddemo\.checkstyle\.config\.dir :text=\S*config/checkstyle$",
        ),
    ),
    (
        "TypeScript JSDoc gate including the file-overview clause",
        "ui/eslint.config.js",
        "javascript",
        (
            r"from 'eslint-plugin-jsdoc'",
            r"'jsdoc/require-jsdoc'",
            r"'jsdoc/require-file-overview'",
        ),
    ),
    (
        "TypeScript gate run at zero tolerance",
        "ui/package.json",
        "json",
        (
            r"(?m)^devDependencies\.eslint-plugin-jsdoc=",
            # Assumptions: the flag is required ON THE lint SCRIPT, not merely
            #   somewhere in the manifest. This file also carries a rationale block
            #   whose prose quotes the flag, so an unanchored needle would pass on the
            #   explanation after the script itself had lost it.
            r"(?m)^scripts\.lint=[^\n]*--max-warnings=0",
        ),
    ),
    (
        "Python pydocstyle family selected",
        "data-migration/pyproject.toml",
        "toml",
        (r"(?m)^tool\.ruff\.lint\.select\[\d+]=D$",),
    ),
    (
        "Python presence gate at every visibility and nesting depth",
        "data-migration/tests/test_docstring_gate.py",
        "python",
        (
            r"(?m)^def test_every_declaration_carries_a_docstring$",
            r"(?m)^def test_the_walker_reaches_every_nesting_and_visibility$",
            r"(?m)^def test_every_package_function_documents_its_return$",
        ),
    ),
    (
        "HCL description presence and file-set declaration",
        "infra/.tflint.hcl",
        "hcl",
        (
            # Assumptions: enabled = true is required alongside the rule name, because
            #   a rule block present with enabled = false is a rule that reports
            #   nothing, and this file's prose names every rule it discusses.
            r'rule "terraform_documented_variables" \{\s*\n(?:\s*\n)*\s*enabled = true',
            r'rule "terraform_documented_outputs" \{\s*\n(?:\s*\n)*\s*enabled = true',
        ),
    ),
    (
        "HCL generated-document freshness",
        "infra/.terraform-docs.yml",
        "yaml",
        (r"(?m)^output:$",),
    ),
    (
        "Java and Python gates wired into services CI",
        ".github/workflows/services-ci.yml",
        "yaml",
        (r"mvn[^\n]*\bvalidate\b", r"\bruff check\b", r"rule1_gate\.py"),
    ),
    (
        "TypeScript gate wired into UI CI",
        ".github/workflows/ui-ci.yml",
        "yaml",
        (r"npm run lint", r"rule1_gate\.py"),
    ),
    (
        "HCL gates wired into infrastructure CI",
        ".github/workflows/infra-ci.yml",
        "yaml",
        (r"\btflint\b", r"\bterraform-docs\b", r"rule1_gate\.py"),
    ),
    (
        "The standard states the four labels in their canonical form",
        "docs/CODE_DOCUMENTATION_STANDARD.md",
        "prose",
        tuple(re.escape(label) for label in CANONICAL_LABELS),
    ),
)


def check_consistency(root: Path) -> list[str]:
    """Hold the standard's live-gate claims to the ACTIVE configuration carrying them.

    Args:
        root: Repository root.

    Returns:
        One human-readable failure line per unmet claim; empty when every claim holds.
        A file that will not parse is reported as a failure rather than skipped,
        because a claim nobody could evaluate is not a claim that holds.
    """
    failures: list[str] = []
    for claim, relative, extractor_key, required in CONSISTENCY_CLAIMS:
        target = root / relative
        if not target.is_file():
            failures.append(f"{relative}: missing, but the standard claims: {claim}")
            continue
        extractor = _EXTRACTORS[extractor_key]
        try:
            active = extractor(target.read_text(encoding="utf-8"))  # type: ignore[operator]
        except (
            ElementTree.ParseError,
            json.JSONDecodeError,
            tomllib.TOMLDecodeError,
            SyntaxError,
        ) as unparsable:
            failures.append(
                f"{relative}: will not parse as {extractor_key}, so the claim cannot be"
                f" evaluated ({unparsable}); the standard claims: {claim}"
            )
            continue
        missing = [pattern for pattern in required if not re.search(pattern, active)]
        if missing:
            failures.append(
                f"{relative}: {claim} -- not present in the active {extractor_key}:"
                f" {', '.join(missing)}"
            )
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
    """Prove every mechanism this gate rests on still works, before trusting a pass.

    Three properties are proven, and each guards a way this gate could report a
    vacuous pass: the two lexical detectors still fire on each prohibited form and
    stay silent on each permitted one; the governed-format decision still admits
    every format the checks must read and still excludes the generated, reference and
    fixed-width-fixture files; and each structural extractor used by
    :func:`check_consistency` still reads ACTIVE configuration rather than a comment
    describing it.

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

    failures.extend(_governed_format_failures())
    failures.extend(_extractor_failures())

    return failures


# WHY : Assumptions: each row names a REPRESENTATIVE path for one governed format
#       and the check each format is subject to, so a format silently dropping out
#       of LABEL_SUFFIXES or WHAT_SUFFIXES fails the self-test rather than making
#       the tree look clean. The five formats added last are all here, because the
#       defect being guarded against is precisely a format that was never in the set
#       and therefore never reported anything.
#       Trade-offs: the paths are representative rather than exhaustive -- one per
#       format, not one per file -- because the property under test is which
#       EXTENSIONS the gate reads, and a per-file list would restate the tree.
_GOVERNED_FORMAT_EXPECTATIONS: tuple[tuple[str, bool, bool], ...] = (
    ("ui/package.json", True, False),
    ("ui/tsconfig.node.json", True, False),
    ("ui/index.html", True, True),
    ("ui/.env.example", True, True),
    ("data-migration/requirements.txt", True, True),
    ("services/common-lib/src/main/java/com/carddemo/common/money/Money.java", True, True),
    ("infra/modules/kms/main.tf", True, True),
    ("docs/CODE_DOCUMENTATION_STANDARD.md", False, False),
    ("ui/package-lock.json", False, False),
    (
        "services/batch-service/src/test/resources/fixtures/posting/happy_path/dailytran.txt",
        False,
        False,
    ),
    (".github/workflows/tests.yml", False, False),
    ("app/cbl/CBTRN02C.cbl", False, False),
)


# WHY : Assumptions: each probe is a MATCHED PAIR over one extractor -- a document
#       whose active configuration carries the thing, and a document from which the
#       active entry has been deleted while a comment describing it survives. The
#       pair is what proves the extractor reads structure: a substring test passes
#       BOTH halves, so a probe that only asserted the positive half would have been
#       satisfied by the implementation this replaced.
#       Trade-offs: the probes are miniature documents written here rather than
#       fixtures on disk. Accepted because a fixture tree would itself be governed by
#       this gate's label and statement-comment checks, so a fixture deliberately
#       carrying a prohibited form or a stale comment would have to be exempted --
#       and a gate with exemptions for its own fixtures is the shape this module's
#       fragment-assembled tokens already exist to avoid.
_EXTRACTOR_PROBES: tuple[tuple[str, str, str, str, str], ...] = (
    (
        "xml",
        r"(?m)^module name=MissingJavadocMethod$",
        '<?xml version="1.0"?><c><module name="MissingJavadocMethod"/></c>',
        '<?xml version="1.0"?><c><!-- MissingJavadocMethod was withdrawn --></c>',
        "an XML module named only in a comment",
    ),
    (
        "xml",
        r"(?m)^property name=scope value=private$",
        '<?xml version="1.0"?><c><property name="scope" value="private"/></c>',
        '<?xml version="1.0"?><c><property name="scope" value="public"/></c>',
        "an XML property whose value changed",
    ),
    (
        "json",
        r"(?m)^scripts\.lint=[^\n]*--max-warnings=0",
        '{"scripts": {"lint": "eslint . --max-warnings=0"}}',
        '{"scripts": {"lint": "eslint ."}, "note": "lint runs --max-warnings=0"}',
        "a JSON flag present only in sibling prose",
    ),
    (
        "toml",
        r"(?m)^tool\.ruff\.lint\.select\[\d+]=D$",
        '[tool.ruff.lint]\nselect = ["D", "E"]\n',
        '# select = ["D", "E"]\n[tool.ruff.lint]\nselect = ["E"]\n',
        "a TOML list member left commented out",
    ),
    (
        "python",
        r"(?m)^def test_the_walker_reaches_every_nesting_and_visibility$",
        "def test_the_walker_reaches_every_nesting_and_visibility() -> None:\n    pass\n",
        "# def test_the_walker_reaches_every_nesting_and_visibility() -> None:\npass\n",
        "a Python test commented out",
    ),
    (
        "yaml",
        r"rule1_gate\.py",
        "steps:\n  - run: python config/rule1/rule1_gate.py\n",
        "steps:\n  # the removed step ran config/rule1/rule1_gate.py\n  - run: true\n",
        "a workflow step deleted but described",
    ),
    (
        "javascript",
        r"'jsdoc/require-file-overview'",
        "export default [{ rules: { 'jsdoc/require-file-overview': ['error'] } }];\n",
        "/* dropped 'jsdoc/require-file-overview' */\n// 'jsdoc/require-file-overview'\n",
        "an ESLint rule surviving only in comments",
    ),
    (
        "hcl",
        r'rule "terraform_documented_variables" \{\s*\n(?:\s*\n)*\s*enabled = true',
        'rule "terraform_documented_variables" {\n  enabled = true\n}\n',
        'rule "terraform_documented_variables" {\n  enabled = false\n}\n'
        "# enabled = true was the previous setting\n",
        "an HCL rule left present but disabled",
    ),
)


def _extractor_failures() -> list[str]:
    """Prove each structural extractor reads active configuration, not prose about it.

    Returns:
        One failure line per probe whose positive half was not matched or whose
        negative half was; empty when every extractor discriminates correctly.
    """
    failures: list[str] = []
    for extractor_key, pattern, carries, describes_only, label in _EXTRACTOR_PROBES:
        extractor = _EXTRACTORS[extractor_key]
        active_positive = extractor(carries)  # type: ignore[operator]
        if not re.search(pattern, active_positive):
            failures.append(
                f"{extractor_key} extractor missed the active entry it must find"
                f" ({label}): {pattern}"
            )
        active_negative = extractor(describes_only)  # type: ignore[operator]
        if re.search(pattern, active_negative):
            failures.append(
                f"{extractor_key} extractor accepted {label}, so a claim would pass on a"
                f" comment after its mechanism was removed: {pattern}"
            )
    return failures


def _governed_format_failures() -> list[str]:
    """Prove the governed-format decision still matches what each check must read.

    Returns:
        One failure line per path whose governance differs from the expectation;
        empty when every expectation holds.
    """
    failures: list[str] = []
    for relative, expect_labels, expect_what in _GOVERNED_FORMAT_EXPECTATIONS:
        actual_labels = _is_governed(relative, LABEL_SUFFIXES)
        actual_what = _is_governed(relative, WHAT_SUFFIXES)
        if actual_labels is not expect_labels:
            failures.append(
                f"governed-format drift: {relative} label governance is {actual_labels},"
                f" expected {expect_labels}"
            )
        if actual_what is not expect_what:
            failures.append(
                f"governed-format drift: {relative} statement-comment governance is"
                f" {actual_what}, expected {expect_what}"
            )
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
        status |= _report(
            "self-test: detectors fire, governed formats hold, extractors read active"
            " configuration",
            run_self_test(),
        )

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
