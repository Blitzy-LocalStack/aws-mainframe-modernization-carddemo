"""Collected integration tests for the MQ authorization producer stub.

Purpose
-------
Exercise ``tests/mocks/mq_request_stub.py`` -- the in-process stand-in for the
external MQ authorization producer that the AWS CardDemo repository deliberately
does NOT ship (AAP sections 0.3.1 / 0.4.4). The QA finding
``F-MQ-STUB-UNTESTED`` observed that the stub was delivered with ZERO collected
test coverage: no test imported or exercised it, so its request/response
contract was unverified and it contributed nothing to the suite's asserted
behavior. This module closes that gap by importing the stub and asserting on its
complete, documented contract:

* the request/response happy path (approvals) and the ``approved`` property;
* every failure-mapping branch (response/reason codes 14/1400, 05/0500,
  54/5400, 51/5100) via both the explicit canned-card table and the rule-based
  path (expiry sentinel, over-ceiling);
* the money boundaries (exactly at vs. one cent over the approval ceiling, zero,
  and the ``+9(10).99`` magnitude limit);
* malformed messages -- ``float``/``bool``/negative/NaN/over-precision/too-large
  amounts and copybook width/domain violations -- which must RAISE, never
  silently approve (finding MA-14);
* determinism/idempotence (the same request yields byte-identical responses and
  a reproducible authorization id code, with no salted ``hash()``); and
* the :class:`MqRequestStub` queue semantics (send-and-record, ordering, reset,
  and the "do not log a raised exchange" property).

WHY this file lives under ``tests/integration`` (Trade-off)
-----------------------------------------------------------
The AAP maps the MQ stub to the optional-module INTEGRATION layer, and the
finding explicitly asks for "collected INTEGRATION tests". The tests are pure
Python (no compiled COBOL, no workspace), so they are fast and always run --
which is exactly why they are safe to keep in the always-collected integration
layer and never hit the suite's no-hidden-skips gate. Marking the whole module
with a single ``pytestmark`` mirrors the convention already used by the other
``tests/integration/*.py`` modules.

WHY assert on the stub's real contract, not an invented one (Assumption)
------------------------------------------------------------------------
Every expected value below is taken verbatim from the stub's own docstrings and
named constants (imported as symbols, never hard-coded magic strings), so these
tests encode the specification the stub claims to implement and will fail if the
stub drifts from it.
"""

from decimal import Decimal

import pytest

# WHY (import path): tests/ has no __init__.py; tests/conftest.py prepends the
# repository root to sys.path at import time, so the ``tests.mocks.*`` namespace
# package resolves identically here to the ``from tests.helpers... import ...``
# imports every other integration module already relies on. This matches the AAP
# convention ``from tests.mocks import ...`` and keeps a single import style.
from tests.mocks.mq_request_stub import (
    AuthorizationRequest,
    AuthorizationResponse,
    MqRequestStub,
    authorize,
    normalize_amount,
    validate_request,
    CANNED_RESPONSES,
    STUB_APPROVAL_CEILING,
    RESP_APPROVED,
    RESP_DECLINE_GENERIC,
    RESP_INVALID_CARD,
    RESP_INSUFFICIENT,
    RESP_EXPIRED_CARD,
    REASON_OK,
    REASON_INVALID_CARD,
    REASON_OVERLIMIT,
    REASON_EXPIRED,
    REASON_DENYLIST,
)

# WHY: a single module-level marker selects the whole file under
# ``pytest -m integration`` (the runner's filter) without decorating each test,
# exactly as tests/integration/test_cbtrn02c_posting.py etc. do.
pytestmark = pytest.mark.integration

# ---------------------------------------------------------------------------
# Deterministic canonical inputs.
# WHY (Assumption): a 16-digit card that is NOT present in CANNED_RESPONSES and
# carries no expiry sentinel is required to reach the RULE-BASED branches
# (expiry step 3 / ceiling step 4); the canned table (step 2) short-circuits
# those branches for the four canonical cards, so a canned card cannot be used
# to test the ceiling. "4222..." is chosen precisely because it is un-canned.
# ---------------------------------------------------------------------------
_RULE_CARD = "4222222222222222"          # 16 digits, not canned, no expiry sentinel
_APPROVE_CARD = "4111111111111111"       # canned RESP_APPROVED
_DECLINE_CARD = "4000000000000002"       # canned RESP_DECLINE_GENERIC
_EXPIRED_CARD = "4000000000000069"       # canned RESP_EXPIRED_CARD
_INSUFF_CARD = "4000000000000119"        # canned RESP_INSUFFICIENT


