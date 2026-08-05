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

# Assumptions: the four names are the ones the reporting JPA entities declare in
#   their @Table annotations. They are listed here rather than derived, because the
#   point of the check is that the SQL and the Java agree, and deriving one from the
#   other would make the check unable to detect a disagreement.
_EXPECTED_VIEWS = (
    "reporting.v_report_transactions",
    "reporting.v_statement_transactions",
    "reporting.v_transaction_types",
    "reporting.v_transaction_categories",
)

# Assumptions: only the two views built over the ledger publish a card number;
#   the two reference projections carry codes and descriptions alone. Naming the two
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
