-- =============================================================================
-- services/auth-service/src/main/resources/db/migration/V8__auth_addressable_user_id.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   Narrows auth.users.user_id from the 94 printable invariant characters to the
--   ADDRESSABLE domain -- the upper-case ASCII letters and the digits -- so that
--   every stored key is a legal single URI path segment, and validates the new
--   guard once no row violates it.
--
--   The key is spoken in a path segment on three published routes. GET, PUT and
--   DELETE on /api/v1/auth/users/{userId} carry it there, and the create
--   operation returns it inside a Location header naming the row it made. The
--   domain V6__auth_canonical_user_id.sql admits contains characters that cannot
--   survive that journey: '/' ends the segment, '?' opens a query, '#' opens a
--   fragment and is never transmitted at all, '%' opens a percent escape the
--   container decodes before any handler sees it, and '\' is rewritten by some
--   intermediaries. A row keyed 'A/B' was therefore storable, and then reachable
--   by none of the three routes -- the same class of unreachable row V4, V5 and
--   V6 each closed one shape of, arriving this time through the address rather
--   than through the fold.
--
--   The service-side counterpart is the domain check in
--   services/auth-service/src/main/java/com/carddemo/auth/service/UserService.java,
--   whose canonicalKey holds the trimmed submission to [A-Za-z0-9] before
--   folding it, and the published contract states the same domain as the pattern
--   ^[A-Za-z0-9]{1,8}$ on the create body and on the path parameter. This
--   constraint is the third statement of that one rule, for a writer that
--   reaches the table without passing through the service.
--
-- Parameters:
--   A migration takes no arguments, so its inputs are the Flyway state and the
--   configuration it is applied under. All of them are declared in sibling
--   resources rather than here, identically to the seven migrations before it:
--   spring.flyway.schemas and spring.flyway.default-schema pin the target schema
--   and the history table's home, spring.flyway.locations pins discovery at
--   classpath:db/migration, and the executing role is the schema's owner because
--   ALTER TABLE requires it.
--
--   - Applied-version state: the schema history table in schema auth. Version 8
--     is the identifier that decides whether this script runs; a history row
--     already recording it means this file is skipped.
--
-- Return values:
--   One schema object, one comment, and a conditional validation.
--
--   - Check constraint ck_users_user_id_addressable on auth.users.user_id,
--     holding the key to the upper-case letters and digits by the same btrim
--     membership test V6 uses, for the same determinism reason.
--   - A comment on that constraint, so a writer that trips it is told what to do
--     rather than only which name refused it.
--   - ck_users_user_id_addressable is VALIDATED when no stored key falls outside
--     the domain, and left NOT VALID with a WARNING naming the residue when one
--     does.
--
--   Deliberately absent: no row is rewritten, no column is added, altered,
--   widened or dropped, no index is created, no privilege changes, and neither
--   ck_users_user_id_canonical nor ck_users_user_id_folded is dropped -- see the
--   WHY block below for why the wider pair is kept beside this one.
--
--   Deliberately absent, and unlike V6: there is NO reconciling UPDATE. V6 could
--   rewrite a non-canonical key because every value it refused had exactly one
--   canonical form to be moved to -- upper(btrim(...)) of itself. A key outside
--   THIS domain has no such form: nothing in 'A/B' says whether the intended
--   identifier was 'AB', 'A0B' or something else, and inventing one would change
--   which person an identifier names. An unattended migration is the wrong place
--   for that decision, so such rows are reported and left exactly as found.
--
-- Exceptions or errors:
--   1. A row whose key falls outside the domain. The constraint is added NOT
--      VALID and the block at the end counts the residue, so a database holding
--      one starts and serves; the row stays exactly as reachable as it already
--      was, which for a key carrying '/', '?', '#' or '%' is not at all. An
--      operator lists them with:
--          SELECT u.user_id AS stored,
--                 btrim(u.user_id, '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ')
--                     AS refused_characters,
--                 u.first_name, u.last_name, u.user_type, u.cognito_sub
--            FROM auth.users u
--           WHERE btrim(u.user_id, '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ') <> ''
--           ORDER BY stored;
--      Resolve each as a business decision, not as a rewrite: identify the
--      person from first_name, last_name and cognito_sub, create the row again
--      under an identifier this domain admits, move the pool account as
--      docs/runbooks/data-migration.md describes, and withdraw the old row and
--      its account. Once the list is empty, promote the constraint so a later
--      reader is not misled by a NOT VALID marker:
--          ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_addressable;
--   2. Insufficient privilege. ALTER TABLE requires the table's owner, which is
--      the role data-migration/sql/V0__schemas_and_roles.sql establishes for this
--      schema and the role each per-service migration is expected to run as.
--      Applying this script as any other role fails naming the table.
--   3. Checksum immutability. The narrowing arrives as a new versioned migration
--      and NOT as an edit to V6, because Flyway refuses a script whose checksum
--      changed after it was applied -- so editing V6's character set in place
--      would stop every database that already ran it from migrating at all. V6
--      is left byte-identical; its Alternatives Considered block, which rejected
--      the letters-and-digits domain as "narrower than necessary", was written
--      before the addressability defect was found and is superseded here rather
--      than edited there.
--   4. No destructive path exists. clean is disabled by the profile overlay, and
--      this script contains no clean, no drop, no truncate, no delete and no
--      update.
--
-- Provenance:
--   Every app/** path cited in this file is REFERENCE-ONLY and is never modified
--   by this migration or by anything else in the migrated trees.
--
--   Primary sources: SEC-USR-ID is PIC X(08) at app/cpy/CSUSR01Y.cpy:18, which is
--   where the width comes from and which declares no character class at all. All
--   ten identifiers in the committed extract app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS
--   -- ADMIN001 through ADMIN005 and USER0001 through USER0005 -- draw on the
--   upper-case letters and digits alone, which is where this domain comes from.
--   The narrowing is registered as D-USER-ID-CANONICAL-DOMAIN in
--   docs/architecture/cobol-to-service-traceability.md.
--
--   Documentation convention: docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL
--   section requires this header block plus a rationale on each non-obvious
--   constraint, and records that nothing in the build inspects a migration's
--   comments.
--
-- Why the term is a btrim membership test rather than a regular expression
--   Assumptions: the term is btrim(user_id, <set>) = '', which is empty exactly
--   when every character of the value is in the set, and it is chosen for
--   DETERMINISM -- the identical reason V6 gives. A bracket range in a
--   PostgreSQL regular expression -- '[A-Z0-9]', '[[:alnum:]]' -- is interpreted
--   through the database''s collation, so the same predicate can admit different
--   characters on two databases and the guard would then mean something
--   different from the service''s pattern depending on where it ran. btrim
--   compares characters against an explicit set and consults no collation.
--
-- Why the set carries the upper case only, where V6''s carried both
--   Assumptions: this column holds the CANONICAL form, which is folded, so the
--   lower-case letters are already refused by the first term of
--   ck_users_user_id_canonical and admitting them here would state a domain no
--   stored value can occupy. V6 deliberately included them so its set could be
--   read against the service''s pattern character for character; that pattern is
--   now applied to the SUBMITTED value, before the fold, so the two are no longer
--   the same string by construction -- ^[A-Za-z0-9]{1,8}$ in the contract and the
--   service, its upper-case half here -- and the relationship between them is
--   stated in this block instead of being inferred from the spelling.
--
-- Why the wider guards are kept
--   Alternatives Considered: dropping ck_users_user_id_canonical, on the ground
--   that this set is a subset of its 94 characters and the fold term makes the
--   lower-case half unreachable. Rejected: that constraint carries the
--   fold-and-trim comparison, which this one cannot see at all -- a key of
--   ' ABC' has no character outside this set once the padding is stripped, so
--   only the canonical guard refuses it -- and ck_users_user_id_folded is the
--   only guard that refuses an all-blank key, as V6''s own measurement records.
--   The three are complementary: each refuses a shape the others admit, so a
--   refusal naming one of them names the term that actually caught the row.
-- =============================================================================

-- WHY : Trade-offs: the constraint is added NOT VALID and validated below only
--       when nothing violates it, for the reason V6 records for the same choice.
--       Adding it VALIDATED would scan the table and FAIL on precisely the
--       databases that hold a key outside the domain, and a failed migration
--       stops the service from starting at all -- replacing an unreachable row
--       that a query can find with an outage, on the databases most likely to be
--       affected. NOT VALID skips only the initial scan: every subsequent INSERT
--       and every UPDATE of an existing row is checked, so the invariant holds
--       from here forward either way.
-- WHY : Assumptions: the set is written out in full rather than generated from a
--       range, because a generated range would reintroduce the collation
--       dependence the membership test exists to avoid. The digits precede the
--       letters so the literal reads in the order a reader checks it against
--       the extract's identifiers, which begin with letters and end in digits.
-- WHY : Assumptions: the guard says nothing about WIDTH, which the CHAR(8)
--       column already fixes, and nothing about emptiness, which
--       ck_users_user_id_folded already refuses. One term per invariant is what
--       lets a refusal name the invariant it caught.
ALTER TABLE auth.users
    ADD CONSTRAINT ck_users_user_id_addressable
    CHECK (btrim(user_id, '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ') = '')
    NOT VALID;

-- WHY : Assumptions: the comment is written in the same migration that adds the
--       constraint, because the constraint alone does not tell an operator who
--       trips it what to do. A refusal on INSERT reports a constraint name; this
--       is where that name explains itself, and it names both the service method
--       that derives the key and the contract facet that publishes the domain,
--       so the three definitions cannot drift apart unnoticed.
COMMENT ON CONSTRAINT ck_users_user_id_addressable ON auth.users IS
    'user_id is drawn only from the upper-case ASCII letters and the digits, so '
    'that every stored key is a legal single URI path segment: GET, PUT and '
    'DELETE on /api/v1/auth/users/{userId} carry it in the path and the create '
    'operation returns it in a Location header. UserService#canonicalKey holds '
    'the trimmed submission to [A-Za-z0-9] before folding it and the published '
    'contract states the same domain as ^[A-Za-z0-9]{1,8}$, so a refusal here '
    'means a writer reached this table without passing through either. A key '
    'this constraint refuses has NO derivable canonical form -- unlike a '
    'non-canonical key, which V6 could rewrite -- so create the row again under '
    'an identifier the domain admits rather than guessing at one; the procedure '
    'is in the header of V8__auth_addressable_user_id.sql. This does NOT '
    'subsume ck_users_user_id_canonical or ck_users_user_id_folded: the first '
    'carries the fold-and-trim comparison this one cannot see and the second '
    'refuses an all-blank key that this one admits. All three are enforced. '
    'Added NOT VALID because databases written before this domain was enforced '
    'may hold a key outside it.';

-- WHY : Refactoring Rationale: validation is CONDITIONAL and in the migration,
--       following the arrangement V6 established rather than leaving a manual
--       step an operator has to remember. Every database on which nothing has
--       ever violated the domain -- which is every fresh one, and every one
--       whose keys came from this service -- ends this migration with the
--       constraint proven, so the NOT VALID marker means what it says wherever
--       it survives.
-- WHY : Assumptions: the residue is reported as a WARNING and not raised. An
--       exception here would abort the migration and stop the service from
--       starting, which is the outage the NOT VALID choice above exists to
--       avoid; a warning reaches the deployment log, where the operator
--       procedure in this file's header is what acts on it.
-- WHY : Assumptions: the count is taken inside the block rather than the
--       validation being attempted and its failure caught, because a failed
--       VALIDATE inside a plpgsql block would still have to be handled and would
--       leave the transaction needing a savepoint, while the count is one
--       sequential scan of a table that holds one row per operator.
DO $$
DECLARE
    unaddressable bigint;
BEGIN
    SELECT count(*) INTO unaddressable
      FROM auth.users
     WHERE btrim(user_id, '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ') <> '';

    IF unaddressable = 0 THEN
        ALTER TABLE auth.users VALIDATE CONSTRAINT ck_users_user_id_addressable;
    ELSE
        RAISE WARNING 'auth.users holds % row(s) whose key carries a character '
                      'outside the addressable domain, so the row cannot be '
                      'named in a URI path segment and is reachable by none of '
                      'the three single-user routes; '
                      'ck_users_user_id_addressable remains NOT VALID. Such a '
                      'key has no derivable canonical form: follow the '
                      'procedure in V8__auth_addressable_user_id.sql, then run '
                      'ALTER TABLE auth.users VALIDATE CONSTRAINT '
                      'ck_users_user_id_addressable.',
                      unaddressable;
    END IF;
END
$$;