def _req(card=_RULE_CARD, amt=Decimal("10.00"), txn="TXN0000000001", **kw):
    """Build an :class:`AuthorizationRequest` with sensible test defaults.

    Parameters
    ----------
    card : str
        ``card_num`` for the request (default: the un-canned rule-path card).
    amt : Decimal | int | str
        ``transaction_amt`` (default: a small, valid, under-ceiling amount).
    txn : str
        ``transaction_id`` (default: a fixed, width-legal id).
    **kw
        Any other ``AuthorizationRequest`` field overrides (e.g.
        ``card_expiry_date``, ``merchant_id``, ``processing_code``).

    Returns
    -------
    AuthorizationRequest
        The constructed immutable request.

    Raises
    ------
    None
    """
    # WHY (Trade-off): a tiny local factory keeps each test focused on the ONE
    # field it varies, instead of repeating the full constructor and obscuring
    # the property under test.
    return AuthorizationRequest(card_num=card, transaction_amt=amt, transaction_id=txn, **kw)


# ===========================================================================
# Request/response happy path + approved property
# ===========================================================================
def test_default_card_approved():
    """A valid, under-ceiling, non-canned request is approved (code 00/0000).

    Returns
    -------
    None

    Raises
    ------
    None
    """
    resp = authorize(_req(amt=Decimal("10.00")))
    assert isinstance(resp, AuthorizationResponse)
    assert resp.approved is True
    assert resp.auth_resp_code == RESP_APPROVED
    assert resp.auth_resp_reason == REASON_OK
    # WHY: the approved amount must be the VALIDATED two-decimal echo of the
    # request, not a re-computed or rounded value.
    assert resp.approved_amt == Decimal("10.00")
    assert resp.card_num == _RULE_CARD
    assert resp.transaction_id == "TXN0000000001"


def test_approved_property_reflects_response_code():
    """``AuthorizationResponse.approved`` is True iff the code is ``"00"``.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    approved = AuthorizationResponse(
        card_num=_RULE_CARD, transaction_id="T", auth_id_code="123456",
        auth_resp_code=RESP_APPROVED, auth_resp_reason=REASON_OK,
        approved_amt=Decimal("1.00"),
    )
    declined = AuthorizationResponse(
        card_num=_RULE_CARD, transaction_id="T", auth_id_code="000000",
        auth_resp_code=RESP_INVALID_CARD, auth_resp_reason=REASON_INVALID_CARD,
        approved_amt=Decimal("0.00"),
    )
    assert approved.approved is True
    # WHY: any non-"00" code is a decline; the property must not treat a declined
    # response as approved even though both share an approved_amt shape.
    assert declined.approved is False


# ===========================================================================
# Failure mapping -- canned card table (step 2)
# ===========================================================================
@pytest.mark.parametrize(
    "card, exp_code, exp_reason, exp_approved",
    [
        (_APPROVE_CARD, RESP_APPROVED, REASON_OK, True),
        (_DECLINE_CARD, RESP_DECLINE_GENERIC, REASON_DENYLIST, False),
        (_EXPIRED_CARD, RESP_EXPIRED_CARD, REASON_EXPIRED, False),
        (_INSUFF_CARD, RESP_INSUFFICIENT, REASON_OVERLIMIT, False),
    ],
)
def test_canned_card_decisions(card, exp_code, exp_reason, exp_approved):
    """Each canonical canned card yields its mapped code/reason deterministically.

    Parameters
    ----------
    card : str
        One of the four canonical cards in ``CANNED_RESPONSES``.
    exp_code : str
        Expected ``auth_resp_code``.
    exp_reason : str
        Expected ``auth_resp_reason``.
    exp_approved : bool
        Expected value of the ``approved`` property.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    resp = authorize(_req(card=card, amt=Decimal("10.00")))
    assert resp.auth_resp_code == exp_code
    assert resp.auth_resp_reason == exp_reason
    assert resp.approved is exp_approved
    # WHY: a decline must zero the auth id and approved amount; an approval must
    # populate a derived id and echo the amount. Asserting the full shape (not
    # just the code) is the finding's "assertion density" requirement.
    if exp_approved:
        assert resp.approved_amt == Decimal("10.00")
        assert resp.auth_id_code != "000000"
    else:
        assert resp.approved_amt == Decimal("0.00")
        assert resp.auth_id_code == "000000"


