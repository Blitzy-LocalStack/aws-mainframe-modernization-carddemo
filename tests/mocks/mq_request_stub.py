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
from typing import Dict, List

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

# Fixed approval ceiling (in dollars). Amounts strictly greater are declined 51.
# WHY (Assumption): a fixed, documented ceiling keeps the over-limit branch of
# the optional-module tests deterministic without needing account state.
STUB_APPROVAL_CEILING = 5000.00


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
    transaction_amt : float
        Transaction amount in dollars (``PA-RQ-TRANSACTION-AMT``, ``+9(10).99``).
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
    transaction_amt: float = 0.0
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
    approved_amt : float
        Approved amount (``PA-RL-APPROVED-AMT``); equals the requested amount when
        approved, ``0.0`` when declined.

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
    approved_amt: float

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


def authorize(request: AuthorizationRequest) -> AuthorizationResponse:
    """Produce a deterministic authorization response for a request.

    Decision order (first match wins):
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

    Raises
    ------
    TypeError
        If ``request`` is not an :class:`AuthorizationRequest`.

    WHY
    ---
    - Assumption: a well-defined, documented precedence makes every branch
      independently testable and keeps the outcome a pure function of the input.
    """
    if not isinstance(request, AuthorizationRequest):
        # WHY: fail loudly on programmer misuse -- a wrong type here is a test
        # bug, not a business decline, so it must not be silently "declined".
        raise TypeError(
            f"authorize() expects AuthorizationRequest, got {type(request).__name__}"
        )

    def _decline(code: str, reason: str) -> AuthorizationResponse:
        """Build a declined response (auth id zeroed, approved amount 0)."""
        return AuthorizationResponse(
            card_num=request.card_num,
            transaction_id=request.transaction_id,
            auth_id_code="000000",
            auth_resp_code=code,
            auth_resp_reason=reason,
            approved_amt=0.0,
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
                approved_amt=round(float(request.transaction_amt), 2),
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

    # 4. Over the fixed approval ceiling.
    if float(request.transaction_amt) > STUB_APPROVAL_CEILING:
        return _decline(RESP_INSUFFICIENT, REASON_OVERLIMIT)

    # 5. Default: approve for the full requested amount.
    return AuthorizationResponse(
        card_num=request.card_num,
        transaction_id=request.transaction_id,
        auth_id_code=_derive_auth_id_code(request),
        auth_resp_code=RESP_APPROVED,
        auth_resp_reason=REASON_OK,
        approved_amt=round(float(request.transaction_amt), 2),
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
    "CANNED_RESPONSES",
    "RESP_APPROVED",
    "RESP_DECLINE_GENERIC",
    "RESP_INVALID_CARD",
    "RESP_INSUFFICIENT",
    "RESP_EXPIRED_CARD",
    "STUB_APPROVAL_CEILING",
]
