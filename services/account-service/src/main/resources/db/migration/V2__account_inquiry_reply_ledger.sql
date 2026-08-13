-- =====================================================================
-- services/account-service/src/main/resources/db/migration/
--   V2__account_inquiry_reply_ledger.sql
--
-- Purpose: give the asynchronous account-inquiry exchange a durable record
--          of the reply it has already sent for a request, so that a
--          redelivery of that request is answered ONCE rather than answered
--          again.
--
-- WHY : Refactoring Rationale: InquiryMessageListener read the account,
--       composed the reply, sent it and returned -- and the queue
--       acknowledges the request only on that clean return. Everything
--       between the send and the acknowledgement is therefore a window in
--       which the reply exists and the request does not yet know it: a task
--       killed there, a container cycled there, or an acknowledgement lost
--       there leaves the request visible again, and the next delivery
--       composes and sends a SECOND reply carrying the same correlation
--       identifier as the first. A requester pairing an answer to a question
--       on that identifier then has two answers for one question, and
--       nothing on the wire distinguishes the duplicate from the original.
--       This table is the durable half of the fix: the reply is recorded
--       under the request's own identity, committed, and only then sent, so
--       a redelivery can see that the answer was already produced.
-- WHY : Alternatives Considered: relying on the queue's own
--       deduplication. Rejected because deduplication acts on the REQUEST
--       queue and suppresses a duplicate PUBLISH within a bounded window;
--       it says nothing about a redelivery, which is the same message being
--       given to a consumer again on purpose after a visibility timeout or
--       a failed acknowledgement. The two mechanisms address different
--       events, and only this one addresses the one that produced the
--       duplicate reply.
-- WHY : Alternatives Considered: making the consumer's whole exchange one
--       transaction spanning the database and the queue. Rejected because
--       that is a distributed transaction across two resource managers, and
--       AAP section 0.7.6 records the migration's elimination of two-phase
--       commit as a property to keep rather than a gap to refill. What is
--       reachable without it -- record, commit, send, mark -- narrows
--       duplication to a single crash window instead of leaving it open on
--       every redelivery, and the residual window is documented on the
--       consumer rather than hidden.
-- WHY : Alternatives Considered: an outbox drained by a poller, which is the
--       shape the authorization context uses for its reply
--       (auth_reply_outbox in V1__authorization.sql). Rejected here for a
--       different requirement, not a different opinion: that context must
--       guarantee a reply EXISTS for every committed decision, because it
--       writes business rows the reply describes, so publication has to
--       survive the process. This exchange writes nothing -- the account
--       read is read-only -- so there is no committed state a missing reply
--       would contradict. What it needs is the opposite guarantee, that a
--       reply is not sent TWICE, and a claim-then-send ledger provides that
--       without a second background component to operate.
-- WHY : Assumptions: this migration is additive. It creates one table and
--       one index, alters nothing that exists, and the runtime role reaches
--       it through the default privileges V0__schemas_and_roles.sql sets for
--       carddemo_account_owner in this schema -- SELECT, INSERT and UPDATE,
--       which is exactly the three verbs this ledger uses.
-- =====================================================================