def test_canned_table_maps_documented_symbols():
    """The canned table maps the four canonical cards to the exported symbols.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY: this pins the stub's public constant table so a silent edit to a
    # canned mapping (which would change many tests' meaning) is caught here.
    assert CANNED_RESPONSES[_APPROVE_CARD] == RESP_APPROVED
    assert CANNED_RESPONSES[_DECLINE_CARD] == RESP_DECLINE_GENERIC
    assert CANNED_RESPONSES[_EXPIRED_CARD] == RESP_EXPIRED_CARD
    assert CANNED_RESPONSES[_INSUFF_CARD] == RESP_INSUFFICIENT


def test_canned_approval_overrides_ceiling_and_expiry():
    """A canned-approve card approves even over-ceiling and with an expiry sentinel.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY (precedence, Assumption): the documented decision order puts the canned
    # table (step 2) BEFORE the expiry sentinel (step 3) and the ceiling (step 4).
    # An amount far over the ceiling plus a "0000" expiry must therefore STILL
    # approve for a canned-approve card -- proving the precedence is honored and
    # that this is a pure function of card identity for canned cards.
    over_ceiling = STUB_APPROVAL_CEILING + Decimal("1000.00")
    resp = authorize(_req(card=_APPROVE_CARD, amt=over_ceiling, card_expiry_date="0000"))
    assert resp.approved is True
    assert resp.approved_amt == over_ceiling


# ===========================================================================
# Failure mapping -- rule-based branches (steps 1, 3, 4)
# ===========================================================================
@pytest.mark.parametrize("bad_card", ["", "4111", "411111111111111", "411111111111111X"])
def test_invalid_card_number_declined_14(bad_card):
    """A width-legal but non-16-digit card is declined 14/1400 (step 1).

    Parameters
    ----------
    bad_card : str
        A card value that FITS the ``X(16)`` slot (len <= 16) but is not exactly
        16 numeric digits, so it survives width validation and reaches the
        format-edit decline rather than raising.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    resp = authorize(_req(card=bad_card, amt=Decimal("10.00")))
    assert resp.auth_resp_code == RESP_INVALID_CARD
    assert resp.auth_resp_reason == REASON_INVALID_CARD
    assert resp.approved is False
    assert resp.approved_amt == Decimal("0.00")
    assert resp.auth_id_code == "000000"


def test_expiry_sentinel_declined_54_rule_path():
    """A non-canned card with expiry ``"0000"`` is declined 54/5400 (step 3).

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY: use the un-canned card so the canned table does not short-circuit the
    # rule-based expiry branch we are trying to exercise.
    resp = authorize(_req(card=_RULE_CARD, amt=Decimal("10.00"), card_expiry_date="0000"))
    assert resp.auth_resp_code == RESP_EXPIRED_CARD
    assert resp.auth_resp_reason == REASON_EXPIRED
    assert resp.approved is False


# ===========================================================================
# Money boundaries -- the exact / one-cent-over ceiling edge (step 4)
# ===========================================================================
@pytest.mark.parametrize(
    "amount, should_approve",
    [
        (Decimal("4999.99"), True),                     # under ceiling
        (STUB_APPROVAL_CEILING, True),                  # EXACTLY at ceiling -> approve
        (STUB_APPROVAL_CEILING + Decimal("0.01"), False),  # one cent over -> decline 51
    ],
)
def test_ceiling_boundary(amount, should_approve):
    """The approval ceiling is inclusive: exactly-at approves, one-cent-over declines.

    Parameters
    ----------
    amount : Decimal
        The transaction amount straddling the fixed approval ceiling.
    should_approve : bool
        Whether the amount is expected to approve.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY (Financial Validation, MA-14): the ceiling comparison is exact-Decimal,
    # so the one-cent boundary must land deterministically. This is the money
    # edge case the finding calls out; a float ceiling could mis-rank it.
    resp = authorize(_req(card=_RULE_CARD, amt=amount))
    assert resp.approved is should_approve
    if should_approve:
        assert resp.approved_amt == amount
    else:
        assert resp.auth_resp_code == RESP_INSUFFICIENT
        assert resp.auth_resp_reason == REASON_OVERLIMIT


def test_zero_amount_is_approved():
    """A zero amount is valid and approves (nonnegative, under ceiling).

    Returns
    -------
    None

    Raises
    ------
    None
    """
    resp = authorize(_req(card=_RULE_CARD, amt=Decimal("0.00")))
    # WHY: a declined response ALSO carries approved_amt 0.00, so the approval is
    # asserted on the CODE, and the amount echo confirmed separately.
    assert resp.approved is True
    assert resp.approved_amt == Decimal("0.00")


# ===========================================================================
# normalize_amount -- accepted money forms (public API)
# ===========================================================================
@pytest.mark.parametrize(
    "value, expected",
    [
        (100, Decimal("100.00")),          # int == whole dollars
        (Decimal("12.50"), Decimal("12.50")),
        ("12.5", Decimal("12.50")),        # str parsed, PADDED to cents (not rounded)
        (Decimal("3.4"), Decimal("3.40")),  # single decimal -> pads
        (Decimal("0"), Decimal("0.00")),
    ],
)
def test_normalize_amount_accepts_valid_money(value, expected):
    """``normalize_amount`` quantizes accepted forms to exact two-decimal cents.

    Parameters
    ----------
    value : Decimal | int | str
        A valid money value in one of the accepted forms.
    expected : Decimal
        The expected two-decimal normalized result.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    result = normalize_amount(value)
    assert result == expected
    # WHY: the contract guarantees exactly two fractional digits; assert the
    # scale, not just numeric equality, so "12.5" vs "12.50" is caught.
    assert result.as_tuple().exponent == -2


