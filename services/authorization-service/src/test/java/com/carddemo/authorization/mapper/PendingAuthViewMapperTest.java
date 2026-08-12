package com.carddemo.authorization.mapper;


import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryView;
import com.carddemo.authorization.service.AccountContextClient;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts that the adapter between the persistent authorization rows and the HTTP bodies produces exactly
 * what {@code authorization-api.yaml} publishes.
 *
 * <p>Purpose: this class is the build-enforcing half of the decision that the OpenAPI document is the
 * contract of record for this context's HTTP edge. Before the adapter existed there was no code path from
 * either entity to the shapes the contract declares, so the contract could be edited into any shape at all
 * without a build noticing. Every assertion below names a property of the contract rather than a property of
 * the implementation, so a change to either side that breaks their agreement fails here.
 *
 * <p>Assumptions: the five conversions the adapter performs are each asserted separately -- the fixed-width
 * identifier rendering, the fixed-point money wrapping, the card-number masking, the sealed selector and the
 * derived approval character -- because each is an independent decision and a single end-to-end assertion
 * would let one of them regress behind another's failure.
 */
@DisplayName("PendingAuthViewMapper")
final class PendingAuthViewMapperTest {

    /**
     * The customer display fields the account context resolves for these fixtures.
     *
     * <p>Assumptions: a non-null value is the default posture, because the four fields it carries are what
     * the reference screen shows and a null default would make every case a test of the
     * unresolved-customer path instead of a test of what it is named for.</p>
     */
    private static final AccountContextClient.CustomerDisplay CUSTOMER_DISPLAY =
            new AccountContextClient.CustomerDisplay("SMITH JOHN", "1 HIGH STREET", "SPRINGFIELD IL",
                    "5550001111");

    /** The account identifier used throughout, chosen to need padding so the padding is exercised. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer identifier used throughout, likewise chosen to need padding. */
    private static final long CUSTOMER_ID = 11L;

    /** The stored authorization date of the row under test. */
    private static final int AUTH_DATE = 26215;

    /** The stored authorization time of the row under test. */
    private static final int AUTH_TIME = 91644902;

    /** The unmasked primary account number the persistent row carries. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The authenticated operator every selector in this class is issued to.
     *
     * <p>Assumptions: a selector's binding names the subject as well as the resource, so every conversion
     * that seals one has to be told who it is for. A fixed value is used rather than a generated one so a
     * failing case is reproducible from the source alone.</p>
     */
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";

    /** A second authorized operator, who must not be able to redeem the first one's selector. */
    private static final String OTHER_SUBJECT = "99999999-8888-7777-6666-555555555555";

    /** The adapter under test. */
    private PendingAuthViewMapper mapper;

    /** The sealer the adapter was built over, retained so a sealed selector can be opened again. */
    private CursorToken cursor;

    /**
     * Builds the adapter over a sealer with deterministic key material.
     *
     * <p>Assumptions: the key material is a fixed fill rather than a random value, so a failure is
     * reproducible. Nothing here asserts a particular token TEXT -- a sealed token carries an issue instant
     * -- only that the token has the sealed shape the contract's pattern accepts.</p>
     *
     * <p>Assumptions: the sealer is held on the instance as well as handed to the adapter, because the only
     * way to prove a selector was sealed rather than merely rearranged is to open it again under the same
     * binding. Rebuilding a second sealer here would key a different MAC and could not open what the
     * adapter produced.</p>
     */
    @BeforeEach
    void setUp() {
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.cursor = new CursorToken(keyMaterial, Duration.ofMinutes(5));
        this.mapper = new PendingAuthViewMapper(this.cursor);
    }

    /**
     * The response code the reference program moves on an approval.
     *
     * <p>Assumptions: {@code '00'} is what {@code cbl/COPAUA0C.cbl} L693 moves on the approval branch,
     * and it is the discriminator this fixture uses to choose the match status a real insert would have
     * stored. It is named rather than repeated inline so that the fixture's mapping from response code
     * to match status is stated once.</p>
     */
    private static final String APPROVED_RESPONSE_CODE = "00";

    /**
     * Builds one persistent authorization row with the response code supplied.
     *
     * @param authRespCode the response code to store, which may be {@code null}
     * @return a fully-populated detail row
     */
    private static PendingAuthDetail detailWith(String authRespCode) {
        return detailWith(authRespCode, new BigDecimal("250.00"), new BigDecimal("250.00"));
    }

