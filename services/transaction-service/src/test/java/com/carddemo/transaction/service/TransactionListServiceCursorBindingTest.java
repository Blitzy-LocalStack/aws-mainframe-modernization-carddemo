package com.carddemo.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/**
 * Pins the ONE property of the paged browse that a token holder can attack: whose cursor a page's
 * boundary tokens open for.
 *
 * <p>The subject is {@code app/cbl/COTRN00C.cbl}, whose browse keys were carried in a communication
 * area the client handed back on the following turn -- storage the region trusted because nothing
 * else could reach it. Over HTTP the equivalent value is held by the caller, so the migration seals
 * it, and a seal is only worth its cost if the binding it authenticates names the caller. An earlier
 * revision bound the token to the listing alone, which made a page issued to one authorized caller
 * redeemable by every other one; these cases are what stop that revision returning.</p>
 *
 * <p>Assumptions: the repository and the mapper are mocks and the sealer is REAL, holding a key
 * generated inside this class. A mocked sealer would assert only that a collaborator was called and
 * would pass just as happily against the defective binding, so the one collaborator whose real
 * arithmetic the property depends on is the one that is not stubbed.</p>
 *
 * <p>Trade-offs: this class asserts the cursor contract and not the five boundary messages, the
 * eleventh-row probe or the display reversal. Those are properties of the paragraphs this service
 * transcribes and belong beside them; mixing them in here would mean a failure in either could not
 * be read off the class name. The sibling package charter records the full target contract for this
 * file, and the parity cases named there are additive to these.</p>
 */
@DisplayName("TransactionListService: a page's cursors open only for the caller they were issued to")
class TransactionListServiceCursorBindingTest {

    /**
     * The sealing key these cases mint tokens with.
     *
     * <p>Assumptions: it is a fixed literal of more than thirty-two bytes rather than a random array,
     * because {@code CursorToken} refuses anything shorter than its digest width and a fixed key makes
     * a failing case reproducible from the source alone. It is a test key and authenticates nothing
     * outside this class, so committing it exposes nothing; a production key is resolved from a secret
     * store, which is why no module publishes a sealer bean.</p>
     */
    private static final byte[] SEALING_KEY =
            "carddemo-transaction-list-service-test-sealing-key".getBytes(StandardCharsets.UTF_8);

    /**
     * The identifier of the single row every positioned case pages away from.
     *
     * <p>Assumptions: sixteen digits, which is the declared width of {@code TRAN-ID PIC X(16)} at line
     * 5 of {@code app/cpy/CVTRA05Y.cpy}. A shorter value would still seal, so the width is carried for
     * fidelity rather than because the seal requires it.</p>
     */
    private static final String BOUNDARY_KEY = "0000000000000042";

    /** The subject a page is issued to. */
    private static final String ISSUED_TO = "11111111-2222-3333-4444-555555555555";

    /** A second authorized subject, who must not be able to redeem the first one's cursor. */
    private static final String OTHER_SUBJECT = "99999999-8888-7777-6666-555555555555";

    /** The repository mock, standing in for every keyset read. */
    private TransactionRepository transactionRepository;

    /** The mapper mock, standing in for display ordering and envelope assembly. */
    private TransactionMapper transactionMapper;

    /** The real sealer, keyed from {@link #SEALING_KEY}. */
    private CursorToken cursorToken;

    /** The service under test. */
    private TransactionListService service;

