package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/**
 * Verifies the screen projection this service owns: the display table, both edit-mask widths, the
 * empty-state literals and the forward step's direction.
 *
 * <p>Assumptions: every citation is relative to {@code app/app-authorization-ims-db2-mq}, which is
 * reference material this migration reads and never modifies.
 *
 * <p>Assumptions: no golden master exists for any path asserted here, because the online programs of this
 * application cannot be run end to end without a CICS runtime. Each assertion is therefore made against a
 * value clause, a picture clause or a paragraph read directly from the reference source and cited beside
 * it, and no assertion claims agreement with a recorded run.
 */
class PendingAuthDetailProjectionTest {

    /** The authenticated principal every case in this class seals and redeems sealed values under. */
    private static final String SUBJECT = "authorization-operator";

    /** The account the row belongs to. */
    private static final long ACCOUNT_ID = 11L;

    /** The Julian date key. */
    private static final int AUTH_DATE = 26215;

    /** The composed time key, positionally 09:16:44 and 902 milliseconds. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The card number the row carries. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The four reason codes {@code 6000-MAKE-DECISION} can actually emit.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} pre-sets {@code '0000'} at L698 and gates every other
     * assignment behind {@code IF AUTH-RESP-DECLINED} at L699, so an approval always keeps {@code '0000'};
     * the branches reachable from there are {@code '3100'} at L701 to L704, {@code '4100'} at L706 and
     * {@code '9000'} from the {@code WHEN OTHER} at L716.
     */
    private static final List<String> PRODUCIBLE_CODES = List.of("0000", "3100", "4100", "9000");

    /**
     * The six reason codes the display table carries that the current producer cannot emit.
     *
     * <p>Assumptions: {@code '4200'} at L708, {@code '4300'} at L710, {@code '5100'} at L712 and
     * {@code '5200'} at L714 are ladder branches unreachable given the available-credit fork, and
     * {@code '4400'} and {@code '5300'} are assigned nowhere in that paragraph at all. They are asserted
     * PRESENT rather than absent, which is the point of the assertion.
     */
    private static final List<String> NON_PRODUCIBLE_CODES =
            List.of("4200", "4300", "4400", "5100", "5200", "5300");

    /** The repository double. */
    private PendingAuthDetailRepository details;

    /** The real mapper, so the selector a test presents is the one the service can redeem. */
    private PendingAuthViewMapper mapper;

    /** The service under test. */
    private PendingAuthDetailService service;

    /**
     * Builds the double, a real sealer over deterministic key material, and the service.
     */
    @BeforeEach
    void setUp() {
        this.details = mock(PendingAuthDetailRepository.class);
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        this.service = new PendingAuthDetailService(this.details, this.mapper);
    }

    /**
     * Every one of the ten declared descriptions is carried character for character.
     *
     * <p>Assumptions: the two abbreviated spellings the fixed-width screen forced are asserted explicitly
     * -- {@code 'INSUFFICNT FUND'} at {@code cbl/COPAUS1C.cbl} L60 and {@code 'EXCED DAILY LMT'} at L63.
     * Asserting them by their exact bytes is what stops a later reader expanding either to a correctly
     * spelled form, which would change what an operator reads on the screen.
     */
    @Test
    @DisplayName("all ten declared reason descriptions are byte-exact, abbreviations included")
    void allTenDeclaredReasonDescriptionsAreByteExact() {
        assertThat(PendingAuthDetailService.declineDescriptionFor("0000").strip())
                .isEqualTo("APPROVED");
        assertThat(PendingAuthDetailService.declineDescriptionFor("3100").strip())
                .isEqualTo("INVALID CARD");
        assertThat(PendingAuthDetailService.declineDescriptionFor("4100").strip())
                .isEqualTo("INSUFFICNT FUND");
        assertThat(PendingAuthDetailService.declineDescriptionFor("4200").strip())
                .isEqualTo("CARD NOT ACTIVE");
        assertThat(PendingAuthDetailService.declineDescriptionFor("4300").strip())
                .isEqualTo("ACCOUNT CLOSED");
        assertThat(PendingAuthDetailService.declineDescriptionFor("4400").strip())
                .isEqualTo("EXCED DAILY LMT");
        assertThat(PendingAuthDetailService.declineDescriptionFor("5100").strip())
                .isEqualTo("CARD FRAUD");
        assertThat(PendingAuthDetailService.declineDescriptionFor("5200").strip())
                .isEqualTo("MERCHANT FRAUD");
        assertThat(PendingAuthDetailService.declineDescriptionFor("5300").strip())
                .isEqualTo("LOST CARD");
        assertThat(PendingAuthDetailService.declineDescriptionFor("9000").strip())
                .isEqualTo("UNKNOWN");
    }

