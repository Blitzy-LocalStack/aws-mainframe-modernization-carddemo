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

Alternatives Considered:
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

Assumptions:
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

# Assumptions: the persisted per-card relation this migration creates. It is the SECOND table in
#   the reporting schema and, unlike the grouping key, the reporting service role may read part of
#   it: the two columns the statement heading walk orders by. It may never read the third, which
#   holds the whole card number the digest and the joins are functions of.
_CARD_IDENTITY_TABLE = "reporting.card_identity"

# Assumptions: the two columns the runtime role may select, in the order the column grant names
#   them. Naming both is what makes the grant assertion exact: a grant of one would break the
#   heading walk, and a grant of all three would publish every card number in the portfolio.
_CARD_IDENTITY_READABLE_COLUMNS = ("card_fingerprint", "card_num_masked")

# Assumptions: the maintenance procedure that reconciles that relation against the cross-reference.
#   It exists because the relation is DERIVED, and derived state that is never reconciled omits a
#   cardholder's statement silently rather than failing.
_CARD_IDENTITY_PROCEDURE = "reporting.refresh_card_identity"

# Assumptions: the two fragments that together establish the digest is keyed and byte-explicit.
#   They are asserted as fragments rather than as one literal because the expression appears twice
#   -- in the creation backfill and in the maintenance procedure -- and the two are wrapped
#   differently by the formatter while carrying the same expression.
_KEYED_DIGEST_FRAGMENTS = ("sha256(convert_to(", "encode(sha256(")

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
def views_statements() -> str:
    """Return the reporting-view migration with its commentary removed and whitespace collapsed.

    Returns
    -------
    str
        The contents of ``V1__reporting_views.sql`` with every ``--`` comment stripped and every
        run of whitespace reduced to one space, so an expectation can name what the file DOES
        without matching what it says ABOUT what it does.

    Raises
    ------
    AssertionError
        If the migration file is absent.

    Assumptions:
        This fixture exists beside :func:`views_sql` rather than replacing it, and the two answer
        different questions. Most assertions here are that a statement is PRESENT, and a statement
        cannot be forged by a comment, so matching against the whole text is both sufficient and
        cheaper to read. An assertion that a construct is ABSENT is the opposite: the file's Rule 1
        commentary explains at length why an unkeyed digest was rejected, and it necessarily spells
        the construct it rejected -- so a negative assertion over the whole text fails on the
        explanation of the very defect it guards against. Stripping the commentary is what lets a
        negative assertion mean what it says.

    Assumptions:
        The strip is line-oriented and quote-aware, and the quote tracking is not decoration: this
        file carries ``COMMENT ON`` statements whose prose contains double hyphens, so a naive cut
        at the first ``--`` would truncate a statement mid-literal and make a present statement
        look absent.
    """
    path = _SQL_DIR / "V1__reporting_views.sql"
    assert path.is_file(), f"the reporting view migration is missing at {path}"
    kept: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        quoted = False
        cut = len(line)
        for index in range(len(line)):
            if line[index] == "'":
                quoted = not quoted
            elif not quoted and line.startswith("--", index):
                cut = index
                break
        kept.append(line[:cut])
    return re.sub(r"\s+", " ", " ".join(kept))


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
    """Assert the cross-reference projection publishes the stored mask and never the raw column.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the projection selects a raw card number, or does not take its rendering from the
        identity relation. This view is asserted separately from the two ledger-backed ones
        because its rendering is STORED rather than computed, and the property matters as much:
        this relation joins to both of the others, so publishing an unmasked number here would
        defeat their masking.

    Refactoring Rationale:
        This asserted the mask EXPRESSION over ``x.card_num``, which is no longer where the
        rendering comes from. The projection now reads ``card_num_masked`` from
        ``reporting.card_identity``, where the same expression computed it once per card -- so the
        rendering is identical and the place it is produced has moved. The assertion had to move
        with it, and the property it guards is unchanged: no query the reporting role can compose
        over this view yields a whole card number. What replaces the expression check is stronger
        rather than weaker, because it also fails if the projection selected the identity
        relation's ``card_num`` column, which the previous form could not have detected.
    """
    body = _projection_of(views_sql, "reporting.v_card_xref")
    assert "ci.card_num_masked::character(16) AS card_num" in body, (
        "v_card_xref must publish the rendering stored in reporting.card_identity, cast back to"
        " the declared width so no consumer sees a type change"
    )
    for raw in ("x.card_num,", "ci.card_num,", "x.card_num AS", "ci.card_num AS"):
        assert raw not in body, (
            f"v_card_xref must not publish an unmasked card number: it selects {raw!r}"
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


def test_statement_fingerprint_is_keyed_and_not_a_bare_digest(
    views_sql: str, views_statements: str
) -> None:
    """Assert the per-card grouping token mixes in the secret rather than hashing the card alone.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    views_statements : str
        The same text with its commentary removed, for the one assertion that is an absence.

    Raises
    ------
    AssertionError
        If the migration uses an unkeyed digest, does not join the key table, or composes the
        digest over anything but the secret followed by the trimmed card number. A digest is only
        as hard to invert as its input space is large, and a card number is a sixteen-digit decimal
        string -- so an unkeyed digest beside a masked ``card_num`` column gives back exactly what
        the mask withholds. This is a regression guard on a security property, not a formatting
        preference.

    Refactoring Rationale:
        The assertions were scoped to the statement view's projection and are now scoped to the
        whole migration, because the digest moved out of the projections. It is computed once per
        card into ``reporting.card_identity`` -- which is what made it indexable, since an
        expression reading a table is not ``IMMUTABLE`` and no index can be built over one -- and
        the projections obtain it by join. Keeping the assertion on the projection would have
        passed on a migration that computed no digest at all.
    """
    # Assumptions: the absence is asserted against the comment-stripped text, because the file's
    #   own commentary explains at length why the unkeyed form was rejected and necessarily spells
    #   it out. Asserting over the whole text would fail on the explanation of the defect.
    assert "md5(" not in views_statements, (
        "the migration must not use an unkeyed md5 digest of a card number anywhere"
    )
    assert f"CROSS JOIN {_GROUPING_KEY_TABLE}" in views_sql, (
        "the keyed digest must be taken over a join to the single-row key table"
    )
    for keyed in _KEYED_DIGEST_FRAGMENTS:
        assert keyed in views_sql, (
            f"the fingerprint must be a keyed SHA-256 over an explicitly encoded byte string,"
            f" and {keyed!r} is absent"
        )
    # Assumptions: the token's VALUE is asserted to be unchanged by naming the exact operand
    #   order. It is a stored identifier that appears in no persisted downstream relation but
    #   does appear in every reporting DTO, and a change to the concatenation order or the
    #   encoding would silently re-key every card -- which reads to a caller as every statement
    #   selector having become invalid at once.
    assert "k.key_value || rtrim(x.card_num)" in views_sql, (
        "the digest must concatenate the secret with the trimmed card number, in that order,"
        " so the token value is bit-identical to the one the computed form produced"
    )


def test_card_identity_table_is_created_owned_and_column_granted(views_sql: str) -> None:
    """Assert the identity relation exists, is owner-held, and conveys two columns and no more.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the table is not created, not reassigned to the barrier owner, not revoked from the
        reporting login role, or granted anything but ``SELECT`` on its two ordering columns.

    Assumptions:
        The revoke is the load-bearing statement, for the reason the grouping key's is, and more
        strongly: ``V0__schemas_and_roles.sql`` sets a default privilege granting ``SELECT`` on
        TABLES in this schema to the service role, PostgreSQL default privileges cannot distinguish
        a table from a view, and a whole-relation grant makes a narrower column grant redundant --
        so PostgreSQL discards the column entries and the role reads every card number in the
        portfolio. The revoke-then-grant pair is what leaves the privilege in the intended state
        regardless of which of the two scripts ran last.
    """
    assert f"CREATE TABLE IF NOT EXISTS {_CARD_IDENTITY_TABLE} " in views_sql, (
        "the identity relation must be created by this migration"
    )
    assert f"ALTER TABLE {_CARD_IDENTITY_TABLE} OWNER TO carddemo_reporting_owner" in views_sql, (
        "the identity relation must be owned by the barrier owner, so the non-invoker views can"
        " join on the column the reading role cannot select"
    )
    assert f"REVOKE ALL ON {_CARD_IDENTITY_TABLE} FROM carddemo_reporting" in views_sql, (
        "the identity relation must be revoked from the reporting service role before any"
        " narrower privilege is granted on it"
    )
    columns = ", ".join(_CARD_IDENTITY_READABLE_COLUMNS)
    assert f"GRANT SELECT ({columns}) ON {_CARD_IDENTITY_TABLE}" in views_sql, (
        f"the reporting role must hold SELECT on exactly ({columns}) -- the two columns the"
        " heading walk orders by -- and on nothing else"
    )
    # Assumptions: the negative half is asserted too, because a column grant and a whole-relation
    #   grant are both spelled GRANT SELECT and only the second is a disclosure. Matching on the
    #   form WITHOUT a column list is what distinguishes them.
    assert f"GRANT SELECT ON {_CARD_IDENTITY_TABLE}" not in views_sql, (
        "the identity relation must never be granted whole: it stores the card number the rest of"
        " this schema exists to withhold"
    )


def test_card_identity_carries_the_access_paths_the_walks_need(views_sql: str) -> None:
    """Assert the identity relation declares the keys and index the reporting reads depend on.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the fingerprint is not the primary key, the card number is not unique, or the composite
        ordering index is absent.

    Assumptions:
        These three are the whole reason the relation exists, so their absence is the defect rather
        than a missed optimisation. The fingerprint key is what turns a lookup by token into a
        single-row seek; the unique card number is what makes the join to the cross-reference and
        the resolution of a whole card number index-eligible; and the composite index is the ORDER
        the heading walk traverses, without which each chunk sorts the whole population. The
        computed form this replaced could have none of them: an expression that reads a table is
        not ``IMMUTABLE``, and PostgreSQL will not build an index over one.
    """
    assert "CONSTRAINT pk_card_identity PRIMARY KEY (card_fingerprint)" in views_sql, (
        "the fingerprint must be the primary key, so a lookup by token is a single-row seek"
    )
    assert "CONSTRAINT uq_card_identity_card_num UNIQUE (card_num)" in views_sql, (
        "the card number must be unique, so the join to account.card_xref and the resolution of a"
        " whole card number are both index-eligible and neither can multiply a row"
    )
    assert (
        "CREATE INDEX IF NOT EXISTS idx_card_identity_masked_fingerprint ON"
        f" {_CARD_IDENTITY_TABLE} (card_num_masked, card_fingerprint)"
    ) in views_sql, (
        "the composite ordering index must exist: it is the order the statement heading walk"
        " traverses, and the tie-breaker must be IN it because the masked rendering is not unique"
    )


@pytest.mark.parametrize("view", _VIEWS_PUBLISHING_A_CARD_NUMBER)
def test_ledger_view_keeps_every_transaction_row(views_sql: str, view: str) -> None:
    """Assert a ledger-backed projection joins the identity relation without dropping a row.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.
    view : str
        A view built over ``ledger.transactions``.

    Raises
    ------
    AssertionError
        If the projection does not drive from the transaction relation, or joins the identity
        relation on anything but an outer join.

    Assumptions:
        Row inclusion is the property under assertion, not the join's shape for its own sake. The
        identity relation is DERIVED from the cross-reference, so a transaction whose card the
        cross-reference does not carry -- or does not carry YET, between a load and the next
        reconciliation -- has no identity row. An inner join would silently drop that transaction
        from the report, and the reference's report driver reads the whole ledger: a row missing
        from a total is a defect no downstream count can attribute. The outer join keeps the row
        with a null fingerprint, which the reporting entities already permit and which a report
        surfaces rather than hides.
    """
    body = _projection_of(views_sql, view)
    assert "FROM ledger.transactions AS t" in body, (
        f"{view} must drive from the transaction relation, so its row count is the ledger's"
    )
    assert f"LEFT JOIN {_CARD_IDENTITY_TABLE} AS ci" in body, (
        f"{view} must reach the fingerprint by OUTER join: an inner join drops every transaction"
        " whose card has no identity row, which is a row missing from a report total"
    )
    assert f"JOIN {_CARD_IDENTITY_TABLE} AS ci" in body, "the join must be present at all"
    assert f"INNER JOIN {_CARD_IDENTITY_TABLE}" not in body, (
        f"{view} must not inner-join the identity relation"
    )


def test_refresh_procedure_is_a_definer_routine_pinned_and_granted(views_sql: str) -> None:
    """Assert the maintenance routine is a pinned definer procedure the service may execute.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the routine is absent, is not ``SECURITY DEFINER``, does not pin its ``search_path``, is
        left executable by ``PUBLIC``, or is not granted to the reporting service role.

    Assumptions:
        Each of the four is a distinct failure with a distinct consequence. Without the definer
        declaration the routine cannot write a relation its callers have no privilege on, so a
        reconciliation would fail and a statement run would omit every card issued since the
        migration. Without a pinned ``search_path`` a definer body resolves unqualified names
        through a caller-controlled path, which is the classic escalation route -- the caller
        creates its own ``account.card_xref`` and the body reads that. Without the ``PUBLIC``
        revoke every login in the database could invoke it, which is wider than the eight views it
        sits beside. And without the grant to the reporting role the repository method that
        declares the reconciliation could not reach the routine at all.

    Assumptions:
        The grant is the privilege half of the contract and not by itself a usable path.
        ``services/reporting-service/src/main/resources/application.yml`` opens that service's pool
        ``read-only: true``, and PostgreSQL refuses a write in a read-only transaction whatever
        privilege the body runs with -- read-only is a property of the transaction, not of the
        privilege. The reconciliation that a cutover depends on is therefore performed by this
        package over a writable migration connection, which is what
        ``loaders.aurora.refresh_card_identity`` exists for; the grant asserted here is what makes
        the service's declared method reach the routine rather than a permission error, and what a
        deployment giving that service a writable datasource would rely on.
    """
    assert f"CREATE OR REPLACE PROCEDURE {_CARD_IDENTITY_PROCEDURE}()" in views_sql, (
        "the maintenance routine must be created by this migration"
    )
    # Assumptions: a PROCEDURE and not a FUNCTION. The reporting service reaches it through a
    #   modifying repository method, which the driver executes as an update, and the driver
    #   refuses a statement that returns a result set where none is expected.
    assert f"CREATE OR REPLACE FUNCTION {_CARD_IDENTITY_PROCEDURE}" not in views_sql, (
        "the maintenance routine must be a procedure: a function in this position returns a"
        " result set the caller's driver refuses"
    )
    declaration = f"CREATE OR REPLACE PROCEDURE {_CARD_IDENTITY_PROCEDURE}()"
    procedure_text = views_sql[views_sql.index(declaration) :]
    procedure_text = procedure_text[: procedure_text.index("COMMENT ON PROCEDURE")]
    assert "SECURITY DEFINER" in procedure_text, (
        "the routine must run with the owner's authority, because the role that calls it holds no"
        " privilege to write the relation or to read the cross-reference it reconciles against"
    )
    assert "SET search_path = pg_catalog, reporting, account" in procedure_text, (
        "the routine must pin its search_path, or a caller-controlled path decides which"
        " account.card_xref its body reads"
    )
    assert f"REVOKE ALL ON PROCEDURE {_CARD_IDENTITY_PROCEDURE}() FROM PUBLIC" in views_sql, (
        "the routine must be withdrawn from PUBLIC, which holds EXECUTE on a new routine"
    )
    assert (
        f"GRANT EXECUTE ON PROCEDURE {_CARD_IDENTITY_PROCEDURE}() TO carddemo_reporting"
    ) in views_sql, (
        "the reporting service role must hold EXECUTE, or the reconciliation its cross-reference"
        " repository declares fails on a permission error rather than reaching the routine"
    )


def test_card_identity_is_repaired_when_the_bootstrap_is_re_run(roles_sql: str) -> None:
    """Assert the bootstrap withdraws its own blanket grant from the identity relation.

    Parameters
    ----------
    roles_sql : str
        The whitespace-collapsed bootstrap text.

    Raises
    ------
    AssertionError
        If the bootstrap does not revoke the relation from the service role, or does not restore
        the two-column grant afterwards.

    Assumptions:
        The documented provisioning sequence ENDS with this script, and this script grants
        ``SELECT`` on all tables in the reporting schema. A column-level privilege cannot survive
        that grant -- PostgreSQL discards the narrower entries as redundant -- so a re-run after
        the reporting migration would hand the service role every card number in the portfolio and
        report nothing. Revoking alone is not sufficient either: the heading walk reads the two
        ordering columns directly, so a script that revoked without re-granting would leave every
        statement run failing with an insufficient-privilege error. Both halves are asserted
        because either alone leaves the deployment wrong in a different direction.
    """
    assert f"REVOKE ALL ON {_CARD_IDENTITY_TABLE} FROM carddemo_reporting" in roles_sql, (
        "the bootstrap must withdraw the whole-relation privilege its own blanket grant conveys"
    )
    columns = ", ".join(_CARD_IDENTITY_READABLE_COLUMNS)
    assert f"GRANT SELECT ({columns}) ON {_CARD_IDENTITY_TABLE}" in roles_sql, (
        "the bootstrap must restore the two-column grant, because the documented sequence ends"
        " with this script and the heading walk reads those columns directly"
    )
    assert f"GRANT SELECT ON {_CARD_IDENTITY_TABLE}" not in roles_sql, (
        "the bootstrap must never grant the relation whole"
    )


def test_whole_card_resolution_is_an_indexed_equality(views_sql: str) -> None:
    """Assert the per-card lookup compares the stored card number rather than a computed one.

    Parameters
    ----------
    views_sql : str
        The whitespace-collapsed migration text.

    Raises
    ------
    AssertionError
        If the lookup trims the stored column, recomputes the digest, or casts without first
        checking the argument's width.

    Assumptions:
        Three distinct defects are guarded here. Trimming the STORED column compares a computed
        value, which no index can serve -- and the previous form did exactly that while claiming to
        stay index-eligible. Recomputing the digest in the lookup would reintroduce the
        non-indexable expression the identity relation exists to remove. And an unguarded cast to
        ``character(16)`` TRUNCATES silently, so a seventeen-digit argument would resolve the card
        formed by its first sixteen digits -- answering for a card the caller does not hold, where
        the contract is to answer for none.
    """
    start = views_sql.index("CREATE FUNCTION reporting.resolve_card(")
    body = views_sql[start : views_sql.index("COMMENT ON FUNCTION reporting.resolve_card", start)]
    assert f"FROM {_CARD_IDENTITY_TABLE} AS ci" in body, (
        "the lookup must read the stored identity rather than deriving one"
    )
    assert "ci.card_num = CAST(rtrim(p_card_num) AS character(16))" in body, (
        "the lookup must pad the ARGUMENT to the stored column's type, so the equality is served"
        " by uq_card_identity_card_num"
    )
    assert "length(rtrim(p_card_num)) = 16" in body, (
        "the argument's width must be checked before the cast, or an over-long argument resolves"
        " a different card by silent truncation"
    )
    assert "rtrim(ci.card_num)" not in body, (
        "the lookup must not trim the stored column: that is a computed value and no index serves"
        " a comparison against one"
    )
    assert "sha256(" not in body, (
        "the lookup must not recompute the digest; the stored token is what makes it indexed"
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
