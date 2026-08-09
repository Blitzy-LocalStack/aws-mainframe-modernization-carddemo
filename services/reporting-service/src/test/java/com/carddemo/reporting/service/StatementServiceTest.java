package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.repository.StatementAccountRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository;
import com.carddemo.reporting.repository.StatementCustomerRepository;
import com.carddemo.reporting.repository.StatementTransactionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

/**
 * Pins how a statement is SELECTED, how much it reads, and what its artifact locations disclose.
 *
 * <p>Purpose: every case here corresponds to a specific defect that shipped in the earlier shape of
 * this class, and each is written to fail against that shape rather than merely to describe the new
 * one. Four of them are about selection: a statement was chosen by the masked card rendering, which
 * names a tail rather than a card, so a request for a card that did not exist could be answered with a
 * different cardholder's statement. Two are about boundedness: a heading-only read materialised every
 * transaction the card had. One is about disclosure: the artifact object key carried the account
 * identifier and the card's last four digits, and an object key is metadata that appears in a bucket
 * listing.
 *
 * <p>Assumptions: the repositories are mocked and the two keyed collaborators are REAL. The tokeniser
 * is constructed over fixed test key material rather than stubbed, because the property under test is
 * what a token does NOT contain -- a stubbed tokeniser returning a constant would satisfy every
 * absence assertion here while proving nothing about the derivation. The fingerprints are literal
 * sixty-four-character strings rather than computed digests, because this class asserts that the
 * service passes the fingerprint it was given through unchanged; how the digest is produced is a
 * property of {@code data-migration/sql/V1__reporting_views.sql} and is verified against a live engine
 * rather than here.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class StatementServiceTest {

    /** A card number from the published demonstration seed, {@code app/data/ASCII/cardxref.txt}. */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /** The masked rendering the reporting relations publish for {@link #SEED_CARD_NUMBER}. */
    private static final String MASKED_CARD = "************7065";

    /**
     * The keyed fingerprint standing for {@link #SEED_CARD_NUMBER}.
     *
     * <p>Assumptions: sixty-four hexadecimal characters, which is the width the view's
     * {@code encode(sha256(...), 'hex')} produces and the width {@code CardXrefView} maps. A shorter
     * stand-in would let a mapping that truncated the column pass.</p>
     */
    private static final String FINGERPRINT = "a".repeat(63) + "1";

    /** A second card's fingerprint, so a two-card account can be expressed. */
    private static final String OTHER_FINGERPRINT = "b".repeat(63) + "2";

    /** The account the seeded card is issued against, at the declared eleven positions. */
    private static final long ACCOUNT_ID = 21_820_493_291L;

    /** The customer the cross-reference names. */
    private static final long CUSTOMER_ID = 100_000_001L;

    /** The bucket artifacts are published to, standing for the deployment's own. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The key prefix artifacts sit under, matching the base configuration document. */
    private static final String PREFIX = "statements/";

    /**
     * Fixed tokeniser key material, at the tokeniser's minimum length.
     *
     * <p>Assumptions: fixed rather than random so a token asserted here is reproducible from the
     * source alone, and declared here rather than defaulted inside the tokeniser, because a tokeniser
     * that defaulted a key is how a development default becomes the committed secret.</p>
     */
    private static final byte[] ARTIFACT_KEY =
            "carddemo-reporting-artifact-test!".repeat(2).getBytes(StandardCharsets.UTF_8);

    private StatementTransactionRepository transactions;

    private StatementCardXrefRepository cardXrefs;

    private StatementCustomerRepository customers;

    private StatementAccountRepository accounts;

    private StatementService service;

    /**
     * Builds the service over mocked reads and a real tokeniser.
     */
    @BeforeEach
    void setUp() {
        transactions = Mockito.mock(StatementTransactionRepository.class);
        cardXrefs = Mockito.mock(StatementCardXrefRepository.class);
        customers = Mockito.mock(StatementCustomerRepository.class);
        accounts = Mockito.mock(StatementAccountRepository.class);
        service = new StatementService(transactions, cardXrefs, customers, accounts,
                BUCKET, PREFIX, new OpaqueIdentifier(ARTIFACT_KEY));
    }

    // WHY : Assumptions: the two halves of exactly-one-of are asserted separately and each names the
    //       component a caller has to act on. One case covering both would pass against an
    //       implementation that refused every request with the same message, which is a refusal a
    //       caller cannot correct from.
    /**
     * Asserts that a request naming neither selector is refused, naming the card component.
     */
    @Test
    @DisplayName("a request naming neither a card nor an account is refused")
    void aRequestNamingNeitherSelectorIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(new StatementRequest(null, null)))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("cardNumber"));

        verify(cardXrefs, never()).resolveByWholeCardNumber(anyString());
        verify(cardXrefs, never()).findCardsOfAccount(anyLong(), any());
    }

    /**
     * Asserts that a request naming both selectors is refused, naming the account component.
     */
    @Test
    @DisplayName("a request naming both a card and an account is refused")
    void aRequestNamingBothSelectorsIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(SEED_CARD_NUMBER, String.valueOf(ACCOUNT_ID))))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("accountId"));

        verify(cardXrefs, never()).resolveByWholeCardNumber(anyString());
        verify(cardXrefs, never()).findCardsOfAccount(anyLong(), any());
    }

    // WHY : Refactoring Rationale: this is the broken-object-selection case. The earlier shape reduced
    //       the caller's number to its last four digits and looked a statement up by that display
    //       mask, so where the requested card did not exist and a different cardholder's card shared
    //       the tail, the caller received that cardholder's statement with nothing recording the
    //       substitution. The assertion is on the ARGUMENT the repository receives, because that is
    //       the only place the difference between a card and a tail is visible from outside.
    /**
     * Asserts that a card is resolved by its whole number and never by a masked rendering.
     */
    @Test
    @DisplayName("a card is resolved by the whole number, never by its masked tail")
    void aCardIsResolvedByTheWholeNumber() {
        stubOneCard();

        service.describe(new StatementRequest(SEED_CARD_NUMBER, null));

        ArgumentCaptor<String> selector = ArgumentCaptor.forClass(String.class);
        verify(cardXrefs).resolveByWholeCardNumber(selector.capture());
        assertThat(selector.getValue())
                .as("the whole number selects the card")
                .isEqualTo(SEED_CARD_NUMBER);
        assertThat(selector.getValue())
                .as("no masked rendering may reach a selection query")
                .doesNotContain("*");
    }

    /**
     * Asserts that a card the cross-reference does not hold is reported absent rather than substituted.
     */
    @Test
    @DisplayName("a card that does not resolve is reported absent")
    void aCardThatDoesNotResolveIsReportedAbsent() {
        when(cardXrefs.resolveByWholeCardNumber(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.describe(new StatementRequest(SEED_CARD_NUMBER, null)));

        verify(cardXrefs, never()).findById(anyString());
    }

    // WHY : Assumptions: the bound handed to the cross-reference is asserted to be TWO and not one.
    //       One row is all a happy path needs, but a bound of one cannot tell an account holding a
    //       single card from an account holding several -- it would answer from the first either way,
    //       which is the outcome the refusal below exists to prevent.
    /**
     * Asserts that an account selector resolves through a bounded two-row cross-reference read.
     */
    @Test
    @DisplayName("an account selector resolves through a two-row bounded read")
    void anAccountSelectorResolvesThroughABoundedRead() {
        stubOneCard();
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of(xref(FINGERPRINT)));

        service.describe(new StatementRequest(null, String.valueOf(ACCOUNT_ID)));

        ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
        verify(cardXrefs).findCardsOfAccount(eq(ACCOUNT_ID), bound.capture());
        assertThat(bound.getValue().max())
                .as("two rows are read so a multi-card account is detectable")
                .isEqualTo(2);
    }

    /**
     * Asserts that an account holding more than one card is refused rather than answered from one.
     */
    @Test
    @DisplayName("an account holding two cards is refused, naming the account")
    void anAccountHoldingTwoCardsIsRefused() {
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of(xref(FINGERPRINT), xref(OTHER_FINGERPRINT)));

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(null, String.valueOf(ACCOUNT_ID))))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("accountId"));

        verify(transactions, never()).aggregateByCardFingerprint(anyString());
    }

    /**
     * Asserts that an account holding no card is reported absent.
     */
    @Test
    @DisplayName("an account holding no card is reported absent")
    void anAccountHoldingNoCardIsReportedAbsent() {
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(null, String.valueOf(ACCOUNT_ID))));
    }

    // WHY : Refactoring Rationale: this is the boundedness case. The earlier shape opened the card's
    //       whole transaction stream to produce a heading, so a summary carrying one total and one
    //       count cost a read proportional to the card's whole history. The assertion is that no row
    //       read happens at all, which is stronger than asserting the aggregate was used: an
    //       implementation could call both.
    /**
     * Asserts that a heading-only read touches the aggregate alone and reads no transaction row.
     */
    @Test
    @DisplayName("a heading-only read materialises no transaction row")
    void aHeadingOnlyReadMaterialisesNoTransactionRow() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null));

        assertThat(response.transactionCount()).isEqualTo(7);
        assertThat(response.totalAmount()).isEqualTo(Money.of("-1234.56"));
        verify(transactions).aggregateByCardFingerprint(FINGERPRINT);
        verify(transactions, never()).findWindowByCardFingerprint(anyString(), anyString(), anyInt());
        verify(transactions, never()).streamByCardFingerprint(anyString());
    }

    // WHY : Assumptions: the count in the heading is asserted against the AGGREGATE and not against
    //       the number of rows in the body. Those two agree on every statement short of the cap and
    //       disagree on exactly the statements where the difference matters, so asserting the capped
    //       case is what makes truncation measurable from outside.
    /**
     * Asserts that a capped window still reports the card's true transaction count.
     */
    @Test
    @DisplayName("a capped transaction window still reports the true count")
    void aCappedWindowStillReportsTheTrueCount() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1234.56", 4_211L));
        when(transactions.findWindowByCardFingerprint(
                eq(FINGERPRINT), anyString(), eq(StatementService.MAX_RESPONSE_TRANSACTIONS)))
                .thenReturn(List.of());

        StatementDocument document = service.compose(new StatementRequest(SEED_CARD_NUMBER, null));

        assertThat(document.statement().transactionCount())
                .as("the heading reports what the card holds, not what the body carries")
                .isEqualTo(4_211);
        verify(transactions).findWindowByCardFingerprint(
                FINGERPRINT, "", StatementService.MAX_RESPONSE_TRANSACTIONS);
    }

    // WHY : Refactoring Rationale: this is the metadata-disclosure case. The earlier object key was
    //       composed from the account identifier and the card's last four digits, and an object key
    //       appears in a bucket listing, an access log, a lifecycle report and a storage inventory
    //       export -- so listing one prefix enumerated the portfolio without a single object being
    //       read. The assertions name the values that must be ABSENT, because a key can only regress
    //       by gaining one.
    /**
     * Asserts that neither artifact location carries an identifier or any card-number fragment.
     */
    @Test
    @DisplayName("an artifact location carries no account identifier and no card-number fragment")
    void anArtifactLocationCarriesNoIdentifier() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null));

        for (String uri : List.of(response.plainTextUri(), response.htmlUri())) {
            assertThat(uri).startsWith("s3://" + BUCKET + "/" + PREFIX);
            assertThat(uri)
                    .as("no key may carry the account identifier")
                    .doesNotContain(String.valueOf(ACCOUNT_ID));
            assertThat(uri)
                    .as("no key may carry any part of the card number")
                    .doesNotContain(SEED_CARD_NUMBER)
                    .doesNotContain("7065")
                    .doesNotContain("485945");
            assertThat(uri)
                    .as("no key may carry the fingerprint either, which names the card exactly")
                    .doesNotContain(FINGERPRINT);
        }
        assertThat(response.plainTextUri())
                .as("the two renderings of one statement share one token and differ by suffix")
                .isEqualTo(response.htmlUri().replace(
                        StatementService.HTML_SUFFIX, StatementService.PLAIN_TEXT_SUFFIX));
    }

    /**
     * Asserts that the object-key token is the tokeniser's declared width and nothing longer.
     */
    @Test
    @DisplayName("the object-key token is exactly the tokeniser's declared width")
    void theObjectKeyTokenIsTheDeclaredWidth() {
        stubOneCard();

        String uri = service.describe(new StatementRequest(SEED_CARD_NUMBER, null)).plainTextUri();
        String object = uri.substring(uri.lastIndexOf('/') + 1);

        assertThat(object)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH + StatementService.PLAIN_TEXT_SUFFIX.length());
    }

    // WHY : Assumptions: the whole-run walk is asserted by the SECOND chunk's continuation argument.
    //       A walk that restarted from the beginning would loop forever on a real portfolio and would
    //       pass any assertion that only counted statements, because the first chunk alone satisfies
    //       a count.
    /**
     * Asserts that the whole-run walk continues from the last fingerprint of the previous chunk.
     */
    @Test
    @DisplayName("the whole-run walk continues by keyset from the previous chunk")
    void theWholeRunWalkContinuesByKeyset() {
        when(cardXrefs.findHeadingChunk("", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(headingRow(FINGERPRINT), headingRow(OTHER_FINGERPRINT)));
        when(cardXrefs.findHeadingChunk(OTHER_FINGERPRINT, StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        int written = service.generateStatements(sink);

        assertThat(written).as("both cards produced a statement").isEqualTo(2);
        assertThat(sink.replacements).as("the previous run's artifacts are cleared once").isEqualTo(1);
        assertThat(sink.plainRecords).as("the plain-text artifact received records").isNotEmpty();
        assertThat(sink.markupRecords).as("the markup artifact received records").isNotEmpty();
        verify(cardXrefs).findHeadingChunk("", StatementService.HEADING_CHUNK_SIZE);
        verify(cardXrefs).findHeadingChunk(OTHER_FINGERPRINT, StatementService.HEADING_CHUNK_SIZE);
    }

    // WHY : Refactoring Rationale: this case exists because the run aborted on it. Two of the projected
    //       customer attributes are nullable in the owning relation -- the middle name and the second
    //       address line -- and the band assembler refuses a null because a band field is required, so
    //       the first customer with a one-line street address raised a null-pointer failure and took the
    //       whole night's statements with it. A fixture with every attribute populated cannot reach that
    //       path, which is why it survived to be found here rather than in the existing cases.
    /**
     * Asserts that a customer with no middle name and no second address line still produces a statement.
     */
    @Test
    @DisplayName("a customer with no middle name and no second address line still statements")
    void aCustomerMissingOptionalAttributesStillStatements() {
        when(cardXrefs.findHeadingChunk("", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(sparseHeadingRow()));
        when(cardXrefs.findHeadingChunk(FINGERPRINT, StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();

        assertThatCode(() -> service.generateStatements(sink)).doesNotThrowAnyException();
        assertThat(sink.plainRecords).as("the statement was produced rather than abandoned").isNotEmpty();
    }

    /**
     * Asserts that a run over an empty portfolio still clears the previous artifacts.
     */
    @Test
    @DisplayName("a run over an empty portfolio still clears the previous artifacts")
    void anEmptyRunStillClearsThePreviousArtifacts() {
        when(cardXrefs.findHeadingChunk("", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        assertThatCode(() -> service.generateStatements(sink)).doesNotThrowAnyException();

        assertThat(sink.replacements).isEqualTo(1);
        assertThat(sink.plainRecords).isEmpty();
    }

    /**
     * Stubs the three keyed reads a single-card request path makes, plus its aggregate.
     */
    private void stubOneCard() {
        CardXrefView xref = xref(FINGERPRINT);
        when(cardXrefs.resolveByWholeCardNumber(SEED_CARD_NUMBER)).thenReturn(Optional.of(xref));
        when(cardXrefs.findById(FINGERPRINT)).thenReturn(Optional.of(xref));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1234.56", 7L));
    }

    /**
     * Builds a cross-reference projection naming one fingerprint.
     *
     * @param fingerprint the fingerprint the projection carries
     * @return the projection
     */
    private static CardXrefView xref(String fingerprint) {
        return new CardXrefView(MASKED_CARD, fingerprint, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds the customer projection the cross-reference names.
     *
     * @return the projection, carrying a credit score so the score's provenance is exercised
     */
    private static CustomerView customer() {
        return new CustomerView(CUSTOMER_ID, "ADA", "B", "LOVELACE",
                "1 MAIN ST", null, "ANYTOWN", "NY", "USA", "10001",
                LocalDate.of(1815, 12, 10), (short) 742);
    }

    /**
     * Builds the account projection the cross-reference names.
     *
     * @return the projection
     */
    private static AccountView account() {
        return new AccountView(ACCOUNT_ID, "Y", Money.of("100.00"), Money.of("5000.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                "DEFAULT   ");
    }

    /**
     * Builds one aggregate answer.
     *
     * @param total the exact total the card's transactions sum to
     * @param lineCount how many transactions the card holds
     * @return the aggregate
     */
    private static StatementTransactionRepository.StatementAggregate aggregate(
            String total, long lineCount) {
        return new StatementTransactionRepository.StatementAggregate() {
            @Override
            public BigDecimal getTotal() {
                return new BigDecimal(total);
            }

            @Override
            public long getLineCount() {
                return lineCount;
            }
        };
    }

    /**
     * Builds one whole-run heading row naming a fingerprint, in the shape a populated customer produces.
     *
     * @param fingerprint the fingerprint the row carries
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow headingRow(String fingerprint) {
        return headingRow(fingerprint, "B", null);
    }

    /**
     * Builds one whole-run heading row whose two nullable attributes are both absent.
     *
     * <p>Assumptions: both are absent in one row rather than one each in two rows, because they are
     * normalised by one mechanism and a fix addressing only one of them should fail here rather than
     * half-pass.</p>
     *
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow sparseHeadingRow() {
        return headingRow(FINGERPRINT, null, null);
    }

    /**
     * Builds one whole-run heading row naming a fingerprint, with both optional attributes supplied.
     *
     * <p>Assumptions: the row is an anonymous implementation of the closed projection rather than a
     * mock, because every one of its fifteen accessors is read while a statement is assembled and a
     * mock returning {@code null} for an unstubbed accessor would fail the run's own resolution guard
     * rather than the case under test.</p>
     *
     * @param fingerprint the fingerprint the row carries
     * @param middleName the middle name the row carries, or {@code null} for a customer with none
     * @param addressLine2 the second address line the row carries, or {@code null} for a customer with
     *     none
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow headingRow(
            String fingerprint, String middleName, String addressLine2) {
        return new StatementCardXrefRepository.StatementHeadingRow() {
            @Override
            public String getCardNum() {
                return MASKED_CARD;
            }

            @Override
            public String getCardFingerprint() {
                return fingerprint;
            }

            @Override
            public Long getCustomerId() {
                return CUSTOMER_ID;
            }

            @Override
            public Long getAccountId() {
                return ACCOUNT_ID;
            }

            @Override
            public String getFirstName() {
                return "ADA";
            }

            @Override
            public String getMiddleName() {
                return middleName;
            }

            @Override
            public String getLastName() {
                return "LOVELACE";
            }

            @Override
            public String getAddressLine1() {
                return "1 MAIN ST";
            }

            @Override
            public String getAddressLine2() {
                return addressLine2;
            }

            @Override
            public String getAddressLine3() {
                return "ANYTOWN";
            }

            @Override
            public String getStateCode() {
                return "NY";
            }

            @Override
            public String getCountryCode() {
                return "USA";
            }

            @Override
            public String getPostalCode() {
                return "10001";
            }

            @Override
            public Short getFicoCreditScore() {
                return (short) 742;
            }

            @Override
            public Money getCurrentBalance() {
                return Money.of("100.00");
            }
        };
    }

    /**
     * A sink that records what a run offered it, so the run's shape is observable.
     *
     * <p>Assumptions: a recording implementation rather than a mock, because the cases above assert
     * ORDER as well as count -- that the clearing call precedes every record -- and an implementation
     * that appends is the plainest way to hold that.</p>
     */
    private static final class RecordingSink implements StatementService.StatementSink {

        /** How many times a run asked for the previous artifacts to be discarded. */
        private int replacements;

        /** The plain-text records a run offered, in the order it offered them. */
        private final List<byte[]> plainRecords = new ArrayList<>();

        /** The markup records a run offered, in the order it offered them. */
        private final List<byte[]> markupRecords = new ArrayList<>();

        /**
         * Records one clearing call, refusing one that arrives after a record has been written.
         *
         * <p>Assumptions: the ordering is enforced here rather than asserted afterwards, because a
         * clearing call that arrived late would discard records already offered and the resulting
         * artifact would be short by however many arrived first -- a difference a count taken at the
         * end cannot see.</p>
         *
         * @throws IllegalStateException if a record was written before the artifacts were cleared
         */
        @Override
        public void replaceArtifacts() {
            if (!plainRecords.isEmpty() || !markupRecords.isEmpty()) {
                throw new IllegalStateException(
                        "the previous artifacts were discarded after a record had been written");
            }
            replacements++;
        }

        /**
         * Records one plain-text record, defensively copied.
         *
         * <p>Assumptions: the array is copied rather than retained, because a producer is free to reuse
         * one buffer across records and a retained reference would make every recorded record equal to
         * the last one.</p>
         *
         * @param record the encoded statement record offered by the run
         */
        @Override
        public void writeStatementRecord(byte[] record) {
            plainRecords.add(record.clone());
        }

        /**
         * Records one markup record, defensively copied for the reason stated above.
         *
         * @param record the encoded markup record offered by the run
         */
        @Override
        public void writeMarkupRecord(byte[] record) {
            markupRecords.add(record.clone());
        }
    }
}
