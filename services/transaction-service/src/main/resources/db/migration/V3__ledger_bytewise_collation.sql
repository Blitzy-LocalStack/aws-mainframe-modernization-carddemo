-- =============================================================================
-- V3__ledger_bytewise_collation.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Pins the ordering-critical character columns of the `ledger` schema to the
--   bytewise `C` collation, so that every ordered read of this schema resolves
--   to the byte-by-byte comparison the reference sort steps perform, whatever
--   collation the database it is deployed into happens to default to.
--
--   This migration alters four columns and nothing else. It creates no table, no
--   index and no constraint, and confers no privilege. It additionally REFUSES to
--   run while a view depends on the column it is about to retype, and VERIFIES
--   its own effect before it finishes -- both of which are stated with their
--   reasoning at the statements that carry them.
--
--   Refactoring Rationale: this file is the single migration for a correction that
--   was written three times over, once per ordering-critical concern: one draft
--   pinned the identifier alone with a dependent-view precondition and a
--   post-condition check, a second pinned the identifier with the same
--   precondition, and this one pinned all four columns. They could not coexist --
--   Flyway admits one script per version, and three scripts numbered V3 makes the
--   whole history unresolvable -- and merging them was preferable to choosing one,
--   because each carried something the others did not. The four-column reach is
--   from this draft; the precondition and the verification are from the first.
--
-- Why this migration exists
-- -----------------------------------------------------------------------------
--   Refactoring Rationale: the migrated ordered reads were correct only by
--   accident of the test engine. Review found that the batch parity gate proved
--   a bytewise order and a linguistic order differ, and then exercised the
--   actual job under a container whose initdb default collation happens to be
--   `C` -- so the gate stayed green while production carried no explicit
--   collation anywhere: not on the column, not in the derived finder
--   (services/batch-service/.../TransactionRepository.java
--   findAllByOrderByTransactionIdAsc), and not in the harness that mirrors this
--   schema for tests. Deployed against a database created with a linguistic
--   default -- which is the ordinary case, and the AWS default -- the same code
--   would have emitted a different order, and the reference comparison would
--   have failed on the produced dataset rather than in any test.
--
--   Assumptions: the contract being preserved is DFSORT's `CH` format, which is
--   a bytewise character comparison. app/jcl/COMBTRAN.jcl declares the symbol
--   `TRAN-ID,1,16,CH` at L28 and orders by it ascending at L30;
--   app/jcl/TRANREPT.jcl declares `TRAN-CARD-NUM,263,16,ZD` at L41 and orders by
--   it ascending at L46. A collation that gives punctuation no primary weight --
--   the behaviour of a conventional linguistic collation -- reorders exactly the
--   identifiers the interest accrual pass produces, because
--   app/cbl/CBACT04C.cbl:476-480 concatenates its ten-character token into the
--   key unchanged and one committed layout of that token carries hyphens.
--
--   Assumptions: the pin is placed on the COLUMN and not in each query. A
--   column's collation governs every comparison on that column, so one
--   declaration reaches the derived finders, the JPQL queries, the native
--   queries, the index that backs them and any future reader, whereas a
--   query-level `COLLATE` reaches only the statement that carries it. Spring
--   Data derived finders cannot express `COLLATE` at all, and HQL has no
--   portable form of it, so a query-level pin would have forced every ordered
--   read in three services into a native statement to say what one column
--   declaration says here.
--
--   Alternatives Considered: pinning the DATABASE default collation instead, by
--   creating the database with `LOCALE_PROVIDER icu` and a bytewise locale.
--   Rejected on two grounds. The database is provisioned by Terraform and its
--   default collation is fixed at creation and cannot be altered afterwards, so
--   the guarantee would live outside the artifact that declares the schema and
--   could not be restored by a migration if it were ever missed. And it would
--   silently change the comparison of every VARCHAR column in every schema of
--   the cluster, including the descriptive ones -- merchant and customer names
--   -- where a linguistic order is the correct one for a human-facing list.
--
--   Alternatives Considered: `ALTER DATABASE ... SET default_text_search_config`
--   or a session-level setting. Rejected because neither governs comparison:
--   `lc_collate` is not a settable run-time parameter, and a session setting
--   would leave the physical index ordered one way and the query comparing
--   another, which is worse than either default alone -- the planner would stop
--   using the index for the ordered walk and the walk would sort in memory.
--
--   Trade-offs: rewriting a column's collation rewrites the table and rebuilds
--   the indexes and constraints that depend on it, so this migration is not free
--   on a loaded ledger. It is accepted because it runs once, because the
--   alternative is a dataset whose record order silently disagrees with the
--   reference on the accrual identifiers alone, and because the cost falls at
--   migration time rather than on every ordered read.
--
--   Trade-offs: `C` gives up locale-aware ordering on these four columns.
--   Nothing is lost: all four hold digits, uppercase letters and hyphens
--   composed by a program, never text a person reads in a sorted list. The
--   descriptive columns of the same tables -- description, merchant_name,
--   merchant_city -- are deliberately NOT pinned, for exactly the reason the
--   database-default alternative was rejected.
-- =============================================================================


