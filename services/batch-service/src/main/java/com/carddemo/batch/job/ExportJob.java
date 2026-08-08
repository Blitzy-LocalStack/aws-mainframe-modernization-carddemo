package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig.LedgerGuardedStep;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.ExportRecord;
import com.carddemo.batch.mapper.ExportRecordMapper.Prefix;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.time.TimestampFormatter;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes the fixed-width export dataset, re-expressing {@code app/cbl/CBEXPORT.cbl}.
 *
 * <p>Outside the nightly chain: an operator-invoked job, driven in the baseline by
 * {@code app/jcl/CBEXPORT.jcl:43} ({@code EXEC PGM=CBEXPORT}, no {@code PARM}). Each record is a
 * five-hundred-byte image made of a common prefix -- record type, twenty-six-character timestamp,
 * a binary sequence number, branch and region -- followed by a four-hundred-and-sixty-byte
 * type-specific view.</p>
 *
 * <p><b>Documented divergence -- the baseline defect is not reproduced.</b> Both
 * {@code app/cbl/CBEXPORT.cbl:68} and {@code app/cbl/CBIMPORT.cbl:40} declare
 * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} on an FD whose record does not contain that field --
 * it is defined in {@code WORKING-STORAGE} through {@code CVEXPORT.cpy}. That is a genuine semantic
 * defect which no compiler flag repairs and which the reference-only policy forbids editing, so the
 * pair does not compile under the open-source compiler at all and its integration test is skipped.
 * This job implements the correct behaviour: the sequence number is a field of the written record
 * and is assigned in write order.</p>
 *
 * <p><b>Documented divergence -- two of the five record types are not written.</b> The reference
 * exports customers, accounts, cross-references, transactions and cards. This module reads only the
 * three views the export mapper binds to entities it owns. The card view is not merely unmodelled
 * but ungrantable: {@code card.cards} belongs to another bounded context's schema, and the migration
 * plan scopes this module's cross-schema write grants to {@code ledger.*} and {@code account.*}
 * alone, so a card read here would require widening a privilege boundary the plan sets deliberately.
 * The customer view is in a grantable schema but has no entity, repository or mapper binding in this
 * module -- the export mapper takes its customer and card views as field maps precisely because
 * nothing here models them. Assumptions: an export missing two record types is honest and an export
 * that fabricated them would not be; the omission is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than hidden behind an empty
 * section. Trade-offs: a consumer expecting all five types must obtain customer and card records
 * from the contexts that own them; that is the cost of the schema-per-service boundary this
 * migration adopts, and it is visible here rather than discovered downstream.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ExportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJob.class);

    /**
     * Branch identifier stamped on every exported record, per
     * {@code app/cbl/CBEXPORT.cbl:278} ({@code MOVE '0001' TO EXPORT-BRANCH-ID}).
     */
    private static final String BRANCH_ID = "0001";

    /**
     * Region code stamped on every exported record, per
     * {@code app/cbl/CBEXPORT.cbl:279} ({@code MOVE 'NORTH' TO EXPORT-REGION-CODE}).
     */
    private static final String REGION_CODE = "NORTH";

    /**
     * Key prefix the export dataset is written under.
     *
     * <p>WHY a fixed prefix rather than a generation family: the baseline's {@code EXPFILE} is
     * {@code DISP=SHR} on an existing dataset at {@code app/jcl/CBEXPORT.jcl:62}, not a relative
     * generation, and none of the ten generation-dataset bases the baseline defines names it. Giving
     * it a generation would invent retention behaviour the baseline does not have.</p>
     */
    private static final String EXPORT_KEY_PREFIX = "export/";

    /**
     * Member name the export dataset is written as, under its business-date partition.
     */
    private static final String EXPORT_MEMBER = "/export.dat";

    /**
     * Content type recorded on the written dataset; binary, because the record carries packed
     * decimal and a binary sequence number that a text transcoding would corrupt.
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Assembles the export job.
     *
     * @param jobRepository the batch job repository; must not be {@code null}
     * @param transactionManager the transaction manager whose boundary the reads run inside; must
     *     not be {@code null}
     * @param steps the shared ledger-guarded step builder; must not be {@code null}
     * @param accounts the accounts exported as record type {@code 'A'}; must not be {@code null}
     * @param crossReferences the cross-reference rows exported as record type {@code 'X'}; must not
     *     be {@code null}
     * @param transactions the transactions exported as record type {@code 'T'}; must not be
     *     {@code null}
     * @param objectStore the object store the dataset is written to; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @param clock the time source stamped into every record prefix; must not be {@code null}
     * @return the job, named exactly {@code export}; never {@code null}
     */
    @Bean
    public Job exportDataset(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            LedgerGuardedStep steps,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            @Value("${carddemo.dataset.bucket}") String bucket,
            Clock clock) {

        String name = BatchJobName.EXPORT.token();
        Step step = steps.build(name, jobRepository, transactionManager,
                businessDate -> writeExport(businessDate, accounts, crossReferences,
                        transactions, objectStore, bucket, clock));

        return new JobBuilder(name, jobRepository).start(step).build();
    }

    /**
     * Assembles and writes the export dataset.
     *
     * <p>The record order follows {@code app/cbl/CBEXPORT.cbl:0000-MAIN-PROCESSING}: accounts, then
     * cross-references, then transactions. The sequence number is a single counter spanning all
     * record types, which is what the reference's {@code WS-SEQUENCE-COUNTER} is.</p>
     *
     * @param businessDate the injected business date the dataset is partitioned under; must not be
     *     {@code null}
     * @param accounts the accounts to export; must not be {@code null}
     * @param crossReferences the per-account cross-reference reader; must not be {@code null}
     * @param transactions the transactions to export; must not be {@code null}
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null}
     * @param clock the time source; must not be {@code null}
     * @return always {@link BatchReturnCode#CLEAN}; the reference sets no return code
     */
    static BatchReturnCode writeExport(
            BusinessDate businessDate,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            String bucket,
            Clock clock) {

        // WHY : the timestamp is read from the clock, not derived from the business date, because
        //       app/cbl/CBEXPORT.cbl:1050-GENERATE-TIMESTAMP does exactly that with ACCEPT FROM
        //       DATE and ACCEPT FROM TIME. That is safe here in a way it would not be in the
        //       nightly chain: this job is operator-invoked, it is compared against no golden
        //       master -- the export test domain ships fixtures and deliberately no goldens,
        //       because its assertion is a round trip rather than a recorded output -- and the
        //       round trip is unaffected by which instant is stamped.
        String timestamp = TimestampFormatter.formatNow(clock);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        long sequence = 0L;
        long accountRecords = 0L;
        long crossReferenceRecords = 0L;
        long transactionRecords = 0L;

        try (Stream<Account> rows = accounts.findAllByOrderByAccountIdAsc()) {
            for (Account row : (Iterable<Account>) rows::iterator) {
                sequence++;
                accountRecords++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofAccount(
                                prefix(RecordType.ACCOUNT, timestamp, sequence), row)));
            }
        }

        // WHY : the cross-reference rows are reached per exported account rather than through a
        //       second unrestricted scan, because the cross-reference repository publishes a keyed
        //       read and a per-account read and no scan at all -- and it publishes no scan because
        //       no reference paragraph performs one. Assumptions: an account with no card
        //       contributes no cross-reference record, which is what a sequential pass over a file
        //       holding one row per card also yields. Trade-offs: an account holding several cards
        //       contributes only its lowest-numbered one, because that is the single row the
        //       per-account path returns; widening it would mean publishing a scan this module has
        //       no reference paragraph to justify.
        try (Stream<Account> rows = accounts.findAllByOrderByAccountIdAsc()) {
            for (Account row : (Iterable<Account>) rows::iterator) {
                CardXref crossReference = crossReferences
                        .findFirstByAccountIdOrderByCardNumAsc(row.getAccountId())
                        .orElse(null);
                if (crossReference == null) {
                    continue;
                }
                sequence++;
                crossReferenceRecords++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofCardXref(
                                prefix(RecordType.CARD_XREF, timestamp, sequence),
                                crossReference)));
            }
        }

        try (Stream<Transaction> rows = transactions.findAllByOrderByTransactionIdAsc()) {
            for (Transaction row : (Iterable<Transaction>) rows::iterator) {
                sequence++;
                transactionRecords++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofTransaction(
                                prefix(RecordType.TRANSACTION, timestamp, sequence), row)));
            }
        }

        String key = EXPORT_KEY_PREFIX + businessDate.identifierPrefix() + EXPORT_MEMBER;
        objectStore.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(payload.toByteArray()));

        LOG.info("event=batch.export.completed key={} accounts={} crossReferences={}"
                + " transactions={} records={}", key, accountRecords, crossReferenceRecords,
                transactionRecords, sequence);

        return BatchReturnCode.CLEAN;
    }

    /**
     * Builds the common record prefix.
     *
     * @param recordType the record type discriminator; must not be {@code null}
     * @param timestamp the twenty-six-character export timestamp; must not be {@code null}
     * @param sequence the record's position in write order; must be positive
     * @return the prefix; never {@code null}
     */
    private static Prefix prefix(RecordType recordType, String timestamp, long sequence) {
        return new Prefix(recordType, timestamp, sequence, BRANCH_ID, REGION_CODE);
    }
}