    /**
     * Builds the two mocks, the real sealer and the service before each case.
     */
    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        transactionMapper = mock(TransactionMapper.class);
        cursorToken = new CursorToken(SEALING_KEY, Duration.ofMinutes(15));
        service = new TransactionListService(transactionRepository, transactionMapper);
    }

    /**
     * A token sealed for one subject resolves to its raw key for that subject and is refused for
     * another, with no read attempted on the refusal.
     */
    @Test
    @DisplayName("a cursor sealed for one subject is refused for a different authorized subject")
    void aCursorSealedForOneSubjectIsRefusedForAnother() {
        String sealedCursor = firstPageCursorFor(ISSUED_TO);

        // Assumptions: the forward path reads through findByTranIdGreaterThan..., so verifying the
        //   argument it received is what proves the token opened to the raw key it was sealed from
        //   rather than merely that it opened to something.
        when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(eq(BOUNDARY_KEY), any()))
                .thenReturn(List.of());
        when(transactionMapper.orderForDisplay(any(), any())).thenReturn(List.of());
        when(transactionMapper.toListPage(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new PageResponse<>(List.of(), null, null, false, false));

        TransactionListRequest replay = new TransactionListRequest(null, sealedCursor,
                TransactionListRequest.Direction.NEXT);
        service.listTransactions(replay, cursorToken, ISSUED_TO);
        verify(transactionRepository)
                .findByTranIdGreaterThanOrderByTranIdAsc(eq(BOUNDARY_KEY), any(Limit.class));

        CursorToken.InvalidCursorException refused =
                assertThrows(CursorToken.InvalidCursorException.class,
                        () -> service.listTransactions(replay, cursorToken, OTHER_SUBJECT));

        // Refactoring Rationale: the assertion is that the SECOND subject caused no further read, not
        //   merely that a throw happened. A refusal raised after the scan had already run would still
        //   fail the request while having disclosed the rows, so the count of reads is the property
        //   that distinguishes a refusal from a late error.
        verify(transactionRepository, times(1))
                .findByTranIdGreaterThanOrderByTranIdAsc(any(), any(Limit.class));
        assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank(),
                "a refusal must carry a message, because the handler renders it to the caller");
    }

    /**
     * Two subjects paging the identical row receive different tokens, so the binding is what separates
     * them rather than the row.
     */
    @Test
    @DisplayName("the same row seals to different tokens for different subjects")
    void theSameRowSealsToDifferentTokensForDifferentSubjects() {
        String issuedToFirst = firstPageCursorFor(ISSUED_TO);
        String issuedToSecond = firstPageCursorFor(OTHER_SUBJECT);

        // Assumptions: both tokens name the same raw key, so any difference between them comes from
        //   the binding. Equality here would mean the subject reached the authentication code as a
        //   value that does not change the digest, which is the defect this case exists to catch.
        assertNotEquals(issuedToFirst, issuedToSecond,
                "a token that does not change with its subject is transferable between callers");
        assertEquals(BOUNDARY_KEY,
                cursorToken.open(CursorToken.binding(TransactionListService.CURSOR_QUERY_NAME,
                        ISSUED_TO, CursorToken.SCOPE_NONE), issuedToFirst),
                "the token issued to the first subject must still name the row it was sealed from");
    }

    /**
     * A blank subject is refused before any read, including on the path that would touch no token at
     * all.
     */
    @Test
    @DisplayName("a blank subject is refused before any read, even on a page that seals nothing")
    void aBlankSubjectIsRefusedBeforeAnyRead() {
        TransactionListRequest opening = new TransactionListRequest(null, null, null);

        assertThrows(NullPointerException.class,
                () -> service.listTransactions(opening, cursorToken, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.listTransactions(opening, cursorToken, ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.listTransactions(opening, cursorToken, "   "));

        // Refactoring Rationale: the request carries no cursor and the case stubs no rows, so an
        //   implementation that composed the binding lazily -- only where a token is opened or sealed
        //   -- would have accepted a blank subject here while refusing it on every other path.
        //   Asserting that neither collaborator was touched is what pins the refusal to the entry of
        //   the method rather than to whichever branch happens to reach the sealer.
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(transactionMapper);
    }

    /**
     * Produces the trailing cursor of an opening page issued to one subject, over a single row.
     *
     * <p>Assumptions: the mapper is stubbed rather than real, so the tokens are captured from the
     * arguments the service hands it. That is the assembled value itself, which is stronger than
     * reading it back off an envelope the mapper would have built.</p>
     *
     * <p>Trade-offs: the recorded interactions are cleared before returning, so a caller may invoke
     * this helper twice in one case and still verify a later interaction by count. Clearing the
     * interactions rather than resetting the mocks keeps the stubs in place, so a helper call does not
     * silently un-stub a read the case is about to make.</p>
     *
     * @param subject the authenticated principal the page is issued to, of type {@code String}; must
     *     not be {@code null} or blank
     * @return the sealed trailing token of that page, never {@code null}
     */
    private String firstPageCursorFor(String subject) {
        List<Transaction> rows = List.of(rowNamed(BOUNDARY_KEY));
        when(transactionRepository.findAllByOrderByTranIdAsc(any(Limit.class))).thenReturn(rows);
        when(transactionMapper.orderForDisplay(any(), any())).thenReturn(rows);
        when(transactionMapper.toListPage(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new PageResponse<>(List.of(), null, null, false, false));

        service.listTransactions(new TransactionListRequest(null, null, null), cursorToken, subject);

        ArgumentCaptor<String> firstKeyToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> lastKeyToken = ArgumentCaptor.forClass(String.class);
        verify(transactionMapper).toListPage(any(), firstKeyToken.capture(), lastKeyToken.capture(),
                anyBoolean(), anyBoolean());

        // Refactoring Rationale: the two ends are compared as the KEYS THEY OPEN TO and no longer as
        //   the sealed strings themselves. CursorToken.seal stamps each token with the epoch second it
        //   was sealed at, so the page's two seals produce different strings whenever the pair happens
        //   to straddle a wall-clock second -- which made this helper fail intermittently while the
        //   property it asserts, that a one-row page names one row at both ends, was never in doubt.
        //   Opening both tokens states that property directly and cannot be perturbed by the clock.
        String binding = CursorToken.binding(TransactionListService.CURSOR_QUERY_NAME, subject,
                CursorToken.SCOPE_NONE);
        assertEquals(cursorToken.open(binding, firstKeyToken.getValue()),
                cursorToken.open(binding, lastKeyToken.getValue()),
                "a one-row page names the same row at both of its ends");

        clearInvocations(transactionRepository, transactionMapper);
        return lastKeyToken.getValue();
    }

    /**
     * Builds the one row these cases page away from.
     *
     * <p>Assumptions: only the identifier matters, because the mapper that would read the remaining
     * members is a mock. The other constructor arguments are supplied because the entity canonicalises
     * its amount and refuses an absent one, so a row cannot be built without a value there.</p>
     *
     * @param tranId the sixteen-character identifier the row carries, of type {@code String}; must not
     *     be {@code null}
     * @return the row, never {@code null}
     */
    private static Transaction rowNamed(String tranId) {
        return new Transaction(tranId, "01", "0001", "POS", "TEST ROW", new BigDecimal("1.00"),
                123_456_789L, "MERCHANT", "CITY", "00000", "4111111111111111",
                LocalDateTime.parse("2022-07-18T10:00:00"),
                LocalDateTime.parse("2022-07-18T10:00:01"));
    }
}