-- Assumptions: transaction_id is the primary key, so this statement rebuilds
--   pk_transactions as well as rewriting the column. That is intended: the index
--   is what the ordered walk at
--   services/batch-service/.../TransactionRepository.java
--   findAllByOrderByTransactionIdAsc scans, and an index built under the old
--   collation would still be ordered the old way. Rebuilding it under the pin is
--   what makes the ordered read a single ordered index scan AND bytewise at the
--   same time.
--   first time either changed. Naming the remedy keeps ownership where it is.
DO $$
DECLARE
    dependents TEXT;
BEGIN
    SELECT string_agg(DISTINCT format('%I.%I', view_schema, view_name), ', ')
      INTO dependents
      FROM information_schema.view_column_usage
     WHERE table_schema = 'ledger'
       AND table_name = 'transactions'
       AND column_name = 'transaction_id';

    IF dependents IS NOT NULL THEN
        RAISE EXCEPTION
            'cannot pin byte collation on ledger.transactions.transaction_id'
            ' while these views depend on it: %', dependents
            USING HINT =
                'drop those views, re-run this migration, then re-apply'
                ' data-migration/sql/V1__reporting_views.sql, which recreates'
                ' them; the documented order runs this migration first, so'
                ' reaching this message means the views were created early';
    END IF;
END $$;


-- Assumptions: the type is restated as CHAR(16) rather than omitted, because
--   PostgreSQL accepts a collation change only as part of a SET DATA TYPE. The
--   width is unchanged and is the one V1__ledger.sql declares, derived from
--   TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy L5.
ALTER TABLE ledger.transactions
    ALTER COLUMN transaction_id SET DATA TYPE CHAR(16) COLLATE "C";


-- Assumptions: card_num carries the pin although it is nullable and is not part
--   of any key, because idx_transactions_card_num is the access path that
--   replaced the reference's physically re-sorted extract, and the sequence it
--   supplies is compared against a golden image produced by DFSORT's bytewise
--   comparison. The index is rebuilt by this statement for the same reason the
--   primary key is above.
ALTER TABLE ledger.transactions
    ALTER COLUMN card_num SET DATA TYPE CHAR(16) COLLATE "C";


-- Assumptions: the two category-balance key components are pinned as a pair,
--   because the walk that reads this table orders by all three key columns
--   together -- account identifier, then type, then category -- and the account
--   identifier is a BIGINT whose comparison no collation touches. Pinning one of
--   the two character components and not the other would leave the composite
--   order partly bytewise and partly linguistic, which is harder to reason about
--   than either.
-- Assumptions: this order is a control-break contract and not a presentation
--   choice. app/cbl/CBACT04C.cbl reads TCATBALF in key order and detects an
--   account change at L194-L205 to close one account's accrual and open the
--   next, so a reordering that interleaved two accounts' category rows would
--   break the account totals rather than merely reorder a report.
ALTER TABLE ledger.transaction_category_balances
    ALTER COLUMN type_cd SET DATA TYPE CHAR(2) COLLATE "C";

ALTER TABLE ledger.transaction_category_balances
    ALTER COLUMN category_cd SET DATA TYPE CHAR(4) COLLATE "C";


COMMENT ON COLUMN ledger.transactions.transaction_id IS
    'Sixteen-character transaction identifier, TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy L5. Collation is pinned to "C" so that ORDER BY and keyset comparisons on this column are byte-wise, reproducing SORT FIELDS=(TRAN-ID,A) over TRAN-ID,1,16,CH at app/jcl/COMBTRAN.jcl L28-L30. Do not remove the pin: accrual identifiers can contain a hyphen, which a linguistic collation weights differently and which would reorder the combined dataset and move keyset page boundaries.';


--   that matches the database default and this one deliberately does not.
DO $$
DECLARE
    pinned TEXT;
    relation TEXT;
    attribute TEXT;
BEGIN
    -- Assumptions: ALL FOUR pinned columns are verified and not only the
    --   identifier, because a partial application is the failure this block
    --   exists to catch: three pinned columns and one inherited would leave the
    --   composite category-balance order half bytewise, which is harder to
    --   diagnose than none of them being pinned. The four are named as
    --   (relation, attribute) pairs so the message says which one is wrong.
    FOR relation, attribute IN
        SELECT * FROM (VALUES
            ('ledger.transactions', 'transaction_id'),
            ('ledger.transactions', 'card_num'),
            ('ledger.transaction_category_balances', 'type_cd'),
            ('ledger.transaction_category_balances', 'category_cd')
        ) AS pinned_columns(rel, att)
    LOOP
        SELECT c.collname
          INTO pinned
          FROM pg_attribute AS a
          JOIN pg_collation AS c ON c.oid = a.attcollation
         WHERE a.attrelid = relation::regclass
           AND a.attname = attribute;

        IF pinned IS DISTINCT FROM 'C' THEN
            RAISE EXCEPTION
                '%.% carries collation % after this migration, expected C',
                relation, attribute, coalesce(pinned, 'none');
        END IF;
    END LOOP;
END $$;
