-- =====================================================================
-- V3__authorization_outbox_fifo_identities.sql
--
-- Purpose: rename the two outbox columns that carry the reply queue's
--          first-in-first-out metadata, because the values they hold are
--          no longer derived.
--
-- WHY : Refactoring Rationale: V1 named these columns order_group_token
--       and deduplication_token because the listener stored a keyed,
--       purpose-scoped derivation of the card number and of the
--       card-and-transaction pair in them, and the publisher sent those
--       derivations as the MessageGroupId and MessageDeduplicationId. That
--       is not what the agreed design specifies. Sections 0.4.1.8 and 0.7.6
--       of the technical specification freeze the two identities as the
--       LITERAL values -- MessageGroupId is the card number and
--       MessageDeduplicationId is the acquirer's transaction identifier --
--       and the specification is the frozen source of truth this migration
--       aligns to rather than reinterprets. With the derivation withdrawn,
--       a name ending in "token" would describe the column as something it
--       is not, and these two columns are named in the messaging contract,
--       in the data-model mapping document and in one native statement, so
--       a misleading name would propagate into all three.
-- WHY : Alternatives Considered: keeping the V1 names and explaining in a
--       comment that the suffix is historical. Rejected because the comment
--       would have to be repeated at every one of the four sites that names
--       the column, and because a column called ..._token holding a
--       sixteen-digit primary account number is precisely the kind of
--       name-versus-content disagreement that leads a later reader to
--       assume the value is already protected when it is not.
-- WHY : Alternatives Considered: editing V1 in place, which for a schema
--       that no deployed database holds would produce the same end state
--       with no second file. Rejected because Flyway validates a migration
--       by checksum: any database that has already run V1 -- a developer's
--       container, a review environment -- would fail validation on the next
--       start with a mismatch that names no cause. A rename applied as its
--       own version is idempotent under that validation and is the form the
--       sibling contexts already use for a follow-on change.
-- WHY : Assumptions: RENAME COLUMN is a catalogue-only operation on
--       PostgreSQL. It rewrites no rows, and the dependent partial index
--       idx_auth_reply_outbox_group -- declared over
--       (order_group_token, outbox_id) in V1 -- follows the rename
--       automatically, so no index is dropped and rebuilt and the publisher's
--       group claim keeps the access path it was written for.
-- =====================================================================

-- WHY : Assumptions: the column keeps its VARCHAR(128) width even though the
--       value it now holds is a sixteen-character card number. The width is
--       not a contract on the value -- nothing reads the column as fixed
--       width -- and narrowing it would rewrite the table for no benefit
--       while removing the headroom a longer group identity would need if the
--       specification's grouping key were ever widened.
ALTER TABLE auth_reply_outbox
    RENAME COLUMN order_group_token TO order_group_id;

-- WHY : Assumptions: the same reasoning applies to the deduplication
--       identity, which now holds the fifteen-character transaction
--       identifier the reply's second field carries.
ALTER TABLE auth_reply_outbox
    RENAME COLUMN deduplication_token TO deduplication_id;
