package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.mockito.ArgumentMatchers.anyString;
import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.service.BatchStepLedger;
import java.util.function.Supplier;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.data.domain.Limit;

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

    private final DailyTransactionRepository feed = mock(DailyTransactionRepository.class);

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
     * The export job writes the three record types it owns, one object, keyed by business date.
     */
    @Test
    @DisplayName("the export job writes accounts, cross-references and transactions")
    void theExportJobWritesTheThreeOwnedRecordTypes() {
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));

        assertThat(ExportJob.writeExport(BUSINESS_DATE, accounts, crossReferences, transactions,
                objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

        assertThat(puts()).singleElement().satisfies(request ->
                assertThat(request.key()).isEqualTo("export/2022071800/export.dat"));
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
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(
                new CardXref("4111111111111111", 555L, 1L),
                new CardXref("4111111111111112", 555L, 1L),
                new CardXref("4111111111111113", 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        assertThat(ExportJob.writeExport(BUSINESS_DATE, accounts, crossReferences, transactions,
                objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

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
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L), account(2L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        ExportJob.writeExport(BUSINESS_DATE, accounts, crossReferences, transactions, objectStore,
                BUCKET, CLOCK);

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
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        assertThat(ExportJob.writeExport(BUSINESS_DATE, accounts, crossReferences, transactions,
                objectStore, BUCKET, CLOCK)).isEqualTo(BatchReturnCode.CLEAN);

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
        when(accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        when(crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(new CardXref(CARD, 555L, 1L)));
        when(transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transaction("0000000000000001")));

        // WHY : the dataset the import reads is produced by the export body rather than hand-built,
        //       so this case asserts the round trip the migration plan asks for: every image the
        //       exporter writes is one the importer recognises and decodes. A hand-built fixture
        //       would let the two drift apart and still pass.
        ExportJob.writeExport(BUSINESS_DATE, accounts, crossReferences, transactions, objectStore,
                BUCKET, CLOCK);
        ArgumentCaptor<RequestBody> exported = ArgumentCaptor.forClass(RequestBody.class);
        verify(objectStore).putObject(any(PutObjectRequest.class), exported.capture());
        byte[] dataset = exported.getValue().contentStreamProvider().newStream()
                .readAllBytes();

        S3Client reader = mock(S3Client.class);
        when(reader.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), dataset));

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
     * A dataset whose length is not a whole multiple of the record length reports the remainder as
     * an error rather than ignoring it.
     *
     * <p>Refactoring Rationale: this case previously asserted that the error output held the raw
     * remainder, so its expected length was the remainder's own length. That expectation belonged to an
     * earlier import body that copied unparsed images through to its outputs, and it is wrong against
     * the record layout: {@code app/cbl/CBIMPORT.cbl:106-109} declares the error record
     * {@code PIC X(132)} and {@code app/jcl/CBIMPORT.jcl:56-60} allocates {@code LRECL=132}, so writing
     * a 250-byte image into that artefact would produce a stream no positional consumer could parse.
     * The assertion now reads one 132-byte diagnostic record, which is what the layout admits.</p>
     */
    @Test
    @DisplayName("a truncated dataset routes its short remainder to the error output")
    void aTruncatedDatasetRoutesTheRemainderToTheErrorOutput() {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        byte[] truncated = new byte[reclen / 2];

        S3Client reader = mock(S3Client.class);
        when(reader.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), truncated));

        assertThat(ImportJob.separate(BUSINESS_DATE, reader, BUCKET))
                .isEqualTo(BatchReturnCode.CLEAN);

        ArgumentCaptor<PutObjectRequest> requests =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(reader, org.mockito.Mockito.atLeast(0))
                .putObject(requests.capture(), bodies.capture());

        int errorIndex = requests.getAllValues().stream()
                .map(PutObjectRequest::key)
                .toList()
                .indexOf("import/2022071800/error.dat");
        assertThat(errorIndex).isNotNegative();
        assertThat(bodies.getAllValues().get(errorIndex).optionalContentLength())
                .as("one diagnostic record at the 132-byte length the error artefact is declared with")
                .contains(132L);
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
        when(ledgerOfSteps.runStep(anyString(), anyString(), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(2).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        PreflightDailyTransactionsJob configuration =
                new PreflightDailyTransactionsJob(feed, crossReferences, accounts, ledgerOfSteps);
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
        verify(ledgerOfSteps).runStep(anyString(), anyString(), body.capture());
        return ledgerOfSteps.runStep("observed", PreflightDailyTransactionsJob.JOB_NAME,
                body.getValue()).returnCode();
    }
}