    /**
     * Builds one persistent authorization row with the response code and both amounts supplied.
     *
     * <p>Assumptions: the two amounts are separate parameters because they differ on a decline, and the
     * assertion that the list row carries the approved one is only meaningful when they are not equal.</p>
     *
     * <p>Assumptions: the match status follows the response code the same way the baseline's own two
     * branches at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 902 to 906 do, so a
     * row built here with the approval code is pending and a row built with any other code is
     * declined. Passing one fixed status for both would build a row combination the reference system
     * never writes, and the view assertions would then be made against an impossible row.</p>
     *
     * @param authRespCode the response code to store, which may be {@code null}
     * @param requested the amount the acquirer asked for
     * @param approved the amount actually granted
     * @return a fully-populated detail row
     */
    private static PendingAuthDetail detailWith(
            String authRespCode, BigDecimal requested, BigDecimal approved) {
        // WHY : Assumptions: the entry mode is (short) 5 rather than a two-digit value on purpose. The
        //       segment's PIC 9(02) admits single-digit quantities, and this fixture is what proves the
        //       view mapper restores the leading zero the picture implies rather than emitting the
        //       shortest decimal spelling of the stored number.
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260806", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", authRespCode, "0000", "003000", requested, approved,
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                // WHY : Assumptions: the match status is derived from the response code the caller
                //       supplied, so a row built with the declined code carries the declined status.
                //       Hard-coding the pending value here would let a regression in the mapper's
                //       treatment of a declined row pass, since every fixture would be pending.
                APPROVED_RESPONSE_CODE.equals(authRespCode)
                        ? PendingAuthDetail.MATCH_STATUS_PENDING
                        : PendingAuthDetail.MATCH_STATUS_DECLINED);
    }

    /**
     * Builds one persistent summary row with non-default limits so the money conversion is observable.
     *
     * @return a summary row carrying limits and one approved authorization
     */
    private static PendingAuthSummary summaryRow() {
        PendingAuthSummary summary = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        summary.refreshLimits(new BigDecimal("5000.00"), new BigDecimal("1000.00"));
        summary.recordApproved(new BigDecimal("250.00"));
        return summary;
    }

    /**
     * The two identifiers are published as exactly their declared width of digits, leading zeros intact.
     */
    @Test
    @DisplayName("renders both identifiers at their declared width")
    void identifiersArePaddedToTheDeclaredWidth() {
        PendingAuthSummaryView view = this.mapper.toSummaryView(summaryRow(), CUSTOMER_DISPLAY);

        assertThat(view.accountId())
                .hasSize(PendingAuthSummaryView.ACCOUNT_ID_WIDTH)
                .isEqualTo("00000000011");
        assertThat(view.customerId())
                .hasSize(PendingAuthSummaryView.CUSTOMER_ID_WIDTH)
                .isEqualTo("000000011");
    }

    /**
     * Every amount on the summary block is fixed-point money at scale two, never a floating-point value.
     */
    @Test
    @DisplayName("wraps every summary amount as fixed-point money")
    void summaryAmountsAreFixedPoint() {
        PendingAuthSummaryView view = this.mapper.toSummaryView(summaryRow(), CUSTOMER_DISPLAY);

        assertThat(view.creditLimit()).isEqualTo(Money.of(new BigDecimal("5000.00")));
        assertThat(view.cashLimit()).isEqualTo(Money.of(new BigDecimal("1000.00")));
        assertThat(view.approvedAuthAmt()).isEqualTo(Money.of(new BigDecimal("250.00")));
        assertThat(view.approvedAuthAmt().amount().scale()).isEqualTo(2);
        assertThat(view.approvedAuthCnt()).isEqualTo((short) 1);
        assertThat(view.declinedAuthAmt()).isEqualTo(Money.ZERO);
    }

    /**
     * A list row publishes the card number masked and the selector sealed, never the raw forms.
     *
     * <p>Refactoring Rationale: the selector assertion used to be
     * {@code doesNotContain(String.valueOf(ACCOUNT_ID))}, a two-character digit probe over the WHOLE token.
     * A sealed token ends in 43 base64url characters of a MAC taken over a payload that includes the issue
     * instant, so those characters differ on every run, and {@code "11"} turns up inside them by chance
     * roughly one run in a hundred. That is exactly how it failed here: the collision sat in the signature
     * segment, not in the payload, and had nothing to do with the adapter. The probe also proved nothing
     * about sealing, because it passed for any string of the right shape. This class's own contract, stated
     * in {@link #setUp()}, is that no assertion here depends on the token's text.</p>
     *
     * <p>Assumptions: the deterministic replacement states the property directly. The cleartext selector
     * {@code accountId:authDate:authTime} must not appear in the published token, and opening that token
     * under the binding {@link PendingAuthViewMapper#cursorBinding(String)} composes for the ISSUING SUBJECT
     * must return exactly that selector -- so the row's identity travels only through the sealer, which is
     * the property the weaker probe was reaching for.</p>
     */
    @Test
    @DisplayName("masks the card number and seals the selector on a list row")
    void rowMasksAndSeals() {
        PendingAuthRowView row = this.mapper.toRowView(detailWith("00"), SUBJECT);
        String cursorKey = ACCOUNT_ID + String.valueOf(PendingAuthViewMapper.KEY_PART_SEPARATOR)
                + AUTH_DATE + String.valueOf(PendingAuthViewMapper.KEY_PART_SEPARATOR) + AUTH_TIME;

        assertThat(row.cardNum()).isEqualTo("************1111").doesNotContain("4111");
        assertThat(CursorToken.hasSealedShape(row.key())).isTrue();
        assertThat(row.key()).isNotEqualTo(cursorKey).doesNotContain(cursorKey);
        assertThat(this.cursor.open(PendingAuthViewMapper.cursorBinding(SUBJECT), row.key()))
                .isEqualTo(cursorKey);
    }

    /**
     * A selector issued to one operator does not open for another, and one issued to no operator is not
     * issued at all.
     *
     * <p>Refactoring Rationale: the binding was the bare query name {@code "authorization-row"} until this
     * case existed, so a selector authenticated the ROW and nothing about who asked for it. Any authorized
     * operator could take a selector out of their own list body and replay it -- and the published cursor
     * contract at {@code common-lib}'s {@code PageResponse} states that a token is bound to the subject it
     * was issued for, which made that contract untrue rather than merely weak. Two operators are asserted
     * rather than one, because a single-subject case passes just as readily against the defective binding.</p>
     */
    @Test
    @DisplayName("seals a selector to its issuing operator and to no other")
    void selectorIsBoundToItsIssuingOperator() {
        String issued = this.mapper.toRowView(detailWith("00"), SUBJECT).key();
        String issuedToOther = this.mapper.toRowView(detailWith("00"), OTHER_SUBJECT).key();

        assertThat(issued).isNotEqualTo(issuedToOther);
        assertThatThrownBy(
                () -> this.cursor.open(PendingAuthViewMapper.cursorBinding(OTHER_SUBJECT), issued))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
        assertThatThrownBy(() -> this.mapper.toRowView(detailWith("00"), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(
                () -> this.mapper.toListView(summaryRow(), List.of(), false, null, "", CUSTOMER_DISPLAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    /**
     * The approval character is derived from the response code, with every non-approved code declining.
     *
     * <p>Assumptions: an absent code is asserted alongside a present decline code, because the reference
     * test is an equality with an unconditional {@code ELSE} and a null-handling slip would otherwise show
     * up only as a thrown exception in production.</p>
     */
    @Test
    @DisplayName("derives the approval character from the response code, declining on anything else")
    void approvalStatusIsDerived() {
        assertThat(this.mapper.toRowView(detailWith("00"), SUBJECT).approvalStatus())
                .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_APPROVED);
        assertThat(this.mapper.toRowView(detailWith("05"), SUBJECT).approvalStatus())
                .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_DECLINED);
        assertThat(this.mapper.toRowView(detailWith(null), SUBJECT).approvalStatus())
                .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_DECLINED);
        assertThat(PendingAuthViewMapper.approvalStatusOf("99"))
                .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_DECLINED);
    }

    /**
     * A list row carries the approved amount, which is what the reference list displayed.
     */
    @Test
    @DisplayName("carries the approved amount on a list row, not the requested one")
    void rowCarriesTheApprovedAmount() {
        PendingAuthDetail declined =
                detailWith("05", new BigDecimal("250.00"), BigDecimal.ZERO.setScale(2));

        PendingAuthRowView row = this.mapper.toRowView(declined, SUBJECT);
        assertThat(row.amount()).isEqualTo(Money.ZERO);

        PendingAuthDetailView detail = this.mapper.toDetailView(declined, SUBJECT);
        assertThat(detail.transactionAmt()).isEqualTo(Money.of(new BigDecimal("250.00")));
        assertThat(detail.approvedAmt()).isEqualTo(Money.ZERO);
    }

    /**
     * The detail body publishes the stored value of every column and composes nothing for display.
     */
    @Test
    @DisplayName("publishes stored values on the detail body and composes nothing")
    void detailPublishesStoredValues() {
        PendingAuthDetailView view = this.mapper.toDetailView(detailWith("00"), SUBJECT);

        assertThat(view.cardExpiryDate()).isEqualTo("2712").doesNotContain("/");
        assertThat(view.authOrigDate()).isEqualTo("260806").hasSize(6);
        assertThat(view.authRespCode()).isEqualTo("00");
        assertThat(view.authRespReason()).isEqualTo("0000");
        // WHY : Refactoring Rationale: the expectation is "05" and not "5". An earlier revision of this
        //       assertion codified the shortest decimal spelling of the stored short, which silently
        //       accepted a body that had dropped the leading zero the copybook's PIC 9(02) declares. The
        //       fixture stores 5, so this line is the one that distinguishes a two-digit fixed-width
        //       rendering from Short.toString.
        assertThat(view.posEntryMode()).isEqualTo("05").hasSize(2);
        assertThat(view.transactionAmt()).isEqualTo(Money.of(new BigDecimal("250.00")));
        assertThat(view.approvedAmt()).isEqualTo(Money.of(new BigDecimal("250.00")));
        assertThat(view.authDate()).isEqualTo(AUTH_DATE);
        assertThat(view.authTime()).isEqualTo(AUTH_TIME);
        assertThat(view.accountId()).isEqualTo("00000000011");
        assertThat(view.cardNum()).isEqualTo("************1111");
        assertThat(view.authFraud()).isNull();
        assertThat(view.fraudRptDate()).isNull();
    }

    /**
     * The list body carries the summary, the page envelope and the two boundary tokens taken from the rows.
     */
    @Test
    @DisplayName("assembles the list body with boundaries taken from the rows returned")
    void listViewCarriesTheEnvelope() {
        PendingAuthListView view = this.mapper.toListView(
                summaryRow(), List.of(detailWith("00")), true, null, SUBJECT, CUSTOMER_DISPLAY);

        assertThat(view.summary().accountId()).isEqualTo("00000000011");
        assertThat(view.page().items()).hasSize(1);
        assertThat(view.page().hasNext()).isTrue();
        assertThat(view.page().firstKey()).isEqualTo(view.page().items().get(0).key());
        assertThat(view.page().lastKey()).isEqualTo(view.page().items().get(0).key());
        assertThat(view.screenMessage()).isNull();
    }

    /**
     * An empty page carries no boundary tokens at all, there being no returned row to take one from.
     */
    @Test
    @DisplayName("returns an empty page with no boundary tokens")
    void emptyPageCarriesNoBoundaries() {
        PendingAuthListView view = this.mapper.toListView(
                summaryRow(), List.of(), false, PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE,
                SUBJECT, CUSTOMER_DISPLAY);

        assertThat(view.page().items()).isEmpty();
        assertThat(view.page().firstKey()).isNull();
        assertThat(view.page().lastKey()).isNull();
        assertThat(view.screenMessage()).isEqualTo(PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE);
    }

    /**
     * A boundary sentence that is not one of the three reference strings is refused rather than published.
     *
     * <p>Refactoring Rationale: the refusal is asserted rather than assumed because a response body is not
     * validated against its own contract at runtime, so a sentence that drifted by one character would
     * otherwise reach a client as though it were the baseline's.</p>
     */
    @Test
    @DisplayName("refuses a navigation sentence outside the three reference strings")
    void authoredBoundarySentenceIsRefused() {
        assertThatThrownBy(() -> this.mapper.toListView(
                summaryRow(), List.of(), false, "You are already at the bottom of the page.",
                SUBJECT, CUSTOMER_DISPLAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference navigation sentences");
    }
}
