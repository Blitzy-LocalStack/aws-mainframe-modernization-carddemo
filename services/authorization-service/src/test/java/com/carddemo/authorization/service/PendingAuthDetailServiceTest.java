package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.common.web.CursorToken;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the single-row read: one query rather than the reference two, the redeemed key, the masked card
 * number, and the not-found condition.
 *
 * <p>Assumptions: every citation is relative to {@code app/app-authorization-ims-db2-mq}, which is reference
 * material this migration reads and never modifies.</p>
 */
class PendingAuthDetailServiceTest {

    /**
     * The authenticated principal every case in this class seals and redeems sealed values under.
     *
     * <p>Assumptions: one subject throughout. The sealed selector and the paging cursor are bound to the
     * caller they were issued to, so sealing and redeeming have to agree on it or every case would fail on
     * a refused token rather than on the property it asserts.</p>
     */
    private static final String SUBJECT = "authorization-operator";

    /** The account the row belongs to. */
    private static final long ACCOUNT_ID = 11L;

    /** The Julian date key. */
    private static final int AUTH_DATE = 26215;

    /** The composed time key, positionally 09:16:44 and 902 milliseconds. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The card number the row carries, which the view must publish masked. */
    private static final String CARD_NUMBER = "4111111111111111";

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
     * The selector is redeemed into the three-part key and the row it names is read directly.
     *
     * <p>Refactoring Rationale: ONE query is asserted, where the reference read is two -- a parent
     * get-unique qualified on the account at {@code cbl/COPAUS1C.cbl} L439 to L443 and then a child
     * get-next-within-parent at L465 to L469. The hierarchy that forced the parent read is gone: the
     * relational child key names its own account, and the foreign key at migration L624 to L625 guarantees
     * the parent exists whenever the child does, so a second query would re-establish an invariant the
     * schema already holds. Asserting the absence of that query is what keeps it from being added back as
     * a defensive read.</p>
     */
    @Test
    @DisplayName("the redeemed key is read in one query and the card number is published masked")
    void redeemedKeyIsReadInOneQueryAndTheCardNumberIsMasked() {
        when(this.details.findById(any(PendingAuthDetailKey.class)))
                .thenReturn(Optional.of(row()));

        PendingAuthDetailView view = this.service.read(this.mapper.toRowView(row(), SUBJECT).key(), SUBJECT);

        ArgumentCaptor<PendingAuthDetailKey> read =
                ArgumentCaptor.forClass(PendingAuthDetailKey.class);
        verify(this.details).findById(read.capture());
        verifyNoMoreInteractions(this.details);
        assertThat(read.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(read.getValue().getAuthDate()).isEqualTo(AUTH_DATE);
        assertThat(read.getValue().getAuthTime()).isEqualTo(AUTH_TIME);
        assertThat(view.cardNum()).isEqualTo("************1111");
    }

    /**
     * A selector that redeems to a key with no row behind it is not found.
     *
     * <p>Assumptions: not found rather than refused, because the reference program treats a
     * segment-not-found status as an end-of-data condition at {@code cbl/COPAUS1C.cbl} L471 to L473 rather
     * than as an error, and because nothing the caller sends can correct a row the expiry sweep has
     * removed.</p>
     */
    @Test
    @DisplayName("a selector naming no row is not found")
    void selectorNamingNoRowIsNotFound() {
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> this.service.read(this.mapper.toRowView(row(), SUBJECT).key(), SUBJECT));
    }

    /**
     * A value that is not a sealed selector is refused, keyed to the path member rather than to the cursor.
     *
     * <p>Refactoring Rationale: the field key is asserted because the shared sealer's own refusal names its
     * paging vocabulary, which is correct for the list operation and wrong here -- this value arrives in a
     * PATH segment the contract names {@code key}. A client told to correct a {@code cursor} it never sent
     * cannot act on the refusal, so the mapper re-keys it at that one boundary.</p>
     */
    @Test
    @DisplayName("a value that is not a sealed selector is refused and keyed to the path member")
    void valueThatIsNotASealedSelectorIsRefused() {
        assertThatExceptionOfType(PendingAuthViewMapper.InvalidSelectorException.class)
                .isThrownBy(() -> this.service.read("11111111111:26215:91644902", SUBJECT))
                .satisfies(refusal -> assertThat(refusal.fields())
                        .containsExactly(PendingAuthViewMapper.SELECTOR_FIELD));
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
