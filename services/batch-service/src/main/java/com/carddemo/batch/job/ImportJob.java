package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig.LedgerGuardedStep;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Reads the fixed-width export dataset back and separates it by record type, re-expressing
 * {@code app/cbl/CBIMPORT.cbl}.
 *
 * <p>Outside the nightly chain: an operator-invoked job, driven in the baseline by
 * {@code app/jcl/CBIMPORT.jcl:22} ({@code EXEC PGM=CBIMPORT}, no {@code PARM}). The reference reads
 * {@code EXPFILE} and writes five sequential outputs -- {@code CUSTOUT}, {@code ACCTOUT},
 * {@code XREFOUT}, {@code TRNXOUT} and {@code ERROUT} -- dispatching on the record-type byte at
 * {@code 2200-PROCESS-RECORD-BY-TYPE} with arms for {@code 'C'}, {@code 'A'}, {@code 'X'},
 * {@code 'T'}, {@code 'D'} and {@code WHEN OTHER}.</p>
 *
 * <p>This job decodes and validates every record of every type, including the two types the sibling
 * export job cannot produce, because an import is a reader of a dataset and not of a database: it
 * needs no entity, no repository and no grant to verify a customer or card image and copy it to its
 * output. The round trip the migration plan asks for is therefore complete in this direction even
 * where the export direction is not.</p>
 *
 * <p><b>Documented divergence.</b> As with the export job, the baseline's
 * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} defect at {@code app/cbl/CBIMPORT.cbl:40} -- a key named
 * on the FD but defined only in working storage -- is not reproduced; the sequence number is a field
 * of the record that is read. Assumptions: card records are copied to no output because the reference
 * declares no card output dataset, so only an unrecognised type byte or an image that will not decode
 * reaches the error output; routing a valid card record to an error stream would report a defect that
 * does not exist.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ImportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ImportJob.class);

    /**
     * Key prefix the export dataset is read from; the sibling export job's write location.
     */
    private static final String EXPORT_KEY_PREFIX = "export/";

    /**
     * Member name of the export dataset within its business-date partition.
     */
    private static final String EXPORT_MEMBER = "/export.dat";

    /**
     * Key prefix the separated outputs are written under.
     */
    private static final String IMPORT_KEY_PREFIX = "import/";

    /**
     * Member name the unrecognised and undecodable images are written as, replacing
     * {@code ERROUT}.
     */
    private static final String ERROR_MEMBER = "/error.dat";

    /**
     * Content type recorded on every written output; binary, because the images carry packed
     * decimal that a text transcoding would corrupt.
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Assembles the import job.
     *
     * @param jobRepository the batch job repository; must not be {@code null}
     * @param transactionManager the transaction manager whose boundary the step runs inside; must
     *     not be {@code null}
     * @param steps the shared ledger-guarded step builder; must not be {@code null}
     * @param objectStore the object store the dataset is read from and the outputs written to; must
     *     not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @return the job, named exactly {@code import}; never {@code null}
     */
    @Bean
    public Job importDataset(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            LedgerGuardedStep steps,
            S3Client objectStore,
            @Value("${carddemo.dataset.bucket}") String bucket) {

        String name = BatchJobName.IMPORT.token();
        Step step = steps.build(name, jobRepository, transactionManager,
                businessDate -> separate(businessDate, objectStore, bucket));

        return new JobBuilder(name, jobRepository).start(step).build();
    }

    /**
     * Reads the export dataset and writes one output per record type plus an error output.
     *
     * @param businessDate the injected business date identifying the dataset partition; must not be
     *     {@code null}
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null}
     * @return always {@link BatchReturnCode#CLEAN}; the reference sets no return code, so an import
     *     that recorded errors still ends at zero and the error output is the report
     */
    static BatchReturnCode separate(
            BusinessDate businessDate, S3Client objectStore, String bucket) {

        String datePartition = businessDate.identifierPrefix();
        String sourceKey = EXPORT_KEY_PREFIX + datePartition + EXPORT_MEMBER;

        byte[] dataset = objectStore.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(sourceKey).build()).asByteArray();

        int reclen = ExportRecordMapper.recordLayout().reclen();
        Map<RecordType, ByteArrayOutputStream> separated = new EnumMap<>(RecordType.class);
        for (RecordType type : RecordType.values()) {
            separated.put(type, new ByteArrayOutputStream());
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        long read = 0L;
        long rejected = 0L;

        for (int offset = 0; offset < dataset.length; offset += reclen) {
            // WHY : a trailing image shorter than the record length is an error and not an
            //       end-of-data marker. The datasets these replace are RECFM=FB, so their length is
            //       always a whole multiple of the record length; a remainder means the dataset was
            //       truncated, and silently ignoring it would report a partial import as a complete
            //       one.
            if (offset + reclen > dataset.length) {
                rejected++;
                DatasetPayloadWriter.append(errors,
                        Arrays.copyOfRange(dataset, offset, dataset.length));
                LOG.warn("event=batch.import.short-record offset={} remaining={}",
                        offset, dataset.length - offset);
                break;
            }

            read++;
            byte[] image = Arrays.copyOfRange(dataset, offset, offset + reclen);

            RecordType type;
            try {
                type = ExportRecordMapper.recordTypeOf(image);
                // WHY : the image is decoded and the result discarded, which looks wasteful and is
                //       deliberate: decoding is how the image is VALIDATED, matching the reference's
                //       per-type paragraphs that move every field into a record area and would fail
                //       on a malformed one. Copying the image without decoding it would let a
                //       corrupt record through to an output that claims to hold verified records.
                ExportRecordMapper.decode(image);
            } catch (RuntimeException malformed) {
                rejected++;
                DatasetPayloadWriter.append(errors, image);
                // WHY : the exception message is logged and the image is not. An export image
                //       carries a primary account number and a national identifier, and a log line
                //       is readable by every holder of log access.
                LOG.warn("event=batch.import.undecodable offset={} reason={}",
                        offset, malformed.getClass().getSimpleName());
                continue;
            }

            DatasetPayloadWriter.append(separated.get(type), image);
        }

        writeOutputs(objectStore, bucket, datePartition, separated, errors);

        LOG.info("event=batch.import.completed sourceKey={} recordsRead={} recordsRejected={}",
                sourceKey, read, rejected);

        return BatchReturnCode.CLEAN;
    }

    /**
     * Writes each type's separated output and the error output.
     *
     * <p>WHY every output is written even when it is empty: the reference's output datasets are
     * allocated {@code DISP=(NEW,CATLG,DELETE)} by {@code app/jcl/CBIMPORT.jcl:33-56}, so they exist
     * after the step whether or not the input held that record type. A consumer distinguishing
     * "no records of this type" from "the import did not run" needs the empty object to exist.</p>
     *
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null}
     * @param datePartition the business-date partition segment; must not be {@code null}
     * @param separated the per-type outputs; must not be {@code null}
     * @param errors the error output; must not be {@code null}
     */
    private static void writeOutputs(
            S3Client objectStore,
            String bucket,
            String datePartition,
            Map<RecordType, ByteArrayOutputStream> separated,
            ByteArrayOutputStream errors) {

        separated.forEach((type, payload) -> put(objectStore, bucket,
                IMPORT_KEY_PREFIX + datePartition + "/"
                        + type.name().toLowerCase(java.util.Locale.ROOT) + ".dat",
                payload.toByteArray()));

        put(objectStore, bucket, IMPORT_KEY_PREFIX + datePartition + ERROR_MEMBER,
                errors.toByteArray());
    }

    /**
     * Puts one output object.
     *
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null}
     * @param key the object key; must not be {@code null}
     * @param payload the object body; must not be {@code null}
     */
    private static void put(S3Client objectStore, String bucket, String key, byte[] payload) {
        objectStore.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(payload));
        LOG.debug("event=batch.import.output-written key={} bytes={}", key, payload.length);
    }
}
