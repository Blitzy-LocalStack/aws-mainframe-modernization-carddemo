"""Exercise the corpus-wide fail-closed disclosure policy over every declared record.

Purpose
-------
Execute the first of the two controls that stand between a decoded CardDemo extract and an
operator's log: the allowlist in :mod:`carddemo_migration.copybook.layouts` that decides which
fields a diagnostic may render. It fails in a silent way when it regresses -- a disclosed field
decodes and renders perfectly -- so it cannot be verified by reading the source.

Refactoring Rationale: this module also carried the masking key's strength floor, in seven cases
that were byte-identical duplicates of the seven in ``test_mask_key_material.py``. Both copies
ran, so both passed, and the duplication was invisible in a green run -- which is precisely the
trap it set: the key rule changed, the copy here still asserted that a blank key falls back to
the process key, and a maintainer editing one file would have seen the suite fail for a reason
the file in front of them did not explain. The seven cases live in ``test_mask_key_material.py``
alone now, whose module docstring states the key concern in full. Alternatives Considered:
keeping both copies as independent guards, on the reasoning that the key floor matters enough to
assert twice. Rejected because two copies of one assertion are not two guards -- they are one
guard and one thing that can silently disagree with it, and the disagreement surfaces as a
failure in whichever file the maintainer did not touch.

Assumptions: the disclosure properties are asserted over EVERY field of EVERY record this
module declares, not over a sample. A fail-closed policy earns its keep on the field nobody
thought about, so the test that matters is "every field is either named or withheld"; a test
listing today's known-sensitive names passes for a policy that is merely right today.

Assumptions: the negative-disclosure assertions are made on rendered OUTPUT and never on the
``sensitive`` flag alone. The flag is a marker that performs no masking itself, so a suite that
checked flags would keep passing if the masking function stopped consulting them. Each field is
filled with a sentinel unique to its position, which is what makes a leak attributable to the
field that leaked rather than merely detectable.

Assumptions: the two authorization segments are covered here only by the corpus-wide
properties. Their own allowlist, its name-for-name equality with the Java transcription and its
per-field application are asserted in ``test_authorization_disclosure.py`` and in
``services/common-lib``; duplicating them here would give two places to disagree.
"""

from __future__ import annotations

import ast
import pathlib
from typing import TYPE_CHECKING

import pytest

import carddemo_migration
from carddemo_migration.copybook import layouts

if TYPE_CHECKING:
    # WHY : Assumptions: the builder class is imported for ANNOTATION only, under the
    #   type-checking guard, which is the idiom ``test_mask_key_material`` states in full for the
    #   same fixture. pytest injects the object itself, so the name is needed to document the
    #   parameter and for nothing else; importing it unconditionally would tie collection of this
    #   file to the folder's conftest being importable for no run-time gain.
    from conftest import SentinelRecordBuilder

# Assumptions: the package directory is resolved from the IMPORTED package rather than from this
#   file's own location, so the duplicate-declaration walk below examines the same source tree the
#   rest of the suite imports. Deriving it from `__file__` here would walk the checkout even when
#   the suite is running against an installed distribution, which is the arrangement
#   data-migration/pyproject.toml deliberately sets up.
_PACKAGE_ROOT = pathlib.Path(carddemo_migration.__file__).parent


def _bound_names(node: ast.stmt) -> tuple[str, ...]:
    """Return the module-level names one top-level statement binds.

    Parameters
    ----------
    node : ast.stmt
        One statement from a module body.

    Returns
    -------
    tuple of str
        Each name the statement binds, empty for a statement that binds none.

    Raises
    ------
    None
    """
    # Assumptions: the four binding forms that matter for a module of declarations are
    #   covered -- a function, a class, an annotated assignment and a plain assignment -- and an
    #   import is deliberately NOT, because re-importing a name is already ruff's F811 territory and
    #   a conditional import guarded by a version check is a legitimate double binding.
    if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef):
        return (node.name,)
    if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
        return (node.target.id,)
    if isinstance(node, ast.Assign):
        return tuple(target.id for target in node.targets if isinstance(target, ast.Name))
    return ()


