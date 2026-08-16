-- =====================================================================
-- services/reference-service/src/main/resources/db/migration/
--   V3__reference_inquiry_reply_ledger.sql
--
-- Purpose: give the asynchronous date-conversion exchange a durable record
--          of the reply it has already produced for a request, so that a
--          redelivery of that request is answered with the FIRST answer
--          rather than with a second, later one.
--
-- WHY : Refactoring Rationale: this table did not exist, and its absence
--       rested on an argument that was right about the baseline and wrong
--       about the target. The argument recorded on
--       com.carddemo.reference.config.SqsConfig was that
--       app/app-vsam-mq/cbl/CODATE01.cbl brackets its get, its reply put
--       and its error put in ONE unit of work -- syncpoint at L275, get
--       options at L296, reply put options at L379, error put options at
--       L416 -- so no window exists in which work is committed and the
--       reply is lost, and that this "maps directly onto a visibility
--       period plus delete-on-success". The first half is true. The second
--       does not follow: on the target the SEND completes before the
--       listener acknowledges, and the acknowledgement is a separate
--       network call, so a task killed between the two -- or an
--       acknowledgement lost between them -- leaves the request visible
--       again and hands it to another delivery.
-- WHY : Refactoring Rationale: for THIS flow that window is worse than a
--       duplicate, and that is the specific defect this table closes. The
--       reply body is the system date and time, read from the clock at the
--       moment the reply is composed -- CODATE01.cbl asks the clock at
--       L343 to L345 and formats it at L347 to L353 -- so a redelivery
--       does not recompose the same answer, it recomposes a LATER one. A
--       requester pairing answers to questions on its correlation
--       identifier therefore receives two replies bearing one identifier
--       and carrying two different timestamps, with nothing on the wire to
--       say which of the two the exchange regarded as its answer. The
--       earlier reasoning that "the answer is the clock, so two answers to
--       one request are the same answer" had the implication exactly
--       backwards: being a function of the clock is precisely what makes
--       the two answers DIFFER.
-- WHY : Assumptions: the remedy is to record the composed reply, commit
--       it, and only then send it, so that a redelivery re-sends the
--       recorded BYTES instead of asking the clock again. The first
--       answer is thereby the only answer this exchange ever gives for one
--       request, whichever delivery gives it.
-- WHY : Alternatives Considered: relying on the queue's own deduplication.
--       Rejected for two independent reasons. Deduplication acts on a
--       PUBLISH to a queue within a bounded window and says nothing about
--       a redelivery, which is the same message being handed to a consumer
--       again on purpose; and it is offered only on FIFO queues, while
--       docs/adr/ADR-004-messaging.md keeps the inquiry queues standard
--       because the flow has no ordering requirement. The two mechanisms
--       address different events, and only this one addresses the event
--       that produced the second timestamp.
-- WHY : Alternatives Considered: making the whole exchange one transaction
--       spanning the database and the queue. Rejected because that is a
--       distributed transaction across two resource managers, and AAP
--       section 0.7.6 records this migration's elimination of two-phase
--       commit as a property to keep rather than a gap to refill. What is
--       reachable without it -- record, commit, send, mark -- narrows
--       divergence to a single crash window in which the SAME bytes are
--       sent twice, instead of leaving a differing answer reachable on
--       every redelivery.
-- WHY : Alternatives Considered: an outbox drained by a poller, which is
--       the shape the authorization context uses for its reply
--       (auth_reply_outbox in V1__authorization.sql). Rejected here for a
--       different requirement rather than a different opinion: that
--       context must guarantee a reply EXISTS for every committed
--       decision, because it writes business rows the reply describes. This
--       exchange writes no business row at all -- it reads nothing and
--       decides nothing -- so there is no committed state a missing reply
--       would contradict. What it needs is the narrower guarantee that one
--       request is never answered with two different answers, and a
--       claim-then-send ledger provides that with no second background
--       component to operate.
-- WHY : Assumptions: this migration is additive and is numbered V3 because
--       V1__reference.sql defines the six reference tables and
--       V2__seed_reference.sql seeds them; it creates one table and one
--       index, alters nothing that exists, and the runtime role reaches it
--       through the default privileges data-migration/sql/
--       V0__schemas_and_roles.sql sets for carddemo_reference_owner in
--       this schema -- SELECT, INSERT and UPDATE, which is exactly the
--       three verbs this ledger uses and no more.
-- WHY : Trade-offs: this is the first table in the reference schema that
--       holds no reference DATA. The schema is otherwise six seeded
--       lookup tables, so a reader arriving at it will not expect an
--       operational row here. The alternative was a schema of its own,
--       which would need a role, a grant set and a search-path entry to
--       hold one table read only by the consumer that writes it; keeping
--       it beside the context that owns the exchange is what makes the
--       exchange's whole state reachable under one grant, and the comment
--       on the table below says plainly that it is not reference data.
-- =====================================================================

