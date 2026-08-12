package com.carddemo.common.codec;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.OpaqueIdentifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Encodes and decodes the two comma-delimited authorization messages, the eighteen-field request and
 * the six-field reply.
 *
 * <p>Assumptions: field order and the delimiter <em>are</em> this contract, not a serialisation
 * detail of it. Both directions declare the payload as string format to the queue manager --
 * {@code MOVE MQFMT-STRING TO MQMD-FORMAT} at line 397 of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} on the inbound message and at line 751
 * on the outbound one -- and a string-format payload carries no structural metadata at all. A
 * receiver has nothing but ordinal position and the delimiter to work from, so changing either is a
 * contract change rather than a representation change. Every rule this class enforces traces to a
 * declaration in {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} or
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}, or to a statement in
 * {@code COPAUA0C.cbl}, cited by line at the point it is applied.</p>
 *
 * <p>Assumptions: the delimiter is a literal comma, proven on both directions rather than inferred.
 * The inbound parse is {@code UNSTRING W01-GET-BUFFER(1:W01-DATALEN) DELIMITED BY ','} at lines 354
 * to 355 of {@code COPAUA0C.cbl}, closed by {@code END-UNSTRING} at line 374, receiving eighteen
 * fields at lines 356 to 373 in the order the request copybook declares them. The outbound build is
 * the {@code STRING} at lines 722 to 731, which interleaves a {@code ','} literal with each value,
 * uses {@code DELIMITED BY SIZE} at line 728 and closes with {@code END-STRING} at line 731.</p>
 *
 * <h2>Declared width is not wire length</h2>
 *
 * <p>Assumptions: three quantities are involved and conflating any two of them shifts every
 * subsequent field. They are kept as separate named constants for exactly that reason:</p>
 *
 * <pre>
 * payload   fields   emitted width sum   delimiters   emitted length
 * request       18                 153   17 commas             170
 * reply          6                  57    6 commas              63
 * </pre>
 *
 * <p>Assumptions: the reply sums to 57 over its six declared widths ({@code 16+15+6+2+4+14}) at
 * lines 19 to 24 of {@code CCPAURLY.cpy}, and the request to 153 over its eighteen declared widths
 * at lines 19 to 36 of {@code CCPAURQY.cpy}. Neither sum counts a delimiter, because a copybook
 * declares fields and not the message that carries them.</p>
 *
 * <p>Assumptions: the request's ordinal-nine width is the fourteen its copybook declares.
 * {@code CCPAURQY.cpy} line 27 declares {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99}: one sign
 * position, ten integer digits, the point and two fractional digits. That declaration is the wire
 * contract, under the migration's rule that a copybook field's picture is normative. Source context,
 * recorded because it explains an observation a reader will make in the reference program and must
 * not mistake for the target contract: the {@code UNSTRING} at lines 354 to 374 of
 * {@code COPAUA0C.cbl} receives the money token into an intermediate,
 * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63, before line 376 converts it with
 * {@code FUNCTION NUMVAL}, so the reference consumer keeps only thirteen of the fourteen characters
 * it is sent. That truncation is a property of one consumer's working storage, not of the payload,
 * and it is registered as a reference-side divergence in
 * {@code docs/architecture/cobol-to-service-traceability.md}. This class emits and publishes
 * fourteen ({@link #formatRequestMoney}), and {@link #parseMoney} accepts the narrower token as well
 * so a producer that emits thirteen still decodes.</p>
 *
 * <p>Assumptions: the request's 153 and 170 are <strong>this class's own emission</strong> and are
 * NOT an observed producer contract, and the distinction matters because no request producer exists
 * anywhere in the repository to observe -- {@code tests/mocks/mq_request_stub.py} is a decision stub
 * that models the authorizer's reply, not a wire emitter. What the reference consumer actually
 * requires is weaker than a length: {@code UNSTRING ... DELIMITED BY ','} imposes no total payload
 * length at all, only that each token fit its receiver. A producer emitting trimmed fields would be
 * accepted by it just as readily. Padding every field to its declared width is nonetheless what this
 * class emits, so that the byte image it produces matches the one the reference program's own
 * {@code STRING ... DELIMITED BY SIZE} would produce for the same values; the constant is a
 * self-check on that padding decision, not a claim about a third party.</p>
 *
 * <p>Assumptions: the reply carries <strong>six</strong> commas for six fields, one of them
 * trailing, so its wire length is 63 and not 62. Two sources disagree on that figure and the
 * disagreement is recorded rather than resolved silently.
 * {@code docs/architecture/messaging-contracts.md} gives 62 as the interior-delimited nominal
 * length, the arithmetic that places one comma between each adjacent pair and therefore five commas
 * across six fields. The first-hand evidence gives 63: the {@code STRING} at lines 722 to 731 of
 * {@code COPAUA0C.cbl} pairs a {@code ','} literal with every one of the six values, including the
 * sixth at line 727, so the built buffer is 57 characters of data plus six commas. This class
 * implements 63, because the emitting statement outranks the arithmetic that predicts it. The same
 * document records the consequence directly: a decoder requiring 62 bytes with no trailing
 * delimiter would reject every well-formed reply the reference program produces.</p>
 *
 * <p>Assumptions: the request needs no such reconciliation. Its interior-delimited length and the
 * length this class emits are the same 170, because the {@code UNSTRING} at line 354 names eighteen
 * receiving fields and therefore consumes seventeen interior delimiters and no trailing one.</p>
 *
 * <h2>Buffer length, payload length and cursor position are three different values</h2>
 *
 * <p>Assumptions: the transport buffer is declared {@code 01 W01-GET-BUFFER PIC X(500).} at line 103
 * of {@code COPAUA0C.cbl}, comfortably larger than either payload, and the reference program passes
 * an explicit data length alongside it -- {@code W01-DATALEN}, distinct from the {@code W01-BUFFLEN}
 * it sets to the buffer's own length at line 398. This class must therefore never treat a buffer's
 * length as a payload length, which is why the byte-array entry points take the payload length as a
 * separate argument.</p>
 *
 * <p>Assumptions: the reference program also shows what happens when a cursor and a length are held
 * in one value. {@code WS-RESP-LENGTH} is declared {@code PIC S9(4) VALUE 1} at line 46, is used as
 * the {@code WITH POINTER} cursor of the outbound {@code STRING} at line 730 -- so after the
 * transfer it addresses the position one past the last character written -- and is then moved
 * straight into the put's buffer length at line 756 and passed to the call at line 762. The baseline
 * therefore sends 64 bytes for the 63 it builds, the sixty-fourth being a space from the 200-byte
 * put buffer declared at line 108. This class implements 63 and the divergence is registered as
 * {@code D-REPLY-PUT-LENGTH} in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Inside
 * this class a payload length and a scan position are always separate, differently named values,
 * because holding them in one is precisely what produced the extra byte.</p>
 *
 * <h2>Strict on encode, tolerant on decode</h2>
 *
 * <p>Trade-offs: symmetric strictness was evaluated and rejected. It reads as the more principled
 * choice and it would leave this codec unable to read the reference program's own traffic, for three
 * separate reasons that are each measurable in the source. The reply's trailing comma means a
 * decoder must accept a final empty token rather than count it as a seventh field. The
 * pointer-derived length means a decoder must accept one pad byte beyond the expected length. And
 * the outbound money rendering is {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} at line 66, whose
 * zero suppression emits leading spaces and no {@code +} for a positive value, so a decoder must
 * trim the token rather than match it against a zero-padded mask. Encoding, by contrast, has one
 * correct answer per direction and no reason to hedge: the widths the copybooks declare, the mask
 * the reference program emits for a reply, and the width its receiver holds for a request.
 * What is accepted is that this codec is not byte-idempotent over every input a producer might
 * send -- a tolerated variant re-encodes to the declared form rather than to itself -- and what is
 * bought is interoperability in both directions.</p>
 *
 * <h2>What this class does not own</h2>
 *
 * <p>Assumptions: transport belongs to the queue-owning service, not here. The FIFO message-group
 * identifier taken from the card number, the deduplication identifier taken from the transaction
 * identifier, the reply-queue routing, the dead-letter configuration and the {@code expiresAt}
 * attribute that stands in for the reference program's message expiry -- {@code MOVE 50 TO
 * MQMD-EXPIRY} at line 750, five seconds in tenths -- are all queue concerns. This class produces
 * and consumes a payload and holds no client, no queue name and no attribute map, so none of them
 * can be configured through it.</p>
 *
 * <p>Assumptions: the consumer's batch discipline is likewise not here. The reference program's
 * processing limit of {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at line 40 becomes a
 * bounded long-poll loop, and its get-with-wait of {@code MOVE 5000 TO WS-WAIT-INTERVAL} at line
 * 242, five seconds in milliseconds, becomes an equivalent receive wait. Both live in the consumer
 * that drives this codec.</p>
 *
 * <p>Assumptions: a JSON envelope over these two payloads is additive for new consumers and never a
 * replacement, so this class imports no JSON library and offers no JSON view. With a string-format
 * payload there is no schema anywhere else, so replacing the encoding would be a contract change
 * against an external producer that the baseline does not supply and that cannot be renegotiated
 * with. A JSON projection, if one is ever wanted, is built by a service-layer mapper from the record
 * carriers below, where the additive framing is visible.</p>
 *
 * <p>Assumptions: the error-log record is outside this class. {@code CCPAUERY.cpy} declares
 * {@code ERROR-LOG-RECORD} at line 19 with eleven fields summing to 122 bytes, and it carries no
 * delimiter at all -- it is a byte-positional record, so its owners are the sibling
 * {@code CopybookLayout} descriptor and {@code FixedWidthCodec}, which is the regime built for
 * offsets. Its declaration also carries a hazard worth recording where a reader will meet it: the
 * {@code 01} sits ten spaces in, beyond Area A, so a census pattern anchored as
 * {@code ^ {0,7}01 } does not match it. That is not a one-off; line 60 of
 * {@code app/cbl/CSUTLDTC.cbl} indents an {@code 01} the same way.</p>
 *
 * <h2>What a failure message may say about a rejected value</h2>
 *
 * <p>Assumptions: three of the twenty-four fields these two payloads declare carry cardholder data --
 * {@code PA-RQ-CARD-NUM} at line 21 of {@code CCPAURQY.cpy}, {@code PA-RQ-CARD-EXPIRY-DATE} at line
 * 23 of the same file, and {@code PA-RL-CARD-NUM} at line 19 of {@code CCPAURLY.cpy}. Every failure
 * message this class composes about one of those three names the field, the width its copybook
 * declares and the width observed, and NEVER any part of the value. The remaining twenty-one fields
 * -- the merchant descriptors, the message and processing codes, the identifiers and the timestamps
 * -- keep their value quoted, because for those the value IS the diagnosis: the delimiter check below
 * exists chiefly for a comma inside a merchant name, and a message that withheld the name would leave
 * an operator no way to find the record that produced it.</p>
 *
 * <p>Alternatives Considered: rendering a withheld card number as its last four digits, which the
 * resolution guidance permits. It was declined because the sibling {@code CopybookLayout} in this
 * package states the boundary this class must not cross -- masking a primary account number to its
 * last four digits belongs to the anti-corruption mapper layer at
 * {@code services/*}{@code /mapper/*Mapper.java} and not to a codec -- and because a value that
 * reaches these messages has already failed its width or delimiter check, so its final four
 * characters are not reliably the final four digits of a card number and could mislead a reader into
 * matching the wrong record. Withholding also keeps one rule for a contributor to apply to a new
 * field rather than a judgement to make about it.</p>
 *
 * <h2>Money on this wire, and the five renderings that are not this class's</h2>
 *
 * <p>Assumptions: money crosses this wire as an edited display form, and this class owns
 * <strong>two</strong> renderings of it rather than one, because the baseline uses two. Outbound
 * replies use {@link #formatReplyMoney}, the fourteen-character zero-suppressed mask
 * {@code PIC -zzzzzzzzz9.99} that {@code COPAUA0C.cbl} declares at line 66 and emits at line 720.
 * Outbound requests use {@link #formatRequestMoney}, the fourteen characters
 * {@code PIC +9(10).99} declares at line 27 of {@code CCPAURQY.cpy}, which is a fixed sign position
 * and zero-padded digits rather than a zero-suppressed mask. The two renderings agree on their width
 * and on nothing else, so they are separate methods and using the wrong one is a call-site choice a
 * reader can see rather than a shared default. Five further renderings exist in the migration and
 * each belongs elsewhere: zoned overpunch of eleven or twelve bytes is
 * {@code ZonedDecimalCodec}; packed
 * {@code COMP-3}, seven bytes for {@code PIC S9(10)V99}, and binary {@code COMP} are
 * {@code PackedDecimalCodec}; the twelve-character sort edit mask, and the fifteen-character report
 * masks at line 30 and lines 54, 60 and 66 of {@code app/cpy/CVTRA07Y.cpy}, are the reporting
 * concern. Naming the boundary here is what keeps a sixth rendering from being added to this
 * class.</p>
 *
 * <h2>Why this class carries its own layout knowledge</h2>
 *
 * <p>Alternatives Considered: routing these two payloads through {@code FixedWidthCodec} was
 * evaluated and rejected. Assumptions: that codec addresses a record by byte offset, and these
 * payloads have no byte offsets to address. Their fields have declared widths, but the widths are
 * not where the fields sit: seventeen delimiters are interleaved between the request's eighteen
 * values, and the reference program's own intake proves the widths are not load-bearing on receive,
 * since it reads the ordinal-nine money token into a narrower intermediate at line 63 before
 * converting it. Reusing a byte-positional codec would mean inventing offsets the wire does not have,
 * and an invented offset is exactly the silent-shift failure this contract cannot absorb. The two
 * regimes are kept apart so that choosing the wrong one is a compilation-time mistake rather than a
 * run-time misread.</p>
 *
 * <p>Assumptions: the copybook declarations were read with whitespace-run tokenising rather than
 * single-space splitting, because {@code CCPAURQY.cpy} writes {@code PIC  X(06)} with two spaces
 * after {@code PIC} on every alphanumeric field while writing {@code PIC +9(10).99} with one on the
 * money field at line 27. A single-space split silently produces an empty token for the two-space
 * form, which would have dropped seventeen of the eighteen widths recorded below.</p>
 *
 * <p>Trade-offs: the two carriers and the failure type are nested inside this class rather than
 * standing as their own files. Separate top-level types would be conventional, and were declined
 * because these three declarations have no meaning apart from this wire format: the carriers exist
 * to hold exactly the fields the two copybooks declare, in exactly that order, and the failure type
 * reports a violation of that same format. Nesting them keeps the format and its vocabulary in one
 * place a reader can hold at once. The cost accepted is a long file.</p>
 */
public final class CsvAuthCodec {

    /**
     * The field separator, proven at lines 354 to 355 and 722 to 731 of {@code COPAUA0C.cbl}.
     */
    public static final char DELIMITER = ',';

    /**
     * The number of fields the request declares, at lines 19 to 36 of {@code CCPAURQY.cpy}.
     */
    public static final int REQUEST_FIELD_COUNT = 18;

    /**
     * The stand-in a rendering prints where a sensitive component is withheld rather than masked.
     *
     * <p>Assumptions: a marker is printed rather than the member being omitted, because an omitted
     * member reads as an absent value while a marker reads as a deliberate withholding, and the two
     * lead a reader to opposite conclusions about the message they are looking at.</p>
     */
    private static final String WITHHELD_MARKER = "<withheld>";

    /**
     * The note a request rendering prints in place of its fifteen withheld components.
     *
     * <p>Assumptions: the note names the CATEGORIES withheld rather than listing fifteen members
     * against the marker, because the list would be longer than the rendering it belongs to and every
     * entry would carry the same value. Naming the categories tells a reader what is missing and why
     * in one line.</p>
     */
    private static final String WITHHELD_COMPONENTS_NOTE =
            "amount, merchant, acquirer and message detail " + WITHHELD_MARKER;

    /**
     * The sum of the eighteen field widths this class emits for a request, counting no delimiter.
     *
     * <p>Assumptions: 153, the sum of the widths {@code CCPAURQY.cpy} declares at lines 19 to 36,
     * because every field including the ordinal-nine money field is emitted at its declared width.</p>
     */
    public static final int REQUEST_DECLARED_WIDTH_SUM = 153;

    /**
     * The request length this class emits, the emitted width sum plus seventeen interior delimiters.
     *
     * <p>Assumptions: this is an emission self-check as well as the length a decoded payload must
     * reach. The reference consumer's {@code UNSTRING ... DELIMITED BY ','} imposes no total length of
     * its own -- only that each token fit its receiver -- and no request producer exists in the
     * repository to observe. Because every field is emitted at its declared width, requiring each
     * token to reach that width is equivalent to requiring the payload to reach this length, and
     * {@link #requireDeclaredTokenWidths(java.util.List, java.util.List, java.util.List, int, String)}
     * is where that equivalence is enforced.</p>
     *
     * <p>Assumptions: 170 is the copybook-declaration arithmetic -- the 153 bytes
     * {@code CCPAURQY.cpy} declares at lines 19 to 36 plus seventeen commas -- and it is the figure
     * this contract publishes, because a copybook field's picture is normative for the wire.</p>
     */
    public static final int REQUEST_WIRE_LENGTH = 170;

    /**
     * The number of fields the reply declares, at lines 19 to 24 of {@code CCPAURLY.cpy}.
     */
    public static final int REPLY_FIELD_COUNT = 6;

    /**
     * The sum of the reply's six declared field widths, counting no delimiter.
     */
    public static final int REPLY_DECLARED_WIDTH_SUM = 57;

    /**
     * The reply length on the wire, the declared width sum plus six delimiters including a trailing
     * one.
     */
    public static final int REPLY_WIRE_LENGTH = 63;

    /**
     * The width of the positional card-number and transaction-identifier pair a correlation token is
     * derived FROM, before that pair is tokenised.
     *
     * <p>Refactoring Rationale: this constant was named for the correlation key itself and it never
     * described one. It is {@link #CARD_NUM_WIDTH} plus {@link #TRANSACTION_ID_WIDTH}, which is the
     * plaintext composite {@code buildCorrelationKey} assembles; the value
     * {@link AuthRequest#correlationKey(OpaqueIdentifier)} publishes is that composite AFTER
     * {@link OpaqueIdentifier#token(String, String)} has tokenised it, and is
     * {@link #CORRELATION_TOKEN_LENGTH} characters. A consumer sizing a column or a buffer from the
     * former name was therefore wrong by nine characters in the direction that truncates. The two
     * quantities are now named apart so neither can be read as the other.</p>
     *
     * <p>Assumptions: the rename does NOT remove the old name. Changing that name's VALUE to 22
     * would have silently halved a buffer a consumer had already sized -- a change no compiler could
     * report -- so the value stays where it is and only the name it is published under changes.
     * {@link #CORRELATION_KEY_LENGTH} is retained beside it as a deprecated alias so that a consumer
     * compiled against the earlier common-lib keeps compiling and is told, by a deprecation warning
     * naming this constant, which of the two quantities it should have meant.</p>
     */
    public static final int CORRELATION_COMPOSITE_LENGTH = 31;

    /**
     * Deprecated alias of {@link #CORRELATION_COMPOSITE_LENGTH}, retained for source compatibility.
     *
     * <p>Alternatives Considered: deleting this name outright, so that a hard compile failure makes
     * every consumer re-decide which quantity it meant. Rejected on the mechanism rather than the goal:
     * common-lib publishes no major-version boundary at which a source-incompatible removal is
     * announced, and every one of the eight service modules resolves it as {@code 1.0.0-SNAPSHOT}, so a
     * deletion breaks compilation with no declared break to point at. A deprecated alias forces the same
     * re-decision -- the compiler emits a warning naming the replacement at every use site -- and
     * removal belongs in a declared major-version break, which is what will take this alias.</p>
     *
     * <p>Assumptions: the alias carries the SAME value as its replacement and is initialised from it
     * rather than restating 31, so the two cannot drift apart. That is the whole safety property of
     * an alias: a reader who finds either name finds one number.</p>
     *
     * <p>Trade-offs: the name is still the misleading one -- it says "correlation key" and measures
     * the plaintext composite the key is derived from, which is nine characters wider than the token
     * {@link #CORRELATION_TOKEN_LENGTH} publishes. Keeping a misleading name in the public surface is
     * a real cost, and it is accepted only because the deprecation is what tells a reader the name is
     * wrong. What is bought is that the correction is delivered as a warning a consumer can act on
     * rather than as a build failure a consumer has to diagnose.</p>
     *
     * @deprecated use {@link #CORRELATION_COMPOSITE_LENGTH}, whose name states that the figure is the
     *     width of the plaintext card-number and transaction-identifier composite. For the width of
     *     the tokenised value the two {@code correlationKey} accessors return, use
     *     {@link #CORRELATION_TOKEN_LENGTH} instead; the two quantities differ, and reading this
     *     constant as the token width oversizes a buffer by nine characters.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static final int CORRELATION_KEY_LENGTH = CORRELATION_COMPOSITE_LENGTH;

    /**
     * The length of the correlation token that matches a reply to its request.
     *
     * <p>Assumptions: this is the width of the value the two {@code correlationKey} accessors return,
     * and it is the tokeniser's own output width rather than an independent figure, so it is taken
     * from {@link OpaqueIdentifier#TOKEN_LENGTH} rather than restated as a literal. Restating it
     * would create a second place for the token width to live and a way for the two to disagree
     * after a change to the tokeniser.</p>
     */
    public static final int CORRELATION_TOKEN_LENGTH = OpaqueIdentifier.TOKEN_LENGTH;

    /**
     * The greatest number of characters a payload may carry before it is parsed at all.
     *
     * <p>Assumptions: the two declared wire lengths are {@code REQUEST_WIRE_LENGTH} and
     * {@code REPLY_WIRE_LENGTH}, 170 and 63 characters, and the reference program's own put buffer is
     * 200 bytes, declared at line 108 of {@code COPAUA0C.cbl}. The bound is set at 512, which is more
     * than twice the largest of those, so no payload the contract admits is affected and no legitimate
     * transport framing is refused.</p>
     *
     * <p>Trade-offs: the bound exists because splitting is proportional to the payload, while a payload
     * arrives from a queue and is therefore attacker-influenced. A payload of nothing but delimiters
     * would otherwise be counted, sized and split into one substring per delimiter before the
     * field-count check could reject it -- so a single message could provoke work and allocation bounded
     * only by the transport's own maximum message size, which for a queue is measured in hundreds of
     * kilobytes. Rejecting on length first makes the cost of a malformed message constant. The accepted
     * cost is that a future contract with a materially longer payload has to move this constant, which
     * is the intended kind of change: deliberate and reviewable.</p>
     */
    public static final int MAX_PAYLOAD_LENGTH = 512;

    /**
     * The purpose string every correlation token is scoped by.
     *
     * <p>Assumptions: a token is scoped so that two derivations over one authorization -- the correlation
     * identity of a card-and-transaction pair and the per-card derivation of {@link #GROUP_PURPOSE} --
     * produce unrelated values, which is what stops a holder of one from joining it to the other. The
     * string is a constant here rather than a caller's argument so that a request and its reply cannot be
     * scoped differently and then fail to pair.</p>
     */
    public static final String CORRELATION_PURPOSE = "carddemo/pauth/correlation";

    /**
     * The purpose string a per-card keyed derivation is scoped by.
     *
     * <p>Assumptions: this purpose does NOT scope the queue's {@code MessageGroupId}. Specification
     * &sect;0.4.1.8 fixes that identity as the card number itself, and a keyed derivation cannot serve
     * it: a group identity orders one card's messages only while EVERY producer on the queue computes
     * the same value for that card, and a value keyed from one consumer's secret is one only that
     * consumer can compute. What this purpose scopes is a stable, non-reversible per-card value for uses
     * that are a single service's own to choose, such as a metric dimension or a diagnostic key; no
     * publisher in this repository derives a queue identity through it.</p>
     */
    public static final String GROUP_PURPOSE = "carddemo/pauth/order-group";

    /**
     * The purpose string a per-authorization keyed derivation is scoped by.
     *
     * <p>Assumptions: this purpose does NOT scope the queue's {@code MessageDeduplicationId}.
     * Specification &sect;0.4.1.8 fixes that identity as the transaction identifier itself, and a keyed
     * derivation cannot serve it: suppression compares an identity the REQUESTER may resend, so it has
     * to be a value the requester can predict. What this purpose scopes is a stable value over the
     * card-and-transaction pair, separate from {@link #CORRELATION_PURPOSE} so that two derivations of
     * one pair cannot be joined to each other; no publisher in this repository derives a queue identity
     * through it.</p>
     */
    public static final String DEDUPLICATION_PURPOSE = "carddemo/pauth/deduplication";

    /**
     * The width of the reply's edited display money rendering {@code PIC -zzzzzzzzz9.99}.
     *
     * <p>Assumptions: fourteen positions -- one sign, nine zero-suppressed digit positions, one
     * forced digit position, the literal point and two fractional digits -- as declared at line 66
     * of {@code COPAUA0C.cbl} for {@code WS-APPROVED-AMT-DIS}. It coincides with the fourteen
     * {@code CCPAURLY.cpy} line 24 declares for {@code PA-RL-APPROVED-AMT PIC +9(10).99}. The
     * coincidence is a hazard worth naming: the two pictures differ in what they put in each position,
     * not in how many there are, so a width check alone cannot tell a correct rendering from a wrong
     * one.</p>
     */
    public static final int MONEY_EDITED_WIDTH = 14;

    /**
     * The width of the request's money token, fourteen.
     *
     * <p>Assumptions: {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at line 27 of
     * {@code CCPAURQY.cpy} spends its fourteen characters on one sign position, ten integer digits,
     * the point and two fractional digits. The reference consumer copies the token into a
     * thirteen-character intermediate before converting it, which is source context recorded on this
     * class rather than a narrower contract.</p>
     */
    public static final int REQUEST_MONEY_WIDTH = 14;

    /**
     * The zero-based ordinal of the request's money field, at line 27 of {@code CCPAURQY.cpy}.
     *
     * <p>Assumptions: exactly one of the eighteen request fields is money, and that one is exempt
     * from the declared-width equality
     * {@link #requireDeclaredTokenWidths(java.util.List, java.util.List, java.util.List, int, String)}
     * imposes on the other seventeen. The ordinal is named here rather than written as a literal at
     * the two sites that need it, because a field inserted ahead of it would otherwise leave one
     * site exempting the wrong field while everything still compiled.</p>
     */
    public static final int REQUEST_AMOUNT_ORDINAL = 8;

    /**
     * The zero-based ordinal of the reply's money field, at line 24 of {@code CCPAURLY.cpy}.
     *
     * <p>Assumptions: this is the reply's LAST field, which is why the truncation defect the width
     * equality now closes was invisible on a reply and visible on a request. A short trailing token
     * on a reply is a short money token, and the picture clause already refuses one whose digit
     * positions do not close; the request's last field is
     * {@code PA-RQ-TRANSACTION-ID PIC X(15)}, which has no picture to violate.</p>
     */
    public static final int REPLY_AMOUNT_ORDINAL = 5;

    /**
     * The number of integer digit positions in the reply's edited display money rendering.
     */
    public static final int MONEY_INTEGER_DIGITS = 10;

    /**
     * The number of decimal digit positions in the edited display money rendering.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The declared width of the card number, at line 21 of {@code CCPAURQY.cpy} and line 19 of
     * {@code CCPAURLY.cpy}.
     */
    public static final int CARD_NUM_WIDTH = 16;

    /**
     * The declared width of the transaction identifier, at line 36 of {@code CCPAURQY.cpy} and line
     * 20 of {@code CCPAURLY.cpy}.
     */
    public static final int TRANSACTION_ID_WIDTH = 15;

    /**
     * The eighteen request field widths this class emits, in the order the wire carries them.
     *
     * <p>Assumptions: seventeen of the eighteen are the copybook's own declarations at lines 19 to 36
     * of {@code CCPAURQY.cpy}; the ordinal-nine entry is {@link #REQUEST_MONEY_WIDTH} rather than the
     * copybook's fourteen, for the receiver reason recorded on that constant. It is referenced here
     * rather than written as a literal so the two cannot drift.</p>
     */
    public static final List<Integer> REQUEST_FIELD_WIDTHS =
            List.of(6, 6, 16, 4, 4, 6, 6, 6, REQUEST_MONEY_WIDTH, 4, 3, 2, 15, 22, 13, 2, 9, 15);

    /**
     * The eighteen request field names as the copybook declares them, in wire order.
     */
    public static final List<String> REQUEST_FIELD_NAMES = List.of(
            "PA-RQ-AUTH-DATE",
            "PA-RQ-AUTH-TIME",
            "PA-RQ-CARD-NUM",
            "PA-RQ-AUTH-TYPE",
            "PA-RQ-CARD-EXPIRY-DATE",
            "PA-RQ-MESSAGE-TYPE",
            "PA-RQ-MESSAGE-SOURCE",
            "PA-RQ-PROCESSING-CODE",
            "PA-RQ-TRANSACTION-AMT",
            "PA-RQ-MERCHANT-CATAGORY-CODE",
            "PA-RQ-ACQR-COUNTRY-CODE",
            "PA-RQ-POS-ENTRY-MODE",
            "PA-RQ-MERCHANT-ID",
            "PA-RQ-MERCHANT-NAME",
            "PA-RQ-MERCHANT-CITY",
            "PA-RQ-MERCHANT-STATE",
            "PA-RQ-MERCHANT-ZIP",
            "PA-RQ-TRANSACTION-ID");

    /**
     * The six reply field widths, in the order the wire carries them.
     */
    public static final List<Integer> REPLY_FIELD_WIDTHS = List.of(16, 15, 6, 2, 4, 14);

    /**
     * The six reply field names as the copybook declares them, in wire order.
     */
    public static final List<String> REPLY_FIELD_NAMES = List.of(
            "PA-RL-CARD-NUM",
            "PA-RL-TRANSACTION-ID",
            "PA-RL-AUTH-ID-CODE",
            "PA-RL-AUTH-RESP-CODE",
            "PA-RL-AUTH-RESP-REASON",
            "PA-RL-APPROVED-AMT");

    /**
     * The copybook names of the fields whose CONTENT must never appear in a diagnostic message.
     *
     * <p>Refactoring Rationale: this set exists because every failure message in this class used to
     * quote the value that failed. Eight sites did so, and the values they quoted are the most
     * sensitive on the wire: the sixteen-digit primary account number at line 29 of
     * {@code CCPAURQY.cpy}, the transaction amount at line 27, the merchant identity, name, city and
     * postal code at lines 31, 32, 33 and 35, and the reply's own card number and approved amount at
     * lines 20 and 25 of {@code CCPAURLY.cpy}. A malformed message is exactly the case that produces a
     * log line, so quoting the value put a primary account number into log storage on precisely the
     * requests that were already going wrong -- and log storage is the one destination the masking
     * applied at the API edge does not reach. The two fixed-width codecs in this package already
     * carried per-field sensitivity and withheld content accordingly; this set brings the delimited
     * codec into line with them rather than inventing a new discipline for it.</p>
     *
     * <p>Assumptions: the card expiry date is in this set alongside the card number, because the two
     * together are the pair a card-not-present authorization is built from, so withholding one while
     * quoting the other would leave the log line carrying half of a usable credential.</p>
     *
     * <p>Assumptions: the authorization date, time, type, message type and source, processing
     * code, category code, country code, entry mode, response code, response reason and authorization
     * identity code are NOT in this set, and their absence is deliberate. Each is a code from a small
     * closed domain or a date, so quoting one names a category rather than a person, and a diagnostic
     * that can say which code was rejected is substantially more useful than one that cannot. The line
     * this set draws is cardholder- or amount-identifying content on one side and closed-domain codes
     * on the other.</p>
     *
     * <p>Trade-offs: the transaction identity IS in the set even though it identifies a transaction
     * rather than a person, because it is the deduplication key of the ordered request queue and
     * therefore appears in operational tooling alongside the card number it was grouped by -- so
     * quoting it in a log line while withholding the card number would still narrow a search to one
     * cardholder. What is given up is naming the offending transaction in the message; the message
     * still names the FIELD and the constraint, and the payload is held by the producer.</p>
     */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "PA-RQ-CARD-NUM",
            "PA-RQ-CARD-EXPIRY-DATE",
            "PA-RQ-TRANSACTION-AMT",
            "PA-RQ-MERCHANT-ID",
            "PA-RQ-MERCHANT-NAME",
            "PA-RQ-MERCHANT-CITY",
            "PA-RQ-MERCHANT-ZIP",
            "PA-RQ-TRANSACTION-ID",
            "PA-RL-CARD-NUM",
            "PA-RL-TRANSACTION-ID",
            "PA-RL-APPROVED-AMT");

    /**
     * The sign character the edited display rendering emits for a non-negative amount.
     */
    private static final char MONEY_POSITIVE_SIGN = '+';

    /**
     * The sign character both renderings emit for a negative amount.
     */
    private static final char MONEY_NEGATIVE_SIGN = '-';

    /**
     * The character the reply mask's leading {@code -} position emits for a non-negative amount.
     *
     * <p>Assumptions: a fixed sign-control position in a COBOL edited picture emits a space, not a
     * plus, when the value is not negative -- one byte that a reading of the picture as "+ or -" gets
     * wrong at the right width.</p>
     */
    private static final char MONEY_SIGN_BLANK = ' ';

    /**
     * The character a suppressed {@code z} position emits.
     *
     * <p>Assumptions: {@code z} is zero SUPPRESSION with blank replacement, so a leading zero
     * becomes a space rather than a zero. The single {@code 9} immediately left of the point in
     * {@code PIC -zzzzzzzzz9.99} is what stops the suppression consuming the units digit, so an
     * amount of zero renders as {@code 0.00} and never as a blank field.</p>
     */
    private static final char MONEY_SUPPRESSED_DIGIT = ' ';

    /**
     * The literal decimal point of the edited display rendering, which occupies a real byte.
     */
    private static final char MONEY_DECIMAL_POINT = '.';

    /**
     * The pad character {@code DELIMITED BY SIZE} contributes for an unused declared position.
     */
    private static final char PAD = ' ';

    /**
     * The highest code point the single-byte wire encoding can represent.
     *
     * <p>Assumptions: ISO-8859-1 maps U+0000 through U+00FF onto the byte values 0x00 through 0xFF
     * one for one, so this bound is the whole of its representable set rather than a chosen limit. It
     * is declared as a constant because the same bound is both the test in
     * {@link #indexOfUnrepresentableCharacter(String)} and the value quoted in the refusal, and a
     * refusal quoting a different number from the one it applied would be worse than no number.</p>
     */
    private static final char WIRE_CHARACTER_MAXIMUM = '\u00ff';

    /**
     * The representable character range, spelled for a refusal message.
     *
     * <p>Assumptions: the range is written out rather than derived from
     * {@link #WIRE_CHARACTER_MAXIMUM} at run time, because rendering that character into a message
     * would place a non-ASCII byte in a diagnostic that a log or a terminal then has to encode -- the
     * very class of problem this contract's single-byte discipline exists to avoid.</p>
     */
    private static final String WIRE_CHARACTER_RANGE_DESCRIPTION = "U+0000 to U+00FF range";

    /**
     * The zero digit, used to left-pad the integer part of the edited display rendering.
     */
    private static final char ZERO_DIGIT = '0';

    /**
     * Prevents instantiation of this static codec holder.
     *
     * <p>Alternatives Considered: an instantiable codec, or one exposing a shared instance, was
     * evaluated and rejected. This class holds no configuration -- the widths, the names, the
     * delimiter and the money rendering are all determined by two copybooks and cannot vary per
     * caller -- so an instance would advertise a lifecycle and an injection point that have nothing
     * behind them. A private constructor on a final class states that in the one place the language
     * enforces it.</p>
     */
    private CsvAuthCodec() {
        // WHY : Alternatives Considered: throwing from this body was evaluated and judged noise. The
        //       private modifier already makes the only call site that could reach it impossible to
        //       write, so an exception here would guard a state the compiler has already excluded.
    }


    /**
     * The stable token an alert rule or a log query matches an authorization-wire refusal on.
     *
     * <p>Assumptions: one code for every format violation of this wire, because a consumer's response to
     * all of them is the same -- the message goes to the dead-letter queue -- and a code that
     * distinguished them would be matched on by nobody.</p>
     */
    private static final String REFUSAL_CODE = "AUTH_WIRE_MALFORMED";

    /**
     * Reports that a payload or a field value violates the declared authorization message format.
     *
     * <p>Assumptions: a format violation on this wire is a caller-side defect rather than a
     * recoverable condition, which is why this extends {@link ClientInputException} -- itself an
     * unchecked {@code IllegalArgumentException} -- rather than a checked type. Assumptions: the
     * supertype is the narrow {@code ClientInputException} rather than the bare
     * {@code IllegalArgumentException}, because the shared advice must not claim the whole family: that
     * family also carries every internal invariant in the migration, so claiming it would report a
     * service defect to a caller as a request to correct. This type satisfies the narrower supertype's
     * redaction obligation through the per-field sensitivity gate every message in this codec is
     * composed by. Nothing downstream can retry its way out of a payload carrying the wrong field
     * count or a money token with no decimal point; the message has to be rejected and reported, and
     * the consumer's dead-letter queue is where a rejected message goes.</p>
     *
     * <p>Trade-offs: a named subtype rather than a bare {@code IllegalArgumentException} costs one
     * more declaration and buys a consumer the ability to separate a malformed payload, which
     * belongs on the dead-letter queue, from a programming error raised by the same call. Declaring
     * it nested rather than as its own file follows the reasoning recorded on the enclosing
     * class.</p>
     */
    public static final class AuthMessageFormatException extends ClientInputException {

        /**
         * The serialisation identity of this exception type.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Creates a format failure carrying a description of what was rejected.
         *
         * <p>Alternatives Considered: restricting this constructor to this package was evaluated and
         * rejected. It would have made the exception evidence that this codec in particular did the
         * rejecting, and the cost outweighed that: a consumer typically validates a transport
         * attribute before handing the body over, and with a package-private constructor it would
         * have to raise a second, unrelated type for that half of the same check. One type for one
         * failure mode leaves a consumer with one exception to route to its dead-letter queue.</p>
         *
         * @param message the description of the violation, which names the field or the payload
         *     concerned and the value that was rejected, so that a dead-lettered message can be
         *     diagnosed from the log line alone
         */
        public AuthMessageFormatException(String message) {
            // WHY : Assumptions: the stable code is fixed and no field key is supplied. Every refusal of
            //       this type concerns one payload rather than one named member -- a wrong field count, a
            //       wrong width, a money token with no decimal point -- so there is no member for a form
            //       to draw a marker against, and the shared advice keys the entry by the request as a
            //       whole when none is named.
            super(REFUSAL_CODE, message);
        }
    }


    /**
     * Carries the eighteen fields of an authorization request in the order the wire declares them.
     *
     * <p>Assumptions: every component is stored with its trailing pad removed, so a component holds
     * the value and not the padding that surrounded it on the wire. That follows the copybook rule
     * that a trailing blank in an alphanumeric field is padding rather than data, and it is what
     * makes a decode of an encode return an equal record. The padding is restored on encode from the
     * declared widths recorded in {@code REQUEST_FIELD_WIDTHS}.</p>
     *
     * <p>Assumptions: the ordinal-ten component records a misspelling that the baseline carries in
     * two forms, and both are recorded here so the lineage is never ambiguous. Line 28 of
     * {@code CCPAURQY.cpy} declares it {@code PA-RQ-MERCHANT-CATAGORY-CODE} with the request infix,
     * and line 36 of {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} declares the bare
     * form {@code PA-MERCHANT-CATAGORY-CODE}. Under AAP Rule T1 (Copybook is normative) no field is
     * renamed except three documented misspelling corrections, and this is the third of them: the
     * target column is {@code merchant_category_code} and the component is
     * {@code merchantCategoryCode}. This is a target-name correction with the baseline spelling
     * preserved verbatim in {@code REQUEST_FIELD_NAMES}, which is what every failure message and
     * every wire-order assertion reads from.</p>
     *
     * <p>Assumptions: the two fields declared {@code PIC 9(nn)} -- the processing code at line 26 and
     * the entry mode at line 30 -- are carried as text and not as numbers. They are identifiers
     * rather than quantities: nothing adds them, a leading zero is significant to the acquirer that
     * assigned them, and an integer component would silently discard that zero on the way back out.
     * The single component the wire genuinely counts as money is the amount, and it is the only one
     * typed {@link Money}.</p>
     *
     * @param authDate the authorization date as {@code PA-RQ-AUTH-DATE} at line 19 of
     *     {@code CCPAURQY.cpy}, at most 6 characters
     * @param authTime the authorization time as {@code PA-RQ-AUTH-TIME} at line 20, at most 6
     *     characters
     * @param cardNum the primary account number as {@code PA-RQ-CARD-NUM} at line 21, at most 16
     *     characters, and the first half of the correlation key
     * @param authType the authorization type as {@code PA-RQ-AUTH-TYPE} at line 22, at most 4
     *     characters
     * @param cardExpiryDate the card expiry as {@code PA-RQ-CARD-EXPIRY-DATE} at line 23, at most 4
     *     characters
     * @param messageType the message type as {@code PA-RQ-MESSAGE-TYPE} at line 24, at most 6
     *     characters
     * @param messageSource the originating source as {@code PA-RQ-MESSAGE-SOURCE} at line 25, at
     *     most 6 characters
     * @param processingCode the processing code as {@code PA-RQ-PROCESSING-CODE} at line 26, at most
     *     6 characters, carried as text because its leading zeros are significant
     * @param transactionAmount the requested amount as {@code PA-RQ-TRANSACTION-AMT} at line 27,
     *     exact at two decimal places and rendered on the wire as {@code REQUEST_MONEY_WIDTH}
     *     characters, the width of the reference program's receiver rather than of the copybook
     *     field
     * @param merchantCategoryCode the merchant category as {@code PA-RQ-MERCHANT-CATAGORY-CODE} at
     *     line 28, at most 4 characters; see the misspelling note above for the target name
     * @param acquirerCountryCode the acquirer country as {@code PA-RQ-ACQR-COUNTRY-CODE} at line 29,
     *     at most 3 characters
     * @param posEntryMode the point-of-sale entry mode as {@code PA-RQ-POS-ENTRY-MODE} at line 30,
     *     at most 2 characters, carried as text for the same reason as the processing code
     * @param merchantId the merchant identifier as {@code PA-RQ-MERCHANT-ID} at line 31, at most 15
     *     characters
     * @param merchantName the merchant name as {@code PA-RQ-MERCHANT-NAME} at line 32, at most 22
     *     characters
     * @param merchantCity the merchant city as {@code PA-RQ-MERCHANT-CITY} at line 33, at most 13
     *     characters
     * @param merchantState the merchant state as {@code PA-RQ-MERCHANT-STATE} at line 34, at most 2
     *     characters
     * @param merchantZip the merchant postal code as {@code PA-RQ-MERCHANT-ZIP} at line 35, at most
     *     9 characters
     * @param transactionId the transaction identifier as {@code PA-RQ-TRANSACTION-ID} at line 36, at
     *     most 15 characters, and the second half of the correlation key
     */
    public record AuthRequest(
            String authDate,
            String authTime,
            String cardNum,
            String authType,
            String cardExpiryDate,
            String messageType,
            String messageSource,
            String processingCode,
            Money transactionAmount,
            String merchantCategoryCode,
            String acquirerCountryCode,
            String posEntryMode,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantState,
            String merchantZip,
            String transactionId) {

        /**
         * Normalises and validates the eighteen components so that every instance already obeys the
         * declared wire contract.
         *
         * <p>Assumptions: validating here rather than in the encoder is what makes the encoder
         * unable to emit a payload of the wrong length. A record is the only gate every instance
         * passes through, so a component that is absent, that overruns its declared width, or that
         * contains the delimiter itself is rejected at the point it is supplied rather than at the
         * point it would have corrupted a message.</p>
         *
         * @param authDate the authorization date, which must not be {@code null}
         * @param authTime the authorization time, which must not be {@code null}
         * @param cardNum the primary account number, which must not be {@code null}
         * @param authType the authorization type, which must not be {@code null}
         * @param cardExpiryDate the card expiry, which must not be {@code null}
         * @param messageType the message type, which must not be {@code null}
         * @param messageSource the originating source, which must not be {@code null}
         * @param processingCode the processing code, which must not be {@code null}
         * @param transactionAmount the requested amount, which must not be {@code null}
         * @param merchantCategoryCode the merchant category code, which must not be {@code null}
         * @param acquirerCountryCode the acquirer country code, which must not be {@code null}
         * @param posEntryMode the point-of-sale entry mode, which must not be {@code null}
         * @param merchantId the merchant identifier, which must not be {@code null}
         * @param merchantName the merchant name, which must not be {@code null}
         * @param merchantCity the merchant city, which must not be {@code null}
         * @param merchantState the merchant state, which must not be {@code null}
         * @param merchantZip the merchant postal code, which must not be {@code null}
         * @param transactionId the transaction identifier, which must not be {@code null}
         * @throws NullPointerException if any component is {@code null}, because a string-format
         *     payload has no representation for an absent field and substituting a blank would emit
         *     a well-formed message asserting something the caller never said
         * @throws AuthMessageFormatException if any character component is longer than the width its
         *     copybook line declares, or contains the delimiter
         */
        public AuthRequest {
            authDate = characterField(authDate, REQUEST_FIELD_NAMES.get(0), REQUEST_FIELD_WIDTHS.get(0));
            authTime = characterField(authTime, REQUEST_FIELD_NAMES.get(1), REQUEST_FIELD_WIDTHS.get(1));
            cardNum = characterField(cardNum, REQUEST_FIELD_NAMES.get(2), REQUEST_FIELD_WIDTHS.get(2));
            authType = characterField(authType, REQUEST_FIELD_NAMES.get(3), REQUEST_FIELD_WIDTHS.get(3));
            cardExpiryDate =
                    characterField(cardExpiryDate, REQUEST_FIELD_NAMES.get(4), REQUEST_FIELD_WIDTHS.get(4));
            messageType =
                    characterField(messageType, REQUEST_FIELD_NAMES.get(5), REQUEST_FIELD_WIDTHS.get(5));
            messageSource =
                    characterField(messageSource, REQUEST_FIELD_NAMES.get(6), REQUEST_FIELD_WIDTHS.get(6));
            processingCode =
                    characterField(processingCode, REQUEST_FIELD_NAMES.get(7), REQUEST_FIELD_WIDTHS.get(7));

            // WHY : Assumptions: the amount is checked for presence only, because its type already
            //       carries every other guarantee this contract needs. A Money is invariantly exact
            //       at two decimal places and bounded by Money.MAX_MAGNITUDE, which is
            //       9999999999.99 -- precisely the ten integer digits the picture at line 27 of
            //       CCPAURQY.cpy declares -- so no width check here could reject a value the
            //       renderer would then fail on.
            transactionAmount = requireAmount(transactionAmount, REQUEST_FIELD_NAMES.get(8));

            merchantCategoryCode = characterField(
                    merchantCategoryCode, REQUEST_FIELD_NAMES.get(9), REQUEST_FIELD_WIDTHS.get(9));
            acquirerCountryCode = characterField(
                    acquirerCountryCode, REQUEST_FIELD_NAMES.get(10), REQUEST_FIELD_WIDTHS.get(10));
            posEntryMode =
                    characterField(posEntryMode, REQUEST_FIELD_NAMES.get(11), REQUEST_FIELD_WIDTHS.get(11));
            merchantId =
                    characterField(merchantId, REQUEST_FIELD_NAMES.get(12), REQUEST_FIELD_WIDTHS.get(12));
            merchantName =
                    characterField(merchantName, REQUEST_FIELD_NAMES.get(13), REQUEST_FIELD_WIDTHS.get(13));
            merchantCity =
                    characterField(merchantCity, REQUEST_FIELD_NAMES.get(14), REQUEST_FIELD_WIDTHS.get(14));
            merchantState =
                    characterField(merchantState, REQUEST_FIELD_NAMES.get(15), REQUEST_FIELD_WIDTHS.get(15));
            merchantZip =
                    characterField(merchantZip, REQUEST_FIELD_NAMES.get(16), REQUEST_FIELD_WIDTHS.get(16));
            transactionId =
                    characterField(transactionId, REQUEST_FIELD_NAMES.get(17), REQUEST_FIELD_WIDTHS.get(17));
        }

        /**
         * Returns the keyed, opaque token that matches a request to its reply.
         *
         * <p>Assumptions: the identity being tokenised is the card number at its declared sixteen
         * characters followed by the transaction identifier at its declared fifteen, and neither part
         * may be shortened before tokenising. Both fields lead the message for exactly this reason, so
         * a consumer can pair the two without decoding the rest of either. Each part is padded back to
         * its declared width before tokenising, because trimming would make two different pairs
         * collide the moment one identifier were a prefix of another.</p>
         *
         * <p>Refactoring Rationale: this returned the thirty-one character pair itself when first
         * authored, which is how the reference program correlates -- and on z/OS that key never left
         * the protected boundary. In the migrated system a correlation identity is written to message
         * metadata, to queue telemetry and to application logs, all of which sit outside the boundary
         * that masks a card number, so returning the pair would publish a primary account number to
         * every one of them. A keyed token is stable, so pairing by equality works exactly as before,
         * and it is not reversible by anything holding the token alone. What is given up is the ability
         * to read the card number back out of a correlation value, which is the point.</p>
         *
         * <p>Trade-offs: the tokeniser is a parameter rather than a field, so this record stays a value
         * with no configuration and no key material of its own, and the same key can be supplied to the
         * producer and the consumer without either of them holding one privately. The cost is one
         * argument at every call site, which is also what makes it impossible to obtain a correlation
         * identity without having decided which key it is scoped to.</p>
         *
         * @param tokeniser the keyed tokeniser, whose key material both ends of the exchange share so
         *     that a request and its reply produce the identical token; must not be {@code null}
         * @return the correlation token, exactly {@link OpaqueIdentifier#TOKEN_LENGTH} URL-safe
         *     characters, carrying neither the card number nor the transaction identifier
         * @throws NullPointerException if {@code tokeniser} is {@code null}, because there is no safe
         *     default: a token without a key would be an unkeyed digest of a low-entropy value, which
         *     an adversary can confirm by guessing
         */
        public String correlationKey(OpaqueIdentifier tokeniser) {
            if (tokeniser == null) {
                throw new NullPointerException("tokeniser must not be null");
            }

            return tokeniser.token(CORRELATION_PURPOSE,
                    buildCorrelationKey(cardNum, REQUEST_FIELD_NAMES.get(2), transactionId,
                            REQUEST_FIELD_NAMES.get(17)));
        }
        /**
         * Returns a keyed, opaque per-card derivation of this request's card number.
         *
         * <p>Assumptions: this derivation is NOT the value a producer publishes as the request queue's
         * {@code MessageGroupId}. Specification &sect;0.4.1.8 states
         * the identity literally -- {@code MessageGroupId = card_num} -- and a keyed derivation cannot serve it,
         * because grouping is only an ordering guarantee while EVERY producer on the queue computes the
         * same value for one card, which a value keyed from one consumer's secret cannot be. The
         * exposure that decision accepts, a primary account number in queue metadata, is registered as
         * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} in the divergence register.</p>
         *
         * <p>Assumptions: the derivation itself is available to any single service that needs it where it needs a
         * stable per-card value that discloses nothing -- a metric dimension or a diagnostic key, where
         * only that service compares two values. No publisher in this repository calls it.</p>
         *
         * @param tokeniser the keyed tokeniser; two callers sharing key material derive one value for
         *     one card; must not be {@code null}
         * @return the derived identity, exactly {@link OpaqueIdentifier#TOKEN_LENGTH} URL-safe
         *     characters, stable for this card and carrying no part of its number
         * @throws NullPointerException if {@code tokeniser} is {@code null}
         */
        public String orderGroup(OpaqueIdentifier tokeniser) {
            if (tokeniser == null) {
                throw new NullPointerException("tokeniser must not be null");
            }

            return tokeniser.token(GROUP_PURPOSE, cardNum);
        }

        /**
         * Renders this request for a log line or a diagnostic, disclosing no sensitive wire value.
         *
         * <p>Refactoring Rationale: the compiler-generated rendering a record receives by default
         * prints EVERY component, so the inherited form emitted the full sixteen-digit primary account
         * number along with the merchant identity and the amount authorization was sought for. The
         * transfer objects that carry the same request over HTTP already mask, but this carrier is the
         * one that travels the live queue path -- it is the value a consumer holds when it logs a
         * failed decision, a redelivery or a poison message -- so it is the rendering most likely to
         * reach a log aggregator, and it was the one still disclosing the number. Overriding is the
         * only remedy available: a record's rendering cannot be suppressed by annotation.</p>
         *
         * <p>Assumptions: the card number is MASKED to its last four digits through the shared masker
         * rather than withheld, because the suffix is what makes a log line actionable -- an operator
         * correlating a customer report to a message needs to recognise the card without being handed
         * it. Every other sensitive wire value is WITHHELD outright rather than masked: the amount, the
         * merchant identity, the acquirer geography and the entry mode are together enough to
         * reconstruct a cardholder's purchase, and none of them helps identify which message this is.</p>
         *
         * <p>Refactoring Rationale: the transaction identifier is now WITHHELD too, where an earlier
         * revision retained it in full on the ground that it "discloses nothing on its own". That
         * reasoning contradicted this class's own sensitivity table two hundred lines above, which lists
         * {@code PA-RQ-TRANSACTION-ID} among the withheld fields and states the reason: the identifier
         * is the deduplication key of the ordered request queue, so it appears in operational tooling
         * beside the card number it was grouped by, and quoting it in a log line while masking the card
         * number still narrows a search to one cardholder. One class cannot hold both positions, and the
         * table is the one that reasons about the queue. A reader who needs to correlate has
         * {@link #correlationKey(OpaqueIdentifier)}, which answers a keyed token over the same identity
         * and is stable across log lines without being the identity.</p>
         *
         * <p>Trade-offs: what is given up is the ability to reconstruct a payload from a log line, and
         * now also the ability to name the transaction from one. That is accepted: the codec's own
         * failure messages already name the offending field through
         * {@link #fieldFailure(String, String, CharSequence)}, which withholds the value for exactly
         * the fields withheld here, so the diagnostic path that genuinely needs field detail has it
         * and the incidental path does not; and correlation is served by the tokenised key rather than
         * by the identifier.</p>
         *
         * @return a rendering carrying the masked card number and a note of the withheld components,
         *     never {@code null}
         */
        @Override
        public String toString() {
            return "AuthRequest[cardNum=" + CardNumberMasker.mask(cardNum)
                    + ", transactionId=" + WITHHELD_MARKER
                    + ", " + WITHHELD_COMPONENTS_NOTE + "]";
        }
    }


    /**
     * Carries the six fields of an authorization reply in the order the wire declares them.
     *
     * <p>Assumptions: the components are stored with their trailing pad removed, for the same reason
     * recorded on the request carrier. The reply additionally has to survive the pad byte the
     * reference program appends: because the length handed to the put at line 762 of
     * {@code COPAUA0C.cbl} is the {@code STRING} cursor rather than the transferred count, the
     * sixty-fourth byte of a genuine reply is a space, and stripping the pad is what makes that byte
     * disappear into the trailing empty token instead of becoming part of a value.</p>
     *
     * @param cardNum the primary account number as {@code PA-RL-CARD-NUM} at line 19 of
     *     {@code CCPAURLY.cpy}, at most 16 characters, and the first half of the correlation key
     * @param transactionId the transaction identifier as {@code PA-RL-TRANSACTION-ID} at line 20, at
     *     most 15 characters, and the second half of the correlation key
     * @param authIdCode the authorization identification code as {@code PA-RL-AUTH-ID-CODE} at line
     *     21, at most 6 characters
     * @param authRespCode the response code as {@code PA-RL-AUTH-RESP-CODE} at line 22, at most 2
     *     characters
     * @param authRespReason the response reason as {@code PA-RL-AUTH-RESP-REASON} at line 23, at
     *     most 4 characters
     * @param approvedAmount the approved amount as {@code PA-RL-APPROVED-AMT} at line 24, exact at
     *     two decimal places and rendered on the wire as the {@code MONEY_EDITED_WIDTH}-character
     *     zero-suppressed mask the reference program emits
     */
    public record AuthReply(
            String cardNum,
            String transactionId,
            String authIdCode,
            String authRespCode,
            String authRespReason,
            Money approvedAmount) {

        /**
         * Normalises and validates the six components so that every instance already obeys the
         * declared wire contract.
         *
         * @param cardNum the primary account number, which must not be {@code null}
         * @param transactionId the transaction identifier, which must not be {@code null}
         * @param authIdCode the authorization identification code, which must not be {@code null}
         * @param authRespCode the response code, which must not be {@code null}
         * @param authRespReason the response reason, which must not be {@code null}
         * @param approvedAmount the approved amount, which must not be {@code null}
         * @throws NullPointerException if any component is {@code null}
         * @throws AuthMessageFormatException if any character component is longer than the width its
         *     copybook line declares, or contains the delimiter
         */
        public AuthReply {
            cardNum = characterField(cardNum, REPLY_FIELD_NAMES.get(0), REPLY_FIELD_WIDTHS.get(0));
            transactionId =
                    characterField(transactionId, REPLY_FIELD_NAMES.get(1), REPLY_FIELD_WIDTHS.get(1));
            authIdCode = characterField(authIdCode, REPLY_FIELD_NAMES.get(2), REPLY_FIELD_WIDTHS.get(2));
            authRespCode =
                    characterField(authRespCode, REPLY_FIELD_NAMES.get(3), REPLY_FIELD_WIDTHS.get(3));
            authRespReason =
                    characterField(authRespReason, REPLY_FIELD_NAMES.get(4), REPLY_FIELD_WIDTHS.get(4));
            approvedAmount = requireAmount(approvedAmount, REPLY_FIELD_NAMES.get(5));
        }

        /**
         * Returns the keyed, opaque token that matches a reply to its request.
         *
         * <p>Assumptions: the identity being tokenised is the card number at its declared sixteen
         * characters followed by the transaction identifier at its declared fifteen, and neither part
         * may be shortened before tokenising. Both fields lead the message for exactly this reason, so
         * a consumer can pair the two without decoding the rest of either. Each part is padded back to
         * its declared width before tokenising, because trimming would make two different pairs
         * collide the moment one identifier were a prefix of another.</p>
         *
         * <p>Refactoring Rationale: this returned the thirty-one character pair itself when first
         * authored, which is how the reference program correlates -- and on z/OS that key never left
         * the protected boundary. In the migrated system a correlation identity is written to message
         * metadata, to queue telemetry and to application logs, all of which sit outside the boundary
         * that masks a card number, so returning the pair would publish a primary account number to
         * every one of them. A keyed token is stable, so pairing by equality works exactly as before,
         * and it is not reversible by anything holding the token alone. What is given up is the ability
         * to read the card number back out of a correlation value, which is the point.</p>
         *
         * <p>Trade-offs: the tokeniser is a parameter rather than a field, so this record stays a value
         * with no configuration and no key material of its own, and the same key can be supplied to the
         * producer and the consumer without either of them holding one privately. The cost is one
         * argument at every call site, which is also what makes it impossible to obtain a correlation
         * identity without having decided which key it is scoped to.</p>
         *
         * @param tokeniser the keyed tokeniser, whose key material both ends of the exchange share so
         *     that a request and its reply produce the identical token; must not be {@code null}
         * @return the correlation token, exactly {@link OpaqueIdentifier#TOKEN_LENGTH} URL-safe
         *     characters, carrying neither the card number nor the transaction identifier
         * @throws NullPointerException if {@code tokeniser} is {@code null}, because there is no safe
         *     default: a token without a key would be an unkeyed digest of a low-entropy value, which
         *     an adversary can confirm by guessing
         */
        public String correlationKey(OpaqueIdentifier tokeniser) {
            if (tokeniser == null) {
                throw new NullPointerException("tokeniser must not be null");
            }

            return tokeniser.token(CORRELATION_PURPOSE,
                    buildCorrelationKey(cardNum, REPLY_FIELD_NAMES.get(0), transactionId,
                            REPLY_FIELD_NAMES.get(1)));
        }

        /**
         * Returns a keyed, opaque per-card derivation of this reply's card number.
         *
         * <p>Assumptions: this returns the SAME value
         * {@link AuthRequest#orderGroup(OpaqueIdentifier)} returns for the same card under the same key,
         * because both derive it from the sixteen-character card number under {@link #GROUP_PURPOSE}.
         * The equality is deliberate: a request and the reply about one card are two carriers of one
         * fact, so a derivation over the card alone is the only one both can compute.</p>
         *
         * <p>Refactoring Rationale: this was the value the reply publisher put in the queue's
         * {@code MessageGroupId}. Specification &sect;0.4.1.8 fixes
         * that identity as {@code card_num}, and a derived group identity is equal for equal cards only
         * WITHIN one producer, so a second producer built to the specification would have split one
         * card's messages across two groups and lost the ordering guarantee. The metadata exposure the
         * literal identity accepts is registered as
         * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} in the divergence register and bounded by the
         * queue's encryption, network isolation and task-role scoping.</p>
         *
         * @param tokeniser the keyed tokeniser; two callers sharing key material derive one value for
         *     one card; must not be {@code null}
         * @return the derived identity, exactly {@link OpaqueIdentifier#TOKEN_LENGTH} URL-safe
         *     characters, stable for this card and carrying no part of its number
         * @throws NullPointerException if {@code tokeniser} is {@code null}
         */
        public String orderGroup(OpaqueIdentifier tokeniser) {
            if (tokeniser == null) {
                throw new NullPointerException("tokeniser must not be null");
            }

            return tokeniser.token(GROUP_PURPOSE, cardNum);
        }

        /**
         * Returns a keyed, opaque derivation over this reply's card and transaction pair.
         *
         * <p>Assumptions: the input is the card number and the transaction identifier at their declared
         * widths, which is the pair that names one authorization exactly once, so the derived value is
         * stable for one authorization and independent of the payload's bytes.</p>
         *
         * <p>Refactoring Rationale: this was the value the reply publisher put in the queue's
         * {@code MessageDeduplicationId}. Specification &sect;0.4.1.8
         * fixes that identity as {@code transaction_id}, and a keyed derivation cannot serve it because
         * suppression compares an identity the REQUESTER may resend: a value keyed from this consumer's
         * secret is unpredictable to the requester, so an honest resend arriving by another path would be
         * accepted as a second answer to one request -- the precise failure the derivation was meant to
         * prevent. The registered consequence is
         * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA}.</p>
         *
         * <p>Assumptions: the purpose string is {@link #DEDUPLICATION_PURPOSE} and NOT the correlation
         * purpose, even though both derive from the same pair. Two purposes yield two unrelated values,
         * so a holder of one cannot join it to the other for the same authorization -- which is the
         * linkage purpose separation exists to prevent. No publisher in this repository calls this
         * method.</p>
         *
         * @param tokeniser the keyed tokeniser; two callers sharing key material derive one value for
         *     one authorization; must not be {@code null}
         * @return the derived identity, exactly {@link OpaqueIdentifier#TOKEN_LENGTH} URL-safe
         *     characters, carrying neither the card number nor the transaction identifier
         * @throws NullPointerException if {@code tokeniser} is {@code null}
         */
        public String deduplicationKey(OpaqueIdentifier tokeniser) {
            if (tokeniser == null) {
                throw new NullPointerException("tokeniser must not be null");
            }

            return tokeniser.token(DEDUPLICATION_PURPOSE,
                    buildCorrelationKey(cardNum, REPLY_FIELD_NAMES.get(0), transactionId,
                            REPLY_FIELD_NAMES.get(1)));
        }

        /**
         * Renders this reply for a log line or a diagnostic, disclosing no sensitive wire value.
         *
         * <p>Refactoring Rationale: as on the request carrier, the compiler-generated rendering
         * printed every component and therefore the full sixteen-digit primary account number. The
         * reply is the value a producer holds when it logs a publication failure or an outbox retry,
         * so it reaches the same log aggregator by the same route, and a record's rendering cannot be
         * suppressed by annotation.</p>
         *
         * <p>Assumptions: the card number is masked to its last four digits, while the three decision
         * fields -- the authorization identification code, the response code and the response reason --
         * are retained in full. The decision fields are the whole reason to look at a reply, they are
         * the values a parity comparison checks, and none of them says anything about the cardholder.</p>
         *
         * <p>Assumptions: the approved amount and the transaction identifier are BOTH withheld. Both
         * appear in this class's sensitivity table -- {@code PA-RL-APPROVED-AMT} and
         * {@code PA-RL-TRANSACTION-ID} -- and the two carriers are kept symmetrical deliberately:
         * withholding an identifier on the request while quoting the same identifier on the reply
         * would leave the value in the log by whichever of the two happened to be rendered, and a
         * reader could not tell which class had made the decision.
         * {@link #correlationKey(OpaqueIdentifier)} answers a keyed token over the same identity for a
         * reader who has to correlate.</p>
         *
         * <p>Trade-offs: the withheld amount and identifier mean a reader reconciling money or naming a
         * transaction has to consult the message or the stored decision rather than a log line, which
         * is accepted for the same reason as on the request carrier: the paths that legitimately need
         * the value have it, and an incidental rendering is not one of them.</p>
         *
         * @return a rendering carrying the masked card number and the three decision fields, with the
         *     identifier and the amount marked as withheld, never {@code null}
         */
        @Override
        public String toString() {
            return "AuthReply[cardNum=" + CardNumberMasker.mask(cardNum)
                    + ", transactionId=" + WITHHELD_MARKER
                    + ", authIdCode=" + authIdCode
                    + ", authRespCode=" + authRespCode
                    + ", authRespReason=" + authRespReason
                    + ", approvedAmount=" + WITHHELD_MARKER + "]";
        }
    }


    /**
     * Renders an authorization request as its comma-delimited payload.
     *
     * <p>Assumptions: every field is padded out to the width its copybook line declares, because the
     * outbound build at lines 722 to 731 of {@code COPAUA0C.cbl} joins with
     * {@code DELIMITED BY SIZE}, which contributes each source field's full declared size including
     * its blanks. Trade-offs: emitting trimmed fields instead would produce a shorter payload that a
     * comma-splitting reader would still parse, and it was rejected because it changes the byte image
     * the existing integration receives. The declared widths are the interface, so the padding is
     * reproduced and the length lands on {@code REQUEST_WIRE_LENGTH} rather than somewhere between
     * {@code REQUEST_DECLARED_WIDTH_SUM} and it.</p>
     *
     * <p>Assumptions: all eighteen widths are the copybook's own, the ordinal-nine money field being
     * rendered by {@link #formatRequestMoney} at the fourteen {@code PIC +9(10).99} declares.</p>
     *
     * @param request the request to render; must not be {@code null}. Its components are already
     *     within their declared widths, because the carrier validates them at construction
     * @return the payload, always exactly {@code REQUEST_WIRE_LENGTH} characters, with seventeen
     *     interior delimiters and no trailing one
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REQUEST_WIRE_LENGTH} characters, which can only mean the width table and the wire
     *     length constant have stopped agreeing
     */
    public static String encodeRequest(AuthRequest request) {
        if (request == null) {
            throw new NullPointerException("request must not be null");
        }

        // WHY : Assumptions: the ordinal-nine value is produced by the money renderer rather than by
        //       a byte copy of a stored string. The reference program converts explicitly in
        //       this direction too -- MOVE WS-APPROVED-AMT TO WS-APPROVED-AMT-DIS at line 720 of
        //       COPAUA0C.cbl moves a numeric field into an edited-display field before the STRING --
        //       so the rendering is a conversion step in the baseline and is kept as one here.
        // Assumptions: formatRequestMoney and NOT formatReplyMoney. Both are fourteen characters wide
        //   and they agree on nothing else: the request picture PIC +9(10).99 at CCPAURQY.cpy line 27
        //   fixes a sign character and zero-pads its digits, while the reply mask PIC -zzzzzzzzz9.99
        //   blanks the sign for a non-negative amount and suppresses leading zeros. Calling the reply
        //   renderer here would emit a payload no producer of this contract would send.
        List<String> values = List.of(
                request.authDate(),
                request.authTime(),
                request.cardNum(),
                request.authType(),
                request.cardExpiryDate(),
                request.messageType(),
                request.messageSource(),
                request.processingCode(),
                formatRequestMoney(request.transactionAmount(), REQUEST_FIELD_NAMES.get(8)),
                request.merchantCategoryCode(),
                request.acquirerCountryCode(),
                request.posEntryMode(),
                request.merchantId(),
                request.merchantName(),
                request.merchantCity(),
                request.merchantState(),
                request.merchantZip(),
                request.transactionId());

        return join(values, REQUEST_FIELD_WIDTHS, REQUEST_FIELD_NAMES, false, REQUEST_WIRE_LENGTH,
                "request");
    }

    /**
     * Renders an authorization request as the bytes of its comma-delimited payload.
     *
     * @param request the request to render; must not be {@code null}
     * @return the payload bytes, always exactly {@code REQUEST_WIRE_LENGTH} bytes long
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REQUEST_WIRE_LENGTH} characters
     */
    public static byte[] encodeRequestBytes(AuthRequest request) {
        return toWireBytes(encodeRequest(request));
    }

    /**
     * Parses an authorization request from its comma-delimited payload.
     *
     * <p>Assumptions: the eighteen values are taken by ordinal position, which is the whole of the
     * contract. The {@code UNSTRING} at lines 354 to 355 of {@code COPAUA0C.cbl} names its eighteen
     * receiving fields at lines 356 to 373 in the order {@code CCPAURQY.cpy} declares them at lines
     * 19 to 36, and {@code REQUEST_FIELD_NAMES} records that order so a test can assert it against
     * the copybook rather than against this method.</p>
     *
     * @param payload the comma-delimited payload; must not be {@code null}. A trailing pad-only
     *     token is tolerated, so a producer that appends a delimiter after the last field is read
     *     rather than rejected
     * @return the parsed request, with every character field's trailing pad removed and the amount
     *     converted through the edited display form
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload does not carry exactly
     *     {@code REQUEST_FIELD_COUNT} fields, if any field overruns its declared width, or if the
     *     amount token is not a well-formed edited display value
     */
    public static AuthRequest decodeRequest(String payload) {
        List<String> tokens = splitOnDelimiter(payload, REQUEST_FIELD_COUNT, "request");
        requireDeclaredTokenWidths(tokens, REQUEST_FIELD_WIDTHS, REQUEST_FIELD_NAMES,
                REQUEST_AMOUNT_ORDINAL, "request");

        return new AuthRequest(
                tokens.get(0),
                tokens.get(1),
                tokens.get(2),
                tokens.get(3),
                tokens.get(4),
                tokens.get(5),
                tokens.get(6),
                tokens.get(7),
                parseMoney(tokens.get(8), REQUEST_FIELD_NAMES.get(8)),
                tokens.get(9),
                tokens.get(10),
                tokens.get(11),
                tokens.get(12),
                tokens.get(13),
                tokens.get(14),
                tokens.get(15),
                tokens.get(16),
                tokens.get(17));
    }

    /**
     * Parses an authorization request from the first bytes of a transport buffer.
     *
     * @param buffer the transport buffer, which may be longer than the payload it carries; must not
     *     be {@code null}
     * @param payloadLength the number of leading bytes of {@code buffer} that constitute the payload,
     *     which is the analogue of the reference program's explicit data length and is deliberately
     *     not derived from the buffer's own length
     * @return the parsed request
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or exceeds the
     *     buffer's length, if the payload does not carry exactly {@code REQUEST_FIELD_COUNT} fields,
     *     if any field overruns its declared width, or if the amount token is malformed
     */
    public static AuthRequest decodeRequest(byte[] buffer, int payloadLength) {
        return decodeRequest(payloadText(buffer, payloadLength, "request"));
    }

    /**
     * Renders an authorization reply as its comma-delimited payload.
     *
     * <p>Assumptions: the payload ends with a delimiter, which is why it is 63 characters and not 62.
     * The {@code STRING} at lines 722 to 731 of {@code COPAUA0C.cbl} pairs a {@code ','} literal with
     * every one of the six values including the sixth at line 727, so the trailing comma is part of
     * the emitted form rather than an artefact of it. Emitting the interior-only form that the
     * nominal arithmetic in {@code docs/architecture/messaging-contracts.md} predicts would produce a
     * payload one byte shorter than the one the reference consumer has always received.</p>
     *
     * <p>Assumptions: this method emits 63 characters where the reference program's put transmits 64.
     * The extra byte is a consequence of {@code WS-RESP-LENGTH} serving as both the {@code STRING}
     * cursor at line 730 and the put's buffer length at lines 756 and 762, so the baseline sends one
     * byte of uninitialised buffer past the payload it built. The divergence is registered as
     * {@code D-REPLY-PUT-LENGTH} in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The reciprocal tolerance lives in
     * {@link #decodeReply(String)}.</p>
     *
     * @param reply the reply to render; must not be {@code null}
     * @return the payload, always exactly {@code REPLY_WIRE_LENGTH} characters, with six delimiters
     *     of which the last is trailing
     * @throws NullPointerException if {@code reply} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REPLY_WIRE_LENGTH} characters
     */
    public static String encodeReply(AuthReply reply) {
        if (reply == null) {
            throw new NullPointerException("reply must not be null");
        }

        List<String> values = List.of(
                reply.cardNum(),
                reply.transactionId(),
                reply.authIdCode(),
                reply.authRespCode(),
                reply.authRespReason(),
                formatReplyMoney(reply.approvedAmount(), REPLY_FIELD_NAMES.get(5)));

        return join(values, REPLY_FIELD_WIDTHS, REPLY_FIELD_NAMES, true, REPLY_WIRE_LENGTH, "reply");
    }

    /**
     * Renders an authorization reply as the bytes of its comma-delimited payload.
     *
     * @param reply the reply to render; must not be {@code null}
     * @return the payload bytes, always exactly {@code REPLY_WIRE_LENGTH} bytes long
     * @throws NullPointerException if {@code reply} is {@code null}
     * @throws AuthMessageFormatException if the assembled payload does not reach exactly
     *     {@code REPLY_WIRE_LENGTH} characters
     */
    public static byte[] encodeReplyBytes(AuthReply reply) {
        return toWireBytes(encodeReply(reply));
    }

    /**
     * Parses an authorization reply from its comma-delimited payload.
     *
     * <p>Assumptions: a trailing pad-only token is expected here rather than merely tolerated,
     * because the trailing comma at line 727 of {@code COPAUA0C.cbl} guarantees one. Splitting a
     * 63-character payload on the delimiter yields seven tokens whose seventh is empty, and splitting
     * the 64-byte form the put actually sends yields seven whose seventh is a single space. Both are
     * accepted, and a seventh token carrying content is rejected, so the tolerance cannot be
     * mistaken for accepting a seven-field reply.</p>
     *
     * @param payload the comma-delimited payload; must not be {@code null}. The canonical form
     *     carries the trailing delimiter, and a producer that omits it is also accepted
     * @return the parsed reply, with every character field's trailing pad removed and the amount
     *     converted through the edited display form
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload does not carry exactly
     *     {@code REPLY_FIELD_COUNT} fields, if any field overruns its declared width, or if the
     *     amount token is not a well-formed edited display value
     */
    public static AuthReply decodeReply(String payload) {
        List<String> tokens = splitOnDelimiter(payload, REPLY_FIELD_COUNT, "reply");
        requireDeclaredTokenWidths(tokens, REPLY_FIELD_WIDTHS, REPLY_FIELD_NAMES,
                REPLY_AMOUNT_ORDINAL, "reply");

        return new AuthReply(
                tokens.get(0),
                tokens.get(1),
                tokens.get(2),
                tokens.get(3),
                tokens.get(4),
                parseMoney(tokens.get(5), REPLY_FIELD_NAMES.get(5)));
    }

    /**
     * Parses an authorization reply from the first bytes of a transport buffer.
     *
     * @param buffer the transport buffer, which may be longer than the payload it carries; must not
     *     be {@code null}
     * @param payloadLength the number of leading bytes of {@code buffer} that constitute the payload,
     *     held separately from the buffer's own length
     * @return the parsed reply
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or exceeds the
     *     buffer's length, if the payload does not carry exactly {@code REPLY_FIELD_COUNT} fields,
     *     if any field overruns its declared width, or if the amount token is malformed
     */
    public static AuthReply decodeReply(byte[] buffer, int payloadLength) {
        return decodeReply(payloadText(buffer, payloadLength, "reply"));
    }

    /**
     * Renders a reply amount in the fourteen-character zero-suppressed mask the reference emits.
     *
     * <p>Assumptions: the rendering is the one the reference program actually puts on the wire,
     * {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} declared at line 66 of {@code COPAUA0C.cbl},
     * moved into at line 720 and joined into the reply buffer at line 727. It is <em>not</em>
     * {@code PA-RL-APPROVED-AMT PIC +9(10).99} from line 24 of {@code CCPAURLY.cpy}: that field
     * holds the value, the mask emits it, and only the mask reaches a consumer. The two agree on the
     * width, fourteen, and disagree on every position's content -- so emitting a {@code +} and zero
     * padding would produce a correctly-sized payload that no COBOL program would ever have produced.
     * The fourteen breaks down as one sign-control position, nine
     * zero-suppressed digit positions, one forced digit position, the literal point, and two
     * fractional digits; the implied decimal position that {@code V} denotes in the zoned and packed
     * pictures occupies no byte at all.</p>
     *
     * <p>Assumptions: the three characteristic outputs are worth stating because each is a byte an
     * intuitive implementation gets wrong. A non-negative value emits a SPACE in the sign position,
     * never a {@code +}. Leading zeros emit SPACES, never zeros. And zero itself emits
     * {@code "          0.00"} -- ten spaces then {@code 0.00} -- because the single {@code 9} left
     * of the point is a forced digit that suppression cannot reach, so the field is never blank.
     * {@link BigDecimal}'s lack of a negative zero is therefore invisible here: negative zero and
     * zero render identically, unlike the packed rendering where a sign nibble can encode a signed
     * zero distinctly and the decoder has to decide what that means.</p>
     *
     * <p>Trade-offs: this method is lossless or it raises. Silent truncation of a value wider than
     * ten integer digits was rejected because it yields a materially smaller number that still looks
     * like money, and silent rounding of a value carrying more than two decimal places was rejected
     * because rounding is a business decision and this is a transport boundary. Rounding is delegated
     * to {@link Money}, which applies scale {@value Money#SCALE} under {@link Money#GENERAL_ROUNDING}
     * for a general reduction -- its other mode governs the interest accrual alone, which no
     * authorization payload carries -- at the point a caller has decided that reducing the value is
     * correct. What is accepted is that a
     * caller holding a three-decimal intermediate has to route it through {@code Money} explicitly
     * rather than having this method decide for them.</p>
     *
     * @param amount the amount to render; must not be {@code null} and must carry no more than
     *     {@code MONEY_SCALE} decimal places, a value with fewer being padded rather than rejected
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message so that a rejection names the field a reader can look up
     * @return the amount as exactly {@code MONEY_EDITED_WIDTH} characters: a sign-control position
     *     holding {@code -} or a space, nine zero-suppressed digit positions, one forced digit
     *     position, a literal decimal point, and two fractional digits
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws AuthMessageFormatException if {@code amount} carries more than {@code MONEY_SCALE}
     *     decimal places, or if its magnitude needs more than {@code MONEY_INTEGER_DIGITS} integer
     *     digits
     */
    public static String formatReplyMoney(BigDecimal amount, String fieldName) {
        BigDecimal canonical = canonicalAmount(amount, fieldName);
        String integerDigits = integerDigitsOf(canonical, fieldName, MONEY_INTEGER_DIGITS);
        String fractionDigits = fractionDigitsOf(canonical);

        // WHY : Assumptions: the sign is taken from the signed value while the digits are taken from
        //       its magnitude, because the picture places the sign in its own leading position rather
        //       than folding it into a digit. That is the difference between this rendering and the
        //       zoned overpunch, where the sign shares the final byte with a digit.
        StringBuilder rendered = new StringBuilder(MONEY_EDITED_WIDTH);
        rendered.append(canonical.signum() < 0 ? MONEY_NEGATIVE_SIGN : MONEY_SIGN_BLANK);

        // WHY : Assumptions: this loop emits SPACES where the zero-padding form emitted zeros, and
        //       that single character difference is the whole of the byte-compatibility fix. The
        //       count is MONEY_INTEGER_DIGITS minus the digits present, so a nine-digit value gets
        //       one space and a one-digit value gets nine -- which is exactly the reach of the nine
        //       `z` positions, because the tenth position is the forced `9` that always receives a
        //       digit from `integerDigits` below.
        for (int position = integerDigits.length(); position < MONEY_INTEGER_DIGITS; position++) {
            rendered.append(MONEY_SUPPRESSED_DIGIT);
        }
        rendered.append(integerDigits).append(MONEY_DECIMAL_POINT).append(fractionDigits);

        // WHY : Assumptions: the result is MONEY_EDITED_WIDTH characters by construction rather than
        //       by check. It is one sign position, MONEY_INTEGER_DIGITS positions after the
        //       suppression loop above, one point, and the MONEY_SCALE fractional digits that the
        //       canonical scale guarantees toPlainString emits. A length check here would test the
        //       loop directly above it rather than anything a caller can influence.
        return rendered.toString();
    }

    /**
     * Renders a request amount in the fourteen characters {@code PIC +9(10).99} declares.
     *
     * <p>Assumptions: the fourteen are spent exactly as {@code PIC +9(10).99} at line 27 of
     * {@code CCPAURQY.cpy} declares them -- one sign character, ten integer digits, the literal point
     * and two fractional digits -- so the whole {@code 9(10)} domain up to {@code 9999999999.99} is
     * renderable at either sign. A leading {@code +} in a COBOL picture is a fixed sign position that
     * emits {@code +} for a non-negative value rather than a blank, which is what distinguishes this
     * rendering from the reply mask.</p>
     *
     * <p>Trade-offs: zero padding rather than blank suppression, which is the opposite choice from
     * {@link #formatReplyMoney}. Either would satisfy the reference conversion, which ignores leading
     * blanks and leading zeros alike; zeros are chosen because they make the field fixed-width in its
     * digits as well as in its total, so a byte-level diff of two payloads aligns column for
     * column.</p>
     *
     * @param amount the amount to render; must not be {@code null} and must carry no more than
     *     {@code MONEY_SCALE} decimal places, a value with fewer being padded rather than rejected
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message so that a rejection names the field a reader can look up
     * @return the amount as exactly {@code REQUEST_MONEY_WIDTH} characters: a {@code +} or {@code -}
     *     sign, ten zero-padded integer digits, a literal point and two fractional digits
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws AuthMessageFormatException if {@code amount} carries more than {@code MONEY_SCALE}
     *     decimal places, or if its magnitude needs more than {@code MONEY_INTEGER_DIGITS} integer
     *     digits
     */
    public static String formatRequestMoney(BigDecimal amount, String fieldName) {
        BigDecimal canonical = canonicalAmount(amount, fieldName);
        String integerDigits = integerDigitsOf(canonical, fieldName, MONEY_INTEGER_DIGITS);
        String fractionDigits = fractionDigitsOf(canonical);

        StringBuilder rendered = new StringBuilder(REQUEST_MONEY_WIDTH);
        rendered.append(canonical.signum() < 0 ? MONEY_NEGATIVE_SIGN : MONEY_POSITIVE_SIGN);
        for (int position = integerDigits.length(); position < MONEY_INTEGER_DIGITS; position++) {
            rendered.append(ZERO_DIGIT);
        }
        rendered.append(integerDigits).append(MONEY_DECIMAL_POINT).append(fractionDigits);

        return rendered.toString();
    }

    /**
     * Reduces an amount to the canonical scale this wire carries, rejecting anything wider.
     *
     * <p>Trade-offs: this is lossless or it raises. Silent rounding of a value carrying more than two
     * decimal places was rejected because rounding is a business decision and this is a transport
     * boundary. Rounding is delegated to {@link Money}, which applies scale {@value Money#SCALE} with
     * {@code RoundingMode.HALF_UP} at the point a caller has decided that reducing the value is
     * correct. What is accepted is that a caller holding a three-decimal intermediate has to route it
     * through {@code Money} explicitly rather than having this method decide for them.</p>
     *
     * @param amount the amount to canonicalise; must not be {@code null}
     * @param fieldName the copybook name of the field being rendered, used to compose a failure
     *     message
     * @return the same value at exactly {@code MONEY_SCALE} decimal places
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws AuthMessageFormatException if {@code amount} carries more than {@code MONEY_SCALE}
     *     decimal places
     */
    private static BigDecimal canonicalAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new NullPointerException(fieldName + " amount must not be null");
        }

        if (amount.scale() > MONEY_SCALE) {
            // WHY : Assumptions: the SCALE is named and the amount is not. A scale is geometry, safe
            //       for a sensitive field and sufficient for the caller to act on; the amount itself
            //       is offered to the gate, which withholds it for a monetary field.
            throw fieldFailure(fieldName, "carries " + amount.scale() + " decimal places but the"
                    + " picture declares " + MONEY_SCALE + "; reduce it through Money so the rounding"
                    + " is an explicit decision", amount.toPlainString());
        }

        // WHY : Assumptions: the bare setScale is safe only because of the guard above. It raises
        //       whenever a reduction would discard a digit, and the guard has already established
        //       that the scale is at most two, so this call only ever pads. Passing a rounding mode
        //       here instead would make the guard decorative and would reintroduce exactly the
        //       silent rounding the guard exists to prevent.
        return amount.setScale(MONEY_SCALE);
    }

    /**
     * Extracts the integer digits of a canonical amount, rejecting a magnitude that will not fit.
     *
     * @param canonical an amount already reduced to {@code MONEY_SCALE} decimal places
     * @param fieldName the copybook name of the field being rendered, used to compose a failure
     *     message
     * @param capacity the number of integer digit positions the rendering has available, which
     *     differs between the two renderings and, for a request, between the two signs
     * @return the magnitude's integer digits with no sign and no padding
     * @throws AuthMessageFormatException if the magnitude needs more than {@code capacity} integer
     *     digits
     */
    private static String integerDigitsOf(BigDecimal canonical, String fieldName, int capacity) {
        // WHY : Assumptions: the digits come from the ABSOLUTE value, because every rendering here
        //       places the sign in its own position rather than folding it into a digit. Taking them
        //       from the signed value would put a '-' inside the digit run and shift the padding.
        String magnitude = canonical.abs().toPlainString();
        int pointIndex = magnitude.indexOf(MONEY_DECIMAL_POINT);
        String integerDigits = magnitude.substring(0, pointIndex);

        if (integerDigits.length() > capacity) {
            throw new AuthMessageFormatException(fieldName
                    + " needs " + integerDigits.length() + " integer digits but this rendering has "
                    + capacity + " available");
        }

        return integerDigits;
    }

    /**
     * Extracts the fractional digits of a canonical amount.
     *
     * @param canonical an amount already reduced to {@code MONEY_SCALE} decimal places, which is what
     *     guarantees the returned run is exactly that long
     * @return the {@code MONEY_SCALE} fractional digits
     */
    private static String fractionDigitsOf(BigDecimal canonical) {
        String magnitude = canonical.abs().toPlainString();
        return magnitude.substring(magnitude.indexOf(MONEY_DECIMAL_POINT) + 1);
    }

    /**
     * Renders a reply amount in the fourteen-character zero-suppressed mask the reference emits.
     *
     * <p>Assumptions: this overload cannot raise a format failure, because its argument type has
     * already excluded both failure modes. A {@link Money} is invariantly exact at
     * {@value Money#SCALE} decimal places and bounded by {@code Money.MAX_MAGNITUDE}, which is
     * {@code 9999999999.99} -- precisely the ten integer digit positions this mask declares -- so
     * neither the scale guard nor the width guard in the delegate can fire. It is offered as the
     * ergonomic entry point, and the {@link BigDecimal} overload remains available for a caller that
     * holds a raw value and wants the guards applied.</p>
     *
     * @param amount the amount to render; must not be {@code null}
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message
     * @return the amount as exactly {@code MONEY_EDITED_WIDTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    public static String formatReplyMoney(Money amount, String fieldName) {
        if (amount == null) {
            throw new NullPointerException(fieldName + " amount must not be null");
        }

        return formatReplyMoney(amount.amount(), fieldName);
    }

    /**
     * Renders a request amount in the fourteen characters {@code PIC +9(10).99} declares.
     *
     * <p>Assumptions: a {@link Money} never fails this rendering at either sign, because
     * {@code Money.MAX_MAGNITUDE} is {@code 9999999999.99} and the picture's sign position is
     * additional to its ten integer digits rather than carved out of them.</p>
     *
     * @param amount the amount to render; must not be {@code null}
     * @param fieldName the copybook name of the field being rendered, used only to compose a failure
     *     message
     * @return the amount as exactly {@code REQUEST_MONEY_WIDTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    public static String formatRequestMoney(Money amount, String fieldName) {
        if (amount == null) {
            throw new NullPointerException(fieldName + " amount must not be null");
        }

        return formatRequestMoney(amount.amount(), fieldName);
    }

    /**
     * Parses an amount from the edited display token the wire carries.
     *
     * <p>Assumptions: the conversion is an explicit parse and never a byte copy of the wire
     * characters. The
     * reference program converts explicitly in this direction too --
     * {@code COMPUTE PA-RQ-TRANSACTION-AMT = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)} at lines 376 to
     * 377 of {@code COPAUA0C.cbl} -- so the text form and the numeric form are distinct
     * representations in the baseline and are kept distinct here.</p>
     *
     * <p>Assumptions: the accepted grammar is wider than the emitted one, and each widening answers
     * something measurable in the source. The sign may be omitted, because the outbound rendering the
     * reference program actually uses is {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} at line 66,
     * whose zero suppression leaves the sign position blank for a positive value. Leading and
     * trailing pad
     * are removed, because that same zero-suppressed rendering emits leading spaces and because the
     * put's pointer-derived length appends a trailing one. Fewer than ten integer digits are
     * accepted, for the same zero-suppression reason. What is not widened is the decimal point, the
     * digits or the sign character itself: a missing or misplaced point, a non-digit in a digit
     * position, and a sign that is neither {@code +} nor {@code -} are each rejected by name, because
     * every one of them would otherwise be read as a different amount rather than as a failure.</p>
     *
     * @param token the field as it appeared on the wire, with or without its pad; must not be
     *     {@code null}
     * @param fieldName the copybook name of the field being parsed, used to compose a failure message
     *     so that a rejected payload names the field and the offending token
     * @return the parsed amount, exact at {@value Money#SCALE} decimal places
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws AuthMessageFormatException if the token is blank, carries a sign character other than
     *     {@code +} or {@code -}, has no decimal point or more than one, has no integer digit before
     *     the point, does not have exactly {@code MONEY_SCALE} digits after it, contains a non-digit
     *     in a digit position, or needs more than {@code MONEY_INTEGER_DIGITS} integer digits
     */
    public static Money parseMoney(String token, String fieldName) {
        if (token == null) {
            throw new NullPointerException(fieldName + " token must not be null");
        }

        String value = stripSurroundingPad(token);
        if (value.isEmpty()) {
            throw fieldFailure(fieldName, "carries no amount; the picture declares "
                    + MONEY_EDITED_WIDTH + " characters and the token was blank", null);
        }

        boolean negative = false;
        int digitsStart = 0;
        char signCandidate = value.charAt(0);
        if (signCandidate == MONEY_POSITIVE_SIGN) {
            digitsStart = 1;
        } else if (signCandidate == MONEY_NEGATIVE_SIGN) {
            negative = true;
            digitsStart = 1;
        } else if (!isAsciiDigit(signCandidate)) {
            // WHY : Assumptions: the offending CHARACTER is named and the whole token is not. One
            //       character of an amount is not the amount, and it is the single most useful fact
            //       for a producer whose rendering emitted a currency symbol or a thousands separator
            //       in the sign position; the token would additionally carry every digit of the value.
            throw fieldFailure(fieldName, "carries '" + signCandidate + "' in its sign position,"
                    + " which the picture declares as '" + MONEY_POSITIVE_SIGN + "' or '"
                    + MONEY_NEGATIVE_SIGN + "'", null);
        }

        // WHY : Assumptions: the pad run BETWEEN the sign position and the first digit is removed here,
        //       and removing it is what closes this codec under its own reply contract. The reply amount
        //       is rendered through PIC -zzzzzzzzz9.99, declared at app/app-authorization-ims-db2-mq/
        //       cbl/COPAUA0C.cbl line 66 and moved into the put buffer at its line 720, whose sign
        //       occupies a FIXED leading position while the zero-suppression characters blank every
        //       leading integer position the magnitude does not reach -- so -100.99 renders as a minus,
        //       seven blanks and then 100.99. Stripping only the two ENDS of the token would leave those
        //       interior blanks in the integer digit run, and the parser would then reject a token this
        //       same class had just emitted. The pad is removed only where the
        //       mask can produce it, immediately after the sign; a blank appearing anywhere else still
        //       reaches the digit check below and is still refused, so a token such as '1 0.99' remains
        //       an error rather than becoming 10.99.
        String unsigned = stripLeadingPad(value.substring(digitsStart));
        int pointIndex = unsigned.indexOf(MONEY_DECIMAL_POINT);
        if (pointIndex < 0) {
            throw fieldFailure(fieldName, "carries no '" + MONEY_DECIMAL_POINT + "'; the picture"
                    + " declares a literal decimal point", token);
        }
        if (unsigned.indexOf(MONEY_DECIMAL_POINT, pointIndex + 1) >= 0) {
            throw fieldFailure(fieldName, "carries more than one '" + MONEY_DECIMAL_POINT + "'",
                    token);
        }

        String integerDigits = unsigned.substring(0, pointIndex);
        String fractionDigits = unsigned.substring(pointIndex + 1);

        if (integerDigits.isEmpty()) {
            throw fieldFailure(fieldName, "has no digit before its decimal point", token);
        }
        if (fractionDigits.length() != MONEY_SCALE) {
            // WHY : Assumptions: the COUNT of digits is named and the digits are not. A count is
            //       geometry rather than content, so it is safe for a sensitive field and is precisely
            //       what a producer needs in order to correct a scale.
            throw fieldFailure(fieldName, "has " + fractionDigits.length() + " digits after its"
                    + " decimal point but the picture declares " + MONEY_SCALE, token);
        }

        requireAsciiDigits(integerDigits, fieldName, token);
        requireAsciiDigits(fractionDigits, fieldName, token);

        if (integerDigits.length() > MONEY_INTEGER_DIGITS) {
            throw fieldFailure(fieldName, "needs " + integerDigits.length() + " integer digits but"
                    + " the picture declares " + MONEY_INTEGER_DIGITS, token);
        }

        // WHY : Assumptions: the two validated digit runs are read as one whole number of cents and
        //       handed to Money.ofCents, which interprets it against an implied decimal point and so
        //       performs no division and engages no rounding mode. Money.of would have accepted a
        //       decimal string just as readily and would have applied its rounding mode on the way
        //       in, which is a behaviour this path must not have. The concatenation is at most
        //       MONEY_INTEGER_DIGITS plus MONEY_SCALE digits, so twelve, and every twelve-digit
        //       integer is inside the long domain; the ten-digit bound checked above is also exactly
        //       Money.MAX_MAGNITUDE, so the factory's own domain check cannot fire here either.
        long cents = Long.parseLong(integerDigits + fractionDigits);

        return Money.ofCents(negative ? -cents : cents);
    }

    /**
     * Joins already-rendered field values into a delimited payload of the expected wire length.
     *
     * @param values the field values in wire order, each already within its declared width
     * @param widths the declared width of each field, in the same order as {@code values}
     * @param names the copybook name of each field, in the same order, used for failure messages
     * @param trailingDelimiter whether a delimiter follows the final field, which the reply carries
     *     and the request does not
     * @param expectedWireLength the length the assembled payload must reach
     * @param payloadName the payload's name, used to compose a failure message
     * @return the assembled payload, exactly {@code expectedWireLength} characters long
     * @throws AuthMessageFormatException if a value overruns its declared width, or if the assembled
     *     length does not equal {@code expectedWireLength}
     */
    private static String join(List<String> values, List<Integer> widths, List<String> names,
            boolean trailingDelimiter, int expectedWireLength, String payloadName) {
        StringBuilder payload = new StringBuilder(expectedWireLength);
        int lastIndex = values.size() - 1;

        // WHY : Assumptions: the builder is sized to the expected wire length rather than left to
        //       grow, because that length is known before the first field is appended and is the same
        //       for every message of this kind. Sizing it also means a payload that reached the wrong
        //       length would have reallocated, which the guard below then reports rather than hides.
        for (int fieldIndex = 0; fieldIndex <= lastIndex; fieldIndex++) {
            payload.append(padToWidth(values.get(fieldIndex), widths.get(fieldIndex),
                    names.get(fieldIndex)));

            // WHY : Assumptions: whether the final field is followed by a delimiter is a per-payload
            //       property rather than a general one, and the two payloads do not agree on it. The
            //       reply's STRING at lines 722 to 731 of COPAUA0C.cbl pairs a comma with all six
            //       values including the sixth, while the request's UNSTRING at line 354 names
            //       eighteen receiving fields and therefore consumes only interior delimiters. That
            //       trailing comma is what makes the built reply sixty-three characters rather than
            //       the sixty-two an interior-delimiter count predicts, and the length it is
            //       published at is registered as D-REPLY-PUT-LENGTH in
            //       docs/architecture/cobol-to-service-traceability.md.
            if (fieldIndex < lastIndex || trailingDelimiter) {
                payload.append(DELIMITER);
            }
        }

        // WHY : Assumptions: this guard protects a future edit rather than the current caller. Every
        //       value reaching here has already been width-checked at construction, so the length is
        //       determined by the width table and the delimiter rule alone. What it would catch is a
        //       field added to a carrier without a matching entry in the width table, or a wire
        //       length constant changed without its table -- either of which would otherwise ship a
        //       payload that parses field by field and is the wrong length overall.
        if (payload.length() != expectedWireLength) {
            throw new AuthMessageFormatException("assembled " + payloadName + " payload is "
                    + payload.length() + " characters but the contract declares "
                    + expectedWireLength + "; the declared width table and the wire length constant"
                    + " no longer agree");
        }

        return payload.toString();
    }

    /**
     * Splits a payload on the delimiter and establishes that it carries the expected field count.
     *
     * <p>Assumptions: the split keeps empty tokens, because an omitted optional field and a trailing
     * delimiter are both legitimate and both produce one. A split that discarded them would turn a
     * blank card number into a missing field and shift every value after it.</p>
     *
     * @param payload the delimited payload as received; must not be {@code null}
     * @param expectedFieldCount the number of fields the payload's copybook declares
     * @param payloadName the payload's name, used to compose a failure message
     * @return the field values in wire order, exactly {@code expectedFieldCount} of them, with any
     *     tolerated trailing pad-only token removed
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws AuthMessageFormatException if the payload carries fewer or more fields than
     *     {@code expectedFieldCount}, a trailing pad-only token being the one tolerated excess
     */
    private static List<String> splitOnDelimiter(String payload, int expectedFieldCount,
            String payloadName) {
        if (payload == null) {
            throw new NullPointerException(payloadName + " payload must not be null");
        }

        // WHY : Assumptions: the length is the FIRST thing decided, before a single character is
        //       examined, because every step after it costs work proportional to the payload while the
        //       payload itself arrives from a queue. This is the early bound: a delimiter flood is
        //       refused here rather than being counted, sized into an array and split into one substring
        //       per delimiter on its way to a field-count check it was always going to fail.
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new AuthMessageFormatException("the " + payloadName + " payload is "
                    + payload.length() + " characters, beyond the " + MAX_PAYLOAD_LENGTH
                    + " this contract admits; the declared wire lengths are "
                    + REQUEST_WIRE_LENGTH + " for a request and " + REPLY_WIRE_LENGTH
                    + " for a reply");
        }

        // WHY : Assumptions: control characters are refused for the whole payload at once rather than
        //       per field, so no token carrying one can reach a value, a diagnostic or a renderer. Two
        //       distinct hazards close here. A carriage return or line feed inside a value forges or
        //       splits a record in any line-oriented log the value later reaches, so one message could
        //       fabricate log entries that never happened. And an escape sequence reaching a terminal or
        //       a log viewer is interpreted by it rather than displayed. The reference contract admits
        //       none of them in any case: every field it declares is a display picture holding digits,
        //       upper-case letters, spaces or the money mask's own punctuation.
        int control = indexOfControlCharacter(payload);
        if (control >= 0) {
            throw new AuthMessageFormatException("the " + payloadName + " payload carries a control"
                    + " character at zero-based position " + control + "; every field this contract"
                    + " declares is a display picture, so no control character is representable in"
                    + " one");
        }

        // WHY : Assumptions: the payload length and the scan position are separate, differently named
        //       values here on purpose. Holding a one-based cursor and a length in one variable is
        //       exactly what produced the reference program's extra byte: WS-RESP-LENGTH is the
        //       STRING cursor at line 730 of COPAUA0C.cbl and is then used as the put's length at
        //       lines 756 and 762. Naming them apart makes that conflation unavailable here.
        final int payloadLength = payload.length();

        // WHY : Alternatives Considered: String.split with a negative limit produces the same tokens
        //       in one line and was rejected on two counts. It compiles its argument as a regular
        //       expression, so the delimiter would become a pattern and a future change to a
        //       character that happens to be a metacharacter would silently stop matching it; and it
        //       offers nowhere to name the payload length and the scan position apart, which is the
        //       separation this method exists to keep. A growable collection would avoid the first
        //       pass and would need a type this class deliberately does not depend on.
        int delimiterCount = 0;
        for (int scanPosition = 0; scanPosition < payloadLength; scanPosition++) {
            if (payload.charAt(scanPosition) == DELIMITER) {
                delimiterCount++;
            }
        }

        String[] tokens = new String[delimiterCount + 1];
        int tokenIndex = 0;
        int fieldStart = 0;
        for (int scanPosition = 0; scanPosition < payloadLength; scanPosition++) {
            if (payload.charAt(scanPosition) == DELIMITER) {
                tokens[tokenIndex] = payload.substring(fieldStart, scanPosition);
                tokenIndex++;
                fieldStart = scanPosition + 1;
            }
        }
        tokens[tokenIndex] = payload.substring(fieldStart, payloadLength);

        List<String> fields = List.of(tokens);

        // WHY : Trade-offs: one excess token is accepted when it holds nothing but pad, and no other
        //       excess is. This is the tolerant half of the strict-encode, tolerant-decode discipline
        //       and it is narrower than it first looks. It admits the reply's canonical trailing
        //       comma, which yields an empty seventh token, and the 64-byte form the put actually
        //       sends, whose seventh token is a single space from the 200-byte buffer at line 108 of
        //       COPAUA0C.cbl. It rejects a genuine extra field, so the tolerance cannot be mistaken
        //       for accepting a seven-field reply or a nineteen-field request. Symmetric strictness
        //       was rejected because it would reject every reply the reference program emits.
        if (fields.size() == expectedFieldCount + 1 && isPadOnly(fields.get(expectedFieldCount))) {
            return fields.subList(0, expectedFieldCount);
        }

        if (fields.size() != expectedFieldCount) {
            throw new AuthMessageFormatException("the " + payloadName + " payload carries "
                    + fields.size() + " delimited fields but the copybook declares "
                    + expectedFieldCount + "; field order and the delimiter are the whole of this"
                    + " contract, so a differing count cannot be reconciled by position");
        }

        return fields;
    }

    /**
     * Establishes that every character token reached the width its copybook line declares.
     *
     * <p>Assumptions: an upper bound is only half of a fixed-width contract, so the lower bound is
     * asserted here while {@link #characterField(String, String, int)} keeps the upper one. Without this
     * half, a payload one byte short of its declared length splits into the right number of fields, every
     * token fits, and the LAST token silently loses its final character -- on a request that token is
     * {@code PA-RQ-TRANSACTION-ID}, the value the migrated messaging design uses as the deduplication
     * identifier and as half of the correlation identity. The consequences compound rather than
     * announcing themselves: the shortened value is re-emitted at the full declared width, so the
     * corruption is indistinguishable from a well-formed payload; the correlation token changes, so a
     * reply carrying the original identifier does not match; and two identifiers differing only in their
     * last character collapse onto one, so a genuinely distinct authorization can be discarded as a
     * duplicate.</p>
     *
     * <p>Assumptions: only the SHORT direction is reported here, and the long direction stays where it
     * was. Rejecting both here would duplicate a check that already exists and would replace its
     * wording, and the two directions are not symmetric in their cause: an over-width token is a
     * producer emitting a value its field cannot hold, while an under-width token is a payload that
     * lost bytes in transit. Together the two bounds make the width an equality, and because every
     * field is emitted at its declared width, that equality is also what makes the total payload
     * length equal {@link #REQUEST_WIRE_LENGTH} or {@link #REPLY_WIRE_LENGTH} without a second check
     * over the whole payload.</p>
     *
     * <p>Trade-offs: the money ordinal is exempt, and that exemption is narrow rather than
     * convenient. The reference reply mask {@code PIC -zzzzzzzzz9.99} suppresses leading zeros, so a
     * legitimate reply amount does not occupy its declared fourteen positions, and the transport may
     * pad around the token; {@link #parseMoney(String, String)} therefore accepts a zero-suppressed or
     * pad-extended token and validates the geometry INSIDE the value instead -- at most ten integer
     * digits, one literal point, exactly two fraction digits. That inner geometry is what refuses a
     * truncated amount, which is why a short money token cannot change a value silently and a short
     * text token could.</p>
     *
     * <p>Assumptions: this runs after the field-count and control-character checks in
     * {@link #splitOnDelimiter(String, int, String)} rather than before them, so a payload with the
     * wrong number of fields is still reported as a field-count failure and a payload carrying a
     * control character is still reported by position. A width complaint about a token that only
     * exists because a delimiter was lost would send a reader to the wrong field.</p>
     *
     * @param fields the tokens in wire order, exactly as many as the copybook declares; must not be
     *     {@code null}
     * @param widths the declared width of each field, positionally aligned with {@code fields}
     * @param names the copybook name of each field, positionally aligned with {@code fields}
     * @param amountOrdinal the zero-based ordinal of the money field, which is exempt from the width
     *     requirement for the reason recorded above
     * @param payloadName the payload's name, used to compose a failure message
     * @throws AuthMessageFormatException if any character token is shorter than its declared width;
     *     the message carries the value only for a field outside {@link #SENSITIVE_FIELD_NAMES}, as
     *     {@link #fieldFailure(String, String, CharSequence)} records
     */
    private static void requireDeclaredTokenWidths(List<String> fields, List<Integer> widths,
            List<String> names, int amountOrdinal, String payloadName) {
        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
            if (fieldIndex == amountOrdinal) {
                continue;
            }

            String token = fields.get(fieldIndex);
            int declaredWidth = widths.get(fieldIndex);
            if (token.length() < declaredWidth) {
                throw fieldFailure(names.get(fieldIndex), "arrived in the " + payloadName
                        + " payload as " + token.length() + " characters but the copybook declares "
                        + declaredWidth + "; every field is padded out to its declared width on this"
                        + " wire, so a shorter token means the payload lost bytes and the value cannot"
                        + " be reconstructed by padding it back", token);
            }
        }
    }

    /**
     * Reads the leading bytes of a transport buffer as payload text.
     *
     * <p>Assumptions: the payload length is taken from the caller and never from the buffer, because
     * the two are routinely different. The reference program declares
     * {@code 01 W01-GET-BUFFER PIC X(500).} at line 103 of {@code COPAUA0C.cbl}, sets
     * {@code W01-BUFFLEN} to that whole length at line 398, and then parses only
     * {@code W01-GET-BUFFER(1:W01-DATALEN)} at line 354. A codec that inferred the payload from the
     * buffer would read 500 bytes of which most are pad.</p>
     *
     * <p>Assumptions: the decoding is single-byte, so a declared character width is also a byte
     * width. Alternatives Considered: UTF-8 was evaluated and rejected. One non-ASCII byte in the
     * 22-character merchant name at line 32 of {@code CCPAURQY.cpy} would either combine with its
     * neighbour into one character or decode to a replacement character, and in both cases a field's
     * character count would stop matching its byte count and the payload would miss its declared wire
     * length while every field still read plausibly as text.</p>
     *
     * @param buffer the transport buffer; must not be {@code null}
     * @param payloadLength the number of leading bytes that constitute the payload
     * @param payloadName the payload's name, used to compose a failure message
     * @return the payload as text, exactly {@code payloadLength} characters long
     * @throws NullPointerException if {@code buffer} is {@code null}
     * @throws AuthMessageFormatException if {@code payloadLength} is negative or greater than the
     *     buffer's length
     */
    private static String payloadText(byte[] buffer, int payloadLength, String payloadName) {
        if (buffer == null) {
            throw new NullPointerException(payloadName + " buffer must not be null");
        }
        if (payloadLength < 0 || payloadLength > buffer.length) {
            throw new AuthMessageFormatException("the " + payloadName + " payload length is "
                    + payloadLength + ", which is outside the " + buffer.length
                    + "-byte buffer that carries it");
        }

        // WHY : Assumptions: the byte bound is checked on the LENGTH ARGUMENT before the bytes are
        //       decoded, because decoding allocates a string proportional to it while the buffer
        //       itself arrived from a queue. The character bound in splitOnDelimiter would catch the
        //       same payload one allocation later; catching it here is what keeps an oversized frame
        //       from being materialised at all.
        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new AuthMessageFormatException("the " + payloadName + " payload length is "
                    + payloadLength + " bytes, beyond the " + MAX_PAYLOAD_LENGTH
                    + " this contract admits");
        }

        return new String(buffer, 0, payloadLength, StandardCharsets.ISO_8859_1);
    }

    /**
     * Finds the first control character in a payload.
     *
     * <p>Assumptions: the platform's ISO-control predicate is used rather than a comparison against the
     * space character. A numeric cutoff at space would classify the delete character and the whole
     * upper control range as acceptable, and those are precisely the characters a log viewer or a
     * terminal interprets rather than displays.</p>
     *
     * @param payload the payload to scan
     * @return the zero-based position of the first control character, or -1 when there is none
     */
    private static int indexOfControlCharacter(String payload) {
        for (int position = 0; position < payload.length(); position++) {
            if (Character.isISOControl(payload.charAt(position))) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Encodes an assembled payload as the bytes the transport carries.
     *
     * <p>Assumptions: the encoding is the single-byte counterpart of the decoding, for the reason
     * recorded on {@code payloadText}. Because that encoding maps every character it can represent to
     * exactly one byte, the returned array's length equals the payload's character length, which is
     * what lets a caller pass the declared wire length as the message length without measuring the
     * array.</p>
     *
     * @param payload the assembled payload text
     * @return the payload bytes, one byte per character
     * @throws AuthMessageFormatException if the encoded array is not one byte per character, which
     *     can only mean a character escaped the representability check at the field boundary
     */
    private static byte[] toWireBytes(String payload) {
        byte[] wire = payload.getBytes(StandardCharsets.ISO_8859_1);

        // WHY : Assumptions: this post-condition protects a future edit rather than the current
        //       caller. Every character reaching here has already been established as representable
        //       in the single-byte wire encoding by characterField, so the array length is determined
        //       by the payload length alone. What it would catch is a value that entered by some
        //       later route without passing that check -- the exact shape of the defect that emitted
        //       a 168-byte request, where a supplementary code point counted as two characters and
        //       encoded as one byte. Length is the one property of the encoding a caller relies on,
        //       because the declared wire length is passed to the transport as the message length
        //       without measuring the array, so a length mismatch has to be loud here rather than
        //       arriving at a consumer as a payload whose fields have all shifted.
        if (wire.length != payload.length()) {
            throw new AuthMessageFormatException("the assembled payload is " + payload.length()
                    + " characters but encodes to " + wire.length + " bytes; this wire is single-byte"
                    + " throughout, so the two must agree and a value that is not representable in it"
                    + " must be refused at the field rather than substituted here");
        }

        return wire;
    }

    /**
     * Normalises and validates one character field against the width its copybook line declares.
     *
     * <p>Assumptions: only trailing pad is removed, never leading pad. A COBOL alphanumeric move
     * left-justifies its source and pads on the right, so on this wire the pad is always trailing and
     * a leading space is data. Stripping both ends would silently alter a value whose first character
     * is genuinely a space.</p>
     *
     * @param value the field value as supplied or as received; must not be {@code null}
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @param declaredWidth the width the copybook line declares for this field
     * @return the value with its trailing pad removed
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws AuthMessageFormatException if the value contains the delimiter, or is longer than
     *     {@code declaredWidth} once its trailing pad is removed; the message carries the value only
     *     for a field outside {@link #SENSITIVE_FIELD_NAMES}, as
     *     {@link #fieldFailure(String, String, CharSequence)} records
     */
    private static String characterField(String value, String cobolName, int declaredWidth) {
        if (value == null) {
            throw new NullPointerException(cobolName + " must not be null");
        }

        String normalised = stripTrailingPad(value);

        // WHY : Assumptions: an embedded delimiter is unrepresentable rather than merely awkward.
        //       Nothing in the baseline quotes or escapes a field: the UNSTRING at lines 354 to 355
        //       of COPAUA0C.cbl splits on every comma it meets, so one comma inside a merchant name
        //       would be read as a field boundary and would shift every value after it by one
        //       position. Rejecting it where the value enters is the only place the offending field
        //       is still identifiable.
        if (normalised.indexOf(DELIMITER) >= 0) {
            throw fieldFailure(cobolName, "contains the '" + DELIMITER + "' delimiter at position "
                    + (normalised.indexOf(DELIMITER) + 1) + ", which this payload has no way to quote"
                    + " or escape", normalised);
        }

        if (normalised.length() > declaredWidth) {
            throw fieldFailure(cobolName, "is " + normalised.length() + " characters but the copybook"
                    + " declares " + declaredWidth, normalised);
        }

        // WHY : Refactoring Rationale: a control character is refused HERE, at the field, and this
        //       check closes a genuine round-trip hole rather than adding a new rule. The decode path
        //       already refuses any control character in a whole payload, in splitOnDelimiter, on the
        //       ground that a carriage return or line feed inside a value forges or splits a record in
        //       any line-oriented log the value later reaches and that an escape sequence reaching a
        //       terminal is interpreted rather than displayed. The encode path did not, so this class
        //       would EMIT a payload it then REFUSED TO READ: encodeRequest returned a full-length
        //       payload and decodeRequest threw on it. An encoder whose output its own decoder rejects
        //       is broken whichever of the two is right, and the decode side is the one that is right.
        // WHY : Assumptions: the platform's ISO-control predicate is used rather than a comparison
        //       against the space character, matching indexOfControlCharacter exactly. A numeric cutoff
        //       at space would admit the delete character and the whole upper control range, which are
        //       precisely the characters a log viewer or a terminal interprets rather than displays.
        //       Trade-offs: refusing at the field costs one scan per field instead of one per payload,
        //       and buys a diagnostic that names the field -- which the payload-level check cannot,
        //       because by then the value is one substring among eighteen.
        for (int position = 0; position < normalised.length(); position++) {
            if (Character.isISOControl(normalised.charAt(position))) {
                throw fieldFailure(cobolName, "carries a control character at position "
                        + (position + 1) + "; every field this contract declares is a display picture,"
                        + " so no control character is representable in one, and the decoder refuses a"
                        + " payload carrying one", normalised);
            }
        }

        // WHY : Refactoring Rationale: a character outside the wire's single-byte range used to be
        //       accepted here and was then replaced with a question mark by the encoder, which is a
        //       silent value change no caller could detect. The surrogate case was worse than lossy:
        //       a supplementary code point is two Java characters but encodes to ONE substitute byte,
        //       so the emitted payload came out a byte short of its declared length, every field
        //       after it shifted by one position, and the under-width token that produced was then
        //       absorbed silently as well. Refusing the character where the field ENTERS is what
        //       makes the failure name the field, and it closes both the encode path and the
        //       byte-emitting path with one check.
        int unrepresentable = indexOfUnrepresentableCharacter(normalised);
        if (unrepresentable >= 0) {
            throw fieldFailure(cobolName, "carries a character at zero-based position "
                    + unrepresentable + " that the single-byte wire character set cannot represent;"
                    + " every field this contract declares is a display picture, so a value outside"
                    + " the " + WIRE_CHARACTER_RANGE_DESCRIPTION + " has no encoding on this wire and"
                    + " substituting one would change the value silently", null);
        }

        return normalised;
    }

    /**
     * Finds the first character a single-byte wire encoding cannot represent.
     *
     * <p>Assumptions: the wire encoding is ISO-8859-1, which maps the code points U+0000 through
     * U+00FF onto the byte values 0x00 through 0xFF one for one and can represent nothing else. The
     * representable set is therefore exactly a numeric range, and a comparison against its upper
     * bound is an exact test rather than an approximation of one. The test also catches a lone or
     * paired surrogate, because every surrogate code unit lies above the bound.</p>
     *
     * <p>Alternatives Considered: a {@code CharsetEncoder} configured to report an unmappable
     * character, which is the general form of this test and is what a multi-byte charset would
     * require. It was not adopted for two reasons that both matter here: it reports a position in the
     * encoder's own input buffer rather than in the field, so a diagnostic built from it could not
     * name the field's own offset, and it needs an encoder instance per call because an encoder is
     * stateful and not safe to share. The numeric bound is exact for this charset and stays a pure
     * function.</p>
     *
     * @param value the field value to scan, already normalised of its trailing pad
     * @return the zero-based position of the first character above the wire range, or -1 when every
     *     character is representable
     */
    private static int indexOfUnrepresentableCharacter(String value) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) > WIRE_CHARACTER_MAXIMUM) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Establishes that a monetary component was supplied.
     *
     * @param amount the amount to check; must not be {@code null}
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @return the amount unchanged
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    private static Money requireAmount(Money amount, String cobolName) {
        if (amount == null) {
            throw new NullPointerException(cobolName + " must not be null");
        }

        return amount;
    }

    /**
     * Builds the correlation key from a card number and a transaction identifier.
     *
     * <p>Assumptions: each part is padded back to its declared width rather than concatenated as
     * stored, so the key is positional and its two halves cannot run together. Concatenating the
     * trimmed values would make two distinct pairs collide whenever one card number were a prefix of
     * another, which is the failure a positional key exists to prevent.</p>
     *
     * @param cardNum the card number, already within {@code CARD_NUM_WIDTH} characters
     * @param cardNumName the copybook name of the card number field on the carrier that asked, used
     *     only to compose a failure message
     * @param transactionId the transaction identifier, already within
     *     {@code TRANSACTION_ID_WIDTH} characters
     * @param transactionIdName the copybook name of the transaction identifier field on the carrier
     *     that asked, used only to compose a failure message
     * @return the plaintext positional composite, exactly {@link #CORRELATION_COMPOSITE_LENGTH}
     *     characters long; this is the value a correlation token is derived from and is never
     *     published, so it is not {@link #CORRELATION_TOKEN_LENGTH} characters
     * @throws AuthMessageFormatException if either part is longer than its declared width
     */
    private static String buildCorrelationKey(String cardNum, String cardNumName,
            String transactionId, String transactionIdName) {
        // WHY : Assumptions: the two field names are supplied by the caller rather than written here
        //       because the same key is built from two differently named pairs -- PA-RQ-CARD-NUM with
        //       PA-RQ-TRANSACTION-ID at lines 21 and 36 of CCPAURQY.cpy, and PA-RL-CARD-NUM with
        //       PA-RL-TRANSACTION-ID at lines 19 and 20 of CCPAURLY.cpy. A message naming the reply's
        //       fields for a request would send a reader to the wrong copybook.
        return padToWidth(cardNum, CARD_NUM_WIDTH, cardNumName)
                + padToWidth(transactionId, TRANSACTION_ID_WIDTH, transactionIdName);
    }

    /**
     * Pads a field value on the right to the width its copybook line declares.
     *
     * @param value the field value, already within {@code declaredWidth} characters
     * @param declaredWidth the width the copybook line declares for this field
     * @param cobolName the copybook name of the field, used to compose a failure message
     * @return the value followed by enough pad characters to reach {@code declaredWidth}
     * @throws AuthMessageFormatException if the value is longer than {@code declaredWidth}; the
     *     message carries the value only for a field outside {@link #SENSITIVE_FIELD_NAMES}, as
     *     {@link #fieldFailure(String, String, CharSequence)} records
     */
    private static String padToWidth(String value, int declaredWidth, String cobolName) {
        if (value.length() > declaredWidth) {
            throw fieldFailure(cobolName, "is " + value.length() + " characters but the copybook"
                    + " declares " + declaredWidth, value);
        }

        StringBuilder padded = new StringBuilder(declaredWidth);
        padded.append(value);
        while (padded.length() < declaredWidth) {
            padded.append(PAD);
        }

        return padded.toString();
    }

    /**
     * Removes the trailing pad characters from a field value.
     *
     * @param value the field value as supplied or as received
     * @return the value with every trailing pad character removed, which may be empty
     */
    private static String stripTrailingPad(String value) {
        // WHY : Assumptions: only the ASCII space is treated as pad, rather than every character
        //       Java's own trailing-strip considers whitespace. DELIMITED BY SIZE at line 728 of
        //       COPAUA0C.cbl contributes the unused positions of a PIC X field, and those positions
        //       hold spaces; a tab or a line feed inside a field would be data, and removing it would
        //       change a value rather than remove its padding.
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == PAD) {
            end--;
        }

        return value.substring(0, end);
    }

    /**
     * Removes the leading pad characters from a field value.
     *
     * @param value the field value, or the portion of it that follows a sign position
     * @return the value with every leading pad character removed, which may be empty
     */
    private static String stripLeadingPad(String value) {
        // WHY : Assumptions: this exists as its own operation rather than reusing the surrounding-pad
        //       form because the caller has already consumed the sign position, so the run to remove is
        //       the zero-suppression pad that sits between that position and the first digit. Reusing the
        //       surrounding form there would additionally strip the token's trailing end, which for a
        //       money token holds the two mandatory decimal digits and must never be shortened.
        int start = 0;
        while (start < value.length() && value.charAt(start) == PAD) {
            start++;
        }

        return value.substring(start);
    }

    /**
     * Removes the leading and trailing pad characters from a field value.
     *
     * @param value the field value as received
     * @return the value with pad characters removed from both ends, which may be empty
     */
    private static String stripSurroundingPad(String value) {
        // WHY : Assumptions: the money token is the one field stripped at both ends, because the
        //       rendering the reference program emits leaves pad on the left. WS-APPROVED-AMT-DIS is
        //       PIC -zzzzzzzzz9.99 at line 66 of COPAUA0C.cbl, and zero suppression replaces each
        //       leading zero with a space, so a small positive amount arrives right-justified inside
        //       its fourteen positions with nothing in the sign position at all.
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == PAD) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == PAD) {
            end--;
        }

        return value.substring(start, end);
    }

    /**
     * Reports whether a token holds nothing but pad characters.
     *
     * @param value the token to inspect
     * @return {@code true} when the token is empty or consists only of pad characters
     */
    private static boolean isPadOnly(String value) {
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != PAD) {
                return false;
            }
        }

        return true;
    }

    /**
     * Establishes that every character of a digit run is an ASCII digit.
     *
     * @param digits the run of characters that the picture declares as digit positions
     * @param fieldName the copybook name of the field being parsed, used for the failure message
     * @param token the whole token as received, quoted in the failure message so the offending value
     *     is visible rather than only the run it was taken from
     * @throws AuthMessageFormatException if any character of {@code digits} is not an ASCII digit
     */
    private static void requireAsciiDigits(String digits, String fieldName, String token) {
        for (int position = 0; position < digits.length(); position++) {
            char digit = digits.charAt(position);
            if (!isAsciiDigit(digit)) {
                // WHY : Assumptions: the offending character and its POSITION are named, and the token
                //       is offered to the gate rather than concatenated here, so a sensitive field
                //       reports where the defect is without reproducing the value it is in.
                throw fieldFailure(fieldName, "carries '" + digit + "' at digit position "
                        + (position + 1) + ", where the picture declares a digit", token);
            }
        }
    }

    /**
     * Builds the exception for one violated field contract, quoting content only where that is
     * permitted.
     *
     * <p>Assumptions: content reaches this method through the {@code content} parameter alone and never
     * inside {@code problem}. Every caller observes that split, so {@code problem} carries only the
     * constraint, the geometry and this class's own constants while {@code content} carries the value.
     * That is what makes the suppression complete rather than partial: there is exactly one channel to
     * gate, and gating it here means no caller can leak a value by forgetting to. The same structure is
     * used by this package's zoned-decimal codec, deliberately, so that a reader who has understood one
     * has understood both.</p>
     *
     * @param cobolName the copybook name of the offending field, always stated because a name is not
     *     content and a diagnostic that omits it is unusable
     * @param problem the constraint that was breached, phrased in terms of the copybook's declared
     *     widths and this class's constants, and never containing field content
     * @param content the value that breached the constraint, or {@code null} when there is none to
     *     quote; omitted from the message when {@code cobolName} names a sensitive field
     * @return the exception to raise, which the caller throws so that the raise site stays visible
     */
    private static AuthMessageFormatException fieldFailure(String cobolName, String problem,
            CharSequence content) {
        StringBuilder message = new StringBuilder(cobolName).append(' ').append(problem);
        if (content != null && !isSensitive(cobolName)) {
            message.append("; value was '").append(content).append('\'');
        }
        return new AuthMessageFormatException(message.toString());
    }

    /**
     * Reports whether a field's content must be withheld from diagnostics.
     *
     * @param cobolName the copybook name of the field, which may be {@code null} when a caller has no
     *     field identity to hand
     * @return {@code true} when the field is named in {@link #SENSITIVE_FIELD_NAMES}, and also when the
     *     name is {@code null} -- an unidentified field is treated as sensitive, because a codec that
     *     cannot say which field it is looking at cannot establish that the value is safe to quote
     */
    private static boolean isSensitive(String cobolName) {
        return cobolName == null || SENSITIVE_FIELD_NAMES.contains(cobolName);
    }

    /**
     * Reports whether a character is one of the ten ASCII digits.
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is in the range {@code '0'} to {@code '9'}
     */
    private static boolean isAsciiDigit(char candidate) {
        // WHY : Assumptions: an explicit ASCII range test is used rather than Character.isDigit,
        //       which accepts every Unicode decimal digit including the Arabic-Indic and Devanagari
        //       forms. None of those is representable in the single-byte encoding this payload uses,
        //       so accepting one here would let a token pass validation and then encode to a byte
        //       that no reader of this contract can interpret as a digit.
        return candidate >= ZERO_DIGIT && candidate <= '9';
    }
}