# Assumptions: the population is taken from the module's own namespace walker rather than from
#   a list written here, for the same reason the walker exists: a list in the test would have the
#   identical failure mode as a list in the module, so a record added without being closed would
#   go unnoticed by exactly the test meant to notice it.
_DECLARED_LAYOUTS = layouts._declared_layouts()

# Assumptions: these are the field names the review named as disclosed and prohibited --
#   identifiers, money, balances, merchant text and the national identifier -- listed explicitly
#   rather than matched by a name pattern. A pattern would be a guess: TRAN-TYPE-DESC and
#   TRAN-CAT-TYPE-DESC end in -DESC and ARE disclosable, because they are seeded reference text
#   with no customer linkage, while TRAN-DESC is a purchase description and is not.
_PROHIBITED_NAMES = (
    # Money, limits, balances and cycle totals, on the master and its export projection.
    "ACCT-CURR-BAL",
    "ACCT-CREDIT-LIMIT",
    "ACCT-CASH-CREDIT-LIMIT",
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT",
    "EXP-ACCT-CURR-BAL",
    "EXP-ACCT-CREDIT-LIMIT",
    "EXP-ACCT-CASH-CREDIT-LIMIT",
    "EXP-ACCT-CURR-CYC-CREDIT",
    "EXP-ACCT-CURR-CYC-DEBIT",
    "TRAN-AMT",
    "TRNX-AMT",
    "DALYTRAN-AMT",
    "EXP-TRAN-AMT",
    "TRAN-CAT-BAL",
    # Account identifiers, wherever a record carries one.
    # Refactoring Rationale: these seven were NAMED DISCLOSABLE by
    #   _CORPUS_DISCLOSABLE_FIELDS on the ground that "the published REST contracts already
    #   render an account identifier in full", while every one of them was in fact marked
    #   sensitive at its declaration site -- so the stated policy and the applied policy
    #   disagreed, and the stated one was the one a reader consulted. The repository's
    #   operator-log contract in docs/architecture/observability.md names account identifiers
    #   among the values a diagnostic must omit "not its content, not its length, and not a
    #   digest of it", and a REST path is a different surface with a different audience from a
    #   retained log store. The names were withdrawn from the allowlist, and they are listed
    #   HERE so the property is enforced from the direction that matters: this case fails if any
    #   record ever discloses one, whereas removing a name from an allowlist only stops
    #   admitting it.
    # Assumptions: PA-ACCT-ID is deliberately NOT in this list. The authorization
    #   allowlist names it, that list is read field by field out of layouts.py by
    #   AuthorizationDisclosurePolicyTest, under
    #   services/common-lib/src/test/java/com/carddemo/common/codec/,
    #   and test_master_disclosure.py pins the disagreement between the two policies as a
    #   deliberate fact. Adding it here would break a cross-language literal rather than close a
    #   gap.
    "ACCT-ID",
    "EXP-ACCT-ID",
    "CARD-ACCT-ID",
    "EXP-CARD-ACCT-ID",
    "XREF-ACCT-ID",
    "EXP-XREF-ACCT-ID",
    "TRANCAT-ACCT-ID",
    # Card and transaction identifiers, and the customer identifier.
    "CARD-NUM",
    "XREF-CARD-NUM",
    "TRAN-CARD-NUM",
    "TRNX-CARD-NUM",
    "DALYTRAN-CARD-NUM",
    "EXP-CARD-NUM",
    "EXP-XREF-CARD-NUM",
    "EXP-TRAN-CARD-NUM",
    "TRAN-ID",
    "TRNX-ID",
    "DALYTRAN-ID",
    "EXP-TRAN-ID",
    "CUST-ID",
    "XREF-CUST-ID",
    "EXP-CUST-ID",
    "EXP-XREF-CUST-ID",
    # Merchant identity and location, and free-text purchase descriptions.
    "TRAN-MERCHANT-ID",
    "TRAN-MERCHANT-NAME",
    "TRAN-MERCHANT-CITY",
    "TRAN-MERCHANT-ZIP",
    "TRNX-MERCHANT-ID",
    "TRNX-MERCHANT-NAME",
    "TRNX-MERCHANT-CITY",
    "TRNX-MERCHANT-ZIP",
    "DALYTRAN-MERCHANT-ID",
    "DALYTRAN-MERCHANT-NAME",
    "DALYTRAN-MERCHANT-CITY",
    "DALYTRAN-MERCHANT-ZIP",
    "EXP-TRAN-MERCHANT-ID",
    "EXP-TRAN-MERCHANT-NAME",
    "EXP-TRAN-MERCHANT-CITY",
    "EXP-TRAN-MERCHANT-ZIP",
    "TRAN-DESC",
    "TRNX-DESC",
    "DALYTRAN-DESC",
    "EXP-TRAN-DESC",
    # Cardholder identity, credentials and the two encrypted identifiers.
    "CARD-CVV-CD",
    "CARD-EMBOSSED-NAME",
    "CARD-EXPIRAION-DATE",
    "EXP-CARD-CVV-CD",
    "EXP-CARD-EMBOSSED-NAME",
    "EXP-CARD-EXPIRAION-DATE",
    "CUST-SSN",
    "EXP-CUST-SSN",
    "CUST-GOVT-ISSUED-ID",
    "EXP-CUST-GOVT-ISSUED-ID",
    "CUST-DOB-YYYY-MM-DD",
    "EXP-CUST-DOB-YYYY-MM-DD",
    "CUST-FICO-CREDIT-SCORE",
    "EXP-CUST-FICO-CREDIT-SCORE",
    "ACCT-ADDR-ZIP",
    "EXP-ACCT-ADDR-ZIP",
    "CUST-ADDR-ZIP",
    "EXP-CUST-ADDR-ZIP",
    "SEC-USR-PWD",
)