    /**
     * Each description occupies the sixteen characters its picture clause declares.
     *
     * <p>Assumptions: {@code DECL-DESC PIC X(16)} at {@code cbl/COPAUS1C.cbl} L73 fixes the width, and the
     * narrowing to fifteen happens later at the point of composition. Asserting the stored width here is
     * what keeps that narrowing a property of the composer rather than of the table.
     */
    @Test
    @DisplayName("every description is held at the sixteen characters DECL-DESC declares")
    void everyDescriptionIsHeldAtItsDeclaredWidth() {
        assertThat(PendingAuthDetailService.declineReasonDescriptions().values())
                .allMatch(description ->
                        description.length() == PendingAuthDetailService.DECLINE_DESCRIPTION_WIDTH);
    }

    /**
     * The table holds all ten entries, the six unreachable ones included.
     *
     * <p>Assumptions: this is a completeness assertion whose purpose is to fail if anyone prunes the table
     * to the four codes the current producer emits. The producer is a separate deployable that can change
     * without this one, so a pruned table would silently downgrade a later producer's reason to the
     * no-entry rendering.
     */
    @Test
    @DisplayName("the table keeps all ten entries, the six the producer cannot emit included")
    void theTableKeepsAllTenEntriesIncludingTheUnreachableSix() {
        Map<String, String> table = PendingAuthDetailService.declineReasonDescriptions();

        assertThat(table).hasSize(PendingAuthDetailService.DECLINE_REASON_ENTRY_COUNT);
        assertThat(table.keySet()).containsAll(PRODUCIBLE_CODES);
        assertThat(table.keySet()).containsAll(NON_PRODUCIBLE_CODES);
        assertThat(PRODUCIBLE_CODES).hasSize(4);
        assertThat(NON_PRODUCIBLE_CODES).hasSize(6);
    }

    /**
     * The declared ascending order of the table is preserved.
     *
     * <p>Assumptions: {@code ASCENDING KEY IS DECL-CODE} at {@code cbl/COPAUS1C.cbl} L70 is what the
     * reference binary search requires, and the hash lookup that replaces it does not. The order is
     * asserted rather than relied on, so the ten entries stay auditable line by line against L58 to L67.
     */
    @Test
    @DisplayName("the table iterates in the declared ascending code order")
    void theTableIteratesInTheDeclaredAscendingCodeOrder() {
        List<String> codes = List.copyOf(
                PendingAuthDetailService.declineReasonDescriptions().keySet());
        List<String> ascending = new ArrayList<>(codes);
        Collections.sort(ascending);

        assertThat(codes).isEqualTo(ascending);
    }

    /**
     * A code with no table entry resolves to nothing rather than to a substitute.
     *
     * <p>Assumptions: the {@code AT END} branch at {@code cbl/COPAUS1C.cbl} L320 to L323 writes a
     * {@code '9999'} and {@code 'ERROR'} pair, and that pair belongs to the composer. Returning nothing
     * from the lookup is what lets the composer distinguish a resolved reason from an unresolved one.
     */
    @Test
    @DisplayName("an unknown, blank or absent reason code resolves to nothing")
    void anUnknownBlankOrAbsentReasonCodeResolvesToNothing() {
        assertThat(PendingAuthDetailService.declineDescriptionFor("7777")).isNull();
        assertThat(PendingAuthDetailService.declineDescriptionFor("    ")).isNull();
        assertThat(PendingAuthDetailService.declineDescriptionFor(null)).isNull();
    }

    /**
     * A space-padded code from the fixed-width segment still finds its entry.
     *
     * <p>Assumptions: {@code PA-AUTH-RESP-REASON PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L32 is
     * space-padded, so a value read from the segment can carry trailing blanks. Failing to find its entry
     * would send every authorization down the no-entry branch and produce a plausible screen rather than
     * an error, which is why this case is asserted separately.
     */
    @Test
    @DisplayName("a space-padded reason code from the segment still resolves")
    void aSpacePaddedReasonCodeFromTheSegmentStillResolves() {
        assertThat(PendingAuthDetailService.declineDescriptionFor("4100 ").strip())
                .isEqualTo("INSUFFICNT FUND");
    }

