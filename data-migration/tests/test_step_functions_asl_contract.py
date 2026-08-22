"""Verify the batch orchestrator's Amazon States Language input gates admit real input."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

#: Repository root, reached from this file rather than from the working directory.
#: WHY : Assumptions: the path is derived from ``__file__`` for the same reason
#: ``test_seed_datasets.py`` derives it -- this suite reads a Terraform artifact that is on no
#: import path, and deriving from the working directory would make the result depend on where
#: pytest was invoked from.
_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]

#: The orchestrator module whose Choice states gate every batch execution.
_ORCHESTRATOR = _REPOSITORY_ROOT / "infra" / "modules" / "step-functions-batch" / "main.tf"

#: The Terraform tree scanned for pseudo-wildcard patterns, so a second module cannot
#: reintroduce the defect this file exists to prevent.
_TERRAFORM_TREE = _REPOSITORY_ROOT / "infra"

#: Each ``StringMatches`` pattern the orchestrator declares, with the values it must admit and
#: the values it must refuse.
#: WHY : Assumptions: the admitted values are the ones the two real callers produce -- an
#:   operator or replay supplying ``businessDate`` as ``YYYY-MM-DD``, and EventBridge Scheduler
#:   substituting ``<aws.scheduler.scheduled-time>`` into ``scheduledTime`` as an RFC3339
#:   instant. The refused values are the mistakes that actually reach a graph: an undelimited
#:   token, an empty string, a prose value, and a timestamp whose separator is a space rather
#:   than the uppercase ``T`` RFC3339 requires.
_PATTERN_CONTRACT: dict[str, dict[str, tuple[str, ...]]] = {
    "*-*-*": {
        "admits": ("2022-07-18", "2026-08-12", "1000-01-01", "9999-12-31"),
        "refuses": ("", "20220718", "2022/07/18", "2022-07"),
    },
    "*-*-*T*": {
        "admits": (
            "2026-08-12T21:17:33Z",
            "2026-08-12T21:17:33.123Z",
            "2026-08-12T21:17:33+02:00",
        ),
        "refuses": ("", "2026-08-12", "2026-08-12 21:17:33", "20260812T211733Z"),
    },
    '*"Name":"${var.batch_container_name}"*': {
        "admits": (
            '{"Containers":[{"ExitCode":4,"Name":"batch"}]}',
            '{"Containers":[{"ExitCode":0,"Name":"collector"},{"ExitCode":4,"Name":"batch"}]}',
        ),
        "refuses": (
            "",
            '{"Containers":[{"ExitCode":4,"Name":"collector"}]}',
            '{"Containers":[{"ExitCode":4,"Name":"batch-sidecar"}]}',
            '{"Containers":[{"ExitCode": 4,"Name": "batch"}]}',
            '{"StopCode":"TaskFailedToStart"}',
        ),
    },
    '*"Name": "${var.batch_container_name}"*': {
        "admits": (
            '{"Containers": [{"ExitCode": 4, "Name": "batch"}]}',
            '{"Containers": [{"Name": "batch", "ExitCode": 4}]}',
        ),
        "refuses": (
            "",
            '{"Containers":[{"ExitCode":4,"Name":"batch"}]}',
            '{"Containers": [{"ExitCode": 4, "Name": "batch-sidecar"}]}',
            '{"StopCode": "TaskFailedToStart"}',
        ),
    },
    '*"ExitCode":4,*': {
        "admits": (
            '{"Containers":[{"ExitCode":4,"Name":"batch"}],"StopCode":"EssentialContainerExited"}',
            '{"Containers":[{"ContainerArn":"arn","ExitCode":4,"LastStatus":"STOPPED"}]}',
        ),
        "refuses": (
            "",
            '{"Containers":[{"ExitCode":40,"Name":"batch"}]}',
            '{"Containers":[{"ExitCode":8,"Name":"batch"}]}',
            '{"Containers":[{"ExitCode": 4,"Name":"batch"}]}',
            '{"StopCode":"TaskFailedToStart"}',
        ),
    },
    '*"ExitCode":4}*': {
        "admits": ('{"Containers":[{"Name":"batch","ExitCode":4}]}',),
        "refuses": (
            "",
            '{"Containers":[{"Name":"batch","ExitCode":40}]}',
            '{"Containers":[{"Name":"batch","ExitCode":4,"LastStatus":"STOPPED"}]}',
        ),
    },
    '*"ExitCode": 4,*': {
        "admits": ('{"Containers": [{"ExitCode": 4, "Name": "batch"}]}',),
        "refuses": (
            "",
            '{"Containers": [{"ExitCode": 40, "Name": "batch"}]}',
            '{"Containers":[{"ExitCode":4,"Name":"batch"}]}',
        ),
    },
    '*"ExitCode": 4}*': {
        "admits": ('{"Containers": [{"Name": "batch", "ExitCode": 4}]}',),
        "refuses": (
            "",
            '{"Containers": [{"Name": "batch", "ExitCode": 40}]}',
            '{"Containers": [{"ExitCode": 4, "Name": "batch"}]}',
        ),
    },
}

#: The comparison operators this file can evaluate. A rule using anything else fails the parse
#: rather than being skipped, so an operator added to the module must be taught here too.
_EVALUABLE_OPERATORS = frozenset(
    {
        "IsPresent",
        "IsString",
        "StringEquals",
        "StringMatches",
        "StringGreaterThanEquals",
        "StringLessThanEquals",
    }
)

#: Operators that read the variable's value, and therefore need the presence guard ahead of them.
_VALUE_OPERATORS = frozenset(
    {"StringEquals", "StringMatches", "StringGreaterThanEquals", "StringLessThanEquals"}
)

#: Matches one ``StringMatches = "<pattern>"`` declaration, tolerating the backslash-escaped
#: quotes a pattern needs when it matches a JSON payload rather than a bare date.
#: WHY : Refactoring Rationale: the earlier expression was ``"([^"]*)"``, which stopped at the
#:   FIRST escaped quote and captured the fragment ``*\\`` for a pattern such as
#:   ``*\"ExitCode\":4,*``. The inventory assertion then compared a truncated token against
#:   the table and the wildcard assertion decided on a fragment, so both read a pattern nobody
#:   wrote.
_PATTERN_DECLARATION = re.compile(r'StringMatches\s*=\s*"((?:[^"\\]|\\.)*)"')


def _declared_patterns(text: str) -> set[str]:
    """Collect every ``StringMatches`` pattern a Terraform text declares.

    Args:
        text: Terraform source with its comments already blanked.

    Returns:
        The declared patterns, with HCL escapes resolved, so the value compared here is the
        value Amazon States Language receives.
    """
    return {
        raw.replace('\\"', '"').replace("\\\\", "\\") for raw in _PATTERN_DECLARATION.findall(text)
    }


#: Stand-in for the ``${field}`` interpolation, chosen so it carries no brace of its own.
_FIELD_TOKEN = "@FIELD@"


def _rendered(pattern: str) -> str:
    """Resolve the interpolations a declared pattern carries, so it is exercised as it renders.

    The posting classifier interpolates ``var.batch_container_name`` into its identity patterns,
    so the literal source text carries ``${var.batch_container_name}`` where a real Cause carries
    the container's name. Exercising the raw text would assert nothing about the rendered rule,
    and hard-coding the name here would let the two drift, so the value is read from the
    variable's own declared default.

    Args:
        pattern: One declared StringMatches pattern, as written in the module source.

    Returns:
        The same pattern with every supported interpolation replaced by its declared value.

    Raises:
        AssertionError: If the pattern interpolates a variable this helper cannot resolve, which
            is a new coupling that has to be taught here rather than silently unexercised.
    """
    resolved = pattern.replace(
        "${var.batch_container_name}", _declared_default("batch_container_name")
    )
    assert "${" not in resolved, f"{pattern!r} interpolates a value this contract cannot resolve"
    return resolved


def _declared_default(variable: str) -> str:
    """Read one module variable's declared default.

    Args:
        variable: The variable's name.

    Returns:
        The default's string value.

    Raises:
        AssertionError: If the variable or its default cannot be found, which means the input was
            renamed or made required and the pattern above no longer renders what this asserts.
    """
    text = (_ORCHESTRATOR.parent / "variables.tf").read_text(encoding="utf-8")
    found = re.search(
        r'variable\s+"' + re.escape(variable) + r'"\s*\{.*?default\s*=\s*"([^"]*)"',
        text,
        re.DOTALL,
    )
    assert found, f"variable {variable!r} declares no string default to render patterns with"
    return found.group(1)


def _module_text() -> str:
    """Read the orchestrator module's Terraform source.

    Returns:
        The module's full text, decoded as UTF-8.

    Raises:
        OSError: If the module has been moved or renamed, which is a relocation rather than a
            contract defect and is left to surface as itself.
    """
    return _ORCHESTRATOR.read_text(encoding="utf-8")


def _without_comments(text: str) -> str:
    """Blank every Terraform comment so prose cannot satisfy an assertion about code.

    Args:
        text: Terraform source text.

    Returns:
        The same text with the contents of every ``#`` comment replaced by spaces, preserving
        line and column positions so a later match still reports a usable location.
    """
    scrubbed: list[str] = []
    for line in text.splitlines():
        quoted = False
        cut = len(line)
        for index, character in enumerate(line):
            if character == '"' and (index == 0 or line[index - 1] != "\\"):
                quoted = not quoted
            elif character == "#" and not quoted:
                cut = index
                break
        scrubbed.append(line[:cut] + " " * (len(line) - cut))
    return "\n".join(scrubbed)


def _string_matches(pattern: str, value: str) -> bool:
    """Evaluate one ``StringMatches`` pattern the way Amazon States Language evaluates it.

    Args:
        pattern: The pattern as it appears in the state machine definition, already decoded from
            JSON so that an escaped asterisk is one backslash followed by one asterisk.
        value: The candidate string.

    Returns:
        ``True`` when the pattern matches the whole value.

    Raises:
        AssertionError: If the pattern ends with a dangling escape, which no service would
            accept and which would otherwise be matched as a literal backslash.
    """
    # WHY : Assumptions: exactly one character is special -- "*" -- and it is escaped with a
    #   backslash; no other character has any special meaning during matching. That single
    #   sentence in the Choice-state specification is the whole reason this file exists: the
    #   module previously wrote "????-??-??", where each "?" is a LITERAL question mark, so the
    #   pattern matched only the ten-character string "????-??-??" and refused every date.
    expression: list[str] = ["\\A"]
    index = 0
    while index < len(pattern):
        character = pattern[index]
        if character == "\\":
            assert index + 1 < len(pattern), f"dangling escape in pattern {pattern!r}"
            expression.append(re.escape(pattern[index + 1]))
            index += 2
            continue
        expression.append(".*" if character == "*" else re.escape(character))
        index += 1
    expression.append("\\Z")
    return re.match("".join(expression), value, re.DOTALL) is not None


def _lexical_order(left: str, right: str) -> int:
    """Order two strings the way a string comparison operator orders them.

    Args:
        left: The candidate value.
        right: The bound it is compared against.

    Returns:
        A negative number when ``left`` sorts first, zero when the two are equal, and a positive
        number otherwise.
    """
    # WHY : Assumptions: the specification points at Java's compareTo for string comparison
    #   semantics, which orders by UTF-16 code unit. Python orders by code point, and the two
    #   agree for every character below U+10000; every bound and every candidate in this
    #   contract is ASCII, so the difference cannot arise here.
    if left == right:
        return 0
    return -1 if left < right else 1


def _bracket_span(text: str, start: int) -> str:
    """Return the balanced ``{...}`` or ``[...]`` value beginning at one offset.

    Args:
        text: Terraform source with comments already blanked.
        start: Index of the opening brace or bracket.

    Returns:
        The value text including its opening and closing delimiter.

    Raises:
        AssertionError: If the delimiters do not balance before the text ends.
    """
    depth = 0
    quoted = False
    for index in range(start, len(text)):
        character = text[index]
        if character == '"' and text[index - 1] != "\\":
            quoted = not quoted
            continue
        if quoted:
            continue
        if character in "{[":
            depth += 1
        elif character in "}]":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    raise AssertionError(f"unbalanced value beginning at offset {start}")


def _local_value(name: str) -> str:
    """Read one local whose value is a bare string literal.

    Args:
        name: The local's name, as declared at two-space indentation.

    Returns:
        The literal's contents.

    Raises:
        AssertionError: If the module declares no such local, which means a bound this contract
            resolves has been renamed or removed.
    """
    found = re.search(
        r'(?m)^  %s\s*=\s*"([^"]*)"\s*$' % re.escape(name), _without_comments(_module_text())
    )
    assert found is not None, f"main.tf declares no string local named {name}"
    return found.group(1)


def _local_block(name: str) -> str:
    """Read one local whose value is an object or a tuple.

    Args:
        name: The local's name, as declared at two-space indentation.

    Returns:
        The local's value text, delimiters included.

    Raises:
        AssertionError: If the module declares no such local.
    """
    body = _without_comments(_module_text())
    found = re.search(r"(?m)^  %s\s*=\s*(?=[{\[])" % re.escape(name), body)
    assert found is not None, f"main.tf declares no block local named {name}"
    return _bracket_span(body, found.end())


def _resolve(literal: str) -> object:
    """Turn one rule's right-hand side into the value a state machine would carry.

    Args:
        literal: The right-hand side exactly as written in Terraform.

    Returns:
        ``True`` or ``False`` for a boolean, the resolved string for a quoted literal or a
        reference to a string local.

    Raises:
        AssertionError: If the form is one this contract cannot resolve, so an expression is
            never silently evaluated as its own source text.
    """
    trimmed = literal.strip().rstrip(",")
    if trimmed in {"true", "false"}:
        return trimmed == "true"
    quoted = re.fullmatch(r'"([^"]*)"', trimmed)
    if quoted is not None:
        return quoted.group(1)
    reference = re.fullmatch(r"local\.([a-z_]+)", trimmed)
    assert reference is not None, f"unresolvable rule value {literal!r}"
    return _local_value(reference.group(1))


def _parse_rules(block: str, *, field: str | None = None) -> tuple[tuple[str, str, object], ...]:
    """Parse one rule set into ordered ``(variable, operator, value)`` triples.

    Args:
        block: The rule-set value text, as returned by :func:`_local_block`.
        field: The field name substituted for a ``${field}`` interpolation, or ``None`` when the
            block names its variable outright.

    Returns:
        The rules in declaration order.

    Raises:
        AssertionError: If a rule carries no variable, carries no evaluable operator, or carries
            more than one comparison.
    """
    # WHY : Assumptions: the interpolation is replaced by a token BEFORE the rule objects are
    #   split out, because "$.${field}" carries braces of its own and an innermost-object match
    #   would otherwise split a rule in half at them.
    tokenised = block.replace("${field}", _FIELD_TOKEN)
    rules: list[tuple[str, str, object]] = []
    for body in re.findall(r"\{([^{}]*)\}", tokenised):
        variable = re.search(r'Variable\s*=\s*"([^"]*)"', body)
        if variable is None:
            continue
        path = variable.group(1)
        if _FIELD_TOKEN in path:
            assert field is not None, f"rule {body!r} interpolates a field this call did not name"
            path = path.replace(_FIELD_TOKEN, field)
        comparisons = [
            (key, value)
            for key, value in re.findall(r"(?m)^\s*([A-Za-z]+)\s*=\s*(.+?)\s*$", body)
            if key != "Variable"
        ]
        assert len(comparisons) == 1, f"rule {body!r} carries {len(comparisons)} comparisons"
        operator, literal = comparisons[0]
        assert operator in _EVALUABLE_OPERATORS, f"rule uses unevaluable operator {operator}"
        rules.append((path, operator, _resolve(literal)))
    assert rules, f"no rules parsed from {block[:60]!r}"
    return tuple(rules)


def _date_rules(field: str) -> tuple[tuple[str, str, object], ...]:
    """Read the shared date rule set as it applies to one field.

    Args:
        field: The execution-input field the rules are applied to.

    Returns:
        The rules in declaration order.
    """
    return _parse_rules(_local_block("iso_date_rules"), field=field)


def _timestamp_rules() -> tuple[tuple[str, str, object], ...]:
    """Read the shared rule set that validates the scheduler's fire time.

    Returns:
        The rules in declaration order.
    """
    return _parse_rules(_local_block("iso_timestamp_rules"))


def _admits(rules: tuple[tuple[str, str, object], ...], document: dict[str, object]) -> bool:
    """Decide whether one ``And`` rule set admits one execution input.

    Args:
        rules: Ordered rules, as parsed from the module.
        document: The execution input, with an absent key standing for an absent field.

    Returns:
        ``True`` when every rule holds.

    Raises:
        AssertionError: If a rule uses an operator this evaluator does not implement.
    """
    for path, operator, expected in rules:
        key = path.removeprefix("$.")
        present = key in document
        value = document.get(key)
        if operator == "IsPresent":
            # WHY : Assumptions: a JSON null is a PRESENT field, which is why the type test
            #   below is load-bearing rather than redundant -- a payload carrying
            #   "businessDate": null satisfies presence and must still be refused.
            if present is not expected:
                return False
            continue
        if operator == "IsString":
            if isinstance(value, str) is not expected:
                return False
            continue
        if not present or not isinstance(value, str):
            # A comparison against an absent or non-string field has nothing to compare, which
            # is why the module orders the guards first; see the ordering assertion below.
            return False
        if operator == "StringEquals" and value != expected:
            return False
        if operator == "StringMatches" and not _string_matches(str(expected), value):
            return False
        if operator == "StringGreaterThanEquals" and _lexical_order(value, str(expected)) < 0:
            return False
        if operator == "StringLessThanEquals" and _lexical_order(value, str(expected)) > 0:
            return False
    return True


def test_the_pattern_evaluator_reproduces_the_documented_wildcard_semantics() -> None:
    """Pin the one wildcard rule this contract rests on, including the defect it detects."""
    # WHY : Assumptions: this is the specification's own example, so a matcher that passed the
    #   rest of this file while implementing shell globbing or a regex dialect fails here.
    assert _string_matches("log-*.txt", "log-2024.txt")
    assert not _string_matches("log-*.txt", "log-2024.csv")
    # WHY : Assumptions: the next two lines ARE the finding. "?" is not a wildcard, so the
    #   pattern the module used to carry matched only itself and refused every real date. A
    #   reader who doubts the fix can run these two assertions.
    assert not _string_matches("????-??-??", "2022-07-18")
    assert _string_matches("????-??-??", "????-??-??")
    assert _string_matches("\\*", "*")
    assert not _string_matches("\\*", "x")


def test_no_terraform_pattern_uses_an_unsupported_pseudo_wildcard() -> None:
    """Refuse any ``StringMatches`` pattern that spells a wildcard the service does not have."""
    offences: list[str] = []
    for source in sorted(_TERRAFORM_TREE.rglob("*.tf")):
        body = _without_comments(source.read_text(encoding="utf-8"))
        for pattern in sorted(_declared_patterns(body)):
            relative = source.relative_to(_REPOSITORY_ROOT)
            if "?" in pattern:
                offences.append(f"{relative}: {pattern!r} spells '?' as a wildcard")
            if "*" not in pattern:
                offences.append(f"{relative}: {pattern!r} has no wildcard, so it is StringEquals")
    assert not offences, "; ".join(offences)


def test_every_declared_pattern_is_exercised_by_this_contract() -> None:
    """Keep the pattern table complete, so a new pattern cannot arrive unexercised."""
    declared = _declared_patterns(_without_comments(_module_text()))
    assert declared == set(_PATTERN_CONTRACT), (
        f"declared {sorted(declared)} but this contract exercises {sorted(_PATTERN_CONTRACT)}"
    )


@pytest.mark.parametrize("pattern", sorted(_PATTERN_CONTRACT))
def test_each_pattern_admits_its_real_values_and_refuses_the_rest(pattern: str) -> None:
    """Exercise every declared pattern against the values its callers really send."""
    contract = _PATTERN_CONTRACT[pattern]
    rendered = _rendered(pattern)
    for value in contract["admits"]:
        assert _string_matches(rendered, value), f"{rendered!r} refused {value!r}"
    for value in contract["refuses"]:
        assert not _string_matches(rendered, value), f"{rendered!r} admitted {value!r}"


def test_the_daily_entry_gate_admits_both_of_its_real_callers() -> None:
    """Admit an operator's explicit business date and the scheduler's substituted fire time."""
    assert _admits(_date_rules("businessDate"), {"businessDate": "2022-07-18"})
    assert _admits(_timestamp_rules(), {"scheduledTime": "2026-08-12T21:17:33Z"})
    # WHY : Assumptions: the scheduler's emitted profile is asserted in three forms because the
    #   lexical bounds must not become a second way to refuse a valid fire time -- a fractional
    #   second or a numeric offset changes characters the bounds never reach, since the
    #   comparison is decided on the leading year digit.
    assert _admits(_timestamp_rules(), {"scheduledTime": "2026-08-12T21:17:33.123456Z"})
    assert _admits(_timestamp_rules(), {"scheduledTime": "2026-08-12T21:17:33+02:00"})


@pytest.mark.parametrize(
    "document",
    [
        {},
        {"businessDate": ""},
        {"businessDate": None},
        {"businessDate": 20220718},
        {"businessDate": "20220718"},
        {"businessDate": "not-a-date"},
        {"businessDate": "--"},
        {"businessDate": "0022-07-18"},
        {"scheduledTime": "2026-08-12T21:17:33Z"},
    ],
)
def test_the_daily_entry_gate_refuses_every_unusable_business_date(
    document: dict[str, object],
) -> None:
    """Refuse an absent, empty, null, non-string, undelimited or out-of-range business date."""
    # WHY : Assumptions: the last case carries a scheduledTime and no businessDate, and it is
    #   listed here because this rule set must NOT admit it -- the fallback is a separate
    #   Choice rule with its own shape test, and conflating the two would let a fire time be
    #   posted as a business date.
    assert not _admits(_date_rules("businessDate"), document)


@pytest.mark.parametrize(
    "document",
    [
        {},
        {"scheduledTime": ""},
        {"scheduledTime": "2026-08-12"},
        {"scheduledTime": "2026-08-12 21:17:33"},
        {"scheduledTime": "notatime"},
    ],
)
def test_the_daily_entry_gate_refuses_every_unusable_scheduled_time(
    document: dict[str, object],
) -> None:
    """Refuse a fire time that is absent, empty, dateless, or missing the RFC3339 separator."""
    assert not _admits(_timestamp_rules(), document)


def test_the_ad_hoc_report_gate_requires_both_dates_and_the_report_type() -> None:
    """Admit a complete report request and refuse each request missing one of its three parts."""
    rules = (
        _date_rules("startDate") + _date_rules("endDate") + (("$.reportType", "IsPresent", True),)
    )
    complete = {"startDate": "2022-07-01", "endDate": "2022-07-31", "reportType": "SUMMARY"}
    assert _admits(rules, complete)
    for omitted in complete:
        assert not _admits(rules, {k: v for k, v in complete.items() if k != omitted}), (
            f"the gate admitted a request with no {omitted}"
        )
    assert not _admits(rules, {**complete, "endDate": "31/07/2022"})


def test_the_operator_machines_reuse_the_shared_date_rules() -> None:
    """Bind all four input gates to one rule set, so no gate can drift back to a local pattern."""
    body = _without_comments(_module_text())
    # WHY : Assumptions: the references are asserted rather than the four Choice bodies, because
    #   the defect being prevented is DIVERGENCE. Six copies of one predicate is how a single wrong
    #   pattern became four wrong machines, so what has to hold is that every gate reads the same
    #   declaration.
    # WHY : Assumptions: FOUR businessDate references over four gates, because the authorization
    #   gate carries TWO unload arms -- one requiring a published extractForm and one requiring its
    #   absence, so a present-but-unpublished form reaches the refusal rather than being downgraded
    #   to the default -- and each arm carries the date test. The count is a measurement of this
    #   module, so it is re-taken rather than assumed whenever an arm lands.
    assert body.count('local.iso_date_rules["businessDate"]') == 4
    assert body.count('local.iso_date_rules["startDate"]') == 1
    assert body.count('local.iso_date_rules["endDate"]') == 1
    assert body.count("local.iso_timestamp_rules") == 1
    for state in (
        "ValidateExecutionInput",
        "ValidateReportRequest",
        "ValidateDatasetRequest",
        "ValidateAuthorizationExtractRequest",
    ):
        assert f"{state} = {{" in body, f"main.tf no longer declares the {state} gate"


def test_the_presence_guard_precedes_every_comparison_in_every_rule_set() -> None:
    """Order each rule set so no comparison is evaluated against an absent or non-string field."""
    for rules in (_date_rules("businessDate"), _timestamp_rules()):
        operators = [operator for _, operator, _ in rules]
        assert operators.index("IsPresent") == 0
        assert operators.index("IsString") == 1
        for operator in operators[2:]:
            assert operator in _VALUE_OPERATORS, f"{operator} is not a value comparison"


def _state_block(name: str) -> str:
    """Read one named state's definition text out of the module.

    Args:
        name: The state name as it is declared in a ``States`` map.

    Returns:
        The state's value text, delimiters included.

    Raises:
        AssertionError: If the module declares no state under that name.
    """
    body = _without_comments(_module_text())
    found = re.search(r"(?m)^\s*%s\s*=\s*(?={)" % re.escape(name), body)
    assert found is not None, f"main.tf declares no state named {name}"
    return _bracket_span(body, found.end())


def test_every_task_state_declares_a_retry_except_the_documented_one() -> None:
    """Hold the header's retry claim to the states, so the two cannot disagree again."""
    body = _without_comments(_module_text())
    # WHY : Assumptions: the count comparison is the assertion because three of the Task
    #   declarations are templates a for expression fans out into several states, so there is no
    #   one-to-one mapping from a state name to a line to walk.
    # WHY : ⚠️ Refactoring Rationale: the assertion is `tasks == retries + 1` where it was
    #   `tasks == retries`, and the change fixes a test that passed for two WRONG reasons at
    #   once. Its premise was that every Retry in the module sits in a Task state, so that equal
    #   counts restate "every Task retries". Both halves were false and they cancelled: the
    #   seed-staging Map carried a Retry of its own, which is not a Task, adding one to the
    #   right-hand side; and StopResidualBatchTask carries NO Retry, taking one away. The Map's
    #   Retry has since been withdrawn -- every error name in it was either not an ASL built-in
    #   at all or unraisable for an INLINE Map, and two of them made CreateStateMachine reject
    #   the whole definition -- which unmasked the missing one and turned this assertion red.
    #   Equality was never the right claim, so it is replaced rather than re-tuned.
    # WHY : Assumptions: the single exception is named rather than absorbed into the arithmetic,
    #   because an off-by-one that anyone may adjust is not a contract. Asserting the exception
    #   BY NAME means a Retry added to a Map still fails the count, a Retry removed from any
    #   other Task still fails the count, and a Retry added to StopResidualBatchTask fails the
    #   named assertion instead of silently rebalancing the total.
    stop_branch = _state_block("StopResidualBatchTask")
    assert re.search(r"(?m)^\s*Retry\s*=", stop_branch) is None, (
        "StopResidualBatchTask now declares a Retry; the cancellation loop already re-issues"
        " the stop on every pass and the branch catches its own failure, so a Retry here"
        " delays the confirmation pass without changing the outcome"
    )
    tasks = len(re.findall(r'Type\s+=\s+"Task"', body))
    retries = len(re.findall(r"(?m)^\s*Retry\s*=", body))
    assert tasks == retries + 1, (
        f"{tasks} Task states declare {retries} retries; exactly one Task state"
        " (StopResidualBatchTask) is documented as retry-free, so any other difference means"
        " either a Task lost its Retry or a non-Task state gained one"
    )


