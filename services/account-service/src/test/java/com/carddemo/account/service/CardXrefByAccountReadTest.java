package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Pins the two account-keyed cross-reference reads that stand in for the {@code CXACAIX} alternate index.
 *
 * <p>Purpose. The reference reaches the cross-reference by account through a declared alternate index:
 * {@code app/csd/CARDDEMO.CSD} L63 defines {@code CXACAIX} and its own description at L64 reads
 * {@code ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY}, and {@code app/cbl/COACTVWC.cbl} paragraph
 * {@code 9200-GETCARDXREF-BYACCT.} at L723 reads through it at L727 to L732. The base cluster cannot serve
 * that access path, because {@code app/cbl/CBACT03C.cbl} L32 declares
 * {@code RECORD KEY IS FD-XREF-CARD-NUM}. Two migrated reads replace it, and this class asserts both: the
 * single deterministic read that mirrors the reference verb, and the paged walk that exists because the
 * target index is not unique.
 *
 * <p>Assumptions: the seal is a REAL {@code CursorToken} rather than a substitute, because the envelope
 * rejects a boundary token it cannot recognise as sealed and a stub returning arbitrary text would fail
 * that check rather than exercise it. Sealing for real also lets a case open a token it was handed and so
 * assert WHICH key was sealed, which is the property a substitute cannot show.
 *
 * <p>Assumptions: the repository is substituted while the seal is real, and the split is deliberate. Every
 * case here is about the arithmetic between the store and the envelope -- how wide the read is asked to be,
 * which row is discarded, which way the rows are turned -- so the rows are supplied directly and no
 * database is involved.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.
 */
class CardXrefByAccountReadTest {

    /** The account every case reads, inside the eleven-digit range the record's key declares. */
    private static final long ACCOUNT_ID = 12345678901L;

