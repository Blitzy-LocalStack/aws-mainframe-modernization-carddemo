-- =====================================================================
-- V4__authorization_outbox_send_acceptance.sql
--
-- Purpose: make a reply's SEND ACCEPTANCE and the deadline it was stamped
--          with durable, so a publisher can tell an accepted send from an
--          unsent one and can never enqueue a second copy of one reply.
--
-- WHY : Refactoring Rationale: OutboxPublisher's publication was a send
--       followed by a separate status transition, and nothing recorded that
--       the broker had accepted the message. When the send succeeded and
--       the transition did not -- a lost connection, a statement timeout,
--       any fault between two round trips -- the row stayed pending with no
--       evidence of the accepted send, so the next pass sent again. Inside
--       the queue's five-minute deduplication window that second send is
--       SUPPRESSED and answered exactly like an accept, so the row was then
--       marked published against a message the broker had held since the
--       first attempt; outside that window it is a genuine second reply to
--       one authorization. Both outcomes are invisible in the schema, which
--       is what these columns fix: sent_at is written in its own
--       transaction the moment the broker answers, so the state that
--       follows a send is a stored fact rather than an inference from the
--       absence of a publication instant.
-- WHY : Assumptions: send_expires_at is the deadline the ACCEPTED send
--       carried, written in the same statement as the acceptance itself.
--       expires_at is the instant the deciding transaction chose and
--       created_at is when it chose it, so the WINDOW a requester asked for
--       is their difference; the publisher re-applies that window from the
--       send it is making, because the reference denominates the deadline as
--       a duration counted from the put at
--       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:750 rather than as
--       an absolute instant, and docs/adr/ADR-004-messaging.md records that
--       sending a decision-time instant makes any delay at all a guaranteed
--       non-delivery. What went wrong was not the recomputation but that it
--       was stored NOWHERE: a retry after an accepted send was suppressed by
--       the queue and then marked published against a value nothing had
--       sent, so the row could not say what deadline the requester received.
-- WHY : Alternatives Considered: fixing the deadline at the FIRST attempt and
--       reusing it, so two attempts at one reply are byte-identical.
--       Rejected because a first attempt that never reached the broker is the
--       commonest transport fault there is, and a frozen deadline would then
--       be stamped on a message sent after it had already passed -- the
--       guaranteed non-delivery ADR-004 exists to prevent. The duplicate that
--       frozen stamping was meant to prevent is prevented by sent_at instead.
-- WHY : Alternatives Considered: overwriting expires_at in place at each send
--       instead of adding a column. Rejected because the window is derived
--       from expires_at MINUS created_at, so the first overwrite would
--       destroy the only record of the window and the next send would compute
--       a different one from the value it had just written -- the column would
--       stop being the decision's own value while still being named as though
--       it were.
-- WHY : Alternatives Considered: an attempt-specific deduplication identity,
--       which would stop the queue suppressing a retry at all and would move
--       duplicate collapsing to the consumer. Rejected because the consumer
--       of the reply queue is the external acquirer, which section 0.2.2 of
--       the technical specification places outside this migration's scope, so
--       the guarantee would depend on software this project does not build;
--       and because sections 0.4.1.8 and 0.7.6 name the card number and the
--       acquirer's transaction identifier as the frozen queue identities, so
--       a per-attempt identity would make one authorization's messages
--       unrecognisable to any other party written to that contract.
-- WHY : Alternatives Considered: editing V1 in place, which for a schema no
--       deployed database holds would reach the same end state in one file.
--       Rejected for the reason V3 records: Flyway validates by checksum, so
--       any database that has already run V1 fails its next start with a
--       mismatch naming no cause. A follow-on version is idempotent under
--       that validation and is the form V2 and V3 already use.
-- WHY : Trade-offs: three columns and one stored deadline are added to a
--       table whose published rows are swept on a retention schedule, so the
--       cost is four nullable columns on a bounded population. What is bought
--       is that the ambiguous window shrinks from "the whole publication
--       transition" to "one short transaction that writes four columns", and
--       that a failure after it leaves a row whose next pass RECONCILES --
--       marking published without sending -- instead of sending again.
-- =====================================================================

-- WHY : Assumptions: all four columns are NULLABLE with no default, because
--       every one of them records something that has not happened yet when
--       the row is inserted. The deciding transaction commits the reply
--       before any send is attempted -- that ordering is the whole reason
--       this table exists -- so a NOT NULL column here would have to be
--       given a placeholder that a reader could not distinguish from a real
--       acceptance.
ALTER TABLE auth_reply_outbox
    ADD COLUMN send_expires_at        TIMESTAMP(6),
    ADD COLUMN sent_at                TIMESTAMP(6),
    ADD COLUMN broker_message_id      VARCHAR(100),
    ADD COLUMN broker_sequence_number VARCHAR(64);

-- WHY : Assumptions: the broker's identity may only be present on a row that
--       also records WHEN the broker answered, because the identity alone
--       cannot be acted on: the reconciling pass tests the instant, and a
--       row carrying an identifier without one would be sent a second time
--       while looking, to a reader, as though it had already gone.
ALTER TABLE auth_reply_outbox
    ADD CONSTRAINT ck_auth_reply_outbox_broker_identity
        CHECK (broker_message_id IS NULL OR sent_at IS NOT NULL);

-- WHY : Assumptions: the FIFO sequence number is subordinate to the message
--       identifier rather than independent of it. The queue returns both
--       from one acceptance and neither from anything else, so a row holding
--       a sequence number with no message identifier could only come from a
--       partial write, which is exactly what a constraint should refuse.
ALTER TABLE auth_reply_outbox
    ADD CONSTRAINT ck_auth_reply_outbox_broker_sequence
        CHECK (broker_sequence_number IS NULL OR broker_message_id IS NOT NULL);

-- WHY : Assumptions: a stamped deadline can only exist on a row that records
--       BOTH an accepted send and a decision-time expiry. It is written in
--       the same statement as the acceptance, so one without the other is a
--       partial write; and the window is derived from expires_at, so a
--       stamped value on a row with no expiry would be a deadline this
--       service invented for a requester that asked for none -- which section
--       0.7.6 of the technical specification resolves as "no attribute", not
--       "a default one".
ALTER TABLE auth_reply_outbox
    ADD CONSTRAINT ck_auth_reply_outbox_send_deadline
        CHECK (send_expires_at IS NULL
               OR (sent_at IS NOT NULL AND expires_at IS NOT NULL));

-- WHY : Assumptions: the partial index covers only rows that were ACCEPTED
--       and not yet published, which is the population the reconciling pass
--       and an operator investigating an unconfirmed publication both read.
--       That set is empty in normal operation -- a row leaves it within one
--       transaction of entering it -- so the index is small by construction
--       and its selectivity does not depend on the table's size.
CREATE INDEX idx_auth_reply_outbox_unconfirmed
    ON auth_reply_outbox (sent_at)
    WHERE sent_at IS NOT NULL
      AND published_at IS NULL;
