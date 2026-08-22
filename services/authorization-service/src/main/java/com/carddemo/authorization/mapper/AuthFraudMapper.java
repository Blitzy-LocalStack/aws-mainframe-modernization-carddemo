package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Carries a stored authorization to the relational fraud row and carries the fraud-mark pair.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the fraud half of the bounded context's anti-corruption layer. The package charter
 * at {@code com/carddemo/authorization/mapper/package-info.java} L83 to L89 assigns it two jobs by
 * name: project a {@code PendingAuthDetail} onto the {@code auth_fraud} row, and carry the fraud-mark
 * request and response pair. Everything it touches is a representation concern that the charter
 * confines to this package, so a caller downstream of it never sees a declared width, a zero-filled
 * numeric rendered as characters, a blank standing in for an absent value, or a baseline spelling.
 *
 * <h2>Three values in this file each have TWO sources</h2>
 *
 * <p>Assumptions: this is the single most important property of the fraud path, and it is stated once
 * here because each wrong choice produces a row that is well formed and plausible, so none of the
 * three is detectable by reading the result.
 *
 * <ul>
 *   <li><b>The fraud report date.</b> The relational column is DATABASE-sourced: the reference INSERT
 *       supplies {@code CURRENT DATE} positionally at {@code cbl/COPAUS2C.cbl} L194, matching
 *       {@code FRAUD_RPT_DATE} in its column list at L166, and the duplicate-key UPDATE sets
 *       {@code FRAUD_RPT_DATE = CURRENT DATE} at L225. The IMS segment field is APPLICATION-sourced
 *       instead: L91 to L100 read the clock and format it month-first with a separator, and L101 moves
 *       the result into {@code PA-FRAUD-RPT-DATE}. <b>A mapper that treats the two alike is wrong in
 *       exactly one of the two places</b>, and it is wrong there on every row it writes. The two are
 *       kept apart by their types as well as by their sources: the detail entity takes the eight
 *       characters through {@code PendingAuthDetail.applyFraudMark(String, String)} into a
 *       {@code CHAR(8)} column, while the fraud row takes a calendar date through
 *       {@code AuthFraud.applyState(String, LocalDate)} into a {@code DATE} column.</li>
 *   <li><b>The fraud marker.</b> The relational column is written from the REQUESTED ACTION:
 *       {@code cbl/COPAUS2C.cbl} L137 moves {@code WS-FRD-ACTION}, the command the caller supplied at
 *       L80, straight into {@code AUTH-FRAUD}. The segment's own {@code PA-AUTH-FRAUD} at
 *       {@code cpy/CIPAUDTY.cpy} L50 is written from the segment. Two sources again, and the same
 *       one-character value in both.</li>
 *   <li><b>The card number.</b> The STORED value is all sixteen digits, because
 *       {@code ddl/AUTHFRDS.ddl} L2 makes {@code CARD_NUM} the first component of the table's key. The
 *       RENDERED value shows only the last four. Both are correct at once; conflating them either
 *       breaks the key or publishes the account number.</li>
 * </ul>
 *
 * <h2>The row this class produces: 26 columns, in the baseline's order</h2>
 *
 * <p>Assumptions: {@code ddl/AUTHFRDS.ddl} carries its columns on L2 through L27, which is twenty-six
 * of them, and declares {@code PRIMARY KEY(CARD_NUM,AUTH_TS)} on L28. The count is asserted rather
 * than counted loosely because a pattern match on leading whitespace returns twenty-five: L2 opens
 * with the table's own parenthesis ahead of {@code CARD_NUM} rather than with indentation. The
 * declaration generated from the same table states the figure independently at
 * {@code dcl/AUTHFRDS.dcl} L88, so twenty-six has two sources and is neither twenty-five nor
 * twenty-seven.
 *
 * <p>Assumptions: {@code ACCT_ID} at L26 and {@code CUST_ID} at L27 are the LAST two columns, which is
 * worth stating because a reader who knows the IMS side expects the opposite -- there the account
 * identifier is the root segment's sequence field, declared at {@code ims/DBPAUTP0.dbd} L30. The
 * entity preserves the relational order, and this class does not reorder it.
 *
 * <h2>The key column that is not this class's to compose</h2>
 *
 * <p>Assumptions: {@code AUTH_TS} is declared at {@code ddl/AUTHFRDS.ddl} L3 as {@code TIMESTAMP} with
 * no explicit precision, and the default is six fractional digits, so the target's
 * {@code TIMESTAMP(6)} is FIXED by that declaration rather than chosen by the migration. Nobody should
 * read it as a setting that could be reduced. Its value is composed by
 * {@link PendingAuthDetailMapper#authTimestamp(PendingAuthDetail)} and by nothing here, and the
 * composed form carries three real millisecond digits with the low three microsecond positions always
 * zero, which follows from the twenty-three-character text form the reference application binds.
 *
 * <p>Assumptions: two readings of that column mislead and are named so they are not repeated. The
 * shared kernel's {@code com.carddemo.common.time.TimestampFormatter} emits a DIFFERENT
 * twenty-six-character form with four year digits, colon separators and six significant fractional
 * digits; it is not the producer of this column and the two formats are not the same thing. And
 * {@code dcl/AUTHFRDS.dcl} L57 declares the host variable as {@code PIC X(26)} because the
 * twenty-three-character value sits left-justified with three trailing spaces, which the conversion
 * mask at {@code cbl/COPAUS2C.cbl} L171 to L172 confirms by naming exactly twenty-three positions;
 * that width is not evidence of the formatter's form either.
 *
 * <h2>Why the target index is directional</h2>
 *
 * <p>Assumptions: {@code ddl/XAUTHFRD.ddl} L3 orders its unique index {@code CARD_NUM ASC, AUTH_TS
 * DESC}, and the reason is visible only on the IMS side. {@code ims/DBPAUTP0.dbd} L37 declares
 * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C}, a character unique sequence field over
 * the eight packed key bytes, and such a field sorts ascending only -- which is why the baseline stores
 * the key complemented to read newest-first. The relational side re-expresses in an INDEX what IMS
 * achieved by inverting the KEY, so the two artifacts are one fact recorded twice and a comment on
 * either has to cite both. The target keeps it as a separate index,
 * {@code idx_auth_fraud_card_recent} in {@code db/migration/V1__authorization.sql}, because the
 * primary key alone does not reproduce a descending second column.
 *
 * <h2>The one name this class replaces</h2>
 *
 * <p>Refactoring Rationale: the row this class produces carries the merchant category code under the
 * target spelling {@code merchantCategoryCode}, against a persisted baseline column named
 * {@code MERCHANT_CATAGORY_CODE}. This is the one place in this file where a name is genuinely being
 * replaced rather than merely translated, and the label is used here for that reason and nowhere else
 * in the file. The baseline spells it that way consistently across four artifacts, and all four are
 * recorded so the lineage is never ambiguous: the IMS segment field
 * {@code PA-MERCHANT-CATAGORY-CODE} at {@code cpy/CIPAUDTY.cpy} L36, the message payload's infixed
 * form {@code PA-RQ-MERCHANT-CATAGORY-CODE} at {@code cpy/CCPAURQY.cpy} L28, the table column
 * {@code MERCHANT_CATAGORY_CODE CHAR(4)} at {@code ddl/AUTHFRDS.ddl} L14, and the generated host
 * structure at {@code dcl/AUTHFRDS.dcl} L37. Because it reaches a persisted column name, adopting the
 * target spelling is a deliberate breaking change at the schema boundary rather than an internal
 * tidying, and it is registered as such in
 * {@code docs/architecture/data-model-and-schema-mapping.md} under transformation rule T1, which makes
 * the copybook normative.
 *
 * <p>Assumptions: the WIRE keeps the baseline spelling. Only the internal and persisted names change,
 * and the message payload's field order and delimiter are themselves the contract, so
 * {@code AuthorizationMessageMapper} owns that side and this class does not touch it. The baseline
 * artifacts are reference material and are not modified; this paragraph records the consequence of
 * their structure and is not a verdict on it. The two other renames the migration makes belong to the
 * account and card contexts and are not claimed here.
 *
 * <h2>The three column boundaries this class no longer publishes</h2>
 *
 * <p>Refactoring Rationale: this class used to publish {@code fraudFlagColumn},
 * {@code processingCodeColumn} and {@code merchantNameColumn}, three per-column boundary methods that
 * NO production path called -- {@code FraudMarkingService} reaches only
 * {@link #toFraudRow(PendingAuthDetail, FraudMarkRequest, Long, LocalDate)} and
 * {@link #markResponse(boolean)}, and the twenty-four snapshot columns are copied by
 * {@code AuthFraud.from} without passing through any of the three. Each therefore stood as a SECOND
 * normalisation of a value whose single authority is elsewhere, which is the arrangement the package
 * charter exists to forbid: the fraud marker on a stored record is normalised once, at decode, by
 * {@code PendingAuthDetailMapper.toEntity} -- it applies the mark only for the two admitted characters
 * and refuses anything else through its own domain guard -- the character form of the processing code
 * is produced once by that class's {@code renderProcessingCode}, and the merchant name is carried
 * untrimmed at both boundaries with the reason recorded at each. Keeping a parallel copy of all three
 * invited a future caller to normalise an already-normalised value, or to reach for
 * {@code fraudFlagColumn} on the way to {@code auth_fraud} -- a column this context writes from the
 * REQUESTED ACTION and never from the stored marker, so that call would have produced a well-formed
 * row recording the wrong thing.
 *
 * <p>Alternatives Considered: keeping the three and routing the production projection through them.
 * Rejected on two grounds. It is not expressible for the processing code, whose boundary took the
 * DECODED number while the detail entity already holds the rendered characters, so the value reaching
 * this class has passed the regime change already; and for the other two it would mean composing the
 * row from twenty-six individual values rather than from the authorization being marked, which is the
 * shape argued against at the projection itself because it lets a caller supply a value that disagrees
 * with the authorization it claims to describe. What the deletion gives up is a width refusal on the
 * merchant name; that guard was unreachable in the shipped paths, since the decode reads a fixed
 * twenty-two-byte span and both the entity's {@code length = 22} and the column's {@code VARCHAR(22)}
 * refuse a wider value at the write.
 *
 * <h2>Two libraries deliberately not adopted</h2>
 *
 * <p>Alternatives Considered: MapStruct, or any generated mapping framework, for this class. Rejected:
 * its most recent published release is a beta, and independently of that the mapping here is not
 * mechanical. In this one file it selects between two provenances for a single date, changes a value
 * from a numeric regime to a character one with zero filling, preserves trailing spaces that a
 * generated mapper would trim, applies a target spelling over a persisted baseline column name, masks
 * an account number, and turns eight spaces into SQL {@code NULL}. Each of those needs its
 * justification recorded at the site that makes the choice, and generated code has nowhere to hold
 * one, so adopting the framework would delete the place the documentation goes rather than save any of
 * the work.
 *
 * <p>Alternatives Considered: Lombok for the accessors and the carrier type below. Rejected: generated
 * members cannot carry the Javadoc that user-specified Rule 1 (Explainability) L15 requires, so a
 * class built that way either fails {@code MissingJavadocMethod} or needs an exemption in
 * {@code config/checkstyle/suppressions.xml}, whose charter admits none for this package. A Java 21
 * {@code record} with a static factory gives the same brevity with members that can be documented.
 *
 * <h2>Trade-offs and boundaries this class accepts</h2>
 *
 * <p>Trade-offs: this class calls package-private helpers on {@link PendingAuthDetailMapper} rather
 * than holding its own copies -- the timestamp composition, the month-first date parse, the
 * account-number mask, the money integer-digit bound and the two complement bases the key guard
 * checks against. Package-private coupling between two
 * classes is accepted deliberately in exchange for exactly one definition of each representation
 * concern in the package. The alternative, a private copy in each class, is what the charter's whole
 * purpose forecloses: a width or a pivot expressed twice can disagree, and the disagreement surfaces
 * as two views of one authorization rather than as any failure. All five files share one package, so
 * package-private visibility is sufficient and nothing is published outside it to achieve this.
 *
 * <p>Assumptions: the transaction boundary is NOT here. {@code cbl/COPAUS1C.cbl} L557 to L559 takes a
 * syncpoint and L565 to L568 rolls one back; transformation rule T5 maps those to a transactional
 * boundary and to exception propagation respectively, both of which belong to the service layer. So
 * does the duplicate-key decision: the reference program tests its status at
 * {@code cbl/COPAUS2C.cbl} L199, branches on minus eight hundred and three to its update paragraph at
 * L203 to L204, and otherwise builds a diagnostic beginning
 * {@code ' SYSTEM ERROR DB2: CODE:'} at L211 to L213. This class produces the row and the state; the
 * choice between inserting and updating is the caller's.
 *
 * <p>Assumptions: the ONE message width this class honours is FIFTY characters, that being
 * {@code WS-FRD-ACT-MSG PIC X(50)} at {@code cbl/COPAUS2C.cbl} L86, and the sentences that fill it
 * are owned by {@code FraudMarkResponse} rather than restated here. Several widths coexist in this
 * bounded context and they must not be normalised into one: the screen responses carry a wider
 * message field of their own, and the house error contract carries a different width again. A
 * structured error body is {@code com.carddemo.common.error.ApiError} and is not the fifty-character
 * component at all. The width is stated in prose rather than as a constant here because that response
 * type already declares it against L86, and a second declaration is a second thing that can drift.
 *
 * <p>Assumptions: divergence D-6 applies to every write this class feeds. The baseline joined IMS
 * DL/I and Db2 under a distributed commit; the target places {@code pending_auth_detail} and
 * {@code auth_fraud} in one PostgreSQL schema, so the migration collapses two stores into one and the
 * write becomes a single local transaction. It is registered as
 * {@code D-6 - the distributed commit is eliminated, not emulated} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Assumptions: no golden master exists for any path in this class and none may be claimed.
 * {@code tests/README.md} L83 to L85 records that the online programs cannot run end to end without a
 * CICS runtime, the message producer is not supplied by the baseline, and {@code cbl/COPAUA0C.cbl}
 * cannot be compiled at all because the vendor message-queue copybooks it names are absent from the
 * repository. Parity here rests on the copybook and data-definition contracts cited throughout and on
 * logic transcribed from the reference programs, and every assertion about this class is an assertion
 * against those.
 */
public final class AuthFraudMapper {

    /**
     * The number of columns the baseline fraud table declares, and the row this class produces.
     *
     * <p>Assumptions: twenty-six is read from {@code ddl/AUTHFRDS.ddl}, whose columns occupy L2
     * through L27 with the key declared on L28, and it is confirmed independently by the generated
     * declaration at {@code dcl/AUTHFRDS.dcl} L88. It is published as a named constant rather than
     * left in prose so the accompanying shape test asserts against one symbol instead of a number
     * retyped in a second place.</p>
     */
    static final int FRAUD_ROW_COLUMN_COUNT = 26;

    /**
     * The declared width of the one variable-length column in the fraud table.
     *
     * <p>Assumptions: twenty-two is read from {@code MERCHANT_NAME VARCHAR(22)} at
     * {@code ddl/AUTHFRDS.ddl} L18 and from the text half of the varying-length host structure at
     * {@code dcl/AUTHFRDS.dcl} L73 to L77, and it is the same width the segment field carries at
     * {@code cpy/CIPAUDTY.cpy} L40. One number with three agreeing sources is a contract rather than
     * a reading.</p>
     *
     * <p>Refactoring Rationale: this constant is now read by the row-shape assertions alone, the
     * boundary method that used to compare against it having been withdrawn for the reason recorded in
     * the class documentation. It is kept rather than inlined because those assertions hold the shipped
     * migration and the entity's declared length to the same figure, and expressing that figure once
     * is what makes the two readings comparable at all; inlining it would put a literal twenty-two in
     * six places, which is precisely the drift the three agreeing sources were cited against.</p>
     */
    static final int MERCHANT_NAME_COLUMN_WIDTH = 22;

    /**
     * The pattern the reference application renders the segment's fraud report date with.
     *
     * <p>Assumptions: month first, solidus separators, two-digit year -- exactly what
     * {@code EXEC CICS FORMATTIME ... MMDDYY(WS-CUR-DATE) DATESEP} at {@code cbl/COPAUS2C.cbl} L95 to
     * L100 produces before its L101 moves the result into the segment. It is declared here rather than in
     * the service that writes it so that this class holds both directions of the format and neither can
     * drift from the other.
     */
    private static final DateTimeFormatter SEGMENT_REPORT_DATE =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * Prevents instantiation of a class that holds only mapping functions.
     *
     * <p>Assumptions: every member is static and none of them reads or writes shared state, so an
     * instance would carry nothing and would only invite one to be injected where a static call
     * expresses the same thing. This mirrors {@link PendingAuthDetailMapper}, whose constructor is
     * private for the same reason, so the package presents one shape rather than two.</p>
     */
    private AuthFraudMapper() {
    }

    /**
     * Projects a stored authorization onto the twenty-six-column fraud row it is being marked into.
     *
     * <p>Assumptions: twenty-four of the twenty-six columns have exactly ONE legitimate source, the
     * authorization being marked, because {@code cbl/COPAUS2C.cbl} L113 to L139 moves them out of the
     * segment it was handed and out of nothing else. The two that do not are the customer identifier
     * and the report date, which is why those are parameters. The account identifier is read from the
     * authorization's own key rather than accepted, so it cannot disagree with the row it describes.</p>
     *
     * <p>Assumptions: the report date is a REQUIRED PARAMETER and this is the hole the reference
     * application's {@code CURRENT DATE} leaves. Both of its write paths take the database's own date --
     * the INSERT supplies it positionally at {@code cbl/COPAUS2C.cbl} L194 and the UPDATE assigns it at
     * L225 -- so the caller must obtain this value from the database and pass it in. The entity refuses
     * a null one, so the hole cannot be left unfilled and forgotten.</p>
     *
     * @param detail the stored authorization being marked, carrying the twenty-four snapshot values;
     *     must not be null and must carry a key with an account identifier
     * @param request the fraud-mark body whose action becomes the row's marker; must not be null and
     *     must carry an action in the closed domain
     * @param custId the customer identifier resolved server-side from the authorization's parent
     *     summary row; must not be null
     * @param databaseCurrentDate the report date as the DATABASE supplies it, never as an application
     *     clock reads it; must not be null
     * @return a fully populated fraud row carrying the marker and the report date, never null
     * @throws NullPointerException if any argument is null, or if the authorization carries no key or
     *     no account identifier in it
     * @throws IllegalArgumentException if the requested action is outside the closed two-value domain,
     *     or if the authorization's originating date cannot form a timestamp
     * @throws IllegalStateException if the authorization carries no key time or no usable originating
     *     date, which is reachable because that value is acquirer-supplied and is stored as characters
     */
    public static AuthFraud toFraudRow(PendingAuthDetail detail, FraudMarkRequest request,
            Long custId, LocalDate databaseCurrentDate) {
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(custId, "custId must not be null");
        Objects.requireNonNull(databaseCurrentDate, "databaseCurrentDate must not be null");
        String action = requireFraudAction(request);

        PendingAuthDetailKey key = Objects.requireNonNull(detail.getId(), "detail must carry a key");
        Long accountId = Objects.requireNonNull(key.getAccountId(),
                "detail key must carry an account identifier");

        // WHY : Assumptions: the second key column is composed by the detail mapper and not here.
        //       That method reads the DECODED time from the entity's key and the calendar parts from
        //       the originating date, which is the pairing the reference application uses at
        //       cbl/COPAUS2C.cbl L103 to L114. Alternatives Considered: composing it locally from the
        //       same two members. Rejected because the composition owns a century pivot and a
        //       separator convention, and a second copy of either would address a DIFFERENT existing
        //       row than the one being marked -- the value is a primary-key component, so a
        //       disagreement is a silent miss rather than a visible error.
        LocalDateTime authTs = PendingAuthDetailMapper.authTimestamp(detail);

        // WHY : Assumptions: the twenty-four snapshot columns are copied by the entity's own factory
        //       and are deliberately NOT transformed on the way through. Two of them look as though
        //       they should be and must not be. The merchant name keeps its trailing spaces: the
        //       reference program writes LENGTH OF PA-MERCHANT-NAME at cbl/COPAUS2C.cbl L130 into the
        //       varying-length prefix, and because that operand is PIC X(22) at cpy/CIPAUDTY.cpy L40
        //       the length is ALWAYS twenty-two, so trimming would store a shorter value than every
        //       row the baseline wrote and silently stop matching them. The processing code keeps its
        //       zero filling: PA-PROCESSING-CODE is PIC 9(06) at cpy/CIPAUDTY.cpy L33 while
        //       PROCESSING_CODE is CHAR(6) at ddl/AUTHFRDS.ddl L11, and a fixed-width character column
        //       compares on its whole width, so a code reaching it as `1` rather than `000001` would
        //       join to nothing rather than report a mismatch.
        //       Alternatives Considered: passing the twenty-six values individually from here.
        //       Rejected because a per-value call lets a caller supply a value that disagrees with the
        //       authorization it claims to describe, which the factory's shape makes unrepresentable.
        return AuthFraud.from(detail, authTs, accountId, custId, action, databaseCurrentDate);
    }

    /**
     * Replaces the marker and the report date on a fraud row that already exists.
     *
     * <p>Assumptions: these are the only two columns the reference update touches.
     * {@code cbl/COPAUS2C.cbl} L224 assigns the marker and L225 assigns the database's current date,
     * and the statement sets nothing else, so the snapshot the row took when it was first inserted is
     * left exactly as it was. A row updated here therefore still describes the authorization as it
     * stood at the first report, which is the property that makes taking a snapshot worthwhile.</p>
     *
     * <p>Assumptions: this is the path the duplicate-key branch reaches, not a second way to write a
     * new row. The reference program attempts its insert, tests the status at L199 and performs its
     * update paragraph at L203 to L204 only on the duplicate-key condition, so a caller reaches this
     * method having already discovered that the key exists.</p>
     *
     * @param row the existing fraud row to restate; must not be null
     * @param request the fraud-mark body whose action becomes the row's marker; must not be null and
     *     must carry an action in the closed domain
     * @param databaseCurrentDate the report date as the DATABASE supplies it, never as an application
     *     clock reads it; must not be null
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the requested action is outside the closed two-value domain
     */
    public static void applyFraudState(AuthFraud row, FraudMarkRequest request,
            LocalDate databaseCurrentDate) {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(databaseCurrentDate, "databaseCurrentDate must not be null");
        row.applyState(requireFraudAction(request), databaseCurrentDate);
    }

    /**
     * Builds the two-part key that addresses an authorization's row in the fraud table.
     *
     * <p>Assumptions: the key is the FULL sixteen-digit account number and the composed timestamp,
     * because {@code ddl/AUTHFRDS.ddl} L2 and L3 declare both columns {@code NOT NULL} and L28 makes
     * the pair the primary key. The stored account number is therefore never masked, which is the
     * asymmetry recorded on this class: {@link #toView(AuthFraud)} publishes the same value reduced to
     * its last four digits, and both are correct because they answer different questions. Masking the
     * key would address no row at all; publishing it unmasked would defeat the narrowing.</p>
     *
     * @param detail the stored authorization to address; must not be null and must carry an account
     *     number, a key time and an originating date
     * @return the key naming this authorization's fraud row, never null
     * @throws NullPointerException if {@code detail} is null, if it carries no key, or if it carries no
     *     account number
     * @throws IllegalArgumentException if the originating date is absent or is not exactly the declared
     *     six characters
     * @throws IllegalStateException if the authorization carries no key time or no usable originating
     *     date, so no timestamp can be composed for it
     */
    public static AuthFraudKey fraudRowKey(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        String storedCardNumber = Objects.requireNonNull(detail.getCardNum(),
                "detail must carry a card number to be addressed in the fraud table");
        return new AuthFraudKey(storedCardNumber, PendingAuthDetailMapper.authTimestamp(detail));
    }

    /**
     * Assembles the composite key of a stored authorization from its three DECODED components.
     *
     * <p>Assumptions: the three components are the account identifier and the two halves of
     * {@code PA-AUTHORIZATION-KEY} at {@code cpy/CIPAUDTY.cpy} L19 to L21, which are
     * {@code PIC S9(05) COMP-3} and {@code PIC S9(09) COMP-3} and so occupy three and five bytes for
     * eight in total. The reference program receives the first two of the three in its communication
     * area at {@code cbl/COPAUS2C.cbl} L75 and L76.</p>
     *
     * <p>Assumptions: the two key halves arriving here are DECODED values and never the stored
     * complement. The baseline stores them inverted so that an ascending read returns the newest
     * authorization first, and the ranges checked below are the decoded ranges the two pictures admit.
     * Accepting a complement as though it were a date would name a real but different row, which is
     * why the check is on the value rather than on its length.</p>
     *
     * <p>Alternatives Considered: taking these three components from {@code FraudMarkRequest}, which
     * is what the reference communication area does. Rejected, and rejected by that type as well: its
     * own contract records that the row is named by the operation's path selector and that a body
     * naming it a second time would be identity supplied by the client. So the components arrive here
     * from the selector the caller already resolved, and this method only refuses ones no stored key
     * could hold.</p>
     *
     * @param accountId the account identifier that owns the authorization; must not be negative
     * @param authDateKey the DECODED authorization date key, never the stored complement; must be
     *     within the five digit positions its picture declares
     * @param authTimeKey the DECODED authorization time key, never the stored complement; must be
     *     within the nine digit positions its picture declares
     * @return the composite key naming one stored authorization, never null
     * @throws IllegalArgumentException if any component is negative or exceeds the digit positions its
     *     picture declares, neither of which a stored key could hold
     */
    public static PendingAuthDetailKey detailKey(long accountId, int authDateKey, int authTimeKey) {
        if (accountId < 0L) {
            throw new IllegalArgumentException("an account identifier must not be negative, but was "
                    + accountId);
        }
        if (authDateKey < 0 || authDateKey > PendingAuthDetailMapper.AUTH_DATE_COMPLEMENT_BASE) {
            throw new IllegalArgumentException("a decoded authorization date key must be between 0 and "
                    + PendingAuthDetailMapper.AUTH_DATE_COMPLEMENT_BASE + ", but was " + authDateKey);
        }
        if (authTimeKey < 0 || authTimeKey > PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE) {
            throw new IllegalArgumentException("a decoded authorization time key must be between 0 and "
                    + PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE + ", but was " + authTimeKey);
        }
        return new PendingAuthDetailKey(accountId, authDateKey, authTimeKey);
    }

    /**
     * Reads the segment's own fraud report date, treating an all-blank field as no date at all.
     *
     * <p>Assumptions: this is the SEGMENT side of the split provenance and the only side a parse
     * applies to. {@code cbl/COPAUS2C.cbl} L91 to L100 read the clock and format it month-first with a
     * separator, and L101 moves the eight characters into {@code PA-FRAUD-RPT-DATE}, declared
     * {@code PIC X(08)} at {@code cpy/CIPAUDTY.cpy} L53. The relational column of the same name takes
     * the database's current date instead and is never parsed from characters, so calling this method
     * on the way to {@code auth_fraud.fraud_rpt_date} would put an application clock's value in a
     * column the reference application sources from the server.</p>
     *
     * <p>Assumptions: the field arrives as EIGHT SPACES rather than absent.
     * {@code cbl/COPAUA0C.cbl} L908 to L909 move {@code SPACE} into the fraud marker and into this
     * field on every insert the message path performs, and those two lines are the only writes to it on
     * that path. Eight spaces name no date, so mapping them to SQL {@code NULL} is a requirement and
     * not a convenience; the relational column is declared nullable for exactly this reason.
     * Alternatives Considered: carrying the blanks through as an empty or space-filled string.
     * Rejected because a date column cannot hold either, so the write would fail at the database with a
     * conversion error naming neither this field nor the authorization it came from.</p>
     *
     * <p>Assumptions: the two stored year digits are widened on a pivot of seventy, so a value of
     * seventy or above reads as nineteen-hundreds and anything below it as two-thousands. The pivot is
     * supplied by the migration because the reference application stores two digits and never widens
     * them, and it is taken from the shared helper rather than restated so that this class and the
     * detail mapper cannot resolve one stored authorization to two different centuries.</p>
     *
     * <p>Assumptions: the stamp is UNCONDITIONAL on the reference path. L101 executes before any fork
     * in that program, so the segment's date is written on the remove path exactly as it is on the
     * report path, and a caller must not make it conditional on the action.</p>
     *
     * @param storedDate the eight stored characters, month first and separated, or null or blank when
     *     the authorization has never been examined
     * @return the calendar date those characters name, or null when {@code storedDate} is null or holds
     *     nothing but spaces
     * @throws IllegalArgumentException if {@code storedDate} is neither blank nor exactly the declared
     *     eight characters, if its separators are not where the reference format puts them, if its
     *     parts are not digits, or if those digits name no calendar date
     */
    public static LocalDate segmentFraudReportDate(String storedDate) {
        return PendingAuthDetailMapper.parseFraudReportDate(storedDate);
    }

    /**
     * Renders a report date into the eight characters the detail segment's own column holds.
     *
     * <p>Purpose: this is the ENCODE direction of {@link #segmentFraudReportDate(String)} and the
     * migrated form of two reference statements taken together -- {@code EXEC CICS FORMATTIME ...
     * MMDDYY(WS-CUR-DATE) DATESEP} at {@code cbl/COPAUS2C.cbl} L95 to L100, whose result its L101 moves
     * straight into {@code PA-AUTH-FRAUD-RPT-DATE}. The eight characters are month first with solidus
     * separators and a two-digit year, which is why that column stores characters rather than a date: the
     * form is not ISO-ordered, so a lexical compare on it is not a date compare.
     *
     * <p>Refactoring Rationale: the rendering used to live in {@code FraudMarkingService} as a formatter
     * of its own, which put the two directions of one eight-character format in two classes. A pattern
     * stated twice can diverge, and a divergence here is silent in the worst way: the write would emit a
     * form this class's own parse would then refuse, so a row written by the online path would be
     * unreadable by the load path, and the failure would surface against the reader rather than the
     * writer. Both directions now sit beside each other so a reader checks them in one place.
     *
     * <p>Assumptions: the stamp is UNCONDITIONAL on the reference path -- L101 executes before any fork
     * in that program -- so a caller writes it on the remove path exactly as on the report path and must
     * not make it conditional on the action.
     *
     * @param reportDate the date the fraud state was reported or withdrawn on; must not be {@code null}
     * @return exactly eight characters, month first and solidus-separated, with a two-digit year
     * @throws NullPointerException if {@code reportDate} is {@code null}
     */
    public static String segmentFraudReportDateText(LocalDate reportDate) {
        Objects.requireNonNull(reportDate, "reportDate must not be null");
        return SEGMENT_REPORT_DATE.format(reportDate);
    }

    /**
     * Selects the outcome body for a write, distinguishing a created row from a restated one.
     *
     * <p>Assumptions: the reference application reports these two outcomes with two different
     * sentences, and both are carried verbatim under transformation rule T8 by the response type
     * itself -- {@code ADD SUCCESS} on the insert path at {@code cbl/COPAUS2C.cbl} L201 and
     * {@code UPDT SUCCESS} on the duplicate-key update path at L232. This method only chooses between
     * them, on the same branch the reference program takes when it tests its status at L199 and finds
     * the duplicate-key condition at L203.</p>
     *
     * <p>Assumptions: there is no failure body to select. The response type admits one outcome
     * character, the success value declared at L84, and a failed write is reported as a non-success
     * status carrying {@code com.carddemo.common.error.ApiError} instead -- which is where the
     * reference program's own failure value at L85 went. That value must never be reached through this
     * method, because a body saying the write failed inside a successful response is a contradiction no
     * caller can resolve.</p>
     *
     * @param created true when the write inserted a new fraud row, false when it restated an existing
     *     one
     * @return the success body carrying the sentence the reference application reports for that
     *     outcome, never null
     */
    public static FraudMarkResponse markResponse(boolean created) {
        return created ? FraudMarkResponse.added() : FraudMarkResponse.updated();
    }

    /**
     * Renders a stored fraud row for publication, with the account number reduced to its last four.
     *
     * <p>Assumptions: the narrowing applied here is added by the migration and removes nothing the
     * reference application offered. That application could emit account numbers into trace output and
     * dumps, because {@code csd/CRDDEMO2.csd} enables both at L44, L54 and L64 while declaring the
     * payload non-confidential at L45, L55 and L65; its detail screen displayed all sixteen digits,
     * declared {@code 02 CARDNUMI PIC X(16).} at {@code cpy-bms/COPAU01.cpy} L60; and no
     * resource-level or command-level check stood in front of either, per L46, L56 and L66.</p>
     *
     * <p>Assumptions: the mask is applied in this package and not in the shared codec, so a field
     * cannot escape it by being serialised from somewhere else. This is also why the stored key value
     * and this rendered value differ on purpose: {@link #fraudRowKey(PendingAuthDetail)} keeps all
     * sixteen digits because they are a key component, and only the published form is reduced.</p>
     *
     * <p>Assumptions: both amounts are published as the money type, which reaches the wire as a
     * fixed-point STRING through the shared money Jackson module rather than as a JSON number. The
     * bound applied is the ten integer digits the reference picture declares, and the general money
     * contract is used -- scale two, half up -- so a zero renders as {@code 0.00} and never as
     * {@code 0}. The truncating contract that type also exposes is reserved for the interest formula
     * and has no place here, because this bounded context performs no interest arithmetic.</p>
     *
     * <p>Assumptions: the merchant name is published exactly as stored, trailing spaces included, for
     * the same reason {@link #toFraudRow(PendingAuthDetail, FraudMarkRequest, Long, LocalDate)} carries
     * it into the row untrimmed -- a consumer comparing a published value against the stored one has to
     * be able to match it, and the stored length is twenty-two on every row by construction.</p>
     *
     * @param row the stored fraud row to publish; must not be null and must carry its key
     * @return a rendered carrier holding the masked account number and the row's published values,
     *     never null
     * @throws NullPointerException if {@code row} is null or carries no key
     * @throws ArithmeticException if either stored amount needs more integer digits than the reference
     *     picture declares, which the column behind it could not have held either
     */
    public static FraudRowView toView(AuthFraud row) {
        Objects.requireNonNull(row, "row must not be null");
        AuthFraudKey key = Objects.requireNonNull(row.getId(), "row must carry a key");
        return new FraudRowView(
                PendingAuthDetailMapper.maskedCardNumber(key.getCardNum()),
                key.getAuthTs(),
                row.getAuthFraud(),
                row.getFraudRptDate(),
                row.getTransactionId(),
                publishedAmount(row.getTransactionAmt()),
                publishedAmount(row.getApprovedAmt()),
                row.getMerchantName(),
                row.getAcctId(),
                row.getCustId());
    }

    /**
     * Reduces a stored amount to the money type published values carry.
     *
     * <p>Assumptions: an absent amount becomes an exact zero rather than a null, because the reference
     * display fields are edited numerics that always rendered digits and had no blank state. The bound
     * is the ten integer digits of {@code PIC S9(10)V99} at {@code cpy/CIPAUDTY.cpy} L34 and L35,
     * which is the same magnitude the {@code DECIMAL(12,2)} columns at {@code ddl/AUTHFRDS.ddl} L12
     * and L13 admit, so applying it here cannot reject a value the column accepted.</p>
     *
     * @param storedAmount the amount as the row holds it, or null when the row carries none
     * @return the amount at scale two under the general money contract, or an exact zero when
     *     {@code storedAmount} is null
     * @throws ArithmeticException if the magnitude needs more than the declared ten integer digits
     */
    private static Money publishedAmount(BigDecimal storedAmount) {
        return storedAmount == null
                ? Money.ZERO
                : Money.ofPicture(storedAmount, PendingAuthDetailMapper.MONEY_INTEGER_DIGITS);
    }

    /**
     * Validates the requested action and returns the marker it selects.
     *
     * <p>Assumptions: the domain is closed by the two condition names at {@code cbl/COPAUS2C.cbl} L81
     * and L82 and by nothing else, so a third character is not an unhandled case but a value the
     * reference program cannot represent. It is compared case-sensitively because a COBOL condition
     * name matches the bytes of its field against the literal it was declared with, so a lower-case
     * form would satisfy neither condition and would still reach the column.</p>
     *
     * <p>Assumptions: the marker written to the relational column is the REQUESTED ACTION, not the
     * segment's own marker. {@code cbl/COPAUS2C.cbl} L137 moves {@code WS-FRD-ACTION} into
     * {@code AUTH-FRAUD}, while the segment's {@code PA-AUTH-FRAUD} is written from the segment -- the
     * third of this class's split provenances, and the reason the action is a parameter of the two
     * write methods rather than something read off the authorization.</p>
     *
     * <p>Assumptions: this action and the response's outcome flag MUST NOT share a type, and the
     * reason is that they share a character with opposite meanings. {@code WS-FRD-ACTION} at L80 takes
     * {@code F} for report-fraud at L81, whereas {@code WS-FRD-UPDATE-STATUS} at L83 takes {@code F}
     * for update-failed at L85. Alternatives Considered: one shared constant set or one shared
     * enumeration for both. Rejected because such a type would compile, would pass every test that did
     * not probe it specifically, and would invert the meaning of a fraud response -- reporting a
     * successful mark as a failure or the reverse. The two live on separate declarations and neither
     * borrows the other's constants.</p>
     *
     * @param request the fraud-mark body to read the action from; must not be null
     * @return the validated one-character marker the action selects, never null
     * @throws NullPointerException if {@code request} is null or carries no action
     * @throws IllegalArgumentException if the action is outside the closed two-value domain
     */
    private static String requireFraudAction(FraudMarkRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String action = Objects.requireNonNull(request.action(), "request must carry an action");
        if (!PendingAuthDetail.FRAUD_REPORTED.equals(action)
                && !PendingAuthDetail.FRAUD_REMOVED.equals(action)) {
            throw new IllegalArgumentException("a fraud action must be either "
                    + PendingAuthDetail.FRAUD_REPORTED + " for report or "
                    + PendingAuthDetail.FRAUD_REMOVED + " for remove, but was " + quoted(action));
        }
        return action;
    }

    /**
     * Wraps a rejected value in quotation marks so a failure message shows its exact extent.
     *
     * <p>Assumptions: the values this class rejects are one and eight characters wide and may be
     * blank, so a message interpolating one bare would read as though a word were missing and a
     * trailing space would be invisible in a log. Delimiting it is what makes the difference between a
     * space and an empty value legible to whoever reads the failure.</p>
     *
     * @param value the rejected value to delimit, which may be null
     * @return the value enclosed in quotation marks, or the four characters spelling null
     */
    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /**
     * Published form of one fraud row, carrying a masked account number and fixed-point amounts.
     *
     * <p>Assumptions: this carrier exists so the entity itself is never published. The entity holds the
     * full account number as the first component of its key, and returning it directly would publish
     * that value from whatever layer happened to serialise it, which is precisely what confining the
     * mask to this package prevents. Alternatives Considered: annotating the entity for serialisation
     * instead. Rejected because the mask would then be a property of the annotation set rather than of
     * a mapping call, and a second serialisation path added later would not inherit it.</p>
     *
     * <p>Assumptions: the two identifiers are the LAST two components, which preserves the order of
     * {@code ACCT_ID} and {@code CUST_ID} at {@code ddl/AUTHFRDS.ddl} L26 and L27 rather than the IMS
     * ordering, where the account identifier is the root sequence field at
     * {@code ims/DBPAUTP0.dbd} L30.</p>
     *
     * @param maskedCardNumber the account number reduced to its last four digits, never the stored
     *     sixteen
     * @param authTimestamp the composed authorization timestamp forming the second half of the row's
     *     key, whose low three microsecond positions are always zero by construction
     * @param fraudMarker the one-character marker the row records, or null when it holds none
     * @param fraudReportDate the report date the DATABASE supplied when the row was written, or null
     *     when the row holds none
     * @param transactionId the transaction identifier the authorization was matched against
     * @param transactionAmount the requested amount at scale two, reaching the wire as a fixed-point
     *     string
     * @param approvedAmount the approved amount at scale two, reaching the wire as a fixed-point string
     * @param merchantName the merchant name exactly as stored, trailing spaces included
     * @param accountId the account identifier the authorization belongs to
     * @param customerId the customer identifier resolved from the authorization's parent summary
     */
    public record FraudRowView(
            String maskedCardNumber,
            LocalDateTime authTimestamp,
            String fraudMarker,
            LocalDate fraudReportDate,
            String transactionId,
            Money transactionAmount,
            Money approvedAmount,
            String merchantName,
            Long accountId,
            Long customerId) {

        /**
         * Renders the fraud row for a diagnostic line, omitting the amounts, the merchant text and both
         * identifiers.
         *
         * <p>Assumptions: the two amounts, the merchant name and the two identifiers are OMITTED. Part
         * one of the rendering rule in {@code docs/architecture/observability.md} names monetary
         * amounts, merchant free text, account identifiers and customer identifiers as a class, and it
         * requires omission rather than abbreviation for all of them. The merchant name is the clearest
         * case on this record: it is carried "exactly as stored, trailing spaces included" because a
         * fraud investigator needs the stored bytes, and stored bytes from an acquirer are exactly the
         * attacker-influenced free text the rule withholds.</p>
         *
         * <p>Assumptions: what remains is a masked card number, two dates and a transaction identifier.
         * Part two of the same rule sanctions a primary account number through {@code CardNumberMasker}
         * and only where a rendering has no other way to say which row it describes, and that is the
         * position here: a fraud row is identified by the card and the authorization instant together,
         * so with the card omitted entirely a line could not be matched to the row an investigator is
         * looking at. Part three admits the timestamps, the marker and the transaction identifier
         * directly.</p>
         *
         * <p>Trade-offs: the masked value is passed through {@link CardNumberMasker} again on the way
         * out, even though this component's contract is that it already arrives masked. The cost is one
         * redundant pass over a short string; what it buys is that the single sanctioned abbreviation
         * cannot be bypassed by a caller that constructed this record from a projection other than this
         * mapper's -- the component is a plain {@code String} and its type cannot enforce the
         * contract.</p>
         *
         * @return a single-line rendering naming the masked card number, the authorization instant, the
         *     fraud marker, the report date and the transaction identifier, and no amount, merchant text
         *     or identifier; never {@code null}
         */
        @Override
        public String toString() {
            return "FraudRowView[maskedCardNumber="
                    + CardNumberMasker.maskEmbeddedCardNumbers(this.maskedCardNumber)
                    + ", authTimestamp=" + this.authTimestamp
                    + ", fraudMarker=" + this.fraudMarker
                    + ", fraudReportDate=" + this.fraudReportDate
                    + ", transactionId=" + this.transactionId + "]";
        }
    }
}