CREATE TABLE IF NOT EXISTS account.inquiry_reply_ledger (

    -- WHY : Assumptions: the key is the QUEUE SERVICE's own identifier for
    --       the delivery, and never a value this service invents. It is
    --       stable across every redelivery of one message -- only the
    --       receipt handle changes -- which is the only reason this row can
    --       be found again, and it is unique per accepted send, which is
    --       what stops two distinct requests colliding onto one row. A
    --       locally minted key would differ on every delivery and the table
    --       would record one row per delivery while suppressing nothing.
    -- WHY : Refactoring Rationale: the key was the identity the PRODUCER
    --       supplied -- its message attribute, or its correlation
    --       identifier when it supplied no message attribute. Neither is
    --       authenticated or constrained, so a producer reusing one
    --       correlation identifier across several questions had its second,
    --       genuine inquiry suppressed as a redelivery of the first. The
    --       producer-supplied values are retained as legacy fallbacks in
    --       the consumer, below the broker identifier, for a request that
    --       never passed the broker.
    -- WHY : Assumptions: 128 characters, which is the width the sibling
    --       outbox uses for its deduplication identity in
    --       V1__authorization.sql. Every value that can reach this column
    --       is bounded ABOVE by the consumer's intake rule -- the shared
    --       queue-identity bound of 64 characters in
    --       com.carddemo.common.messaging.MessagingCorrelationId -- or is a
    --       broker identifier of the same order, so the width is a ceiling
    --       nothing approaches rather than a limit a request can discover
    --       on an insert. The baseline's own descriptor fields are 24 bytes
    --       each, MQMD-MSGID and MQMD-CORRELID.
    request_key      VARCHAR(128) NOT NULL,

    -- WHY : Assumptions: two states and no third. PENDING means the reply is
    --       recorded and its send has not been observed to succeed; SENT
    --       means it has. A FAILED state was considered and left out
    --       deliberately: a send that fails propagates, the request is
    --       redelivered, and the redelivery finds PENDING and re-sends the
    --       recorded reply -- so failure is a transient property of an
    --       attempt rather than a state of the answer, and recording it
    --       would create a state nothing ever leaves.
    status           VARCHAR(8)   NOT NULL,

    -- WHY : Assumptions: the reply is stored VERBATIM, framed exactly as it
    --       went to the queue, so a re-send is a re-send of the same bytes
    --       rather than a recomposition. Recomposing would read the account
    --       again and could answer a redelivery with a balance that moved
    --       since the first answer, which would make two replies bearing one
    --       correlation identifier disagree -- worse than the duplication
    --       this table exists to remove, because a requester cannot tell
    --       which is authoritative.
    -- WHY : Assumptions: TEXT rather than a bounded CHAR of the message
    --       length. The reply is framed to 1000 characters today, matching
    --       the baseline's literal buffer length, but the framing width is
    --       the messaging contract's to state and not this table's; a second
    --       declaration of it here would be a width that could disagree with
    --       the codec.
    reply_payload    TEXT         NOT NULL,

    -- WHY : Assumptions: the resolved destination is recorded with the
    --       payload, because a re-send has to go where the first send went.
    --       Re-resolving it on the retry would let a configuration change
    --       between the two deliveries send the second copy of one answer to
    --       a different queue from the first.
    reply_to_queue_url VARCHAR(1024) NOT NULL,

    -- WHY : Assumptions: the two echoed identities are recorded exactly as
    --       the request supplied them, because the re-sent reply must carry
    --       the same attributes as the original. They are nullable because a
    --       request may supply either, both or neither.
    -- WHY : Assumptions: an identity the consumer refused at intake is
    --       recorded as NULL rather than truncated to this width.
    --       Truncation would store a value that is neither the requester's
    --       nor absent, so a requester pairing on it would match the answer
    --       to nothing while believing it had matched; a null says plainly
    --       that the exchange carried no usable identity of that kind. The
    --       refusal itself is reported to the error sink as a protocol
    --       diagnostic naming the attribute and its length.
    correlation_id   VARCHAR(128),
    message_id       VARCHAR(128),

    -- WHY : Assumptions: the claim instant is recorded so that pruning is
    --       expressible. This ledger grows by one row per distinct request
    --       and nothing in the exchange removes a row -- deliberately, since
    --       the runtime role holds no DELETE on this schema -- so retention
    --       is an operator action under a privileged role, and the index
    --       below is what makes it cheap. A row is safe to remove once it is
    --       older than the request queue's message-retention period, because
    --       past that point the request it answers can no longer be
    --       redelivered and the row can no longer suppress anything.
    claimed_at       TIMESTAMP(6) NOT NULL,
    sent_at          TIMESTAMP(6),

    -- WHY : Assumptions: attempts counts SENDS of this answer and nothing
    --       else, so its value answers one question: how many times this
    --       reply reached the queue. The sibling outbox's V2 migration
    --       records what happens when one counter carries two jobs, and this
    --       column is declared narrowly to avoid repeating it.
    attempts         INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_inquiry_reply_ledger PRIMARY KEY (request_key),

    -- WHY : Assumptions: the state domain is enforced by the DATABASE and
    --       not only by the application, because the claim is a native
    --       statement rather than a mapped write -- there is no entity whose
    --       type could constrain it -- and an unrecognised state would make
    --       a redelivery neither suppressible nor re-sendable.
    CONSTRAINT ck_inquiry_reply_ledger_status
        CHECK (status IN ('PENDING', 'SENT')),

    -- WHY : Assumptions: the send instant and the state are the same fact,
    --       so the constraint ties them rather than trusting the two writes
    --       to agree. A SENT row with no instant would be unprunable by age
    --       and a PENDING row with one would claim an answer had been sent
    --       while inviting a re-send.
    CONSTRAINT ck_inquiry_reply_ledger_sent_instant
        CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- WHY : Assumptions: the index is on the claim instant and exists for the
--       pruning statement documented on that column, not for the exchange.
--       Every read the exchange performs is by primary key, so it needs no
--       index of its own; a prune without this one would scan the whole
--       ledger, which grows without bound until the first prune runs.
CREATE INDEX IF NOT EXISTS idx_inquiry_reply_ledger_claimed_at
    ON account.inquiry_reply_ledger (claimed_at);

-- WHY : Assumptions: the comment is ONE string literal rather than concatenated
--       parts. COMMENT ON takes a literal and not an expression, so a
--       concatenation -- which reads naturally and is what a first draft of this
--       file used -- is a syntax error the migration fails on, and the failure
--       surfaces as a service that cannot start rather than as a missing comment.
COMMENT ON TABLE account.inquiry_reply_ledger IS 'One row per answered account-inquiry request, keyed by the queue service''s own identifier for the delivery, so that a redelivered request is answered once. Recorded and committed before the reply is sent; marked SENT afterwards.';