CREATE TABLE IF NOT EXISTS reference.inquiry_reply_ledger (

    -- WHY : Assumptions: the key is the QUEUE SERVICE's own identifier for
    --       the delivery, and never a value this service invents. It is
    --       stable across every redelivery of one message -- only the
    --       receipt handle changes -- which is the only reason this row can
    --       be found again, and it is unique per accepted send, which is
    --       what stops two distinct requests colliding onto one row. A
    --       locally minted key would differ on every delivery, so the table
    --       would record one row per delivery and suppress nothing.
    -- WHY : Assumptions: an identity the PRODUCER supplied is accepted only
    --       BELOW the broker's, as an explicitly legacy fallback for a
    --       request that never passed the broker. Neither producer value is
    --       authenticated or constrained, so preferring one would let a
    --       producer that reuses a correlation identifier across several
    --       questions have its second, genuine inquiry answered with the
    --       first question's timestamp. The consumer records the ordering.
    -- WHY : Assumptions: 128 characters, matching the width the sibling
    --       ledger in account-service and the authorization outbox both use
    --       for their identity columns. Every value that can reach this
    --       column is bounded ABOVE by the consumer's intake rule -- the
    --       shared queue-identity bound in
    --       com.carddemo.common.messaging.MessagingCorrelationId -- or is a
    --       broker identifier of the same order, so the width is a ceiling
    --       nothing approaches rather than a limit a request can discover
    --       on an insert. The baseline's own descriptor fields are 24 bytes
    --       each, MQ-MSG-ID at CODATE01.cbl L52 and MQ-CORRELID at L53.
    request_key      VARCHAR(128) NOT NULL,

    -- WHY : Assumptions: two states and no third. PENDING means the reply
    --       is recorded and its send has not been observed to succeed; SENT
    --       means it has. A FAILED state was considered and left out
    --       deliberately: a send that fails propagates, the request is
    --       redelivered, and the redelivery finds PENDING and re-sends the
    --       recorded reply -- so failure is a transient property of an
    --       attempt rather than a state of the answer, and recording it
    --       would create a state nothing ever leaves.
    status           VARCHAR(8)   NOT NULL,

    -- WHY : Assumptions: the reply is stored VERBATIM, framed exactly as it
    --       went to the queue, so a re-send is a re-send of the same bytes
    --       rather than a recomposition. This is the load-bearing column of
    --       the whole table for this flow: recomposing would ask the clock
    --       again and answer a redelivery with a later timestamp, which is
    --       the divergence the header records.
    -- WHY : Assumptions: TEXT rather than a bounded CHAR of the message
    --       length. The reply is framed to the thousand characters
    --       CODATE01.cbl L50 declares for MQ-BUFFER, but the framing width
    --       is the messaging contract's to state and not this table's; a
    --       second declaration of it here would be a width free to
    --       disagree with the mapper that renders the reply.
    reply_payload    TEXT         NOT NULL,

    -- WHY : Assumptions: the resolved destination is recorded with the
    --       payload, because a re-send has to go where the first send went.
    --       Re-resolving it on the retry would let a configuration change
    --       between two deliveries send the second copy of one answer to a
    --       different queue from the first.
    reply_to_queue_url VARCHAR(1024) NOT NULL,

    -- WHY : Assumptions: the two echoed identities are recorded exactly as
    --       the request supplied them, because the re-sent reply must carry
    --       the same attributes as the original -- the baseline restores its
    --       saved message identifier at CODATE01.cbl L373 and its saved
    --       correlation identifier at L374 immediately before the put at
    --       L383. They are nullable because a request may supply either,
    --       both or neither, and the baseline accepts all four cases.
    -- WHY : Assumptions: an identity the consumer refused at intake is
    --       recorded as NULL rather than truncated to this width.
    --       Truncation would store a value that is neither the requester's
    --       nor absent, so a requester pairing on it would match the answer
    --       to nothing while believing it had matched; a null says plainly
    --       that the exchange carried no usable identity of that kind.
    correlation_id   VARCHAR(128),
    message_id       VARCHAR(128),

    -- WHY : Assumptions: the claim instant is recorded so that pruning is
    --       expressible. This ledger grows by one row per distinct request
    --       and nothing in the exchange removes a row -- deliberately, since
    --       the runtime role holds no DELETE on this schema -- so retention
    --       is an operator action under a privileged role, and the index
    --       below is what makes it cheap. A row is safe to remove once it
    --       is older than the request queue's message-retention period,
    --       because past that point the request it answers can no longer be
    --       redelivered and the row can no longer suppress anything.
    claimed_at       TIMESTAMP(6) NOT NULL,
    sent_at          TIMESTAMP(6),

    -- WHY : Assumptions: attempts counts SENDS of this answer and nothing
    --       else, so its value answers one question: how many times this
    --       reply reached the queue. A value above one is the operational
    --       signal that the crash window between the send and the mark was
    --       actually entered, which is the one residual divergence this
    --       design accepts and therefore the one an operator should be able
    --       to measure.
    attempts         INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_reference_inquiry_reply_ledger PRIMARY KEY (request_key),

    -- WHY : Assumptions: the state domain is enforced by the DATABASE and
    --       not only by the application, because the claim is a native
    --       statement rather than a mapped write -- there is no entity whose
    --       type could constrain it -- and an unrecognised state would make
    --       a redelivery neither suppressible nor re-sendable.
    CONSTRAINT ck_reference_inquiry_reply_ledger_status
        CHECK (status IN ('PENDING', 'SENT')),

    -- WHY : Assumptions: the send instant and the state are the same fact,
    --       so the constraint ties them rather than trusting the two writes
    --       to agree. A SENT row with no instant would be unprunable by age
    --       and a PENDING row with one would claim an answer had been sent
    --       while inviting a re-send.
    CONSTRAINT ck_reference_inquiry_reply_ledger_sent_instant
        CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- WHY : Assumptions: the index is on the claim instant and exists for the
--       pruning statement documented on that column, not for the exchange.
--       Every read the exchange performs is by primary key, so it needs no
--       index of its own; a prune without this one would scan the whole
--       ledger, which grows without bound until the first prune runs.
CREATE INDEX IF NOT EXISTS idx_reference_inquiry_reply_ledger_claimed_at
    ON reference.inquiry_reply_ledger (claimed_at);

-- WHY : Assumptions: the comment is ONE string literal rather than
--       concatenated parts. COMMENT ON takes a literal and not an
--       expression, so a concatenation -- which reads naturally and is what
--       a first draft of the sibling migration used -- is a syntax error
--       the migration fails on, and the failure surfaces as a service that
--       cannot start rather than as a missing comment.
COMMENT ON TABLE reference.inquiry_reply_ledger IS 'Operational, not reference data: one row per answered date-conversion request, keyed by the queue service''s own identifier for the delivery, so that a redelivered request is answered with the first answer rather than a later timestamp. Recorded and committed before the reply is sent; marked SENT afterwards.';