    /**
     * The empty-state literals are emitted verbatim rather than replaced by a modern equivalent.
     *
     * <p>Assumptions: {@code WS-AUTH-DATE PIC X(08) VALUE '00/00/00'} at {@code cbl/COPAUS1C.cbl} L53 and
     * {@code WS-AUTH-TIME PIC X(08) VALUE '00:00:00'} at L54 are what the screen displayed wherever the
     * {@code IF ERR-FLG-OFF} gate at L294 left the fields unwritten. Substituting a null, an empty string
     * or a zero date in calendar order would each publish a value no reference field ever held.
     */
    @Test
    @DisplayName("the empty-state date and time literals are emitted verbatim")
    void theEmptyStateDateAndTimeLiteralsAreEmittedVerbatim() {
        assertThat(PendingAuthDetailService.screenAuthDate(null)).isEqualTo("00/00/00");
        assertThat(PendingAuthDetailService.screenAuthDate("   ")).isEqualTo("00/00/00");
        assertThat(PendingAuthDetailService.screenAuthTime(null)).isEqualTo("00:00:00");
        assertThat(PendingAuthDetailService.screenAuthTime("   ")).isEqualTo("00:00:00");
        assertThat(PendingAuthDetailService.EMPTY_AUTH_DATE).isEqualTo("00/00/00");
        assertThat(PendingAuthDetailService.EMPTY_AUTH_TIME).isEqualTo("00:00:00");
    }

    /**
     * A rendered value passes through untouched, so the empty-state default is a default and not an
     * override.
     *
     * <p>Assumptions: the separators differ between the two fields -- solidi on the date, colons on the
     * time -- so both are asserted rather than one standing in for the other.
     */
    @Test
    @DisplayName("a rendered date or time passes through the empty-state default untouched")
    void aRenderedDateOrTimePassesThroughUntouched() {
        assertThat(PendingAuthDetailService.screenAuthDate("08/03/26")).isEqualTo("08/03/26");
        assertThat(PendingAuthDetailService.screenAuthTime("09:16:44")).isEqualTo("09:16:44");
    }

    /**
     * The screen mask is the twelve-character one and not the fourteen-character wire mask.
     *
     * <p>Assumptions: {@code WS-AUTH-AMT PIC -zzzzzzz9.99} at {@code cbl/COPAUS1C.cbl} L52 is twelve
     * characters, while {@code WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99} at {@code cbl/COPAUA0C.cbl} L66 is
     * fourteen. Both render a well-formed amount, so crossing them is wrong only in which column each
     * digit lands in -- which is exactly why the width is asserted rather than inferred from the output
     * looking reasonable.
     */
    @Test
    @DisplayName("the screen mask is twelve characters wide and not the fourteen-character wire mask")
    void theScreenMaskIsTwelveCharactersAndNotTheWireMask() {
        assertThat(PendingAuthDetailService.SCREEN_AMOUNT_MASK).isEqualTo("-zzzzzzz9.99");
        assertThat(PendingAuthDetailService.SCREEN_AMOUNT_MASK).hasSize(12);
        assertThat(PendingAuthDetailService.SCREEN_AMOUNT_WIDTH).isEqualTo(12);
        assertThat(PendingAuthDetailService.SCREEN_AMOUNT_MASK).isNotEqualTo("-zzzzzzzzz9.99");

        assertThat(PendingAuthDetailService.renderScreenAmount(Money.of(new BigDecimal("250.00"))))
                .isEqualTo("      250.00")
                .hasSize(PendingAuthDetailService.SCREEN_AMOUNT_WIDTH);
    }

    /**
     * Zero suppression stops one position short of the decimal point.
     *
     * <p>Assumptions: the mask's final integer position is a mandatory digit rather than a suppression
     * position, so a zero amount renders a zero and not an empty integer region. Suppressing all eight
     * positions would blank the units digit and produce an amount reading only as a decimal fraction.
     */
    @Test
    @DisplayName("a zero amount keeps its mandatory units digit")
    void aZeroAmountKeepsItsMandatoryUnitsDigit() {
        assertThat(PendingAuthDetailService.renderScreenAmount(Money.ZERO))
                .isEqualTo("        0.00")
                .hasSize(PendingAuthDetailService.SCREEN_AMOUNT_WIDTH);
    }