# ===========================================================================
# Malformed messages -- amounts that MUST raise (finding MA-14)
# ===========================================================================
@pytest.mark.parametrize(
    "value, exc",
    [
        (100.0, TypeError),                 # binary float rejected
        (True, TypeError),                  # bool (int subclass) rejected before int branch
        (Decimal("-1.00"), ValueError),     # negative
        (Decimal("1.001"), ValueError),     # over-precision (> 2 dp)
        (Decimal("NaN"), ValueError),       # non-finite
        (Decimal("Infinity"), ValueError),  # non-finite
        (Decimal("10000000000.00"), ValueError),  # 11 integer digits > +9(10).99
        ("not-a-number", ValueError),       # unparseable string
    ],
)
def test_normalize_amount_rejects_malformed(value, exc):
    """``normalize_amount`` raises on every documented invalid money form.

    Parameters
    ----------
    value : object
        An invalid money value (wrong type, negative, non-finite, over-precise,
        too large, or unparseable).
    exc : type[Exception]
        The exception class the stub documents for this failure mode.

    Returns
    -------
    None

    Raises
    ------
    None
        The expected exception is captured by ``pytest.raises``; the test itself
        does not propagate it.
    """
    # WHY (MA-14): these are the exact silent-approval holes the finding targets
    # (a float/NaN/negative/over-precise amount must never reach a decision). We
    # assert they RAISE at the money boundary rather than being coerced.
    with pytest.raises(exc):
        normalize_amount(value)


def test_authorize_rejects_malformed_amount_before_any_decision():
    """A malformed amount raises from ``authorize`` even for a canned-approve card.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY (MA-14 security property): copybook validation is step 0, BEFORE the
    # canned table (step 2). So even the "always approve" card must RAISE on a
    # float amount -- proving an invalid amount can never slip through to an
    # approval via the canned short-circuit.
    with pytest.raises(TypeError):
        authorize(AuthorizationRequest(card_num=_APPROVE_CARD, transaction_amt=100.0))


# ===========================================================================
# Malformed messages -- copybook width/domain violations (validate_request)
# ===========================================================================
@pytest.mark.parametrize(
    "kwargs, exc",
    [
        ({"card_num": "4" * 17}, ValueError),                       # card_num > X(16)
        ({"card_num": _RULE_CARD, "merchant_id": "M" * 16}, ValueError),  # merchant > X(15)
        ({"card_num": _RULE_CARD, "transaction_id": "T" * 16}, ValueError),  # txn id > X(15)
        ({"card_num": _RULE_CARD, "card_expiry_date": "12345"}, ValueError),  # expiry > X(4)
        ({"card_num": _RULE_CARD, "processing_code": "12x456"}, ValueError),  # 9(6) non-numeric
        ({"card_num": _RULE_CARD, "processing_code": "1234567"}, ValueError),  # 9(6) too wide
        ({"card_num": _RULE_CARD, "processing_code": ""}, ValueError),  # 9(6) empty
    ],
)
def test_validate_request_rejects_width_and_domain_violations(kwargs, exc):
    """Over-width / non-numeric copybook fields raise before any decision.

    Parameters
    ----------
    kwargs : dict
        Field overrides that violate one copybook width/domain rule while
        keeping the amount valid, so the failure is attributable to that field.
    exc : type[Exception]
        The expected exception class.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY: exercise via BOTH validate_request and authorize so the guarantee
    # holds at the public entry point too (authorize calls validate_request at
    # step 0). A valid amount is supplied so the raise is unambiguously the field.
    req = AuthorizationRequest(transaction_amt=Decimal("10.00"), **kwargs)
    with pytest.raises(exc):
        validate_request(req)
    with pytest.raises(exc):
        authorize(req)


