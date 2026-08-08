package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.AuthorizationReplyPayload;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.common.web.CorrelationIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Carries the eighteen-field authorization request and the six-field reply between the wire records,
 * this context's queue payloads, its persistent detail row, its reply outbox and its diagnostic log.
 *
 * <p><b>Purpose.</b> This is the anti-corruption boundary of the pending-authorization context. Five
 * representations of one exchange exist deliberately, each for a different consumer, and this class is
 * the only place they meet. {@link AuthRequest} and {@link AuthReply} in the shared kernel are the
 * delimited wire records. {@link AuthorizationRequestPayload} and {@link AuthorizationReplyPayload}
 * are the structured payloads a consumer reading an envelope works from. {@link PendingAuthDetail} is
 * the durable decision. {@link OutboxMessage} is the reply made as durable as that decision. And
 * {@link ErrorLogEntry} is the diagnostic record, which goes to the log stream and nowhere else.</p>
 *
 * <p>Assumptions: field ORDER is the interface on both wire records, not merely their field names. The
 * reference program declares the delimited-string format in both directions --
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L397 inbound and L751 outbound -- and
 * parses inbound POSITIONALLY, with a single {@code UNSTRING ... DELIMITED BY ','} at L354 to L355
 * feeding eighteen receiving items and closing at L374, over the buffer its L103 declares as
 * {@code 01 W01-GET-BUFFER PIC X(500).} A string-format payload carries no schema, so reordering one
 * field is a breaking change even when every name is still correct. The eighteen request fields are
 * declared at {@code cpy/CCPAURQY.cpy} L19 to L36 and their widths sum to 153, which is 170 on the
 * wire once the seventeen separators are counted; the six reply fields are declared at
 * {@code cpy/CCPAURLY.cpy} L19 to L24 and sum to 57, which is 63 on the wire.</p>
 *
 * <p>Assumptions: the delimiter, the field positions and the parse itself belong to
 * {@link CsvAuthCodec} and are never restated here. This class performs the SEMANTIC crossing --
 * record to payload, record to entity, reply to outbox row, failure to log entry -- and calls the
 * codec for every byte-level concern. That division is what keeps one answer to the question of what
 * the wire looks like.</p>
 *
 * <p>Alternatives Considered: a generated mapper, specifically MapStruct, was evaluated and rejected.
 * Its most recent published release is a beta, and more decisively this mapping is not mechanical: it
 * renames a field internally while deliberately keeping the baseline misspelling on the wire, it
 * honours an emitted edit mask that contradicts the declared picture, it emits a payload length that
 * differs from the length the reference program transmits, it projects a reply into a transactional
 * outbox that has no antecedent in the reference source at all, and it routes a diagnostic record to
 * the log stream rather than to the queue a casual reading suggests. Each of those needs a written
 * justification at the mapping site, and a generated mapper can hold none of them. The accepted cost
 * is that every component assignment is written out by hand, which is also what makes each one
 * reviewable.</p>
 *
 * <p>Alternatives Considered: Lombok was evaluated for the accessors and rejected. Generated
 * accessors cannot carry the documentation the user-specified Rule 1 (Explainability) validation gate
 * requires of every member, so adopting it would trade a few lines of source for members that fail
 * review. Java 21 {@code record} types with explicit compact constructors give the same brevity while
 * leaving every component documentable, which is what the two nested records below use.</p>
 *
 * <p>Assumptions: the primary account number is treated ASYMMETRICALLY, and both halves are stated
 * here because a reader who applies either rule uniformly is wrong. The request and reply payloads
 * carry the number in FULL, because the wire is the contract and the counterparty needs it to act;
 * masking it there would break a counterparty this migration does not control. Every other outlet --
 * an interface response, a log line, a queue attribute -- carries at most the last four digits. Three
 * independent grounds make the narrowing worth stating: the reference resource definitions enable
 * dumping and tracing at {@code csd/CRDDEMO2.csd} L44, L54 and L64 while declining to classify the
 * payload as confidential at L45, L55 and L65; the reference detail screen displayed all sixteen
 * digits, declared {@code 02 CARDNUMI  PIC X(16).} at {@code cpy-bms/COPAU01.cpy} L60; and no
 * resource-level or command-level check stood in front of either, at L46, L56 and L66. The migration
 * ADDS the narrowing on the paths that never needed the number; it removes nothing. The card
 * verification value is not carried by either wire record and is never returned or logged.</p>
 *
 * <p>Assumptions: masking happens in this package rather than inside the codec, so that no value can
 * escape it by being serialised from somewhere else, and the mask itself is
 * {@link PendingAuthDetailMapper#maskedCardNumber(String)} rather than a second implementation here.
 * A representation concern appearing twice inside the one package chartered to hold it would defeat
 * the charter.</p>
 *
 * <p>Assumptions: three timestamp regimes meet in this context and this class uses exactly one of
 * them. The outbox instants below are ordinary microsecond audit values, matching the
 * {@code TIMESTAMP(6)} columns that store them. They are NOT the twenty-three character authorization
 * timestamp, which {@link PendingAuthDetailMapper#authTimestampText(PendingAuthDetail)} owns, and they
 * are not the twenty-six character form {@code com.carddemo.common.time.TimestampFormatter} produces.
 * The two character forms are rendered text for a screen and for a record layout; an outbox deadline
 * is an instant that gets compared, so it stays an instant.</p>
 *
 * <p>Assumptions: no golden master exists for any path in this class, and the reason is worth stating
 * so that no future reader assumes one was consulted. The reference consumer cannot be compiled at
 * all: the eight {@code COPY} statements at {@code cbl/COPAUA0C.cbl} L149 through L170 name six
 * vendor message-queue copybooks, none of which is present in this repository. The online programs of
 * this extension additionally cannot run without a transaction-processing runtime, as
 * {@code tests/README.md} L83 to L85 records, and the request producer is not supplied by the
 * reference source. Parity here therefore rests on the copybook and definition contracts and on
 * transcribed logic, each cited at the site that depends on it.</p>
 *
 * <p>Assumptions: the two destinations this context exchanges messages with are referred to by ROLE
 * only -- a pending-authorization request queue and a pending-authorization reply queue. No queue
 * name, address, identifier or credential appears in this source. A reply destination arrives as
 * data on the request, mirroring {@code cbl/COPAUA0C.cbl} L741 to L742, which move a per-request
 * queue name onto the reply descriptor rather than addressing one predetermined place.</p>
 */
@Component
public class AuthorizationMessageMapper {

    /**
     * The reply expiry the reference descriptor sets, in tenths of a second.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} L750 performs {@code MOVE 50 TO MQMD-EXPIRY}, and that
     * descriptor field is denominated in TENTHS of a second, so fifty of them is five seconds. The
     * unit is stated because the literal alone is ambiguous and both plausible misreadings are wrong
     * by two orders of magnitude in opposite directions: as milliseconds it would be a twentieth of a
     * second and every reply would be stale on arrival, and as seconds it would be fifty and a
     * requester that had already given up would still be answered. The neighbouring receive wait at
     * L242 is denominated in MILLISECONDS and set to 5000, so the two settings agree on five seconds
     * by different arithmetic -- which is exactly why neither unit can be inferred from the other.</p>
     */
    public static final int REPLY_EXPIRY_TENTHS_OF_A_SECOND = 50;

    /**
     * The number of milliseconds in one tenth of a second, used to convert the declared expiry.
     */
    private static final long MILLIS_PER_TENTH_OF_A_SECOND = 100L;

    /**
     * The interval after which a reply stops being worth sending, five seconds.
     *
     * <p>Assumptions: this is DERIVED from {@link #REPLY_EXPIRY_TENTHS_OF_A_SECOND} rather than
     * written as five, so the reference literal and the interval it means cannot drift apart and a
     * reader can see the conversion instead of trusting it.</p>
     */
    public static final Duration REPLY_EXPIRY_HORIZON =
            Duration.ofMillis(REPLY_EXPIRY_TENTHS_OF_A_SECOND * MILLIS_PER_TENTH_OF_A_SECOND);

    /**
     * The declared length of the diagnostic record, one hundred and twenty-two characters.
     *
     * <p>Assumptions: {@code cpy/CCPAUERY.cpy} declares eleven all-character fields at L20 to L40
     * whose widths sum to 122: six, six, eight, eight, four, one, one, nine, nine, fifty and twenty.
     * The figure is recorded because it is the whole of the record's contract -- the reference write at
     * L1004 of {@code cbl/COPAUA0C.cbl} passes {@code LENGTH OF ERROR-LOG-RECORD} rather than a
     * literal, so the layout is the only place the length is stated.</p>
     */
    public static final int ERROR_LOG_RECORD_LENGTH = 122;

    /**
     * The diagnostic severity that ends the reference program.
     *
     * <p>Assumptions: {@code 88 ERR-CRITICAL VALUE 'C'} at {@code cpy/CCPAUERY.cpy} L29, tested at
     * {@code cbl/COPAUA0C.cbl} L1008 to L1010, where {@code IF ERR-CRITICAL} performs the end
     * routine. The severity therefore carries a CONTROL-FLOW consequence and is not merely a label on
     * a line of text.</p>
     */
    public static final String ERROR_LEVEL_CRITICAL = "C";

    /**
     * The diagnostic severity the reference program records a business condition under.
     *
     * <p>Assumptions: {@code 88 ERR-WARNING VALUE 'W'} at {@code cpy/CCPAUERY.cpy} L28. The reference
     * uses exactly this severity for the three record-not-found conditions -- {@code 5100-READ-XREF-RECORD}
     * at {@code cbl/COPAUA0C.cbl} L495, the account read at L542 and the customer read at L590 -- each
     * paired with {@link #ERROR_SUBSYSTEM_APPLICATION}. It is NOT terminal: the program records the
     * condition, declines the authorization and carries on with the next message, which is why
     * {@link ErrorLogEntry#isTerminal()} answers false for it.</p>
     */
    public static final String ERROR_LEVEL_WARNING = "W";

    /**
     * The diagnostic subsystem the reference program attributes a business condition to.
     *
     * <p>Assumptions: {@code 88 ERR-APP VALUE 'A'} at {@code cpy/CCPAUERY.cpy} L31, as opposed to the
     * five infrastructure subsystems declared beside it at L32 to L36. A record that is simply absent is
     * the application's own condition rather than a fault of the datastore that answered, which is the
     * distinction the reference draws by pairing this subsystem with the warning severity while pairing
     * a datastore fault with {@link #ERROR_LEVEL_CRITICAL} and that datastore's own subsystem.</p>
     */
    public static final String ERROR_SUBSYSTEM_APPLICATION = "A";

    /**
     * The purpose the diagnostic event key is tokenised under.
     *
     * <p>Assumptions: the value follows the same three-part shape as the purposes
     * {@code CsvAuthCodec} declares for the queue identities -- {@code carddemo/pauth/correlation},
     * {@code carddemo/pauth/order-group} and {@code carddemo/pauth/deduplication} -- so the whole set is
     * readable as one namespace and a reader can see at a glance that four purposes exist and what each
     * is for.</p>
     *
     * <p>Assumptions: it is DISTINCT from all three of those, and the distinctness is the control rather
     * than a naming nicety. {@code OpaqueIdentifier} authenticates the purpose along with the value, so
     * two purposes yield unrelated tokens for one input; sharing the correlation purpose would let a
     * reader of the log stream match a diagnostic line to a queue message carrying the same token and
     * recover, by correlation, which card the failure concerned -- reconstructing from two tokenised
     * values the identifier neither of them discloses.</p>
     */
    public static final String EVENT_KEY_TOKEN_PURPOSE = "carddemo/pauth/diagnostic-event-key";

    /**
     * The pattern the diagnostic record's date field is rendered in, year first.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} L990 to L994 format the current instant with
     * {@code FORMATTIME ... YYMMDD(WS-CUR-DATE-X6)} into a field its L51 declares
     * {@code PIC X(06)}, so the field holds a two-digit year, then the month, then the day, with no
     * separators at all. It is neither an ordered international form nor the month-first display form
     * the reference screens use, and rendering it as either would stop a diagnostic line matching the
     * value the reference application itself wrote.</p>
     */
    private static final DateTimeFormatter ERROR_DATE_PATTERN = DateTimeFormatter.ofPattern("yyMMdd");

    /**
     * The pattern the diagnostic record's time field is rendered in, hours first.
     *
     * <p>Assumptions: the companion {@code TIME(WS-CUR-TIME-X6)} clause at L993 fills a second
     * {@code PIC X(06)} field, declared at L52, with hours, minutes and seconds in that order and
     * again unseparated.</p>
     */
    private static final DateTimeFormatter ERROR_TIME_PATTERN = DateTimeFormatter.ofPattern("HHmmss");


    /**
     * The wire position of {@code PA-RQ-PROCESSING-CODE} among the eighteen request fields.
     *
     * <p>Assumptions: the position is recorded as an index into
     * {@link CsvAuthCodec#REQUEST_FIELD_WIDTHS} rather than as the width itself, so the declared width
     * this class fills to has exactly one source -- the codec that also encodes and decodes it. Writing
     * the six here would create a second statement of the same copybook fact, and a copybook change
     * would then leave the two disagreeing with nothing failing. The field is the eighth declared at
     * {@code cpy/CCPAURQY.cpy} L26, hence the zero-based seven.</p>
     */
    private static final int PROCESSING_CODE_POSITION = 7;

    /**
     * The wire position of {@code PA-RQ-POS-ENTRY-MODE} among the eighteen request fields.
     *
     * <p>Assumptions: the twelfth field declared at {@code cpy/CCPAURQY.cpy} L30, hence the zero-based
     * eleven, indexed into {@link CsvAuthCodec#REQUEST_FIELD_WIDTHS} for the reason recorded on
     * {@link #PROCESSING_CODE_POSITION}.</p>
     */
    private static final int POS_ENTRY_MODE_POSITION = 11;

    /**
     * The character a numeric-display field is filled with on the left.
     *
     * <p>Assumptions: a {@code PIC 9(n)} field moved a shorter value into is filled with zeros and not
     * with blanks, which is the whole difference between this constant and the pad character the codec
     * applies on the right to a {@code PIC X(n)} field.</p>
     */
    private static final char NUMERIC_DISPLAY_FILL = '0';

    /**
     * The validation engine the payload constraints are applied through.
     */
    private final Validator validator;

    /**
     * Creates the mapper.
     *
     * <p>Assumptions: the engine arrives through the constructor rather than being created here, so
     * this class uses the application's own configured engine -- the same one the web layer applies to
     * a request body -- and a unit test can supply an engine built from the default provider without a
     * container. The field is final and is the only state this class holds, which is what allows one
     * instance to be shared by every consumer.</p>
     *
     * @param validator the validation engine; must not be {@code null}
     * @throws NullPointerException if {@code validator} is {@code null}
     */
    public AuthorizationMessageMapper(Validator validator) {
        // WHY : Refactoring Rationale: the engine is null-checked HERE, and it was not -- the parameter
        //       was documented as required and then assigned unchecked. An absent engine has no
        //       observable consequence at construction; it surfaces on the first crossing, as a null
        //       dereference inside a validation call, in a component that has already accepted a message
        //       off a queue and is midway through deciding an authorization. Refusing it at construction
        //       turns a mis-wired context into a startup failure naming the missing collaborator, which
        //       is the difference between a deployment that never serves and one that fails per message.
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * Derives the structured request payload from the decoded wire record.
     *
     * <p>Assumptions: the payload is validated AFTER it is built and not before, because the
     * constraints are declared on the payload's own components and there is nothing to apply them to
     * until it exists. A wire record that decoded successfully can still carry a value the payload's
     * domain refuses -- a negative amount is exactly that case, refused by
     * {@link AuthorizationRequestPayload#isAmountWithinRecordDomain} against the ten integer digits
     * and two decimals of {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at
     * {@code cpy/CCPAURQY.cpy} L27 -- so this crossing can fail even though the decode did not.</p>
     *
     * <p>Assumptions: the tenth component is read from {@link AuthRequest#merchantCategoryCode()} and
     * written to the identically named payload component, while the field it came from is
     * {@code PA-RQ-MERCHANT-CATAGORY-CODE} at {@code cpy/CCPAURQY.cpy} L28. The internal spelling is
     * the conventional one and the wire spelling is the baseline's; the full reasoning is on
     * {@link #toPendingAuthDetail(PendingAuthDetailKey, AuthRequest, AuthReply, String)}, which is
     * where the persisted name is decided.</p>
     *
     * @param source the decoded wire record; must not be {@code null}
     * @return the equivalent payload, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws ConstraintViolationException if the derived payload violates its declared contract
     */
    public AuthorizationRequestPayload toPayload(AuthRequest source) {
        if (source == null) {
            throw new NullPointerException("request wire record must not be null");
        }
        AuthorizationRequestPayload payload = new AuthorizationRequestPayload(source.authDate(),
                source.authTime(), source.cardNum(), source.authType(), source.cardExpiryDate(),
                source.messageType(), source.messageSource(), source.processingCode(),
                source.transactionAmount(), source.merchantCategoryCode(),
                source.acquirerCountryCode(), source.posEntryMode(), source.merchantId(),
                source.merchantName(), source.merchantCity(), source.merchantState(),
                source.merchantZip(), source.transactionId());
        requireValid(payload, "authorization request payload");
        return payload;
    }

    /**
     * Derives the wire record from the structured request payload.
     *
     * <p>Assumptions: the payload is validated BEFORE the wire record is built, which is the reverse
     * of the direction above and is deliberate. The wire record's compact constructor raises a
     * width-or-delimiter failure naming a copybook field, one of the eighteen
     * {@code CsvAuthCodec.REQUEST_FIELD_NAMES} declares from {@code cpy/CCPAURQY.cpy} L19 to L36;
     * validating first means a caller with a structurally invalid payload is told which COMPONENT is
     * wrong, in the vocabulary it supplied, rather than being told about a copybook field it never
     * named.</p>
     *
     * @param payload the structured payload; must not be {@code null}
     * @return the equivalent wire record, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws ConstraintViolationException if the payload violates its declared contract
     * @throws AuthMessageFormatException if a component exceeds the width its copybook line declares
     *     or contains the delimiter, which the wire record's own constructor raises
     */
    public AuthRequest toWireRecord(AuthorizationRequestPayload payload) {
        if (payload == null) {
            throw new NullPointerException("request payload must not be null");
        }
        requireValid(payload, "authorization request payload");
        return new AuthRequest(payload.authDate(), payload.authTime(), payload.cardNumber(),
                payload.authType(), payload.cardExpiryDate(), payload.messageType(),
                payload.messageSource(), numericDisplay(payload.processingCode(), PROCESSING_CODE_POSITION), payload.transactionAmount(),
                payload.merchantCategoryCode(), payload.acquirerCountryCode(),
                numericDisplay(payload.posEntryMode(), POS_ENTRY_MODE_POSITION), payload.merchantId(), payload.merchantName(),
                payload.merchantCity(), payload.merchantState(), payload.merchantZip(),
                payload.transactionId());
    }

    /**
     * Derives the structured reply payload from the wire record.
     *
     * @param source the reply wire record; must not be {@code null}
     * @return the equivalent payload, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws ConstraintViolationException if the derived payload violates its declared contract
     */
    public AuthorizationReplyPayload toPayload(AuthReply source) {
        if (source == null) {
            throw new NullPointerException("reply wire record must not be null");
        }
        AuthorizationReplyPayload payload = new AuthorizationReplyPayload(source.cardNum(),
                source.transactionId(), source.authIdCode(), source.authRespCode(),
                source.authRespReason(), source.approvedAmount());
        requireValid(payload, "authorization reply payload");
        return payload;
    }

    /**
     * Derives the wire record from the structured reply payload, so that the encoder receives the
     * amount in the form the reference program actually emits.
     *
     * <p>Assumptions: the amount crosses as an exact decimal and is rendered only when
     * {@link CsvAuthCodec#encodeReply(AuthReply)} writes it, which matters because the DECLARED
     * picture and the EMITTED mask differ. {@code cpy/CCPAURLY.cpy} L24 declares
     * {@code PA-RL-APPROVED-AMT PIC +9(10).99}, whereas the reference program moves the value into
     * {@code WS-APPROVED-AMT-DIS}, declared {@code PIC -zzzzzzzzz9.99} at
     * {@code cbl/COPAUA0C.cbl} L66, at its L720 immediately before assembling the payload at L722 to
     * L731 -- so the mask on the wire is the edited one. Both forms occupy fourteen characters, which
     * is precisely why the difference survives every width check, and they differ in two observable
     * ways: the sign position is blank for a positive value rather than carrying a mandatory plus, and
     * leading zeros are suppressed to spaces rather than written as digits. A codec written to the
     * declared picture would put {@code +0000002065.00} where the reference program puts
     * {@code        2065.00}, and a counterparty matching on the leading character would refuse every
     * message. The rendering therefore stays with the codec that owns it and is not anticipated
     * here.</p>
     *
     * <p>Assumptions: the two directions are NOT symmetric on the wire. Inbound, the reference program
     * converts the parsed characters numerically with {@code COMPUTE ... FUNCTION NUMVAL} at
     * {@code cbl/COPAUA0C.cbl} L376 to L377, with no edited intermediary at all; outbound it passes
     * through the edited display item at L720. Assuming one shape for both is the mistake this note
     * exists to prevent.</p>
     *
     * @param payload the structured payload; must not be {@code null}
     * @return the equivalent wire record, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws ConstraintViolationException if the payload violates its declared contract
     * @throws AuthMessageFormatException if a component exceeds the width its copybook line declares
     *     or contains the delimiter, which the wire record's own constructor raises
     */
    public AuthReply toWireRecord(AuthorizationReplyPayload payload) {
        if (payload == null) {
            throw new NullPointerException("reply payload must not be null");
        }
        requireValid(payload, "authorization reply payload");
        return new AuthReply(payload.cardNumber(), payload.transactionId(), payload.authIdCode(),
                payload.authResponseCode(), payload.authResponseReason(), payload.approvedAmount());
    }

    /**
     * Projects a decided authorization onto the durable detail row, taking the acquirer's values from
     * the request and the decision's values from the reply.
     *
     * <p>Assumptions: the reply is the carrier of the decision, so the four decided items -- the
     * identification code, the response code, the response reason and the approved amount -- are read
     * from it rather than passed separately. Those are exactly the components
     * {@link AuthReply} declares beyond the two identifiers, so nothing has to be supplied twice and
     * the persisted row cannot disagree with the answer that was sent. The alternative was a parameter
     * per decided field, which would have let a caller store one response code and transmit
     * another.</p>
     *
     * <p>Refactoring Rationale: the merchant category is spelled {@code merchantCategoryCode} on this
     * entity and stored in a column named {@code merchant_category_code}, replacing the baseline
     * spelling, and BOTH halves of that decision matter. The baseline propagates
     * {@code CATAGORY} through four separate artifacts -- {@code cpy/CIPAUDTY.cpy} L36 as
     * {@code PA-MERCHANT-CATAGORY-CODE}, {@code cpy/CCPAURQY.cpy} L28 as the request's
     * {@code PA-RQ-MERCHANT-CATAGORY-CODE}, {@code ddl/AUTHFRDS.ddl} L14 and
     * {@code dcl/AUTHFRDS.dcl} L37 as the column {@code MERCHANT_CATAGORY_CODE} -- and the rename is
     * applied at the SCHEMA boundary only, registered in
     * {@code docs/architecture/data-model-and-schema-mapping.md}. The WIRE keeps the baseline
     * spelling: {@code CsvAuthCodec.REQUEST_FIELD_NAMES} carries
     * {@code PA-RQ-MERCHANT-CATAGORY-CODE} at the tenth position, because renaming the field on the
     * wire would break an external counterparty this migration does not control, whereas a column name
     * is read only by code inside it. The rejected alternative was to carry the misspelling everywhere
     * for consistency, whose consequence is that every future reader of the schema has to learn that
     * the column is misspelled before they can query it. The baseline artifacts themselves are read as
     * the specification and are never edited; this note records the consequence of their structure, not
     * a verdict on it.</p>
     *
     * <p>Assumptions: the entry-mode crossing is a genuine type change and not a copy. The wire and
     * payload carry it as characters, because {@code PA-RQ-POS-ENTRY-MODE} is
     * {@code PIC 9(02)} at {@code cpy/CCPAURQY.cpy} L30 and a character form preserves a leading zero,
     * while the column is a small integer bounded to two digits. The processing code is deliberately
     * NOT given the same treatment: it stays characters end to end, because
     * {@code PA-RQ-PROCESSING-CODE PIC 9(06)} at L26 admits a value such as {@code 000001} whose
     * leading zeros are part of the code rather than an artefact of its width.</p>
     *
     * <p>Assumptions: both amounts cross as exact decimals at scale two, taken through
     * {@code Money#amount()} into the {@code NUMERIC(12,2)} columns that store them. No binary
     * floating-point type appears anywhere on this path, and none may: a reply's approved amount is a
     * value a cardholder is billed for, and the prohibition is asserted architecturally rather than
     * left to review.</p>
     *
     * @param key the account and instant that identify this authorization, carrying the plain ordinal
     *     date and time of day rather than the complement the reference segment stores; must not be
     *     {@code null}
     * @param request the decoded request supplying every acquirer-provided field; must not be
     *     {@code null}
     * @param decision the reply that was answered, supplying the four decided fields; must not be
     *     {@code null}
     * @param matchStatus the state the decision places the authorization in, which must be
     *     {@link PendingAuthDetail#MATCH_STATUS_PENDING} for an approval or
     *     {@link PendingAuthDetail#MATCH_STATUS_DECLINED} for a decline; must not be {@code null}
     * @return the unsaved detail row, never {@code null}
     * @throws NullPointerException if {@code key}, {@code request}, {@code decision} or
     *     {@code matchStatus} is {@code null}
     * @throws IllegalArgumentException if {@code matchStatus} is outside the two states an originating
     *     decision may record, or if the request's entry mode is present but is not two digits within
     *     the range the column admits
     */
    public PendingAuthDetail toPendingAuthDetail(PendingAuthDetailKey key, AuthRequest request,
            AuthReply decision, String matchStatus) {
        Objects.requireNonNull(key, "authorization key must not be null");
        Objects.requireNonNull(request, "request wire record must not be null");
        Objects.requireNonNull(decision, "decision reply must not be null");
        Objects.requireNonNull(matchStatus, "match status must not be null");
        requireAnswers(request, decision);

        // WHY : Assumptions: the request's own date and time become the ORIGINATING date and time,
        //       not the key's. The reference insert draws them from the request while keying the row
        //       on the instant of processing, so the two are independent: an authorization received
        //       late still records the moment the acquirer says it happened. Both fields are six
        //       characters on the wire and six in the column, so the values cross unaltered.
        return new PendingAuthDetail(key, request.authDate(), request.authTime(), request.cardNum(),
                request.authType(), request.cardExpiryDate(), request.messageType(),
                request.messageSource(), decision.authIdCode(), decision.authRespCode(),
                decision.authRespReason(), request.processingCode(),
                request.transactionAmount().amount(), decision.approvedAmount().amount(),
                request.merchantCategoryCode(), request.acquirerCountryCode(),
                entryModeOf(request.posEntryMode()), request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantState(), request.merchantZip(),
                request.transactionId(), matchStatus);
    }

    /**
     * Projects a reply and the metadata of the request it answers onto one durable outbox publication.
     *
     * <p>Assumptions: this projection has NO antecedent in the reference source, and that is its
     * purpose rather than an omission. The reference consumer commits its database work per message
     * with {@code EXEC CICS SYNCPOINT} at {@code cbl/COPAUA0C.cbl} L335, and publishes the reply with
     * {@code MQPMO-NO-SYNCPOINT} at L753 -- outside that commit, matching the equally unsynchronised
     * receive at L389. A failure between the commit and the send therefore loses a reply that the
     * committed data says was produced. The migrated design writes this publication as a row inside the
     * same transaction as the decision and sends it from there afterwards, so a reply exists for every
     * committed decision. The baseline publishes outside the syncpoint; the Java commits the reply with
     * the data; the divergence is registered as D-5 in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Alternatives Considered: keeping the reference ordering -- commit the decision, then send --
     * was the obvious alternative and is rejected on its observable outcome: a committed authorization
     * with no reply ever sent, which the requester experiences as a timeout on a decision that in fact
     * happened. The mirror ordering, sending before committing, is rejected for the opposite outcome: a
     * requester told a card was authorized when the row recording it was rolled back. Writing the row
     * first and sending afterwards can only ever duplicate a send, and a duplicate is the one failure
     * the reply queue already suppresses on the deduplication token below.</p>
     *
     * <p>Assumptions: the queue identities are the keyed TOKENS the reply carries, never the values
     * behind them. Ordering is grouped by card and duplicates are suppressed by the card and
     * transaction pair, which is what preserves the per-card sequence the reference system gets from a
     * single-threaded consumer; but a group or deduplication identity becomes message METADATA at
     * publication, and metadata sits outside the body that server-side encryption covers. Publishing
     * the raw values would put a primary account number and the acquirer's transaction identifier into
     * queue telemetry and into the trace of every send, so
     * {@link AuthReply#orderGroup(OpaqueIdentifier)} and
     * {@link AuthReply#deduplicationKey(OpaqueIdentifier)} are used, each of which is equal for equal
     * inputs and different for different ones -- the only property ordering and suppression need.</p>
     *
     * <p>Assumptions: the payload is the encoded reply exactly as the wire carries it, sixty-three
     * characters. The reference program assembles it at {@code cbl/COPAUA0C.cbl} L722 to L731 with a
     * {@code STRING ... DELIMITED BY SIZE} that interleaves a separator after ALL SIX fields including
     * the last -- its L727 is the sixth -- so the six declared widths of {@code cpy/CCPAURLY.cpy} L19
     * to L24, which sum to 57, become 63 on the wire. Any count of five delimiters, and the shorter
     * length such a count implies, is superseded.</p>
     *
     * <p>Assumptions: the reference program transmits SIXTY-FOUR bytes for that sixty-three character
     * payload, and the Java emits sixty-three. The cause is visible in two lines: {@code WS-RESP-LENGTH}
     * is declared {@code PIC S9(4) VALUE 1} at L46 and used as the {@code WITH POINTER} of the assembly
     * at L730, so it is a ONE-BASED cursor that finishes at 64 after sixty-three characters are
     * written; L756 then reuses that same cursor as a buffer length with
     * {@code MOVE WS-RESP-LENGTH TO W02-BUFFLEN}, appending one trailing byte of the two-hundred byte
     * put buffer. This class is strict on encode, emitting exactly sixty-three, and the codec is
     * tolerant on decode, accepting sixty-three or sixty-four so that a message from the reference
     * producer still parses. The baseline transmits sixty-four; the Java emits sixty-three and accepts
     * both; the divergence is documented.</p>
     *
     * <p>Assumptions: the format label comes from {@link OutboxMessage#csvReply} rather than being
     * written here, so the message and the row that stores it take it from one constant. It stands for
     * the delimited-string format the reference descriptor declares on both directions at L397 and
     * L751.</p>
     *
     * @param reply the decided reply to be published; must not be {@code null}
     * @param routing the destination, correlation identity and deadline taken from the request being
     *     answered; must not be {@code null}
     * @param tokeniser the keyed tokeniser every producer and consumer of the reply queue shares, so
     *     that one card's messages derive one group identity; must not be {@code null}
     * @return the publication to be committed alongside the decision, never {@code null}
     * @throws NullPointerException if {@code reply}, {@code routing} or {@code tokeniser} is
     *     {@code null}, or if the routing carries no reply destination
     * @throws IllegalArgumentException if the routing's destination or correlation identity is wider
     *     than the column that stores it, or if the destination is blank
     */
    public OutboxMessage toOutboxMessage(AuthReply reply, ReplyRouting routing,
            OpaqueIdentifier tokeniser) {
        Objects.requireNonNull(reply, "reply wire record must not be null");
        Objects.requireNonNull(routing, "reply routing must not be null");
        Objects.requireNonNull(tokeniser, "messaging tokeniser must not be null");

        // WHY : Assumptions: both tokens are derived HERE, while the deciding transaction is still
        //       open, rather than at publication. The sender then holds no key material at all, and a
        //       row whose payload could not later be parsed remains publishable -- which is precisely
        //       the case where sending an answer matters most. Deriving them at publication was the
        //       alternative and would put key material into the component least able to protect it.
        return OutboxMessage.csvReply(routing.replyQueueUrl(), routing.correlationId(),
                reply.orderGroup(tokeniser), reply.deduplicationKey(tokeniser),
                CsvAuthCodec.encodeReply(reply), routing.expiresAt());
    }

    /**
     * Answers the instant after which a reply stops being worth sending.
     *
     * <p>Assumptions: the target transport has NO per-message time to live, so the reference
     * descriptor's five-second reply deadline at {@code cbl/COPAUA0C.cbl} L750 cannot be expressed as a
     * transport setting and moves into the message instead -- it travels as an attribute, a sender
     * declines a message whose instant has passed, and a consumer drops and logs a stale reply. The
     * conversion from the descriptor's tenths of a second is done once, by
     * {@link #REPLY_EXPIRY_HORIZON}.</p>
     *
     * <p>Alternatives Considered: relying on short queue retention alone was evaluated and rejected.
     * Retention is a queue-wide floor rather than a per-message deadline, so a reply could still be
     * delivered long after the requester stopped waiting for it, and a requester acting on a five
     * second old authorization decision is the outcome the deadline exists to prevent. Retention is
     * kept short on the reply queue as well, as a backstop rather than as the mechanism. The gap and
     * this resolution are recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
     *
     * @param sentAt the instant the reply is produced, in coordinated universal time; must not be
     *     {@code null}
     * @return the instant the reply goes stale, never {@code null}
     * @throws NullPointerException if {@code sentAt} is {@code null}
     */
    public static LocalDateTime replyExpiresAt(LocalDateTime sentAt) {
        Objects.requireNonNull(sentAt, "sent instant must not be null");
        return sentAt.plus(REPLY_EXPIRY_HORIZON);
    }

    /**
     * Answers the keyed token that identifies a request and its reply as one exchange.
     *
     * <p>Assumptions: the identity being tokenised is the business correlation key -- the sixteen
     * character card number followed by the fifteen character transaction identifier, thirty-one
     * characters in all, each part held at its declared width. That pair is what names one
     * authorization semantically, independently of any identifier the transport supplies, which is why
     * both fields lead the message: a consumer can pair a reply to a request without decoding the rest
     * of either.</p>
     *
     * <p>Alternatives Considered: returning those thirty-one characters themselves, which is how the
     * reference program correlates, was evaluated and rejected. A correlation identity is written to
     * message metadata, to queue telemetry and to application log lines, none of which is behind the
     * boundary that masks a card number, so the raw pair would publish a primary account number to
     * every one of them. A keyed token pairs by equality exactly as the pair does, and what is given
     * up -- reading the card number back out of a correlation value -- is the point of using one.</p>
     *
     * @param request the decoded request to derive the identity from; must not be {@code null}
     * @param tokeniser the keyed tokeniser both ends of the exchange share, so that a request and its
     *     reply derive the identical token; must not be {@code null}
     * @return the correlation token, carrying neither the card number nor the transaction identifier,
     *     never {@code null}
     * @throws NullPointerException if {@code request} or {@code tokeniser} is {@code null}
     */
    public static String businessCorrelationToken(AuthRequest request, OpaqueIdentifier tokeniser) {
        Objects.requireNonNull(request, "request wire record must not be null");
        return request.correlationKey(tokeniser);
    }

    /**
     * Answers the keyed token that identifies a reply and the request it answers as one exchange.
     *
     * <p>Assumptions: this returns the SAME token the request overload returns for the same card and
     * transaction pair under the same key, because both derive it from the identical thirty-one
     * character identity under one purpose. That equality is the whole mechanism by which a reply is
     * matched to its request, so it is a requirement rather than a coincidence; the overload exists
     * because a sender draining the outbox holds only a reply and would otherwise need the eighteen
     * request fields to name the exchange it is completing.</p>
     *
     * @param reply the reply to derive the identity from; must not be {@code null}
     * @param tokeniser the keyed tokeniser both ends of the exchange share; must not be {@code null}
     * @return the correlation token, carrying neither the card number nor the transaction identifier,
     *     never {@code null}
     * @throws NullPointerException if {@code reply} or {@code tokeniser} is {@code null}
     */
    public static String businessCorrelationToken(AuthReply reply, OpaqueIdentifier tokeniser) {
        Objects.requireNonNull(reply, "reply wire record must not be null");
        return reply.correlationKey(tokeniser);
    }

    /**
     * Builds the diagnostic record that a failure is reported through, rendering its date and time in
     * the two unseparated forms the reference program writes.
     *
     * <p>Assumptions: the date is rendered year first and the time hours first, both six characters and
     * both without separators, because {@code cbl/COPAUA0C.cbl} L990 to L994 fill them with
     * {@code FORMATTIME ... YYMMDD(WS-CUR-DATE-X6) TIME(WS-CUR-TIME-X6)} into the two
     * {@code PIC X(06)} fields its L51 and L52 declare. The remaining identifying fields are moved in
     * at L996 to L999.</p>
     *
     * <p>Alternatives Considered: rendering either field in an ordered international form, or in the
     * month-first and colon-separated display forms this package already provides for the detail
     * screen, was evaluated and rejected. The record's own contract is the unseparated form, so a
     * rendered value would not match the diagnostic text the reference application itself emitted and
     * an operator correlating the two would find nothing. Where a display form is genuinely wanted the
     * existing helpers on {@link PendingAuthDetailMapper} produce it from these same six characters, so
     * no third date convention is introduced by this class.</p>
     *
     * @param occurredAt the instant the failure was observed, in coordinated universal time; must not
     *     be {@code null}
     * @param application the transaction identifier the failure was observed under, at most eight
     *     characters; may be {@code null}
     * @param program the program or component that observed it, at most eight characters; may be
     *     {@code null}
     * @param location the four-character position within that component; may be {@code null}
     * @param level the single-character severity, of which {@link #ERROR_LEVEL_CRITICAL} is terminal;
     *     may be {@code null}
     * @param subsystem the single-character subsystem the failure arose in; may be {@code null}
     * @param codeOne the first nine-character status or reason code; may be {@code null}
     * @param codeTwo the second nine-character status or reason code; may be {@code null}
     * @param message the fifty-character description; may be {@code null}
     * @param eventKey the twenty-character key of the item being processed; may be {@code null}
     * @return the diagnostic record, never {@code null}
     * @throws NullPointerException if {@code occurredAt} is {@code null}
     */
    public static ErrorLogEntry errorLogEntry(LocalDateTime occurredAt, String application,
            String program, String location, String level, String subsystem, String codeOne,
            String codeTwo, String message, String eventKey) {
        Objects.requireNonNull(occurredAt, "observed instant must not be null");
        return new ErrorLogEntry(ERROR_DATE_PATTERN.format(occurredAt),
                ERROR_TIME_PATTERN.format(occurredAt), application, program, location, level,
                subsystem, codeOne, codeTwo, message, eventKey);
    }

    /**
     * Applies the declared constraints of one payload and raises on any violation.
     *
     * <p>Assumptions: every violation is reported rather than the first one, because a producer
     * correcting a payload with three wrong fields should learn about three of them from one rejection.
     * The exception type is the specification's own, so a caller already handling validation failures
     * from the web layer handles these identically.</p>
     *
     * @param <T> the payload type being validated
     * @param candidate the payload to validate; must not be {@code null}
     * @param description the payload's name, used to introduce the violations in the failure message
     * @throws ConstraintViolationException if the payload violates its declared contract
     */
    private <T> void requireValid(T candidate, String description) {
        Set<ConstraintViolation<T>> violations = this.validator.validate(candidate);
        if (!violations.isEmpty()) {
            // WHY : Assumptions: the message names the payload and the count and nothing else, while
            //       the violation set travels on the exception for a caller that renders per-component
            //       detail. A message composed from the violations themselves would quote the offending
            //       values, and on this payload those values are a primary account number and an
            //       authorization amount.
            throw new ConstraintViolationException(description + " violates its declared contract in "
                    + violations.size() + " component(s)", violations);
        }
    }

    /**
     * Converts the entry mode from the characters the wire carries to the small integer the column
     * stores.
     *
     * <p>Assumptions: an absent or blank value becomes absent rather than zero. Zero is a MEANINGFUL
     * entry mode within the two-digit range the column admits, so defaulting a missing value to it
     * would record a specific claim about how the card details were captured where the acquirer made
     * none.</p>
     *
     * @param posEntryMode the entry mode as the wire carries it, or {@code null} when absent
     * @return the entry mode as a small integer, or {@code null} when the value is absent or blank
     * @throws IllegalArgumentException if the value is present and non-blank but is not a number the
     *     column's two-digit domain can hold
     */
    private static Short entryModeOf(String posEntryMode) {
        if (posEntryMode == null || posEntryMode.isBlank()) {
            return null;
        }
        try {
            return Short.valueOf(posEntryMode.trim());
        } catch (NumberFormatException notNumeric) {
            // WHY : Assumptions: the rejected value is NOT quoted back. It arrives from a queue and is
            //       therefore attacker-influenced text, and this failure is reported through the same
            //       log stream the diagnostic record below uses; the field name alone identifies which
            //       of the eighteen positions was wrong, which is what a diagnosis needs.
            throw new IllegalArgumentException("PA-RQ-POS-ENTRY-MODE must be a two-digit entry mode",
                    notNumeric);
        }
    }

    /**
     * Refuses a reply that does not answer the request it is being combined with.
     *
     * <p>Refactoring Rationale: this check did not exist, and its absence was reachable by an ordinary
     * mistake rather than by misuse. The projection above takes the acquirer's fields from one object
     * and the decided fields from another, and the two identifiers that say the pair belong together --
     * the card number at {@code cpy/CCPAURQY.cpy} L21 and {@code cpy/CCPAURLY.cpy} L19, the transaction
     * identifier at L36 and L20 -- appear on both. With no check, a caller that paired one
     * authorization's request with another's reply produced a detail row carrying one card's merchant
     * data and another card's decision, and the same pair then addressed the reply: the wrong requester
     * would be told the wrong outcome, and the persisted history of two accounts would both be wrong,
     * with nothing failing at any point.</p>
     *
     * <p>Alternatives Considered: passing the four decided fields as parameters instead of a reply
     * object, which would make the mismatch unrepresentable. Rejected on the reasoning already recorded
     * on the projection: separate parameters let a caller persist one response code and transmit
     * another, which is a worse failure than the one this check closes because it has no single place it
     * could be detected. Alternatives Considered: comparing the values after trimming. Rejected because
     * both records hold declared-width character fields with their trailing pad already removed by the
     * codec, so two renderings of one identity are equal as they stand; admitting a difference in
     * trailing blanks would accept a pair assembled from two different decodings and lose the property
     * being checked.</p>
     *
     * @param request the decoded request supplying the acquirer's fields; must not be {@code null}
     * @param decision the reply supplying the decided fields; must not be {@code null}
     * @throws IllegalArgumentException if the two disagree on the card number or on the transaction
     *     identifier; the message names WHICH identifier disagrees and neither value, because one of
     *     them is a primary account number
     */
    private static void requireAnswers(AuthRequest request, AuthReply decision) {
        if (!Objects.equals(request.cardNum(), decision.cardNum())) {
            throw new IllegalArgumentException("the reply's PA-RL-CARD-NUM does not match the"
                    + " request's PA-RQ-CARD-NUM, so the reply does not answer this request");
        }
        if (!Objects.equals(request.transactionId(), decision.transactionId())) {
            throw new IllegalArgumentException("the reply's PA-RL-TRANSACTION-ID does not match the"
                    + " request's PA-RQ-TRANSACTION-ID, so the reply does not answer this request");
        }
    }

    /**
     * Fills a numeric-display field on the left with zeros to the width its copybook line declares.
     *
     * <p>Assumptions: only a SHORT value is altered. A value already at the declared width crosses
     * unchanged, and an over-wide one crosses unchanged as well so that the wire record's own
     * constructor refuses it by name -- refusing it here would report the fault without the copybook
     * field it belongs to, and truncating it would send a different number than the caller supplied.</p>
     *
     * <p>Assumptions: an absent value stays absent. A blank or {@code null} numeric-display field means
     * the acquirer supplied nothing, and filling it would emit six or two zeros -- a specific claim
     * ({@code 000000} is a processing code, {@code 00} an entry mode) where none was made.</p>
     *
     * @param value the field value as the payload carries it, which may be {@code null} or blank
     * @param position the field's zero-based position among the eighteen request fields, used to read
     *     its declared width from {@link CsvAuthCodec#REQUEST_FIELD_WIDTHS}
     * @return the value filled on the left to the declared width, or the value unaltered when it is
     *     absent, blank, already at that width or wider than it
     */
    private static String numericDisplay(String value, int position) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int declaredWidth = CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(position);
        if (value.length() >= declaredWidth) {
            return value;
        }
        StringBuilder filled = new StringBuilder(declaredWidth);
        while (filled.length() < declaredWidth - value.length()) {
            filled.append(NUMERIC_DISPLAY_FILL);
        }
        return filled.append(value).toString();
    }

    /**
     * The transport metadata of an inbound request that its reply has to be addressed and dated by.
     *
     * <p>Assumptions: the reference message descriptor's concerns map onto three transport attributes
     * and this record is where they arrive together. The destination comes from the descriptor's
     * reply-to queue, which {@code cbl/COPAUA0C.cbl} L413 to L414 capture from the inbound message and
     * L741 to L742 move onto the outgoing one -- so a reply goes where its request nominated rather than
     * to one configured place, and the destination is DATA. The correlation identity is captured at
     * L411 to L412 into {@code WS-SAVE-CORRELID}, declared {@code PIC X(24)} at L45, and echoed
     * unaltered at L745; the equivalent width on the inbound edge is
     * {@link CorrelationIdFilter#CORRELATION_ID_MAX_LENGTH}, and the identity reaches the log stream
     * through {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} so that a diagnostic line and a
     * message carry one identity. The deadline is the descriptor's expiry from L750, converted by
     * {@link #replyExpiresAt(LocalDateTime)}.</p>
     *
     * <p>Assumptions: the destination is a plain character value and never a transport client's own
     * type. No infrastructure type may cross into this context's persistent types, and the publication
     * this record feeds is stored on a row, so admitting one here would carry it there. The value is
     * expected to be one a configured allowlist has already admitted, because the nomination arrives on
     * an attribute a requester controls; admitting it belongs to the component that receives the
     * message, before any decision is committed. No address is written into this source.</p>
     *
     * <p>Trade-offs: this is a carrier with no behaviour, which is one more type to name. The
     * alternative was three loose parameters on the projection above, and it was rejected because two
     * of the three are character values of similar width -- a destination and a correlation identity --
     * so transposing them at a call site would compile silently and answer the wrong requester.</p>
     *
     * @param replyQueueUrl the destination the request nominated for its answer, as a plain character
     *     value; must not be {@code null} or blank
     * @param correlationId the identity to echo back unaltered, which may be {@code null} because a
     *     requester need not supply one
     * @param expiresAt the instant after which the reply stops being worth sending, which may be
     *     {@code null} to mean it never goes stale
     */
    public record ReplyRouting(String replyQueueUrl, String correlationId,
            LocalDateTime expiresAt) {

        /**
         * Refuses a routing that cannot be made durable or cannot be addressed.
         *
         * <p>Refactoring Rationale: these components were accepted unchecked. A routing is the address a
         * committed decision's reply is sent to and the identity that reply is correlated by, and it is
         * built from message attributes the REQUESTER supplies. An absent or blank destination produced
         * an outbox row that no dispatcher could deliver, and an over-wide component produced one the
         * storing column truncated or refused at flush time -- both failures surfacing after the
         * authorization was committed, when the reply is the only thing left to go wrong. Refusing at
         * construction moves the failure to the point that has the faulty value.</p>
         *
         * @param replyQueueUrl the destination the reply is sent to, taken from the request message's
         *     reply-to attribute; required and never blank, because an outbox row naming no destination
         *     can never be delivered
         * @param correlationId the identity the reply is correlated by, taken from the request message's
         *     correlation attribute; optional, since a requester that supplied none is answered on the
         *     destination alone
         * @param expiresAt the instant after which the reply is stale and is dropped rather than sent,
         *     which is the target equivalent of the reference producer's message expiry; required
         * @throws NullPointerException if {@code replyQueueUrl} or {@code expiresAt} is {@code null}
         * @throws IllegalArgumentException if {@code replyQueueUrl} is blank, or if either component is
         *     wider than the column that stores it
         */
        public ReplyRouting {
            Objects.requireNonNull(replyQueueUrl, "replyQueueUrl must not be null");
            if (replyQueueUrl.isBlank()) {
                throw new IllegalArgumentException("replyQueueUrl must not be blank");
            }
            requireWithin(replyQueueUrl, "replyQueueUrl", OutboxMessage.REPLY_QUEUE_URL_MAX_LENGTH);
            requireWithin(correlationId, "correlationId", OutboxMessage.CORRELATION_ID_MAX_LENGTH);
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        /**
         * Refuses a component wider than the column that stores it.
         *
         * <p>Assumptions: the failure names the component and its two lengths and never its value. A
         * reply destination is a deployment address and a correlation identity arrives from a message
         * attribute, so one of the two is attacker-influenced text, and this refusal is reported through
         * the same log stream every other diagnostic on this path uses.</p>
         *
         * @param value the component to check, which may be {@code null} for an absent optional one
         * @param component the component's name, reproduced in the refusal
         * @param maxLength the width the storing column declares
         * @throws IllegalArgumentException if {@code value} is present and wider than {@code maxLength}
         */
        private static void requireWithin(String value, String component, int maxLength) {
            if (value != null && value.length() > maxLength) {
                throw new IllegalArgumentException(component + " is " + value.length()
                        + " characters but at most " + maxLength + " can be made durable");
            }
        }

        /**
         * Renders the routing for a diagnostic line, omitting the destination it names.
         *
         * <p>Assumptions: the destination is OMITTED and its presence is reported instead. A queue
         * destination is deployment topology -- it carries the account the queue belongs to, the region
         * it lives in and the queue's own name -- and it arrives on an attribute the REQUESTER controls,
         * so a rendering that reproduced it would put a requester-supplied string describing our own
         * infrastructure into a log store. The rendering rule in
         * {@code docs/architecture/observability.md} requires omission rather than abbreviation for a
         * withheld value, and a truncated queue address would still name the account.</p>
         *
         * <p>Assumptions: the correlation identity IS rendered, and it is the one member of this record
         * that a diagnostic needs. Part three of that rule admits it by name, and the same value reaches
         * the log stream through {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} on the request path
         * -- so rendering it here is what lets a reply's diagnostic line be joined to the request that
         * produced it, which is the join the rule offers in place of naming a row.</p>
         *
         * <p>Trade-offs: reporting the destination as a boolean rather than dropping the member entirely
         * costs one word and answers the question a reader of this line actually has, which is whether a
         * reply could be addressed at all. A missing destination and an unreachable one present the same
         * way in a queue client's failure, and only this distinguishes them.</p>
         *
         * @return a single-line rendering naming the correlation identity and whether a destination and
         *     a deadline are present, and no queue address; never {@code null}
         */
        @Override
        public String toString() {
            return "ReplyRouting[correlationId=" + this.correlationId
                    + ", destinationPresent=" + (this.replyQueueUrl != null)
                    + ", expiresAt=" + this.expiresAt + "]";
        }
    }

    /**
     * The one-hundred-and-twenty-two character diagnostic record, projected for the log stream.
     *
     * <p>Assumptions: this record goes to CENTRALIZED STRUCTURED LOGGING and never to a queue, and the
     * reference source is unambiguous about it even though the opposite reading is the intuitive one.
     * The reference write is {@code EXEC CICS WRITEQ TD QUEUE('CSSL')} at {@code cbl/COPAUA0C.cbl}
     * L1001 to L1006 -- a transient data queue, which is the platform's system log destination and NOT a
     * message-queue put; it is the only such write in the program. The error queue that a casual reading
     * would reach for belongs to a different extension entirely: the error destination it would name is
     * referenced in exactly two places in the whole repository, {@code app/app-vsam-mq/cbl/CODATE01.cbl}
     * L243 and {@code app/app-vsam-mq/cbl/COACCT01.cbl} L294, both in the account-inquiry and
     * date-conversion tree. This tree's only message destinations are its request and reply queues.</p>
     *
     * <p>Alternatives Considered: routing this record to a durable error queue was evaluated and
     * rejected on two concrete consequences. It would provision a messaging endpoint this bounded
     * context never had, and it would put diagnostic text -- which by construction describes a failure
     * and often quotes surrounding state -- onto a durable transport instead of into the log stream
     * where retention, access control and redaction already apply to it. A log entry correlated by
     * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} is findable by the same identity the message
     * path carries, which is what the queue was being reached for in the first place.</p>
     *
     * <p>Assumptions: the eleven components are the eleven fields of {@code cpy/CCPAUERY.cpy} L20 to
     * L40 in declaration order, all character, with widths six, six, eight, eight, four, one, one,
     * nine, nine, fifty and twenty summing to {@link #ERROR_LOG_RECORD_LENGTH}. The record's
     * {@code 01} level is indented ten spaces at L19, unlike its siblings in that directory, which is
     * noted because a reader scanning for it at the usual column will not find it.</p>
     *
     * <p>Assumptions: the widths are documented and NOT enforced here. Every component is a value the
     * failing path already holds, so refusing one for width would replace a diagnostic with a second
     * failure raised while reporting the first -- the one circumstance in which losing the report is
     * worst. The projection below truncates nothing and pads nothing; it hands the values to the logger
     * as they are.</p>
     *
     * @param errDate the six characters of the observation date, year first and unseparated
     * @param errTime the six characters of the observation time, hours first and unseparated
     * @param application the eight-character transaction identifier the failure was observed under
     * @param program the eight-character program or component that observed it
     * @param location the four-character position within that component
     * @param level the single-character severity, of which {@link #ERROR_LEVEL_CRITICAL} is terminal
     * @param subsystem the single-character subsystem the failure arose in
     * @param codeOne the first nine-character status or reason code
     * @param codeTwo the second nine-character status or reason code
     * @param message the fifty-character description of the failure
     * @param eventKey the twenty-character key of the item being processed when it failed
     */
    public record ErrorLogEntry(String errDate, String errTime, String application, String program,
            String location, String level, String subsystem, String codeOne, String codeTwo,
            String message, String eventKey) {

        /**
         * Reports whether this severity ends processing rather than merely being recorded.
         *
         * <p>Assumptions: the reference program treats one severity as terminal --
         * {@code IF ERR-CRITICAL PERFORM 9990-END-ROUTINE} at {@code cbl/COPAUA0C.cbl} L1008 to L1010
         * ends the program on {@code 'C'} -- so the severity carries a control-flow consequence and not
         * just a label. This accessor surfaces that consequence so the component handling the failure
         * can honour it; a critical severity must not be quietly recorded as an ordinary warning and
         * processing continued, because the reference system would have stopped. Acting on it belongs
         * to that component, because deciding whether to stop consuming is not a mapping
         * concern.</p>
         *
         * @return true when the severity is {@link #ERROR_LEVEL_CRITICAL}, ignoring case and
         *     surrounding blanks so that a value read from a fixed-width field still matches
         */
        public boolean isTerminal() {
            return this.level != null && ERROR_LEVEL_CRITICAL.equalsIgnoreCase(this.level.trim());
        }

        /**
         * Projects the fields onto the key and value pairs a structured logger emits, omitting the
         * event key.
         *
         * <p>Assumptions: every value is passed through
         * {@link LogSafeText#sanitize(String)} first. Several of these fields originate in a message
         * this service received, so they are attacker-influenced text on its way to a log stream, and a
         * value carrying a line break would otherwise be able to forge an additional log entry. The
         * alternative was to trust the fields because the reference layout declares them as
         * fixed-width character data; that was rejected because the declared width bounds the LENGTH of
         * a value and says nothing about which characters it contains.</p>
         *
         * <p>Refactoring Rationale: this projection used to emit {@code eventKey} verbatim, and
         * sanitizing it was not enough, because sanitizing prevents log FORGING and not
         * DISCLOSURE. The reference program puts the key of the item being processed into that field,
         * and for this consumer the item is an authorization -- so the twenty characters carry a primary
         * account number, an account identifier or a customer identifier depending on which step failed.
         * Emitting it sent a protected identifier to centralized logging on every failure, which is the
         * one path guaranteed to be exercised when something is already going wrong. The field is now
         * omitted from this projection, and the overload below is the only way to record it, in
         * tokenised form.</p>
         *
         * <p>Alternatives Considered: masking the key in place with {@link CardNumberMasker}, so the
         * field kept its name and its position. Rejected because the masker abbreviates a card number
         * and this field is not always one: an eleven-digit account identifier and a nine-digit customer
         * identifier both fall below the width a card masker recognises, so they would pass through
         * unchanged while the field's name asserted it had been masked. A control that works for one of
         * three cases and announces itself for all three is worse than none.</p>
         *
         * @return the sanitized fields in declaration order with {@code eventKey} absent, never
         *     {@code null} and never containing a {@code null} value, absent components appearing as an
         *     empty value
         */
        public Map<String, String> structuredFields() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("errDate", safe(this.errDate));
            fields.put("errTime", safe(this.errTime));
            fields.put("application", safe(this.application));
            fields.put("program", safe(this.program));
            fields.put("location", safe(this.location));
            fields.put("level", safe(this.level));
            fields.put("subsystem", safe(this.subsystem));
            fields.put("codeOne", safe(this.codeOne));
            fields.put("codeTwo", safe(this.codeTwo));
            fields.put("message", safe(this.message));
            return fields;
        }

        /**
         * Projects the fields for a structured logger and records the event key as a keyed token.
         *
         * <p>Assumptions: the caller supplies the tokeniser rather than this record reaching for one,
         * and that is the shape {@code docs/architecture/observability.md} sanctions for exactly this
         * problem: a keyed opaque token is "the right control ... where the caller holds the tokeniser
         * and passes it in". A method that takes an argument can be handed a collaborator where a
         * {@code toString()} cannot, which is why the tokenised form lives here and not on the rendering
         * below.</p>
         *
         * <p>Assumptions: the token is derived under a purpose of its own, so the same identifier
         * tokenised for a queue group and tokenised for a diagnostic do not produce the same value. That
         * separation is what stops a reader of the log stream from joining a diagnostic line to a queue
         * message and recovering which card a group belongs to by correlation, which would reconstruct
         * from two tokenised values the identifier neither of them discloses.</p>
         *
         * <p>Assumptions: the key is sanitized BEFORE it is tokenised. The tokeniser accepts any
         * character, so sanitizing afterwards would be sanitizing a value that is already
         * base-thirty-two text and cannot carry a line break; doing it first means the token identifies
         * the value the producer actually sent rather than a value with control characters still in
         * it, so two failures carrying the same key agree.</p>
         *
         * <p>Trade-offs: an absent or blank key yields an absent entry rather than a token of the empty
         * string. A token of nothing is a fixed value that every keyless failure would share, and a
         * reader would reasonably read repeated identical tokens as repeated failures on one item.</p>
         *
         * @param tokeniser the keyed tokeniser the event key is derived through, which the caller holds;
         *     must not be {@code null}
         * @return the sanitized fields in declaration order followed by {@code eventKeyToken}, which is
         *     absent when this record carries no key; never {@code null}
         * @throws NullPointerException if {@code tokeniser} is {@code null}, because a projection that
         *     silently omitted the token would be indistinguishable from one whose key was absent
         */
        public Map<String, String> structuredFields(OpaqueIdentifier tokeniser) {
            Objects.requireNonNull(tokeniser, "tokeniser must not be null");
            Map<String, String> fields = structuredFields();
            String sanitizedKey = safe(this.eventKey);
            if (!sanitizedKey.isBlank()) {
                fields.put("eventKeyToken",
                        tokeniser.token(EVENT_KEY_TOKEN_PURPOSE, sanitizedKey.trim()));
            }
            return fields;
        }

        /**
         * Renders the diagnostic for a log line, omitting the event key.
         *
         * <p>Assumptions: the nine bounded members are rendered and the event key is not, for the reason
         * the projection above records -- the twenty characters carry a primary account number, an
         * account identifier or a customer identifier, and part one of the rendering rule in
         * {@code docs/architecture/observability.md} omits all three as a class. The tokenised form is
         * unavailable here because a {@code toString()} takes no argument and so cannot be handed a
         * tokeniser, which that document states as the reason tokens do not reach a rendering.</p>
         *
         * <p>Assumptions: the description IS rendered, sanitized. It is a fifty-character account of
         * what failed rather than data about a cardholder, so it is the one member of this record a
         * reader is looking for; sanitizing it in the rendering as well as in the projection matters
         * because a rendering reaches a log line by exactly the same route.</p>
         *
         * @return a single-line rendering naming the observation date and time, the component
         *     coordinates, the severity, both status codes and the sanitized description, and no event
         *     key in any form; never {@code null}
         */
        @Override
        public String toString() {
            return "ErrorLogEntry[errDate=" + safe(this.errDate)
                    + ", errTime=" + safe(this.errTime)
                    + ", application=" + safe(this.application)
                    + ", program=" + safe(this.program)
                    + ", location=" + safe(this.location)
                    + ", level=" + safe(this.level)
                    + ", subsystem=" + safe(this.subsystem)
                    + ", codeOne=" + safe(this.codeOne)
                    + ", codeTwo=" + safe(this.codeTwo)
                    + ", message=" + safe(this.message) + "]";
        }

        /**
         * Renders one component as a value safe to place in a log line.
         *
         * <p>Assumptions: an absent component becomes an empty value rather than the four characters
         * that spell a null. A logger writing a literal null into a field is indistinguishable from a
         * producer having sent that word, and the two mean different things when the record is being
         * read to diagnose a failure.</p>
         *
         * @param value the component to render, which may be {@code null}
         * @return the sanitized value, or an empty string when {@code value} is {@code null}
         */
        private static String safe(String value) {
            return value == null ? "" : LogSafeText.sanitize(value);
        }
    }
}