# Assumptions: each field is filled with ONE character used by no other field of the same
#   record, and the alphabet deliberately excludes every character a redaction can emit -- the
#   hexadecimal digits, the angle brackets, the asterisk and the space. That exclusion is what
#   makes "this field's span appears nowhere in the masked record" a falsifiable statement: with
#   a multi-character marker, a two-byte field's marker can occur inside a wider neighbour's
#   repetition, and the assertion then fails on a record that leaked nothing.
_SENTINEL_ALPHABET = "GHIJKLMNOPQRSTUVWXYZghijklmnopqrstuvwxyz"


def _sentinel_record(layout: layouts.RecordSpec) -> str:
    """Build a record of declared length whose every field carries a unique sentinel.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record whose geometry the sentinels are laid out against.

    Returns
    -------
    str
        Exactly ``layout.reclen`` characters, each field's span filled with a character
        unique to that field's ordinal position within the record.

    Raises
    ------
    AssertionError
        If the record declares more fields than the sentinel alphabet can distinguish.
    """
    # Assumptions: the sentinel is chosen by the field's ORDINAL and not by its name,
    #   because a name-derived marker would let a leak of one field be mistaken for a leak of a
    #   similarly named one -- TRAN-MERCHANT-ZIP and TRNX-MERCHANT-ZIP share a suffix. An ordinal
    #   is unique within the record by construction.
    assert len(layout.fields) <= len(_SENTINEL_ALPHABET), (
        f"record {layout.name} declares {len(layout.fields)} fields, more than the sentinel"
        " alphabet can keep distinct"
    )
    return "".join(
        _SENTINEL_ALPHABET[ordinal] * field.length for ordinal, field in enumerate(layout.fields)
    )


@pytest.mark.parametrize("layout", _DECLARED_LAYOUTS, ids=lambda spec: spec.name)
def test_every_field_of_every_record_is_named_or_withheld(
    layout: layouts.RecordSpec,
) -> None:
    """Assert each field is admitted by an allowlist or else marked sensitive.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    admitted = layouts._CORPUS_DISCLOSABLE_FIELDS | layouts._AUTHORIZATION_DISCLOSABLE_FIELDS
    assert layout.fields, f"record {layout.name} declares no fields at all"
    for field in layout.fields:
        if field.sensitive:
            continue
        assert field.name in admitted, (
            f"field {field.name} of {layout.name} is rendered verbatim by every diagnostic but"
            " no allowlist admits it"
        )


def test_the_import_time_audit_reports_no_unnamed_disclosure() -> None:
    """Assert the module's own import-time audit found nothing to refuse.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: this re-runs the audit over the FULLY imported module rather than
    #   trusting the constant the module computed while importing. A record declared below the
    #   audit's own call site would escape that call, and re-running here is what closes the
    #   gap -- so the two assertions are not redundant.
    assert layouts._UNNAMED_DISCLOSURES == ()
    assert layouts._unnamed_disclosures(layouts._declared_layouts()) == ()


