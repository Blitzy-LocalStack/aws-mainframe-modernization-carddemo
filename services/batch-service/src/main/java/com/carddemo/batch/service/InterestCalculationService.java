package com.carddemo.batch.service;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.money.Money;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Accrues monthly interest, transcribed paragraph by paragraph from {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>Purpose: this type carries every accrual rule the reference interest calculator applies, one
 * method per originating paragraph so that the register at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite paragraph-to-method pairs.
 * {@code 1050-UPDATE-ACCOUNT} at {@code :350} applies an account's accumulated total and clears its
 * billing cycle; {@code 1100-GET-ACCT-DATA} at {@code :372} and {@code 1110-GET-XREF-DATA} at
 * {@code :393} read the account and its card once per account group; {@code 1200-GET-INTEREST-RATE}
 * at {@code :415} resolves the disclosure-group rate and {@code 1200-A-GET-DEFAULT-INT-RATE} at
 * {@code :443} re-reads under the group named {@code DEFAULT};
 * {@code 1300-COMPUTE-INTEREST} at {@code :462} applies the formula;
 * {@code 1300-B-WRITE-TX} at {@code :473} renders the generated transaction; and
 * {@code 1400-COMPUTE-FEES} at {@code :518} is the extension point the reference declares and
 * never fills in.</p>
 *
 * <p>What this type does NOT own is as load-bearing as what it does. The ordered walk over category
 * balances, the step's transaction, its return code and the running per-account state all belong to
 * {@code com.carddemo.batch.job.CalculateInterestJob}, because a control break is defined by what
 * the PREVIOUS row was and that state cannot live on a singleton. Every method here is therefore a
 * function of its arguments: the run-scoped identifier suffix arrives as a parameter rather than as
 * a field, so two concurrent runs cannot interleave one counter.</p>
 *
 * <p>Assumptions: the accrual truncates toward zero, and the evidence is an ABSENCE rather than a
 * statement, which is why it is recorded here instead of being left to be re-derived. The reference
 * statement at {@code app/cbl/CBACT04C.cbl:464-465} reads
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} and carries no
 * {@code ROUNDED} phrase; no statement anywhere in that program's 652 lines carries one, so a
 * COBOL arithmetic statement storing into {@code WS-MONTHLY-INT PIC S9(09)V99} at line 168
 * discards its surplus digits toward zero. This is the one monetary reduction in the module
 * governed by that mode rather than by the shared half-up contract.</p>
 *
 * <p>Assumptions: a rate of zero and an absent disclosure group are different outcomes and stay
 * different. A group carrying a genuine zero rate accrues nothing and emits nothing, because
 * {@code :214} gates the accrual and the fee call together; a group that is not found accrues at
 * whatever the {@code DEFAULT} group carries, because {@code :436-438} substitutes that group and
 * retries. A {@code DEFAULT} group that is itself absent is neither of those outcomes: {@code :443}
 * reads with no {@code INVALID KEY} clause and abends, so it is a hard failure here too.</p>
 *
 * <p>Refactoring Rationale: the accrued total is applied for EVERY account group including the
 * last, which the baseline does not do. A final-flush path is written at {@code :219-220} as the
 * {@code ELSE} of the {@code IF END-OF-FILE = 'N'} test at {@code :189}, performing the same update
 * paragraph, and it cannot be reached: the loop at {@code :188} is
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'}, which tests before each iteration, and the flag is
 * assigned {@code 'Y'} at exactly one place, {@code :340} inside
 * {@code 1000-TCATBALF-GET-NEXT} -- so once the read reports end of file the loop terminates before
 * its body can observe the condition its own {@code ELSE} arm tests for. The baseline therefore
 * never applies the final account group's accumulated interest; the migrated job applies it for
 * every account group; and the divergence is documented as D-3 in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The reference source under
 * {@code app/**} is the specification this work is compared against and is left exactly as it
 * stands. The evidence is directly observable in the shipped goldens:
 * {@code tests/golden/interest/happy_path/acctdat.expected} carries account
 * {@code 00000000001} at <code>00000002065&#123;</code>, which is 206.50 and therefore 194.00 plus
 * its 12.50 accrual, while account {@code 00000000002} carries <code>00000001580&#123;</code>,
 * which is 158.00 and unchanged, even though its interest transaction was written. The trailing
 * character is the zoned-decimal positive overpunch of a final zero digit, not a brace in the
 * data.</p>
 *
 * @see InterestRateLookup
 * @see DisclosureGroupKey
 * @see Money#monthlyInterest(java.math.BigDecimal)
 */
@Service
public class InterestCalculationService {

    /**
     * The account-group component the reference substitutes when a group is not found, unpadded.
     *
     * <p>Assumptions: the literal is held at its source width of seven characters, exactly as
     * {@code app/cbl/CBACT04C.cbl:437} writes it into a field declared {@code PIC X(10)}. Padding to
     * the declared width belongs to {@link DisclosureGroupKey}, which owns that width, so this
     * constant stays comparable with the reference literal character for character and no second
     * definition of the padded form exists here.</p>
     */
    public static final String DEFAULT_ACCOUNT_GROUP = "DEFAULT";

    /**
     * The rounding mode the reference's own accrual applies, named rather than left implicit.
     *
     * <p>Assumptions: this constant is DOCUMENTATION and not a parameter. The accrual reduces
     * through {@link Money#monthlyInterest(java.math.BigDecimal)}, which binds the mode to
     * {@code Money.BASELINE_INTEREST_ROUNDING} and exposes no way to select another, so naming it
     * beside the service records the reference's mode where a reader of this service looks for it
     * without giving any call site the ability to apply a different one. This service's own tests
     * assert the two are the same value, so a drift between the name and the behaviour fails the
     * build rather than misleading a reader.</p>
     *
     * <p>Assumptions: the mode is truncation toward zero because the reference statement at
     * {@code app/cbl/CBACT04C.cbl:464-465} carries no {@code ROUNDED} phrase, and neither does any
     * other statement in that program, so it discards the surplus digits of its result.</p>
     */
    public static final RoundingMode ACCRUAL_ROUNDING = RoundingMode.DOWN;

    /**
     * The transaction type code every generated interest transaction carries, {@code 01}.
     *
     * <p>Assumptions: the value is the literal at {@code app/cbl/CBACT04C.cbl:482} and is constant
     * across every accrual rather than derived from the category balance that produced it.</p>
     */
    public static final String INTEREST_TYPE_CODE = "01";

    /**
     * The transaction category code every generated interest transaction carries, {@code 05}.
     *
     * <p>Assumptions: the value is HARDCODED at {@code app/cbl/CBACT04C.cbl:483} and is emphatically
     * not the category of the balance being accrued. {@code tests/golden/interest/happy_path}
     * carries {@code 0005} in that field for a fixture whose input category is {@code 0001}, so
     * propagating the source category would be the plausible reading and the wrong one.</p>
     */
    public static final String INTEREST_CATEGORY_CODE = "05";

    /**
     * The width {@code TRAN-CAT-CD} declares, four digits.
     *
     * <p>Assumptions: the width is that of {@code TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:7}, and it is needed because the value moved into that field at
     * {@code app/cbl/CBACT04C.cbl:483} is a TWO-character alphanumeric literal. A COBOL
     * {@code MOVE} of an alphanumeric literal into a numeric-display field aligns on the decimal
     * point and zero-fills to the declared width, so the stored field is four characters and not
     * two.</p>
     */
    public static final int TRANSACTION_CATEGORY_CODE_DIGITS = 4;

    /**
     * The stored form of the generated transaction's category code, {@code 0005}.
     *
     * <p>Assumptions: this is the value the reference actually stores, and it differs from the
     * literal it is written from. {@code :483} moves the two characters {@code 05} into
     * {@code TRAN-CAT-CD PIC 9(04)}, and the golden
     * {@code tests/golden/interest/happy_path/transact.expected} carries {@code 0005} at that
     * field's offset 18 -- so storing the literal unpadded would be the right number in the wrong
     * form. The distinction is load-bearing rather than cosmetic: this column is compared against
     * the category component of the category-balance and disclosure-group keys, both of which carry
     * the four-character form, so an unpadded value would match nothing.</p>
     *
     * <p>Alternatives Considered: writing {@code "0005"} as a second literal beside
     * {@link #INTEREST_CATEGORY_CODE}. It is rejected because the two would then be independent
     * values that could disagree, and a reader could not tell which of them the reference states.
     * Deriving the stored form from the literal and the declared width keeps one of them the
     * reference's own characters and makes the other visibly a consequence of the field it lands
     * in.</p>
     */
    public static final String INTEREST_CATEGORY_CODE_FIELD =
            zeroPadded(Long.parseLong(INTEREST_CATEGORY_CODE), TRANSACTION_CATEGORY_CODE_DIGITS);

    /**
     * The source every generated interest transaction is attributed to, {@code System}.
     *
     * <p>Assumptions: the value is the literal at {@code app/cbl/CBACT04C.cbl:484}. It reaches a
     * field declared {@code PIC X(10)}, and the padding to that width belongs to the record mapper
     * rather than to this constant.</p>
     */
    public static final String INTEREST_SOURCE = "System";

    /**
     * The description prefix a generated interest transaction carries before the account identifier.
     *
     * <p>Assumptions: the thirteen characters are the literal at {@code app/cbl/CBACT04C.cbl:485},
     * trailing blank included. The blank is part of the literal and not an accident of alignment:
     * without it the rendered description would run the prefix into the account identifier.</p>
     */
    public static final String INTEREST_DESCRIPTION_PREFIX = "Int. for a/c ";

    /**
     * The width the generated identifier's run-scoped suffix is zero-padded to, six digits.
     *
     * <p>Assumptions: the width is that of {@code WS-TRANID-SUFFIX PIC 9(06)} declared at
     * {@code app/cbl/CBACT04C.cbl:173}. Together with the ten-character business-date token it fills
     * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5} exactly, with no padding left
     * over, which is why neither width is free to change on its own.</p>
     */
    public static final int IDENTIFIER_SUFFIX_DIGITS = 6;

    /**
     * The width the account identifier is rendered to inside the generated description, eleven.
     *
     * <p>Assumptions: {@code ACCT-ID} is declared {@code PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:5}, and a COBOL {@code STRING ... DELIMITED BY SIZE} moves a
     * numeric-display field at its full declared width, so the eleven characters are zero-padded
     * digits rather than the shortest rendering of the number. The golden
     * {@code tests/golden/interest/happy_path/transact.expected} carries
     * {@code Int. for a/c 00000000001}, so rendering the identifier as a plain integer would
     * produce fourteen characters where the reference produces twenty-four.</p>
     */
    public static final int ACCOUNT_IDENTIFIER_DIGITS = 11;

    /** The merchant identifier a generated interest transaction carries, zero, per {@code :491}. */
    private static final long NO_MERCHANT = 0L;

    /**
     * The value the three merchant text fields are cleared to, per {@code :492-494}.
     *
     * <p>Assumptions: the empty String stands for the reference's {@code MOVE SPACES}, because the
     * padding to each field's declared width belongs to the record mapper. Writing a run of blanks
     * here would put the same width in two places and let them disagree.</p>
     */
    private static final String NO_MERCHANT_TEXT = "";

    /** The read-only disclosure groups both rate reads go through. */
    private final DisclosureGroupRepository disclosureGroups;

    /** The account master an accrued total is applied to. */
    private final AccountRepository accounts;

    /** The cross-reference a generated transaction's card number is taken from. */
    private final CardXrefRepository crossReferences;

    /** The ledger the generated interest transactions are written to. */
    private final TransactionRepository ledger;

    /**
     * Builds the service over the repositories the reference's five files reduce to.
     *
     * <p>Assumptions: the dependencies arrive through the constructor and are held final, which is
     * the injection style the migration plan's section 0.4.3 settles on for every service. The
     * alternative that matters is not field injection but STATE: this type deliberately holds no
     * mutable member, so the run-scoped identifier suffix has to be passed in. A counter held here
     * would be shared by every run the container serves, and two overlapping runs would then issue
     * the same generated identifier.</p>
     *
     * @param disclosureGroups the read-only repository over the rate table, standing in for the
     *     {@code DISCGRP} file at {@code app/jcl/INTCALC.jcl:35-36}; must not be {@code null}
     * @param accounts the account master, standing in for {@code ACCTFILE} at
     *     {@code app/jcl/INTCALC.jcl:33-34}; must not be {@code null}
     * @param crossReferences the card cross-reference, standing in for the by-account alternate
     *     index path {@code XREFFIL1} at {@code app/jcl/INTCALC.jcl:31-32}; must not be
     *     {@code null}
     * @param ledger the destination for generated interest transactions, standing in for the
     *     {@code SYSTRAN} generation at {@code app/jcl/INTCALC.jcl:37-41}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public InterestCalculationService(DisclosureGroupRepository disclosureGroups,
            AccountRepository accounts, CardXrefRepository crossReferences,
            TransactionRepository ledger) {

        this.disclosureGroups =
                Objects.requireNonNull(disclosureGroups, "disclosureGroups must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    }

    /**
     * Reads the account a group of category balances belongs to, per {@code 1100-GET-ACCT-DATA}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:372-391}, reached from the control break at
     * {@code :203}. It is deliberately a read and nothing more: the account it returns supplies the
     * disclosure-group component at {@code :210} and is the row the accrued total is later applied
     * to, and both of those are separate steps here as they are there.</p>
     *
     * <p>Assumptions: the account is read ONCE PER ACCOUNT GROUP and not once per category balance.
     * The reference performs this paragraph from inside the control break at {@code :194-206}, so
     * every category row of the same account reuses the row the break read. Calling this per row
     * would multiply the reads by the number of categories an account holds and would produce the
     * same numbers, so no test would report it.</p>
     *
     * <p>Alternatives Considered: raising when the account is absent, which is what the reference
     * does -- {@code :378-390} accepts only file status {@code '00'} and otherwise abends after
     * displaying {@code ERROR READING ACCOUNT FILE}. It is declined HERE and only here, because the
     * decision to continue past an orphaned balance row is already registered as divergence
     * D-INTEREST-ORPHAN-ROW against {@code CalculateInterestJob}, which owns the walk and therefore
     * owns what a skipped row means for the rows after it. Returning the absence lets that one
     * registered decision stay in the one file the register names, rather than being taken twice in
     * two places that could drift apart.</p>
     *
     * @param accountId the account identifier the category-balance key carries, standing for
     *     {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy:6}; must not be
     *     {@code null}
     * @return the account row as an {@code Optional<Account>}, or an EMPTY optional when no account
     *     is keyed by that identifier; an empty result is reported rather than raised, for the
     *     reason recorded above
     * @throws NullPointerException if {@code accountId} is {@code null}
     */
    public Optional<Account> loadAccount(Long accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");

        return this.accounts.findByAccountId(accountId);
    }

    /**
     * Reads the card number an account's generated transactions carry, per
     * {@code 1110-GET-XREF-DATA}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:393-413}, reached from the control break at
     * {@code :205}. The value it returns is what {@code :495} moves into {@code TRAN-CARD-NUM}.</p>
     *
     * <p>Assumptions: the finder is the BOUNDED, ORDERED, single-row one rather than a plain
     * single-result finder, and the distinction is forced by the index rather than chosen for
     * tidiness. The reference reads through the by-account alternate index -- {@code XREFFIL1} at
     * {@code app/jcl/INTCALC.jcl:31-32} names
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} -- and that index is declared non-unique, so
     * one account may legitimately map to several cards. A finder expecting at most one row would
     * raise an incorrect-result-size failure on valid baseline data; taking the first row in
     * ascending card-number order resolves to the same card on every run, which a keyed read over a
     * non-unique index also does.</p>
     *
     * <p>Assumptions: an account with no card row is FATAL and is not skipped. {@code :400-412}
     * accepts only file status {@code '00'} and otherwise displays {@code ERROR READING XREF FILE}
     * and abends. Continuing would stamp a generated transaction with no card number, which is a
     * row the ledger cannot attribute to anything, so the failure is raised here where the account
     * is still named rather than surfacing later as a constraint violation.</p>
     *
     * @param accountId the account whose card is wanted, standing for the value {@code :204} moves
     *     into the alternate-index key; must not be {@code null}
     * @return the sixteen-character card number as a String, never {@code null} and never blank
     * @throws NullPointerException if {@code accountId} is {@code null}
     * @throws IllegalStateException if the account has no cross-reference row at all, which is the
     *     abend at {@code :408}; the message names the account because an operator repairing the
     *     extract needs to know which one, and it carries no card number because none was found
     */
    public String loadCrossReference(Long accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");

        Optional<CardXref> crossReference =
                this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(accountId);
        if (crossReference.isEmpty()) {
            // WHAT: the absent cross-reference ends the step rather than producing a blank card.
            // WHY : Alternatives Considered: defaulting the card number to blanks and carrying on was
            //       evaluated and rejected. The reference abends at :408 rather than defaulting, and a
            //       blank card number would be written into TRAN-CARD-NUM PIC X(16) as a syntactically
            //       valid row, so the run would report success while emitting interest attributed to no
            //       card. A refusal that names the account is recoverable; a plausible wrong row is not.
            throw new IllegalStateException("the account " + accountId + " has no card"
                    + " cross-reference row, so no card number can be stamped on its generated"
                    + " interest transaction; app/cbl/CBACT04C.cbl:408 abends on this condition");
        }

        return crossReference.get().getCardNum();
    }

    /**
     * Resolves the disclosure-group rate for one key, per {@code 1200-GET-INTEREST-RATE}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:415-440}. The reference reads the group at {@code :416}
     * with an {@code INVALID KEY} clause that displays
     * {@code DISCLOSURE GROUP RECORD MISSING} and {@code TRY WITH DEFAULT GROUP CODE}, treats file
     * status {@code '23'} as a NORMAL outcome alongside {@code '00'} at {@code :422}, and on
     * {@code '23'} substitutes the group at {@code :437} and retries through {@code :438}.</p>
     *
     * <p>Assumptions: absence is an ordinary outcome of the first read and not an error. Line
     * {@code :422} accepts {@code '00' OR '23'} and abends only on anything else, so an empty first
     * read is what selects the fallback rather than what fails the step.</p>
     *
     * <p>Assumptions: the retry key replaces the ACCOUNT-GROUP component alone. {@code :437} moves
     * the literal into {@code FD-DIS-ACCT-GROUP-ID}, one field of the three-part key declared at
     * {@code app/cpy/CVTRA02Y.cpy:5-8}, and leaves the transaction type and category exactly as they
     * were. The derivation is taken from {@link DisclosureGroupKey#withDefaultAccountGroupId()}
     * rather than assembled here, because a key rebuilt wholesale from defaults would resolve the
     * rate of a different type and category and so would return a plausible number instead of
     * failing.</p>
     *
     * @param requested the key the account's own group, the balance's transaction type and its
     *     category form, built by name from {@code :210-212}; must not be {@code null}
     * @return the resolved lookup, recording the rate and which of the two keys answered; never
     *     {@code null} and never an absent result, because the reference has no outcome in which
     *     neither key resolves and the run continues
     * @throws NullPointerException if {@code requested} is {@code null}
     * @throws IllegalStateException if the requested key resolves nothing AND the
     *     {@code DEFAULT}-group retry resolves nothing, which is the abend at {@code :455}
     */
    public InterestRateLookup rateFor(DisclosureGroupKey requested) {
        Objects.requireNonNull(requested, "requested must not be null");

        Optional<DisclosureGroup> direct = findGroup(requested);
        if (direct.isPresent()) {
            return InterestRateLookup.ofDirectHit(requested, direct.get().getInterestRate());
        }

        // WHAT: the first read found nothing, which is the file status '23' arm of :422.
        // WHY : Assumptions: the two-step retry is control flow the goldens observe, so it is
        //       performed here rather than hidden behind a repository convenience that answered both
        //       keys in one call. tests/golden/interest/default_fallback exists precisely to
        //       distinguish a rate reached under the account's own group from one reached under the
        //       substituted group, and a single-call finder would make the two indistinguishable.
        return defaultRateFor(requested);
    }

    /**
     * Re-reads the rate under the group named {@code DEFAULT}, per
     * {@code 1200-A-GET-DEFAULT-INT-RATE}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:443-460}, performed from {@code :438} once
     * {@code :437} has replaced the account-group component.</p>
     *
     * <p>Assumptions: a missing {@code DEFAULT} row is FATAL and never a silent zero. The reference
     * read at {@code :444} carries NO {@code INVALID KEY} clause at all -- unlike the first read at
     * {@code :416}, which has one -- and {@code :446-459} then accepts only file status {@code '00'},
     * displaying {@code ERROR READING DEFAULT DISCLOSURE GROUP} and abending otherwise. Returning
     * zero instead would suppress the accrual for every account whose group is unknown while
     * reporting a clean run, which is why the migration makes seeding that one row mandatory in
     * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql}.</p>
     *
     * @param requested the key that resolved nothing, whose group component alone is displaced;
     *     must not be {@code null}
     * @return the resolved lookup, whose effective key is the requested key's {@code DEFAULT}-group
     *     derivation and whose fallback predicate therefore answers {@code true}; never {@code null}
     * @throws NullPointerException if {@code requested} is {@code null}
     * @throws IllegalStateException if no row is keyed by the substituted key, which is the abend at
     *     {@code :455}; the message names the substituted key so an operator can see which of the
     *     three components was displaced and which two were carried through
     */
    public InterestRateLookup defaultRateFor(DisclosureGroupKey requested) {
        Objects.requireNonNull(requested, "requested must not be null");

        DisclosureGroupKey substituted = requested.withDefaultAccountGroupId();
        Optional<DisclosureGroup> fallback = findGroup(substituted);
        if (fallback.isEmpty()) {
            throw new IllegalStateException("no disclosure group is keyed by the substituted key "
                    + substituted.fixedWidthKey() + ", so no rate can be resolved for the requested"
                    + " key " + requested.fixedWidthKey() + "; app/cbl/CBACT04C.cbl:455 abends on"
                    + " this condition, and the DEFAULT group row is mandatory reference seed data");
        }

        // WHY : Assumptions: the factory is handed the REQUESTED key and derives the effective one
        //       itself, so the pair it records cannot be an inconsistent one. The rate passed is the
        //       one the RETRY resolved, which is what :444 reads into the record area, and not a value
        //       left over from the read at :416 that found nothing.
        return InterestRateLookup.ofDefaultGroupFallback(requested, fallback.get().getInterestRate());
    }

    /**
     * Reads one disclosure group by the whole three-part key a lookup key describes.
     *
     * <p>Assumptions: the repository identity type is assembled from the key's own named components
     * rather than from positional values, because the reference assembles the key by name too and in
     * a DIFFERENT order from the physical one. {@code :210-212} move the account group, then the
     * CATEGORY, then the TYPE, while the physical layout at {@code app/cpy/CVTRA02Y.cpy:6-8} is the
     * account group, then the TYPE, then the category. Both components are same-width character
     * fields as far as a positional transcription is concerned, so transposing them would compile,
     * would resolve a real row for many inputs, and would accrue at the wrong rate.</p>
     *
     * <p>Assumptions: the category component is taken in its zero-padded four-character form, which
     * is what {@code DIS-TRAN-CAT-CD PIC 9(04)} holds and what the stored key column compares
     * against; the numeric accessor would be the right number and the wrong key.</p>
     *
     * @param key the lookup key whose three components identify the wanted row; must not be
     *     {@code null}
     * @return the one matching row as an {@code Optional<DisclosureGroup>}, or an empty optional
     *     when no group is keyed by that combination
     */
    private Optional<DisclosureGroup> findGroup(DisclosureGroupKey key) {
        return this.disclosureGroups.findByIdIs(new DisclosureGroup.DisclosureGroupId(
                key.accountGroupId(), key.transactionTypeCode(),
                key.transactionCategoryCodeField()));
    }

    /**
     * Accrues one month of interest on one category balance, per {@code 1300-COMPUTE-INTEREST}.
     *
     * <p>This is the formula at {@code app/cbl/CBACT04C.cbl:464-465},
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The caller adds the
     * value this returns to the account's running total, which is {@code :467}, and emits one
     * generated transaction for it, which is {@code :468}.</p>
     *
     * <p>Alternatives Considered: reducing the sum of an account's raw products once at the end,
     * rather than reducing every term as it is produced. It is rejected because {@code :467} reads
     * {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}, and {@code WS-MONTHLY-INT} has ALREADY been stored
     * into {@code PIC S9(09)V99} at line 168 by the preceding statement -- so the addend is a
     * two-place value and the account increment is the sum of the reduced terms, never the reduction
     * of their unreduced sum. The two differ by cents on ordinary multi-category accounts, and
     * {@code Money} deliberately offers no sum-then-reduce helper for exactly this reason, so there
     * is nothing to reach for by mistake.</p>
     *
     * <p>Alternatives Considered: multiplying and dividing through the shared type's general
     * arithmetic, or performing the arithmetic locally. Both are rejected in favour of the one
     * dedicated helper. The general multiplication reduces its product to cents before returning,
     * so a caller that multiplied by the rate and then divided would have discarded the two decimal
     * places the division still needs -- and the general contract rounds half up, which is not the
     * mode this statement applies. Re-implementing the two operations here would put the reference's
     * only monetary formula in a second place, where the mode and the divisor could drift from the
     * shared constants that name them.</p>
     *
     * <p>Assumptions: the delegated helper multiplies at FULL precision before dividing, which is
     * the order the reference states rather than one inferred from it -- the multiplication is
     * parenthesised in the source at {@code :465}. A two-place balance times a two-place rate yields
     * a FOUR-place product, and the single reduction happens at the division; dividing first
     * discards two digits the division would have consumed, which on a balance of 1000.80 at a rate
     * of 2.50 is a two-cent difference.</p>
     *
     * <p>Assumptions: the helper truncates toward zero rather than rounding, because the reference
     * statement carries no {@code ROUNDED} phrase. Truncation is NOT the same as flooring here: the
     * receiving field is declared SIGNED, so a negative balance is reachable, and flooring a negative
     * quotient moves it away from zero by a cent where truncation does not. {@link #ACCRUAL_ROUNDING}
     * names the mode beside this service and the shared helper is what applies it.</p>
     *
     * @param categoryBalance the balance to accrue on, standing for
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy:9}; must not be
     *     {@code null}
     * @param lookup the resolved rate and the key that answered it, standing for
     *     {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:9}; must not be
     *     {@code null}
     * @return one month's interest on that balance at that rate, exact at two decimal places and
     *     truncated toward zero; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Money monthlyInterest(Money categoryBalance, InterestRateLookup lookup) {
        Objects.requireNonNull(categoryBalance, "categoryBalance must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");

        // WHY : Assumptions: the divisor is not written here. It is the named constant the shared type
        //       and InterestRateLookup both publish, so the value 1200 that :465 states appears once in
        //       the module rather than once per call site that needs it.
        return categoryBalance.monthlyInterest(lookup.resolvedRate());
    }

    /**
     * Renders and stores the transaction that records one accrual, per {@code 1300-B-WRITE-TX}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:473-515}, performed from {@code :468}.</p>
     *
     * <p>Assumptions: ONE transaction is emitted PER CATEGORY BALANCE and not one per account. The
     * {@code PERFORM} at {@code :468} sits INSIDE {@code 1300-COMPUTE-INTEREST}, alongside the
     * accumulate at {@code :467}, so it fires once for every category row that clears the zero-rate
     * gate. An account holding three qualifying categories therefore yields three rows, and
     * {@code tests/golden/interest/happy_path/transact.expected} carries consecutively numbered rows
     * for consecutive accounts rather than one row per account.</p>
     *
     * <p>Assumptions: the identifier is the ten-character business-date token followed by the
     * six-digit suffix, filling {@code TRAN-ID PIC X(16)} exactly. {@code :476-480} concatenates
     * {@code PARM-DATE} straight in with {@code DELIMITED BY SIZE}, so the token contributes its raw
     * bytes; the golden shows {@code 2024-01-15000001}, hyphens included. The RAW accessor is used
     * and the normalising one is not, because normalising that token would render the same day as
     * {@code 2024011500} and every generated identifier in the run would differ from the
     * reference's.</p>
     *
     * <p>Assumptions: the suffix advances across the WHOLE RUN and is never reset per account.
     * {@code :474} increments {@code WS-TRANID-SUFFIX PIC 9(06)}, declared at {@code :173}, and no
     * statement in the program resets it -- the control break at {@code :200} resets the interest
     * total and nothing else. The golden's two rows are numbered {@code 000001} and {@code 000002}
     * for two DIFFERENT accounts, which is what makes the run-global scope observable.</p>
     *
     * <p>Assumptions: both timestamps take the SAME instant. {@code :496} derives one value and
     * {@code :497-498} move that one value into both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS},
     * because an accrual originates at the moment it is processed -- unlike a fed transaction, whose
     * originating stamp arrives on the feed. This is why the parity harness needs a layout that masks
     * BOTH stamp fields for this producer where the ordinary layouts mask only the processing
     * stamp.</p>
     *
     * <p>Trade-offs: an instant is accepted as a parameter and no formatting is performed here. The
     * reference renders it through {@code Z-GET-DB2-FORMAT-TIMESTAMP} into the dotted form its own
     * comment at {@code :140} records, which is a different pattern from the target's; the record
     * mapper rules that the two patterns must never be normalised into each other, so representation
     * stays with the mapper and this method supplies only the value. What is given up is that this
     * method cannot be read to discover the stored pattern; what is bought is that the pattern has
     * one owner and a caller cannot choose a second.</p>
     *
     * @param accountId the account the accrual belongs to, rendered into the description at its
     *     declared eleven digits; must not be {@code null}
     * @param cardNumber the card number from the by-account cross-reference, which {@code :495}
     *     stamps into {@code TRAN-CARD-NUM}; must not be {@code null}
     * @param accruedInterest the already-truncated interest this row accrued, which {@code :490}
     *     moves into {@code TRAN-AMT}; must not be {@code null}
     * @param businessDate the injected business date whose raw token opens the generated identifier;
     *     must not be {@code null}
     * @param identifierSuffix the run-scoped counter {@code :474} advances, which the caller owns
     *     because it spans the whole run; must be positive
     * @param stamp the single instant both the originating and the processing stamps take; must not
     *     be {@code null}
     * @return the stored transaction, returned so a caller can assert on the rendered fields without
     *     reading them back; never {@code null}
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code identifierSuffix} is not positive, since
     *     {@code :474} increments before use and so never presents zero
     * @throws IllegalStateException if the suffix has outgrown the six digits the reference reserves
     *     for it, which would otherwise silently widen the generated identifier past the sixteen
     *     characters its column holds
     */
    public Transaction writeInterestTransaction(Long accountId, String cardNumber,
            Money accruedInterest, BusinessDate businessDate, long identifierSuffix,
            LocalDateTime stamp) {

        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(accruedInterest, "accruedInterest must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(stamp, "stamp must not be null");
        if (identifierSuffix <= 0) {
            throw new IllegalArgumentException("identifierSuffix must be positive because"
                    + " app/cbl/CBACT04C.cbl:474 increments the counter before rendering it, so the"
                    + " first generated identifier of a run carries 000001 and never 000000");
        }

        Transaction generated = new Transaction(
                businessDate.token() + zeroPadded(identifierSuffix, IDENTIFIER_SUFFIX_DIGITS));

        generated.setTypeCd(INTEREST_TYPE_CODE);
        // WHY : Assumptions: the category is the HARDCODED literal at :483 and not the category of the
        //       balance being accrued. tests/golden/interest/happy_path/transact.expected carries 0005
        //       where the driving fixture's category is 0001, so propagating the source category is the
        //       plausible reading and would put a real code in a field the reference holds constant.
        // WHY : Assumptions: the STORED form is used and not the two-character literal, because the
        //       MOVE at :483 zero-fills to the declared width of TRAN-CAT-CD PIC 9(04). The entity
        //       compares this column against the four-character category component of the
        //       category-balance and disclosure-group keys, so the unpadded literal would be the right
        //       number in a form that matches nothing.
        generated.setCategoryCd(INTEREST_CATEGORY_CODE_FIELD);
        generated.setSource(INTEREST_SOURCE);

        // WHY : Assumptions: the identifier is rendered at its declared eleven digits rather than as a
        //       plain number, because :485-489 moves ACCT-ID PIC 9(11) with DELIMITED BY SIZE, which
        //       takes the field's full width. The golden's description is the twenty-four characters
        //       Int. for a/c 00000000001; a plain rendering of account 1 would give fourteen.
        generated.setDescription(
                INTEREST_DESCRIPTION_PREFIX + zeroPadded(accountId, ACCOUNT_IDENTIFIER_DIGITS));

        generated.setAmount(accruedInterest.amount());
        generated.setMerchantId(NO_MERCHANT);

        // WHY : Assumptions: the three merchant text fields are cleared rather than left unset, which
        //       is what :492-494 does with MOVE SPACES. An accrual has no merchant, and the reference
        //       states that by writing the fields; leaving them null would store an absence the
        //       baseline record cannot express, since a fixed-width field is always present.
        generated.setMerchantName(NO_MERCHANT_TEXT);
        generated.setMerchantCity(NO_MERCHANT_TEXT);
        generated.setMerchantZip(NO_MERCHANT_TEXT);
        generated.setCardNum(cardNumber);

        generated.setOrigTs(stamp);
        generated.setProcTs(stamp);

        return this.ledger.save(generated);
    }

    /**
     * Accrues the fees the reference declares and never implements, per {@code 1400-COMPUTE-FEES}.
     *
     * <p>This method does nothing, and doing nothing is the whole of its contract.
     * {@code app/cbl/CBACT04C.cbl:518-520} declares {@code 1400-COMPUTE-FEES} as a comment reading
     * {@code To be implemented} followed by an immediate {@code EXIT}, with no statement between
     * them. The migration plan's section 0.5.1.7 requires the paragraph preserved as an explicit,
     * documented extension point rather than dropped.</p>
     *
     * <p>Assumptions: it is gated by the SAME condition the accrual is. {@code :214} tests
     * {@code IF DIS-INT-RATE NOT = 0} and encloses both {@code :215} and {@code :216}, so a
     * zero-rate category reaches neither the accrual nor this method. A caller that gated only the
     * accrual would be calling this more often than the reference performs it, which no test could
     * see today and which would become a behavioural difference the moment it did something.</p>
     *
     * <p>Trade-offs: the empty method is kept rather than the call site removed. Keeping it means a
     * reader comparing the two implementations can see that fee accrual was intended and never
     * written, which a silent omission would hide, and it marks where the behaviour belongs if it is
     * ever supplied. What is accepted in exchange is a method that a reader may briefly mistake for
     * an oversight, which this documentation exists to prevent. Supplying behaviour here would be a
     * behavioural change against the baseline and would need its own registered divergence.</p>
     */
    public void computeFees() {
        // WHY : Assumptions: the body is empty because the reference paragraph is empty -- :519 is a
        //       comment and :520 is the exit. A method that logged, counted or returned a value would
        //       be doing something the reference does not, and the register would then owe a divergence
        //       for a behaviour nobody asked for.
    }

    /**
     * Applies an account's accumulated interest and clears its billing cycle, per
     * {@code 1050-UPDATE-ACCOUNT}.
     *
     * <p>This is {@code app/cbl/CBACT04C.cbl:350-370}: {@code :352} adds the accumulated total to
     * the current balance, {@code :353} and {@code :354} move zero into the two cycle accumulators,
     * and {@code :356} rewrites the row.</p>
     *
     * <p>Assumptions: the addend is the sum of the ALREADY-TRUNCATED per-row terms, which is what
     * the caller accumulates at {@code :467}. This method performs the addition at {@code :352} and
     * introduces no reduction of its own, so the balance it stores is reachable by adding the same
     * terms in the same order the reference adds them.</p>
     *
     * <p>Assumptions: BOTH cycle accumulators are zeroed, and the reset happens even when it changes
     * nothing. {@code :353-354} zero the credit and the debit unconditionally, and the shipped golden
     * shows both at zero because the fixture's inputs were already zero -- so the reset is observable
     * only on non-zero input, and a transcription that dropped it would pass on that fixture alone.
     * The two assignments and the balance update travel together through
     * {@link Account#applyAccruedInterestAndResetCycle(java.math.BigDecimal)}, which the entity
     * exposes as one operation precisely so that no caller can apply part of the transition.</p>
     *
     * <p>Trade-offs: a lost optimistic-lock race FAILS the step and is not retried or re-read here.
     * The reference rewrite at {@code :356-369} accepts only file status {@code '00'} and otherwise
     * displays {@code ERROR RE-WRITING ACCOUNT FILE} and abends, so refusing is the faithful
     * outcome; silently re-reading and re-applying would invent a reconciliation the baseline does
     * not perform, and it would do so on the one row whose balance another writer had just moved.
     * What is accepted is that the whole step is redriven rather than one account, which the
     * orchestrator's per-state retry already covers.</p>
     *
     * @param account the account whose accumulated interest is being applied, read once for this
     *     account group at {@code :203}; must not be {@code null}
     * @param accumulatedInterest the sum of the truncated per-category accruals for this account,
     *     which is zero when no category qualified; must not be {@code null}
     * @return the stored account, returned so a caller can assert on the applied balance without
     *     reading it back; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws org.springframework.dao.OptimisticLockingFailureException if another writer changed the
     *     row since it was read, which fails the step for the reason recorded above
     */
    public Account flushAccount(Account account, Money accumulatedInterest) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(accumulatedInterest, "accumulatedInterest must not be null");

        // WHY : Assumptions: the addition is performed by the shared money type rather than on the raw
        //       column value, so the two operands meet at one declared scale. The entity's own
        //       operation takes the POST-accrual balance because the arithmetic belongs to that type
        //       and the state transition belongs to the entity, which is the split :352 makes when it
        //       adds into the record area and :356 then rewrites it.
        Money balanceAfterAccrual = Money.of(account.getCurrBal()).plus(accumulatedInterest);
        account.applyAccruedInterestAndResetCycle(balanceAfterAccrual.amount());

        return this.accounts.save(account);
    }

    /**
     * Renders a non-negative value as a fixed run of ASCII digits, zero-padded on the left.
     *
     * <p>Assumptions: the padding is assembled around a plain integer rendering rather than produced
     * by a formatting call with a width specifier. A single-argument formatting call resolves its
     * digit characters from the default formatting locale, so under a locale whose numbering system
     * is not latin it would emit that system's digits -- and the generated identifier and description
     * would no longer be the ASCII forms every other reader compares, sorts and byte-matches
     * against.</p>
     *
     * <p>Assumptions: overflow raises rather than truncating or widening. Both callers render into a
     * field of fixed declared width -- six digits for {@code WS-TRANID-SUFFIX PIC 9(06)} and eleven
     * for {@code ACCT-ID PIC 9(11)} -- so a value too large for the width has no correct rendering:
     * widening would push the generated identifier past the sixteen characters its column holds, and
     * truncating would silently reuse an identifier already issued in the same run.</p>
     *
     * @param value the non-negative value to render; must not be negative
     * @param digits the exact number of ASCII digits to render it in; must be positive
     * @return the value rendered in exactly {@code digits} ASCII digits, never {@code null}
     * @throws IllegalStateException if {@code value} needs more than {@code digits} characters, so no
     *     rendering of the declared width exists
     */
    private static String zeroPadded(long value, int digits) {
        String rendered = Long.toString(value);
        if (rendered.length() > digits) {
            throw new IllegalStateException("the value " + value + " needs " + rendered.length()
                    + " digits and the reference reserves only " + digits + " for it, so it cannot be"
                    + " rendered at the declared width without changing the field's size");
        }

        StringBuilder padded = new StringBuilder(digits);
        for (int pad = rendered.length(); pad < digits; pad++) {
            padded.append('0');
        }
        return padded.append(rendered).toString();
    }
}