    /** Key material for the real seal; test-only and deliberately not a credential. */
    private static final byte[] CURSOR_KEY =
            "carddemo-account-xref-by-account-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** How long a sealed cursor stays redeemable; generous, because no case here asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /**
     * The rows one page carries.
     *
     * <p>Assumptions: seven is the reference window, declared as
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP} at L177 of {@code app/cbl/COCRDLIC.cbl} with
     * {@code VALUE 7.} at L178. It is restated here so a case can assert the read is asked for one row
     * beyond it without importing a package-private constant.</p>
     */
    private static final int PAGE_SIZE = 7;

    /** The query name the service seals a cross-reference cursor against. */
    private static final String CURSOR_QUERY = "account.card-xrefs.by-account";

    /** The subject every case seals for, standing in for a validated principal's name. */
    private static final String SUBJECT = "operator-under-test";

    /** The cross-reference rows this test supplies, substituted for the store. */
    private CardXrefRepository crossReferences;

    /** The projection onto the published row shape, substituted so a case controls the items. */
    private CardXrefMapper crossReferenceMapper;

    /** The projection onto the machine-read view the single-row read answers with. */
    private AccountContextMapper contextMapper;

    /** The real seal the envelope's boundary tokens are produced by. */
    private CursorToken sealer;

    /** The read path under test. */
    private AccountViewService reads;

    /**
     * Builds the read path over substituted stores and a real seal.
     *
     * <p>Assumptions: the projection is wired to echo its input as a row per element, so a case can read
     * the page's item order directly off the rows it supplied. Asserting order through a real projection
     * would additionally assert the masking that projection applies, which a different class already
     * pins.</p>
     */
    @BeforeEach
    void setUp() {
        this.crossReferences = mock(CardXrefRepository.class);
        this.crossReferenceMapper = mock(CardXrefMapper.class);
        this.contextMapper = mock(AccountContextMapper.class);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);

        when(this.crossReferenceMapper.toCardXrefResponses(any()))
                .thenAnswer(call -> {
                    List<CardXref> rows = call.getArgument(0);
                    List<CardXrefResponse> projected = new ArrayList<>(rows.size());
                    for (CardXref row : rows) {
                        projected.add(new CardXrefResponse(row.getCardNum(), "000000001",
                                String.valueOf(ACCOUNT_ID)));
                    }
                    return projected;
                });

        this.reads = new AccountViewService(mock(AccountRepository.class),
                mock(CustomerRepository.class), this.crossReferences, this.contextMapper,
                mock(AccountMapper.class), mock(CustomerMapper.class), this.crossReferenceMapper,
                this.sealer);
    }

    /**
     * Verifies the opening forward page reads one row beyond the window and publishes neither that row nor
     * its key.
     *
     * <p>Assumptions: the surplus row is the mechanism the reference uses to answer whether a further page
     * exists, setting its indicator at L242 through L244 of {@code app/cbl/COCRDLIC.cbl} after discovering
     * a record beyond the window. Publishing that row's key as the trailing boundary would advance a
     * caller's cursor past a row it never received, so the case asserts the boundary is the seventh row and
     * not the eighth.</p>
     */
    @Test
    @DisplayName("the opening page reads one row beyond the window and reports a further page")
    void theOpeningPageProbesOneRowBeyondTheWindow() {
        when(this.crossReferences.findForwardFromCursor(isNull(), eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(rows(1, PAGE_SIZE + 1));

        PageResponse<CardXrefResponse> page =
                this.reads.listCardCrossReferences(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.hasNext()).isTrue();
        verify(this.crossReferences).findForwardFromCursor(isNull(), eq(ACCOUNT_ID),
                eq(Limit.of(PAGE_SIZE + 1)));
        verify(this.crossReferences, never()).findBackwardFromCursor(any(), any(), any());

        assertThat(this.sealer.open(binding(false), page.lastKey()))
                .as("the trailing boundary must name the last row RETURNED, never the surplus row")
                .isEqualTo(cardNumber(PAGE_SIZE));
        assertThat(this.sealer.open(binding(true), page.firstKey()))
                .isEqualTo(cardNumber(1));
    }

    /**
     * Verifies an exhausted walk yields the shared empty envelope rather than a page naming boundaries.
     *
     * <p>Assumptions: an account legitimately holds no card, so this is an empty result and not an absence
     * of the account. A browse over an alternate-index key with no records ends the same way rather than
     * failing, which is why this case expects no exception.</p>
     */
    @Test
    @DisplayName("an account with no cross-referenced card yields an empty page")
    void anAccountWithNoRowsYieldsAnEmptyPage() {
        when(this.crossReferences.findForwardFromCursor(isNull(), eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of());

        PageResponse<CardXrefResponse> page =
                this.reads.listCardCrossReferences(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
    }

    /**
     * Verifies a backward step discards the surplus row from the END of the descending read and only then
     * turns the rows into presentation order.
     *
     * <p>Assumptions: this is the one ordering in the walk that a plausible implementation gets wrong. The
     * backward statement returns descending rows, so its surplus row is the smallest key and therefore the
     * LAST element, while the row adjacent to the cursor is the first. Reversing before discarding would
     * drop that adjacent row and leave a gap at the boundary which no caller could detect, so the case
     * asserts the page both begins and ends on the rows nearest the cursor.</p>
     */
    @Test
    @DisplayName("a backward step drops the surplus row from the end and then reverses")
    void aBackwardStepDropsTheSurplusRowBeforeReversing() {
        String cursor = this.sealer.seal(binding(true), cardNumber(20));
        when(this.crossReferences.findBackwardFromCursor(eq(cardNumber(20)), eq(ACCOUNT_ID),
                any(Limit.class)))
                .thenReturn(descending(19, PAGE_SIZE + 1));

        PageResponse<CardXrefResponse> page =
                this.reads.listCardCrossReferences(ACCOUNT_ID, cursor, "previous", SUBJECT);

        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.items().get(0).cardNumberMasked())
                .as("the page must begin on the row furthest from the cursor, after reversal")
                .isEqualTo(cardNumber(13));
        assertThat(page.items().get(PAGE_SIZE - 1).cardNumberMasked())
                .as("the row ADJACENT to the cursor must survive, which is what dropping from the end"
                        + " preserves")
                .isEqualTo(cardNumber(19));

        assertThat(page.hasNext())
                .as("a backward page always reports a further page forward, because the set stepped back"
                        + " from is ahead of this one")
                .isTrue();
        verify(this.crossReferences).findBackwardFromCursor(eq(cardNumber(20)), eq(ACCOUNT_ID),
                eq(Limit.of(PAGE_SIZE + 1)));
    }

    /**
     * Verifies a backward step with no cursor reads the opening page forward instead of failing.
     *
     * <p>Assumptions: a backward walk is only expressible from a set already returned, and the repository's
     * backward statement declares its cursor mandatory, so there is no position to seek from. Reading the
     * opening page is the answer rather than a refusal, which is also the answer the reference reaches when
     * the backward key it holds is still its low-value sentinel at L243 of
     * {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    @Test
    @DisplayName("a backward step with no cursor reads the opening page forward")
    void aBackwardStepWithoutACursorReadsForward() {
        when(this.crossReferences.findForwardFromCursor(isNull(), eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(rows(1, 3));

        PageResponse<CardXrefResponse> page =
                this.reads.listCardCrossReferences(ACCOUNT_ID, "  ", "previous", SUBJECT);

        assertThat(page.items()).hasSize(3);
        assertThat(page.hasNext())
                .as("three rows is short of the window, so nothing follows")
                .isFalse();
        verify(this.crossReferences, never()).findBackwardFromCursor(any(), any(), any());
    }

    /**
     * Verifies a cursor sealed for one direction cannot be presented as the other.
     *
     * <p>Assumptions: the direction is part of the seal rather than a hint taken from the request, so
     * replaying a trailing boundary as a leading one is refused instead of silently stepping over rows.
     * The reference keeps its two boundaries in separate fields for the same reason, at L230 through L232
     * and L233 through L235 of {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    @Test
    @DisplayName("a cursor sealed forward is refused when presented as a backward step")
    void aCursorSealedForOneDirectionIsRefusedAsTheOther() {
        String forwardCursor = this.sealer.seal(binding(false), cardNumber(9));

        assertThatThrownBy(() ->
                this.reads.listCardCrossReferences(ACCOUNT_ID, forwardCursor, "previous", SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Verifies a cursor issued while walking one account cannot reposition a walk of another.
     *
     * <p>Assumptions: this is the account-scope half of the seal, and without it the cursor was portable
     * between accounts. The value a cursor names is a CARD NUMBER, the predicate is keyed on it, and the
     * same card number under a different account is a valid position -- so the misposition was silent: a
     * page could begin part way through the other account's rows, or come back empty, with nothing in the
     * response saying anything had gone wrong. No row of the wrong account was ever returned, because the
     * account in the path is the row filter; what was wrong was WHERE the page started.</p>
     */
    @Test
    @DisplayName("a cursor issued for one account is refused while walking another")
    void aCursorIssuedForOneAccountIsRefusedForAnother() {
        String cursor = this.sealer.seal(binding(false), cardNumber(9));

        assertThatThrownBy(() ->
                this.reads.listCardCrossReferences(ACCOUNT_ID + 1, cursor, "next", SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Verifies no part of a card number survives in a cursor this walk issues.
     *
     * <p>Assumptions: the cursor's VALUE is a primary account number, so what the token does with it is a
     * confidentiality question and not merely an integrity one. The token was previously signed but only
     * base64url-encoded, which is a reversible transport encoding rather than a cipher, so the whole card
     * number was recoverable by anyone holding a page of this listing -- a browser history entry, a proxy
     * log or a bookmarked URL. The shared sealer now enciphers under authenticated encryption, and this case
     * asserts the property from the OUTSIDE: it decodes every segment of the token as the transport encoding
     * it is and requires that neither the card number, nor its last four digits, nor its leading digits
     * appear anywhere in the resulting bytes.</p>
     */
    @Test
    @DisplayName("a decoded cursor reveals no part of the card number it positions on")
    void aDecodedCursorRevealsNoPartOfTheCardNumber() {
        when(this.crossReferences.findForwardFromCursor(isNull(), eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(rows(1, PAGE_SIZE));

        PageResponse<CardXrefResponse> page =
                this.reads.listCardCrossReferences(ACCOUNT_ID, null, null, SUBJECT);

        String positioned = cardNumber(PAGE_SIZE);
        for (String token : List.of(page.firstKey(), page.lastKey())) {
            assertThat(token).isNotNull();
            StringBuilder decoded = new StringBuilder(token);
            for (String segment : token.split("\\.")) {
                decoded.append(' ').append(new String(
                        Base64.getUrlDecoder().decode(segment.getBytes(StandardCharsets.US_ASCII)),
                        StandardCharsets.ISO_8859_1));
            }
            String material = decoded.toString();

            assertThat(material)
                    .as("the whole card number must not be recoverable from the token")
                    .doesNotContain(positioned)
                    .as("nor its last four digits, which are the part a mask would have left")
                    .doesNotContain(positioned.substring(positioned.length() - 4))
                    .as("nor its leading digits, which identify the issuer")
                    .doesNotContain(positioned.substring(0, 6));
        }
    }

    /**
     * Verifies the single account-keyed read takes the lowest-ordering row and reads only that row.
     *
     * <p>Assumptions: the reference issues one {@code EXEC CICS READ} at L727 of
     * {@code app/cbl/COACTVWC.cbl} rather than a browse, so one row answers. The tie-break is asserted
     * because the target index is not unique: ascending card number is the base cluster's own order per
     * {@code ACCESS MODE IS SEQUENTIAL} at L31 of {@code app/cbl/CBACT03C.cbl}, and without it two
     * identical requests could differ.</p>
     */
    @Test
    @DisplayName("the single account-keyed read takes the lowest-ordering row")
    void theSingleReadTakesTheLowestOrderingRow() {
        CardXref row = new CardXref(cardNumber(1), 1L, ACCOUNT_ID);
        CardXrefView view = new CardXrefView(ACCOUNT_ID, 1L);
        when(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(row));
        when(this.contextMapper.toCardXrefView(row)).thenReturn(view);

        assertThat(this.reads.resolveCardCrossReferenceByAccount(ACCOUNT_ID)).isSameAs(view);

        // Refactoring Rationale: this used to assert that the UNBOUNDED by-account query was not called,
        //   and that query no longer exists on the repository at all -- it was withdrawn because a public
        //   route materialised every cross-reference row an account holds, and every such row carries a
        //   primary account number. The guarantee is now structural rather than asserted: there is no
        //   unbounded read for this service to reach for. What is still worth asserting is that resolving
        //   one row costs exactly one bounded call, which is what the count below pins.
        verify(this.crossReferences, times(1)).findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID);
    }

    /**
     * Verifies the single account-keyed read raises rather than answering an empty document.
     *
     * <p>Assumptions: the raised type is the one the shared advice renders as 404, which the consuming
     * context reads as a decision input. The reference declares the same outcome at L129 and L130 of
     * {@code app/cbl/COACTVWC.cbl} as {@code DID-NOT-FIND-ACCT-IN-CARDXREF}.</p>
     */
    @Test
    @DisplayName("the single account-keyed read raises when the account has no card")
    void theSingleReadRaisesWhenTheAccountHasNoCard() {
        when(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.reads.resolveCardCrossReferenceByAccount(ACCOUNT_ID))
                .isInstanceOf(NoSuchElementException.class)
                // WHY : Assumptions: the message is read as well as the type, because this message is not
                //       consumed only by the caller -- the shared advice writes it to the operational
                //       record and returns it in a response body, so it lands in a durable place. The
                //       sensitive-data contract in docs/architecture/observability.md names account and
                //       customer identifiers alongside the primary account number and requires a
                //       prohibited value to be OMITTED rather than abbreviated, so this asserts ABSENCE
                //       rather than a masked rendering. It used to name the account.
                .hasMessageNotContaining(String.valueOf(ACCOUNT_ID))
                .hasMessage("the account has no cross-referenced card");
    }

    /**
     * Verifies the card-keyed cross-reference refusal names neither the card nor any part of it.
     *
     * <p>Assumptions: the card number is asserted absent in whole rather than checked for masking, because
     * a primary account number is the one value the migration's logging contract withholds from a durable
     * diagnostic outright. The caller supplied the card, so the message has nothing to add by repeating it
     * into the operational record and the response body.</p>
     *
     * <p>Assumptions: this exercises the third read on this service rather than one of the two the class
     * charter names, and it is here because it is the SAME refusal decision as the case above, taken by
     * the sibling method over the same substituted store. Asserting the pair together is what shows the
     * two agree; separating them by class would leave a reader to discover that they must.</p>
     */
    @Test
    @DisplayName("the card-keyed cross-reference refusal carries no card number")
    void theCardKeyedRefusalNamesNoCardNumber() {
        String card = cardNumber(3);
        when(this.crossReferences.findByCardNum(card)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.reads.resolveCardCrossReference(card))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageNotContaining(card)
                .hasMessage("no cross-reference row exists for the requested card");
    }

    /**
     * Composes the binding the service seals a cross-reference cursor against.
     *
     * @param backward {@code true} for the backward scope, {@code false} for the forward scope
     * @return the binding string, never {@code null}
     */
    private static String binding(boolean backward) {
        // Refactoring Rationale: the ACCOUNT is part of the scope, and its absence was a real defect rather
        //   than a gap in this helper. A cursor bound to the caller and the direction alone opened cleanly
        //   while walking a DIFFERENT account: the cursor names a card number, the predicate is keyed on
        //   it, and the same card number under another account is a valid position the caller never saw --
        //   so a page could begin part way through that account's rows, or be empty, with nothing in the
        //   response saying so.
        // Assumptions: the account is rendered zero-padded to its declared eleven digits and the two
        //   predicates are composed with the shared length-prefixing composer, not concatenated, so no pair
        //   of predicate values can compose the scope of another pair.
        return CursorToken.binding(CURSOR_QUERY, SUBJECT,
                CursorToken.scope(backward ? "direction:previous" : "direction:next",
                        "account:" + String.format(Locale.ROOT, "%011d", ACCOUNT_ID)));
    }

    /**
     * Renders the sixteen-character card number of one corpus row.
     *
     * <p>Assumptions: the value is a zero-padded ordinal rather than anything resembling a real primary
     * account number, so the corpus sorts in the order the cases reason about while carrying no value that
     * could be mistaken for cardholder data.</p>
     *
     * @param ordinal the row's position in the corpus, counted from one
     * @return the card number, exactly the width the record's key declares, never {@code null}
     */
    private static String cardNumber(int ordinal) {
        return String.format("%016d", ordinal);
    }

    /**
     * Builds an ascending run of rows.
     *
     * @param firstOrdinal the ordinal the run starts at, counted from one
     * @param count how many rows the run carries
     * @return the rows in ascending card-number order, never {@code null}
     */
    private static List<CardXref> rows(int firstOrdinal, int count) {
        List<CardXref> built = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            built.add(new CardXref(cardNumber(firstOrdinal + offset), 1L, ACCOUNT_ID));
        }
        return built;
    }

    /**
     * Builds a descending run of rows, as the backward statement returns them.
     *
     * @param firstOrdinal the ordinal the run starts at, which is its HIGHEST because the run descends
     * @param count how many rows the run carries
     * @return the rows in descending card-number order, never {@code null}
     */
    private static List<CardXref> descending(int firstOrdinal, int count) {
        List<CardXref> built = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            built.add(new CardXref(cardNumber(firstOrdinal - offset), 1L, ACCOUNT_ID));
        }
        return built;
    }
}