def test_the_audit_detects_a_record_left_unclosed() -> None:
    """Assert the audit reports a disclosable field no allowlist admits.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the guard is exercised against a record built HERE rather than by
    #   editing one of the module's own, because "the audit returned nothing" is only evidence
    #   that the corpus is closed if the audit returns something when it is not. Without this
    #   case an audit that had been reduced to `return ()` would pass every other test in this
    #   file.
    unclosed = layouts.RecordSpec(
        "SYNTHETIC-UNCLOSED",
        20,
        4,
        0,
        (
            layouts.text("SYNTHETIC-KEY", 0, 4),
            layouts.text("FILLER", 4, 16),
        ),
    ).validate_geometry()
    findings = layouts._unnamed_disclosures((unclosed,))
    assert findings == ("SYNTHETIC-UNCLOSED.SYNTHETIC-KEY",), findings

    # Assumptions: the same record passed through the closure helper reports nothing,
    #   which is what proves the finding above was the POLICY speaking and not the audit
    #   objecting to a synthetic record on some unrelated ground.
    closed = layouts.RecordSpec(
        "SYNTHETIC-CLOSED",
        20,
        4,
        0,
        layouts._closed(
            (
                layouts.text("SYNTHETIC-KEY", 0, 4),
                layouts.text("FILLER", 4, 16),
            )
        ),
    ).validate_geometry()
    assert layouts._unnamed_disclosures((closed,)) == ()


def test_the_audit_walks_every_record_the_module_declares() -> None:
    """Assert the audited population is every declared record and not a subset.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the count is asserted against the three populations that make it up
    #   rather than against a bare number, so a record moved between them still reconciles while
    #   a record dropped from the walk does not. The registry holds the flat datasets; the export
    #   projections and the two IMS segments are declared outside it.
    audited = {spec.name for spec in _DECLARED_LAYOUTS}
    assert set(layouts.LAYOUTS) <= audited
    for spec in (
        layouts.EXPORT_HEADER_LAYOUT,
        layouts.EXPORT_ACCOUNT_LAYOUT,
        layouts.EXPORT_CARD_LAYOUT,
        layouts.EXPORT_CARD_XREF_LAYOUT,
        layouts.EXPORT_CUSTOMER_LAYOUT,
        layouts.EXPORT_TRANSACTION_LAYOUT,
        layouts.PENDING_AUTH_SUMMARY_LAYOUT,
        layouts.PENDING_AUTH_DETAIL_LAYOUT,
    ):
        assert spec.name in audited, f"{spec.name} is declared but never audited"
    assert len(audited) == len(_DECLARED_LAYOUTS), "two records share one name"


@pytest.mark.parametrize("name", _PROHIBITED_NAMES)
def test_a_prohibited_field_is_withheld_wherever_it_is_declared(name: str) -> None:
    """Assert a named prohibited field is marked sensitive in every record declaring it.

    Parameters
    ----------
    name : str
        The copybook field name the disclosure policy must withhold.

    Returns
    -------
    None
        The assertion is the result.
    """
    declaring = [spec for spec in _DECLARED_LAYOUTS if any(f.name == name for f in spec.fields)]
    # Assumptions: the case fails when NO record declares the name, rather than passing
    #   vacuously. A prohibited name that has been renamed or removed would otherwise leave this
    #   case green while asserting nothing at all, which is the failure mode a hand-kept list of
    #   names is most prone to.
    assert declaring, f"no record declares {name}; the prohibited list is stale"
    for spec in declaring:
        assert spec.field(name).sensitive, (
            f"{name} must be withheld from diagnostics but {spec.name} discloses it"
        )