@pytest.mark.parametrize("bad_request", [None, "4111111111111111", 42, {"card_num": "x"}])
def test_authorize_rejects_wrong_type(bad_request):
    """``authorize`` raises TypeError on a non-AuthorizationRequest argument.

    Parameters
    ----------
    bad_request : object
        A value that is not an :class:`AuthorizationRequest`.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY: a wrong type is programmer misuse (a test bug), not a business
    # decline; it must fail loudly rather than be silently "declined".
    with pytest.raises(TypeError):
        authorize(bad_request)


# ===========================================================================
# Determinism / idempotence
# ===========================================================================
def test_authorize_is_deterministic():
    """The same request authorized twice yields byte-identical responses.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    req = _req(card=_RULE_CARD, amt=Decimal("42.42"), txn="TXN0000000042")
    first = authorize(req)
    second = authorize(req)
    # WHY: AuthorizationResponse is a frozen dataclass, so == compares every
    # field; equality here proves the auth id code (and all else) is reproducible
    # across calls -- i.e. no salted built-in hash() leaked into the derivation.
    assert first == second
    assert first.auth_id_code == second.auth_id_code


def test_auth_id_code_is_six_numeric_digits_when_approved():
    """An approved response carries a 6-digit numeric authorization id code.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    resp = authorize(_req(card=_RULE_CARD, amt=Decimal("10.00")))
    assert resp.approved is True
    assert len(resp.auth_id_code) == 6
    assert resp.auth_id_code.isdigit()


# ===========================================================================
# MqRequestStub -- queue/exchange semantics
# ===========================================================================
def test_stub_send_request_returns_and_records():
    """``send_request`` returns the response AND records the exchange.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    stub = MqRequestStub()
    req = _req(card=_RULE_CARD, amt=Decimal("10.00"))
    resp = stub.send_request(req)
    # WHY: the stub models an MQ PUT/GET; a test must be able to assert BOTH the
    # returned response and what was "put on the queue".
    assert resp.approved is True
    assert stub.requests == [req]
    assert stub.responses == [resp]


def test_stub_matches_module_authorize():
    """``send_request`` returns exactly what the module-level ``authorize`` returns.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    stub = MqRequestStub()
    req = _req(card=_INSUFF_CARD, amt=Decimal("10.00"))
    # WHY: the object entry point and the functional entry point must agree, so a
    # test may use either interchangeably.
    assert stub.send_request(req) == authorize(req)


def test_stub_preserves_request_order():
    """The stub log preserves the order of submitted requests (queue FIFO).

    Returns
    -------
    None

    Raises
    ------
    None
    """
    stub = MqRequestStub()
    reqs = [
        _req(card=_APPROVE_CARD, amt=Decimal("1.00"), txn="TXN1"),
        _req(card=_DECLINE_CARD, amt=Decimal("2.00"), txn="TXN2"),
        _req(card=_RULE_CARD, amt=Decimal("3.00"), txn="TXN3"),
    ]
    for r in reqs:
        stub.send_request(r)
    assert stub.requests == reqs
    assert len(stub.responses) == 3
    # WHY: the i-th recorded response must correspond to the i-th request.
    assert stub.responses[0].approved is True     # canned approve
    assert stub.responses[1].auth_resp_code == RESP_DECLINE_GENERIC
    assert stub.responses[2].approved is True     # rule-path approve


def test_stub_reset_clears_log():
    """``reset`` empties the exchange log so an instance can be reused.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    stub = MqRequestStub()
    stub.send_request(_req())
    assert stub.requests and stub.responses
    stub.reset()
    assert stub.requests == []
    assert stub.responses == []


def test_stub_does_not_log_a_raised_exchange():
    """A request that fails validation is NOT recorded (no half-logged exchange).

    Returns
    -------
    None

    Raises
    ------
    None
    """
    stub = MqRequestStub()
    # WHY: the stub records AFTER a successful authorize(); a raised call must
    # leave the log empty so downstream sequence assertions are not corrupted by
    # a request that never produced a response.
    with pytest.raises(TypeError):
        stub.send_request(AuthorizationRequest(card_num=_RULE_CARD, transaction_amt=100.0))
    assert stub.requests == []
    assert stub.responses == []
