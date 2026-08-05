-- =============================================================================
-- data-migration/sql/verify/alternate_database_users.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Proves, against a provisioned cluster, that no login role EXPLICITLY GRANTED
--   membership in a CardDemo owning role holds authority beyond that role. The
--   ETL's schema isolation rests entirely on an owning role's grants being the
--   ceiling of what a load can reach; a member role that also holds SUPERUSER or
--   BYPASSRLS raises that ceiling to the whole cluster while every name and every
--   grant still reads correctly. Each query returns rows ONLY when the property is
--   broken, so a run whose every result set is empty is a pass and no result needs
--   interpreting.
--
-- Parameters:
--   None. The owning roles are the eight literals created by
--   data-migration/sql/V0__schemas_and_roles.sql, so the check cannot be pointed
--   at a different set of roles and pass by examining the wrong ones.
--
-- Provides:
--   Three result sets, in the order below. Any non-empty result set is a
--   privilege defect that must be resolved before a load is run:
--     1. granted members of an owning role holding a disqualifying attribute
--     2. granted members that cannot log in, i.e. dead allowlist entries
--     3. owning roles absent, or granted membership in ANOTHER owning role, which
--        would merge two bounded contexts' authority
--
-- Failure modes:
--   - Run against a cluster whose roles were never created and checks 1 and 2
--     return empty, which alone would be a false pass. Check 3 therefore asserts
--     the eight-role expectation directly, so absence is reported rather than
--     mistaken for cleanliness.
--   - A role granted membership through an intermediate group role IS reported,
--     because the recursion below follows the grant chain; a direct-grant test
--     would miss exactly the indirection an escalation would use.
--
-- WHY (non-obvious design decisions):
--   - Refactoring Rationale: membership is derived by recursing pg_auth_members
--     rather than by calling pg_has_role, which is what the first version did.
--     pg_has_role answers "may this role act as that one", and for a SUPERUSER
--     that is unconditionally true -- so the first version reported the cluster's
--     bootstrap superuser as an offending member of all eight owning roles, 40
--     rows of noise on a clean cluster. A verify script whose clean output is 40
--     rows is a script that gets ignored, which costs more than the check gains.
--     Recursing the grant catalog answers the question actually being asked --
--     "was this role GRANTED the owning role" -- and still reports a superuser
--     that was also explicitly granted it, which is the genuine defect.
--   - Alternatives Considered: keeping pg_has_role and excluding superusers from
--     the result. Rejected because it would suppress the one case the check exists
--     to find: a role that holds both an explicit grant and SUPERUSER.
--   - Alternatives Considered: requiring a member's attributes to equal its owning
--     role's. Rejected for the same reason the Python adjudicator rejects it: two
--     roles legitimately differ in password expiry and connection limit, so
--     equality fails on differences that confer no authority, and a check that
--     fails on benign differences is a check that gets removed.
--   - Assumptions: pg_roles is read rather than pg_authid, because every attribute
--     examined here is visible in pg_roles while pg_authid additionally exposes
--     password hashes. Reading the narrower catalog needs no privilege this script
--     cannot justify.
--   - Assumptions: the recursive CTE is repeated in each query rather than shared
--     through a temporary view. A temp view would need CREATE on the temporary
--     schema, which a read-only auditing role may not hold, and repeating it keeps
--     every check independently runnable by an operator pasting one of them.
-- =============================================================================

\echo 'Check 1: granted members of an owning role holding a disqualifying attribute (expect 0 rows)'

-- WHY : Assumptions: the attributes are unpivoted into one row per (role,
-- attribute) pair rather than reported as a boolean column per role. An operator
-- repairing a role that holds two disqualifying attributes should see both named
-- in one run, matching carddemo_migration.config.require_equivalent_database_user,
-- which reports every failing attribute rather than stopping at the first.
WITH RECURSIVE granted(member_oid, owner_oid) AS (
    SELECT am.member, am.roleid
      FROM pg_catalog.pg_auth_members AS am
    UNION
    SELECT g.member_oid, am.roleid
      FROM granted AS g
      JOIN pg_catalog.pg_auth_members AS am ON am.member = g.owner_oid
)
SELECT member.rolname AS offending_role,
       owner.rolname  AS owning_role,
       attribute.name AS disqualifying_attribute
  FROM granted AS g
  JOIN pg_catalog.pg_roles AS member ON member.oid = g.member_oid
  JOIN pg_catalog.pg_roles AS owner  ON owner.oid = g.owner_oid
  CROSS JOIN LATERAL (
      VALUES ('is_superuser', member.rolsuper),
             ('can_create_db', member.rolcreatedb),
             ('can_create_role', member.rolcreaterole),
             ('bypasses_row_level_security', member.rolbypassrls),
             ('can_replicate', member.rolreplication)
  ) AS attribute(name, held)
 WHERE owner.rolname IN (
           'carddemo_auth', 'carddemo_account', 'carddemo_card', 'carddemo_ledger',
           'carddemo_reference', 'carddemo_batch', 'carddemo_authorization',
           'carddemo_reporting'
       )
   AND member.rolcanlogin
   AND attribute.held
 ORDER BY offending_role, owning_role, disqualifying_attribute;

