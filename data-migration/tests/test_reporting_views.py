"""Verify the reporting context's data-access contract in the SQL that establishes it.

Purpose
-------
Assert, without needing a provisioned database, that
``data-migration/sql/V1__reporting_views.sql`` and
``data-migration/sql/V0__schemas_and_roles.sql`` together establish the four
properties the reporting service's read path depends on:

1. every view the reporting entities map is created, as a security barrier, owned by
   the login-less barrier role;
2. the card number is masked in every view that publishes one;
3. no view publishes a card verification value, a national identifier or a
   government-issued identifier;
4. the service role is granted ``SELECT`` on each view by name and is denied every
   source relation those views read from.

WHY (Alternatives Considered)
    Running these assertions against a live database was considered and rejected as
    the primary check. A database test can only run where a database has been
    provisioned, so on a workstation and in the documentation gate it would be
    skipped -- and a skipped security check reads as a pass. The SQL text is the
    artifact under review and is present in every checkout, so asserting against the
    text is what makes the check unconditional. The database-side assertions live in
    ``data-migration/sql/verify/reporting_view_privileges.sql``, which an operator
    runs after applying the migration and which is referenced from the deploy
    runbook; the two are complementary rather than duplicated, since only the text
    check can run everywhere and only the database check can observe the resolved
    privilege graph.

WHY (Assumptions)
    The assertions are written against normalised whitespace rather than against exact
    lines, because the file carries the explainability commentary Rule 1 requires and
    that commentary is reflowed whenever it is edited. Matching on statement content
    with collapsed whitespace keeps the expectations about the SQL rather than about
    its formatting.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

# Assumptions: the repository root is located by walking up from this file
#   rather than from the working directory, so the suite behaves the same whether it
#   is run from the repository root, from `data-migration/`, or by an editor that
#   sets neither.
_SQL_DIR = Path(__file__).resolve().parents[1] / "sql"

# Assumptions: the seven names are the relations the reporting context reads. They are
#   listed here rather than derived, because the point of the check is that the SQL and
#   the Java agree, and deriving one from the other would make the check unable to
#   detect a disagreement.
# Refactoring Rationale: the list held FOUR names while the three account-backed views
#   could not be created -- account.accounts, account.customers and account.card_xref
#   did not exist, and CREATE VIEW resolves its references at creation time. The owning
#   module's V1__account.sql now creates them, so the three views exist and are held to
#   the same ownership, barrier, grant and revoke contract as the original four.
_EXPECTED_VIEWS = (
    "reporting.v_report_transactions",
    "reporting.v_statement_transactions",
    "reporting.v_transaction_types",
    "reporting.v_transaction_categories",
    "reporting.v_accounts",
    "reporting.v_customers",
    "reporting.v_card_xref",
)

# Assumptions: the one table this migration creates, which the reporting service role
#   must never be able to read. It holds the secret mixed into the statement view's
#   per-card grouping token, and the token is only non-invertible while that secret is
#   unavailable to whoever holds the token -- a sixteen-digit card number is recovered
#   from an UNKEYED digest by exhaustive search.
_GROUPING_KEY_TABLE = "reporting.card_grouping_key"

# Assumptions: only the two views built over the ledger publish a card number under the
#   't' alias; the reference projections carry codes and descriptions alone, and
#   v_card_xref publishes a masked card number under its own 'x' alias and is asserted
#   separately by test_card_xref_view_masks_its_card_number. Naming the two
#   that must mask, rather than asserting over all four, is what lets the masking
#   assertion be exact instead of conditional.
_VIEWS_PUBLISHING_A_CARD_NUMBER = (
    "reporting.v_report_transactions",
    "reporting.v_statement_transactions",
)

# Assumptions: these are the column names the migration gives to the three
#   protected values, taken from the per-service migrations that declare them. A view
#   that mentioned any of them would be publishing protected data whatever it called
#   the output column, so the check is on the SOURCE column name.
_FORBIDDEN_COLUMNS = ("cvv_encrypted", "ssn_encrypted", "govt_issued_id_encrypted")


@pytest.fixture(scope="module")
def views_sql() -> str:
    """Return the reporting-view migration with its whitespace collapsed.

    Returns
    -------
    str
        The contents of ``V1__reporting_views.sql`` with every run of whitespace
        reduced to one space, so an expectation can name a statement without
        depending on how the file is wrapped.

    Raises
    ------
    AssertionError
        If the migration file is absent, which is itself the defect this module was
        written for: the reporting entities map views that must exist somewhere, and
        the reporting module owns no migration directory of its own.
    """
    path = _SQL_DIR / "V1__reporting_views.sql"
    assert path.is_file(), f"the reporting view migration is missing at {path}"
    return re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def roles_sql() -> str:
    """Return the schema-and-role bootstrap with its whitespace collapsed.

    Returns
    -------
    str
        The contents of ``V0__schemas_and_roles.sql`` with whitespace collapsed.

    Raises
    ------
    AssertionError
        If the bootstrap file is absent, in which case no role or schema the views
        depend on is established at all.
    """
    path = _SQL_DIR / "V0__schemas_and_roles.sql"
    assert path.is_file(), f"the schema and role bootstrap is missing at {path}"
    return re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))


@pytest.mark.parametrize("view", _EXPECTED_VIEWS)
def test_view_is_created_as_a_security_barrier(views_sql: str, view: str) -> None:
    """Assert each expected view is created and declared a security barrier.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        The schema-qualified view name the reporting entities map.

    Raises
    ------
    AssertionError
        If the view is not created, or is created without the barrier option. The
        barrier is what stops the planner pushing a caller-supplied predicate below
        the view's projection, which is what stops a crafted predicate on a masked
        column being evaluated against the underlying value.
    """
    creation = f"CREATE VIEW {view} WITH (security_barrier = true) AS"
    assert creation in views_sql, f"{view} must be created as a security barrier"


@pytest.mark.parametrize("view", _EXPECTED_VIEWS)
def test_view_is_owned_by_the_loginless_barrier_role(views_sql: str, view: str) -> None:
    """Assert each view is owned by the barrier role rather than by the service role.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        The schema-qualified view name.

    Raises
    ------
    AssertionError
        If ownership is not assigned to ``carddemo_reporting_owner``. An owner may
        replace its own views, so a view owned by the service's login role would let a
        compromised task substitute an unmasked view and read exactly the data the
        view withholds -- within its own rights, leaving nothing to detect.
    """
    assert f"ALTER VIEW {view} OWNER TO carddemo_reporting_owner;" in views_sql, (
        f"{view} must be owned by carddemo_reporting_owner"
    )


def test_barrier_owner_cannot_be_logged_in_as(roles_sql: str) -> None:
    """Assert the barrier-owning role is created without login.

    Parameters
    ----------
    roles_sql : str
        The whitespace-collapsed bootstrap text.

    Raises
    ------
    AssertionError
        If the bootstrap does not declare the owner role as ``NOLOGIN``. Ownership is
        the whole protection, so a role that can be authenticated as is a credential
        that can replace a masking view.
    """
    assert "carddemo_reporting_owner" in roles_sql
    assert "NOLOGIN" in roles_sql, (
        "the bootstrap must create the reporting owner role NOLOGIN, so ownership cannot be"
        " exercised by a caller"
    )


@pytest.mark.parametrize("view", _VIEWS_PUBLISHING_A_CARD_NUMBER)
def test_card_number_is_masked_in_the_view(views_sql: str, view: str) -> None:
    """Assert the card number a view publishes is masked to its last four digits.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        A view that publishes a card number.

    Raises
    ------
    AssertionError
        If the view's projection does not carry the mask expression, or if it selects
        the raw column as ``card_num``. Masking at the barrier is what makes the
        unmasked value unreachable by any query the service can compose, as opposed to
        masking in application code that the same task also executes.
    """
    body = _projection_of(views_sql, view)
    mask = "('************' || right(rtrim(t.card_num), 4))::character(16) AS card_num"
    assert mask in body, f"{view} must publish a masked card number"
    assert "t.card_num," not in body, (
        f"{view} must not publish the unmasked card_num column alongside the mask"
    )


def test_card_xref_view_masks_its_card_number(views_sql: str) -> None:
    """Assert the cross-reference projection masks its card number to the last four digits.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the projection omits the mask expression, or selects the raw column. This view
        is asserted separately from the two ledger-backed ones because it aliases its
        source table ``x`` rather than ``t``, so the shared mask literal does not match
        it. The property is the same and matters as much: this relation joins to both of
        the others, so publishing an unmasked number here would defeat their masking.
    """
    body = _projection_of(views_sql, "reporting.v_card_xref")
    mask = "('************' || right(rtrim(x.card_num), 4))::character(16) AS card_num"
    assert mask in body, "v_card_xref must publish a masked card number"
    assert "x.card_num," not in body, (
        "v_card_xref must not publish the unmasked card_num column alongside the mask"
    )


def test_grouping_key_table_is_created_owned_and_withheld(views_sql: str) -> None:
    """Assert the grouping-key table exists, is owner-held, and is revoked from the service role.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the table is not created, not reassigned to the barrier owner, or not revoked
        from the reporting login role. The revoke is the load-bearing statement: the
        bootstrap sets a default privilege granting ``SELECT`` on tables in this schema
        to that role, and PostgreSQL default privileges cannot distinguish a view from a
        table, so without an explicit revoke the secret would be readable by the very
        role it is withheld from and the per-card token would be invertible again.
    """
    assert f"CREATE TABLE IF NOT EXISTS {_GROUPING_KEY_TABLE}" in views_sql, (
        "the grouping-key table must be created by this migration"
    )
    assert f"ALTER TABLE {_GROUPING_KEY_TABLE} OWNER TO carddemo_reporting_owner" in views_sql, (
        "the grouping-key table must be owned by the barrier owner"
    )
    assert f"REVOKE ALL ON {_GROUPING_KEY_TABLE} FROM carddemo_reporting" in views_sql, (
        "the grouping-key table must be revoked from the reporting service role"
    )
    assert f"GRANT SELECT ON {_GROUPING_KEY_TABLE}" not in views_sql, (
        "the grouping-key table must never be granted to any role"
    )


def test_statement_fingerprint_is_keyed_and_not_a_bare_digest(views_sql: str) -> None:
    """Assert the per-card grouping token mixes in the secret rather than hashing the card alone.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the projection uses an unkeyed digest, or does not join the key table. A digest
        is only as hard to invert as its input space is large, and a card number is a
        sixteen-digit decimal string -- so an unkeyed digest beside a masked ``card_num``
        column gives back exactly what the mask withholds. This is a regression guard on a
        security property, not a formatting preference.
    """
    body = _projection_of(views_sql, "reporting.v_statement_transactions")
    assert "md5(" not in body, (
        "the statement projection must not use an unkeyed md5 digest of the card number"
    )
    assert "k.key_value || rtrim(t.card_num)" in body, (
        "the fingerprint must concatenate the secret with the trimmed card number"
    )
    assert "sha256(convert_to(" in body, (
        "the fingerprint must be a SHA-256 over an explicitly encoded byte string"
    )
    assert f"CROSS JOIN {_GROUPING_KEY_TABLE}" in body, (
        "the statement projection must join the single-row key table"
    )


@pytest.mark.parametrize("column", _FORBIDDEN_COLUMNS)
def test_no_view_publishes_protected_columns(views_sql: str, column: str) -> None:
    """Assert no view mentions the card verification value or the two identifiers.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    column : str
        A protected source column name.

    Raises
    ------
    AssertionError
        If the migration mentions the column anywhere, including in a comment that
        might later be uncommented. These three values have no readable
        representation anywhere in the migration, and the reporting context is the
        broadest reader in the system, so its view set is where an accidental
        inclusion would matter most.
    """
    assert column not in views_sql, f"no reporting view may reference {column}"


@pytest.mark.parametrize("view", _EXPECTED_VIEWS)
def test_select_is_granted_per_view_by_name(views_sql: str, view: str) -> None:
    """Assert the service role is granted ``SELECT`` on each view individually.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        The schema-qualified view name.

    Raises
    ------
    AssertionError
        If the grant is missing. A per-view grant is required rather than a
        schema-wide one so that a view added later for another purpose is not
        readable by the service from the moment it exists.
    """
    assert f"GRANT SELECT ON {view} TO carddemo_reporting;" in views_sql, (
        f"the reporting role must be granted SELECT on {view} by name"
    )


def test_schema_wide_grant_is_not_used(views_sql: str) -> None:
    """Assert the migration does not grant the service role a schema-wide read.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If a schema-wide grant to the service role appears. Such a grant, paired with
        the default-privilege rule the bootstrap records, would extend to every view
        the owner creates in future regardless of what it exposed.
    """
    assert "GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO carddemo_reporting" not in views_sql


def test_service_role_is_denied_every_source_schema(roles_sql: str) -> None:
    """Assert the bootstrap revokes the service role's access to all four source schemas.

    Parameters
    ----------
    roles_sql : str
        The whitespace-collapsed bootstrap text.

    Raises
    ------
    AssertionError
        If the revocation is absent. The masking view is only a boundary while the
        base tables are unreachable: with a source grant in place the service could
        read the unmasked column directly and the view would be a convenience.
    """
    assert "REVOKE ALL ON SCHEMA ledger, account, card, reference FROM carddemo_reporting" in (
        roles_sql
    ), "the bootstrap must revoke the reporting role's access to every source schema"


def test_no_write_privilege_is_granted_on_a_view(views_sql: str) -> None:
    """Assert write privileges are revoked from the service role on every view.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the revocation is absent. A simple view over one table is automatically
        updatable in PostgreSQL, so a view that reads as read-only would accept a
        write if the privilege were ever granted; revoking states the contract.
    """
    assert "REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER" in views_sql


def _projection_of(views_sql: str, view: str) -> str:
    """Return the text of one view's projection, up to the start of the next statement.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        The schema-qualified view name whose projection is wanted.

    Returns
    -------
    str
        The substring from the view's ``CREATE VIEW`` through to its
        ``ALTER VIEW ... OWNER TO`` statement, which is where every projection ends.

    Raises
    ------
    AssertionError
        If either boundary cannot be found, which means the file no longer follows the
        create-then-assign-owner shape every view in it uses.
    """
    start = views_sql.find(f"CREATE VIEW {view} ")
    assert start >= 0, f"{view} is not created in the migration"
    end = views_sql.find(f"ALTER VIEW {view} OWNER TO", start)
    assert end > start, f"{view} has no ownership assignment following its definition"
    return views_sql[start:end]