def test_the_allowlist_names_no_field_the_corpus_does_not_declare() -> None:
    """Assert every admitted name belongs to a real field of a real record.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: a stale allowlist entry is not merely untidy. It is a standing
    #   permission for whatever field is declared under that name next, granted before anybody
    #   looks at what that field holds, which is the exact reverse of the decision order a
    #   fail-closed policy exists to impose.
    declared = {field.name for spec in _DECLARED_LAYOUTS for field in spec.fields}
    stale = layouts._CORPUS_DISCLOSABLE_FIELDS - declared
    assert stale == set(), f"the corpus allowlist names undeclared fields: {sorted(stale)}"


def test_every_admitted_field_is_actually_disclosed() -> None:
    """Assert the allowlists describe this module's behaviour and not merely its intention.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: this is the OTHER direction of the audit, and it is here
    #   because its absence hid a real contradiction. Seven account identifiers -- ACCT-ID,
    #   CARD-ACCT-ID, XREF-ACCT-ID, TRANCAT-ACCT-ID, EXP-ACCT-ID, EXP-CARD-ACCT-ID and
    #   EXP-XREF-ACCT-ID -- were named as disclosable and were sensitive at every occurrence
    #   anyway, because `_close_disclosure` can only ADD sensitivity: four had a `sensitive_*`
    #   factory at their declaration site and three belong to records closed by the narrower
    #   `_close_master_disclosure`. Every check that existed passed, because each looked only for
    #   a field disclosed WITHOUT being named. The consequence was not a leak but something
    #   harder to find: a reader auditing the corpus by reading the allowlist got the wrong
    #   answer about seven fields.
    # Assumptions: the union of the two allowlists is used, matching both audits in the
    #   module, and it is sound here because no name occurs in both a corpus-closed and an
    #   authorization-closed record -- so a name admitted by either list must be disclosed
    #   wherever it occurs.
    admitted = layouts._CORPUS_DISCLOSABLE_FIELDS | layouts._AUTHORIZATION_DISCLOSABLE_FIELDS
    ineffective = sorted(
        f"{spec.name}.{field.name}"
        for spec in _DECLARED_LAYOUTS
        for field in spec.fields
        if field.sensitive and field.name in admitted
    )
    assert ineffective == [], (
        "these fields are named as disclosable and withheld anyway, so the allowlist describes a"
        f" policy the module does not apply: {ineffective}"
    )


def test_the_import_time_audit_reports_no_ineffective_admission() -> None:
    """Assert the module's own bidirectional audit agrees with the assertion above.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the module's audit and this suite's independent recomputation are both
    #   asserted, for the same reason the one-directional pair are: the module's version runs at
    #   import and would be skipped entirely by anything that stubbed it out, while this suite's
    #   version cannot protect a deployment that never runs the suite. Each covers the other's
    #   blind spot.
    assert layouts._INEFFECTIVE_ADMISSIONS == ()


def test_the_layouts_module_binds_no_top_level_name_twice() -> None:
    """Assert no module-level declaration in the layouts module is silently shadowed by a twin.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: `_MASK_HMAC_KEY_MIN_BYTES` and `_mask_hmac_key` were declared
    #   TWICE in that module, roughly 165 lines apart, with byte-identical bodies. Nothing behaved
    #   differently, and that is precisely why it needed a test: Python binds a module-level name
    #   by executing statements in order, so the second declaration silently replaced the first and
    #   the first remained readable, reviewable and dead. A future strengthening of the key rule
    #   applied to the first copy would have compiled, passed review and had no effect on the one
    #   control that decides whether a redaction tag is confirmable.
    # Assumptions: ruff cannot report this and the gap is in the RULE rather than in the
    #   configuration -- F811 covers redefinition of an UNUSED name, and both of these were used.
    #   So the check is written here against the AST rather than expected from the linter.
    # Trade-offs: the whole package is walked rather than only the layouts module, because a
    #   duplicate is a hazard wherever it occurs and naming one module would leave the other
    #   thirty-odd unprotected for no saving. Only TOP-LEVEL statements are examined: a name
    #   rebound inside a function is ordinary control flow, and a method redefined in a class body
    #   is a separate hazard this check deliberately does not claim to cover.
    duplicates: dict[str, list[str]] = {}
    for module_path in sorted(_PACKAGE_ROOT.rglob("*.py")):
        bindings: dict[str, list[int]] = {}
        for node in ast.parse(module_path.read_text(encoding="utf-8")).body:
            for name in _bound_names(node):
                bindings.setdefault(name, []).append(node.lineno)
        for name, lines in bindings.items():
            if len(lines) > 1:
                duplicates[f"{module_path.name}:{name}"] = [str(line) for line in lines]
    assert duplicates == {}, (
        "these module-level names are bound more than once, so every declaration but the last is"
        f" dead code that still reads as live: {duplicates}"
    )


