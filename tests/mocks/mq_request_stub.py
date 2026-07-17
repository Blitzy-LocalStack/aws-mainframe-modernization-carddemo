"""Deterministic stub for CardDemo's absent external MQ authorization producer.

Purpose
-------
CardDemo's optional "Credit Card Authorizations with IMS, DB2, and MQ" extension
(``app/app-authorization-ims-db2-mq``) simulates real-time card authorizations by
putting a *pending authorization request* on an MQ queue and receiving an
*authorization response* from an EXTERNAL producer/authorizer. That external
producer is **not shipped in this repository**, so the optional-module tests have
nothing real to call. This module stands in for it with a pure-Python, fully
deterministic responder so those tests can run offline, in parallel, and
reproducibly -- exactly as required by the financial-enterprise test standard.

The request/response field names and widths mirror the COBOL copybooks
``CCPAURQY.cpy`` (PENDING AUTHORIZATION REQUEST, ``PA-RQ-*``) and
``CCPAURLY.cpy`` (PENDING AUTHORIZATION RESPONSE, ``PA-RL-*``) so the stub is a
faithful test double of the real MQ contract.

WHY (design rationale)
----------------------
- Alternatives Considered: mocking the MQ client library (e.g. ``pymqi``) was
  rejected -- no MQ client is installed on the runner and the goal is to model
  the *producer's business behavior*, not the transport. A plain in-process
  responder is simpler and has zero external dependencies.
- Assumption: tests need decisions they can assert on exactly, so the response
  is a pure function of the request (no wall-clock, no RNG, no network).
- Trade-off: the approval rules here are a deliberately small, transparent
  rule-set (plus an explicit canned-response table) rather than a real
  authorization engine; determinism and auditability are valued over realism.

Raises
------
This module raises ``TypeError``/``ValueError`` only for programmer misuse
(described per function); it never raises for a "declined" business outcome --
a decline is a normal, asserted response.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Dict, List, Union

# ---------------------------------------------------------------------------
# ISO-8583-style response codes used by the stub. Kept as named constants so
# tests import symbols instead of hard-coding magic strings.
# WHY (Refactoring rationale): centralising the codes prevents drift between the
# stub and the tests that assert on it.
# ---------------------------------------------------------------------------
RESP_APPROVED = "00"          # PA-RL-AUTH-RESP-CODE for an approval
RESP_DECLINE_GENERIC = "05"   # do not honor
RESP_INVALID_CARD = "14"      # PA-RQ-CARD-NUM failed the format edit
RESP_INSUFFICIENT = "51"      # amount exceeds the stub's fixed approval ceiling
RESP_EXPIRED_CARD = "54"      # PA-RQ-CARD-EXPIRY-DATE indicates an expired card

REASON_OK = "0000"
REASON_INVALID_CARD = "1400"
REASON_OVERLIMIT = "5100"
REASON_EXPIRED = "5400"
REASON_DENYLIST = "0500"

# A money value may be supplied as an exact Decimal, an int (whole dollars), or
# a decimal STRING. Binary ``float`` is deliberately NOT accepted (see
# :func:`normalize_amount`) because it cannot represent cents exactly.
MoneyLike = Union[Decimal, int, str]

# Fixed approval ceiling. Amounts strictly greater are declined 51.
# WHY (Financial Validation, MA-14): the ceiling is a Decimal, not a binary
# float. Comparing the (already Decimal-normalized) request amount against a
# Decimal ceiling is exact -- a float ceiling (5000.00) reintroduces the very
# representation error the finding calls out (e.g. a float compare can mis-rank
# a value one cent away from the ceiling).
# WHY (Assumption): a fixed, documented ceiling keeps the over-limit branch of
# the optional-module tests deterministic without needing account state.
STUB_APPROVAL_CEILING: Decimal = Decimal("5000.00")

# ---------------------------------------------------------------------------
# Authoritative copybook widths/domains for the PENDING AUTHORIZATION REQUEST
# (app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy, PA-RQ-*). Every request
# field is validated against these BEFORE any canned/business decision (MA-14),
# so the stub can never approve (or even evaluate) a request that the real MQ
# copybook could not physically carry.
# WHY (Assumption): these constants are transcribed verbatim from the frozen
# copybook (PA-RQ-CARD-NUM X(16), PA-RQ-CARD-EXPIRY-DATE X(04),
# PA-RQ-PROCESSING-CODE 9(06), PA-RQ-MERCHANT-ID X(15), PA-RQ-TRANSACTION-ID
# X(15), PA-RQ-TRANSACTION-AMT +9(10).99); pinning them here keeps the double a
# faithful mirror of the contract and localises any future copybook change to
# one place.
_PA_RQ_CARD_NUM_LEN = 16          # PA-RQ-CARD-NUM        PIC X(16)
_PA_RQ_CARD_EXPIRY_LEN = 4        # PA-RQ-CARD-EXPIRY-DATE PIC X(04)
_PA_RQ_PROCESSING_CODE_LEN = 6    # PA-RQ-PROCESSING-CODE PIC 9(06)
_PA_RQ_MERCHANT_ID_LEN = 15       # PA-RQ-MERCHANT-ID     PIC X(15)
_PA_RQ_TRANSACTION_ID_LEN = 15    # PA-RQ-TRANSACTION-ID  PIC X(15)

# PA-RQ-TRANSACTION-AMT PIC +9(10).99 -> finite, nonnegative, at most ten
# integer digits and exactly two fractional digits (cents).
_CENTS = Decimal("0.01")
_AMT_MAX = Decimal("9999999999.99")   # ten 9s . 99
_AMT_MIN = Decimal("0.00")


@dataclass(frozen=True)
class AuthorizationRequest:
    """A pending-authorization request (mirror of CCPAURQY.cpy ``PA-RQ-*``).

    Parameters
    ----------
    card_num : str
        16-character card number (``PA-RQ-CARD-NUM``). Non-16-digit values are
        treated as an invalid card by :func:`authorize`.
    transaction_id : str
        Up to 15-character transaction id (``PA-RQ-TRANSACTION-ID``); echoed back
        on the response and used to derive the deterministic auth id code.
    transaction_amt : Decimal | int | str
        Transaction amount in dollars (``PA-RQ-TRANSACTION-AMT``, ``+9(10).99``).
        Supplied as an exact ``Decimal`` (preferred), an ``int`` (whole dollars),
        or a decimal string; binary ``float`` is rejected by :func:`authorize`
        via :func:`normalize_amount`. The value is validated to a finite,
        nonnegative, two-decimal amount within ``0.00``..``9999999999.99`` before
        any decision is made.
    card_expiry_date : str
        4-character ``YYMM``/``MMYY`` expiry token (``PA-RQ-CARD-EXPIRY-DATE``).
        The sentinel ``"0000"`` marks an expired card for the expiry test branch.
    processing_code : str
        6-digit processing code (``PA-RQ-PROCESSING-CODE``); default ``"000000"``.
    merchant_id : str
        15-character merchant id (``PA-RQ-MERCHANT-ID``); default empty.

    Returns
    -------
    AuthorizationRequest
        An immutable request record (``frozen=True`` for hashability and to keep
        canned test inputs from being mutated between parametrized cases).
    """

    card_num: str
    transaction_id: str = ""
    # WHY (MA-14): default is an exact Decimal, never 0.0 (float). The field is
    # typed MoneyLike so callers pass Decimal/int/str; authorize() normalizes and
    # validates it against the +9(10).99 domain before use.
    transaction_amt: MoneyLike = Decimal("0.00")
    card_expiry_date: str = ""
    processing_code: str = "000000"
    merchant_id: str = ""


@dataclass(frozen=True)
class AuthorizationResponse:
    """An authorization response (mirror of CCPAURLY.cpy ``PA-RL-*``).

    Parameters
    ----------
    card_num : str
        Echoed 16-character card number (``PA-RL-CARD-NUM``).
    transaction_id : str
        Echoed transaction id (``PA-RL-TRANSACTION-ID``).
    auth_id_code : str
        6-character authorization id code (``PA-RL-AUTH-ID-CODE``); ``"000000"``
        when declined, otherwise a deterministic value derived from the request.
    auth_resp_code : str
        2-character response code (``PA-RL-AUTH-RESP-CODE``); ``"00"`` == approved.
    auth_resp_reason : str
        4-character response reason (``PA-RL-AUTH-RESP-REASON``).
    approved_amt : Decimal
        Approved amount (``PA-RL-APPROVED-AMT``, ``+9(10).99``); the validated,
        two-decimal requested amount when approved, ``Decimal("0.00")`` when
        declined. Always an exact ``Decimal`` (never a binary float).

    Returns
    -------
    AuthorizationResponse
        An immutable response record.
    """

    card_num: str
    transaction_id: str
    auth_id_code: str
    auth_resp_code: str
    auth_resp_reason: str
    approved_amt: Decimal

    @property
    def approved(self) -> bool:
        """Return True when this response represents an approval.

        Returns
        -------
        bool
            ``True`` iff ``auth_resp_code`` equals :data:`RESP_APPROVED` (``"00"``).
        """
        return self.auth_resp_code == RESP_APPROVED


# Explicit per-card canned responses. A card number present here yields the
# mapped response code regardless of amount, so tests can force a specific
# decision deterministically.
# WHY (Trade-off): a small explicit table is clearer and more auditable than
# encoding every test case in branching logic; the rule-based path below is the
# fallback for cards not listed here.
CANNED_RESPONSES: Dict[str, str] = {
    "4111111111111111": RESP_APPROVED,          # canonical "always approve" card
    "4000000000000002": RESP_DECLINE_GENERIC,   # canonical "always decline" card
    "4000000000000069": RESP_EXPIRED_CARD,      # canonical "expired card" card
    "4000000000000119": RESP_INSUFFICIENT,      # canonical "insufficient funds" card
}


def _derive_auth_id_code(request: AuthorizationRequest) -> str:
    """Derive a stable 6-digit authorization id code from a request.

    Parameters
    ----------
    request : AuthorizationRequest
        The request whose ``card_num`` + ``transaction_id`` seed the code.

    Returns
    -------
    str
        A zero-padded 6-character numeric string.

    WHY
    ---
    - Assumption: the code must be reproducible across processes and runs, so
      Python's salted built-in ``hash()`` MUST NOT be used. A transparent
      positional checksum over the seed characters is used instead -- fully
      deterministic and dependency-free (no ``hashlib`` import needed).
    """
    seed = f"{request.card_num}{request.transaction_id}"
    # Simple deterministic positional checksum, folded into 6 digits.
    total = 0
    for index, char in enumerate(seed):
        total = (total + (ord(char) * (index + 1))) % 1_000_000
    return f"{total:06d}"


def normalize_amount(value: MoneyLike) -> Decimal:
    """Validate and normalize a money value to a finite, nonnegative Decimal.

    The returned value has exactly two fractional digits (cents) and lies within
    the ``PA-RQ-TRANSACTION-AMT PIC +9(10).99`` domain ``0.00``..``9999999999.99``.

    Parameters
    ----------
    value : Decimal | int | str
        The money value to validate. ``Decimal`` is preferred; ``int`` is read as
        whole dollars; a ``str`` is parsed as a decimal literal.

    Returns
    -------
    Decimal
        The value quantized to two decimal places (cents), e.g. ``Decimal("10.50")``.

    Raises
    ------
    TypeError
        If ``value`` is a ``bool`` or a ``float`` (or any other unsupported type).
    ValueError
        If the value is not a valid decimal, is not finite (NaN/Infinity), is
        negative, carries more than two decimal places (over-precision), or
        exceeds the ``+9(10).99`` magnitude.

    WHY
    ---
    - Financial Validation (MA-14): the previous code used ``round(float(amt), 2)``
      which SILENTLY accepted the three failure modes the finding names --
      negatives, ``NaN`` (``NaN > ceiling`` is ``False`` in IEEE-754 so a NaN
      amount fell through to *approve*), and over-precise input (silently
      rounded). Exact ``Decimal`` validation rejects all three deterministically.
    - Alternatives Considered: accepting ``float`` and coercing via
      ``Decimal(str(x))`` -- REJECTED. It perpetuates the unsafe-money pattern and
      produces surprising results (e.g. ``0.1 + 0.2`` -> ``Decimal("0.30000...4")``
      -> a confusing over-precision error). Requiring ``Decimal``/``int``/``str``
      makes the money contract explicit at every call site.
    - Trade-off: over-precision is REJECTED rather than rounded. A test double for
      a financial authorizer must not invent cents the caller did not specify;
      forcing the caller to state the exact cents keeps assertions unambiguous.
    """
    # ``bool`` is an ``int`` subclass; reject it before the int branch so a stray
    # True/False (a test bug) is not read as 1/0 dollars.
    if isinstance(value, bool):
        raise TypeError("transaction amount must not be a bool")
    if isinstance(value, float):
        # WHY (MA-14): binary float cannot represent most cent values exactly;
        # accepting it is the root cause the finding targets. Fail loudly with a
        # remediation hint rather than silently converting.
        raise TypeError(
            "transaction amount must be Decimal/int/str, not float "
            "(binary float cannot represent exact cents)"
        )
    if isinstance(value, Decimal):
        dec = value
    elif isinstance(value, int):
        dec = Decimal(value)
    elif isinstance(value, str):
        try:
            dec = Decimal(value)
        except InvalidOperation as exc:
            raise ValueError(f"invalid decimal amount: {value!r}") from exc
    else:
        raise TypeError(
            f"transaction amount must be Decimal/int/str, got {type(value).__name__}"
        )

    if not dec.is_finite():
        # Covers NaN and +/-Infinity, both of which are un-representable in the
        # copybook and must never be evaluated as a business amount.
        raise ValueError(f"transaction amount must be finite, got {dec}")
    if dec < _AMT_MIN:
        raise ValueError(f"transaction amount must be nonnegative, got {dec}")
    # ``exponent`` is the (negative) count of fractional digits for a finite
    # Decimal; < -2 means MORE than two decimal places -> over-precision.
    exponent = dec.as_tuple().exponent
    if isinstance(exponent, int) and exponent < -2:
        raise ValueError(
            f"transaction amount has more than two decimal places: {dec}"
        )
    if dec > _AMT_MAX:
        raise ValueError(
            f"transaction amount exceeds +9(10).99 maximum {_AMT_MAX}, got {dec}"
        )
    # Scale is guaranteed <= 2 here, so quantizing to cents only PADS (never
    # rounds) -- no precision is created or destroyed.
    return dec.quantize(_CENTS)


def _check_width(field_name: str, value: str, max_len: int) -> None:
    """Assert a string field fits its fixed-width ``PIC X(n)`` copybook slot.

    Parameters
    ----------
    field_name : str
        Copybook field name, used only in the error message.
    value : str
        The candidate value.
    max_len : int
        The copybook width ``n``; values longer than this cannot be marshalled.

    Returns
    -------
    None

    Raises
    ------
    TypeError
        If ``value`` is not a ``str``.
    ValueError
        If ``len(value) > max_len``.

    WHY
    ---
    - Assumption/Trade-off: ``PIC X(n)`` is space-padded and left-justified, so a
      SHORTER value is legal (it pads) while a LONGER value is a genuine contract
      violation. We therefore check "fits" (``<= n``), not "equals", so callers
      may omit optional trailing fields without tripping validation.
    """
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a str, got {type(value).__name__}")
    if len(value) > max_len:
        raise ValueError(
            f"{field_name} exceeds copybook width X({max_len}): "
            f"{len(value)} chars"
        )


def _check_numeric_width(field_name: str, value: str, digits: int) -> None:
    """Assert a numeric string field fits its ``PIC 9(n)`` copybook slot.

    Parameters
    ----------
    field_name : str
        Copybook field name, used only in the error message.
    value : str
        The candidate value; must be all digits and no wider than ``digits``.
    digits : int
        The copybook digit count ``n``.

    Returns
    -------
    None

    Raises
    ------
    TypeError
        If ``value`` is not a ``str``.
    ValueError
        If ``value`` is empty, non-numeric, or wider than ``digits``.

    WHY
    ---
    - Assumption: ``PIC 9(n)`` holds only decimal digits; a non-digit value could
      not be moved into the field on the mainframe side, so it is rejected here
      rather than silently mis-evaluated.
    """
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a str, got {type(value).__name__}")
    if not value or not value.isdigit():
        raise ValueError(f"{field_name} must be numeric (PIC 9({digits})): {value!r}")
    if len(value) > digits:
        raise ValueError(
            f"{field_name} exceeds copybook width 9({digits}): {len(value)} digits"
        )


def validate_request(request: AuthorizationRequest) -> Decimal:
    """Validate a request against the CCPAURQY copybook widths/domains.

    Runs BEFORE any canned or business decision so the stub never evaluates (let
    alone approves) a request the real MQ copybook could not carry.

    Parameters
    ----------
    request : AuthorizationRequest
        The request to validate.

    Returns
    -------
    Decimal
        The normalized, validated transaction amount (cents), ready for the
        ceiling comparison and the approved-amount echo.

    Raises
    ------
    TypeError
        If a field has the wrong Python type (propagated from the checkers).
    ValueError
        If any field violates its copybook width or domain.

    WHY
    ---
    - Financial Validation (MA-14): "validate every copybook width/domain before
      canned decisions." Amount validation is the security-critical part (it
      closes the negative/NaN/over-precision approval holes); the width checks
      keep the double honest about the fixed-width contract it claims to mirror.
    - Assumption/Trade-off: ``card_num`` is width-checked here (<= 16) but its
      "exactly 16 digits" business edit remains in :func:`authorize` because an
      ill-formed-but-fitting card is a normal *decline 14* outcome, not a
      malformed request. Over-width (> 16) is a true contract violation -> raise.
    """
    _check_width("card_num", request.card_num, _PA_RQ_CARD_NUM_LEN)
    _check_width("transaction_id", request.transaction_id, _PA_RQ_TRANSACTION_ID_LEN)
    _check_width("card_expiry_date", request.card_expiry_date, _PA_RQ_CARD_EXPIRY_LEN)
    _check_width("merchant_id", request.merchant_id, _PA_RQ_MERCHANT_ID_LEN)
    _check_numeric_width(
        "processing_code", request.processing_code, _PA_RQ_PROCESSING_CODE_LEN
    )
    return normalize_amount(request.transaction_amt)


def authorize(request: AuthorizationRequest) -> AuthorizationResponse:
    """Produce a deterministic authorization response for a request.

    Decision order (first match wins):
      0. copybook validation  -> raise on any width/domain violation (MA-14)
      1. invalid card number  -> decline ``14`` / ``1400``
      2. explicit canned card -> the mapped code (approve or decline)
      3. expired-card sentinel-> decline ``54`` / ``5400``
      4. amount over ceiling  -> decline ``51`` / ``5100``
      5. otherwise            -> approve ``00`` / ``0000``

    Parameters
    ----------
    request : AuthorizationRequest
        The pending-authorization request to evaluate.

    Returns
    -------
    AuthorizationResponse
        The canned/derived response; ``approved`` is ``True`` only for code ``00``.
        On approval ``approved_amt`` is the validated two-decimal ``Decimal``.

    Raises
    ------
    TypeError
        If ``request`` is not an :class:`AuthorizationRequest`, or a request field
        has the wrong Python type (e.g. a ``float`` amount).
    ValueError
        If any request field violates its copybook width/domain (e.g. a negative,
        NaN, over-precise, or too-large amount).

    WHY
    ---
    - Assumption: a well-defined, documented precedence makes every branch
      independently testable and keeps the outcome a pure function of the input.
    - Financial Validation (MA-14): copybook validation runs FIRST (step 0), so a
      malformed request is rejected loudly BEFORE any canned/business decision --
      an invalid amount can never slip through to an approval.
    """
    if not isinstance(request, AuthorizationRequest):
        # WHY: fail loudly on programmer misuse -- a wrong type here is a test
        # bug, not a business decline, so it must not be silently "declined".
        raise TypeError(
            f"authorize() expects AuthorizationRequest, got {type(request).__name__}"
        )

    # 0. Copybook width/domain validation (MA-14). This runs BEFORE any canned or
    # business decision and returns the validated Decimal amount; a violation
    # raises here, so a negative/NaN/over-precise/too-large amount (or an
    # over-width text field) can never reach an approval branch.
    amount = validate_request(request)

    def _decline(code: str, reason: str) -> AuthorizationResponse:
        """Build a declined response (auth id zeroed, approved amount 0).

        Parameters
        ----------
        code : str
            The ``PA-RL-AUTH-RESP-CODE`` to return (e.g. ``"14"``).
        reason : str
            The ``PA-RL-AUTH-RESP-REASON`` to return (e.g. ``"1400"``).

        Returns
        -------
        AuthorizationResponse
            A declined response echoing the request's card/txn id, with a zeroed
            auth id and ``approved_amt`` of ``Decimal("0.00")``.
        """
        return AuthorizationResponse(
            card_num=request.card_num,
            transaction_id=request.transaction_id,
            auth_id_code="000000",
            auth_resp_code=code,
            auth_resp_reason=reason,
            approved_amt=Decimal("0.00"),
        )

    # 1. Card-number format edit: must be exactly 16 digits.
    if not (len(request.card_num) == 16 and request.card_num.isdigit()):
        return _decline(RESP_INVALID_CARD, REASON_INVALID_CARD)

    # 2. Explicit canned decision for known test cards.
    canned = CANNED_RESPONSES.get(request.card_num)
    if canned is not None:
        if canned == RESP_APPROVED:
            return AuthorizationResponse(
                card_num=request.card_num,
                transaction_id=request.transaction_id,
                auth_id_code=_derive_auth_id_code(request),
                auth_resp_code=RESP_APPROVED,
                auth_resp_reason=REASON_OK,
                approved_amt=amount,
            )
        reason = {
            RESP_EXPIRED_CARD: REASON_EXPIRED,
            RESP_INSUFFICIENT: REASON_OVERLIMIT,
            RESP_DECLINE_GENERIC: REASON_DENYLIST,
        }.get(canned, REASON_DENYLIST)
        return _decline(canned, reason)

    # 3. Expired-card sentinel.
    if request.card_expiry_date == "0000":
        return _decline(RESP_EXPIRED_CARD, REASON_EXPIRED)

    # 4. Over the fixed approval ceiling. Both operands are exact Decimals, so
    # the comparison is exact (a value one cent over the ceiling is declined,
    # exactly at the ceiling is approved).
    if amount > STUB_APPROVAL_CEILING:
        return _decline(RESP_INSUFFICIENT, REASON_OVERLIMIT)

    # 5. Default: approve for the full (validated) requested amount.
    return AuthorizationResponse(
        card_num=request.card_num,
        transaction_id=request.transaction_id,
        auth_id_code=_derive_auth_id_code(request),
        auth_resp_code=RESP_APPROVED,
        auth_resp_reason=REASON_OK,
        approved_amt=amount,
    )


class MqRequestStub:
    """In-process stand-in for the external MQ authorization producer.

    Models the request/response (PUT/GET) exchange of the MQ producer without
    any real queue: :meth:`send_request` submits a request and returns the
    deterministic response, while recording the exchange so tests can assert on
    what was "put on the queue".

    Parameters
    ----------
    None

    Returns
    -------
    MqRequestStub
        A fresh stub with an empty request/response log.

    WHY
    ---
    - Alternatives Considered: a module-level function alone would suffice for
      pure decisions, but a small object lets optional-module tests assert on the
      *sequence* of requests (queue semantics) and reset state between tests.
    """

    def __init__(self) -> None:
        """Initialize an empty in-memory exchange log."""
        self.requests: List[AuthorizationRequest] = []
        self.responses: List[AuthorizationResponse] = []

    def send_request(self, request: AuthorizationRequest) -> AuthorizationResponse:
        """Submit an authorization request and return its response.

        Parameters
        ----------
        request : AuthorizationRequest
            The request to "put on the queue".

        Returns
        -------
        AuthorizationResponse
            The deterministic response from :func:`authorize`.

        Raises
        ------
        TypeError
            Propagated from :func:`authorize` on a wrong-typed request.
        """
        response = authorize(request)
        # WHY: record AFTER a successful authorize() so a rejected (raised) call
        # does not leave a half-logged exchange that would confuse assertions.
        self.requests.append(request)
        self.responses.append(response)
        return response

    def reset(self) -> None:
        """Clear the exchange log so a stub instance can be reused per test.

        Returns
        -------
        None
        """
        self.requests.clear()
        self.responses.clear()


__all__ = [
    "AuthorizationRequest",
    "AuthorizationResponse",
    "MqRequestStub",
    "authorize",
    "normalize_amount",
    "validate_request",
    "MoneyLike",
    "CANNED_RESPONSES",
    "RESP_APPROVED",
    "RESP_DECLINE_GENERIC",
    "RESP_INVALID_CARD",
    "RESP_INSUFFICIENT",
    "RESP_EXPIRED_CARD",
    "STUB_APPROVAL_CEILING",
]
