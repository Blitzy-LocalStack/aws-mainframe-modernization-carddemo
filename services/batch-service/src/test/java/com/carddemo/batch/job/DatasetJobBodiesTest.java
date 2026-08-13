package com.carddemo.batch.job;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.Card;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Customer;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.DailyFeedWatermarkRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.data.domain.Limit;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the four dataset-writing job bodies -- backup, combine, export and import -- plus the
 * read-only preflight pass.
 *
 * <p>These five jobs share a property the posting and interest jobs do not: their observable output
 * is an object written to the dataset store rather than a row. The object store is therefore a double
 * whose put requests are captured, so the assertions are about the key written, the byte length and
 * the record count rather than about a persisted entity.</p>
 */
class DatasetJobBodiesTest {

    /**
     * Card number every fixture uses.
     */
    private static final String CARD = "4111111111111111";

    /**
     * Dataset bucket every write is addressed to.
     */
    private static final String BUCKET = "carddemo-datasets-test";

    /**
     * Business date supplying the dataset partition.
     */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /**
     * Fixed clock so an export timestamp is reproducible.
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T10:15:30.123456Z"), ZoneOffset.UTC);

    private final S3Client objectStore = mock(S3Client.class);

    private final TransactionRepository transactions = mock(TransactionRepository.class);

    private final AccountRepository accounts = mock(AccountRepository.class);

    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    private final CustomerRepository customers = mock(CustomerRepository.class);

    private final CardRepository cards = mock(CardRepository.class);

    private final DailyTransactionRepository feed = mock(DailyTransactionRepository.class);

    /**
     * Stubs every export source empty, so a case can populate only the one it is about.
     */
    private void stubEveryExportSourceEmpty() {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.empty());
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.empty());
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
    }

    /**
     * Stubs all five export sources with one row each, in the reference's own phase order.
     *
     * <p>Assumptions: this helper exists because the export takes five sources and a case that stubbed
     * only the ones it cared about would leave the rest returning {@code null} from the mock rather
     * than an empty stream, which fails inside the walk with a message about a null stream rather than
     * about the case's own subject.</p>
     */
    private void stubOneRowPerRecordType() {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));
        // WHY : Assumptions: the card is stubbed as belonging to account 1, the same account the other
        //       four stubs name, so one row per type describes one consistent account rather than five
        //       unrelated rows. The helper takes the account identifier because the card master carries
        //       it, which is what the cross-reference above resolves.
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));
    }

    /**
     * Bytes of every payload published through {@link #objectStore}, snapshotted at put time.
     *
     * <p>Refactoring Rationale: the payload is snapshotted DURING the put rather than read from the
     * captured {@code RequestBody} afterwards, and that is now a correctness requirement rather than a
     * preference. The export stages its dataset in a temporary file and deletes that file before
     * returning, so a body captured by Mockito and read after the call refers to a path that no longer
     * exists. Snapshotting inside the stub is also the more faithful double: a real client reads the
     * body during the request, which is exactly the contract {@code RequestBody} publishes -- a stream
     * handle valid for the duration of the call and not beyond it.</p>
     */
    private final List<byte[]> publishedPayloads = new java.util.ArrayList<>();

    /**
     * Makes the object-store double read each payload as a real client would.
     *
     * @throws java.io.IOException if a payload stream cannot be read, which would mean the job under
     *     test supplied a body no client could send
     */
    @org.junit.jupiter.api.BeforeEach
    void snapshotEveryPublishedPayload() throws java.io.IOException {
        when(objectStore.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    RequestBody body = call.getArgument(1);
                    try (java.io.InputStream stream = body.contentStreamProvider().newStream()) {
                        this.publishedPayloads.add(stream.readAllBytes());
                    }
                    return null;
                });
    }

    /**
     * Returns the single dataset published through the object store.
     *
     * @return the published bytes
     */
    private byte[] stagedDataset() {
        assertThat(this.publishedPayloads)
                .as("exactly one object is published per export run")
                .hasSize(1);
        return this.publishedPayloads.get(0);
    }

    /**
     * Extracts one whole record from a dataset by its ordinal position.
     *
     * @param dataset the published dataset
     * @param ordinal the zero-based record position
     * @return exactly one record image
     */
    private static byte[] recordAt(byte[] dataset, int ordinal) {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        return java.util.Arrays.copyOfRange(dataset, ordinal * reclen, (ordinal + 1) * reclen);
    }

    /**
     * Reads the discriminator byte of every record in a dataset, in write order.
     *
     * <p>Assumptions: the discriminator is the record's FIRST byte, which is what
     * {@code app/cpy/CVEXPORT.cpy:11} declares and what {@code app/cbl/CBIMPORT.cbl:272-286}
     * dispatches on. Reading it positionally rather than through the decoder is deliberate -- see the
     * calling case.</p>
     *
     * @param dataset the published dataset
     * @return the discriminator of each record, in order
     */
    private static List<Character> discriminatorsOf(byte[] dataset) {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        List<Character> discriminators = new java.util.ArrayList<>();
        for (int offset = 0; offset < dataset.length; offset += reclen) {
            discriminators.add((char) (dataset[offset] & 0xFF));
        }
        return discriminators;
    }

    /**
     * Lists any export staging file still present in the temporary directory.
     *
     * <p>Assumptions: the stem is matched rather than an exact name, because the remainder of the name
     * is generated to make the path unique. A file from a concurrently running test of this same class
     * could in principle be seen here, which is why the assertion that uses this runs in a case whose
     * own run has completed -- at that point this class has no staging file outstanding.</p>
     *
     * @param stem the per-job filename stem to match, for example {@code export} or {@code import-}
     * @return the surviving staging files, empty when the run cleaned up after itself
     * @throws java.io.IOException if the temporary directory cannot be listed
     */
    private static List<java.nio.file.Path> stagingLeftovers(String stem) throws java.io.IOException {
        java.nio.file.Path temporaryDirectory =
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<java.nio.file.Path> entries = java.nio.file.Files.list(temporaryDirectory)) {
            return entries.filter(candidate -> candidate.getFileName().toString()
                            .startsWith("carddemo-" + stem)
                            && candidate.getFileName().toString().endsWith(".staging"))
                    .toList();
        }
    }

    /**
     * Builds an object-store double that streams one dataset back and captures every artefact put.
     *
     * <p>Refactoring Rationale: the double now stubs {@code getObject} rather than
     * {@code getObjectAsBytes}, because the import job streams its input one record at a time instead of
     * fetching the whole dataset as an array -- a double that only answered the array form would leave
     * the streaming call unstubbed and the job would read nothing at all, so the assertions would fail
     * for the wrong reason. It also snapshots each published artefact during the put, for the reason the
     * export-side stub records: the artefacts are staged files that the job deletes before returning, so
     * a body read after the call refers to a path that no longer exists.</p>
     *
     * <p>Assumptions: a FRESH stream is supplied on every invocation rather than one shared instance,
     * because a stream is consumed by the first reader and a case that read twice would otherwise see an
     * empty artefact the second time.</p>
     *
     * @param dataset the bytes the export artefact holds
     * @return the double, with the read stubbed and the writes captured; never {@code null}
     */
    private S3Client readerOf(byte[] dataset) {
        S3Client reader = mock(S3Client.class);
        when(reader.getObject(any(GetObjectRequest.class))).thenAnswer(call ->
                new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new java.io.ByteArrayInputStream(dataset))));
        when(reader.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    RequestBody body = call.getArgument(1);
                    try (java.io.InputStream stream = body.contentStreamProvider().newStream()) {
                        this.publishedPayloads.add(stream.readAllBytes());
                    }
                    return null;
                });
        return reader;
    }

    /**
     * Installs an answer that copies each put artefact's bytes out before the job deletes the file.
     *
     * <p>Refactoring Rationale: the bodies MUST be read inside the call rather than captured and
     * read afterwards. Both jobs delete their staging files in a {@code finally} block, so a captured
     * {@code Path} refers to a file that no longer exists by the time an assertion runs, and reading
     * it would raise rather than fail with a useful message. Copying at call time is what lets the
     * assertions still be about bytes.</p>
     *
     * <p>Assumptions: an insertion-ordered map keyed by object key is returned rather than a list,
     * because every assertion here is about one named artefact and the key is what names it. A record
     * type that is put twice would overwrite its own entry, which is why the put COUNT is asserted
     * separately from the contents where it matters.</p>
     *
     * @param store the object-store double to install the answer on; must not be {@code null}
     * @return a live map from object key to the bytes put under it; never {@code null}
     */
    private static Map<String, byte[]> recordBodies(S3Client store) {
        Map<String, byte[]> written = new LinkedHashMap<>();
        // WHY : Assumptions: the stubbed overload is putObject(PutObjectRequest, RequestBody), which is
        //       the one both jobs call -- ExportJob and ImportJob each build a RequestBody from the
        //       staged file and DatasetPayloadWriter builds one from bytes. Stubbing the Path overload
        //       instead compiles and stubs a method nothing invokes, so the map stays empty and every
        //       assertion over it fails on a null rather than on the artefact's contents.
        when(store.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    PutObjectRequest request = call.getArgument(0);
                    RequestBody body = call.getArgument(1);
                    try (java.io.InputStream stream = body.contentStreamProvider().newStream()) {
                        written.put(request.key(), stream.readAllBytes());
                    }
                    return null;
                });
        return written;
    }

    /**
     * Stubs a streaming get of one artefact, declaring its true length.
     *
     * <p>Assumptions: the declared content length is set from the array's own length rather than left
     * unset, because the import body reads it to refuse an artefact whose length is not a whole
     * multiple of the record length. Leaving it unset would silently skip that check and let a case
     * that means to exercise it pass without doing so.</p>
     *
     * @param store the object-store double to stub; must not be {@code null}
     * @param dataset the artefact bytes the get returns; must not be {@code null}
     */
    private static void stubGet(S3Client store, byte[] dataset) {
        when(store.getObject(any(GetObjectRequest.class))).thenAnswer(call ->
                new ResponseInputStream<>(
                        GetObjectResponse.builder()
                                .contentLength((long) dataset.length)
                                .build(),
                        new ByteArrayInputStream(dataset)));
    }

    /**
     * Writes a value into one payload field of one export record, in place.
     *
     * <p>Assumptions: the write goes through the PRODUCTION encoder rather than copying bytes by
     * hand, and that is what makes this helper usable for all three protected spans. They do not share
     * a storage kind: the national identifier is display numeric, the government-issued identifier is
     * character, and the verification value is a two-byte binary integer. A hand-written byte copy
     * would produce a well-formed value for the first two and a meaningless one for the third, so a
     * case built on it would appear to supply a verification value while supplying nothing the decoder
     * could read -- and the redaction assertion would then pass without having been tested.</p>
     *
     * @param dataset the whole dataset being modified in place; must not be {@code null}
     * @param recordOffset the byte offset of the record within {@code dataset}
     * @param view the record type whose payload interpretation applies; must not be {@code null}
     * @param fieldName the payload field to overwrite; must not be {@code null}
     * @param value the replacement value, in the form the field's storage kind accepts; must not be
     *     {@code null}
     */
    private static void overwriteViewField(byte[] dataset, int recordOffset, RecordType view,
            String fieldName, Object value) {

        CopybookLayout.FieldSpec payloadSpan =
                ExportRecordMapper.recordLayout().field("EXPORT-RECORD-DATA");
        CopybookLayout.FieldSpec span = ExportRecordMapper.viewLayout(view).field(fieldName);

        byte[] payload = Arrays.copyOfRange(dataset, recordOffset + payloadSpan.start(),
                recordOffset + payloadSpan.end());
        FixedWidthCodec.encodeField(value, span, payload, StandardCharsets.US_ASCII);
        System.arraycopy(payload, 0, dataset, recordOffset + payloadSpan.start(), payload.length);
    }

    /**
     * Reads one field of one fixed-width record as text.
     *
     * @param record the whole record image; must not be {@code null}
     * @param layoutName the record layout's registered name; must not be {@code null}
     * @param fieldName the field to read; must not be {@code null}
     * @return the field's bytes decoded as US-ASCII; never {@code null}
     */
    private static String field(byte[] record, String layoutName, String fieldName) {
        return field(record, CopybookLayout.layout(layoutName), fieldName);
    }

    /**
     * Reads one field of one fixed-width record as text, under a descriptor supplied directly.
     *
     * <p>Assumptions: the export record's descriptor is not in the shared registry -- it is assembled
     * by {@link ExportRecordMapper} -- so a name-based lookup cannot reach it and this overload takes
     * the descriptor instead. Both overloads read through a descriptor rather than a literal offset,
     * which is the property that matters.</p>
     *
     * @param record the whole record image; must not be {@code null}
     * @param layout the descriptor the field belongs to; must not be {@code null}
     * @param fieldName the field to read; must not be {@code null}
     * @return the field's bytes decoded as US-ASCII; never {@code null}
     */
    private static String field(byte[] record, CopybookLayout.RecordSpec layout, String fieldName) {
        CopybookLayout.FieldSpec span = layout.field(fieldName);
        return new String(Arrays.copyOfRange(record, span.start(), span.end()),
                StandardCharsets.US_ASCII);
    }

    /**
     * Builds a transaction row.
     *
     * @param transactionId the sixteen-character identifier
     * @return the transaction; never {@code null}
     */
    private static Transaction transaction(String transactionId) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd("01");
        row.setCategoryCd("0005");
        row.setSource("POS");
        row.setDescription("purchase");
        row.setAmount(new BigDecimal("10.00"));
        row.setMerchantId(999L);
        row.setMerchantName("merchant");
        row.setMerchantCity("city");
        row.setMerchantZip("12345");
        row.setCardNum(CARD);
        row.setOrigTs(LocalDateTime());
        row.setProcTs(LocalDateTime());
        return row;
    }

    /**
     * Supplies the one timestamp every fixture row carries.
     *
     * @return the fixed timestamp; never {@code null}
     */
    private static java.time.LocalDateTime LocalDateTime() {
        return java.time.LocalDateTime.of(2022, 7, 18, 10, 15, 30);
    }

    /**
     * Builds an account row.
     *
     * @param accountId the account identifier
     * @return the account; never {@code null}
     */
    private static Account account(long accountId) {
        return new Account(accountId, "Y", new BigDecimal("100.00"), new BigDecimal("9000.00"),
                new BigDecimal("500.00"), LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1),
                LocalDate.of(2024, 1, 1), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "12345", "ZG");
    }

    /**
     * Builds a customer row carrying a value in every field the export view renders.
     *
     * <p>Assumptions: every character field is given a value shorter than its declared span, so the
     * encode exercises the codec's blank padding rather than its exact-fit path -- a fixture sized
     * exactly to each span would pass even if the padding rule were wrong.</p>
     *
     * @param customerId the nine-digit identifier
     * @return the customer; never {@code null}
     */
    private static Customer customer(long customerId) {
        return new Customer(customerId, "AVA", "B", "STONE", "1 HIGH ST", "APT 2", "BLOCK C",
                "NY", "USA", "10001", "2125550100", "2125550101", LocalDate.of(1985, 4, 2),
                "9876543210", "Y", (short) 742);
    }

    /**
     * Builds a card row carrying a value in every field the export view renders.
     *
     * @param cardNumber the sixteen-character primary account number
     * @param accountId the owning account identifier
     * @return the card; never {@code null}
     */
    private static Card card(String cardNumber, long accountId) {
        return new Card(cardNumber, accountId, "AVA B STONE", LocalDate.of(2027, 12, 31), "Y");
    }

    /**
     * Stubs the customer and card masters as empty for a case that asserts only the other three types.
     *
     * <p>Assumptions: an EMPTY stream rather than an unstubbed mock. An unstubbed repository returns
     * {@code null} and the phase would fail on it, so the absence has to be stated; and stating it
     * keeps each case's expected record count derivable from what that case stubbed.</p>
     */
    private void stubNoCustomersOrCards() {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.empty());
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
    }

    /**
     * Captures every put request issued against the object store.
     *
     * @return the captured requests, in order
     */
    private List<PutObjectRequest> puts() {
        ArgumentCaptor<PutObjectRequest> captor =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(objectStore, org.mockito.Mockito.atLeast(0))
                .putObject(captor.capture(), any(RequestBody.class));
        return captor.getAllValues();
    }



    /**
     * The export job writes ALL FIVE record types the reference writes, in the reference's own order.
     *
     * <p>Refactoring Rationale: this case previously asserted only that ONE object was written under
     * the right key, and it was named for the "three owned record types". That is what let the export
     * ship two whole record types short: the customer and card phases logged a heading, reported zero
     * and emitted nothing, and no assertion could tell the difference because the key was still right,
     * the object still existed, and the counter reconciliation still agreed with itself. This case now
     * asserts the record COUNT, so a missing phase changes the length; the DISCRIMINATOR at each record's
     * first byte, so a phase emitting the wrong type is caught; the phase ORDER, which is part of the
     * output contract; and that the customer and card payloads carry real field content rather than a
     * run of pad bytes.</p>
     *
     * <p>Assumptions: the discriminators are read out of the dataset at the record-length stride rather
     * than through the decoder, so the assertion depends on the bytes and not on the decoder agreeing
     * with the encoder. A decoded assertion would pass even if both halves shared one wrong letter.</p>
     *
     * @throws java.io.IOException if the staged dataset cannot be re-read from the captured request
     *     body, which would mean the export published a stream no consumer can read
     */
    @Test
    @DisplayName("the export job writes all five record types in the reference's phase order")
    void theExportJobWritesAllFiveRecordTypes() throws java.io.IOException {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));

        assertThat(ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences,
                transactions, cards, objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

        assertThat(puts()).singleElement().satisfies(request ->
                assertThat(request.key()).isEqualTo("export/2022071800/export.dat"));

        int reclen = ExportRecordMapper.recordLayout().reclen();
        byte[] dataset = stagedDataset();
        assertThat(dataset)
                .as("one record from each of the five phases")
                .hasSize(5 * reclen);

        assertThat(discriminatorsOf(dataset))
                .as("the phase order of app/cbl/CBEXPORT.cbl: customer, account, xref, transaction, card")
                .containsExactly('C', 'A', 'X', 'T', 'D');

        // WHY : Assumptions: the customer and card records are checked for CONTENT and not merely for
        //       presence, because a phase that emitted a correctly stamped but wholly blank record
        //       would satisfy both the length and the discriminator assertions above. Decoding is used
        //       here -- unlike for the discriminators -- because the point is that the values survived
        //       the projection, and the projection and the decoder are separate code paths.
        assertThat(ExportRecordMapper.decode(recordAt(dataset, 0)).customerFields())
                .containsEntry("EXP-CUST-LAST-NAME", "STONE".concat(" ".repeat(20)))
                .containsEntry("EXP-CUST-ADDR-STATE-CD", "NY")
                .containsEntry("EXP-CUST-FICO-CREDIT-SCORE", new BigDecimal("742"));
        assertThat(ExportRecordMapper.decode(recordAt(dataset, 4)).cardFields())
                .containsEntry("EXP-CARD-NUM", CARD)
                .containsEntry("EXP-CARD-EXPIRAION-DATE", "2027-12-31")
                .containsEntry("EXP-CARD-ACTIVE-STATUS", "Y");
    }

    /**
     * The two protected customer fields and the card verification value are redacted, not exported.
     *
     * <p>Assumptions: this asserts the DELIBERATE redaction the class note records, so that a future
     * change which "completes" these three fields by reading the enciphered columns fails here rather
     * than silently shipping a cleartext-secret extract. The national identifier is a zero because its
     * span is unsigned display and cannot be blank; the government-issued identifier is blank; the card
     * verification value is the encoded zero the absent carrier writes, and it is absent from the
     * decoded projection entirely because the mapper suppresses it.</p>
     *
     * @throws java.io.IOException if the staged dataset cannot be re-read
     */
    @Test
    @DisplayName("the protected customer and card fields are exported redacted")
    void theProtectedFieldsAreExportedRedacted() throws java.io.IOException {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.empty());
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));

        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);

        byte[] dataset = stagedDataset();
        // WHY : Assumptions: the expected national identifier is the boxed long zero and not a
        //       BigDecimal. The shared codec decodes an unsigned-display span to a Long and a
        //       packed-decimal span to a BigDecimal, and the two are unequal even at the same value --
        //       which is why this assertion names the type the span's own kind produces rather than the
        //       type the neighbouring credit-score assertion uses.
        assertThat(ExportRecordMapper.decode(recordAt(dataset, 0)).customerFields())
                .containsEntry("EXP-CUST-SSN", 0L)
                .containsEntry("EXP-CUST-GOVT-ISSUED-ID", " ".repeat(20));
        assertThat(ExportRecordMapper.decode(recordAt(dataset, 1)).cardFields())
                .as("the verification value never appears in a projection this module can read")
                .doesNotContainKey("EXP-CARD-CVV-CD");
    }

    /**
     * The staged dataset is published in one request whose length describes the records counted.
     *
     * <p>Refactoring Rationale: this case exists because the export no longer accumulates its dataset on
     * the heap. It stages the encoded records to a temporary file and publishes that file, so what has to
     * be asserted is that the published body is still ONE object of exactly the expected length -- the
     * all-or-nothing property the previous in-memory shape provided and which staging had to preserve.
     * It also asserts the staging file does not survive the run, because a file holding customer and card
     * data left on a reused task's volume is a disclosure as well as a leak.</p>
     *
     * @throws java.io.IOException if the temporary directory cannot be listed for surviving staging
     *     files
     */
    @Test
    @DisplayName("the staged dataset is published once and the staging file is removed")
    void theStagedDatasetIsPublishedOnceAndCleanedUp() throws java.io.IOException {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));

        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);

        int reclen = ExportRecordMapper.recordLayout().reclen();
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(objectStore).putObject(any(PutObjectRequest.class), body.capture());
        assertThat(body.getValue().optionalContentLength())
                .as("three records staged and published as one object")
                .contains(3L * reclen);
        assertThat(stagingLeftovers("export"))
                .as("no staging file survives a completed run")
                .isEmpty();
    }

    /**
     * A MULTI-CARD account contributes one cross-reference record per card, not one per account.
     *
     * <p>Refactoring Rationale: this case is the one the export body previously failed. It walked the
     * account master a second time and took the lowest-numbered card of each account, so an account
     * holding three cards exported ONE cross-reference record and the other two were absent -- with
     * nothing reporting it, because the dataset stayed well formed and every record in it stayed
     * correct. {@code app/cbl/CBEXPORT.cbl:47-51} opens the cross-reference
     * {@code ACCESS MODE IS SEQUENTIAL} and {@code :376-389} reads it until end of file, so the
     * reference writes one record per CARD, and an account legitimately holds many: the by-account
     * index is {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:74-75}.</p>
     *
     * <p>Assumptions: the byte length is asserted rather than the record contents, because the count
     * is what was lost. Three cards under ONE account give four records -- one account plus three
     * cross-references -- where the previous shape gave two, so the two shapes are distinguishable by
     * length alone and the assertion cannot pass under a regression.</p>
     */
    @Test
    @DisplayName("export one cross-reference record per card of a multi-card account")
    void aMultiCardAccountContributesOneRecordPerCard() {
        stubEveryExportSourceEmpty();
        stubNoCustomersOrCards();
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(
                new CardXref("4111111111111111", 555L, 1L),
                new CardXref("4111111111111112", 555L, 1L),
                new CardXref("4111111111111113", 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        assertThat(ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences,
                transactions, cards, objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

        int reclen = ExportRecordMapper.recordLayout().reclen();
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(objectStore).putObject(any(PutObjectRequest.class), body.capture());
        assertThat(body.getValue().optionalContentLength())
                .as("one account record plus THREE cross-reference records")
                .contains(4L * reclen);
    }

    /**
     * The cross-reference walk is a single ordered pass, not one query per account.
     *
     * <p>Assumptions: this asserts the SHAPE of the access rather than the output, because the two
     * shapes can agree on the output for single-card accounts and disagree on every multi-card one.
     * The account master is walked exactly once -- for the account records -- and the by-account
     * finder, which is bounded to a single row and belongs to the interest flow, is never called.</p>
     */
    @Test
    @DisplayName("walk the cross-reference table once and never per account")
    void theCrossReferenceWalkIsOneOrderedPass() {
        stubEveryExportSourceEmpty();
        stubNoCustomersOrCards();
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L), account(2L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);

        verify(crossReferences, org.mockito.Mockito.times(1)).findAllByOrderByCardNumAsc();
        verify(crossReferences, never()).findFirstByAccountIdOrderByCardNumAsc(any());
        verify(accounts, org.mockito.Mockito.times(1)).findAllByOrderByAccountIdAsc();
    }

    /**
     * An empty cross-reference table contributes no cross-reference record.
     */
    @Test
    @DisplayName("an empty cross-reference table contributes no cross-reference record")
    void anEmptyCrossReferenceTableContributesNoRecord() {
        stubEveryExportSourceEmpty();
        stubNoCustomersOrCards();
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        assertThat(ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences,
                transactions, cards, objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

        int reclen = ExportRecordMapper.recordLayout().reclen();
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(objectStore).putObject(any(PutObjectRequest.class), body.capture());
        assertThat(body.getValue().optionalContentLength())
                .as("one account record only, so exactly one record length")
                .contains((long) reclen);
    }

    /**
     * The import job separates a dataset by record type and writes an output per type.
     *
     * @throws java.io.IOException if the exported payload cannot be re-read from the captured
     *     request body, which would mean the export body produced an unreadable stream
     */
    @Test
    @DisplayName("the import job separates the dataset into one output per record type")
    void theImportJobSeparatesByRecordType() throws java.io.IOException {
        stubOneRowPerRecordType();
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));

        // WHY : Refactoring Rationale: the dataset the import reads is produced by the export body
        //       rather than hand-built, so this case asserts the round trip the migration plan asks
        //       for: every image the exporter writes is one the importer recognises and decodes. A
        //       hand-built fixture would let the two halves drift apart and still pass. All FIVE
        //       masters are now stubbed non-empty, so the round trip covers every record type rather
        //       than three of them -- with three types the customer and card branches of the import
        //       dispatcher were reached by no test at all.
        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);
        byte[] dataset = stagedDataset();

        S3Client reader = readerOf(dataset);

        assertThat(ImportJob.separate(BUSINESS_DATE, reader, BUCKET))
                .isEqualTo(BatchReturnCode.CLEAN);

        ArgumentCaptor<PutObjectRequest> outputs =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(reader, org.mockito.Mockito.atLeast(0))
                .putObject(outputs.capture(), any(RequestBody.class));
        assertThat(outputs.getAllValues()).extracting(PutObjectRequest::key)
                .as("one output per record type plus the error output, all under the partition")
                .contains("import/2022071800/account.dat",
                        "import/2022071800/card_xref.dat",
                        "import/2022071800/transaction.dat",
                        "import/2022071800/customer.dat",
                        "import/2022071800/card.dat",
                        "import/2022071800/error.dat")
                .hasSize(RecordType.values().length + 1);
    }

    /**
     * A dataset whose length is not a whole multiple of the record length FAILS the run and publishes
     * nothing.
     *
     * <p>Refactoring Rationale: this case has been rewritten twice and the second rewrite is the
     * behavioural one. It first asserted that the error output held the raw remainder; then that it held
     * one 132-byte diagnostic record, which matched the layout. Both expected the run to end CLEAN and to
     * publish its artefacts, and that is the expectation the import job no longer meets. A truncated input
     * means every record after the truncation point is unreadable, the artefacts these jobs produce have
     * no golden master to be compared against, and a clean status on a knowingly partial product is
     * undetectable downstream. Divergence D-9 on {@code ImportJob} carries the full argument.</p>
     *
     * <p>Assumptions: the assertion is that NOTHING was put, which is stronger than asserting the tier.
     * Failing after publishing five of six artefacts would satisfy an exit-status assertion and would
     * still leave a partial set in the store for a loader to consume.</p>
     */
    @Test
    @DisplayName("a truncated dataset fails the run and publishes no artefact")
    void aTruncatedDatasetFailsTheRun() {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        byte[] truncated = new byte[reclen / 2];

        S3Client reader = readerOf(truncated);

        assertThatThrownBy(() -> ImportJob.separate(BUSINESS_DATE, reader, BUCKET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truncated")
                .hasMessageContaining(String.valueOf(reclen / 2));

        verify(reader, org.mockito.Mockito.never())
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * The export and import jobs refuse a launch that omits a required parameter.
     *
     * <p>Refactoring Rationale: neither job carried the shared parameter validator. Five of this
     * package's seven jobs were built with it and these two were not, so a launch of either with no
     * business date reached the step body and failed partway through -- and for the import that means an
     * artefact set may already be half published. This case asserts the refusal happens at validation,
     * before the body is entered, which is both earlier and cheaper than a mid-run failure.</p>
     *
     * <p>Assumptions: the assertion is made through the job's own {@code getJobParametersValidator}
     * rather than by launching a job and observing a failure, because the point is WHICH mechanism
     * refuses: a body that threw on a missing parameter would also fail a launch, and the two are
     * distinguishable only by asking the job what validator it holds.</p>
     */
    @Test
    @DisplayName("the export and import jobs validate their parameters before running")
    void theDatasetJobsValidateTheirParameters() {
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        JobParameters missingRunId = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, "2022-07-18", true)
                .toJobParameters();
        JobParameters complete = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, "2022-07-18", true)
                .addString(BatchConfig.RUN_ID_PARAMETER, "dataset-run", false)
                .toJobParameters();
        JobParameters missingBusinessDate = new JobParametersBuilder()
                .addString(BatchConfig.RUN_ID_PARAMETER, "dataset-run", false)
                .toJobParameters();

        for (Job job : List.of(exportJob(validator), importJob(validator))) {
            assertThat(job.getJobParametersValidator())
                    .as("%s holds the shared validator rather than none", job.getName())
                    .isNotNull();
            assertThatThrownBy(() -> job.getJobParametersValidator().validate(missingRunId))
                    .as("%s refuses a launch missing the run identifier", job.getName())
                    .isInstanceOf(InvalidJobParametersException.class);
            assertThatThrownBy(() -> job.getJobParametersValidator().validate(missingBusinessDate))
                    .as("%s refuses a launch missing the business date", job.getName())
                    .isInstanceOf(InvalidJobParametersException.class);
            assertThatCode(() -> job.getJobParametersValidator().validate(complete))
                    .as("%s accepts a launch carrying exactly the two required parameters",
                            job.getName())
                    .doesNotThrowAnyException();
            // WHY : ⚠️ Assumptions: an UNRECOGNISED parameter is deliberately NOT asserted to be
            //       refused, because the framework's default validator does not refuse one when no
            //       optional keys are declared -- it checks that every supplied key is required or
            //       optional only when the optional set is non-empty. That behaviour is recorded on the
            //       validator bean itself, whose rationale previously claimed the opposite; asserting
            //       the claim here would have made this case fail rather than making the claim true.
            assertThatCode(() -> job.getJobParametersValidator().validate(
                            new JobParametersBuilder(complete)
                                    .addString("aParameterThisModuleDoesNotName", "value", false)
                                    .toJobParameters()))
                    .as("%s tolerates an unrecognised parameter, which is the framework default",
                            job.getName())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * A completed import leaves none of its seven staged artefacts behind.
     *
     * <p>Refactoring Rationale: this case exists because the import no longer accumulates its six
     * outputs and its diagnostic output on the heap. Each is a staged file on the task's ephemeral
     * volume, and those files hold account, card, customer and transaction records -- so a run that
     * failed to remove them would leak volume and disclose data on a task that is reused across
     * executions. The assertion is on the absence of the files rather than on a log line, because a
     * deletion that was logged and did not happen would satisfy the latter.</p>
     *
     * @throws java.io.IOException if the temporary directory cannot be listed
     */
    @Test
    @DisplayName("a completed import removes every staged artefact")
    void aCompletedImportRemovesEveryStagedArtefact() throws java.io.IOException {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));
        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);

        ImportJob.separate(BUSINESS_DATE, readerOf(stagedDataset()), BUCKET);

        assertThat(stagingLeftovers("import-"))
                .as("none of the seven staged import artefacts survives the run")
                .isEmpty();
    }

    /**
     * Builds the export job bean with every collaborator doubled.
     *
     * @param validator the shared parameter validator
     * @return the assembled job; never {@code null}
     */
    private Job exportJob(JobParametersValidator validator) {
        return new ExportJob().exportDataset(new ResourcelessJobRepository(),
                new ResourcelessTransactionManager(), passThroughStep(), customers, validator,
                accounts, crossReferences, transactions, cards, objectStore, BUCKET,
                ExportJob.DEFAULT_BRANCH_ID, ExportJob.DEFAULT_REGION_CODE, "", CLOCK);
    }

    /**
     * Builds the import job bean with every collaborator doubled.
     *
     * @param validator the shared parameter validator
     * @return the assembled job; never {@code null}
     */
    private Job importJob(JobParametersValidator validator) {
        return new ImportJob().importDataset(new ResourcelessJobRepository(),
                new ResourcelessTransactionManager(), passThroughStep(), validator, objectStore,
                BUCKET, "", CLOCK);
    }

    /**
     * Supplies a ledger-guarded step builder whose ledger runs the body it is handed.
     *
     * <p>Assumptions: the step is never executed by the case that uses it -- only the assembled job's
     * validator is read -- so the ledger's behaviour is immaterial here and is stubbed only so the
     * builder can be constructed.</p>
     *
     * @return the step builder; never {@code null}
     */
    private BatchConfig.LedgerGuardedStep passThroughStep() {
        // WHY : Assumptions: the builder is obtained through the configuration's own factory method
        //       rather than by calling its constructor, because that constructor is package-private to
        //       the configuration package -- which is the boundary that keeps the run identifier bound
        //       exactly once, from the container variable. Going through the factory means this case
        //       constructs the builder the same way production does.
        return new BatchConfig().ledgerGuardedStep(mock(BatchStepLedger.class), "dataset-run");
    }

    /**
     * The preflight pass reports clean even when nothing resolves, and consults no account.
     *
     * <p>Assumptions: the pass is reached through the job's own step rather than by calling the walk
     * directly, because the walk is private to the job and the step is the only route production takes to
     * it. The step ledger is substituted with one that runs the body it is handed and hands back what the
     * body returned, which is how the graded return code is observed without a database.</p>
     *
     * <p>Assumptions: the feed is stubbed on the KEYSET walk rather than on the stream, because that is
     * the access path this job takes -- one bounded batch at a time, terminating on the empty batch. A
     * stream stub would leave the real call unstubbed and the pass would read nothing at all, so the
     * assertion would pass for the wrong reason.</p>
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("preflight grades clean even when no record resolves")
    void preflightGradesCleanEvenWhenNothingResolves() throws Exception {
        stubFeedWithOneRecord();
        when(crossReferences.findByCardNum(CARD)).thenReturn(Optional.empty());

        assertThat(runPreflight())
                .as("the reference contains no RETURN-CODE statement, so it always ends at zero")
                .isEqualTo(BatchReturnCode.CLEAN);
        verify(accounts, never()).findByAccountId(any());
    }

    /**
     * Preflight consults the account only for a record whose card resolved.
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("preflight looks an account up only when the card resolved")
    void preflightLooksUpTheAccountOnlyWhenTheCardResolved() throws Exception {
        stubFeedWithOneRecord();
        when(crossReferences.findByCardNum(CARD))
                .thenReturn(Optional.of(new CardXref(CARD, 555L, 1L)));
        when(accounts.findByAccountId(1L)).thenReturn(Optional.of(account(1L)));

        assertThat(runPreflight()).isEqualTo(BatchReturnCode.CLEAN);
        verify(accounts).findByAccountId(1L);
    }

    /**
     * Stubs the feed's keyset walk with exactly one record, then exhaustion.
     *
     * <p>Assumptions: the second answer is the EMPTY batch rather than the same batch again, because the
     * walk terminates on emptiness; repeating a non-empty batch would not terminate.</p>
     */
    private void stubFeedWithOneRecord() {
        DailyTransaction record = new DailyTransaction("0000000000000001", "01", "0005", "POS",
                "purchase", new BigDecimal("10.00"), 1L, "m", "c", "12345", CARD,
                LocalDateTime(), LocalDateTime());
        when(feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(any(), any(Limit.class)))
                .thenReturn(List.of(record))
                .thenReturn(List.of());
    }

    /**
     * Runs the preflight job once through its step and returns the code the pass graded.
     *
     * @return the graded return code, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private BatchReturnCode runPreflight() throws Exception {
        BatchStepLedger ledgerOfSteps = mock(BatchStepLedger.class);
        when(ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> {
                    BatchReturnCode outcome =
                            call.<Supplier<BatchReturnCode>>getArgument(3).get();
                    return new BatchStepLedger.StepOutcome(outcome, false);
                });

        // WHY : Assumptions: the watermark table answers EMPTY, so the preflight reports on the whole
        //       feed exactly as it did before the cursor existed and every count this method's
        //       callers assert stays a measurement of the inspection rather than of the cursor. The
        //       service over it is real, because the position it returns for an absent row is the
        //       service's rule and not the repository's.
        DailyFeedWatermarkRepository watermarks = mock(DailyFeedWatermarkRepository.class);
        when(watermarks.findById(anyString())).thenReturn(Optional.empty());
        when(watermarks.findByFeedName(anyString())).thenReturn(Optional.empty());

        PreflightDailyTransactionsJob configuration = new PreflightDailyTransactionsJob(
                feed, crossReferences, accounts, ledgerOfSteps,
                new DailyFeedWatermarkService(watermarks, Clock.systemUTC()));
        Job job = configuration.preflightDailyTransactions(new ResourcelessJobRepository(),
                new ResourcelessTransactionManager(), new BatchConfig().carddemoJobParametersValidator());

        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, "2022-07-18", true)
                .addString(BatchConfig.RUN_ID_PARAMETER, "preflight-run", false)
                .toJobParameters();
        JobExecution execution = new JobExecution(1L,
                new JobInstance(1L, PreflightDailyTransactionsJob.JOB_NAME), parameters);
        job.execute(execution);

        ArgumentCaptor<Supplier<BatchReturnCode>> body = ArgumentCaptor.forClass(Supplier.class);
        verify(ledgerOfSteps)
                .runStep(anyString(), anyString(), any(BatchJobName.class), body.capture());
        return ledgerOfSteps.runStep("observed", PreflightDailyTransactionsJob.JOB_NAME,
                BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS,
                body.getValue()).returnCode();
    }
    /**
     * The imported customer and card artefacts persist NO protected value.
     *
     * <p>Assumptions: the artefacts are durable objects in the dataset bucket, so this case is what
     * holds the line on the one rule that admits no exception: a card verification value may not be
     * retained after authorisation at all, encrypted or otherwise. The two customer identifiers are
     * asserted alongside it because they are redacted under the same reasoning -- the migration plan
     * holds them stored encrypted and returned masked, and an object store is neither.</p>
     *
     * <p>Assumptions: the input is built by hand with a REAL value in each of the three protected
     * spans, rather than round-tripped from the export. That is deliberate and it is the only way this
     * case can prove anything: the export already redacts all three, so an export-produced dataset
     * carries zeros there and the assertion would hold no matter what the import did with a value it
     * was given.</p>
     */
    @Test
    @DisplayName("the imported customer and card artefacts persist no protected value")
    void theImportedArtefactsPersistNoProtectedValue() {
        when(customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.empty());
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());
        when(cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(card(CARD, 1L)));
        ExportJob.writeExport(BUSINESS_DATE, customers, accounts, crossReferences, transactions,
                cards, objectStore, BUCKET, CLOCK);

        // WHY : Assumptions: the exported bytes come from this class's own recorder rather than from a
        //       second stub keyed by object name. The recorder is installed once for every case and
        //       asserts that exactly one object is published per run, so it proves the artefact count as
        //       well as supplying the bytes; a key literal would additionally have to track the key the
        //       job composes, and would fail as a null dereference rather than as a wrong key when it
        //       drifted.
        int reclen = ExportRecordMapper.recordLayout().reclen();
        byte[] dataset = stagedDataset().clone();
        overwriteViewField(dataset, 0, RecordType.CUSTOMER, "EXP-CUST-SSN", 123456789L);
        overwriteViewField(dataset, 0, RecordType.CUSTOMER, "EXP-CUST-GOVT-ISSUED-ID",
                "G0VT12345678");
        overwriteViewField(dataset, reclen, RecordType.CARD, "EXP-CARD-CVV-CD",
                new BigDecimal("987"));

        S3Client reader = mock(S3Client.class);
        Map<String, byte[]> outputs = recordBodies(reader);
        stubGet(reader, dataset);

        assertThat(ImportJob.separate(BUSINESS_DATE, reader, BUCKET))
                .isEqualTo(BatchReturnCode.CLEAN);

        byte[] customerArtefact = outputs.get("import/2022071800/customer.dat");
        byte[] cardArtefact = outputs.get("import/2022071800/card.dat");
        assertThat(field(cardArtefact, "CARD", "CARD-CVV-CD"))
                .as("the supplied verification value is NOT carried into the durable artefact")
                .isEqualTo("000");
        assertThat(field(customerArtefact, "CUSTOMER", "CUST-SSN"))
                .as("the supplied national identifier is not carried")
                .isEqualTo("000000000");
        assertThat(field(customerArtefact, "CUSTOMER", "CUST-GOVT-ISSUED-ID"))
                .as("the supplied government identifier is not carried, and blank-fills its span")
                .isBlank();

        // WHY : Assumptions: a redacted span must not shift any following field, because the artefact
        //       is fixed-width and every consumer reads it positionally. Asserting a neighbouring
        //       field's value is what proves the redaction replaced bytes rather than removing them.
        assertThat(field(cardArtefact, "CARD", "CARD-NUM")).isEqualTo(CARD);
        assertThat(cardArtefact)
                .hasSize(CopybookLayout.layout("CARD").reclen());
        assertThat(field(customerArtefact, "CUSTOMER", "CUST-ADDR-ZIP")).startsWith("10001");
        assertThat(customerArtefact)
                .hasSize(CopybookLayout.layout("CUSTOMER").reclen());
    }

    /**
     * A dataset whose body ends early despite a well-formed declared length is refused too.
     *
     * <p>Assumptions: the two refusal paths are separate cases because they catch different failures
     * and only one of them can be reached by the declared length. Here the declared length IS a whole
     * multiple of the record length -- so the up-front check passes -- and the body then ends mid
     * record, which is what a transfer cut short looks like from inside the read loop. A single case
     * over one input could not tell the two arms apart, and an untested arm here is the one that lets
     * a partial artefact through.</p>
     */
    @Test
    @DisplayName("a body that ends mid-record is refused even when its declared length is whole")
    void aBodyThatEndsMidRecordIsRefused() {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        byte[] shortBody = new byte[reclen + reclen / 2];

        S3Client reader = readerOf(shortBody);

        assertThatThrownBy(() -> ImportJob.separate(BUSINESS_DATE, reader, BUCKET))
                .isInstanceOf(IllegalStateException.class);
        // WHY : Assumptions: nothing published is asserted through the shared reader stub's own capture
        //       list rather than through a second map, because that stub is what every other case in
        //       this class publishes through -- one capture mechanism keeps "published" meaning one
        //       thing across the file.
        assertThat(this.publishedPayloads)
                .as("a body that ends mid-record publishes NOTHING, so no partition is half-written")
                .isEmpty();
    }

}