@pytest.mark.parametrize("layout", _DECLARED_LAYOUTS, ids=lambda spec: spec.name)
def test_a_masked_record_carries_no_withheld_field_s_content(
    layout: layouts.RecordSpec,
    sentinel_record_builder: SentinelRecordBuilder,
) -> None:
    """Assert masking removes every withheld field's characters and keeps every admitted one.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor under test.
    sentinel_record_builder : SentinelRecordBuilder
        Suite-wide builder for a record whose every field carries a position-unique sentinel.

    Returns
    -------
    None
        The assertion is the result.
    """
    raw = sentinel_record_builder.build(layout)
    assert len(raw) == layout.reclen
    masked = layouts.mask_record(raw, layout)
    assert len(masked) == layout.reclen, "a masked record must still tile the record it describes"

    for field in layout.fields:
        span = raw[field.start : field.end]
        rendered = masked[field.start : field.end]
        if not field.sensitive:
            assert rendered == span, f"{field.name} is admitted and must render verbatim"
            continue
        if field.name in layouts._LAST4_REVEAL:
            # Assumptions: the four card-number names keep the documented last-four
            #   concession, so their span is asserted to be masked EXCEPT its final four
            #   characters rather than absent. Asserting absence here would contradict the
            #   policy, and asserting nothing would leave the concession's width unchecked --
            #   which is what would let it widen to eight unnoticed.
            assert rendered.endswith(span[-4:])
            assert rendered[:-4] == "*" * (field.length - 4)
            continue
        assert rendered != span, f"{field.name} is withheld and must not render verbatim"
        assert span not in masked, f"{field.name}'s content reappears elsewhere in the rendering"


def test_the_customer_national_identifier_is_never_revealed_in_part(
    sentinel_record_builder: SentinelRecordBuilder,
) -> None:
    """Assert neither national-identifier field reveals its trailing characters.

    Parameters
    ----------
    sentinel_record_builder : SentinelRecordBuilder
        Suite-wide builder for a record whose every field carries a position-unique sentinel.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: CUST-SSN was in the last-four concession and is removed, so
    #   this case pins the removal. It asserts the rendered OUTPUT and not just the membership,
    #   because membership alone would keep passing if the masking function grew a second route
    #   to a partial reveal.
    for spec, name in (
        (layouts.CUSTOMER_LAYOUT, "CUST-SSN"),
        (layouts.EXPORT_CUSTOMER_LAYOUT, "EXP-CUST-SSN"),
    ):
        assert name not in layouts._LAST4_REVEAL
        field = spec.field(name)
        raw = sentinel_record_builder.build(spec)
        span = raw[field.start : field.end]
        rendered = layouts.mask_record(raw, spec)[field.start : field.end]
        assert span[-4:] not in rendered, f"{name} must not reveal its trailing characters"
        assert "*" not in rendered, f"{name} must not render the partial-reveal marker"


def test_the_last_four_concession_covers_card_numbers_only() -> None:
    """Assert the partial-reveal set is exactly the four card-number fields.

    Returns
    -------
    None
        The assertion is the result.
    """
    assert layouts._LAST4_REVEAL == frozenset(
        {"CARD-NUM", "XREF-CARD-NUM", "TRAN-CARD-NUM", "DALYTRAN-CARD-NUM"}
    )
    # Assumptions: every member is additionally required to be a declared, withheld
    #   sixteen-character field, so the set cannot be satisfied by a name that no longer exists
    #   or by one whose width has changed -- the width is what bounds how much four revealed
    #   characters disclose.
    for name in layouts._LAST4_REVEAL:
        declaring = [s for s in _DECLARED_LAYOUTS if any(f.name == name for f in s.fields)]
        assert declaring, f"{name} is granted a partial reveal but no record declares it"
        for spec in declaring:
            field = spec.field(name)
            assert field.sensitive and field.length == 16