\echo 'Check 2: granted members that cannot log in (expect 0 rows)'

-- WHY : Trade-offs: a granted member that cannot log in is reported as a defect
-- rather than ignored as harmless. It confers no access by itself, but it means an
-- allowlist entry naming it can never succeed, so the rotation it was created for
-- would fail at the moment it is needed. Reporting it now costs one line of
-- output; discovering it during a cutover costs the window.
WITH RECURSIVE granted(member_oid, owner_oid) AS (
    SELECT am.member, am.roleid
      FROM pg_catalog.pg_auth_members AS am
    UNION
    SELECT g.member_oid, am.roleid
      FROM granted AS g
      JOIN pg_catalog.pg_auth_members AS am ON am.member = g.owner_oid
)
SELECT member.rolname AS unusable_member,
       owner.rolname  AS owning_role
  FROM granted AS g
  JOIN pg_catalog.pg_roles AS member ON member.oid = g.member_oid
  JOIN pg_catalog.pg_roles AS owner  ON owner.oid = g.owner_oid
 WHERE owner.rolname IN (
           'carddemo_auth', 'carddemo_account', 'carddemo_card', 'carddemo_ledger',
           'carddemo_reference', 'carddemo_batch', 'carddemo_authorization',
           'carddemo_reporting'
       )
   AND NOT member.rolcanlogin
   -- WHY : Assumptions: the reporting-view barrier owner is excluded by name. V0
   -- and V1__reporting_views.sql create it deliberately NOLOGIN -- that is the
   -- control that stops anyone authenticating as the owner of a security-barrier
   -- view -- so reporting it here would flag the intended design as a defect.
   AND member.rolname <> 'carddemo_reporting_owner'
 ORDER BY unusable_member, owning_role;

\echo 'Check 3: owning roles absent, or granted membership in another owning role (expect 0 rows)'

-- WHY : Refactoring Rationale: absence and cross-membership are reported by ONE
-- query over an expected-role list rather than by two. Written as two queries the
-- absence check was the only thing standing between this script and a false pass
-- on an unprovisioned cluster, and it is easy to drop a query while believing the
-- remaining ones still assert something. Deriving both from the same literal list
-- means the list cannot be satisfied vacuously.
WITH RECURSIVE granted(member_oid, owner_oid) AS (
    SELECT am.member, am.roleid
      FROM pg_catalog.pg_auth_members AS am
    UNION
    SELECT g.member_oid, am.roleid
      FROM granted AS g
      JOIN pg_catalog.pg_auth_members AS am ON am.member = g.owner_oid
),
expected(rolname) AS (
    VALUES ('carddemo_auth'), ('carddemo_account'), ('carddemo_card'),
           ('carddemo_ledger'), ('carddemo_reference'), ('carddemo_batch'),
           ('carddemo_authorization'), ('carddemo_reporting')
)
SELECT expected.rolname AS owning_role,
       CASE
           WHEN present.oid IS NULL THEN 'role does not exist'
           ELSE 'is granted membership in ' || other.rolname
       END AS defect
  FROM expected
  LEFT JOIN pg_catalog.pg_roles AS present ON present.rolname = expected.rolname
  LEFT JOIN granted AS g ON g.member_oid = present.oid
  LEFT JOIN pg_catalog.pg_roles AS other
         ON other.oid = g.owner_oid
        AND other.rolname <> expected.rolname
        AND other.rolname IN (
                'carddemo_auth', 'carddemo_account', 'carddemo_card', 'carddemo_ledger',
                'carddemo_reference', 'carddemo_batch', 'carddemo_authorization',
                'carddemo_reporting'
            )
 WHERE present.oid IS NULL
    OR other.oid IS NOT NULL
 ORDER BY owning_role, defect;