def test_the_seed_dataset_refresh_retries_the_work_only_at_the_task() -> None:
    """Pin the documented split: the per-dataset task retries the work, and neither wrapper does."""
    # WHY : ⚠️ Refactoring Rationale: this case reads three nesting levels where it read two,
    #   because state 2 gained a wrapper. StageSeedDatasets is now a Parallel holding ONE branch,
    #   and that branch holds the Map followed by the whole-migration verification gate; the gate
    #   is nested there so the nightly chain keeps the ELEVEN top-level work states AAP section
    #   0.4.1.7 fixes while still running the verification sections 0.9.2 and 0.7.7 mandate. The
    #   old two-level read still passed after the fold, but only by accident: its "outer" text had
    #   silently grown to include the gate, so it was asserting the gate's properties while
    #   claiming to assert the Map's. Each level is now located by name.
    wrapper = _state_block("StageSeedDatasets")
    mapping = _state_block("RefreshEachSeedDataset")
    gate = _state_block("VerifyMigration")
    assert re.search(r'Type\s*=\s*"Parallel"', wrapper) is not None, (
        "StageSeedDatasets is no longer a Parallel; the verification gate has nowhere to run"
        " inside state 2, which would put it back among the top-level states"
    )
    assert mapping in wrapper, "the seed-refresh Map is no longer inside StageSeedDatasets"
    assert gate in wrapper, "the verification gate is no longer inside StageSeedDatasets"
    processor = re.search(r"(?m)^\s*ItemProcessor\s*=\s*(?={)", mapping)
    assert processor is not None, "the seed-refresh Map no longer declares an ItemProcessor"
    branch = _bracket_span(mapping, processor.end())
    map_own = mapping.replace(branch, "")
    parallel_own = wrapper.replace(mapping, "").replace(gate, "")
    # WHY : Assumptions: the per-dataset task's retry is the one that must exist, because it is
    #   the only one that can replay ONE dataset. Re-entering either wrapper on a task failure
    #   would re-run every dataset to recover the one that faulted, and re-entering the Parallel
    #   would additionally repeat the read-only verification pass over a migration that had not
    #   changed.
    assert re.search(r"(?m)^\s*Retry\s*=", branch) is not None
    # WHY : Assumptions: the two ABSENCES are asserted, not merely tolerated as the previous form
    #   did with an `if`. A conditional assertion cannot fail, so it documented an intention
    #   without holding anything to it -- which is how a Map-level Retry naming two non-existent
    #   `States.` error codes survived long enough to make the machine un-creatable.
    assert re.search(r"(?m)^\s*Retry\s*=", map_own) is None, (
        "the seed-refresh Map declares a Retry again; for an INLINE Map with no ItemReader and no"
        " ResultWriter every Map-level error is either unraisable or documented as non-retriable,"
        " and re-entering the Map replays every branch of the fan-out"
    )
    assert re.search(r"(?m)^\s*Retry\s*=", parallel_own) is None, (
        "the StageSeedDatasets Parallel declares a Retry; re-entering it would replay a completed"
        " refresh of every dataset in order to redo one read-only verification pass"
    )
    # WHY : Assumptions: the ceiling sits on the Map and NOT on the Parallel, and both directions
    #   are asserted. The States Language defines TimeoutSeconds for Task and Activity states and
    #   at the machine's top level; it is absent from the Parallel field list, so a value written
    #   there would be silently inert rather than rejected -- exactly the kind of ceiling an
    #   operator would believe was in force.
    assert re.search(r"(?m)^\s*TimeoutSeconds\s*=", map_own) is not None
    assert re.search(r"(?m)^\s*TimeoutSeconds\s*=", parallel_own) is None, (
        "the StageSeedDatasets Parallel declares a TimeoutSeconds, which the States Language does"
        " not define for a Parallel; the ceiling belongs on the Map and on each task inside it"
    )
    # WHY : Assumptions: the failure edge is asserted on the WRAPPER, because a state inside a
    #   Parallel branch cannot transition out of it. The Map's and the gate's own catchers were
    #   withdrawn for that reason, so this is the only Catch that can carry a branch failure to
    #   the shared notification path, and it writes the same $.failure the other work states do.
    assert re.search(r"(?m)^\s*Catch\s*=", parallel_own) is not None
    assert re.search(r"(?m)^\s*Catch\s*=", map_own) is None, (
        "the seed-refresh Map declares a Catch, whose target cannot be outside the enclosing"
        " branch; the wrapper's Catch is what carries a branch failure to NotifyFailure"
    )


