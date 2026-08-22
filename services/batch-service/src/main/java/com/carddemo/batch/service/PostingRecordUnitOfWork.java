package com.carddemo.batch.service;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.mapper.TransactionRejectRecordMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.PostingValidationService.PostingDecision;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies the posting decisions to ONE feed record and issues that record's durable writes.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this component is the migrated form of {@code 2000-POST-TRANSACTION} at
 * {@code app/cbl/CBTRN02C.cbl:424-444} together with the reject branch at
 * {@code app/cbl/CBTRN02C.cbl:446-465}. Given one record of the daily feed it validates it and then
 * either posts it -- category balance, account master, posted ledger -- or records it as rejected,
 * and in both cases advances the feed's consumed position to that record. It holds the ORDER those
 * writes are issued in and the pairing between them and the checkpoint; it holds no business rule of
 * its own beyond the account accumulation transcribed below, because every other rule is delegated
 * to the type that owns it.</p>
 *
 * <p>Refactoring Rationale: this class exists because these decisions were private to
 * {@code com.carddemo.batch.job.PostTransactionsJob} and therefore unreachable by any test that
 * wanted to drive the real thing against a real database. The consequence was measured rather than
 * feared: the repository tests that claim to prove posting atomicity re-implemented the write
 * sequence in their own private helpers, so an ordering change, a cycle-bucket change, a sign-test
 * change or a missing checkpoint in production changed nothing about whether they passed -- and one
 * of them omitted the checkpoint write entirely, which is the write that decides whether a redriven
 * night double-posts. Extracting the unit as an injectable component is what lets those tests drive
 * production code instead of a copy of it. Nothing about the transaction boundary moves with it: the
 * job still opens it, for the reason recorded below.</p>
 *
 * <h2>Who owns the transaction, and why it is not this class</h2>
 *
 * <p>Assumptions: this class opens NO transaction and carries no transaction annotation, in common
 * with every other type in this package. {@link #applyOneRecord} must be called inside a transaction
 * its caller has already begun -- {@code PostTransactionsJob} opens one per record through a
 * {@code TransactionTemplate} -- and the package charter fixes that rule for the whole directory so
 * the boundary has exactly one owner. A boundary opened here would split the record's writes from
 * the validation reads that informed them, and it would do so invisibly: the account's
 * {@code @Version} check would still pass, having been made against a row nobody held.</p>
 *
 * <p>Assumptions: the reject record is RETURNED rather than appended, and that is a consequence of
 * the same ownership. The 430-byte stream record belongs in a file, the file append is not
 * transactional and cannot be made so, so the caller appends it only after the transaction commits.
 * A reject appended from inside the transaction would appear in the dataset the golden masters
 * compare even when its row rolled back.</p>
 *
 * <h2>What a caller must not re-derive</h2>
 *
 * <p>Assumptions: the reject reason acted on here is whatever {@code PostingValidationService}
 * returned, and it carries AT MOST ONE reason even for a record that fails two conditions. The
 * reference's over-limit test at {@code app/cbl/CBTRN02C.cbl:407} and expiration test at
 * {@code app/cbl/CBTRN02C.cbl:414} are sequential and unguarded, both writing the same field, so a
 * record that is both over limit and past expiry is reported as {@code 103} and its {@code 102} is
 * overwritten. That precedence is an artefact of STATEMENT ORDER rather than a designed rule, which
 * is exactly why it is not re-derived here: {@code PostingValidationResult} owns the resolving
 * factory that encodes it, and a second copy is one that no test fails when the first one
 * changes.</p>
 *
 * <p>Assumptions: reason {@code 109} can never appear on this path. It is the dead write the posting
 * job's divergence note describes, assigned only inside the reference's account rewrite and never
 * reachable by its reject writer, and {@code RejectReason.isPersistedToRejectStream()} reports
 * {@code false} for it alone.</p>
 *
 * @see PostingValidationService for the reject predicates this unit acts on the outcome of
 * @see CategoryBalanceService for the create-versus-update arms this unit drives
 * @see DailyFeedWatermarkService for the consumed position this unit checkpoints
 */
// WHY : Alternatives Considered: leaving the unit inside the job and making its methods package
//       private, so a test in the job package could drive them. Rejected on two counts. It would
//       put the atomicity and ordering proofs in the job package, where the tests are context-free
//       unit tests over mocks and there is no database for a commit to be observed against -- the
//       repository package is where an engine-backed proof belongs. And package-private visibility
//       widened for a test is a visibility decided by a test rather than by the design, which the
//       next reader cannot distinguish from an ordinary collaborator boundary.
// WHY : Alternatives Considered: naming this class PostingService, matching the sibling rule
//       classes. Rejected because it holds no rule and the name would place it in the closed roster
//       of four baseline rule transcriptions that this package's charter maintains. The name states
//       what it is -- the per-record unit of work -- so a reader looking for "which class decides
//       whether a category balance is created" is not sent here.
// WHY : Alternatives Considered: a @Service stereotype, as all eight sibling classes in this package
//       carry, so that component scanning would register it. Rejected because the only context that
//       needs it is the one that assembles the posting step, and not every such context scans this
//       package: the job-registration census and the posting parity harness each build a narrow
//       context from explicitly contributed beans. A factory method on
//       com.carddemo.batch.job.PostTransactionsJob -- the one configuration that consumes it --
//       registers exactly one definition and registers it identically in all three, where a
//       stereotype would register it in the scanned context only and leave the other two resolving a
//       collaborator that does not exist. Trade-offs: the inconsistency with the siblings is real and
//       is the reason this comment exists; what it buys is that the unit's wiring is stated in the
//       file that depends on it rather than inferred from a scan.
public class PostingRecordUnitOfWork {

    /** The account master the posted amounts are accumulated into. */
    private final AccountRepository accounts;

    /** The ledger the posted transactions are written to. */
    private final TransactionRepository ledger;

    /** The decomposed rows of the reject stream. */
    private final TransactionRejectRepository rejects;

    /** The rule deciding which conditions a record fails. */
    private final PostingValidationService validation;

    /** The rule deciding whether a category balance is created or updated. */
    private final CategoryBalanceService categoryBalances;

    /** The stored position that makes a pass consume its own input and not every night's. */
    private final DailyFeedWatermarkService watermark;

    /** The clock the posted records' processing stamp is read from. */
    private final Clock clock;

    /**
     * Builds the unit of work over the rules and repositories one record's writes go through.
     *
     * @param accounts the account master the posted amounts are accumulated into; must not be
     *     {@code null}
     * @param ledger the posted-transaction ledger; must not be {@code null}
     * @param rejects the reject stream's decomposed rows; must not be {@code null}
     * @param validation the posting validation rule; must not be {@code null}
     * @param categoryBalances the category-balance rule; must not be {@code null}
     * @param watermark the feed's consumed position, advanced once per record accounted for; must
     *     not be {@code null}
     * @param clock the clock the processing stamp is read from; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PostingRecordUnitOfWork(AccountRepository accounts, TransactionRepository ledger,
            TransactionRejectRepository rejects, PostingValidationService validation,
            CategoryBalanceService categoryBalances, DailyFeedWatermarkService watermark,
            Clock clock) {

        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.rejects = Objects.requireNonNull(rejects, "rejects must not be null");
        this.validation = Objects.requireNonNull(validation, "validation must not be null");
        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null");
        this.watermark = Objects.requireNonNull(watermark, "watermark must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Applies the decisions to one feed record inside the transaction the caller has opened.
     *
     * <p>Assumptions: the return value carries the reject record rather than a boolean, because the
     * caller must append those bytes only after the transaction commits and therefore needs them
     * back out of it. An empty result means the record posted.</p>
     *
     * <p>Assumptions: the feed's consumed position is advanced HERE, inside the record's own
     * transaction, so the checkpoint and the writes it accounts for commit or roll back as one. Both
     * arms advance it: a rejected record is consumed as surely as a posted one, and leaving it behind
     * would make a retry re-reject it and write its reject row a second time.</p>
     *
     * @param feedRecord the feed record to post or reject; must not be {@code null}
     * @param runId the orchestrator execution recorded against the advanced watermark; must not be
     *     {@code null}
     * @param generationDate the injected date recorded against the advanced watermark; must not be
     *     {@code null}
     * @return the 430-byte reject record when the record was rejected, empty when it posted, never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the validation rule accepted a record without resolving both
     *     the cross-reference and the account
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, because the watermark's locking read requires one
     */
    public Optional<byte[]> applyOneRecord(DailyTransaction feedRecord, String runId,
            BusinessDate generationDate) {

        Objects.requireNonNull(feedRecord, "feedRecord must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(generationDate, "generationDate must not be null");

        // WHY : Refactoring Rationale: this method performed its own card read, its own account read
        //       and its own copy of the app/cbl/CBTRN02C.cbl:372 guard between them, then handed both
        //       records to a validation overload that only decided the outcome. The validation
        //       service transcribes :370 including that guard and asserts it under test, so the guard
        //       existed in two places -- one asserted, one executed -- and a rule the reference
        //       expresses only as a MISSING statement is the last one that should be duplicated,
        //       because neither copy fails when the other changes. The resolving entry point now
        //       returns what its reads found, so this method consumes them instead of repeating them
        //       and each record is still read exactly once per feed row.
        PostingDecision decision = this.validation.validate(feedRecord);
        PostingValidationResult outcome = decision.outcome();

        if (outcome.isRejected()) {
            byte[] rejectRecord = recordReject(feedRecord, outcome);
            checkpointConsumed(feedRecord, runId, generationDate);
            return Optional.of(rejectRecord);
        }

        // WHY : Assumptions: both values are present on the accepted path by construction -- the
        //       validation rule cannot return accepted without having read an account, which it can
        //       only do through a resolved cross-reference -- so unwrapping here is an assertion of
        //       that invariant rather than an unchecked assumption. If it were ever violated the
        //       failure would be immediate and named, which is what the step should do with a broken
        //       invariant.
        CardXref resolved = decision.crossReference().orElseThrow(() -> new IllegalStateException(
                "validation accepted a record whose card resolved to no cross-reference"));
        Account posting = decision.account().orElseThrow(() -> new IllegalStateException(
                "validation accepted a record whose cross-reference resolved to no account"));

        // WHY : Assumptions: the ORDER is category balance, then account, then ledger, transcribing
        //       app/cbl/CBTRN02C.cbl:440, :441 and :442 in sequence. It is preserved even though a
        //       single commit makes the order invisible to any reader of the committed state, for two
        //       reasons that outlive the current implementation. Within one transaction the order
        //       still fixes the sequence in which row locks are taken, so every posting task in the
        //       fleet taking them in one order is what keeps two concurrent tasks from deadlocking on
        //       the same pair; and the order is the property PostingUnitOfWorkIT and
        //       AccountRepositoryIT pin, so a transcription that reordered it would be a silent
        //       divergence from the paragraph this method claims to reproduce.
        // WHY : Alternatives Considered: reordering to write the ledger first, which reads as more
        //       natural because the transaction is the thing being posted. Rejected because it buys
        //       nothing and costs the two properties above.
        // WHY : Assumptions: NOT-FOUND IS NORMAL on the category-balance read, and this call site
        //       deliberately carries no absent-row check because of it.
        //       app/cbl/CBTRN02C.cbl:481 accepts file status '00' OR '23' as success, and '23' is
        //       record-not-found -- so a missing category row is the ordinary
        //       first-transaction-of-a-category case rather than an error, and the reference responds
        //       by CREATING the row at :495-496 instead of failing. A guard here that treated an
        //       empty result as a failure would reject the first transaction in every new category,
        //       which is both a parity break and a business-visible one. The service owns the
        //       decision and the two arms; what this comment records is the caller's obligation not
        //       to second-guess it.
        this.categoryBalances.accumulatePostedTransaction(feedRecord, resolved);
        applyToAccount(feedRecord, posting);
        postToLedger(feedRecord);
        // WHY : Assumptions: the checkpoint is written AFTER the three writes and never before, so a
        //       failure in any of them leaves the position where it was and the record is re-presented
        //       whole. Ordering it first would mark a record consumed that the transaction then rolled
        //       back, which is the one direction of this pairing that loses a transaction silently.
        checkpointConsumed(feedRecord, runId, generationDate);
        return Optional.empty();
    }

    /**
     * Advances the feed's consumed position to one record, inside that record's own transaction.
     *
     * <p>Assumptions: the ordinal recorded is the record's own ingestion sequence rather than a
     * running counter, because the walk is keyset-paginated over exactly that column -- so the value
     * stored is the same value the next pass compares against, with nothing to keep in step.</p>
     *
     * <p>Assumptions: the run identifier and the injected date are carried verbatim into the
     * watermark row's attribution fields. They name WHICH RUN'S NIGHT moved the position, for an
     * operator reading the row afterwards, and reach no posted or rejected record.</p>
     *
     * @param feedRecord the record just accounted for; must not be {@code null}
     * @param runId the orchestrator execution to attribute the advance to; must not be {@code null}
     * @param generationDate the injected date to attribute the advance to; must not be {@code null}
     */
    private void checkpointConsumed(DailyTransaction feedRecord, String runId,
            BusinessDate generationDate) {

        this.watermark.recordConsumedThrough(DailyFeedWatermarkService.DAILY_TRANSACTION_FEED,
                feedRecord.getIngestSeq(), runId, generationDate.token());
    }

    /**
     * Records one rejected feed record, both as a 430-byte stream record and as a decomposed row.
     *
     * <p>Assumptions: the stream record's first 350 bytes are the feed record's own image copied
     * VERBATIM, because {@code app/cbl/CBTRN02C.cbl:447} moves {@code DALYTRAN-RECORD} into
     * {@code REJECT-TRAN-DATA} as a GROUP move rather than field by field -- so no value is re-encoded
     * on the way across -- and the trailer at {@code app/cbl/CBTRN02C.cbl:180-182} is a four-digit
     * zero-padded reason followed by its 76-character space-padded description. The composition is
     * delegated so the offsets live in one place.</p>
     *
     * <p>Assumptions: the two writes are not redundant. The 430-byte record is the dataset the golden
     * masters compare byte for byte; the decomposed row is what makes a reject queryable. The
     * repository is append-only by design, which is why the pass's reject tally is the walk's own
     * counter and never a query against it.</p>
     *
     * <p>Assumptions: this method saves the row and RETURNS the record, because the two writes belong
     * on opposite sides of a commit: the row is transactional and the append is not, and appending
     * inside the transaction would leave a reject in the compared dataset whose row had rolled back.
     * The caller owns the append for that reason, and this method is left owning exactly the writes
     * the transaction can guarantee.</p>
     *
     * @param feedRecord the rejected feed record; must not be {@code null}
     * @param outcome the validation outcome carrying the single reason; must not be {@code null}
     * @return the 430-byte stream record, for the caller to append after this transaction commits,
     *     never {@code null}
     */
    private byte[] recordReject(DailyTransaction feedRecord, PostingValidationResult outcome) {
        byte[] sourceImage = DailyTransactionMapper.toRecord(feedRecord);
        this.rejects.save(TransactionRejectRecordMapper.toRejectRow(sourceImage, outcome));
        return TransactionRejectRecordMapper.toRecord(sourceImage, outcome);
    }

    /**
     * Accumulates the record's amount into the account's balance and its cycle totals.
     *
     * <p>Assumptions: the sign test is {@code >= 0} and a NEGATIVE amount is ADDED to the debit total
     * rather than subtracted from it, exactly as {@code app/cbl/CBTRN02C.cbl:547-552} does --
     * {@code :547} adds the amount to the balance, {@code :548} tests it against zero, {@code :549}
     * adds it to the cycle credit and {@code :551} adds it, still signed, to the cycle debit. Two
     * consequences look like defects and are not, so neither may be "cleaned up". The debit total
     * accumulates NEGATIVELY; and a ZERO amount routes to CREDIT, because the test is
     * {@code >= 0} rather than {@code > 0}. Both are internally consistent with the over-limit
     * projection at {@code app/cbl/CBTRN02C.cbl:403-405}, which computes cycle credit MINUS cycle
     * debit, so subtracting a magnitude there while accumulating a magnitude here would place the
     * projection on the opposite side of the credit limit and move the boundary the golden masters
     * pin. Reproducing the sign convention is what keeps the boundary where the reference puts it.</p>
     *
     * <p>Assumptions: an optimistic-lock loss on this write FAILS THE STEP. The account carries a
     * {@code @Version} column and this method does not catch the resulting exception, so it
     * propagates, THIS RECORD's transaction rolls back and the orchestrator's per-state {@code Retry}
     * re-runs the step from a consistent starting point. Records committed before it stay committed,
     * which is what the durable step ledger's resume promise is written against.</p>
     *
     * <p>Alternatives Considered: re-reading the account and reapplying the amount inside the step.
     * Rejected because the only writer that can win that race is a concurrent ONLINE update, and
     * silently reapplying on top of it would hide a change a human made and could double-apply this
     * amount -- the balance would be wrong and nothing would say so. Failing surfaces the collision
     * to the state machine, which is the component able to re-run the step. The online path's
     * {@code 409 Conflict} is the interactive analogue of the same ruling.</p>
     *
     * @param feedRecord the record being posted; must not be {@code null}
     * @param posting the account the amount is accumulated into; must not be {@code null}
     */
    private void applyToAccount(DailyTransaction feedRecord, Account posting) {
        // WHY : Assumptions: every step of the accumulation goes through Money, which fixes scale two
        //       and HALF_UP rounding. The amounts originate as zoned decimal with a sign overpunch and
        //       land in NUMERIC(p,2) columns, so a binary floating-point intermediate would introduce
        //       a representation error that no rounding at the end can undo. The prohibition is
        //       enforced by the ArchUnit money rule rather than left to review.
        BigDecimal amount = feedRecord.getAmount();
        posting.setCurrBal(Money.of(posting.getCurrBal()).plus(Money.of(amount)).amount());

        if (amount.signum() >= 0) {
            posting.setCurrCycCredit(
                    Money.of(posting.getCurrCycCredit()).plus(Money.of(amount)).amount());
        } else {
            posting.setCurrCycDebit(
                    Money.of(posting.getCurrCycDebit()).plus(Money.of(amount)).amount());
        }

        this.accounts.save(posting);
    }

    /**
     * Writes the record to the posted ledger under this run's processing stamp.
     *
     * <p>Assumptions: the two timestamps on a posted record are handled DIFFERENTLY and conflating
     * them fails every posting golden. The ORIGINATING stamp is a verbatim 26-byte passthrough --
     * {@code app/cbl/CBTRN02C.cbl:436} moves {@code DALYTRAN-ORIG-TS} into {@code TRAN-ORIG-TS} with
     * no reformatting, and the committed golden
     * {@code tests/golden/posting/happy_path/tranfile.expected} carries
     * {@code 2022-06-10 19:27:53.000000} at offset 278, space-separated and colon-delimited, straight
     * from the feed. The PROCESSING stamp is generated: {@code app/cbl/CBTRN02C.cbl:437-438} builds it
     * and moves it in. This method therefore supplies only the processing instant and copies nothing,
     * because {@code DailyTransactionMapper} carries the originating value across untouched.</p>
     *
     * <p>Trade-offs: the originating stamp is passed through as an opaque value rather than parsed and
     * re-rendered into a canonical form, which forgoes the chance to normalise a malformed feed value
     * here. That is the right trade because {@code tests/helpers/golden_compare.py:22-28} documents the
     * record-mode comparison as byte-exact with the originating stamp preserved VERBATIM and only the
     * run-generated processing stamp masked in place; no ISO-timestamp regex runs and trailing
     * whitespace is never stripped. Any re-rendering, however cosmetic, would fail all nine posting
     * golden trees.</p>
     *
     * <p>Assumptions: this method establishes the processing INSTANT and deliberately not its byte
     * form. The reference renders that field as {@code YYYY-MM-DD-HH.MM.SS.mm0000} -- the layout
     * declared at {@code app/cbl/CBTRN02C.cbl:159-175} and assembled at
     * {@code app/cbl/CBTRN02C.cbl:692-705}, with a DASH between day and hour, dots inside the time,
     * two digits of hundredths and a literal {@code 0000}. That is NOT the shape
     * {@code TimestampFormatter.format} emits, which is the space-and-colon target form the migration
     * plan's section 0.5.1.1 describes; the COBOL is authoritative for this field and the two patterns
     * must never be normalised into each other. The distinction is real but is not this file's to
     * resolve: the entity stores a {@code LocalDateTime}, so the byte rendering belongs to
     * {@code TransactionRecordMapper}, which records the same finding independently. What is used here
     * is {@code TimestampFormatter.normalizeNow}, for the instant only.</p>
     *
     * @param feedRecord the record being posted; must not be {@code null}
     */
    private void postToLedger(DailyTransaction feedRecord) {
        // WHY : Assumptions: the stamp is read ONCE, here, and handed to the mapper as an argument.
        //       The mapper refuses a null instant rather than defaulting, precisely so that it never
        //       reaches for a clock of its own; a second read inside it could return a later instant
        //       than the one this unit of work believes it posted at. normalizeNow is preferred over a
        //       raw clock read because it truncates to whole microseconds, which is the resolution the
        //       26-character target form round-trips, so the persisted value and its rendering cannot
        //       disagree in a digit the column can hold but the rendering cannot show.
        // WHY : Trade-offs: this is a genuine clock read, and it is the one place the business path is
        //       permitted one, because the field it feeds is by definition the moment of posting --
        //       app/cbl/CBTRN02C.cbl:693 reads FUNCTION CURRENT-DATE for exactly this. The business
        //       date is NOT substituted for it, even though that would make the field deterministic,
        //       because it would post a stamp the reference never writes. Determinism is recovered at
        //       comparison time instead: the golden comparator masks this field in place, and the
        //       clock is injected so a test can pin it.
        LocalDateTime postedAt = TimestampFormatter.normalizeNow(this.clock);
        Transaction posted = DailyTransactionMapper.toPostedTransaction(feedRecord, postedAt);
        this.ledger.save(posted);
    }
}
