-- =============================================================================
-- V2__ledger_transaction_id_allocator.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Creates the database-owned allocator that issues transaction identifiers for
--   the two interactive write paths of this context: transaction add
--   (app/cbl/COTRN02C.cbl) and bill payment (app/cbl/COBIL00C.cbl).
--
--   This migration creates one sequence and nothing else. It creates no table,
--   alters no table and confers no privilege beyond what the schema owner already
--   holds; `ledger` and its owning login are established by
--   data-migration/sql/V0__schemas_and_roles.sql.
--
-- Why an allocator exists at all
-- -----------------------------------------------------------------------------
--   Refactoring Rationale: the two services previously derived an identifier as
--   `max(transaction_id) + 1`, read through the repository and incremented in
--   Java. That reproduced what the baseline does -- both reference programs
--   position a browse at high values and read one record backwards, at
--   app/cbl/COTRN02C.cbl:444-447 and app/cbl/COBIL00C.cbl:212-215 -- but it does
--   NOT reproduce the guarantee, and the difference is not academic. Under CICS
--   those two transactions were serialised by the region; two Fargate tasks
--   behind a load balancer are not. Two concurrent adds both read the same
--   maximum, both add one, and both attempt the same primary key: one succeeds
--   and the other fails on a constraint violation the caller sees as an internal
--   error rather than as anything actionable.
--
--   Assumptions: a SEQUENCE is the right primitive rather than a counter row
--   taken `FOR UPDATE`. A sequence allocates outside the calling transaction, so
--   two concurrent allocators never wait on each other and neither holds a lock
--   across the rest of its unit of work -- which matters here because bill
--   payment's unit of work also writes the account balance through another
--   context. A counter row would serialise every add behind every payment for the
--   duration of that remote call.
--
--   Trade-offs: a sequence does not recycle a value consumed by a rolled-back
--   transaction, so the identifier space develops gaps. That is accepted
--   deliberately: nothing in the reference tree treats an identifier as a count
--   or reads a gap as meaningful -- the report job orders by processing timestamp
--   and card number, not by identifier arithmetic -- so a gap is invisible to
--   every consumer. The alternative, gapless allocation, requires exactly the
--   serialising lock the paragraph above rejects.
--
--   Alternatives Considered: making the column an identity column instead. Rejected
--   because the column is CHAR(16) and must stay so: app/cpy/CVTRA05Y.cpy L5
--   declares `TRAN-ID PIC X(16)`, an ALPHANUMERIC picture, and the seeded data
--   depends on it -- identifiers such as `0000000000683580` in
--   app/data/ASCII/dailytran.txt carry leading zeros an integer column would
--   discard. An identity column would also fix the format in the schema, and this
--   context has TWO identifier formats: the sequence format this allocator serves
--   and the business-date-prefixed format the interest job composes. Only the
--   first is allocated here.
-- =============================================================================

-- Assumptions: the START value is 1 and the sequence is advanced past the loaded
--   data by the statement below rather than by a literal here. A literal would
--   have to be maintained against whatever the ETL happened to load, and the two
--   would drift the first time a different extract was staged. Deriving it means
--   this migration is correct against an empty table, against the 300-record
--   sample extract, and against a full production load, with no edit.
-- Assumptions: NO CYCLE, which is the default and is stated anyway. A cycling
--   sequence would eventually reissue an identifier that is still stored, and the
--   failure would present as a duplicate-key error long after the cause.
-- Assumptions: the bound is the largest value sixteen digit characters can
--   express, so an allocation that would not fit the column fails at the sequence
--   rather than being silently truncated into a colliding identifier. BIGINT holds
--   it with three decimal orders to spare.
CREATE SEQUENCE ledger.transaction_id_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9999999999999999
    NO CYCLE;

COMMENT ON SEQUENCE ledger.transaction_id_seq IS
    'Allocates transaction identifiers for the interactive add and bill-payment paths. A caller renders the allocated value as sixteen zero-padded digit characters. Replaces a max()+1 derivation that was not atomic across concurrently running tasks. Does not serve the interest job, which composes its identifiers from a business-date prefix.';

-- Assumptions: the sequence is advanced past every identifier already stored, and
--   the advance considers ONLY identifiers that are entirely digits. That filter is
--   the load-bearing part of this statement rather than defensive noise. This
--   context stores identifiers in two formats: the sequence format, sixteen digits;
--   and the format the interest job composes, which is a ten-character business-date
--   token followed by a six-digit suffix (app/cbl/CBACT04C.cbl:474-480). The
--   baseline's own business-date parameter is compact and numeric -- app/jcl/
--   INTCALC.jcl:22 passes '2022071800' -- so in the reference tree both formats are
--   numeric and the maximum is well defined across both. A migrated deployment that
--   ever admitted a separated token would store a value that is not a number, and
--   casting it here would abort this migration. Filtering to digits keeps the
--   advance defined regardless, and the identifier formats cannot collide because a
--   date-prefixed value is necessarily larger than any value this sequence issues in
--   the era the data covers.
-- Assumptions: setval's third argument is false, so the value passed is the value
--   the NEXT allocation returns rather than the last one used, and the expression
--   therefore adds one to the stored maximum. Passing the bare maximum with a third
--   argument of true would be equivalent, and the `+ 1` form is chosen for a
--   concrete reason: it is also what keeps the empty-table case legal. This sequence
--   declares MINVALUE 1, and `setval` rejects a value below the minimum, so the
--   otherwise-natural `coalesce(max, 0)` with a false third argument would abort this
--   migration on any deployment whose ledger has not been loaded yet -- which is
--   every fresh environment.
-- Assumptions: coalesce supplies 0 before the addition, so an empty table yields a
--   first allocation of 1. That matches the reference, which moves zeros into its key
--   when the browse finds no record -- at app/cbl/COTRN02C.cbl:689 and
--   app/cbl/COBIL00C.cbl:488 -- so the identifier it derives is likewise 1.
SELECT setval(
    'ledger.transaction_id_seq',
    coalesce(
        (SELECT max(transaction_id::BIGINT)
           FROM ledger.transactions
          WHERE transaction_id ~ '^[0-9]{16}$'),
        0) + 1,
    false
);