@pytest.mark.parametrize(
    "state",
    [
        "NotifyInvalidExecutionInput",
        "NotifyFailure",
        "NotifyAdHocFailure",
        "NotifyDatasetFailure",
        "NotifyAuthorizationExtractFailure",
    ],
)
def test_every_notification_state_retries_and_catches(state: str) -> None:
    """Pin the second documented asymmetry: a notification retries and catches, and is untimed."""
    block = _state_block(state)
    assert re.search(r"(?m)^\s*Retry\s*=", block) is not None
    assert re.search(r"(?m)^\s*Catch\s*=", block) is not None
    # WHY : Assumptions: the ABSENCE is asserted deliberately. These states are the ones whose
    #   job is to report that something else failed, and the header now says they carry no
    #   TimeoutSeconds; an assertion in only the other direction would let the header drift back
    #   into claiming a bound these states do not have.
    assert re.search(r"(?m)^\s*TimeoutSeconds\s*=", block) is None


def test_the_shared_bounds_bracket_the_whole_representable_range() -> None:
    """Hold the lexical bounds wide enough to admit every four-digit year the callers can send."""
    lower = _local_value("iso_date_lower_bound")
    upper = _local_value("iso_date_upper_bound")
    assert _lexical_order(lower, "2022-07-18") < 0
    assert _lexical_order(upper, "2022-07-18") > 0
    # WHY : Assumptions: the bounds are asserted to REFUSE the two shapes the glob alone admits,
    #   because that is the whole reason they are there: "--" satisfies "*-*-*" and so does a
    #   prose value, and only the bounds separate those from a date.
    assert _lexical_order("--", lower) < 0
    assert _lexical_order("not-a-date", upper) > 0