    /**
     * The single leading sign control emits a minus for a negative amount and a space otherwise.
     *
     * <p>Assumptions: a mask written with a leading plus would emit a plus for a non-negative amount, and
     * {@code cbl/COPAUS1C.cbl} L52 declares a minus. The space is asserted explicitly because it is easy
     * to read an absent sign as an absent position.
     */
    @Test
    @DisplayName("the sign position emits a minus when negative and a space otherwise")
    void theSignPositionEmitsMinusWhenNegativeAndSpaceOtherwise() {
        assertThat(PendingAuthDetailService.renderScreenAmount(Money.of(new BigDecimal("-125.00"))))
                .isEqualTo("-     125.00")
                .hasSize(PendingAuthDetailService.SCREEN_AMOUNT_WIDTH);
        assertThat(PendingAuthDetailService.renderScreenAmount(Money.of(new BigDecimal("125.00"))))
                .startsWith(" ");
    }

    /**
     * An amount too wide for the mask raises instead of silently losing its high-order digits.
     *
     * <p>Assumptions: the mask provides eight integer positions where
     * {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L35 holds ten, so the
     * condition is reachable. Raising is the ruling recorded as D-EDIT-MASK-OVERFLOW in
     * {@code docs/architecture/cobol-to-service-traceability.md}: a truncated amount carries no evidence
     * of the digits it lost, so an operator would read a smaller authorization with no indication.
     */
    @Test
    @DisplayName("an amount wider than the mask raises rather than truncating")
    void anAmountWiderThanTheMaskRaisesRatherThanTruncating() {
        assertThat(PendingAuthDetailService.renderScreenAmount(
                Money.of(new BigDecimal("99999999.99")))).isEqualTo(" 99999999.99");

        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() ->
                PendingAuthDetailService.renderScreenAmount(
                        Money.of(new BigDecimal("100000000.00"))));
    }

    /**
     * No member of the service admits or returns a binary floating-point type.
     *
     * <p>Assumptions: a JSON number is parsed into a binary double by most clients, so exactness is lost
     * at the boundary an operator actually reads. Asserting the absence over the whole declared surface
     * catches a signature added later, which reading the current code cannot.
     */
    @Test
    @DisplayName("no declared member of the service admits or returns a floating-point type")
    void noDeclaredMemberAdmitsOrReturnsAFloatingPointType() {
        for (Method method : PendingAuthDetailService.class.getDeclaredMethods()) {
            assertThat(method.getReturnType()).isNotIn(double.class, Double.class,
                    float.class, Float.class);
            assertThat(List.of(method.getParameterTypes())).doesNotContain(double.class,
                    Double.class, float.class, Float.class);
        }
    }

    /**
     * A selector redeeming to a key with no row is the ordinary not-found outcome.
     *
     * <p>Assumptions: the reference program treats a segment-not-found status at
     * {@code cbl/COPAUS1C.cbl} L471 to L473 as an end-of-data condition rather than as an error, and
     * nothing the caller sends can recover a row the expiry sweep has removed.
     */
    @Test
    @DisplayName("a key naming no row is the ordinary not-found outcome")
    void aKeyNamingNoRowIsTheOrdinaryNotFoundOutcome() {
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() ->
                this.service.read(selectorFor(row()), SUBJECT));
    }

    /**
     * The forward step compares strictly less than the current position under a descending order.
     *
     * <p>Assumptions: {@code cbl/COPAUA0C.cbl} stores the key nines-complemented at L874 and L875, so the
     * ascending chain order the reference get-next walks is descending chronology. This schema stores the
     * decoded values, so the same traversal is the strictly-less-than predicate the repository declares. A
     * greater-than or ascending query would page backward through history without erroring, which is why
     * the ascending method is asserted NEVER to be called.
     */
    @Test
    @DisplayName("the forward step reads strictly older rows and never the ascending predicate")
    void theForwardStepReadsStrictlyOlderRowsAndNeverTheAscendingPredicate() {
        // WHY : ⚠️ Refactoring Rationale: the anchor is stubbed present because the step now RESOLVES it
        //       before advancing, and an unstubbed repository answers an empty optional -- which would make
        //       this case exercise the not-found outcome while claiming to assert the step's predicate.
        //       The stub is the premise of the assertion below, not scaffolding.
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.of(row()));
        when(this.details.findOlderThan(anyLong(), anyInt(), anyInt(), any(Limit.class)))
                .thenReturn(List.of(row()));

        PendingAuthDetailService.NextAuthorization next =
                this.service.readNext(selectorFor(row()), SUBJECT);

        ArgumentCaptor<Integer> date = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> time = ArgumentCaptor.forClass(Integer.class);
        verify(this.details).findOlderThan(any(), date.capture(), time.capture(), any(Limit.class));
        verify(this.details, never()).findNewerThan(any(), any(), any(), any(Limit.class));
        assertThat(date.getValue()).isEqualTo(AUTH_DATE);
        assertThat(time.getValue()).isEqualTo(AUTH_TIME);
        assertThat(next.endOfData()).isFalse();
        assertThat(next.authorization()).isNotNull();
        assertThat(next.message()).isNull();
    }

    /**
     * An exhausted forward step carries the verbatim message rather than raising.
     *
     * <p>Assumptions: {@code 'Already at the last Authorization...'} at {@code cbl/COPAUS1C.cbl} L283 is a
     * user-visible string, trailing ellipsis included, and the reference program leaves the current screen
     * in place rather than treating exhaustion as an error.
     */
    @Test
    @DisplayName("an exhausted forward step carries the verbatim message and does not raise")
    void anExhaustedForwardStepCarriesTheVerbatimMessage() {
        // WHY : ⚠️ Assumptions: exhaustion is asserted with the anchor PRESENT, because that is what makes
        //       it exhaustion. An absent anchor and an empty successor list are two different conditions
        //       with two different outcomes, and the case below asserts the other one.
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.of(row()));
        when(this.details.findOlderThan(anyLong(), anyInt(), anyInt(), any(Limit.class)))
                .thenReturn(List.of());

        PendingAuthDetailService.NextAuthorization next =
                this.service.readNext(selectorFor(row()), SUBJECT);

        assertThat(next.endOfData()).isTrue();
        assertThat(next.authorization()).isNull();
        assertThat(next.message()).isEqualTo("Already at the last Authorization...");
        assertThat(PendingAuthDetailService.LAST_AUTHORIZATION_REACHED)
                .isEqualTo("Already at the last Authorization...");
    }

    /**
     * A forward step from an anchor the sweep has removed is not found, and no step is attempted.
     *
     * <p>⚠️ Refactoring Rationale: this case exists because the step previously ADVANCED from a position
     * that need not exist. It read the successor of a key without ever asking whether the key named a row,
     * so a selector whose authorization the expiry sweep had removed was answered 200 with the next older
     * authorization -- while the same selector on the keyed read and on the screen read answered 404. The
     * caller received a real authorization it had not asked for and nothing said the one it asked for was
     * gone.
     *
     * <p>⚠️ Assumptions: the second assertion is the substance. Asserting only that the call raises would
     * pass against an implementation that resolved the anchor AFTER stepping, which would still touch a
     * chain the caller has no position in; requiring that the successor query is never issued pins the
     * ORDER, which is what makes the absence decisive.
     *
     * <p>⚠️ Assumptions: the reference cannot reach this state either, which is why the outcome is
     * not-found rather than a new condition of this migration's own. {@code READ-NEXT-AUTH-RECORD} at
     * L493 to L519 issues an UNQUALIFIED get-next, which advances from the position a successful
     * get-unique established; a retrieval that found nothing leaves no position to advance from.
     */
    @Test
    @DisplayName("a forward step from an absent anchor is not found and attempts no step")
    void aForwardStepFromAnAbsentAnchorIsNotFoundAndAttemptsNoStep() {
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() ->
                this.service.readNext(selectorFor(row()), SUBJECT));

        verify(this.details, never()).findOlderThan(anyLong(), anyInt(), anyInt(), any(Limit.class));
        verify(this.details, never()).findNewerThan(any(), any(), any(), any(Limit.class));
    }

    /**
     * Seals the selector a test presents, so the value the service redeems is the one the mapper issued.
     *
     * @param detail the authorization whose list row supplies the sealed selector
     * @return the sealed selector bound to {@link #SUBJECT}, never {@code null}
     */
    private String selectorFor(PendingAuthDetail detail) {
        return this.mapper.toRowView(detail, SUBJECT).key();
    }

    /**
     * Builds the authorization row every test in this class reads.
     *
     * @return a fully populated authorization row
     */
    private static PendingAuthDetail row() {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260803", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
