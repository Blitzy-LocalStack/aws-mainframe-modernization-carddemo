-- =============================================================================
-- services/auth-service/src/main/resources/db/migration/V1__auth.sql
-- -----------------------------------------------------------------------------
-- Purpose: creates auth.users, the AUTH bounded context's local identity record.
--   The table preserves the identifier, names and A/U role from
--   app/cpy/CSUSR01Y.cpy L17-L23 and links each row to its Cognito subject.
-- Refactoring Rationale: Cognito owns authentication, so the baseline
--   SEC-USR-PWD credential is intentionally excluded rather than copied into a
--   local password, hash or shadow column.
-- Assumptions: data-migration/sql/V0__schemas_and_roles.sql creates the auth
--   schema, owner and grants before Flyway applies this migration.
-- =============================================================================

-- Refactoring Rationale: app/cpy/CSUSR01Y.cpy L21 stores SEC-USR-PWD in the
--   baseline record, and app/cbl/COSGN00C.cbl L223 compares it directly during
--   sign-on. The target records only cognito_sub because Cognito performs that
--   comparison. Password recovery therefore becomes an identity-provider reset,
--   while database queries, backups and logs cannot disclose a credential.
-- Assumptions: SEC-USR-FILLER at app/cpy/CSUSR01Y.cpy L23 is padding only; no
--   program or symbolic map addresses it, so persisting 23 blank bytes would
--   create storage with no domain meaning.
-- Refactoring Rationale: no version column is needed because COUSR02C and
--   COUSR03C acquire their update locks and complete the mutation in one CICS
--   task; neither carries a before-image across user think-time. Adding
--   optimistic locking would create a conflict outcome the baseline cannot
--   produce.
-- Trade-offs: PostgreSQL READ COMMITTED is stricter than USRSEC's
--   READINTEG(UNCOMMITTED), and row locks replace the file-wide STRINGS(1)
--   ceiling recorded in app/csd/CARDDEMO.CSD L89-L93. This removes dirty reads
--   and permits unrelated user rows to be administered concurrently without
--   weakening each mutation's row-level exclusion.
-- Assumptions: the bootstrap migration owns schema, role and grant creation.
--   Keeping those statements out of this file preserves the ownership and
--   privileges established by data-migration/sql/V0__schemas_and_roles.sql.
CREATE TABLE auth.users (
-- Assumptions: SEC-USR-ID is PIC X(08) at app/cpy/CSUSR01Y.cpy L18.
--   CHAR(8) preserves the blank-padded key consumed by the ETL and API length
--   validation; VARCHAR(8) would discard part of that fixed-width contract.
    user_id CHAR(8) PRIMARY KEY,
-- Refactoring Rationale: the PIC X(20) names at CSUSR01Y.cpy L19-L20 use
--   trailing blanks as record padding, not name data. VARCHAR(20) removes that
--   transport concern, while NOT NULL prevents a state the fixed record cannot
--   represent.
    first_name VARCHAR(20) NOT NULL,
    last_name VARCHAR(20) NOT NULL,
-- Assumptions: app/cpy/COCOM01Y.cpy L26-L28 defines the complete user-type
--   domain as A (administrator) or U (user). The CHECK protects this
--   authorization boundary for every write path, not only application code.
    user_type CHAR(1) NOT NULL CHECK (user_type IN ('A','U')),
-- Refactoring Rationale: UUID NOT NULL UNIQUE makes the Cognito-to-local
--   identity mapping total and one-to-one. A nullable or duplicated subject
--   would be unauthenticatable or resolve one token to conflicting authorities;
--   UUID also canonicalizes values before uniqueness is evaluated.
    cognito_sub UUID NOT NULL UNIQUE
);

-- Refactoring Rationale: this migration intentionally creates one table and no
--   seed data; users arrive through ETL. It also adds no credential, version,
--   audit or soft-delete columns, and the primary-key and unique constraints
--   already provide the only required access paths. These absences preserve the
--   baseline record and deletion semantics without inventing unused state.
