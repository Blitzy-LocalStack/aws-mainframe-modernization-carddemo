package com.carddemo.authorization.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One authorization reply expressed as the message it will become, apart from the row that makes it
 * durable.
 *
 * <p>Refactoring Rationale: this shape has no counterpart in the reference system, because there a
 * reply is a message and never a record. The reference consumer decides, replies, writes and only
 * then commits. In {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} the decision is
 * computed at L459; the response paragraph is performed at L461 and its put carries
 * {@code MQPMO-NO-SYNCPOINT} at L753 to L754; the database write is performed afterwards at L464;
 * and the single {@code EXEC CICS SYNCPOINT} is reached at L335 later still, in the outer loop once
 * the paragraph performed at L330 returns. The request behind all of it was taken with
 * {@code MQGMO-NO-SYNCPOINT} at L389 to L391, so it is already consumed and cannot be presented a
 * second time to re-derive the answer. Put, write and commit are therefore three separate units of
 * work in that order, and an interruption between any two of them leaves the answer the requester
 * holds and the data the schema holds disagreeing in one direction or the other. The target writes
 * the reply as a row inside the same transaction as the decision and publishes it afterwards, so a
 * reply exists for every committed decision, and this type is that reply's transport-facing half.
 * The divergence is registered as D-5 in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No reference source is edited by this
 * migration: the baseline behaves as described above, the target adds a durable reply, and the
 * difference is recorded rather than made silently.</p>
 *
 * <p>Alternatives Considered: two ways of closing that window without a durable row were evaluated
 * and both were declined. Retrying the publication in process after the commit was declined because
 * it cannot outlive the process, and loss of the process is the window at issue rather than an
 * incidental one -- a retry only helps while the party retrying is still running. A single
 * transaction spanning the database and the queue was declined because the queue is not a resource
 * the database can enlist, so the two-resource commit would have to be reconstructed in application
 * code and would fail in the same places. Note what this reasoning is NOT: it is not the
 * elimination of the reference system's own distributed commit across its two resource managers.
 * That is a separate divergence, D-6, and it belongs to the schema that now holds the pending detail
 * and the fraud row together rather than to this type.</p>
 *
 * <p>Alternatives Considered: this type deliberately declares no persistence mapping and is not an
 * entity. {@link AuthReplyOutbox} already maps the one outbox table this context owns,
 * {@code auth_reply_outbox}, created at L948 of
 * {@code services/authorization-service/src/main/resources/db/migration/V1__authorization.sql} and
 * keyed at L1120 of the same file. Declaring a second mapped type over that one table was the
 * obvious alternative and was rejected outright: it would give the table two identity generators and
 * two independent notions of its own state, and a reader could no longer tell which type owned a
 * column. Holding the message apart from the row is also what lets the publication decision below be
 * exercised with no persistence context, no queue client and no transport library on the class path,
 * which is the property
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * exists to keep true of every type in this package.</p>
 *
 * <p>Alternatives Considered: no optimistic-lock version member is declared. The account and card
 * contexts carry one because the reference programs there compare a complete pre-edit image across
 * the conversational gap before rewriting; the authorization programs contain no such comparison,
 * and the migration declares no version column on the outbox table. An outbox row is claimed by a
 * state transition recorded on the row itself, not by comparing a prior image, so versioning was
 * considered and declined rather than overlooked.</p>
 *
 * <p>Alternatives Considered: no publication-status member is declared, and no nested status type is
 * declared to hold one. An earlier reading of the plan expected a status domain here; the migration
 * declares no {@code status} column at all. Publication state is carried by {@code published_at}
 * being absent or set (L1080), the attempt counter is named {@code attempts} (L1091) and the
 * diagnostic is {@code last_error} (L1118). A status member here would therefore describe a column
 * that does not exist, and all three of those items belong to the durable row rather than to the
 * message, which is why none of them appears among the components below.</p>
 *
 * <p>Alternatives Considered: the components are declared by a record and their accessors come from
 * the record itself, with no annotation processor emitting them. A processor that generated
 * accessors could not carry the docstring this project's Explainability rule requires on each one,
 * whereas a record documents every component once, in the {@code @param} tags of this block. A
 * generated mapper was declined on the same ground and on a second: the anti-corruption work in
 * {@code com.carddemo.authorization.mapper} masks, suppresses and renames, and each of those is a
 * decision a reader cannot recover from generated code.</p>
 *
 * <p>Assumptions: {@code payload} holds the already-encoded reply exactly as the wire carries it,
 * and is never assembled or parsed here. The reply is the six items of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} L19 to L24, whose declared widths are
 * 16, 15, 6, 2, 4 and a 14-character signed edited amount -- the amount picture at L24 being
 * {@code PIC +9(10).99}, an edited item rather than a packed or a zoned one, spending its fourteen
 * characters on one sign-control position, ten integer digit positions, an embedded decimal point
 * and two decimal places -- summing to 57 characters. That sign position is positional rather than
 * floating, so it holds a minus for a negative amount and a SPACE for a non-negative one, never a
 * plus; {@code com.carddemo.common.codec.CsvAuthCodec#formatReplyMoney} is where that rendering
 * lives. The wire form is 63 characters, not the 62 that interior-delimiter arithmetic predicts,
 * because the reference program's
 * {@code STRING} at {@code cbl/COPAUA0C.cbl} L722 to L730 emits a comma after the sixth item as well
 * as between the pairs: six separators, so 57 plus 6. That figure is
 * {@code com.carddemo.common.codec.CsvAuthCodec#REPLY_WIRE_LENGTH}, and the encoding and decoding
 * are performed there and imported rather than restated, because a second place to express a field
 * order is a second place to get it wrong. Since the reference descriptor declares the payload as a
 * string format at L751, the item order and the delimiter ARE the interface; a structured envelope
 * is offered to new consumers alongside it and never in place of it.</p>
 *
 * <p>Assumptions: the reply carries SIX items and the request carries EIGHTEEN, and the two counts
 * are not interchangeable. The request's own item names are not renameable either: L28 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} declares
 * {@code PA-RQ-MERCHANT-CATAGORY-CODE PIC X(04)} and the wire keeps that spelling, while only
 * internal and persisted names read {@code merchantCategoryCode} and {@code merchant_category_code}.
 * A payload's item names belong to whoever consumes the payload. The reference files themselves are
 * read as the specification and are never modified.</p>
 *
 * <p>Assumptions: {@code orderGroupToken} and {@code deduplicationToken} hold purpose-scoped keyed
 * tokens and not the values they stand for -- the first a token over the card number, the second a
 * token over the card and transaction pair -- which is why each component is named for the token
 * rather than for the item behind it. Grouping by card is what preserves the per-card ordering the
 * reference system gets from a single-threaded consumer, and deduplicating by the pair is what makes
 * suppression independent of the payload bytes. A single group for every message was the alternative
 * and would serialise every card behind one ordering chain. The tokens are produced by
 * {@code com.carddemo.common.codec.CsvAuthCodec.AuthReply#orderGroup} and
 * {@code #deduplicationKey}, and the two purposes differ so that a holder of one token cannot join
 * it to the other; the derivation is specified in
 * {@code docs/architecture/messaging-contracts.md}. They are carried as tokens because the
 * publisher passes them as the transport's group and deduplication attributes, and an attribute is
 * metadata that the queue's server-side encryption of a message body does not cover -- so the raw
 * form would put a primary account number into queue telemetry and into the trace of every send.</p>
 *
 * <p>Assumptions: {@code correlationId} is echoed from the request unaltered, mirroring
 * {@code cbl/COPAUA0C.cbl} L745, which moves the saved inbound identifier onto the reply descriptor
 * exactly as it arrived. The reference identifier is declared {@code PIC X(24)} at L45 and is
 * captured from the inbound descriptor at L411 to L414, so the identity comes from the request and
 * never from configuration and is never defaulted here. The column is wider than 24 at
 * {@code VARCHAR(64)} (L992) because what is stored may be a derived token rather than the raw
 * inbound bytes, and it is nullable because a requester need not send one.</p>
 *
 * <p>Assumptions: {@code replyQueueUrl} is data and not configuration. The reference system routes
 * each reply to the destination that request nominated, moving a per-request queue name onto the
 * reply object descriptor at {@code cbl/COPAUA0C.cbl} L741 to L742 rather than addressing one place,
 * so the destination travels with the message. It is a plain character value here and never a
 * transport client's own type, because no infrastructure type may appear in this package. The value
 * is expected to be one the service's configured allowlist already admitted, since the nomination
 * arrives on an attribute a requester controls; admitting it is the consumer's responsibility and
 * happens before the decision and the row are committed. No address is written into this source: the
 * shape is described in words on purpose.</p>
 *
 * <p>Assumptions: {@code expiresAt} is a contract and not housekeeping. The reference descriptor
 * expresses a reply deadline at {@code cbl/COPAUA0C.cbl} L750, {@code MOVE 50 TO MQMD-EXPIRY}, and
 * that field is denominated in TENTHS of a second, so fifty of them is five seconds -- a distinct
 * unit from the receive wait at L242, which is denominated in milliseconds and where 5000 is also
 * five seconds. The target transport has no per-message time to live at all, so the deadline moves
 * out of the transport and into this component: it travels as a message attribute, a publisher
 * declines to send a message whose instant has passed and a consumer drops and logs a stale reply,
 * backed by short retention on the reply queue. Leaving it to retention alone was declined because
 * retention is queue-wide and cannot express a per-message deadline. The gap and this resolution are
 * recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <p>Assumptions: the instant is carried at microsecond resolution, matching the 26-character
 * timestamp form this context uses throughout. That resolution is settled twice over in the
 * reference tree: {@code app/app-authorization-ims-db2-mq/dcl/AUTHFRDS.dcl} L57 declares
 * {@code AUTH-TS PIC X(26)}, and {@code cbl/COPAUS2C.cbl} L171 to L172 formats a timestamp as
 * {@code 'YY-MM-DD HH24.MI.SSNNNNNN'} with six fractional digits. The outbox columns that store such
 * instants are declared {@code TIMESTAMP(6)} for the same reason, and a coarser resolution would
 * make two deadlines that differ only in the sixth fractional digit indistinguishable.</p>
 *
 * <p>Assumptions: no monetary member is declared. The reply's approved amount is already present,
 * inside {@code payload}, as the 14-character edited item of {@code cpy/CCPAURLY.cpy} L24, so
 * modelling it again here would create a second representation of one value and a second chance for
 * the two to disagree. Where this context does carry an amount as a number it carries it as an exact
 * decimal at scale two through {@code com.carddemo.common.money.Money}, rendered as a string on the
 * wire by {@code com.carddemo.common.money.MoneyModule}, and never as a binary approximation.</p>
 *
 * <p>Assumptions: two properties of the reference consumer are deliberately NOT represented here,
 * and the reason is worth stating so that neither is added later by mistake. Its per-invocation
 * message cap is declared {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
 * {@code cbl/COPAUA0C.cbl} L40 and guarded at L339 with a strictly-greater-than comparison, so the
 * loop ends once the processed count exceeds 500; its receive wait is set at L242. Both become
 * settings on the bounded polling loop that drains this outbox, which is why the drain happens in
 * bounded batches at all. A batch size and a wait interval are consumer configuration and are not
 * properties of a message, so neither is a component below.</p>
 *
 * <p>Assumptions: publication is retried by the transport tier rather than by anything declared
 * here. Redelivery with a dead-letter queue after five receives, together with per-state retry in
 * the batch orchestrator, is the durable tier, as recorded in
 * {@code docs/adr/ADR-004-messaging.md}; the attempt counter that pairs with it is the
 * {@code attempts} column at L1091 of
 * {@code services/authorization-service/src/main/resources/db/migration/V1__authorization.sql},
 * which is on the row and not on the message. No retry library is adopted anywhere in this
 * reactor.</p>
 *
 * <p>Trade-offs: the accepted cost of this design is one extra row written per authorization, one
 * extra hop to publish it, and at-least-once publication -- a publisher that succeeds in sending and
 * then fails before recording the send will send that reply twice. The duplicate is tolerable
 * precisely because the reply queue suppresses it on the deduplication token above, whereas the
 * opposite ordering, recording the send before making it, converts a duplicate into a missing reply,
 * which is the outcome the outbox was added to remove. The migration states the same ordering
 * argument on the {@code attempts} column it introduces for it, at L1082 to L1090 of
 * {@code services/authorization-service/src/main/resources/db/migration/V1__authorization.sql}, so
 * the schema and this type agree rather than each deciding. What is bought for that cost is that a
 * reply and the decision it reports are exactly as durable as each other.</p>
 *
 * @param replyQueueUrl where the reply is to be sent, taken from the request rather than from
 *     configuration; must not be {@code null} or blank
 * @param correlationId the identity a requester matches an answer against, echoed unaltered; may be
 *     {@code null} when the request carried none
 * @param orderGroupToken the purpose-scoped keyed token over the card number that carries per-card
 *     ordering; must not be {@code null} or blank
 * @param deduplicationToken the purpose-scoped keyed token over the card and transaction pair that
 *     carries duplicate suppression; must not be {@code null} or blank
 * @param payload the already-encoded reply body, held exactly as the wire carries it; must not be
 *     {@code null} or blank
 * @param contentType the wire format the payload is expressed in; must not be {@code null} or blank
 * @param expiresAt the instant after which the reply stops being worth sending, in coordinated
 *     universal time; may be {@code null} to mean it never goes stale
 */
public record OutboxMessage(
        String replyQueueUrl,
        String correlationId,
        String orderGroupToken,
        String deduplicationToken,
        String payload,
        String contentType,
        LocalDateTime expiresAt) {

    /**
     * The widest reply destination this message can be made durable as.
     *
     * <p>Assumptions: every width constant in this block mirrors the declared width of the column
     * that stores the component, so a value this type accepts is one the durable row can hold. The
     * alternative was to validate nowhere and let the database reject an oversized value on flush;
     * that was declined because the flush happens inside the decision transaction, so a width fault
     * would abort the decision rather than the message that caused it. This one mirrors
     * {@code reply_to_queue_url VARCHAR(1024)} at L985 of the migration.</p>
     */
    public static final int REPLY_QUEUE_URL_MAX_LENGTH = 1024;

    /**
     * The widest correlation identity this message can be made durable as.
     *
     * <p>Assumptions: mirrors {@code correlation_id VARCHAR(64)} at L992 of the migration, which is
     * wider than the 24 characters the reference identifier declares at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L45 because what reaches the column
     * may be a derived token rather than the inbound bytes.</p>
     */
    public static final int CORRELATION_ID_MAX_LENGTH = 64;

    /**
     * The widest ordering or deduplication token this message can be made durable as.
     *
     * <p>Assumptions: mirrors {@code order_group_token} and {@code deduplication_token}, both
     * {@code VARCHAR(128)} at L1031 and L1032 of the migration. Narrowing either to the exact width
     * of the present derivation was rejected there, because a later purpose-scoped derivation of a
     * different length would then need a schema change to store what is in every other respect the
     * same thing; the same reasoning keeps one constant for both rather than two that could
     * drift.</p>
     */
    public static final int TOKEN_MAX_LENGTH = 128;

    /**
     * The widest wire-format label this message can be made durable as.
     *
     * <p>Assumptions: mirrors {@code content_type VARCHAR(64)} at L1060 of the migration, whose
     * default is the delimited-text format the reference descriptor declares at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L751.</p>
     */
    public static final int CONTENT_TYPE_MAX_LENGTH = 64;

    /**
     * Creates one reply publication, checking the components the durable row requires.
     *
     * <p>Assumptions: values are held EXACTLY as they arrive and nothing is trimmed. The reference
     * items are declared-width character fields, so a trailing blank is part of a value rather than
     * an artefact of it: the identity declared {@code PIC X(24)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L45 must survive intact when it ends
     * in blanks, and the payload must survive byte for byte because its delimiters and the
     * 14-character edited amount of {@code cpy/CCPAURLY.cpy} L24 are the interface. Blankness is
     * therefore tested and never silently amended.</p>
     *
     * <p>Alternatives Considered: {@code correlationId} is width-checked but NOT blank-checked,
     * unlike the four required components. The reference descriptor's identifier may legitimately
     * arrive as all blanks or as an absence, and rejecting an all-blank identity would refuse a
     * reply that the reference system would have sent. The four components that are blank-checked
     * have no meaning when empty: a message with no destination, no ordering token, no
     * deduplication token or no body cannot be published at all.</p>
     *
     * <p>Assumptions: {@code expiresAt} is not validated. Any instant is admissible, including one
     * already in the past, because the decision to withhold a stale reply belongs to the publisher
     * and refusing to construct the message would lose the record of a reply that was owed.</p>
     *
     * @param replyQueueUrl where the reply is to be sent; must not be {@code null} or blank and must
     *     fit {@link #REPLY_QUEUE_URL_MAX_LENGTH}
     * @param correlationId the identity to echo; may be {@code null}, and must fit
     *     {@link #CORRELATION_ID_MAX_LENGTH} when present
     * @param orderGroupToken the keyed ordering token; must not be {@code null} or blank and must
     *     fit {@link #TOKEN_MAX_LENGTH}
     * @param deduplicationToken the keyed deduplication token; must not be {@code null} or blank and
     *     must fit {@link #TOKEN_MAX_LENGTH}
     * @param payload the encoded reply body; must not be {@code null} or blank, and is not
     *     width-checked because the column that stores it is unbounded text
     * @param contentType the wire format of the payload; must not be {@code null} or blank and must
     *     fit {@link #CONTENT_TYPE_MAX_LENGTH}
     * @param expiresAt the staleness deadline in coordinated universal time; may be {@code null}
     * @throws NullPointerException when a component that must be present is {@code null}
     * @throws IllegalArgumentException when a component that must be present is blank, or when any
     *     character component is wider than the column that stores it
     */
    public OutboxMessage {
        replyQueueUrl = requiredWithin(replyQueueUrl, "replyQueueUrl", REPLY_QUEUE_URL_MAX_LENGTH);
        correlationId = boundedOrNull(correlationId, "correlationId", CORRELATION_ID_MAX_LENGTH);
        orderGroupToken = requiredWithin(orderGroupToken, "orderGroupToken", TOKEN_MAX_LENGTH);
        deduplicationToken =
                requiredWithin(deduplicationToken, "deduplicationToken", TOKEN_MAX_LENGTH);
        payload = required(payload, "payload");
        contentType = requiredWithin(contentType, "contentType", CONTENT_TYPE_MAX_LENGTH);
    }

    /**
     * Creates one reply publication in the delimited-text format the reference descriptor declares.
     *
     * <p>Alternatives Considered: this factory exists so that a caller never restates the format
     * label. The alternative was to let every caller pass the literal through the canonical
     * constructor, which was declined because the label then has as many sources as it has callers
     * and one of them can drift; here it is taken from {@link AuthReplyOutbox#CONTENT_TYPE_CSV}, the
     * same constant the durable row defaults to, so the message and the row cannot disagree about
     * what the bytes are.</p>
     *
     * <p>Refactoring Rationale: this factory REQUIRES a deadline where the canonical constructor admits
     * an absent one, and it required nothing. The two rules are deliberately different. A reply produced
     * by this flow always has one -- the reference descriptor sets a five-second expiry on every reply it
     * puts, at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L750 -- and the target transport
     * has no per-message time to live, so the instant travelling on the message IS the whole of that
     * control. A publication built here without it would be published unconditionally however long it
     * had waited, which is the outcome the deadline exists to prevent, and nothing downstream could tell
     * the difference between a reply that never expires and one whose deadline was dropped by mistake.
     * The canonical constructor stays permissive because it also represents a row read BACK from the
     * schema, where {@code expires_at} is nullable at L1071 of the migration so that a row written by an
     * extract or by an earlier revision still projects rather than failing to load.</p>
     *
     * @param replyQueueUrl where the reply is to be sent; must not be {@code null} or blank
     * @param correlationId the identity to echo; may be {@code null}
     * @param orderGroupToken the keyed ordering token; must not be {@code null} or blank
     * @param deduplicationToken the keyed deduplication token; must not be {@code null} or blank
     * @param payload the encoded reply body; must not be {@code null} or blank
     * @param expiresAt the staleness deadline in coordinated universal time; must not be {@code null}
     * @return a publication carrying the delimited-text format label, never {@code null}
     * @throws NullPointerException when a component that must be present is {@code null}, the deadline
     *     included
     * @throws IllegalArgumentException when a component that must be present is blank, or when any
     *     character component is wider than the column that stores it
     */
    public static OutboxMessage csvReply(String replyQueueUrl, String correlationId,
            String orderGroupToken, String deduplicationToken, String payload,
            LocalDateTime expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt must not be null for an authorization reply");
        return new OutboxMessage(replyQueueUrl, correlationId, orderGroupToken, deduplicationToken,
                payload, AuthReplyOutbox.CONTENT_TYPE_CSV, expiresAt);
    }

    /**
     * Projects a durable outbox row into the publication it describes.
     *
     * <p>Refactoring Rationale: this is the drain half of the ordering recorded on this type. The row
     * is committed with the decision and this projection is what a publisher works from afterwards,
     * so the transport-specific assembly reads one shape instead of reaching separately for each
     * column of an entity. Only the components a transport needs are carried across: the row's key,
     * its creation instant, its publication instant, its attempt counter and its diagnostic stay on
     * the row, because each of those describes the row's own progress rather than the message.</p>
     *
     * <p>Assumptions: the row is expected to be one already read from the schema, so its required
     * columns are populated; the canonical constructor re-checks them regardless, because a
     * projection that silently produced an unpublishable message would move the fault to the send.
     * The row's persistence mapping stays entirely on the row -- nothing here touches a persistence
     * context, which is what allows a publication to be reasoned about in a plain unit test.</p>
     *
     * @param row the committed outbox row to project; must not be {@code null}
     * @return the publication that row describes, never {@code null}
     * @throws NullPointerException when {@code row} is {@code null}, or when a column the message
     *     requires is unpopulated on it
     * @throws IllegalArgumentException when a column on the row is blank or wider than this type
     *     admits
     */
    public static OutboxMessage from(AuthReplyOutbox row) {
        Objects.requireNonNull(row, "row must not be null");
        return new OutboxMessage(row.getReplyQueueUrl(), row.getCorrelationId(),
                row.getOrderGroupToken(), row.getDeduplicationToken(), row.getPayload(),
                row.getContentType(), row.getExpiresAt());
    }

    /**
     * Builds the pending durable row that makes this publication as durable as the decision.
     *
     * <p>Refactoring Rationale: this is the decision half of the same ordering. The row it returns is
     * meant to be persisted inside the transaction that records the authorization, which is what
     * gives the reply and the decision one fate instead of two; a caller that persists it afterwards
     * has reproduced the reference ordering the divergence exists to move away from. The returned row
     * carries no publication instant and a zero attempt counter, because a row that has just been
     * created has not been drained -- the absence of a publication instant IS the pending state, the
     * migration declaring no separate status column for it.</p>
     *
     * <p>Assumptions: the creation instant is supplied by the caller rather than read from a clock
     * here, so that a decision and the reply it produces can be stamped from one reading and a test
     * can state the instant it means. The column defaults to the statement timestamp if nothing is
     * supplied, which is exactly the ambiguity worth avoiding for two authorizations on one card.</p>
     *
     * <p>Alternatives Considered: this refuses a publication whose format label is not the one the
     * durable row can carry, rather than quietly substituting the row's default. The row assigns the
     * delimited-text label unconditionally, so substituting would leave a row asserting a format its
     * bytes are not in, and a consumer reading that label would parse the payload the wrong way. A
     * refusal at the point of construction names the fault while the caller can still act on it.</p>
     *
     * @param createdAt when the row is being written, in coordinated universal time; must not be
     *     {@code null}
     * @return a pending outbox row carrying this publication, never {@code null}
     * @throws NullPointerException when {@code createdAt} is {@code null}
     * @throws IllegalStateException when this publication's format label is not the one the durable
     *     row carries
     */
    public AuthReplyOutbox toPendingRow(LocalDateTime createdAt) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!AuthReplyOutbox.CONTENT_TYPE_CSV.equals(this.contentType)) {
            // WHY : Assumptions: the message names the shape of the fault and never the value that
            //       caused it. A publication in scope here carries a full card number inside its
            //       payload, and a diagnostic that quoted the offending content would copy that
            //       number into a log that outlives the reply. The label is the one component that
            //       is safe to name, so it is named and nothing else is.
            throw new IllegalStateException("contentType must be "
                    + AuthReplyOutbox.CONTENT_TYPE_CSV + " to be held on an outbox row, was "
                    + this.contentType);
        }
        return new AuthReplyOutbox(this.replyQueueUrl, this.correlationId, this.orderGroupToken,
                this.deduplicationToken, this.payload, this.expiresAt, createdAt);
    }

    /**
     * Reports whether this reply is still worth sending as of a stated instant.
     *
     * <p>Assumptions: this is the check that stands in for the transport capability the reference
     * system had and the target does not. The reference descriptor sets a five-second deadline at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L750, where the field is denominated
     * in tenths of a second so that fifty of them is five seconds, and the target queue offers no
     * per-message time to live to express it. The deadline therefore travels as a message attribute
     * and is enforced here, by a publisher before the send and by a consumer on receipt, as recorded
     * in {@code docs/adr/ADR-004-messaging.md}.</p>
     *
     * <p>Alternatives Considered: an absent deadline means the reply never goes stale, rather than
     * meaning it is already stale. Treating absence as expiry was rejected because it would silently
     * discard every reply to a requester that nominates no deadline, and the reference system sends
     * those replies. The comparison excludes the deadline instant itself, so a reply is publishable
     * strictly before it and not at it, which makes this the exact complement of
     * {@link AuthReplyOutbox#isExpiredAsOf(LocalDateTime)} on the row it came from; the two are
     * written as complements so a row and its message can never disagree about staleness.</p>
     *
     * @param asOf the instant to judge staleness against, in coordinated universal time; must not be
     *     {@code null}
     * @return {@code true} when no deadline is carried, or when {@code asOf} is strictly before the
     *     deadline carried
     * @throws NullPointerException when {@code asOf} is {@code null}
     */
    public boolean isPublishableAsOf(LocalDateTime asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        return this.expiresAt == null || asOf.isBefore(this.expiresAt);
    }

    /**
     * Reports whether a correlation identity is carried and can be published as an attribute.
     *
     * <p>Assumptions: the identity is optional on the wire, so a publisher must decide whether to
     * attach the attribute at all rather than attach an absent value. Asking that question here
     * keeps the decision in one place; a publisher testing the accessor against absence itself would
     * spread the same condition across every send path. The reference system always has one to echo,
     * moving the saved inbound identifier onto the reply descriptor at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L745 after capturing it at L411 to
     * L414, so an absent identity means the requester sent none and not that one was mislaid.</p>
     *
     * @return {@code true} when a correlation identity is present on this publication
     */
    public boolean hasCorrelationId() {
        return this.correlationId != null;
    }

    /**
     * Renders the shape of this publication without rendering anything that identifies a cardholder.
     *
     * <p>Assumptions: this override is load-bearing and its absence would be a data-exposure fault
     * rather than a cosmetic one. A record generates a rendering that emits every component, and
     * three of the components here must never reach a log: {@code payload} contains the full
     * sixteen-character card number of {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}
     * L19, and the two tokens stand for that number and for the card-and-transaction pair. Masking
     * belongs to {@code com.carddemo.authorization.mapper}, the single layer permitted to hold that
     * concern, so this rendering routes around the question entirely by naming none of the three.
     * The destination is withheld on the same reasoning: it is an operational address, and a
     * rendering is exactly the path by which one leaks into a log aggregator.</p>
     *
     * <p>Alternatives Considered: declaring no rendering at all, which is what the durable row does.
     * That was rejected here because a record cannot decline to have one -- omitting the override
     * leaves the generated rendering in place, which is the very thing to be avoided -- so the choice
     * is between this and the generated form rather than between this and silence. What is emitted is
     * the SHAPE of the publication: the format label, the payload's length rather than its content,
     * whether an identity is carried, and the deadline. That is the same discipline the migration
     * states for its own diagnostic column, and a value assembled from geometry alone carries
     * nothing that needs redacting.</p>
     *
     * @return a rendering carrying the format label, the payload length, whether a correlation
     *     identity is present and the deadline, never {@code null}
     */
    @Override
    public String toString() {
        return "OutboxMessage[contentType=" + this.contentType
                + ", payloadLength=" + this.payload.length()
                + ", correlationIdPresent=" + hasCorrelationId()
                + ", expiresAt=" + this.expiresAt + ']';
    }

    /**
     * Checks that a component the durable row requires is present and not empty.
     *
     * <p>Assumptions: emptiness is rejected but never silently amended, because a declared-width
     * item carries its padding as content and trimming would alter the value the wire agreed on.
     * Blankness rather than length is the test, so a value consisting only of separators or spaces
     * is refused as firmly as an empty one.</p>
     *
     * @param value the component to check; must not be {@code null} or blank
     * @param componentName the component's name, used to name the fault
     * @return the value exactly as supplied, never {@code null}
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} is blank
     */
    private static String required(String value, String componentName) {
        Objects.requireNonNull(value, componentName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(componentName + " must not be blank");
        }
        return value;
    }

    /**
     * Checks that a required component is present, not empty and within its column's width.
     *
     * @param value the component to check; must not be {@code null} or blank
     * @param componentName the component's name, used to name the fault
     * @param maxLength the declared width of the column that stores the component
     * @return the value exactly as supplied, never {@code null}
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} is blank or wider than {@code maxLength}
     */
    private static String requiredWithin(String value, String componentName, int maxLength) {
        return bounded(required(value, componentName), componentName, maxLength);
    }

    /**
     * Checks that an optional component is within its column's width when it is present.
     *
     * <p>Alternatives Considered: absence passes through untouched and is not normalised to an empty
     * value. Substituting an empty value would make an identity the requester never sent
     * indistinguishable from one it sent as blanks, and the two mean different things to a requester
     * matching an answer to its question.</p>
     *
     * @param value the component to check; may be {@code null}
     * @param componentName the component's name, used to name the fault
     * @param maxLength the declared width of the column that stores the component
     * @return the value exactly as supplied, or {@code null} when none was supplied
     * @throws IllegalArgumentException when {@code value} is present and wider than
     *     {@code maxLength}
     */
    private static String boundedOrNull(String value, String componentName, int maxLength) {
        return value == null ? null : bounded(value, componentName, maxLength);
    }

    /**
     * Checks that a present component fits the column that stores it.
     *
     * <p>Assumptions: the fault names the component, the width it exceeded and the width it has, and
     * never the content that overflowed. A publication in scope here carries a card number, so a
     * diagnostic quoting the offending value would put that number into a log; geometry alone tells a
     * reader everything needed to locate the fault and carries nothing that needs redacting.</p>
     *
     * @param value the component to check; must not be {@code null}
     * @param componentName the component's name, used to name the fault
     * @param maxLength the declared width of the column that stores the component
     * @return the value exactly as supplied, never {@code null}
     * @throws IllegalArgumentException when {@code value} is wider than {@code maxLength}
     */
    private static String bounded(String value, String componentName, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(componentName + " must be at most " + maxLength
                    + " characters, was " + value.length());
        }
        return value;
    }
}
