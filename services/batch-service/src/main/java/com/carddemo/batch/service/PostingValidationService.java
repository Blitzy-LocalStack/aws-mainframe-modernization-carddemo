package com.carddemo.batch.service;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Decides which of the four posting conditions one daily transaction fails, transcribed from
 * {@code app/cbl/CBTRN02C.cbl:370-422}.
 *
 * <h2>Purpose</h2>
 *
 * <p>Three paragraphs of the reference program carry the posting validation and this class
 * transcribes all three. {@code 1500-VALIDATE-TRAN} at {@code :370} is the entry point,
 * {@code 1500-A-LOOKUP-XREF} at {@code :380} resolves the card cross-reference, and
 * {@code 1500-B-LOOKUP-ACCT} at {@code :393} resolves the account and applies the credit-limit and
 * expiration tests. This class reports WHICH conditions a transaction failed. Which of those
 * failures is REPORTED is decided by
 * {@link PostingValidationResult#resolve(boolean, boolean, boolean, boolean, BigDecimal)}, so the
 * precedence cannot be inverted here.</p>
 *
 * <h2>The precedence is MIXED, and this class never decides it</h2>
 *
 * <p>The reference does not apply one uniform rule across the four reasons, and the mixture is part
 * of the contract:</p>
 *
 * <ul>
 *   <li><b>First-reason-wins across the two lookups.</b> {@code :372} guards the account lookup at
 *       {@code :373} with {@code IF WS-VALIDATION-FAIL-REASON = 0}, so an unresolved card excludes
 *       the account reason; and the account read's {@code INVALID KEY} branch at {@code :396} is
 *       mutually exclusive with the {@code NOT INVALID KEY} branch at {@code :400} that encloses
 *       both boundary tests, so an absent account excludes both boundary reasons.</li>
 *   <li><b>Last-writer-wins across the two boundaries.</b> The credit-limit block at {@code :407}
 *       and the expiration block at {@code :414} are two SEQUENTIAL, UNGUARDED {@code IF} blocks:
 *       {@code :413} closes the first and {@code :414} opens the second with no test of the reason
 *       between them. A transaction failing BOTH therefore has the assignment at {@code :417}
 *       overwrite the assignment at {@code :410}, and the reported reason is the expiration one, not
 *       the credit-limit one.</li>
 * </ul>
 *
 * <p>Because those two rules differ, this class hands BOTH boundary findings to the result type
 * rather than selecting between them. Selecting here would mean restating a rule the reference
 * expresses only as a missing guard, and a rule restated in a second place is a rule that can
 * disagree with the first.</p>
 *
 * <h2>Both boundaries are INCLUSIVE on the passing side, so each reject test is STRICT</h2>
 *
 * <p>Each boundary is written in the reference as a PASS guard using {@code &gt;=}, so each finding
 * this class reports is the strict complement of a guard rather than a second comparison written by
 * hand:</p>
 *
 * <ul>
 *   <li>{@code :407} reads {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL} and PASSES on that
 *       comparison, so a projection landing exactly ON the limit posts and the credit-limit finding
 *       holds only when the projection is STRICTLY GREATER than the limit.</li>
 *   <li>{@code :414} reads {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)} and PASSES
 *       on that comparison, so a transaction dated exactly ON the expiration date posts and the
 *       expiration finding holds only when the expiration date is STRICTLY EARLIER than the
 *       transaction's originating date. The field name is misspelled in the reference, at
 *       {@code app/cpy/CVACT01Y.cpy:11}, and is cited here as it is spelled there because that
 *       spelling is the contract.</li>
 * </ul>
 *
 * <p>Assumptions: both senses are corroborated by the functional-parity oracle independently of the
 * source. {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected} and
 * {@code tests/golden/posting/boundary_expiry_equal/dalyrejs.expected} are each EMPTY with a
 * companion return code of zero, which is consistent only with the inclusive reading of both
 * guards; were either boundary exclusive, each would instead hold one 430-character reject record,
 * the width {@code app/jcl/POSTTRAN.jcl:36} allocates as {@code LRECL=430}.</p>
 *
 * <h2>The projection comes from the CYCLE accumulators</h2>
 *
 * <p>Assumptions: {@code :403-405} forms the quantity the credit-limit guard compares as
 * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, and {@code ACCT-CURR-BAL} takes
 * no part in it. The account record declares all three fields, at {@code app/cpy/CVACT01Y.cpy:7},
 * {@code :13} and {@code :14}, so substituting the account's own balance compiles perfectly well and
 * yields credit-limit decisions that look entirely reasonable while being wrong on every account
 * whose cycle totals differ from its balance.</p>
 *
 * <h2>Reason 109 is never produced here</h2>
 *
 * <p>Assumptions: the reference assigns reason 109 at {@code :556}, in the {@code INVALID KEY} branch
 * of the account {@code REWRITE} inside {@code 2800-UPDATE-ACCOUNT-REC}. That paragraph is reached
 * only from {@code :441}, inside {@code 2000-POST-TRANSACTION}, which {@code :212} enters only when
 * validation assigned no reason at all. It therefore describes a transaction that PASSED validation
 * and then could not be recorded, which is not a validation outcome in any sense. The reasons this
 * class can produce are exactly the four the reject stream carries, and
 * {@code com.carddemo.batch.domain.TransactionReject} independently constrains the persisted domain
 * to those four.</p>
 *
 * <h2>This class is NOT the pre-posting pass, and must not be reused for it</h2>
 *
 * <p>Trade-offs: {@code app/cbl/CBTRN01C.cbl} is migrated by
 * {@code com.carddemo.batch.job.PreflightDailyTransactionsJob}, and that job must NOT be routed
 * through this class even though its first two checks look identical. Three differences make the
 * reuse a divergence rather than a saving. It performs ONLY the two referential checks and has no
 * credit-limit and no expiration test at all -- neither {@code CREDIT-LIMIT} nor any spelling of the
 * expiration field occurs anywhere in its 494 lines. Its messages differ:
 * {@code INVALID CARD NUMBER FOR XREF} at its {@code :232} and
 * {@code INVALID ACCOUNT NUMBER FOUND} at its {@code :246}, against the two this chain reports at
 * {@code app/cbl/CBTRN02C.cbl:386} and {@code :398}. And it records soft status only: it writes no
 * reject stream and sets no return code, reaching {@code GOBACK} at its {@code :197} with no
 * {@code MOVE} to {@code RETURN-CODE} anywhere in the program. Routing it here would emit the wrong
 * message text, fabricate reject rows the reference never writes, and turn a program that ends at
 * zero into one that ends at four.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Baseline paths above are cited for provenance only. Nothing under {@code app/**} is read at run
 * time and nothing under it is altered by this migration: the reference is the behavioural oracle
 * and stays byte-identical. Where this class departs from the reference, the reference does one
 * thing, this class does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement.</p>
 */
@Service
public class PostingValidationService {

    /**
     * The integer-digit width the reference declares for the projected-balance intermediate.
     *
     * <p>Assumptions: {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:187} is nine
     * integer digits and two decimal places. The count is stated here rather than as a magnitude
     * because transformation rule T1 makes the copybook normative and a digit count is read straight
     * off a {@code PICTURE} clause, where a composed bound can be mistyped into a plausible wrong
     * value.</p>
     */
    private static final int PROJECTED_BALANCE_INTEGER_DIGITS = 9;

    /** The by-card access path {@code 1500-A-LOOKUP-XREF} reads. */
    private final CardXrefRepository crossReferences;

    /** The by-account access path {@code 1500-B-LOOKUP-ACCT} reads. */
    private final AccountRepository accounts;

    /**
     * Creates the validation service over the two access paths the reference program opens.
     *
     * @param crossReferences the by-card cross-reference path {@code 1500-A-LOOKUP-XREF} reads, of
     *     type {@link CardXrefRepository}; must not be {@code null}
     * @param accounts the by-account master path {@code 1500-B-LOOKUP-ACCT} reads, of type
     *     {@link AccountRepository}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PostingValidationService(CardXrefRepository crossReferences, AccountRepository accounts) {
        // WHAT: both collaborators arrive through the constructor and are held final.
        // WHY : Assumptions: the migration plan's design-pattern section makes constructor injection
        //       the rule for this tree, and it is what lets every case below be driven without a
        //       database. Field or setter injection would additionally admit a half-built instance,
        //       whose first lookup would fail with a null dereference carrying no collaborator name.
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

    /**
     * Validates one daily transaction, resolving the cross-reference and the account itself.
     *
     * <p>This is the transcription of {@code 1500-VALIDATE-TRAN} at
     * {@code app/cbl/CBTRN02C.cbl:370}, including the guard at {@code :372} that stops the account
     * lookup running once the cross-reference lookup has failed.</p>
     *
     * @param transaction the daily transaction being validated, of type {@link DailyTransaction};
     *     must not be {@code null}
     * @return the one outcome the reference reports for this transaction, as a
     *     {@link PostingValidationResult} carrying at most one reason, never {@code null}
     * @throws NullPointerException if {@code transaction} is {@code null}
     * @throws ArithmeticException if the projected balance needs more integer digits than
     *     {@code WS-TEMP-BAL} declares, propagated unchanged from
     *     {@link #computeProjectedBalance(Account, DailyTransaction)}
     * @throws org.springframework.dao.DataAccessException if either read cannot be carried out,
     *     propagated unchanged from the repository
     */
    public PostingValidationResult validate(DailyTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");

        // WHAT: this entry point owns both reads, where an earlier revision of this class owned
        //       neither.
        // WHY : Refactoring Rationale: that revision accepted the cross-reference and the account
        //       already resolved and held no repository at all, which left the guard at :372 outside
        //       the class that transcribes :370. Two things followed. The skipped read could not be
        //       demonstrated here, because a caller had necessarily performed it before calling, so
        //       the distinction between reason 100 EXCLUDING reason 101 and merely outranking it was
        //       untestable at this level. And the reads themselves were unverifiable against
        //       app/jcl/XREFFILE.jcl, so nothing prevented a caller resolving the card through the
        //       alternate index rather than the base cluster. Owning both reads restores the guard
        //       to the paragraph it belongs to; the overload below keeps the caller that needs the
        //       entities from paying for a second read.
        Optional<CardXref> crossReference = lookupCrossReference(transaction);

        // WHAT: the :372 guard -- the account read is reached only while no reason has been assigned.
        // WHY : Assumptions: flatMap expresses that guard exactly, because it does not invoke its
        //       function when the optional is empty, so an unresolved card leaves the account path
        //       genuinely unread rather than read and discarded. The reference is explicit that the
        //       lookup does not happen: :372 tests the reason and :373 performs the paragraph only
        //       inside that test, which is why reason 100 excludes reason 101 rather than merely
        //       outranking it.
        // WHY : Alternatives Considered: reading the account unconditionally and letting the result
        //       type discard the surplus finding. Rejected because it would issue one wasted query
        //       for every unresolvable card in the daily feed, and because it would make the
        //       exclusion an artefact of precedence rather than of control flow -- the observable
        //       reason would agree while the reads no longer matched the reference.
        Optional<Account> account = crossReference.flatMap(this::lookupAccount);

        return validate(transaction, crossReference, account);
    }

    /**
     * Validates one daily transaction against a cross-reference and account already resolved.
     *
     * <p>This overload exists for the caller that must hold both entities anyway. The posting job
     * writes the transaction, the category balance and the account inside one unit of work, at
     * {@code app/cbl/CBTRN02C.cbl:440-442}, so it reads both records before validating and needs
     * them afterwards.</p>
     *
     * @param transaction the daily transaction being validated, of type {@link DailyTransaction};
     *     must not be {@code null}
     * @param crossReference the row the card number resolved to, or an EMPTY optional when it
     *     resolved to nothing, of type {@code Optional<CardXref>}; must not be {@code null}
     * @param account the account the cross-reference named, or an EMPTY optional when that read
     *     found nothing, of type {@code Optional<Account>}; must not be {@code null}
     * @return the one outcome the reference reports for these findings, as a
     *     {@link PostingValidationResult} carrying at most one reason, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws ArithmeticException if the projected balance needs more integer digits than
     *     {@code WS-TEMP-BAL} declares, propagated unchanged from
     *     {@link #computeProjectedBalance(Account, DailyTransaction)}
     */
    public PostingValidationResult validate(DailyTransaction transaction,
            Optional<CardXref> crossReference, Optional<Account> account) {

        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(crossReference, "crossReference must not be null");
        Objects.requireNonNull(account, "account must not be null");

        // WHAT: this overload is kept alongside the resolving one rather than replacing it.
        // WHY : Trade-offs: two entry points cost a slightly wider surface and buy one read of each
        //       record per feed row. The posting job needs the cross-reference to key the category
        //       balance and the account to carry the balance update, which the reference performs at
        //       :440 and :441 in the same unit of work as the write at :442; had it to call the
        //       resolving entry point it would read both records a second time, and the two reads
        //       could not be shown to have returned the same rows by inspection of this class.
        boolean crossReferenceMissing = crossReference.isEmpty();
        boolean accountMissing = account.isEmpty();

        if (crossReferenceMissing || accountMissing) {
            // WHAT: neither boundary is reported, and no projection accompanies these outcomes.
            // WHY : Assumptions: both boundary tests sit inside the account read's NOT INVALID KEY
            //       branch at :400, so neither is evaluated without an account record, and the
            //       projection at :403-405 is formed from fields of that record. Reporting a
            //       boundary finding here would require inventing a limit and a date, and passing a
            //       zero projection would assert that one was computed and came to nothing, which is
            //       a different claim from there having been none. The result type enforces the same
            //       pairing, so a projection supplied here would be refused rather than ignored.
            return PostingValidationResult.resolve(crossReferenceMissing, accountMissing, false,
                    false, null);
        }

        Account resolved = account.get();
        Money projectedBalance = computeProjectedBalance(resolved, transaction);

        // WHAT: both boundary conditions are evaluated, and neither suppresses the other.
        // WHY : Assumptions: :413 closes the credit-limit block and :414 opens the expiration block
        //       with no IF WS-VALIDATION-FAIL-REASON = 0 between them, unlike the guard at :372, so
        //       the reference evaluates the expiration test even after assigning the credit-limit
        //       reason. Guarding the second evaluation on the first finding would drop the very
        //       overwrite at :417 that decides the outcome for a transaction failing both.
        boolean overCreditLimit = isOverCreditLimit(projectedBalance, resolved);
        boolean pastAccountExpiration = isReceivedAfterExpiration(resolved, transaction);

        // WHAT: the two findings are handed over as findings, and the surviving reason is chosen
        //       by the result type.
        // WHY : Alternatives Considered: an if/else-if over the two conditions in the order the
        //       source reads them, which is what a direct transcription produces. Rejected because
        //       the reference's two assignments are not alternatives: with no guard between :413 and
        //       :414, the MOVE 103 at :417 overwrites the MOVE 102 at :410, so declaration order
        //       reports the credit-limit reason where the reference reports the expiration one.
        // WHY : Assumptions: the divergence such a chain produces is invisible to every
        //       single-condition scenario -- reject_102_overlimit and reject_103_expired would both
        //       still agree byte for byte -- and shows up only on transactions that trip BOTH, which
        //       is why the ordering is delegated to the one factory that owns it rather than
        //       restated here.
        return PostingValidationResult.resolve(false, false, overCreditLimit, pastAccountExpiration,
                projectedBalance.amount());
    }

    /**
     * Resolves the cross-reference row for a transaction's card number.
     *
     * <p>This is the transcription of {@code 1500-A-LOOKUP-XREF} at
     * {@code app/cbl/CBTRN02C.cbl:380}, whose {@code INVALID KEY} branch at {@code :384} is the
     * finding this method reports as an empty result.</p>
     *
     * @param transaction the daily transaction whose card number keys the read, of type
     *     {@link DailyTransaction}; must not be {@code null}
     * @return the matching row as an {@code Optional<CardXref>}, or an EMPTY optional when the card
     *     number resolves to nothing or the feed record carries no card number at all, never
     *     {@code null}
     * @throws NullPointerException if {@code transaction} is {@code null}
     * @throws org.springframework.dao.DataAccessException if the read cannot be carried out,
     *     propagated unchanged from the repository
     */
    Optional<CardXref> lookupCrossReference(DailyTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");

        String cardNumber = transaction.getCardNum();

        // WHAT: a feed record carrying no card number is reported as an unresolved card, not as an
        //       error.
        // WHY : Assumptions: the reference cannot distinguish the two. :382 moves the field into the
        //       key and :383 reads on it, so a blank card number is read as a blank key, finds no
        //       row and takes the INVALID KEY branch at :384 like any other miss. The column is
        //       nullable in the target -- ledger.daily_transactions.card_num -- and the feed entity
        //       stores what it was given, whose own charter states that validation belongs to this
        //       service and that raising there would replace a counted reject carrying a reason code
        //       with an exception carrying none. Reporting absence keeps that reject counted.
        // WHY : Trade-offs: this also keeps the repository's stated contract intact, which requires a
        //       non-null card number; passing the null through to satisfy a single code path would
        //       trade a business outcome for a failed step.
        if (cardNumber == null) {
            return Optional.empty();
        }

        // WHAT: the read goes through the by-card finder and never the by-account one.
        // WHY : Assumptions: the card number is the base cluster's whole key, all sixteen bytes from
        //       byte zero per app/jcl/XREFFILE.jcl, matching the head of the fifty-byte layout at
        //       app/cpy/CVACT03Y.cpy:5, so this lookup is single valued. The sibling by-account
        //       finder reads the NONUNIQUEKEY alternate index defined over eleven bytes at offset 25
        //       and belongs to the interest flow; using it here would answer a different question
        //       and could not be single valued.
        return this.crossReferences.findByCardNum(cardNumber);
    }

    /**
     * Resolves the account master record a cross-reference row names.
     *
     * <p>This is the read of {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:393}, whose
     * {@code INVALID KEY} branch at {@code :396} is the finding this method reports as an empty
     * result. The two tests that paragraph goes on to apply are
     * {@link #isOverCreditLimit(Money, Account)} and
     * {@link #isReceivedAfterExpiration(Account, DailyTransaction)}.</p>
     *
     * @param crossReference the row naming the account to read, of type {@link CardXref}; must not
     *     be {@code null}
     * @return the matching account as an {@code Optional<Account>}, or an EMPTY optional when the
     *     identifier resolves to nothing or the row names no account at all, never {@code null}
     * @throws NullPointerException if {@code crossReference} is {@code null}
     * @throws org.springframework.dao.DataAccessException if the read cannot be carried out,
     *     propagated unchanged from the repository
     */
    Optional<Account> lookupAccount(CardXref crossReference) {
        Objects.requireNonNull(crossReference, "crossReference must not be null");

        Long accountId = crossReference.getAccountId();

        // WHAT: a cross-reference row naming no account is reported as an account that was not
        //       found.
        // WHY : Assumptions: this matches what the reference does with the same data. :394 moves
        //       XREF-ACCT-ID into the key and :395 reads on it, so an absent identifier is read as a
        //       blank key and takes the INVALID KEY branch at :396, which is reason 101. Reporting
        //       absence therefore reproduces the reject the reference counts, where handing the
        //       missing identifier to the finder would raise before the read was attempted.
        if (accountId == null) {
            return Optional.empty();
        }

        return this.accounts.findByAccountId(accountId);
    }

    /**
     * Forms the projected balance the credit-limit guard compares.
     *
     * @param account the account whose cycle accumulators open the projection, of type
     *     {@link Account}; must not be {@code null}
     * @param transaction the transaction whose amount completes the projection, of type
     *     {@link DailyTransaction}; must not be {@code null}
     * @return the projection as a {@link Money}, an exact decimal amount at two decimal places,
     *     never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ArithmeticException if the projection needs more than
     *     {@code PROJECTED_BALANCE_INTEGER_DIGITS} integer digits, which is the width
     *     {@code WS-TEMP-BAL} declares
     */
    Money computeProjectedBalance(Account account, DailyTransaction transaction) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");

        // WHAT: the two operands are the CYCLE accumulators, and the debit is SUBTRACTED.
        // WHY : Assumptions: :403-405 reads COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT -
        //       ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. The subtraction is transcribed as written even
        //       though the debit accumulator holds signed values rather than magnitudes -- :551 adds
        //       a negative amount into it -- because the statement is the contract and reversing the
        //       operator to suit the sign convention would change the quantity compared.
        // WHY : Alternatives Considered: projecting from ACCT-CURR-BAL, which reads as the more
        //       natural meaning of the balance this transaction would bring the account to. Rejected
        //       because it is not the quantity the reference compares: the field is declared at
        //       app/cpy/CVACT01Y.cpy:7 and the reference touches it only when posting an amount at
        //       :547, never in this computation. On any account whose cycle totals do not happen to
        //       sum to that balance the two projections differ, so the inclusive guard at :407 lands
        //       on the other side of the limit and a transaction is refused where the reference
        //       posts it, or posted where the reference refuses it.
        BigDecimal projection = account.getCurrCycCredit()
                .subtract(account.getCurrCycDebit())
                .add(transaction.getAmount());

        // WHAT: the expression is evaluated at full precision and reduced to cents exactly once.
        // WHY : Assumptions: a COBOL COMPUTE evaluates its whole expression before storing the
        //       result into the receiving field, so reducing after the third operand is what
        //       matches :403-405; reducing at each step would impose the target's width on two
        //       intermediates the reference never stores. Every operand is already at two decimal
        //       places, so the reduction is exact rather than a rounding.
        // WHY : Trade-offs: the picture-bounded factory is chosen over the general one so the bound
        //       is the nine integer digits WS-TEMP-BAL declares at :187 rather than the ten of the
        //       widest reference money field. The reference would truncate a wider result silently,
        //       having no ON SIZE ERROR clause, whereas this raises; a projection that cannot be
        //       represented is reported at the value that produced it instead of deciding the
        //       credit-limit comparison on digits the reference discarded. The divergence is
        //       registered in the traceability document.
        return Money.ofPicture(projection, PROJECTED_BALANCE_INTEGER_DIGITS);
    }

    /**
     * Reports whether the projected balance breaches the account's credit limit.
     *
     * @param projectedBalance the projection formed by
     *     {@link #computeProjectedBalance(Account, DailyTransaction)}, of type {@link Money}; must
     *     not be {@code null}
     * @param account the account whose credit limit bounds the projection, of type {@link Account};
     *     must not be {@code null}
     * @return {@code true} when the projection is STRICTLY GREATER than the limit, and therefore
     *     {@code false} when it equals the limit exactly
     * @throws NullPointerException if either argument is {@code null}
     */
    boolean isOverCreditLimit(Money projectedBalance, Account account) {
        Objects.requireNonNull(projectedBalance, "projectedBalance must not be null");
        Objects.requireNonNull(account, "account must not be null");

        // WHAT: the finding is taken from the shared money type's strict breach test.
        // WHY : Alternatives Considered: writing the comparison here with an inclusive operator,
        //       which reads correctly against the >= at :407 and is the wrong transcription of it.
        //       That line is a PASS guard, so its reject arm at :409-413 is reached only on a
        //       strictly greater projection; an inclusive reject test would refuse the
        //       exactly-at-limit transaction the reference posts, and
        //       tests/golden/posting/boundary_exact_limit/dalyrejs.expected is empty with a return
        //       code of zero precisely because that transaction posts.
        // WHY : Assumptions: the shared type derives this from the inclusive guard rather than from
        //       an independent comparison, so the pass test and the reject test cannot drift apart
        //       at the one value both exist to decide, and it compares with compareTo rather than
        //       equals -- equals is scale-sensitive, so a limit of 1000.0 would not equal a
        //       projection of 1000.00 and an at-limit transaction would be refused on a difference
        //       of notation.
        return projectedBalance.exceeds(Money.of(account.getCreditLimit()));
    }

    /**
     * Reports whether a transaction was received after its account expired.
     *
     * @param account the account whose expiration date bounds the transaction, of type
     *     {@link Account}; must not be {@code null}
     * @param transaction the transaction whose originating date is compared, of type
     *     {@link DailyTransaction}; must not be {@code null}
     * @return {@code true} when the expiration date is STRICTLY EARLIER than the originating date,
     *     and therefore {@code false} when the two are the same day
     * @throws NullPointerException if either argument is {@code null}
     */
    boolean isReceivedAfterExpiration(Account account, DailyTransaction transaction) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");

        LocalDate expirationDate = account.getExpirationDate();
        LocalDateTime originatingStamp = transaction.getOrigTs();

        // WHAT: an absent originating stamp is reported as within the expiration, and an absent
        //       expiration date as beyond it.
        // WHY : Assumptions: :414 compares two character fields, so the reference's answer for a
        //       blank operand follows from the collating sequence rather than from a decision it
        //       makes. A blank sorts below a digit in both the EBCDIC the datasets carry and the
        //       ASCII the harness compiles for, so a real expiration date against a blank stamp
        //       satisfies the inclusive guard and posts, while a blank expiration date against a
        //       real stamp fails it and is reported. Testing the stamp first also gives two blanks
        //       the equal comparison the reference makes of them.
        // WHY : Trade-offs: the ordering of these two tests is the whole of their behaviour, so it
        //       is stated rather than left to fall out of the code. The originating stamp is
        //       nullable in the target while the expiration date is declared NOT NULL, so only the
        //       first branch is reachable from a row this module reads; the second is kept because
        //       the entity accessor is nullable and a mapped blank date would otherwise dereference
        //       to a failed step instead of the counted reject the reference produces.
        if (originatingStamp == null) {
            return false;
        }
        if (expirationDate == null) {
            return true;
        }

        // WHAT: the comparison is made on calendar dates rather than on ten characters.
        // WHY : Assumptions: the equivalence is licensed by the record mapper that produces these
        //       values, com.carddemo.batch.mapper.AccountRecordMapper, which records that the stored
        //       ten-character form is already ISO ordered so a lexical comparison of it is
        //       equivalent to a chronological one, and names :414 as the line relying on that. The
        //       reference reads only the first ten characters of the 26-character
        //       DALYTRAN-ORIG-TS declared at app/cpy/CVTRA06Y.cpy:16, which is the date portion, so
        //       taking the date of the mapped stamp compares the same characters.
        // WHY : Assumptions: the reject test is STRICT because :414 is an inclusive PASS guard. A
        //       transaction dated exactly on the expiration date posts, which is why
        //       tests/golden/posting/boundary_expiry_equal/dalyrejs.expected is empty with a return
        //       code of zero, and only one dated later is reported.
        return expirationDate.isBefore(originatingStamp.toLocalDate());
    }
}
