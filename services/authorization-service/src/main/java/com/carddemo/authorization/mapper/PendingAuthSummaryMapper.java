package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryResponse;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.PageResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries the 100-byte pending-authorization summary segment to the entity and to the summary screen.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the crossing point between the stored form of the pending-authorization root segment
 * and its Java forms. It decodes the segment image into named field values and into a typed carrier,
 * encodes a carrier back into an image that is byte-identical to the one it came from, reads the
 * unprefixed unload parent record the reference unload program writes, splits the segment's five-slot
 * status array into the five discrete members the schema declares, and projects a stored summary and a
 * page of authorization rows onto the {@link PendingAuthSummaryResponse} the summary screen is specified
 * by.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static holder with a private
 * constructor, so the type itself accepts no parameter, yields no value and raises nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader has
 * to be able to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: THREE numeric encodings coexist inside this one record</h2>
 *
 * <p>This is the single fact that governs every line below, and it is the one a reader is most likely to
 * generalise away. {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} declares thirteen
 * components across L19 to L31, and its numerics are written three different ways:</p>
 *
 * <ul>
 *   <li><b>Packed decimal, seven fields.</b> The account key at L19 is {@code PIC S9(11) COMP-3} and
 *       occupies SIX bytes; the six money fields at L23 to L26 and again at L29 to L30 are each
 *       {@code PIC S9(09)V99 COMP-3} and occupy SIX bytes apiece. The width rule is the ceiling of the
 *       digit count plus one sign nibble, halved: eleven digits and twelve digits both reach six bytes.
 *       Note that the two money fields are NOT one contiguous group -- the two counters are declared
 *       between them -- so a reader summing "four amounts then two counters" and stopping has missed
 *       twelve bytes.</li>
 *   <li><b>Binary halfwords, two fields.</b> The two counters at L27 and L28 are
 *       {@code PIC S9(04) COMP} and occupy TWO bytes each, most significant byte first, two's
 *       complement. Four decimal digits or fewer means a halfword; five to nine means a fullword.</li>
 *   <li><b>Zoned display, one field.</b> The customer identifier at L20 is {@code PIC 9(09)} and
 *       occupies NINE bytes, one character per digit. It is UNSIGNED, so no sign overpunch applies to
 *       it at all; a reader looking for the overpunch alphabet that {@code ZonedDecimalCodec} carries
 *       will not find a sign nibble here because the picture declares none.</li>
 * </ul>
 *
 * <p>Assumptions: what each wrong generalisation produces is worth stating, because neither failure
 * raises anything. Reading the two counters as packed decimal interprets a two-byte two's complement
 * quantity as digit nibbles with a trailing sign nibble, which yields a small plausible integer that is
 * simply wrong -- the canonical fixture's approved count of 42 is stored {@code 0x002A}, and a packed
 * reading of those bytes finds digits 0, 0, 2 and a sign nibble of {@code 0xA}, an alternate positive
 * sign that does not even fail. Reading the customer identifier as binary consumes nine bytes of ASCII
 * digits as though they were an integer. Every other field in the record decodes correctly in both
 * cases, which is exactly what makes the defect expensive to find.</p>
 *
 * <h2>Assumptions: the geometry is read from the registry and is never restated here</h2>
 *
 * <p>The layout is registered once, as {@code CopybookLayout.layout("PAUTSUM0")}, and this class holds
 * no offset and no field width of its own. That registry entry already classifies each field into one of
 * the three regimes above, so the distinction is applied by the codec rather than re-derived here. The
 * closed byte ledger it transcribes is:</p>
 *
 * <pre>
 *   offset  0   PA-ACCT-ID             S9(11) COMP-3   L19    6 bytes
 *   offset  6   PA-CUST-ID             9(09)  DISPLAY  L20    9 bytes
 *   offset 15   PA-AUTH-STATUS         X(01)           L21    1 byte
 *   offset 16   PA-ACCOUNT-STATUS      X(02) OCCURS 5  L22   10 bytes
 *   offset 26   PA-CREDIT-LIMIT        S9(09)V99 C-3   L23    6 bytes
 *   offset 32   PA-CASH-LIMIT          S9(09)V99 C-3   L24    6 bytes
 *   offset 38   PA-CREDIT-BALANCE      S9(09)V99 C-3   L25    6 bytes
 *   offset 44   PA-CASH-BALANCE        S9(09)V99 C-3   L26    6 bytes
 *   offset 50   PA-APPROVED-AUTH-CNT   S9(04) COMP     L27    2 bytes
 *   offset 52   PA-DECLINED-AUTH-CNT   S9(04) COMP     L28    2 bytes
 *   offset 54   PA-APPROVED-AUTH-AMT   S9(09)V99 C-3   L29    6 bytes
 *   offset 60   PA-DECLINED-AUTH-AMT   S9(09)V99 C-3   L30    6 bytes
 *   offset 66   FILLER                 X(34)           L31   34 bytes
 *                                                     total 100 bytes
 * </pre>
 *
 * <p>Assumptions: the total is corroborated by a source that is not the copybook, which is why it can be
 * trusted as a check rather than as a restatement. {@code ims/DBPAUTP0.dbd} L28 declares
 * {@code SEGM NAME=PAUTSUM0,PARENT=0,BYTES=100}. Two independent declarations agreeing on 100 is the
 * cheapest available proof that all three width rules were applied correctly, because getting any one of
 * them wrong changes the total: sizing the counters as packed would give 102, and sizing the customer
 * identifier as a fullword would give 95. This class therefore recomputes the ledger from the three
 * width rules at class initialisation and refuses to load if it does not close.</p>
 *
 * <h2>Assumptions: this segment carries NO nines complement</h2>
 *
 * <p>A reader arriving from {@link PendingAuthDetailMapper} will be looking for one, so its absence is
 * stated plainly rather than left to be inferred. The root's sequence field is declared at
 * {@code ims/DBPAUTP0.dbd} L30 as {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} -- a PACKED
 * unique sequence field over the first six bytes, which is the account key itself. The child's is
 * declared at L37 as {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} -- a CHARACTER sequence
 * over its eight packed key bytes. Only the character-typed field needed inverting, because an ascending
 * character sequence is the only way the newest occurrence could be read first under its parent. An
 * account identifier has no newest-first requirement at all, so nothing about this key is complemented
 * and nothing here decodes one.</p>
 *
 * <h2>Assumptions: the primary key is one column, and both identifiers widen to BIGINT</h2>
 *
 * <p>{@code PendingAuthSummary} and the migration key this table on {@code account_id} ALONE, matching
 * the single root sequence field above. {@code PA-ACCT-ID} becomes {@code account_id BIGINT} and
 * {@code PA-CUST-ID} becomes {@code customer_id BIGINT}, following the migration plan's rule that a
 * {@code PIC 9(n)} used as a key or an identifier becomes {@code BIGINT} in SQL and {@code Long} in
 * Java. Neither is money, so neither carries a scale.</p>
 *
 * <h2>Assumptions: {@code auth_status} carries no CHECK constraint, and that is a decision</h2>
 *
 * <p>{@code cpy/CIPAUSMY.cpy} L21 declares {@code PA-AUTH-STATUS PIC X(01)} and declares no
 * {@code 88}-level condition names beneath it. The migration therefore declares {@code auth_status
 * CHAR(1)} with no CHECK, and this class passes the character through unvalidated. The contrast makes
 * the point: {@code match_status} and {@code auth_fraud} DO carry check constraints, and both are
 * promoted from {@code 88}-level value sets that the DETAIL copybook declares at
 * {@code cpy/CIPAUDTY.cpy} L46 to L49 and L51 to L52. Where the baseline closes a domain, the target
 * closes the column; where it does not, inventing one would refuse a value the reference application
 * stores, which is a behavioural change rather than a tightening. The absence is recorded here so it
 * reads as a finding and not as an oversight.</p>
 *
 * <h2>Assumptions: this projection publishes no account number at all</h2>
 *
 * <p>The narrowing this package applies is worth recording here even though this class has nothing to
 * mask, because a reader comparing the two screens will notice that one publishes a card number and the
 * other does not. Three properties of the reference application establish why the narrowing exists, and
 * all three are stated as facts about that application rather than as faults in it -- the migration adds
 * a path, it does not remove one:</p>
 *
 * <ul>
 *   <li>{@code csd/CRDDEMO2.csd} enables diagnostics on all three of this context's transactions,
 *       {@code DUMP(YES) TRACE(YES)} at L44, L54 and L64, while classifying their payloads as
 *       non-confidential with {@code CONFDATA(NO)} at L45, L55 and L65, so a primary account number
 *       could reach trace output and a transaction dump.</li>
 *   <li>{@code cpy-bms/COPAU01.cpy} L60 declares {@code 02 CARDNUMI PIC X(16)}, all sixteen digits of
 *       the number, on the DETAIL screen.</li>
 *   <li>The same definitions carry {@code RESSEC(NO) CMDSEC(NO)} at L46, L56 and L66, so no
 *       resource-level or command-level check stood in front of either screen.</li>
 * </ul>
 *
 * <p>Assumptions: the summary map declares NO card-number component, so this projection has none to
 * publish and none to mask -- {@code cpy-bms/COPAU00.cpy} contains no such field anywhere among its
 * sixty-two components, and the card number reaches the operator only on the detail screen above.
 * Publishing one here would break the component count this class is verified against. Where a number IS
 * published, the mask belongs to this package rather than to the codec, so that a field cannot escape
 * masking by being serialised from somewhere else; the sibling detail mapper applies it, and no
 * verification value is returned by any endpoint in this context.</p>
 *
 * <h2>Trade-offs: the per-field render rules are consumed, not restated</h2>
 *
 * <p>The date re-ordering, the time re-spacing and the account-number mask are defined once for this
 * package by {@link PendingAuthDetailMapper}, and the approved-or-declined character by
 * {@link PendingAuthViewMapper#approvalStatusOf(String)}. All four are called from here rather than
 * reimplemented. The compromise accepted is package-private coupling between mappers instead of four
 * self-contained classes. What it buys is exactly one definition per representation concern, which is
 * the property the package charter beside this file exists to hold: two definitions of a date
 * re-ordering inside one package would be two places it could be got wrong, and a divergence between
 * them would surface as two endpoints disagreeing about the same authorization rather than as a
 * failure anywhere.</p>
 *
 * <h2>Alternatives Considered: a generated mapper, and generated accessors</h2>
 *
 * <p>MapStruct was evaluated for this class and rejected. Its most recent published release is a beta,
 * and more decisively the mapping here is not mechanical: it splits one {@code OCCURS} interval into
 * five members by position, distinguishes three numeric encodings inside a single record, drops a
 * trailing padding field on the way in and restores it on the way out, masks a primary account number,
 * and flattens a nested screen structure into sixty-two ordered components whose order carries a
 * documented irregularity. Every one of those needs a justification recorded at the site where the
 * decision is applied, and a generated mapper has nowhere to carry one. Lombok was evaluated and
 * rejected on the same ground: generated accessors cannot hold the Javadoc user-specified Rule 1
 * requires, whereas a Java 21 {@code record} with an explicit component list gives the same brevity
 * with documentable members.</p>
 *
 * <h2>Assumptions: no golden master exists for any path through this module</h2>
 *
 * <p>Parity here rests on the copybook, the database description and the transcribed logic, and not on
 * a recorded reference run, so the limitation is stated rather than implied. {@code tests/README.md}
 * records at its lines 83 to 85 that the online {@code CO*} programs cannot be run end to end without a
 * CICS runtime, which is absent from the runner. The authorization request producer is not supplied by
 * the baseline at all. Consequently the assertions available to this class are contract assertions --
 * the declared widths, the declared segment length, the declared value domains, and a byte-identical
 * round trip through the committed segment fixtures -- and no claim of golden-master parity is made for
 * it.</p>
 *
 * @see PendingAuthDetailMapper
 * @see PendingAuthViewMapper
 */
public final class PendingAuthSummaryMapper {

    /** The registry name under which the summary segment's geometry is declared. */
    static final String SEGMENT_LAYOUT_NAME = "PAUTSUM0";

    /** The number of slots the account-status array declares at {@code cpy/CIPAUSMY.cpy} L22. */
    static final int ACCOUNT_STATUS_SLOTS = 5;

    /** The declared width in characters of one account-status slot, from {@code PIC X(02)}. */
    static final int ACCOUNT_STATUS_SLOT_WIDTH = 2;

    /** The integer digit count of every money field in this segment, from {@code PIC S9(09)V99}. */
    static final int MONEY_INTEGER_DIGITS = 9;

    /** The decimal digit count of every money field in this segment, from {@code PIC S9(09)V99}. */
    static final int MONEY_DECIMAL_DIGITS = 2;

    /** The digit count of both authorization counters, from {@code PIC S9(04) COMP} at L27 and L28. */
    static final int COUNTER_DIGITS = 4;

    /** The digit count of the packed account key, from {@code PIC S9(11) COMP-3} at L19. */
    static final int ACCOUNT_KEY_DIGITS = 11;

    /** The digit count of the zoned customer identifier, from {@code PIC 9(09)} at L20. */
    static final int CUSTOMER_ID_DIGITS = 9;

    /** The declared width of the single-character authorization status at L21. */
    static final int AUTH_STATUS_WIDTH = 1;

    /** The declared width of the trailing padding field at {@code cpy/CIPAUSMY.cpy} L31. */
    static final int FILLER_WIDTH = 34;

    /**
     * The number of authorization rows the summary screen displays at once.
     *
     * <p>Assumptions: five is the arity of the screen's row group and not a page size chosen here. The
     * symbolic map {@code cpy-bms/COPAU00.cpy} declares eight components for each of five rows, and
     * {@code cbl/COPAUS0C.cbl} dispatches on a row index whose {@code EVALUATE} at L542 carries exactly
     * five {@code WHEN} branches. A page longer than five has nowhere to be displayed, so it is refused
     * rather than truncated.</p>
     */
    static final int SCREEN_ROW_CAPACITY = 5;

    /**
     * The declared width in characters of the screen's message component.
     *
     * <p>Assumptions: this is SEVENTY-EIGHT, from {@code 02 ERRMSGI PIC X(78)} at
     * {@code cpy-bms/COPAU00.cpy} L390, and it is neither of the two other widths this bounded context
     * uses. It is not the house 75-character error contract that {@code CCARD-ERROR-MSG} and
     * {@code CCARD-RETURN-MSG} declare, and it is not the 50-character fraud-action regime. Three widths
     * coexist here, so the one this class honours is named explicitly to stop a reader normalising them
     * onto each other. Structured HTTP error bodies remain the shared kernel's problem shape and are not
     * this component.</p>
     */
    static final int MESSAGE_WIDTH = 78;

    /** The copybook name of the packed account key, the segment's root sequence field. */
    private static final String FIELD_ACCT_ID = "PA-ACCT-ID";

    /** The copybook name of the zoned customer identifier. */
    private static final String FIELD_CUST_ID = "PA-CUST-ID";

    /** The copybook name of the single-character authorization status. */
    private static final String FIELD_AUTH_STATUS = "PA-AUTH-STATUS";

    /** The copybook name of the five-slot account-status array, declared as one interval. */
    private static final String FIELD_ACCOUNT_STATUS = "PA-ACCOUNT-STATUS";

    /** The copybook name of the mirrored account credit limit. */
    private static final String FIELD_CREDIT_LIMIT = "PA-CREDIT-LIMIT";

    /** The copybook name of the mirrored account cash credit limit. */
    private static final String FIELD_CASH_LIMIT = "PA-CASH-LIMIT";

    /** The copybook name of the running authorized-but-unposted credit balance. */
    private static final String FIELD_CREDIT_BALANCE = "PA-CREDIT-BALANCE";

    /** The copybook name of the cash balance. */
    private static final String FIELD_CASH_BALANCE = "PA-CASH-BALANCE";

    /** The copybook name of the approved authorization counter, a binary halfword. */
    private static final String FIELD_APPROVED_COUNT = "PA-APPROVED-AUTH-CNT";

    /** The copybook name of the declined authorization counter, a binary halfword. */
    private static final String FIELD_DECLINED_COUNT = "PA-DECLINED-AUTH-CNT";

    /** The copybook name of the approved authorization amount total. */
    private static final String FIELD_APPROVED_AMOUNT = "PA-APPROVED-AUTH-AMT";

    /** The copybook name of the declined authorization amount total. */
    private static final String FIELD_DECLINED_AMOUNT = "PA-DECLINED-AUTH-AMT";

    /** The copybook name of the trailing padding field. */
    private static final String FIELD_FILLER = "FILLER";

    /**
     * The registered geometry of the summary segment, proven to close at its declared length.
     *
     * <p>Assumptions: the registry instance is bound here rather than a locally built descriptor, and the
     * identity matters beyond tidiness. The shared codec treats a text {@code FILLER} as droppable
     * padding only when the descriptor it is handed is the very instance the registry holds, so passing a
     * private copy would silently disable the padding rule and leave inert bytes in every decoded map.
     * Binding the registry instance is what makes the drop and the restore described at
     * {@link #toSegmentFields(byte[])} actually happen.</p>
     */
    private static final CopybookLayout.RecordSpec SEGMENT =
            CopybookLayout.layout(SEGMENT_LAYOUT_NAME);

    static {
        // WHY : Assumptions: the ledger is recomputed from the three width rules rather than asserted as
        //       a comment, because a comment cannot fail. Each term below is produced by the rule for its
        //       own regime -- the packed rule for the key and the six amounts, the binary rule for the two
        //       counters, and one byte per digit for the zoned identifier -- so a regime applied with the
        //       wrong rule changes the sum and this class refuses to initialise. Sizing the counters as
        //       packed yields 102 and sizing the identifier as a fullword yields 95, and either would
        //       otherwise surface as plausible wrong numbers in two fields with nothing raised.
        int ledger = PackedDecimalCodec.packedWidth(ACCOUNT_KEY_DIGITS, 0)
                + CUSTOMER_ID_DIGITS
                + AUTH_STATUS_WIDTH
                + ACCOUNT_STATUS_SLOTS * ACCOUNT_STATUS_SLOT_WIDTH
                + 6 * PackedDecimalCodec.packedWidth(MONEY_INTEGER_DIGITS, MONEY_DECIMAL_DIGITS)
                + 2 * PackedDecimalCodec.binaryWidth(COUNTER_DIGITS, 0)
                + FILLER_WIDTH;
        if (ledger != SEGMENT.reclen()) {
            throw new IllegalStateException("the summary segment ledger totals " + ledger
                    + " bytes but " + SEGMENT_LAYOUT_NAME + " is declared as " + SEGMENT.reclen()
                    + "; one of the three numeric regimes is being sized with the wrong rule");
        }
    }

    /**
     * Prevents instantiation of a type that holds no state.
     *
     * <p>Alternatives Considered: publishing this mapper as an injectable component with instance
     * methods, which is how the sibling view mapper is published. Rejected here because that mapper needs
     * a collaborator -- the cursor signer -- whereas every operation below is a pure function of a byte
     * array or of values already read from the database. Injecting a dependency-free component would make
     * a decode reachable only from a managed context, and the load and unload paths that need it run as
     * plain batch steps.</p>
     */
    private PendingAuthSummaryMapper() {
        // Assumptions: unreachable by design. A static holder with no state has nothing an instance
        //   could carry, and a private constructor states that rather than leaving a default one that
        //   invites an instance nobody needs.
    }

    /**
     * One decoded summary segment, with the status array split and the padding dropped.
     *
     * <p>Purpose. This is the complete-fidelity Java form of the segment: every component the copybook
     * declares as data survives onto it, decoded out of whichever of the three numeric regimes held it,
     * and the encode direction restores the identical bytes from it. It exists because the persistent
     * aggregate deliberately cannot carry an arbitrary stored state -- see {@link #toEntity(byte[])} --
     * so a load, an unload or a verification pass needs a carrier that can.</p>
     *
     * <p>Alternatives Considered: mapping the five-slot array to a single collection component, or to a
     * PostgreSQL {@code CHAR(2)[]} array column behind a list. Rejected because the arity of five is
     * declared by {@code cpy/CIPAUSMY.cpy} L22 as {@code PIC X(02) OCCURS 5 TIMES}, and five discrete
     * members let the schema itself assert it: the migration declares {@code account_status_1} through
     * {@code account_status_5}, so a sixth slot has no column to land in and a fourth cannot go missing.
     * With an array column the arity would instead become an application-layer invariant, enforced only
     * by whatever code happened to build the list, and the JPA mapping would need a vendor-specific
     * array type in place of five portable character columns. That matters because the arity is the
     * thing a defect here would break, and a constraint the database holds cannot be bypassed by a
     * caller that forgot it.</p>
     *
     * <p>Assumptions: the slots map by POSITION and never by content. Occurrence one becomes
     * {@code accountStatus1} and occurrence five becomes {@code accountStatus5}, and the encode direction
     * writes each back into the interval it came from, which is what makes the round trip byte-identical.
     * Two slots holding the same two characters are therefore not interchangeable, and nothing here
     * sorts, de-duplicates or compacts them.</p>
     *
     * <p>Assumptions: money is carried as the shared kernel's fixed-point type and never as a binary
     * floating-point value. Each amount is bounded at the nine integer digits
     * {@code PIC S9(09)V99 COMP-3} declares, so a value too wide for its column is refused at the decode
     * that introduced it rather than at a later insert. The two counters are carried as {@code Short},
     * matching the {@code SMALLINT} column that the four-digit {@code PIC S9(04) COMP} picture maps
     * onto.</p>
     *
     * @param accountId the decoded packed account key, the segment's root sequence field; never
     *     {@code null} on a carrier produced by a decode
     * @param customerId the decoded zoned customer identifier; never {@code null} on a decoded carrier
     * @param authStatus the single authorization status character, passed through with no value domain
     *     imposed, or {@code null} when the field is blank
     * @param accountStatus1 the first two-character account-status slot, or {@code null} when blank
     * @param accountStatus2 the second account-status slot, or {@code null} when blank
     * @param accountStatus3 the third account-status slot, or {@code null} when blank
     * @param accountStatus4 the fourth account-status slot, or {@code null} when blank
     * @param accountStatus5 the fifth account-status slot, or {@code null} when blank
     * @param creditLimit the credit limit mirrored onto the segment from the account master
     * @param cashLimit the cash credit limit mirrored onto the segment from the account master
     * @param creditBalance the authorized-but-unposted credit balance, which may be negative
     * @param cashBalance the cash balance
     * @param approvedAuthCount the approved authorization count decoded from a binary halfword
     * @param declinedAuthCount the declined authorization count decoded from a binary halfword
     * @param approvedAuthAmount the running total of approved authorization amounts
     * @param declinedAuthAmount the running total of declined authorization amounts
     */
    public record SummarySegment(
            Long accountId,
            Long customerId,
            String authStatus,
            String accountStatus1,
            String accountStatus2,
            String accountStatus3,
            String accountStatus4,
            String accountStatus5,
            Money creditLimit,
            Money cashLimit,
            Money creditBalance,
            Money cashBalance,
            Short approvedAuthCount,
            Short declinedAuthCount,
            Money approvedAuthAmount,
            Money declinedAuthAmount) {

        /**
         * Reads one account-status slot by its one-based occurrence number.
         *
         * <p>Assumptions: the accessor is one-based because the copybook's own subscript is. Reading
         * {@code accountStatusSlot(1)} for the first occurrence keeps every call site aligned with the
         * {@code OCCURS 5 TIMES} numbering and with the {@code account_status_1} column name, so no
         * caller has to hold an off-by-one conversion in its head. Zero-basing it would leave the Java
         * and the SQL disagreeing about which slot is which by exactly one.</p>
         *
         * @param occurrence the one-based slot number, from one to five
         * @return the two characters held in that slot, or {@code null} when the slot is blank
         * @throws IllegalArgumentException if {@code occurrence} is outside one to five, which names a
         *     slot the array does not declare
         */
        public String accountStatusSlot(int occurrence) {
            return switch (occurrence) {
                case 1 -> this.accountStatus1;
                case 2 -> this.accountStatus2;
                case 3 -> this.accountStatus3;
                case 4 -> this.accountStatus4;
                case 5 -> this.accountStatus5;
                default -> throw new IllegalArgumentException("account status occurrence must be between"
                        + " 1 and " + ACCOUNT_STATUS_SLOTS + " but was " + occurrence);
            };
        }
    }

    /**
     * The six screen components that no stored segment holds.
     *
     * <p>Purpose. The summary map opens with six components of screen chrome, and every one of them is
     * supplied by the running program rather than read from data. Grouping them into one parameter keeps
     * the response projection's signature readable and makes their common provenance explicit: a reader
     * looking for where a title comes from finds the answer in one place instead of six.</p>
     *
     * <p>Assumptions: each component is populated by {@code cbl/COPAUS0C.cbl} from a program constant or
     * from the clock, never from the segment. Its L731 moves the first title line and L732 the second,
     * both from the shared title copybook; L733 moves the transaction identifier and L734 the program
     * name; L740 moves the current date already rendered month-first and L746 the current time already
     * rendered with colons. They are carried here as text for that reason -- they arrive rendered, and
     * re-deriving them would substitute this service's clock for the value the caller resolved.</p>
     *
     * @param transactionName the transaction identifier the screen displays, from L733
     * @param title01 the first title line, from L731
     * @param currentDate the current date as already-rendered characters, from L740
     * @param programName the name of the program serving the screen, from L734
     * @param title02 the second title line, from L732
     * @param currentTime the current time as already-rendered characters, from L746
     */
    public record ScreenChrome(
            String transactionName,
            String title01,
            String currentDate,
            String programName,
            String title02,
            String currentTime) {
    }

    /**
     * The five cardholder components the summary screen shows that the summary segment does not store.
     *
     * <p>Purpose. Five of the map's context components describe the cardholder rather than the pending
     * authorizations, and the reference program reads them from the customer master rather than from the
     * segment. They are grouped so the response projection cannot be called without the caller having
     * decided where they came from.</p>
     *
     * <p>Assumptions: the provenance is the customer record, established at {@code cbl/COPAUS0C.cbl}
     * L763 for the composed name, L769 and L776 for the two composed address lines, and L779 for the
     * first telephone number. Two of those are STRING compositions of several customer fields rather
     * than single moves, which is why they are accepted here as finished text: recomposing them would
     * put this bounded context in the business of formatting another context's data.</p>
     *
     * <p>Assumptions: the account-status component is carried but is NOT populated by the reference
     * program. The map declares {@code ACCSTATI PIC X(1)} at {@code cpy-bms/COPAU00.cpy} L84, and
     * {@code cbl/COPAUS0C.cbl} moves nothing into it anywhere -- neither the input nor the output alias
     * appears in that program. The component is preserved so the projection matches the map one for one,
     * and it is passed through exactly as supplied rather than defaulted, because inventing a value for
     * a field the baseline leaves untouched would put content on a screen that never showed any.</p>
     *
     * @param customerName the composed cardholder name, from L763
     * @param addressLine1 the first composed address line, from L769
     * @param accountStatus the single account-status character, which the reference program never
     *     populates and which is therefore passed through as supplied, including {@code null}
     * @param addressLine2 the second composed address line, from L776
     * @param phoneNumber1 the cardholder's first telephone number, from L779
     */
    public record CardholderContext(
            String customerName,
            String addressLine1,
            String accountStatus,
            String addressLine2,
            String phoneNumber1) {
    }

    /**
     * Returns the declared length of one summary segment image.
     *
     * @return the segment length in bytes, one hundred, taken from the registered layout rather than
     *     from a local constant so it cannot drift from the geometry the codec uses
     */
    public static int segmentLength() {
        return SEGMENT.reclen();
    }

    /**
     * Returns the declared length of one unload parent record.
     *
     * <p>Assumptions: this equals the segment length, and the equality is the point rather than a
     * coincidence. {@code cbl/PAUDBUNL.CBL} L44 declares the parent output record as
     * {@code 01 OPFIL1-REC PIC X(100)} -- the segment verbatim, with no key prefix -- and its L227 moves
     * the whole segment into it in one statement. The child record is shaped differently on purpose: L45
     * to L48 declare {@code OPFIL2-REC} as a {@code PIC S9(11) COMP-3} parent key occupying six bytes
     * ahead of a {@code PIC X(200)} child image, giving 206 bytes, and L230 populates that prefix from
     * the parent's own key. The asymmetry has a reason worth recording: a child segment does not carry
     * its parent's key, so an unloaded child would be unattributable without the prefix, whereas the
     * parent's first six bytes already ARE its key. That shape belongs to {@link PendingAuthDetailMapper}
     * and nothing here reads a prefix.</p>
     *
     * @return the unload parent record length in bytes, which is the segment length
     */
    public static int unloadRecordLength() {
        return SEGMENT.reclen();
    }

    /**
     * Decodes one segment image into its named field values.
     *
     * <p>Purpose. This is the complete-fidelity decode: every declared data component appears in the
     * returned map under its exact copybook name, in declaration order, and the map is the input the
     * byte-identical encode takes back. It is the path a load, an unload or a verification pass uses,
     * because it can represent any state a stored segment is in.</p>
     *
     * <p>Assumptions: the three numeric regimes are distinguished by the registered layout and applied by
     * the shared codec, not re-derived here. The registry classifies the account key and the six amounts
     * as packed, the two counters as binary and the customer identifier as unsigned display, so the
     * decoded value types differ by field: packed and binary fields arrive as {@code BigDecimal}, the
     * unsigned display field as {@code Long}, and character fields as {@code String}. A caller reading
     * the map directly has to respect that; {@link #toSummarySegment(byte[])} exists so most callers do
     * not have to.</p>
     *
     * <p>Assumptions: the trailing padding at {@code cpy/CIPAUSMY.cpy} L31 is dropped when it is blank
     * and RETAINED when it is not, and this class relies on the shared codec for both halves rather than
     * handling padding itself. The distinction matters because a {@code FILLER} is not always padding: a
     * {@code FILLER} carrying a {@code VALUE} clause is content, since the literal IS the data -- the
     * 23-character authorization timestamp this context composes elsewhere contains a
     * {@code FILLER X(03) VALUE '000'} that must survive. This one carries no {@code VALUE}, so its
     * thirty-four bytes are padding to the declared segment length and carry nothing; the baseline's own
     * relational side sets the same precedent by omitting its filler from the fraud table definition
     * entirely. The encode direction restores the interval, so the physical width is preserved either
     * way and the round trip is byte-identical in both cases.</p>
     *
     * @param segment the segment image to decode; must not be {@code null} and must be exactly the
     *     declared segment length
     * @return a mutable, declaration-ordered map of copybook field name to decoded value, from which a
     *     blank padding field is absent
     * @throws NullPointerException if {@code segment} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length, a truncated image being refused outright rather than decoded partially,
     *     because a plausible partial map can hide a shifted money field
     * @throws PackedDecimalCodec.PackedDecimalException if a packed field carries a digit nibble outside
     *     zero to nine, a sign position holding a digit rather than a sign, or a non-zero leading pad
     *     nibble on an odd digit count
     */
    public static Map<String, Object> toSegmentFields(byte[] segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        return FixedWidthCodec.decodeRecord(segment, SEGMENT);
    }

    /**
     * Decodes one segment image into the typed carrier, splitting the status array by position.
     *
     * <p>Assumptions: the five-slot array is decoded by the codec as ONE ten-byte character interval,
     * because {@code cpy/CIPAUSMY.cpy} L22 declares {@code PIC X(02) OCCURS 5 TIMES} as a single field
     * and the registry registers it that way. Splitting it into the five members the schema declares is
     * therefore this class's own work, and it is done arithmetically from the slot width so the five
     * intervals cannot drift apart. The reason the split happens at all is recorded on
     * {@link SummarySegment}.</p>
     *
     * @param segment the segment image to decode; must not be {@code null} and must be exactly the
     *     declared segment length
     * @return the decoded carrier with the status array split into five positional members and the
     *     padding field absent
     * @throws NullPointerException if {@code segment} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length
     * @throws PackedDecimalCodec.PackedDecimalException if a packed field carries a malformed digit or
     *     sign nibble
     * @throws ArithmeticException if a decoded amount needs more integer digits than the nine its
     *     picture declares, or if a decoded counter lies outside the range a {@code SMALLINT} column
     *     accepts
     */
    public static SummarySegment toSummarySegment(byte[] segment) {
        Map<String, Object> fields = toSegmentFields(segment);
        String statusArray = text(fields, FIELD_ACCOUNT_STATUS);
        return new SummarySegment(
                identifier(fields, FIELD_ACCT_ID),
                identifier(fields, FIELD_CUST_ID),
                text(fields, FIELD_AUTH_STATUS),
                statusSlot(statusArray, 1),
                statusSlot(statusArray, 2),
                statusSlot(statusArray, 3),
                statusSlot(statusArray, 4),
                statusSlot(statusArray, 5),
                amount(fields, FIELD_CREDIT_LIMIT),
                amount(fields, FIELD_CASH_LIMIT),
                amount(fields, FIELD_CREDIT_BALANCE),
                amount(fields, FIELD_CASH_BALANCE),
                counter(fields, FIELD_APPROVED_COUNT),
                counter(fields, FIELD_DECLINED_COUNT),
                amount(fields, FIELD_APPROVED_AMOUNT),
                amount(fields, FIELD_DECLINED_AMOUNT));
    }

    /**
     * Decodes one unload parent record into the typed carrier.
     *
     * <p>Assumptions: the record IS the segment, so this reads no key prefix and skips no leading bytes.
     * {@code cbl/PAUDBUNL.CBL} L227 writes it with a single {@code MOVE PENDING-AUTH-SUMMARY TO
     * OPFIL1-REC} into the {@code PIC X(100)} record its L44 declares. This entry point exists as a named
     * method rather than leaving callers to notice that the two shapes coincide, because the sibling
     * child record does NOT coincide -- it carries six prefix bytes -- and a reader who generalised from
     * the child to the parent would skip six bytes of a hundred and shift every field after them. The
     * reason for that asymmetry is recorded on {@link #unloadRecordLength()}.</p>
     *
     * @param unloadRecord the unload parent record to decode; must not be {@code null} and must be
     *     exactly the declared record length
     * @return the decoded carrier, identical to what {@link #toSummarySegment(byte[])} yields for the
     *     same bytes
     * @throws NullPointerException if {@code unloadRecord} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if {@code unloadRecord} is not exactly the declared
     *     record length
     * @throws PackedDecimalCodec.PackedDecimalException if a packed field carries a malformed digit or
     *     sign nibble
     * @throws ArithmeticException if a decoded amount or counter lies outside the range its declared
     *     picture admits
     */
    public static SummarySegment fromUnloadRecord(byte[] unloadRecord) {
        Objects.requireNonNull(unloadRecord, "unloadRecord must not be null");
        return toSummarySegment(unloadRecord);
    }

    /**
     * Encodes a decoded carrier back into one segment image.
     *
     * <p>Assumptions: the five status members are written back into the intervals they came from, in the
     * same positions, which is what makes a decode followed by this encode an identity. The padding
     * interval is restored as blanks by the shared codec, so the result is exactly the declared segment
     * length even though the carrier holds no padding member.</p>
     *
     * <p>Trade-offs: this form does not reproduce two things the map-based sign-preserving form does, and
     * both are named because a byte comparison that fails on either looks like a decode defect and is
     * not. A packed amount whose stored sign nibble was the negative one and whose digits were all zero
     * re-encodes with the positive nibble, because negative zero and positive zero are the same quantity
     * and only the bytes differ. And a record whose padding held anything other than blanks re-encodes
     * with blanks, because the carrier has no member in which to keep the difference. Where byte identity
     * against a specific source image is what is being asserted, use
     * {@link #toSegment(java.util.Map, byte[])} with the map that decode produced.</p>
     *
     * @param decoded the carrier to encode; must not be {@code null}, and both identifiers must be
     *     present because the key column and the customer column are declared not null
     * @return a newly allocated image of exactly the declared segment length
     * @throws NullPointerException if {@code decoded} is {@code null}, or if either identifier is absent
     * @throws FixedWidthCodec.FieldCodecException if a value the carrier holds does not fit its declared
     *     interval, which includes a status slot wider than two characters and an amount wider than its
     *     picture
     * @throws PackedDecimalCodec.PackedDecimalException if an amount or a counter cannot be represented
     *     in the digits its picture declares
     */
    public static byte[] toSegment(SummarySegment decoded) {
        Objects.requireNonNull(decoded, "decoded must not be null");
        return toSegment(segmentFieldsOf(decoded));
    }

    /**
     * Encodes named field values back into one segment image.
     *
     * <p>Assumptions: a field the map omits is restored by the shared codec, which is how a dropped blank
     * padding interval comes back at its declared width. That is relied upon rather than reimplemented
     * here, so the drop rule and the restore rule stay one decision in one place.</p>
     *
     * @param fields the values keyed by exact copybook field name; must not be {@code null}
     * @return a newly allocated image of exactly the declared segment length
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws FixedWidthCodec.FieldCodecException if a key is not a declared field name, if a required
     *     value is absent, or if a value does not fit its declared interval
     * @throws PackedDecimalCodec.PackedDecimalException if a numeric value cannot be represented in the
     *     digits its picture declares
     */
    public static byte[] toSegment(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        return FixedWidthCodec.encodeRecord(fields, SEGMENT);
    }

    /**
     * Re-encodes named field values byte-exactly against the image they were decoded from.
     *
     * <p>Assumptions: one class of byte difference is not recoverable from decoded values alone, which is
     * the whole reason this overload exists. A packed field stores its sign in the low nibble of its last
     * byte, and both {@code 0xC} and {@code 0xD} are legitimate there; when every digit is zero the two
     * describe the same quantity, so a decode cannot tell them apart and the ordinary encoder writes the
     * positive nibble. Handing the source image back lets the codec carry the original nibble through, so
     * a stored negative zero rewrites to the bytes it came from. The committed
     * {@code pautsum0-negative-zero-decode-only.bin} fixture is exactly this case, and its name records
     * that a plain round trip through it is not an identity.</p>
     *
     * <p>Alternatives Considered: making this the only encode, so every caller got byte identity by
     * default. Rejected because it requires a source image, and the load path that writes a segment
     * assembled from database columns has none -- there is no earlier image to preserve a nibble from.
     * Two entry points let each caller state which property it needs instead of one signature carrying a
     * nullable argument whose meaning changes the result.</p>
     *
     * @param fields the values keyed by exact copybook field name; must not be {@code null}
     * @param decodedFrom the image these values were decoded from, supplying the sign carriers; must not
     *     be {@code null} and must be exactly the declared segment length
     * @return a newly allocated image of exactly the declared segment length, byte-identical to
     *     {@code decodedFrom} when {@code fields} is that image's unmodified decode
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if {@code decodedFrom} is absent or is not exactly
     *     the declared segment length
     * @throws FixedWidthCodec.FieldCodecException if a key is not a declared field name, if a required
     *     value is absent, or if a value does not fit its declared interval
     * @throws PackedDecimalCodec.PackedDecimalException if a numeric value cannot be represented in the
     *     digits its picture declares
     */
    public static byte[] toSegment(Map<String, Object> fields, byte[] decodedFrom) {
        Objects.requireNonNull(fields, "fields must not be null");
        return FixedWidthCodec.encodeRecordPreservingSign(fields, SEGMENT, decodedFrom);
    }

    /**
     * Decodes a newly created segment into the persistent aggregate, refusing any populated one.
     *
     * <p>Purpose. This carries the one segment state the aggregate can faithfully represent: the state a
     * root segment is in when it has just been created. {@code cbl/COPAUA0C.cbl} L801 to L806 is that
     * state exactly -- the reference program zeroes every numeric field of the segment and then moves in
     * the account and customer identifiers from the cross-reference row, and nothing else -- and L810 to
     * L811 then copy the account's two limits in, which is what this method reproduces through
     * {@code refreshLimits}.</p>
     *
     * <p>Alternatives Considered: returning a partially populated aggregate for ANY segment, filling in
     * the two identifiers and the two limits and quietly discarding the rest. Rejected because the
     * aggregate exposes no way to set the authorization status, the five status slots, either balance,
     * either counter or either running total -- deliberately, so that a caller cannot move a counter and
     * forget the balance that belongs with it. Populating four of sixteen components and returning the
     * object anyway would discard twelve, and a caller that persisted the result would overwrite a real
     * balance and a real counter in the database with a constructor's zero. Nothing would raise, and the
     * row would remain perfectly well-formed, which is precisely the plausible-but-wrong failure class
     * this whole class is written to prevent. Refusing is louder and cheaper than a silent overwrite.</p>
     *
     * <p>Trade-offs: the consequence accepted is that this entry point does not serve a bulk load, which
     * has to reconstitute arbitrary stored states. That path uses {@link #toSummarySegment(byte[])} or
     * {@link #toSegmentFields(byte[])} instead and reaches the database through the columns those
     * carriers fill, while the aggregate stays reachable only through the decision operations that own
     * its invariants. What the split buys is that every mutation of a persisted summary still goes
     * through the arithmetic the reference program performs, rather than through a mapper that could
     * assemble a state no sequence of authorizations could produce.</p>
     *
     * @param segment the segment image to decode; must not be {@code null} and must be exactly the
     *     declared segment length
     * @return a summary aggregate carrying the two identifiers and the two mirrored limits
     * @throws NullPointerException if {@code segment} is {@code null}, or if the image carries no account
     *     or customer identifier, neither of which a stored row may omit
     * @throws FixedWidthCodec.RecordLengthException if {@code segment} is not exactly the declared
     *     segment length
     * @throws PackedDecimalCodec.PackedDecimalException if a packed field carries a malformed digit or
     *     sign nibble
     * @throws IllegalArgumentException if the segment carries any balance, counter, total, status
     *     character or status slot the aggregate cannot represent; the refusal names every such component
     *     so the caller can see which state was rejected without reading the bytes
     */
    public static PendingAuthSummary toEntity(byte[] segment) {
        SummarySegment decoded = toSummarySegment(segment);
        requireAggregateRepresentable(decoded);

        PendingAuthSummary summary = new PendingAuthSummary(
                Objects.requireNonNull(decoded.accountId(),
                        "the segment carries no account identifier, which a stored row may not omit"),
                Objects.requireNonNull(decoded.customerId(),
                        "the segment carries no customer identifier, which a stored row may not omit"));

        // WHY : Assumptions: the two limits are applied through the aggregate's own refresh operation
        //       rather than at construction, because that is where the reference program applies them --
        //       cbl/COPAUA0C.cbl L810 and L811 copy them on EVERY authorization and not only when the
        //       segment is created. Routing through the same operation keeps one code path for a limit
        //       reaching this segment, whichever direction it arrived from.
        summary.refreshLimits(amountOrZero(decoded.creditLimit()), amountOrZero(decoded.cashLimit()));
        return summary;
    }

    /**
     * Projects a stored summary and one page of authorization rows onto the summary screen's response.
     *
     * <p>Purpose. The response reproduces the symbolic map {@code cpy-bms/COPAU00.cpy} component for
     * component: six components of screen chrome, fifteen of account and cardholder context, forty of row
     * data across five rows of eight, and one message line, in the map's own declaration order.</p>
     *
     * <p>Trade-offs: the row components are FLAT rather than nested. Five sub-records of eight components
     * would read more tidily and would reduce the top level from sixty-two components to twenty-seven, and
     * that reduction is the reason the flat shape was kept: sixty-two is a number that can be checked
     * against the map by counting field components in the copybook, and twenty-seven cannot be checked
     * against anything. The compromise accepted is a wide constructor in exchange for a component count
     * that is falsifiable against the source.</p>
     *
     * <p>Assumptions: row five's two final components are declared in an IRREGULAR order and the
     * irregularity is preserved. Rows one through four each LEAD with their selection component --
     * {@code SEL0001I} at {@code cpy-bms/COPAU00.cpy} L150 precedes its row's data -- whereas row five's
     * {@code SEL0005I} is declared at L384, AFTER {@code PAMT005I} at L378. Normalising row five into line
     * with the other four would move two components and is not done, because the declaration order IS the
     * contract that the response's component order was derived from. A reader who assumes regularity here
     * silently transposes a one-character selection and a twelve-character amount.</p>
     *
     * <p>Assumptions: each row's amount is the APPROVED amount and never the requested one.
     * {@code cbl/COPAUS0C.cbl} L522 opens the row-population paragraph and its L525 is
     * {@code MOVE PA-APPROVED-AMT TO WS-AUTH-AMT}; the detail segment declares both
     * {@code PA-TRANSACTION-AMT} at {@code cpy/CIPAUDTY.cpy} L34 and {@code PA-APPROVED-AMT} at L35, and
     * they differ on a declined authorization, where an amount was requested and nothing was approved.
     * Publishing the requested amount would therefore show a declined authorization as though it had gone
     * through -- money that reads as entirely reasonable and is wrong. The rows arriving here already
     * carry the approved amount, chosen at that same line by the sibling view mapper.</p>
     *
     * <p>Assumptions: the two limits published here are the ACCOUNT's, mirrored onto the segment rather
     * than owned by it. {@code cbl/COPAUS0C.cbl} L780 to L783 move the account master's credit limit and
     * cash credit limit onto the screen, while its L788 to L799 take the two counters, the two balances
     * and the two totals from the segment, and its L800 to L806 substitute zero for those six when no
     * segment exists. Reading the limits from the aggregate is faithful because the aggregate mirrors them
     * from the account on every authorization; the provenance is recorded so a reader does not conclude
     * that this segment is the system of record for a limit.</p>
     *
     * <p>Alternatives Considered: publishing a page number and a row offset so a client could ask for the
     * next page arithmetically. Rejected because the reference application browses by key and the target
     * preserves that: under a concurrent insert, offset paging skips a row or shows one twice, whereas a
     * key-positioned read does neither. This projection therefore carries no paging component at all and
     * no row key of its own -- the keyset envelope stays the shared kernel's page type, which the caller
     * holds and which this class neither re-declares nor unwraps beyond reading the rows out of it. The
     * baseline's own browse state lived in a communication-area extension that the migration removes
     * entirely, so there is no page number to carry forward even if one were wanted.</p>
     *
     * <p>Assumptions: each row's selection marker and its opaque selector are different components with
     * different jobs. The marker is the one character the terminal displayed in the selection column; the
     * selector is the sealed token a client sends back to open that row, and it arrives already sealed on
     * the row view because sealing needs key material that belongs to the service layer and must not
     * reach a mapper. This class reads the token rather than minting one, so there is exactly one sealer
     * in the module.</p>
     *
     * @param summary the stored summary whose counters, balances, totals and identifiers are published;
     *     must not be {@code null}
     * @param page the keyset page of authorization rows to display, whose items must not exceed the five
     *     the screen holds; must not be {@code null}
     * @param chrome the six screen components no segment stores; must not be {@code null}
     * @param cardholder the five cardholder components the summary segment does not store; must not be
     *     {@code null}
     * @param message the message line to display, or {@code null} when the screen reports nothing
     * @return the fully populated summary response
     * @throws NullPointerException if {@code summary}, {@code page}, {@code chrome} or
     *     {@code cardholder} is {@code null}
     * @throws IllegalArgumentException if {@code page} carries more than five rows, which the screen has
     *     nowhere to display, or if {@code message} is longer than the seventy-eight positions the map's
     *     message component declares, or if a row's approval or match character lies outside the value
     *     domain its column admits, or if a row's selector is present and is not a sealed token
     */
    public static PendingAuthSummaryResponse toResponse(PendingAuthSummary summary,
            PageResponse<PendingAuthRowView> page, ScreenChrome chrome,
            CardholderContext cardholder, String message) {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(chrome, "chrome must not be null");
        Objects.requireNonNull(cardholder, "cardholder must not be null");

        List<PendingAuthRowView> rows = page.items();
        if (rows.size() > SCREEN_ROW_CAPACITY) {
            // WHY : Assumptions: an over-long page is refused rather than trimmed. The screen holds five
            //       rows and the reference program's row dispatch at cbl/COPAUS0C.cbl L542 carries five
            //       branches, so a sixth row has no component to occupy; silently dropping it would
            //       publish a page that claims to be complete while omitting an authorization, and the
            //       caller would have no way to detect the omission. The refusal names both counts so the
            //       defect is located in the query's page limit, which is where it is.
            throw new IllegalArgumentException("a summary page holds at most " + SCREEN_ROW_CAPACITY
                    + " rows because the screen declares five, but " + rows.size() + " were supplied");
        }

        PendingAuthRowView row1 = rowAt(rows, 0);
        PendingAuthRowView row2 = rowAt(rows, 1);
        PendingAuthRowView row3 = rowAt(rows, 2);
        PendingAuthRowView row4 = rowAt(rows, 3);
        PendingAuthRowView row5 = rowAt(rows, 4);

        return new PendingAuthSummaryResponse(
                chrome.transactionName(),
                chrome.title01(),
                chrome.currentDate(),
                chrome.programName(),
                chrome.title02(),
                chrome.currentTime(),
                digits(summary.getAccountId()),
                cardholder.customerName(),
                digits(summary.getCustomerId()),
                cardholder.addressLine1(),
                cardholder.accountStatus(),
                cardholder.addressLine2(),
                cardholder.phoneNumber1(),
                count(summary.getApprovedAuthCount()),
                count(summary.getDeclinedAuthCount()),
                money(summary.getCreditLimit()),
                money(summary.getCashLimit()),
                money(summary.getApprovedAuthAmount()),
                money(summary.getCreditBalance()),
                money(summary.getCashBalance()),
                money(summary.getDeclinedAuthAmount()),
                // WHY : Assumptions: every selection marker is published unset. The marker is the position
                //       the operator TYPES a mark into, which cbl/COPAUS0C.cbl L286 reads and L287 moves
                //       into its selection flag on the way back in; outbound the reference program writes
                //       no character there and only changes the field's attribute byte, leaving it
                //       protected at L614 and making it enterable at L554 once the row is populated. A
                //       response payload has no attribute byte, so which rows are addressable is carried
                //       by the sealed selector being present instead, and putting a character in the
                //       marker would show the operator a mark nobody made.
                null,
                transactionIdOf(row1),
                authDateOf(row1),
                authTimeOf(row1),
                authTypeOf(row1),
                approvalOf(row1),
                matchOf(row1),
                amountOf(row1),
                null,
                transactionIdOf(row2),
                authDateOf(row2),
                authTimeOf(row2),
                authTypeOf(row2),
                approvalOf(row2),
                matchOf(row2),
                amountOf(row2),
                null,
                transactionIdOf(row3),
                authDateOf(row3),
                authTimeOf(row3),
                authTypeOf(row3),
                approvalOf(row3),
                matchOf(row3),
                amountOf(row3),
                null,
                transactionIdOf(row4),
                authDateOf(row4),
                authTimeOf(row4),
                authTypeOf(row4),
                approvalOf(row4),
                matchOf(row4),
                amountOf(row4),
                // WHY : Assumptions: row five omits its selection component here and supplies it AFTER
                //       its amount, two lines below. That is not a transcription slip: cpy-bms/COPAU00.cpy
                //       declares SEL0005I at L384, after PAMT005I at L378, while rows one to four lead
                //       with their selection component. The response's component order was derived from
                //       that declaration order, so the call has to follow it.
                transactionIdOf(row5),
                authDateOf(row5),
                authTimeOf(row5),
                authTypeOf(row5),
                approvalOf(row5),
                matchOf(row5),
                amountOf(row5),
                null,
                message,
                selectorOf(row1),
                selectorOf(row2),
                selectorOf(row3),
                selectorOf(row4),
                selectorOf(row5));
    }

    /**
     * Refuses a segment whose stored state the persistent aggregate cannot represent.
     *
     * <p>Assumptions: the aggregate models the authorization decision lifecycle and exposes no way to
     * assign a balance, a counter, a total or a status directly, so the only stored state it can
     * faithfully hold is the one a freshly created segment is in. Every component checked below is one the
     * aggregate would otherwise drop, and the check is on the value rather than on a flag because a
     * freshly created segment has a determinate shape: {@code cbl/COPAUA0C.cbl} L801 to L806 zeroes every
     * numeric field before moving the two identifiers in. A zero counter and an exactly-zero total are
     * therefore representable; anything else is not.</p>
     *
     * <p>Trade-offs: the two limits are deliberately absent from this check even though they are also
     * populated state. They are the only components the aggregate DOES admit after construction, through
     * its own refresh operation, so admitting them costs nothing and refusing them would reject the very
     * segment {@code cbl/COPAUA0C.cbl} L810 to L811 produces one statement later.</p>
     *
     * <p><strong>Return value.</strong> This guard returns no value; normal completion means every
     * component the aggregate cannot carry is at the value a newly created segment holds.</p>
     *
     * @param decoded the decoded carrier to inspect; must not be {@code null}
     * @throws IllegalArgumentException if any inspected component is populated, listing every component
     *     at fault so the caller sees the whole reason rather than the first one
     */
    private static void requireAggregateRepresentable(SummarySegment decoded) {
        StringBuilder populated = new StringBuilder();
        appendIfPresent(populated, FIELD_AUTH_STATUS, decoded.authStatus() != null);
        for (int slot = 1; slot <= ACCOUNT_STATUS_SLOTS; slot++) {
            appendIfPresent(populated, FIELD_ACCOUNT_STATUS + '(' + slot + ')',
                    decoded.accountStatusSlot(slot) != null);
        }
        appendIfPresent(populated, FIELD_CREDIT_BALANCE, isPopulated(decoded.creditBalance()));
        appendIfPresent(populated, FIELD_CASH_BALANCE, isPopulated(decoded.cashBalance()));
        appendIfPresent(populated, FIELD_APPROVED_COUNT, isPopulated(decoded.approvedAuthCount()));
        appendIfPresent(populated, FIELD_DECLINED_COUNT, isPopulated(decoded.declinedAuthCount()));
        appendIfPresent(populated, FIELD_APPROVED_AMOUNT, isPopulated(decoded.approvedAuthAmount()));
        appendIfPresent(populated, FIELD_DECLINED_AMOUNT, isPopulated(decoded.declinedAuthAmount()));

        if (populated.length() > 0) {
            // WHY : Trade-offs: the refusal names the COMPONENTS and never their values. A balance or a
            //       count identifies an account's position as surely as the key does, and this message
            //       reaches a log, so the component names locate the defect without copying account data
            //       into it. The caller that needs the values already holds the decoded carrier.
            throw new IllegalArgumentException("this segment carries state the summary aggregate cannot"
                    + " represent, so it is refused rather than partially applied; use"
                    + " toSummarySegment or toSegmentFields for a populated segment. Populated"
                    + " components: " + populated);
        }
    }

    /**
     * Appends a component name to a refusal list when that component is populated.
     *
     * <p>Assumptions: the list is built rather than thrown on first sight so one refusal reports every
     * offending component. A caller handed the first name alone would fix it, retry, and meet the next --
     * a sequence of identical failures where one message would have shown the whole shape of the
     * mismatch.</p>
     *
     * <p><strong>Return value.</strong> This method returns no value; it appends to the supplied builder
     * in place.</p>
     *
     * @param names the refusal list under construction; must not be {@code null}
     * @param component the copybook component name to record
     * @param populated whether that component carries a value the aggregate cannot represent
     */
    private static void appendIfPresent(StringBuilder names, String component, boolean populated) {
        if (populated) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(component);
        }
    }

    /**
     * Reports whether an amount carries a value the aggregate's constructor would not have produced.
     *
     * <p>Assumptions: absent and zero are treated alike, because both are states a newly created segment
     * legitimately presents -- the reference program zeroes the field and a nullable column may simply be
     * unset -- and only a non-zero amount is state the aggregate cannot carry forward.</p>
     *
     * @param amount the decoded amount, which may be {@code null}
     * @return {@code true} when the amount is present and not zero
     */
    private static boolean isPopulated(Money amount) {
        return amount != null && !amount.isZero();
    }

    /**
     * Reports whether a counter carries a value the aggregate's constructor would not have produced.
     *
     * <p>Assumptions: absent and zero are treated alike, for the same reason as the amount above. A newly
     * created segment's counters are zero, so zero is representable and any other value is not.</p>
     *
     * @param counter the decoded counter, which may be {@code null}
     * @return {@code true} when the counter is present and not zero
     */
    private static boolean isPopulated(Short counter) {
        return counter != null && counter.shortValue() != 0;
    }

    /**
     * Reads one two-character account-status slot out of the array's single decoded interval.
     *
     * <p>Assumptions: the slot boundaries are computed from the declared slot width rather than written
     * out as five literal intervals, because five hand-written pairs of offsets are five places the same
     * arithmetic could be got wrong and the copybook declares the width once. The array is one field to
     * the codec -- {@code cpy/CIPAUSMY.cpy} L22 declares {@code PIC X(02) OCCURS 5 TIMES}, which the
     * registry registers as a single ten-byte character interval -- so the split is this class's work and
     * belongs at exactly one site.</p>
     *
     * <p>Assumptions: a blank slot becomes {@code null} rather than two spaces. The reference application
     * clears an unused slot to spaces, and the migration declares the five columns nullable, so a slot of
     * spaces there means the same thing an absent value means here; carrying the spaces through would make
     * a never-set slot compare unequal to an absent one for no behavioural reason.</p>
     *
     * @param statusArray the whole decoded ten-character interval, or {@code null} when the array decoded
     *     blank and was therefore absent from the field map
     * @param occurrence the one-based slot number, from one to five
     * @return the two characters at that slot position, or {@code null} when the array is absent or that
     *     slot is blank
     */
    private static String statusSlot(String statusArray, int occurrence) {
        if (statusArray == null) {
            return null;
        }
        int from = (occurrence - 1) * ACCOUNT_STATUS_SLOT_WIDTH;
        int to = from + ACCOUNT_STATUS_SLOT_WIDTH;
        if (statusArray.length() < to) {
            return null;
        }
        String slot = statusArray.substring(from, to);
        return slot.isBlank() ? null : slot;
    }

    /**
     * Reads a decoded character field, treating a blank value as absent.
     *
     * @param fields the decoded field map; must not be {@code null}
     * @param fieldName the exact copybook field name to read
     * @return the field's characters, or {@code null} when the field is absent from the map or blank
     */
    private static String text(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null) {
            return null;
        }
        String characters = value.toString();
        return characters.isBlank() ? null : characters;
    }

    /**
     * Reads a decoded identifier field as a whole number, whichever regime carried it.
     *
     * <p>Assumptions: the two identifiers on this segment arrive as DIFFERENT Java types because they are
     * stored in different regimes -- the packed account key at {@code cpy/CIPAUSMY.cpy} L19 decodes to a
     * {@code BigDecimal} while the unsigned display customer identifier at L20 decodes to a {@code Long}
     * -- and this method accepts either rather than each caller testing the type. Widening both to
     * {@code Long} matches the {@code BIGINT} columns the migration declares, since a key is a name and
     * not a quantity and so carries no scale.</p>
     *
     * @param fields the decoded field map; must not be {@code null}
     * @param fieldName the exact copybook field name to read
     * @return the identifier as a whole number, or {@code null} when the field is absent
     * @throws ArithmeticException if a packed identifier carries a fractional part or exceeds the range a
     *     {@code BIGINT} column holds, neither of which its declared picture admits
     */
    private static Long identifier(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Long already) {
            return already;
        }
        return ((BigDecimal) value).longValueExact();
    }

    /**
     * Reads a decoded packed money field as the shared kernel's fixed-point amount.
     *
     * <p>Assumptions: the amount is bounded at the nine integer digits {@code PIC S9(09)V99 COMP-3}
     * declares rather than at the widest picture the kernel supports. Bounding at the field's own picture
     * refuses a too-wide value at the decode that introduced it, whereas the wider bound would let it pass
     * every Java boundary and fail later at the database with no field named. An absent field yields an
     * exact zero, because the reference program substitutes zero for each of these six components when no
     * segment exists, at {@code cbl/COPAUS0C.cbl} L800 to L806.</p>
     *
     * @param fields the decoded field map; must not be {@code null}
     * @param fieldName the exact copybook field name to read
     * @return the amount at exactly two decimal places, never {@code null}
     * @throws ArithmeticException if the decoded amount needs more than nine integer digits, which its
     *     declared picture cannot hold
     */
    private static Money amount(Map<String, Object> fields, String fieldName) {
        BigDecimal value = (BigDecimal) fields.get(fieldName);
        return value == null ? Money.ZERO : Money.ofPicture(value, MONEY_INTEGER_DIGITS);
    }

    /**
     * Reads a decoded binary counter as the small integer its column declares.
     *
     * <p>Assumptions: the counters are the two fields on this segment that are NOT packed. They are
     * {@code PIC S9(04) COMP} at {@code cpy/CIPAUSMY.cpy} L27 and L28, two-byte two's complement
     * halfwords, and the registry classifies them as binary so the codec reads them that way. They arrive
     * as {@code BigDecimal} like the packed fields do, which is why the regime distinction cannot be
     * recovered from the decoded type and has to be read from the layout; the narrowing below is exact, so
     * a value outside the halfword raises rather than wrapping. An absent field yields zero, matching the
     * substitution at {@code cbl/COPAUS0C.cbl} L801 to L802.</p>
     *
     * @param fields the decoded field map; must not be {@code null}
     * @param fieldName the exact copybook field name to read
     * @return the counter as a small integer, never {@code null}
     * @throws ArithmeticException if the decoded counter carries a fractional part or lies outside the
     *     range a {@code SMALLINT} column holds
     */
    private static Short counter(Map<String, Object> fields, String fieldName) {
        BigDecimal value = (BigDecimal) fields.get(fieldName);
        return value == null ? Short.valueOf((short) 0) : Short.valueOf(value.shortValueExact());
    }

    /**
     * Assembles the encodable field map from a decoded carrier.
     *
     * <p>Assumptions: the five status members are rejoined into the one ten-character interval the
     * copybook declares, each padded to its declared slot width and placed at its own position, because
     * the codec sees the array as a single field. Rejoining in slot order is what makes the encode restore
     * the interval the decode split, so a round trip is byte-identical; joining them in any other order
     * would produce a well-formed record describing a different account.</p>
     *
     * <p>Assumptions: the padding field is deliberately NOT put into the map. The codec restores a missing
     * registered padding interval at its declared width, so omitting it is how the thirty-four bytes come
     * back as blanks, and supplying them here would duplicate a rule that already has one owner.</p>
     *
     * @param decoded the carrier to convert; must not be {@code null}
     * @return a declaration-ordered map of copybook field name to encodable value, without the padding
     *     field
     * @throws NullPointerException if either identifier is absent, neither of which a stored row may omit
     */
    private static Map<String, Object> segmentFieldsOf(SummarySegment decoded) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_ACCT_ID, BigDecimal.valueOf(Objects.requireNonNull(decoded.accountId(),
                "accountId must not be null, because the key column is declared not null")));
        fields.put(FIELD_CUST_ID, Objects.requireNonNull(decoded.customerId(),
                "customerId must not be null, because the customer column is declared not null"));
        fields.put(FIELD_AUTH_STATUS, blankIfAbsent(decoded.authStatus(), AUTH_STATUS_WIDTH));

        StringBuilder statusArray = new StringBuilder();
        for (int slot = 1; slot <= ACCOUNT_STATUS_SLOTS; slot++) {
            statusArray.append(blankIfAbsent(decoded.accountStatusSlot(slot),
                    ACCOUNT_STATUS_SLOT_WIDTH));
        }
        fields.put(FIELD_ACCOUNT_STATUS, statusArray.toString());

        // WHY : Assumptions: an absent amount or counter is encoded as zero rather than refused, on the
        //       same reading the decode applies in the other direction -- cbl/COPAUS0C.cbl L800 to L806
        //       treats every one of these six components as zero when no segment exists, so absent and
        //       zero already mean the same thing for this record. A carrier assembled by hand from
        //       nullable database columns therefore encodes without the caller pre-filling zeros, and no
        //       unboxing of an absent value can arise on this path.
        fields.put(FIELD_CREDIT_LIMIT, amountOrZero(decoded.creditLimit()));
        fields.put(FIELD_CASH_LIMIT, amountOrZero(decoded.cashLimit()));
        fields.put(FIELD_CREDIT_BALANCE, amountOrZero(decoded.creditBalance()));
        fields.put(FIELD_CASH_BALANCE, amountOrZero(decoded.cashBalance()));
        fields.put(FIELD_APPROVED_COUNT, counterOrZero(decoded.approvedAuthCount()));
        fields.put(FIELD_DECLINED_COUNT, counterOrZero(decoded.declinedAuthCount()));
        fields.put(FIELD_APPROVED_AMOUNT, amountOrZero(decoded.approvedAuthAmount()));
        fields.put(FIELD_DECLINED_AMOUNT, amountOrZero(decoded.declinedAuthAmount()));
        return fields;
    }

    /**
     * Supplies an amount for encoding, substituting an exact zero for an absent one.
     *
     * @param amount the carrier's amount, or {@code null} when the carrier holds none
     * @return the amount at exactly two decimal places, or an exact zero when {@code amount} is
     *     {@code null}
     */
    private static BigDecimal amountOrZero(Money amount) {
        return amount == null ? Money.ZERO.amount() : amount.amount();
    }

    /**
     * Supplies a counter for encoding, substituting zero for an absent one.
     *
     * @param counter the carrier's counter, or {@code null} when the carrier holds none
     * @return the counter as an exact decimal, or zero when {@code counter} is {@code null}
     */
    private static BigDecimal counterOrZero(Short counter) {
        return counter == null ? BigDecimal.ZERO : BigDecimal.valueOf(counter.longValue());
    }

    /**
     * Renders an absent or short character value as exactly the declared field width.
     *
     * <p>Assumptions: an absent value becomes spaces rather than a zero or a marker character, because
     * spaces are what the reference application leaves in an unused character field and what the decode
     * reads back as absent. Padding on the right preserves the value's own position within its interval,
     * which is the property a fixed-width field carries.</p>
     *
     * @param value the characters to place, or {@code null} when the field carries none
     * @param width the declared field width in characters
     * @return exactly {@code width} characters, space-padded on the right, or the value unchanged when it
     *     already meets or exceeds that width so the codec can report the overflow against its own field
     */
    private static String blankIfAbsent(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /**
     * Selects the row occupying one screen position, or none when the page is shorter.
     *
     * <p>Assumptions: a page shorter than the screen leaves its trailing positions empty rather than
     * repeating a row or refusing the page. The reference program displays whatever it read and clears the
     * rest, so a final page of two authorizations fills two rows and leaves three blank, and every
     * component of a blank row is published absent.</p>
     *
     * @param rows the page's rows in display order; must not be {@code null}
     * @param index the zero-based screen position to fill
     * @return the row at that position, or {@code null} when the page holds fewer rows
     */
    private static PendingAuthRowView rowAt(List<PendingAuthRowView> rows, int index) {
        return index < rows.size() ? rows.get(index) : null;
    }

    /**
     * Publishes one row's transaction identifier.
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the transaction identifier, or {@code null} when the position is empty
     */
    private static String transactionIdOf(PendingAuthRowView row) {
        return row == null ? null : row.transactionId();
    }

    /**
     * Publishes one row's authorization date in the month-first form the screen displays.
     *
     * <p>Assumptions: the stored six characters are YEAR first and the displayed form RE-ORDERS them, so
     * this is a re-ordering and not a re-formatting. {@code cbl/COPAUS0C.cbl} L531 to L533 slice the
     * stored value into year, month and day parts and its L534 moves the recomposed month-day-year group
     * into a field already carrying its separators. Reading the six characters as month-first instead
     * yields dates that are well-formed and wrong, which is why the rule is delegated to the single
     * definition of it rather than restated: the sibling detail mapper owns it, and calling it keeps one
     * answer in the package.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the eight-character month-first date, or {@code null} when the position is empty or the row
     *     carries no stored date
     * @throws IllegalArgumentException if the stored date is neither blank nor exactly six characters, a
     *     width the declaring field cannot hold
     */
    private static String authDateOf(PendingAuthRowView row) {
        return row == null ? null : PendingAuthDetailMapper.renderOriginatingDate(row.authOrigDate());
    }

    /**
     * Publishes one row's authorization time in the colon-separated form the screen displays.
     *
     * <p>Assumptions: unlike the date beside it, the stored order and the displayed order AGREE here --
     * {@code cbl/COPAUS0C.cbl} L527 to L529 move the three pairs into positions one, four and seven of a
     * field that already carries its colons, keeping hours, minutes and seconds in sequence. The agreement
     * is stated because the adjacent date rule does not agree, and a reader generalising from this one to
     * that one would corrupt the date. The rule is delegated for the same reason as the date's.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the eight-character colon-separated time, or {@code null} when the position is empty or the
     *     row carries no stored time
     * @throws IllegalArgumentException if the stored time is neither blank nor exactly six characters
     */
    private static String authTimeOf(PendingAuthRowView row) {
        return row == null ? null : PendingAuthDetailMapper.renderOriginatingTime(row.authOrigTime());
    }

    /**
     * Publishes one row's authorization type.
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the authorization type, or {@code null} when the position is empty
     */
    private static String authTypeOf(PendingAuthRowView row) {
        return row == null ? null : row.authType();
    }

    /**
     * Publishes one row's approved-or-declined character.
     *
     * <p>Assumptions: the character is taken from the row as it arrives rather than derived again here.
     * The rule is an equality against the single approved response code with an unconditional alternative,
     * written at {@code cbl/COPAUS0C.cbl} L536 to L540, and the sibling view mapper already applied it
     * when it built the row. Deriving it a second time would put two answers to one question in one
     * module, and a divergence between them would show as two endpoints disagreeing about whether the same
     * authorization was approved.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the single approved-or-declined character, or {@code null} when the position is empty
     */
    private static String approvalOf(PendingAuthRowView row) {
        return row == null ? null : row.approvalStatus();
    }

    /**
     * Publishes one row's match status.
     *
     * <p>Assumptions: the character passes through unchanged, and its value domain is asserted by the
     * response rather than here. The four admissible values are declared as condition names at
     * {@code cpy/CIPAUDTY.cpy} L46 to L49, and the response's own constructor refuses anything outside
     * them, so checking here as well would put the same domain in two places.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the single match-status character, or {@code null} when the position is empty
     */
    private static String matchOf(PendingAuthRowView row) {
        return row == null ? null : row.matchStatus();
    }

    /**
     * Publishes one row's approved amount as fixed point.
     *
     * <p>Assumptions: this is the APPROVED amount and not the requested one, chosen at
     * {@code cbl/COPAUS0C.cbl} L525, and it arrives already selected on the row. An empty screen position
     * publishes no amount rather than a zero, because zero is a real approved amount on a declined
     * authorization and substituting it would make a blank row indistinguishable from a declined one.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the approved amount at exactly two decimal places, or {@code null} when the position is
     *     empty
     */
    private static Money amountOf(PendingAuthRowView row) {
        return row == null ? null : row.amount();
    }

    /**
     * Publishes one row's opaque selector.
     *
     * <p>Assumptions: the token arrives already sealed and is read rather than minted. Sealing needs key
     * material that belongs to the service layer, so a static mapper method could not produce a valid
     * token even if it tried, and the response's constructor refuses a value that does not carry the
     * sealed shape -- which is what stops a raw account-date-time address being published in a selector's
     * place.</p>
     *
     * @param row the row occupying a screen position, or {@code null} when that position is empty
     * @return the sealed selector token, or {@code null} when the position is empty
     */
    private static String selectorOf(PendingAuthRowView row) {
        return row == null ? null : row.key();
    }

    /**
     * Renders an identifier as the digits the screen displays.
     *
     * <p>Assumptions: an identifier reaches the screen as characters and not as a JSON number, because the
     * map declares both as character fields and because a number would let a client route an eleven-digit
     * account key through a binary floating-point type. No leading zeros are restored, matching the
     * reference program, which moves the value into a display field without an edit mask.</p>
     *
     * @param identifier the identifier to render, or {@code null} when absent
     * @return the identifier's digits, or {@code null} when {@code identifier} is {@code null}
     */
    private static String digits(Long identifier) {
        return identifier == null ? null : identifier.toString();
    }

    /**
     * Widens a stored counter to the type the response declares.
     *
     * <p>Assumptions: the response declares both counters as {@code Integer} while the column and the
     * entity carry {@code Short}, so the widening happens here rather than at the entity. It is lossless
     * in this direction by construction -- every value a four-digit halfword holds fits an
     * {@code Integer} -- and doing it at the projection keeps the narrower storage type where the
     * copybook's four-digit picture put it.</p>
     *
     * @param counter the stored counter, or {@code null} when absent
     * @return the counter widened for publication, or {@code null} when {@code counter} is {@code null}
     */
    private static Integer count(Short counter) {
        return counter == null ? null : Integer.valueOf(counter.intValue());
    }

    /**
     * Publishes a stored amount as the shared kernel's fixed-point type.
     *
     * <p>Assumptions: an absent amount becomes an exact zero rather than staying absent, which is the one
     * place this projection substitutes a value. The reference program does the same at
     * {@code cbl/COPAUS0C.cbl} L800 to L806, moving zero into all six of these components when no summary
     * segment exists, so a screen that showed zeros is reproduced by publishing zeros. The zero is
     * constructed at scale two, so it serialises as a two-place decimal string and never as a bare
     * integer.</p>
     *
     * @param amount the stored amount at scale two, or {@code null} when the column is unset
     * @return the amount as fixed point, or an exact zero when {@code amount} is {@code null}
     * @throws ArithmeticException if the stored amount needs more than nine integer digits, which the
     *     declaring picture cannot hold
     */
    private static Money money(BigDecimal amount) {
        return amount == null ? Money.ZERO : Money.ofPicture(amount, MONEY_INTEGER_DIGITS);
    }
}
