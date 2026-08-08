package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pending authorization recorded against an account.
 *
 * <p>This is the migrated form of the hierarchical database's CHILD segment {@code PAUTDTL1},
 * declared at 200 bytes in {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L36, whose
 * fields are laid out at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L19 to L54 --
 * 27 {@code 05}-levels, two of them {@code 10}-level children of the key group. Every citation
 * below is relative to {@code app/app-authorization-ims-db2-mq} unless stated otherwise, and every
 * one of them is a path and a line number because that tree is read as this type's specification
 * and is never modified. Its key is {@link PendingAuthDetailKey}; its parent is
 * {@link PendingAuthSummary}, and the database enforces that parentage with a foreign key at
 * migration L624 to L626 so a detail row cannot outlive the summary the segment hierarchy required
 * it to hang beneath.</p>
 *
 * <p>Assumptions: the widths of those 27 items sum to exactly the declared 200 bytes, and the
 * arithmetic only closes if the two packed amounts are read as SEVEN bytes each. A packed item
 * occupies {@code ceil((digits + 1) / 2)} bytes, so {@code PIC S9(10)V99 COMP-3} at L34 and L35 is
 * thirteen digits in seven bytes rather than the six a reader counting only the twelve significant
 * digits would assume; the same formula puts {@code PIC S9(05) COMP-3} at L20 in three bytes and
 * {@code PIC S9(09) COMP-3} at L21 in five, and {@code DBPAUTP0.dbd} L37 confirms that pair
 * independently as an eight-byte sequence field. Six-byte amounts would total 198, and the record
 * would silently fail to reconcile against the 200 the segment declares. That is worth stating at
 * the top of this type because the failure it guards against is not a compile error: it surfaces
 * later as amounts read from the wrong offsets, which look plausible.</p>
 *
 * <p>Assumptions: every field of the segment is carried across except the trailing seventeen-byte
 * {@code FILLER} at L54, which pads the record to that fixed 200-byte length and holds no data --
 * transformation rule T1 drops {@code FILLER} and only {@code FILLER}, and records the drop, which
 * this paragraph and the row in
 * {@code docs/architecture/data-model-and-schema-mapping.md} are. The distinction matters in both
 * directions: a {@code FILLER} carrying a {@code VALUE} clause would be content rather than padding,
 * and this one carries none; and {@code PA-AUTH-ORIG-TIME} at L23 is an ordinary named
 * {@code 05}-level that is easy to skip when reading past its neighbour at L22, so it is mapped
 * below like any other field. Dropping it would lose data and leave the byte sum at 194. The
 * baseline's own relational mapping treats the padding the same way: {@code ddl/AUTHFRDS.ddl}
 * declares no {@code FILLER} column.</p>
 *
 * <p>Assumptions: the two amounts are exact fixed point at scale two. The copybook declares them
 * {@code COMP-3}, the extract and the mapper decode the packed nibbles once at that boundary, and
 * no column here stores a packed byte -- a sign nibble inside the database is unreadable to every
 * SQL predicate. {@code NUMERIC(12,2)} is the twelve significant digits of {@code S9(10)V99}, and
 * it is deliberately WIDER than the {@code NUMERIC(11,2)} that {@link PendingAuthSummary} carries
 * for its own {@code S9(09)V99} money: the two pictures differ by an integer digit, and mapping
 * both onto one precision would truncate the larger. Rule T3 forbids a binary floating-point type
 * anywhere in the money path, {@link com.carddemo.common.money.Money} is where scale two and
 * {@code HALF_UP} are held for arithmetic, {@link com.carddemo.common.money.MoneyModule} is what
 * renders an amount as a JSON string rather than a number that a client would parse into a double,
 * and the architecture test enforces the prohibition rather than trusting it.</p>
 *
 * <p>Assumptions: this type deliberately declares no {@code toString}. A rendering of it would
 * carry a primary account number, and a diagnostic that is safe to write into a log is the one
 * thing such a rendering must never be; masking belongs to
 * {@code com.carddemo.authorization.mapper}, and a member that renders itself has escaped the one
 * place that rule is applied, invisibly at the call site. {@link PendingAuthDetailKey} carries the
 * loggable identity instead, which is why that type has a rendering and this one does not. The
 * package charter states the same boundary at {@code package-info.java} L202 to L212.</p>
 *
 * <p>Assumptions: the table name is UNQUALIFIED and resolves through the pinned connection
 * {@code search_path}, for the reason the migration records at its L77 to L93 -- PostgreSQL reports
 * {@code authorization} as a reserved word, so a qualified name would have to be double-quoted at
 * every occurrence and a single omission is a parse error rather than a failed lookup. Resolution
 * is pinned twice by the declared consumer, at
 * {@code services/authorization-service/src/main/resources/application.yml} L186, which sets the
 * migration tool's default schema, and by its L125, which sets the pool's connection-init statement
 * for every other connection. This annotation therefore names its table and
 * nothing more: no unique constraint, no index, no column definition.</p>
 *
 * <p>Alternatives Considered: letting this mapping declare the schema objects it needs -- the
 * composite key, the two check constraints, the uniqueness of card number with transaction
 * identifier, the foreign key. Rejected because {@code V1__authorization.sql} is the single
 * authority for this schema's shape and holds all of them at its L561 to L626, and this module runs
 * with schema generation switched off at {@code application.yml} L201. Two declarations of one
 * constraint can disagree, and the one that would then be wrong is the one no migration applies.</p>
 *
 * <p>Alternatives Considered: no {@code Version} column is declared on this type, and it was
 * considered rather than overlooked. Optimistic concurrency is carried by the account, customer and
 * card entities of other contexts, where the reference programs implement an explicit before-image
 * comparison across a pseudo-conversational gap that a version column expresses natively. The
 * authorization programs have no before-image pattern to carry: a detail row is written once by the
 * consumer and afterwards only its two fraud members change. The migration declares no version
 * column on {@code pending_auth_detail}, and adding an unmapped one here would fail against the
 * table it claims to map.</p>
 *
 * <p>Alternatives Considered: neither Lombok nor MapStruct is used, here or anywhere in this tree.
 * Generated accessors cannot carry the Javadoc that user-specified Rule 1 L15 requires on every
 * method, and a generated mapper cannot hold the per-member justification that this migration's
 * mapping decisions need -- the masking, the renaming and the padding treatments each need a reason
 * recorded at the site. Java 21 with explicit constructors and explicit accessors gives the same
 * brevity with documentable members.</p>
 */
@Entity
@Table(name = "pending_auth_detail")
public class PendingAuthDetail {

    /**
     * The state an APPROVED authorization is recorded in: pending a match against a posted
     * transaction.
     *
     * <p>Assumptions: {@code 'P'} is the first of the four values the copybook's condition names
     * close the match-status domain to, {@code PA-MATCH-PENDING} at {@code cpy/CIPAUDTY.cpy} L46,
     * and it is the one meaning "approved and not yet matched". The consumer selects it on the
     * APPROVAL BRANCH ONLY, at {@code cbl/COPAUA0C.cbl} L903 inside the two-branch test opened at
     * L902; the decline branch at L905 selects {@link #MATCH_STATUS_DECLINED} instead. Treating this
     * value as the state of every newly-recorded authorization would record a declined
     * authorization as one still awaiting a match, which every consumer of the summary screen and
     * of the purge job reads as an open commitment against the account.</p>
     */
    public static final String MATCH_STATUS_PENDING = "P";

    /**
     * The state a DECLINED authorization is recorded in: answered, and never eligible for a match.
     *
     * <p>Assumptions: {@code 'D'} is {@code PA-MATCH-AUTH-DECLINED} at {@code cpy/CIPAUDTY.cpy} L47,
     * the second of the four values the domain admits, and the consumer selects it on the ELSE
     * branch of {@code cbl/COPAUA0C.cbl} L904 to L906 -- that is, whenever the reply's response code
     * is not the approved one. It is a terminal state rather than a waiting one: a declined
     * authorization reserves nothing, so nothing will ever match it. Recording a decline as {@link
     * #MATCH_STATUS_PENDING} instead would leave it permanently unmatchable while still presenting
     * as outstanding on the summary screen, which is a behavioural difference rather than a cosmetic
     * one.</p>
     */
    public static final String MATCH_STATUS_DECLINED = "D";

    /**
     * The state a pending authorization reaches when it expires before any transaction matches it.
     *
     * <p>Assumptions: {@code 'E'} is {@code PA-MATCH-PENDING-EXPIRED} at {@code cpy/CIPAUDTY.cpy}
     * L48. No path in this deployment writes it yet -- it belongs to the expiry and purge program
     * {@code cbl/CBPAUP0C.cbl} -- but the constant is declared here because the migration's check
     * constraint at its L582 to L583 admits it and the extract loads rows already carrying it, so a
     * caller reading a loaded row needs a name for the value rather than a bare literal. It is
     * deliberately NOT one of {@link #ORIGINATED_MATCH_STATUSES}: naming a state is not the same
     * claim as being able to create a row in it.</p>
     */
    public static final String MATCH_STATUS_PENDING_EXPIRED = "E";

    /**
     * The state a pending authorization reaches once a posted transaction matches it.
     *
     * <p>Assumptions: {@code 'M'} is {@code PA-MATCHED-WITH-TRAN} at {@code cpy/CIPAUDTY.cpy} L49,
     * the fourth and last value the condition names admit. As with {@link
     * #MATCH_STATUS_PENDING_EXPIRED}, it is reached by transaction matching rather than by this
     * context's insert path, and it is named here for the same reason: the extract loads it.</p>
     */
    public static final String MATCH_STATUS_MATCHED_WITH_TRAN = "M";

    /**
     * The two states this service ORIGINATES, in the order the reference branches select them.
     *
     * <p>Assumptions: the reference insert reaches exactly two of the four values the domain admits.
     * The other two are reached later and elsewhere -- {@link #MATCH_STATUS_PENDING_EXPIRED} by the
     * purge job and {@link #MATCH_STATUS_MATCHED_WITH_TRAN} by the posting match -- so a row created
     * here carrying either of those would assert an outcome no insert path can have produced. The
     * constructor validates against this pair and the database validates against all four, which is
     * the correct division: the column has to be able to HOLD every state a row can ever reach, while
     * this type may only CREATE the two an insert reaches. The provider materialises a loaded row
     * through the no-argument constructor and field assignment, so narrowing this check costs a
     * loaded expired or matched row nothing.</p>
     */
    public static final List<String> ORIGINATED_MATCH_STATUSES =
            List.of(MATCH_STATUS_PENDING, MATCH_STATUS_DECLINED);

    /**
     * The fraud indicator meaning the authorization has been reported as fraudulent.
     *
     * <p>Assumptions: {@code 'F'} is {@code PA-FRAUD-CONFIRMED} at {@code cpy/CIPAUDTY.cpy} L51,
     * one of only two values the fraud domain names. The check constraint at migration L607 to L608
     * admits that pair, null and a single space and nothing else, so an out-of-domain value cannot
     * be stored even by a caller that writes the column without going through this type.</p>
     */
    public static final String FRAUD_REPORTED = "F";

    /**
     * The fraud indicator meaning a previous fraud report has been withdrawn.
     *
     * <p>Assumptions: {@code 'R'} is {@code PA-FRAUD-REMOVED} at {@code cpy/CIPAUDTY.cpy} L52, the
     * other of the two values the fraud domain names, and it is a REACHED state rather than an
     * absence of one. {@code cbl/COPAUS1C.cbl} L236 to L241 toggles between the two -- confirmed
     * becomes removed and anything else becomes confirmed -- so a withdrawal is recorded as this
     * value and not by blanking the field back to its never-examined state. The published request
     * contract admits the same pair, {@code enum [F, R]} on the marking action, so the entity and
     * the interface close the domain to the same two values.</p>
     */
    public static final String FRAUD_REMOVED = "R";

    /**
     * The lowest value the point-of-sale entry mode may take.
     *
     * <p>Assumptions: zero, because {@code PA-POS-ENTRY-MODE PIC 9(02)} at {@code cpy/CIPAUDTY.cpy}
     * L38 is an UNSIGNED two-digit display picture. An unsigned picture cannot hold a negative
     * quantity at all, so a negative value here did not come from the segment.</p>
     */
    public static final short POS_ENTRY_MODE_MIN = 0;

    /**
     * The highest value the point-of-sale entry mode may take.
     *
     * <p>Assumptions: 99, the largest quantity two digits can express. The bound is stated as a
     * checked invariant rather than left to the column, because {@code SMALLINT} holds five digits
     * and would accept a three-digit value that the segment cannot represent and that the
     * two-character wire field at {@code cpy/CCPAURQY.cpy} L30 cannot carry. A value outside this
     * range would therefore persist successfully and then fail to encode, which is the failure mode
     * this bound exists to move forward to the boundary that produced it.</p>
     */
    public static final short POS_ENTRY_MODE_MAX = 99;

    /**
     * The four one-character match statuses the copybook's condition names close the domain to.
     *
     * <p>Assumptions: the set is the constraint's own membership list, in copybook order, so the
     * entity and the migration cannot drift apart on which values are admissible. It is used only to
     * validate, never to iterate for presentation, so its order carries no display meaning.</p>
     */
    private static final Set<String> MATCH_STATUS_DOMAIN = Set.of(MATCH_STATUS_PENDING,
            MATCH_STATUS_DECLINED, MATCH_STATUS_PENDING_EXPIRED, MATCH_STATUS_MATCHED_WITH_TRAN);

    /**
     * The two fraud indicators a marking transition may set.
     *
     * <p>Assumptions: this set deliberately excludes the blank and null states the column also
     * admits. Blank and null are the states of an authorization NOBODY HAS EXAMINED, reached only by
     * an insert or an extract load; a marking transition always asserts a fraud position, so it may
     * only move to one of these two.</p>
     */
    private static final Set<String> FRAUD_MARK_DOMAIN = Set.of(FRAUD_REPORTED, FRAUD_REMOVED);

    /**
     * The exact number of characters a fraud report date occupies.
     *
     * <p>Assumptions: eight, from {@code PA-FRAUD-RPT-DATE PIC X(08)} at {@code cpy/CIPAUDTY.cpy}
     * L53 and the {@code CHAR(8)} column the migration declares at its L536. The width is checked
     * rather than assumed because a fixed-character column pads a short value with blanks silently,
     * so a five-character date would store as a plausible-looking eight and only be caught when
     * something tried to read the day out of it.</p>
     */
    private static final int FRAUD_REPORT_DATE_LENGTH = 8;

    /**
     * The three-part key: account, authorization date and authorization time.
     *
     * <p>Assumptions: the key is {@code (account_id, auth_date, auth_time)}, the inherited parent
     * key followed by the segment's own two-part key, and one declared number settles it.
     * {@code ims/PSBPAUTB.psb} L17 declares {@code KEYLEN=14} for this database, which is exactly
     * the six bytes of the root sequence field {@code ACCNTID} at {@code ims/DBPAUTP0.dbd} L30 plus
     * the eight of the child sequence field {@code PAUT9CTS} at L37 -- a hierarchic path key, not a
     * key of the child alone. Two further readings agree: the insert at {@code cbl/COPAUA0C.cbl}
     * L913 to L919 reaches the child only through a parent qualification, its L915 reading
     * {@code WHERE (ACCNTID = PA-ACCT-ID)}; and the extract at {@code cbl/PAUDBUNL.CBL} L46 to L48
     * declares its child output record as a packed root key followed by the 200-byte segment, a
     * prefix its L230 fills with {@code MOVE PA-ACCT-ID TO ROOT-SEG-KEY}.</p>
     *
     * <p>Assumptions: the 200-byte segment therefore has NO account field of its own -- it opens at
     * {@code PA-AUTHORIZATION-KEY} on L19 with only the two packed children on L20 and L21 -- and
     * {@code account_id} is materialised as a real column here because a relational child cannot
     * inherit its parent's key positionally the way a hierarchic one does. A reader comparing this
     * type to the copybook will look for that field and not find it, which is why its origin is
     * stated rather than left to be inferred. The segment key is unique only WITHIN one account,
     * because that is the only scope the hierarchy enforces it in, so the two segment components
     * alone would collide as soon as two accounts recorded an authorization in the same
     * millisecond.</p>
     *
     * <p>Alternatives Considered: expressing the key as a nested static member of this class rather
     * than as {@link PendingAuthDetailKey} beside it. The nested form keeps the key beside its only
     * entity and needs no separate file; the separate form is what this package declares, and it is
     * kept for two concrete reasons. The package charter at {@code package-info.java} L31 to L36
     * and L43 to L46 fixes the four types and the key among them, so the separate form is already
     * the declared shape and does not add a sixth file to the directory. And the key is referenced
     * by name outside this type -- {@code repository/PendingAuthDetailRepository.java} L33 to L34
     * parameterises its repository on it, the mapper seals it into a page cursor, and the request
     * listener constructs it -- so nesting it would rename every one of those references for no
     * change in behaviour.</p>
     */
    @EmbeddedId
    private PendingAuthDetailKey id;

    /**
     * The originating date as the acquirer supplied it,
     * {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22.
     */
    // WHY : Alternatives Considered: relying on the declared length alone was evaluated and rejected,
    //       because a Java String otherwise selects the JDBC VARCHAR binding and schema validation then
    //       rejects this schema's CHAR columns even though every width agrees. Spelling the physical
    //       type into columnDefinition was rejected too: that would duplicate vendor DDL inside a
    //       mapping which has no authority to create the table. The type code below selects the standard
    //       CHAR binding for reads, writes and validation while leaving the physical definition wholly
    //       with V1__authorization.sql, and it is the same mechanism the batch context's own entities
    //       use for the identical reason. Every CHAR column on this type carries it for this one
    //       reason, which is stated here once rather than repeated on each of the twenty that follow.
    // WHY : Assumptions: six characters, not a parsed DATE, and the migration records the same reading
    //       at its L345 to L361. The value is YYMMDD -- the reference programs slice it year-first at
    //       cbl/COPAUS0C.cbl L531 to L534 and identically at cbl/COPAUS2C.cbl L103 to L105, and merely
    //       DISPLAY it month-first -- so it is not ISO-ordered and a lexical compare is not a date
    //       compare. Widening the two-digit year would need a century pivot the baseline never chose,
    //       and this value arrives from the ACQUIRER on the request rather than from a clock
    //       (cbl/COPAUA0C.cbl L877 to L878), so a value that will not parse has to round-trip rather
    //       than fail the row. Storing the characters keeps every state the segment can hold.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_orig_date", length = 6)
    private String authOrigDate;

    /**
     * The originating time as the acquirer supplied it,
     * {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23.
     */
    // WHY : Assumptions: symmetric with auth_orig_date above and CHAR(6) for the same reasons, per the
    //       migration at its L369 to L376. The reference system only ever slices these six characters
    //       for display, at cbl/COPAUS0C.cbl L527 to L529, and never computes with them, so a TIME
    //       column would buy no arithmetic and would refuse an acquirer value that will not parse.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_orig_time", length = 6)
    private String authOrigTime;

    /**
     * The primary account number the authorization was presented against,
     * {@code PA-CARD-NUM PIC X(16)} at {@code cpy/CIPAUDTY.cpy} L24.
     */
    // WHY : Assumptions: all sixteen characters are stored, unmasked, because this value is a KEY and a
    //       masked key selects nothing -- the idempotency lookup at
    //       repository/PendingAuthDetailRepository.java L131 seeks on it, and the fraud access path is
    //       keyed by it. The confidentiality rule this obeys is about egress rather than storage: a card
    //       number leaves this package only through com.carddemo.authorization.mapper, which masks it to
    //       its last four digits, and no member here declares a serialiser or rendering that could
    //       bypass that. The package charter states the same division at package-info.java L207 to L212.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", nullable = false, length = 16)
    private String cardNum;

    /**
     * The authorization type, {@code PA-AUTH-TYPE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L25.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_type", length = 4)
    private String authType;

    /**
     * The card expiry date as presented,
     * {@code PA-CARD-EXPIRY-DATE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L26.
     */
    // WHY : Assumptions: CHAR(4) and not a DATE. Four characters cannot carry a calendar date, the
    //       baseline's own relational table declares CARD_EXPIRY_DATE CHAR(4) at ddl/AUTHFRDS.ddl L5,
    //       and the solidus the detail screen shows at position three is inserted on the way to the
    //       screen -- cbl/COPAUS1C.cbl moves the first two characters at L336, overlays the separator at
    //       L337 and moves the remaining two at L338 -- so the separator is a rendering and not part of
    //       the stored value.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiry_date", length = 4)
    private String cardExpiryDate;

    /**
     * The network message type, {@code PA-MESSAGE-TYPE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L27.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_type", length = 6)
    private String messageType;

    /**
     * The network message source, {@code PA-MESSAGE-SOURCE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L28.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_source", length = 6)
    private String messageSource;

    /**
     * The authorization identification code returned to the acquirer,
     * {@code PA-AUTH-ID-CODE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L29.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_id_code", length = 6)
    private String authIdCode;

    /**
     * The response code returned to the acquirer,
     * {@code PA-AUTH-RESP-CODE PIC X(02)} at {@code cpy/CIPAUDTY.cpy} L30.
     */
    // WHY : Assumptions: this column DOES carry a check constraint, at migration
    //       ck_pending_auth_detail_auth_resp_code, and the domain it closes is taken from the PRODUCER
    //       rather than from the condition name. The single condition name that follows the field,
    //       PA-AUTH-APPROVED VALUE '00' at cpy/CIPAUDTY.cpy L31, is a sentinel for one value and does
    //       not close a domain by itself -- but the two MOVE statements that write the field do:
    //       cbl/COPAUA0C.cbl L688 moves '05' on a decline and L693 moves '00' on an approval, with no
    //       third branch. Those two are exhaustive, so the domain is '00', '05', a blank the extract
    //       may carry and null.
    // WHY : Refactoring Rationale: an earlier revision recorded here that the absence of a constraint
    //       was deliberate. It was corrected because the API contract publishes this member as a CLOSED
    //       enumeration of those same two values plus null, so an unconstrained column admitted a third
    //       value that no response body could legally carry -- and the failure would then surface on a
    //       READ of the offending row rather than on the write that created it.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_code", length = 2)
    private String authRespCode;

    /**
     * The response reason returned to the acquirer,
     * {@code PA-AUTH-RESP-REASON PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L32.
     */
    // WHY : Assumptions: this column carries a check constraint over EIGHT values, at migration
    //       ck_pending_auth_detail_auth_resp_reason, and like the response code above the domain comes
    //       from the producer rather than from a condition name -- the copybook declares none for this
    //       field at all. cbl/COPAUA0C.cbl L698 writes the approved reason and its L700 to L717 select
    //       one of seven decline reasons, which is the whole set the reference system can emit. The
    //       published contract enumerates the same eight plus null, so the column and the response
    //       schema now state one domain instead of the schema being narrower than the store.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_reason", length = 4)
    private String authRespReason;

    /**
     * The processing code, {@code PA-PROCESSING-CODE PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33.
     */
    // WHY : Assumptions: a NUMERIC picture persisted as CHAR(6) and held here as a String, which is the
    //       one place on this type where the copybook's picture and the target type deliberately
    //       disagree. These are digits used as a code rather than counted with, so a leading zero is
    //       data; an integer target would discard it and render "003000" as "3000". The baseline's own
    //       relational table reaches the same conclusion independently, declaring PROCESSING_CODE
    //       CHAR(6) at ddl/AUTHFRDS.ddl L11 even though the copybook field is numeric. This is what
    //       separates it from pos_entry_mode below, where the same 9(nn) shape does become an integer.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_code", length = 6)
    private String processingCode;

    /**
     * The amount the acquirer requested,
     * {@code PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34.
     */
    // WHY : Assumptions: NUMERIC(12,2) and non-nullable, matching the migration at its L406. Twelve
    //       significant digits are what S9(10)V99 declares, and the baseline's relational table agrees
    //       at ddl/AUTHFRDS.ddl L12. The column cannot be null because a packed field has no null state
    //       to carry and the insert always writes this one, at cbl/COPAUA0C.cbl L885; admitting a null
    //       would represent a row the reference system cannot produce and would oblige every reader of
    //       a running total to distinguish absence from zero, which the baseline cannot do.
    @Column(name = "transaction_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal transactionAmount;

    /**
     * The amount actually approved,
     * {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L35.
     */
    // WHY : Assumptions: non-nullable for the same reason as the requested amount above, and the
    //       decline path is the case that proves it -- a decline writes a literal zero at
    //       cbl/COPAUA0C.cbl L689 and the reply amount is moved in at L900 on both paths, so the field
    //       is never left unset.
    @Column(name = "approved_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    /**
     * The merchant category,
     * {@code PA-MERCHANT-CATAGORY-CODE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L36.
     */
    // WHY : Refactoring Rationale: the baseline transposes two letters of "category" in this field's
    //       name, and the target spells the column and this member correctly. That is a deliberate
    //       breaking change at the schema boundary rather than a cosmetic edit, because the baseline
    //       spelling reached persisted state and running code in four places:
    //       cpy/CIPAUDTY.cpy L36 is the segment field, cpy/CCPAURQY.cpy L28 is the request payload
    //       field, ddl/AUTHFRDS.ddl L14 is the actual Db2 column name and dcl/AUTHFRDS.dcl L37 is its
    //       generated declaration. Carrying it forward would propagate it into a column name, a member
    //       name, a response body and a browser client, where every later reader would have to learn it.
    //       The divergence is recorded in docs/architecture/data-model-and-schema-mapping.md.
    // WHY : Assumptions: the WIRE keeps the baseline spelling, so this rename does not reach the
    //       interface. The request contract declares its field at cpy/CCPAURQY.cpy L28 and the field
    //       order of that delimited payload IS the contract, so only internal and persisted names
    //       change; cbl/COPAUA0C.cbl L886 to L887 is the hop between the two spellings. This is the
    //       only rename this context owns -- the two expiration-date renames the migration also
    //       records belong to the account and card contexts, not to this one.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_category_code", length = 4)
    private String merchantCategoryCode;

    /**
     * The acquirer country,
     * {@code PA-ACQR-COUNTRY-CODE PIC X(03)} at {@code cpy/CIPAUDTY.cpy} L37.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acqr_country_code", length = 3)
    private String acqrCountryCode;

    /**
     * How the card details entered the terminal,
     * {@code PA-POS-ENTRY-MODE PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38.
     */
    // WHY : Assumptions: SMALLINT and Short, not CHAR(2), even though the copybook picture has the same
    //       9(nn) display shape as processing_code above. The baseline's own relational declaration is
    //       the deciding reading: ddl/AUTHFRDS.ddl L16 makes POS_ENTRY_MODE a SMALLINT, and the
    //       generated host variable at dcl/AUTHFRDS.dcl L71 is a binary halfword. Entry mode is a
    //       bounded quantity rather than a code whose leading zero carries meaning, which is exactly
    //       the distinction that sends the two fields to different types.
    @Column(name = "pos_entry_mode")
    private Short posEntryMode;

    /**
     * The merchant identifier, {@code PA-MERCHANT-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L39.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 15)
    private String merchantId;

    /**
     * The merchant name, {@code PA-MERCHANT-NAME PIC X(22)} at {@code cpy/CIPAUDTY.cpy} L40.
     */
    // WHY : Assumptions: this is the ONE VARCHAR on this type and it deliberately carries no CHAR type
    //       code, mirroring ddl/AUTHFRDS.ddl L18 where MERCHANT_NAME is the only VARCHAR(22) among
    //       otherwise fixed CHAR columns. Its blank padding is PERSISTED rather than trimmed: the
    //       baseline sets the length half of the varying-length host variable to the full declared
    //       width regardless of content, moving LENGTH OF PA-MERCHANT-NAME -- a constant 22 -- at
    //       cbl/COPAUS2C.cbl L130 before moving the text at L131, against the paired length and text
    //       items at dcl/AUTHFRDS.dcl L73 to L77. Trimming on the way in would change stored bytes the
    //       reference system preserves, so nothing on this path trims.
    @Column(name = "merchant_name", length = 22)
    private String merchantName;

    /**
     * The merchant city, {@code PA-MERCHANT-CITY PIC X(13)} at {@code cpy/CIPAUDTY.cpy} L41.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_city", length = 13)
    private String merchantCity;

    /**
     * The merchant state, {@code PA-MERCHANT-STATE PIC X(02)} at {@code cpy/CIPAUDTY.cpy} L42.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_state", length = 2)
    private String merchantState;

    /**
     * The merchant postal code, {@code PA-MERCHANT-ZIP PIC X(09)} at {@code cpy/CIPAUDTY.cpy} L43.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 9)
    private String merchantZip;

    /**
     * The acquirer's transaction identifier,
     * {@code PA-TRANSACTION-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L44.
     */
    // WHY : Assumptions: non-nullable, because this value together with the card number is the DURABLE
    //       idempotency key of the request path and the migration enforces that pair as unique at its
    //       L570 to L571. The queue's own deduplication identifier suppresses a redelivery only inside
    //       its bounded window; a later redelivery arrives as a new message and the consumer has to
    //       recognise it from stored state, which it can only do if the state is there. The insert
    //       always writes it, at cbl/COPAUA0C.cbl L895, so a null is a row the reference system cannot
    //       produce. The pair is scoped to a card rather than global because the identifier is a
    //       fifteen-character acquirer value at cpy/CCPAURQY.cpy L36 that two acquirers may coincide on.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", nullable = false, length = 15)
    private String transactionId;

    /**
     * Whether the authorization has matched a posted transaction,
     * {@code PA-MATCH-STATUS PIC X(01)} at {@code cpy/CIPAUDTY.cpy} L45.
     */
    // WHY : Assumptions: the domain is FOUR values even though the consumer writes only two of them.
    //       The condition names at cpy/CIPAUDTY.cpy L46 to L49 close it to 'P', 'D', 'E' and 'M', while
    //       cbl/COPAUA0C.cbl L902 to L906 selects only pending on an approval and declined on a
    //       decline; 'E' is reached later by the expiry and purge path and 'M' by transaction matching.
    //       Narrowing the constraint to the two this one program emits would reject rows the other
    //       paths legitimately write, so the migration keeps all four at its L582 to L583.
    // WHY : Assumptions: non-nullable, because those two branches at L902 to L906 are exhaustive --
    //       there is no third branch that leaves the field unset -- and the migration declares the same
    //       at its L492, so the two statements of this invariant agree rather than differ.
    // WHY : Refactoring Rationale: the check constraints behind this column and behind auth_fraud
    //       PROMOTE AN APPLICATION-ONLY INVARIANT INTO THE DATABASE, and that is a change of where the
    //       invariant lives rather than a change of what it is. The baseline's relational table declares
    //       no check constraint and no default clause anywhere in its 28 lines -- ddl/AUTHFRDS.ddl L23
    //       and L24 declare MATCH_STATUS and AUTH_FRAUD as bare CHAR(1), and only CARD_NUM at L2 and
    //       AUTH_TS at L3 carry NOT NULL -- so both domains existed solely in the 88-level condition
    //       names at cpy/CIPAUDTY.cpy L46 to L49 and L51 to L52, which bind only the programs that
    //       reference them. The consequence of that structure is what motivates the move: the flag is
    //       now written by more than one path, the marking flow toggling it in both directions at
    //       cbl/COPAUS1C.cbl L534 to L538 and the extract loading it from the segment, and an invariant
    //       asserted inside one program cannot bind the others. The domains themselves are unchanged.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "match_status", nullable = false, length = 1)
    private String matchStatus;

    /**
     * Whether the authorization has been marked fraudulent,
     * {@code PA-AUTH-FRAUD PIC X(01)} at {@code cpy/CIPAUDTY.cpy} L50.
     */
    // WHY : Assumptions: the ordinary state of a never-marked authorization is a SPACE, not a null, and
    //       the constraint at migration L607 to L608 admits 'F', 'R', null AND a single space for that
    //       reason. This is a factual reading rather than defensive width: cbl/COPAUA0C.cbl L908 to
    //       L909 moves SPACE into this field and into the report date on EVERY insert, and those two
    //       lines are the only writes to either field anywhere in that program. The detail screen
    //       corroborates it -- the ELSE at cbl/COPAUS1C.cbl L349 renders a bare hyphen, and it is
    //       reachable exactly when the flag is neither of the two condition names at L51 to L52.
    //       Narrowing the domain to those two plus null would reject rows the extract routinely loads.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_fraud", length = 1)
    private String authFraud;

    /**
     * When the fraud mark was applied,
     * {@code PA-FRAUD-RPT-DATE PIC X(08)} at {@code cpy/CIPAUDTY.cpy} L53.
     */
    // WHY : Assumptions: CHAR(8) holding the eight characters themselves, matching the migration at its
    //       L508 to L536. The characters are MM/DD/YY -- cbl/COPAUS2C.cbl L95 to L100 formats the
    //       current date with a month-first pattern and a separator and its L101 moves the result
    //       straight in -- so they are not ISO-ordered, and widening the two-digit year would need a
    //       century pivot the baseline never chose. The decisive constraint is that the reference
    //       system writes SPACES into this field outright at cbl/COPAUA0C.cbl L908 to L909, which a
    //       DATE column cannot hold at all; storing the characters keeps every state the segment can be
    //       in, including that blank one.
    // WHY : Trade-offs: the relational fraud table keeps a real DATE for its own copy of this value,
    //       because ddl/AUTHFRDS.ddl L25 declares it that way, so the two representations of one date
    //       still differ in the target exactly as they differ in the baseline. The conversion is
    //       therefore performed at the single boundary that writes that relational row, rather than
    //       being forced onto every segment row that has no report date at all. The column is nullable
    //       as well as blank-tolerant: a row loaded from an extract that carried no value is null here
    //       and a row the reference system blanked is eight spaces, and neither is invented.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fraud_rpt_date", length = 8)
    private String fraudReportDate;


    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a no-argument constructor on an entity.
     * It is protected rather than public because a detail row with no key, no card number and no
     * amounts is meaningless to any caller outside this hierarchy, and the constructor below is the
     * only way application code should reach a populated one.</p>
     */
    protected PendingAuthDetail() {
        // Assumptions: empty by design; the provider assigns every mapped field after construction.
    }

    /**
     * Creates a fully-specified detail row from a decoded authorization request and the decision
     * reached on it.
     *
     * <p>Assumptions: the parameter order follows the segment's own field order at
     * {@code cpy/CIPAUDTY.cpy} L19 to L45, so a reader checking a call site against the copybook
     * reads both in the same sequence -- and the match status is the LAST parameter because L45 is
     * where the copybook declares it.</p>
     *
     * <p>Refactoring Rationale: the match status IS a parameter, and an earlier revision of this
     * constructor set {@link #MATCH_STATUS_PENDING} itself on the ground that a newly-recorded
     * authorization is always pending. That ground was wrong, and it was wrong in the direction that
     * loses money rather than merely mis-labelling a row. The reference insert path selects between
     * TWO values on the decision it just reached: {@code cbl/COPAUA0C.cbl} L902 tests
     * {@code IF AUTH-RESP-APPROVED}, L903 sets {@code PA-MATCH-PENDING} on that branch, and L905 sets
     * {@code PA-MATCH-AUTH-DECLINED} on the else branch, with no third outcome. Fixing the value to
     * pending therefore persisted every DECLINED authorization as one still awaiting a match, which
     * the summary screen renders as an open commitment and which the purge job at
     * {@code cbl/CBPAUP0C.cbl} treats as a live pending row to age out -- so a decline consumed
     * pending capacity it had never been granted. The value now travels from the deciding service,
     * which is the only place that knows which branch was taken.</p>
     *
     * <p>Alternatives Considered: passing the decision object itself, or a boolean approval flag, and
     * letting this constructor map it to a character. Rejected because it would put the branch in two
     * places: the caller already renders the same decision into the response code and the approved
     * amount, and a second, independent mapping here could disagree with those two without any
     * mechanism noticing. Passing the persisted character keeps one mapping, and the validation below
     * is what stops a caller passing a character the insert path cannot reach.</p>
     *
     * <p>Trade-offs: every value is supplied at construction and there is no mutator for any of them
     * except the two fraud members, which move together in {@link #applyFraudMark(String, String)}. A
     * pending authorization is an immutable record of what an acquirer presented and what was
     * answered; the only thing that legitimately changes afterwards is whether it was later reported
     * fraudulent, which is the one transition the reference system performs on an existing segment.
     * A builder would read more comfortably at the three call sites --
     * {@code service/AuthorizationRequestListener.java} L521 and two test fixtures -- and it would
     * also make a half-populated instance representable, which this shape makes impossible. The
     * accepted cost is a long parameter list whose order has to be read against the copybook rather
     * than checked by the compiler, since every character parameter has the same type.</p>
     *
     * <p>Assumptions: the two amounts arrive already decoded and already at scale two. This
     * constructor does not decode a packed quantity, parse a character date or mask a card number,
     * because each of those is a representation concern belonging to the extract and the mapper --
     * the one boundary this migration allows copybook representation to appear at. An entity that
     * decoded its own input would put that boundary in two places.</p>
     *
     * @param id the three-part key of account, authorization date and authorization time; must not
     *     be {@code null}, and its two clock parts hold decoded rather than complemented values
     * @param authOrigDate the six characters of the acquirer's originating date, year first; may be
     *     {@code null}
     * @param authOrigTime the six characters of the acquirer's originating time; may be {@code null}
     * @param cardNum the sixteen-character primary account number presented, unmasked because it is
     *     a key; must not be {@code null}
     * @param authType the four-character authorization type; may be {@code null}
     * @param cardExpiryDate the four characters of the card expiry code as presented, without the
     *     separator the detail screen inserts; may be {@code null}
     * @param messageType the six-character network message type; may be {@code null}
     * @param messageSource the six-character network message source; may be {@code null}
     * @param authIdCode the six-character authorization identification code returned to the
     *     acquirer; may be {@code null}
     * @param authRespCode the two-character response code returned to the acquirer, unconstrained
     *     because its single condition name closes no domain; may be {@code null}
     * @param authRespReason the four-character response reason returned to the acquirer; may be
     *     {@code null}
     * @param processingCode the six digits of the processing code as characters, so that leading
     *     zeros survive; may be {@code null}
     * @param transactionAmount the amount the acquirer requested, exact at scale two; must not be
     *     {@code null}
     * @param approvedAmount the amount actually approved, exact at scale two and zero on a decline;
     *     must not be {@code null}
     * @param merchantCategoryCode the four-character merchant category, spelled correctly here while
     *     the wire keeps the baseline spelling; may be {@code null}
     * @param acqrCountryCode the three-character acquirer country; may be {@code null}
     * @param posEntryMode how the card details entered the terminal, as a bounded small integer; may
     *     be {@code null}, and when present must lie between {@value #POS_ENTRY_MODE_MIN} and
     *     {@value #POS_ENTRY_MODE_MAX} inclusive
     * @param merchantId the fifteen-character merchant identifier; may be {@code null}
     * @param merchantName the merchant name at its declared width, blank padding included and never
     *     trimmed; may be {@code null}
     * @param merchantCity the thirteen-character merchant city; may be {@code null}
     * @param merchantState the two-character merchant state; may be {@code null}
     * @param merchantZip the nine-character merchant postal code; may be {@code null}
     * @param transactionId the acquirer's fifteen-character transaction identifier, which with the
     *     card number forms the durable idempotency key; must not be {@code null}
     * @param matchStatus the state the decision places the authorization in, from
     *     {@code PA-MATCH-STATUS PIC X(01)} at {@code cpy/CIPAUDTY.cpy} L45: must be
     *     {@link #MATCH_STATUS_PENDING} when the authorization was approved or
     *     {@link #MATCH_STATUS_DECLINED} when it was declined, and must not be {@code null}, because
     *     the column is {@code NOT NULL} and the reference system's two decision branches are
     *     exhaustive
     * @throws NullPointerException if {@code matchStatus} is {@code null}
     * @throws IllegalArgumentException if {@code matchStatus} is outside the column's four-value
     *     domain, or is inside it but not one of {@link #ORIGINATED_MATCH_STATUSES}, or if
     *     {@code posEntryMode} is present and outside its two-digit range
     */
    public PendingAuthDetail(PendingAuthDetailKey id, String authOrigDate, String authOrigTime,
            String cardNum, String authType, String cardExpiryDate, String messageType,
            String messageSource, String authIdCode, String authRespCode, String authRespReason,
            String processingCode, BigDecimal transactionAmount, BigDecimal approvedAmount,
            String merchantCategoryCode, String acqrCountryCode, Short posEntryMode,
            String merchantId, String merchantName, String merchantCity, String merchantState,
            String merchantZip, String transactionId, String matchStatus) {
        this(id, authOrigDate, authOrigTime, cardNum, authType, cardExpiryDate, messageType,
                messageSource, authIdCode, authRespCode, authRespReason, processingCode,
                transactionAmount, approvedAmount, merchantCategoryCode, acqrCountryCode,
                posEntryMode, merchantId, merchantName, merchantCity, merchantState, merchantZip,
                transactionId, matchStatus, MatchStatusProvenance.ORIGINATED);
    }

    /**
     * Reconstitutes a row that already exists in the store, in whichever of the four states it holds.
     *
     * <p>Purpose: this is the rehydration entry point, and it exists because the constructor above cannot
     * serve one. That constructor originates a NEW decision, so it accepts only the two states an insert
     * reaches; a row being loaded may legitimately carry either of the other two --
     * {@link #MATCH_STATUS_PENDING_EXPIRED} written by the purge job and
     * {@link #MATCH_STATUS_MATCHED_WITH_TRAN} written by the posting match -- and the extract that seeds
     * this deployment carries rows in all four. The committed fixture
     * {@code pautdtl1-match-status-domain.bin} holds one of each.
     *
     * <p>Refactoring Rationale: rehydration previously went through the originating constructor, so an
     * expired or matched row COULD NOT BE LOADED AT ALL -- the load failed with a message stating the
     * value "is reached by a later transition and never by an insert", which was true of the constructor
     * and false of the row. The two paths are now separate methods over one private constructor, and the
     * only thing that differs between them is which set the match status is checked against. Widening the
     * constructor instead was rejected: it would have let the decision path persist a row claiming to have
     * been matched against a transaction that does not exist, which is the defect the narrow check was
     * introduced to prevent, and no constraint could catch it because all four values are legal for the
     * column.
     *
     * <p>Assumptions: every OTHER invariant is unchanged and still applies here -- the entry mode's
     * two-digit range, the match status membership of the column's four-value domain, and the required
     * key. Rehydration relaxes exactly one rule, and it relaxes it to the schema's own
     * {@code ck_pending_auth_detail_match_status}, so a value this method accepts is a value the column
     * accepts. A rehydration path that skipped validation altogether was rejected for the same reason the
     * mapper refuses a partially applied summary: a malformed stored row should be reported when it is
     * read, naming the component, rather than propagated into the application as though it were sound.
     *
     * <p>Assumptions: the two fraud members are NOT parameters here either. A loaded row that carries a
     * marking is applied through {@link #applyFraudMark(String, String)} by the caller that decoded it,
     * which is the same operation the marking screen uses, so a marking reaches these members through one
     * path however it arrived.
     *
     * @param id the three-part key of account, authorization date and authorization time; must not be
     *     {@code null}, and its two clock parts hold decoded rather than complemented values
     * @param authOrigDate the six characters of the acquirer's originating date; may be {@code null}
     * @param authOrigTime the six characters of the acquirer's originating time; may be {@code null}
     * @param cardNum the sixteen-character primary account number presented; must not be {@code null}
     * @param authType the four-character authorization type; may be {@code null}
     * @param cardExpiryDate the four characters of the card expiry code; may be {@code null}
     * @param messageType the six-character network message type; may be {@code null}
     * @param messageSource the six-character network message source; may be {@code null}
     * @param authIdCode the six-character authorization identification code; may be {@code null}
     * @param authRespCode the two-character response code; may be {@code null}
     * @param authRespReason the four-character response reason; may be {@code null}
     * @param processingCode the six digits of the processing code as characters; may be {@code null}
     * @param transactionAmount the amount the acquirer requested, exact at scale two; must not be
     *     {@code null}
     * @param approvedAmount the amount actually approved, exact at scale two; must not be {@code null}
     * @param merchantCategoryCode the four-character merchant category; may be {@code null}
     * @param acqrCountryCode the three-character acquirer country; may be {@code null}
     * @param posEntryMode how the card details entered the terminal; may be {@code null}, and when
     *     present must lie between {@value #POS_ENTRY_MODE_MIN} and {@value #POS_ENTRY_MODE_MAX}
     * @param merchantId the fifteen-character merchant identifier; may be {@code null}
     * @param merchantName the merchant name at its declared width, never trimmed; may be {@code null}
     * @param merchantCity the thirteen-character merchant city; may be {@code null}
     * @param merchantState the two-character merchant state; may be {@code null}
     * @param merchantZip the nine-character merchant postal code; may be {@code null}
     * @param transactionId the acquirer's fifteen-character transaction identifier; must not be
     *     {@code null}
     * @param matchStatus the state the stored row holds, which may be any of the four the column admits
     *     and must not be {@code null}
     * @return the reconstituted row, never {@code null}
     * @throws NullPointerException if {@code matchStatus} is {@code null}
     * @throws IllegalArgumentException if {@code matchStatus} is outside the column's four-value domain,
     *     or if {@code posEntryMode} is present and outside its two-digit range
     */
    public static PendingAuthDetail rehydrated(PendingAuthDetailKey id, String authOrigDate,
            String authOrigTime, String cardNum, String authType, String cardExpiryDate,
            String messageType, String messageSource, String authIdCode, String authRespCode,
            String authRespReason, String processingCode, BigDecimal transactionAmount,
            BigDecimal approvedAmount, String merchantCategoryCode, String acqrCountryCode,
            Short posEntryMode, String merchantId, String merchantName, String merchantCity,
            String merchantState, String merchantZip, String transactionId, String matchStatus) {
        return new PendingAuthDetail(id, authOrigDate, authOrigTime, cardNum, authType,
                cardExpiryDate, messageType, messageSource, authIdCode, authRespCode,
                authRespReason, processingCode, transactionAmount, approvedAmount,
                merchantCategoryCode, acqrCountryCode, posEntryMode, merchantId, merchantName,
                merchantCity, merchantState, merchantZip, transactionId, matchStatus,
                MatchStatusProvenance.REHYDRATED);
    }

    /**
     * Assigns every member, validating the match status against the set its provenance admits.
     *
     * <p>Assumptions: the provenance is the LAST parameter and is what distinguishes this constructor
     * from the public one, whose parameter list is otherwise identical. A private constructor with the
     * same erasure as a public one is not expressible, so the discriminator has to be a parameter rather
     * than a convention; making it an enumeration rather than a boolean means each call site reads as the
     * path it is instead of as {@code true} or {@code false}.</p>
     *
     * @param id the three-part key; must not be {@code null}
     * @param authOrigDate the acquirer's originating date; may be {@code null}
     * @param authOrigTime the acquirer's originating time; may be {@code null}
     * @param cardNum the primary account number presented; must not be {@code null}
     * @param authType the authorization type; may be {@code null}
     * @param cardExpiryDate the card expiry code; may be {@code null}
     * @param messageType the network message type; may be {@code null}
     * @param messageSource the network message source; may be {@code null}
     * @param authIdCode the authorization identification code; may be {@code null}
     * @param authRespCode the response code; may be {@code null}
     * @param authRespReason the response reason; may be {@code null}
     * @param processingCode the processing code as characters; may be {@code null}
     * @param transactionAmount the requested amount at scale two; must not be {@code null}
     * @param approvedAmount the approved amount at scale two; must not be {@code null}
     * @param merchantCategoryCode the merchant category; may be {@code null}
     * @param acqrCountryCode the acquirer country; may be {@code null}
     * @param posEntryMode the entry mode; may be {@code null} and is range-checked when present
     * @param merchantId the merchant identifier; may be {@code null}
     * @param merchantName the merchant name, never trimmed; may be {@code null}
     * @param merchantCity the merchant city; may be {@code null}
     * @param merchantState the merchant state; may be {@code null}
     * @param merchantZip the merchant postal code; may be {@code null}
     * @param transactionId the acquirer's transaction identifier; must not be {@code null}
     * @param matchStatus the state to record; must not be {@code null}
     * @param provenance which set of match statuses this construction may use; must not be {@code null}
     * @throws NullPointerException if {@code matchStatus} or {@code provenance} is {@code null}
     * @throws IllegalArgumentException if the match status is outside the set the provenance admits, or
     *     if the entry mode is present and out of range
     */
    private PendingAuthDetail(PendingAuthDetailKey id, String authOrigDate, String authOrigTime,
            String cardNum, String authType, String cardExpiryDate, String messageType,
            String messageSource, String authIdCode, String authRespCode, String authRespReason,
            String processingCode, BigDecimal transactionAmount, BigDecimal approvedAmount,
            String merchantCategoryCode, String acqrCountryCode, Short posEntryMode,
            String merchantId, String merchantName, String merchantCity, String merchantState,
            String merchantZip, String transactionId, String matchStatus,
            MatchStatusProvenance provenance) {
        this.id = id;
        this.authOrigDate = authOrigDate;
        this.authOrigTime = authOrigTime;
        this.cardNum = cardNum;
        this.authType = authType;
        this.cardExpiryDate = cardExpiryDate;
        this.messageType = messageType;
        this.messageSource = messageSource;
        this.authIdCode = authIdCode;
        this.authRespCode = authRespCode;
        this.authRespReason = authRespReason;
        this.processingCode = processingCode;
        this.transactionAmount = transactionAmount;
        this.approvedAmount = approvedAmount;
        this.merchantCategoryCode = merchantCategoryCode;
        this.acqrCountryCode = acqrCountryCode;
        this.posEntryMode = requirePosEntryModeInRange(posEntryMode);
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantState = merchantState;
        this.merchantZip = merchantZip;
        this.transactionId = transactionId;

        // WHY : Assumptions: the match status is validated here rather than relied upon from the check
        //       constraint alone, because the constraint only fires at flush time. A row built with an
        //       out-of-domain status would otherwise travel through the whole handler and fail as an
        //       opaque constraint violation at commit, naming the column but not the call site that
        //       chose the value. The accepted set is narrower than the column's, for the reason
        //       recorded on ORIGINATED_MATCH_STATUSES.
        // WHY : Alternatives Considered: giving the column a database default instead of taking the
        //       value from the caller. Rejected because a default can express one of the two insert
        //       outcomes at most, and would then be silently wrong for the other -- and wrong in a
        //       direction no constraint could catch, since both values are legal for the column to
        //       hold. Only the caller that reached the decision knows which branch this row is.
        // WHY : Refactoring Rationale: the set the value is checked against comes from the PROVENANCE
        //       rather than being fixed to the originated pair, and it was fixed. An originating
        //       decision may reach only the two values the reference insert selects between; a row
        //       being loaded may hold any of the four the column admits, and routing a load through
        //       the narrow check made an expired or matched row impossible to read at all. One check
        //       with two sets keeps both rules in one place, where a reader can see that the load
        //       relaxes exactly one of them and nothing else.
        this.matchStatus = Objects
                .requireNonNull(provenance, "provenance must not be null")
                .requireInDomain(matchStatus);

        // WHY : Assumptions: the two fraud members are deliberately left unassigned. Their ordinary
        //       state is a space the extract supplies or a null this deployment writes -- the reference
        //       moves SPACES into both at cbl/COPAUA0C.cbl L908 to L909 -- and inventing either here
        //       would assert a fraud position about an authorization nobody has yet examined.
    }

    /**
     * Which set of match statuses a construction of this type may choose from.
     *
     * <p>Purpose: the two entry points of this class differ in exactly one rule, and this enumeration is
     * that rule made explicit. An originating decision may record only the two values the reference insert
     * selects between; a row being reconstituted from the store may hold any of the four its column
     * admits. Both checks live here, side by side, so a reader can see the whole of the difference in one
     * place rather than inferring it from two constructors.
     *
     * <p>Alternatives Considered: a boolean parameter on the private constructor. Rejected because
     * {@code false} at a call site says nothing about which path it is, whereas the two names below do,
     * and because a boolean invites a third state to be squeezed in as a second boolean later.
     * Alternatives Considered: two full constructors each with its own check. Rejected because the
     * twenty-three assignments beside the check would then exist twice, and the copy that was edited
     * without the other is the defect this class has already had once.
     */
    private enum MatchStatusProvenance {

        /**
         * A new decision, which may record only the two values an insert reaches.
         *
         * <p>Assumptions: the pair is {@link PendingAuthDetail#ORIGINATED_MATCH_STATUSES}, taken from
         * {@code cbl/COPAUA0C.cbl} L902 to L906, whose test has exactly two branches and no default.</p>
         */
        ORIGINATED(ORIGINATED_MATCH_STATUSES),

        /**
         * A row read back from the store, which may hold any state the column admits.
         *
         * <p>Assumptions: the set is {@link PendingAuthDetail#MATCH_STATUS_DOMAIN}, which is the schema's
         * own {@code ck_pending_auth_detail_match_status}, so a value this provenance accepts is a value
         * the column accepts and nothing wider.</p>
         */
        REHYDRATED(MATCH_STATUS_DOMAIN);

        /** The values a construction of this provenance may record. */
        private final Collection<String> admitted;

        /**
         * Binds a provenance to the set of match statuses it admits.
         *
         * @param admittedStatuses the values this provenance may record; must not be {@code null}
         */
        MatchStatusProvenance(Collection<String> admittedStatuses) {
            this.admitted = admittedStatuses;
        }

        /**
         * Returns the supplied match status if this provenance may record it.
         *
         * <p>Assumptions: the refusal is staged, and the two stages report different faults. A value the
         * copybook does not name at all is not a match status; a value it does name but that only a later
         * transition reaches is a match status an INSERT may not originate. Collapsing the two would
         * report a lower-case {@code 'p'} and a legitimately-loaded {@code 'E'} with the same sentence,
         * and only one of those is a spelling mistake.</p>
         *
         * <p>Assumptions: a bare {@link IllegalArgumentException} rather than a bean-validation
         * annotation, because this invariant has to hold for every instance including the ones a test
         * builds directly, and bean validation runs only where a validator is wired. Each message names
         * the rejected value so a caller that passed a lower-case spelling can see which one it was.</p>
         *
         * @param candidate the match status a caller supplied; must not be {@code null}
         * @return {@code candidate} unchanged, once this provenance is known to admit it
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if {@code candidate} is outside
         *     {@link PendingAuthDetail#MATCH_STATUS_DOMAIN}, or is inside it but outside the set this
         *     provenance admits
         */
        private String requireInDomain(String candidate) {
            // WHY : Assumptions: null is refused FIRST and by name, rather than being left for either
            //       membership test to reject. Both Set.of and List.of build immutable collections whose
            //       contains THROWS NullPointerException on a null argument instead of returning false, so
            //       relying on membership alone would raise an NPE from inside a collection -- a refusal
            //       that names neither the field nor the invariant. requireNonNull raises the same
            //       exception type the caller documents, carrying the component's name.
            Objects.requireNonNull(candidate, "matchStatus must not be null");
            if (!MATCH_STATUS_DOMAIN.contains(candidate)) {
                throw new IllegalArgumentException("matchStatus is not a match status value: the "
                        + "copybook's condition names at cpy/CIPAUDTY.cpy L46 to L49 close the domain to "
                        + "P, D, E or M, of which only P and D may be originated per cbl/COPAUA0C.cbl "
                        + "L902 to L906, but was: " + candidate);
            }
            if (!this.admitted.contains(candidate)) {
                throw new IllegalArgumentException("matchStatus must be one of "
                        + ORIGINATED_MATCH_STATUSES + " -- '" + MATCH_STATUS_PENDING
                        + "' when the authorization was approved and '" + MATCH_STATUS_DECLINED
                        + "' when it was declined, per cbl/COPAUA0C.cbl L902 to L906; '" + candidate
                        + "' is reached by a later transition and never by an insert");
            }
            return candidate;
        }
    }


    /**
     * Returns the supplied point-of-sale entry mode if it fits the copybook's two unsigned digits.
     *
     * <p>Assumptions: {@code null} is permitted and returned unchanged, because the column is
     * nullable and an extract may legitimately carry no entry mode. Only a PRESENT value is bounded,
     * which is the distinction between a missing field and an impossible one.</p>
     *
     * @param candidate the entry mode a caller supplied; may be {@code null}
     * @return {@code candidate} unchanged, once it is known to be absent or in range
     * @throws IllegalArgumentException if {@code candidate} is present and lies outside {@value
     *     #POS_ENTRY_MODE_MIN} to {@value #POS_ENTRY_MODE_MAX} inclusive
     */
    private static Short requirePosEntryModeInRange(Short candidate) {
        if (candidate != null
                && (candidate < POS_ENTRY_MODE_MIN || candidate > POS_ENTRY_MODE_MAX)) {
            throw new IllegalArgumentException("pos entry mode must be between "
                    + POS_ENTRY_MODE_MIN + " and " + POS_ENTRY_MODE_MAX + " but was: " + candidate);
        }
        return candidate;
    }


    /**
     * Returns the three-part key.
     *
     * <p>Assumptions: the key's two clock parts hold DECODED values, and this accessor hands them
     * back as stored. The segment encodes them as nines complements -- {@code cbl/COPAUA0C.cbl} L874
     * computes the date part as a constant less the ordinal date and its L875 the time part the same
     * way, and both are decoded again on every read, at {@code cbl/CBPAUP0C.cbl} L280 and
     * {@code cbl/COPAUS2C.cbl} L107 with the same two constants. A caller ordering on these values
     * therefore orders on the real date and time, descending for most-recent-first.</p>
     *
     * <p>Alternatives Considered: persisting the complemented form the segment holds, so that an
     * ascending scan would still yield newest-first with no ordering clause. Rejected because the
     * complement exists only to make a hierarchic unique sequence field -- an eight-byte CHARACTER
     * sequence per {@code ims/DBPAUTP0.dbd} L37 -- sort the wanted way, and PostgreSQL expresses that
     * ordering in the query instead. Keeping the encoding would leave the key superficially
     * functional while every rendered date came out wrong and every comparison came out inverted, and
     * every predicate and every {@code ORDER BY} in the repository would have to be written against
     * an inverted encoding to compensate.</p>
     *
     * @return the key, never {@code null} on a persisted instance
     */
    public PendingAuthDetailKey getId() {
        return this.id;
    }

    /**
     * Returns the primary account number the authorization was presented against.
     *
     * <p>Assumptions: the value is returned unmasked because the callers that need it need all
     * sixteen characters -- the reply encoder must echo what the acquirer sent, and the idempotency
     * lookup at {@code repository/PendingAuthDetailRepository.java} L131 keys on it. Masking happens
     * at the presentation boundary instead, in {@code mapper/PendingAuthViewMapper.java}, which masks
     * this value on both of the paths that publish it, at its L186 and L214. That is the only place
     * masking happens, which is why this type carries no rendering of its own that could bypass
     * it.</p>
     *
     * @return the sixteen-character primary account number, never {@code null} on a persisted
     *     instance
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the acquirer's transaction identifier.
     *
     * @return the fifteen-character identifier, never {@code null} on a persisted instance
     */
    public String getTransactionId() {
        return this.transactionId;
    }

    /**
     * Returns the originating date the acquirer supplied.
     *
     * <p>Assumptions: the six characters come back exactly as stored, year first, and are not
     * recomposed here. The reference program displays them month first with separators, splitting the
     * field at {@code cbl/COPAUS0C.cbl} L531 to L534, and that recomposition is a rendering the
     * client performs; performing it here would put a presentation concern inside the persistence
     * layer and would leave two different orderings of one value in circulation.</p>
     *
     * @return the six stored characters, or {@code null} when the request carried none
     */
    public String getAuthOrigDate() {
        return this.authOrigDate;
    }

    /**
     * Returns the originating time the acquirer supplied.
     *
     * @return the six stored characters, or {@code null} when the request carried none
     */
    public String getAuthOrigTime() {
        return this.authOrigTime;
    }

    /**
     * Returns the authorization type the acquirer supplied.
     *
     * @return the four-character type, or {@code null} when the request carried none
     */
    public String getAuthType() {
        return this.authType;
    }

    /**
     * Returns the card expiry code as the four characters stored against this authorization.
     *
     * <p>Assumptions: the separator the detail screen shows at position three is inserted on the way
     * to that screen, by the three moves at {@code cbl/COPAUS1C.cbl} L336 to L338, so it is a
     * rendering and not part of the stored value. This accessor returns the stored four.</p>
     *
     * @return the four stored characters, or {@code null} when the request carried none
     */
    public String getCardExpiryDate() {
        return this.cardExpiryDate;
    }

    /**
     * Returns the message type the acquirer supplied.
     *
     * @return the six-character message type, or {@code null} when the request carried none
     */
    public String getMessageType() {
        return this.messageType;
    }

    /**
     * Returns the message source the acquirer supplied.
     *
     * @return the six-character message source, or {@code null} when the request carried none
     */
    public String getMessageSource() {
        return this.messageSource;
    }

    /**
     * Returns the processing code the acquirer supplied.
     *
     * <p>Assumptions: the six digits come back as characters, leading zeros intact, because the
     * column stores them as characters for exactly that reason -- the reference relational table
     * declares {@code PROCESSING_CODE CHAR(6)} at {@code ddl/AUTHFRDS.ddl} L11 from a numeric
     * picture. A caller needing arithmetic on them would be treating a code as a quantity.</p>
     *
     * @return the six stored digits as characters, or {@code null} when the request carried none
     */
    public String getProcessingCode() {
        return this.processingCode;
    }

    /**
     * Returns the merchant category code the acquirer supplied.
     *
     * <p>Assumptions: this accessor is spelled correctly while the segment field it carries is not,
     * which is the target side of the one rename this context owns. The lineage is recorded in
     * {@code docs/architecture/data-model-and-schema-mapping.md} so that no reader has to work out
     * whether the two names denote the same field.</p>
     *
     * @return the four-character category code, or {@code null} when the request carried none
     */
    public String getMerchantCategoryCode() {
        return this.merchantCategoryCode;
    }

    /**
     * Returns the acquirer country code.
     *
     * @return the three-character country code, or {@code null} when the request carried none
     */
    public String getAcqrCountryCode() {
        return this.acqrCountryCode;
    }

    /**
     * Returns the point-of-sale entry mode the acquirer supplied.
     *
     * @return the entry mode as a small integer, or {@code null} when the request carried none
     */
    public Short getPosEntryMode() {
        return this.posEntryMode;
    }

    /**
     * Returns the merchant identifier the acquirer supplied.
     *
     * @return the fifteen-character merchant identifier, or {@code null} when the request carried
     *     none
     */
    public String getMerchantId() {
        return this.merchantId;
    }

    /**
     * Returns the merchant name the acquirer supplied.
     *
     * <p>Assumptions: the value comes back with its blank padding intact, because the padding is
     * stored rather than trimmed -- {@code cbl/COPAUS2C.cbl} L130 sets the varying-length host
     * variable to the full declared width before its L131 moves the text, so the reference system
     * persists those blanks. A caller that wants a display form trims at the presentation boundary;
     * trimming here would make the stored bytes unobservable through the only accessor that reads
     * them.</p>
     *
     * @return the merchant name including any trailing blanks, or {@code null} when the request
     *     carried none
     */
    public String getMerchantName() {
        return this.merchantName;
    }

    /**
     * Returns the merchant city the acquirer supplied.
     *
     * @return the thirteen-character merchant city, or {@code null} when the request carried none
     */
    public String getMerchantCity() {
        return this.merchantCity;
    }

    /**
     * Returns the merchant state the acquirer supplied.
     *
     * @return the two-character state, or {@code null} when the request carried none
     */
    public String getMerchantState() {
        return this.merchantState;
    }

    /**
     * Returns the merchant postal code the acquirer supplied.
     *
     * @return the nine-character postal code, or {@code null} when the request carried none
     */
    public String getMerchantZip() {
        return this.merchantZip;
    }

    /**
     * Returns the authorization identification code that was returned to the acquirer.
     *
     * @return the six-character identification code, or {@code null} when none was assigned
     */
    public String getAuthIdCode() {
        return this.authIdCode;
    }

    /**
     * Returns the response code that was returned to the acquirer.
     *
     * <p>Assumptions: the value is not constrained to a domain, so a caller deciding whether this
     * authorization was approved compares it against the approval sentinel rather than switching over
     * an enumeration. The copybook names only that one value, at {@code cpy/CIPAUDTY.cpy} L31, and
     * leaves every decline code unnamed.</p>
     *
     * @return the two-character response code, or {@code null} when none was assigned
     */
    public String getAuthRespCode() {
        return this.authRespCode;
    }

    /**
     * Returns the response reason that was returned to the acquirer.
     *
     * @return the four-character response reason, or {@code null} when none was assigned
     */
    public String getAuthRespReason() {
        return this.authRespReason;
    }

    /**
     * Returns the amount the acquirer requested.
     *
     * @return the requested amount, exact at scale two and never {@code null} on a persisted instance
     */
    public BigDecimal getTransactionAmount() {
        return this.transactionAmount;
    }

    /**
     * Returns the amount actually approved.
     *
     * @return the approved amount, exact at scale two, zero on a decline and never {@code null} on a
     *     persisted instance
     */
    public BigDecimal getApprovedAmount() {
        return this.approvedAmount;
    }

    /**
     * Returns whether the authorization has matched a posted transaction.
     *
     * @return one of the four one-character match statuses, never {@code null} on a persisted
     *     instance
     */
    public String getMatchStatus() {
        return this.matchStatus;
    }

    /**
     * Returns the fraud indicator.
     *
     * @return {@code 'F'} or {@code 'R'} as a one-character string, a single space when the extract
     *     supplied the reference system's blank, or {@code null} when no value was loaded
     */
    public String getAuthFraud() {
        return this.authFraud;
    }

    /**
     * Returns when the fraud mark was applied.
     *
     * @return the eight stored characters, eight spaces when the extract supplied the reference
     *     system's blank, or {@code null} when no value was loaded
     */
    public String getFraudReportDate() {
        return this.fraudReportDate;
    }

    /**
     * Moves this authorization to a stated fraud position, as of a stated date.
     *
     * <p>Assumptions: the indicator and the report date move together in one method because the
     * reference system writes both in one segment replace -- {@code cbl/COPAUS1C.cbl} L525 to L528
     * rewrites the whole segment, and {@code cbl/COPAUS2C.cbl} L101 sets the report date beside the
     * flag -- so a row carrying one without the other is a state neither program produces. Two
     * separate mutators would let a caller set the flag and omit the date, and neither check
     * constraint could catch it because each column would still be individually valid. The date is
     * supplied by the caller rather than read from a clock here, so the marking flow controls the
     * value that is stored and the same call is reproducible in a test.</p>
     *
     * <p>Refactoring Rationale: this replaces a method that took only a date and always wrote
     * {@link #FRAUD_REPORTED}. That shape could not express a WITHDRAWAL at all, so the published
     * marking contract -- whose action field admits {@code F} and {@code R} -- had a state the entity
     * could not reach, and the {@code R} half of the interface was unimplementable through this type.
     * The target state is now a parameter, which makes both published actions reachable through one
     * transition and keeps the pairing with the date that the reference system's single segment
     * replace establishes.</p>
     *
     * <p>Assumptions: the report date is required for BOTH target states, not only for a report.
     * That is a reading of the reference marking flow rather than a symmetry preference:
     * {@code cbl/COPAUS2C.cbl} L101 performs {@code MOVE WS-CUR-DATE TO PA-FRAUD-RPT-DATE}
     * unconditionally, before either the insert path at its L199 to L201 or the update path at its
     * L230 to L232 is chosen, and {@code cbl/COPAUS1C.cbl} L520 to L528 copies the whole amended
     * segment back for either direction of its L236 to L241 toggle. So a withdrawal stamps the date
     * it was withdrawn on exactly as a report stamps the date it was reported, and a caller that
     * omitted it for a withdrawal would produce a row the reference system never writes.</p>
     *
     * <p>Alternatives Considered: clearing the date to blanks on a withdrawal, on the reading that a
     * withdrawn report has no report date. Rejected because the only program that blanks these two
     * fields is the INSERT path at {@code cbl/COPAUA0C.cbl} L908 to L909, which blanks them together
     * as the never-examined state; no marking path blanks either one. Treating {@code R} as a return
     * to that state would erase the evidence that the authorization was examined at all.</p>
     *
     * <p>Trade-offs: the transition is idempotent and does not refuse a repeat -- marking an already
     * reported authorization as reported again succeeds and restamps the date. This mirrors the
     * reference system, where {@code cbl/COPAUS2C.cbl} L203 detects the duplicate key and performs
     * the update paragraph rather than failing, so a repeated mark is an accepted update there too.
     * The accepted cost is that a caller cannot learn from this method whether the position changed;
     * a caller that needs to distinguish an insert from an update reads the prior value through
     * {@link #getAuthFraud()} before calling.</p>
     *
     * @param fraudState the position to move to, which must be {@link #FRAUD_REPORTED} to report this
     *     authorization as fraudulent or {@link #FRAUD_REMOVED} to withdraw an existing report; must
     *     not be {@code null}, and neither the blank nor the null never-examined state may be set
     *     through this transition
     * @param reportDate the eight characters of the date the mark was applied, in the month-first form
     *     the reference system writes; must not be {@code null} and must be exactly {@value
     *     #FRAUD_REPORT_DATE_LENGTH} characters for either target state
     * @throws IllegalArgumentException if {@code fraudState} is not one of the two marking values, or
     *     if {@code reportDate} is {@code null} or is not exactly {@value #FRAUD_REPORT_DATE_LENGTH}
     *     characters long
     */
    public void applyFraudMark(String fraudState, String reportDate) {
        // WHY : Assumptions: the null test precedes the membership test for the reason recorded on
        //       MatchStatusProvenance.requireInDomain -- a Set.of set throws on contains(null) rather than
        //       answering false, so membership alone would surface an NPE from inside the collection
        //       instead of the refusal this method documents.
        if (fraudState == null || !FRAUD_MARK_DOMAIN.contains(fraudState)) {
            throw new IllegalArgumentException("fraud state must be F or R but was: " + fraudState);
        }
        if (reportDate == null || reportDate.length() != FRAUD_REPORT_DATE_LENGTH) {
            throw new IllegalArgumentException("fraud report date must be exactly "
                    + FRAUD_REPORT_DATE_LENGTH + " characters but was: " + reportDate);
        }
        this.authFraud = fraudState;
        this.fraudReportDate = reportDate;
    }
}
