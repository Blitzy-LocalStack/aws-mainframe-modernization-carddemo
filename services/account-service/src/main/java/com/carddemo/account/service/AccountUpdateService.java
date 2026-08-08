package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an edited account and customer under an explicit concurrency precondition.
 *
 * <p>Purpose: this is the migrated form of {@code app/cbl/COACTUPC.cbl}'s write path. That program reads
 * the account and customer masters, holds a complete pre-edit snapshot in {@code ACUP-OLD-*} from L669
 * onward, compares it field by field before rewriting, sets {@code WS-DATACHANGED-FLAG} at L168 when the
 * comparison fails, and issues {@code EXEC CICS SYNCPOINT ROLLBACK} at L4095 to L4104 rather than
 * writing. On success it rewrites both records and commits with {@code EXEC CICS SYNCPOINT} at L945 to
 * L958.</p>
 *
 * <p>Refactoring Rationale: this class did not exist, and its absence is why the whole account update
 * DTO layer had no production path -- the request shape, the response shape, the customer projections and
 * the mapper's write direction were reachable only from each other. It is also why the concurrency check
 * had no target form: with nothing loading a row, applying an edit to it and committing, the
 * {@code @Version} column the entities declare was never exercised by anything.</p>
 *
 * <p>Assumptions: the precondition arrives as an OPAQUE revision token supplied by the caller, and this
 * class compares it against the loaded rows rather than assigning it onto them. Assigning a
 * client-supplied version onto an entity would defeat the check completely -- the provider compares the
 * value it holds, so writing the client's value in first makes every comparison succeed. The entities
 * expose no version setter for exactly that reason.</p>
 *
 * <p>Assumptions: BOTH rows are read, checked, applied and committed in ONE transaction, because the
 * reference rewrites both inside one unit of work and commits once. Splitting them would make a state
 * observable that the baseline cannot produce -- an updated account beside an unchanged customer -- and
 * the reference's single rollback covers both.</p>
 *
 * <p>Trade-offs: the revision covers the ACCOUNT and the CUSTOMER as a pair, rendered as the two
 * versions joined, rather than one token per row. The reference's snapshot likewise spans both records
 * and its comparison fails if either changed, so a single token reproduces that. The cost is that a
 * concurrent edit to either row rejects an edit to the other; that is the baseline's behaviour and the
 * screen edits both together anyway.</p>
 */
@Service
public class AccountUpdateService {

    /**
     * Separates the two versions inside one revision token.
     *
     * <p>Assumptions: a character that cannot occur in a decimal version, so the token cannot be
     * ambiguous however large either version grows.</p>
     */
    private static final String REVISION_SEPARATOR = "-";

    /**
     * The aggregate message a successful update latches.
     *
     * <p>Assumptions: reproduced verbatim from {@code app/cbl/COACTUPC.cbl} L527 and L528, including its
     * FOUR dots -- the ellipsis is part of the reference's own text and is not a typographic choice made
     * here, so it is written out rather than normalised to three.</p>
     */
    private static final String UPDATE_ACCEPTED_MESSAGE = "Looks Good.... so far";

    /** The response-field identity of the submitted state code. */
    private static final String FIELD_STATE_CODE = "stateCode";

    /** The response-field identity of the submitted postal code. */
    private static final String FIELD_ZIP_CODE = "zipCode";

    /** The response-field identity of the first telephone number's area code. */
    private static final String FIELD_PHONE_1_AREA_CODE = "phone1AreaCode";

    /** The response-field identity of the second telephone number's area code. */
    private static final String FIELD_PHONE_2_AREA_CODE = "phone2AreaCode";

    // WHY : Assumptions: these four labels are the literals app/cbl/COACTUPC.cbl moves into
    //       WS-EDIT-VARIABLE-NAME immediately before each edit -- 'State' at line 1592, 'Zip' at line
    //       1605, 'Phone Number 1' at line 1632 and 'Phone Number 2' at line 1640. The reference
    //       prefixes its message with this label, so carrying the literals across is what keeps the
    //       rendered sentence identical rather than merely similar.

    /** The label the reference prefixes the state message with. */
    private static final String LABEL_STATE = "State";

    /** The label the reference prefixes the first telephone message with. */
    private static final String LABEL_PHONE_1 = "Phone Number 1";

    /** The label the reference prefixes the second telephone message with. */
    private static final String LABEL_PHONE_2 = "Phone Number 2";

    /** Loads and flushes the account master row. */
    private final AccountRepository accounts;

    /** Loads and flushes the customer master row. */
    private final CustomerRepository customers;

    /** Resolves the account's customer through the by-account cross-reference path. */
    private final CardXrefRepository crossReferences;

    /** Projects the committed account state onto the response. */
    private final AccountContextMapper accountMapper;

    /** Applies the submitted edit onto the loaded customer and projects the committed state. */
    private final CustomerMapper customerMapper;

    /** The value-domain edits that read the reference context's three address allow-lists. */
    private final AddressValidationService addressValidation;

    /**
     * Creates the update service over the rows it writes and the projections it publishes.
     *
     * @param accounts the account master repository; must not be {@code null}
     * @param customers the customer master repository; must not be {@code null}
     * @param crossReferences the card cross-reference repository, used for the by-account path; must not
     *     be {@code null}
     * @param accountMapper the projection to the published account detail; must not be {@code null}
     * @param customerMapper the customer projection and write direction; must not be {@code null}
     * @param addressValidation the address value-domain edits; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountUpdateService(AccountRepository accounts, CustomerRepository customers,
            CardXrefRepository crossReferences, AccountContextMapper accountMapper,
            CustomerMapper customerMapper, AddressValidationService addressValidation) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper must not be null");
        this.customerMapper = Objects.requireNonNull(customerMapper, "customerMapper must not be null");
        this.addressValidation =
                Objects.requireNonNull(addressValidation, "addressValidation must not be null");
    }

    /**
     * Reads the revision token a caller must return on its next update.
     *
     * <p>Assumptions: the token is derived from the two rows and never stored, so it cannot drift from
     * what the provider will compare. Persisting a token would create a second source of truth for the
     * same fact.</p>
     *
     * @param accountId the account whose revision is required
     * @return the opaque revision token, never {@code null}
     * @throws NoSuchElementException if the account or its customer is absent
     */
    @Transactional(readOnly = true)
    public String currentRevision(long accountId) {
        Account account = loadAccount(accountId);
        Customer customer = loadCustomer(accountId);
        return revisionOf(account, customer);
    }

    /**
     * Applies a submitted edit to an account and its customer, refusing a stale precondition.
     *
     * <p>Assumptions: the precondition is checked BEFORE anything is applied, so a stale submission
     * changes nothing at all rather than being partly applied and rolled back. The reference reaches the
     * same outcome by a different route -- it compares before rewriting -- and checking first is the
     * closer reproduction as well as the cheaper one.</p>
     *
     * <p>Assumptions: the provider's own optimistic failure is ALSO translated, not just the explicit
     * comparison. The explicit check closes the long window across the submitter's think time; the
     * provider's check closes the short window between this transaction's read and its flush, which two
     * concurrent submissions holding the same valid revision can both pass. Only the pair covers both.</p>
     *
     * @param accountId the account to update
     * @param request the submitted edit; must not be {@code null}
     * @param expectedRevision the revision token the caller was given, from an {@code If-Match} header;
     *     must not be {@code null} or blank
     * @return the committed state with the NEW revision token, never {@code null}
     * @throws NullPointerException if {@code request} or {@code expectedRevision} is {@code null}
     * @throws NoSuchElementException if the account or its customer is absent
     * @throws RecordConflictException if the precondition is blank or does not match
     * @throws org.springframework.dao.OptimisticLockingFailureException if the rows change between this
     *     transaction's read and its flush, which the shared advice renders as the same conflict
     * @throws ClientInputException if a submitted value is absent where the reference requires one, is
     *     wider than the field it is stored in, or lies outside one of the reference allow-lists for a
     *     state, postal or area code -- all of which the shared advice renders as HTTP 400 carrying one
     *     entry per offending request property
     */
    @Transactional
    public AccountUpdateResponse update(long accountId, AccountUpdateRequest request,
            String expectedRevision) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");

        Account account = loadAccount(accountId);
        Customer customer = loadCustomer(accountId);
        requireCurrentRevision(account, customer, expectedRevision);

        // WHY : Refactoring Rationale: the value-domain edits run BEFORE the mapper applies anything, so
        //       a refused address leaves the loaded rows untouched. Run after applyUpdate they would
        //       mutate the managed entities first and then refuse, and because those entities are managed
        //       by the open transaction the abandoned edit would still be flushed unless the rollback
        //       caught it -- a correctness property that would then depend on transaction configuration
        //       rather than on statement order. app/cbl/COACTUPC.cbl orders it the same way: the whole
        //       1200-EDIT-MAP-INPUTS chain completes and the INPUT-ERROR test at line 1671 gates the
        //       write, so no record is rewritten when an edit fails.
        requireValidAddress(request);

        this.customerMapper.applyUpdate(customer, request);

        // WHY : Assumptions: the flush is FORCED here rather than left to the transaction's end, so an
        //       optimistic failure is raised while this method is still on the stack and the response
        //       below is never assembled from rows that failed to persist. Left to commit time it would
        //       surface from the transaction interceptor after a response object had already been built.
        //       Alternatives Considered: catching it here and rethrowing a conflict of our own. Rejected
        //       because the shared advice ALREADY renders an optimistic-lock failure as HTTP 409 with the
        //       reference's verbatim changed-record sentence, so translating it here would put the same
        //       mapping in a second place and the two could disagree about the message.
        this.customers.saveAndFlush(customer);
        this.accounts.saveAndFlush(account);

        return new AccountUpdateResponse(
                Long.toString(accountId),
                null,
                UPDATE_ACCEPTED_MESSAGE,
                List.of(),
                this.accountMapper.toAccountDetail(account),
                this.customerMapper.toCustomerDetail(customer));
    }

    /**
     * Runs the address value-domain edits and refuses the update when any of them reports an error.
     *
     * <p>The order is the one {@code app/cbl/COACTUPC.cbl} performs in its edit driver: the state code
     * at line 1600, the first telephone number at line 1635, the second at line 1643, and then the
     * cross-field state-and-postal-prefix check at line 1667. That last one is GUARDED in the reference
     * by {@code IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID} at lines 1665 and 1666, and the guard is
     * reproduced here: pairing a state that is already known to be invalid with a postal code would
     * report a second error about a combination whose first half the caller has already been told about.
     *
     * <p>Assumptions: each field is edited only when it was SUPPLIED. An omitted field means unchanged
     * on this path, and the three validators are total -- they pad a blank value to width and then look
     * it up, so a blank state would be reported as an invalid state. Editing unsupplied fields would
     * therefore refuse every update that did not restate the whole address.
     *
     * <p>Trade-offs: every offending field is named in the refusal, but they share ONE message, because
     * that is the shape {@link ClientInputException} carries. This is a close match to the reference,
     * which highlights every field whose flag is not valid -- see {@code app/cpy/CSSETATY.cpy} lines 17
     * to 27 -- while its single 75-character message line can only show one sentence at a time. The
     * sentence carried here is the first failure's, which is the one the reference would have displayed.
     *
     * <p>Trade-offs: the reference sentences reach the operational log but not the response body. The
     * shared advice admits a message to the body only when it ends with the reference's ellipsis
     * terminator, and these sentences end with a full stop or with no punctuation at all. Appending an
     * ellipsis to make them pass was considered and rejected: it would alter text the migration is
     * required to carry across character for character.
     *
     * @param request the submitted edit; must not be {@code null}
     * @throws ClientInputException if any supplied state, postal or area code lies outside the
     *     allow-lists the reference context owns
     */
    private void requireValidAddress(AccountUpdateRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();

        boolean stateSupplied = !FieldValidationFlag.isNeverSupplied(request.stateCode());
        AddressValidationService.AddressValidationResult stateOutcome = stateSupplied
                ? this.addressValidation.validateStateCode(
                        request.stateCode(), FIELD_STATE_CODE, LABEL_STATE)
                : AddressValidationService.AddressValidationResult.valid();
        errors.addAll(stateOutcome.fieldErrors());

        if (!FieldValidationFlag.isNeverSupplied(request.phone1AreaCode())) {
            errors.addAll(this.addressValidation.validateAreaCode(
                    request.phone1AreaCode(), FIELD_PHONE_1_AREA_CODE, LABEL_PHONE_1).fieldErrors());
        }
        if (!FieldValidationFlag.isNeverSupplied(request.phone2AreaCode())) {
            errors.addAll(this.addressValidation.validateAreaCode(
                    request.phone2AreaCode(), FIELD_PHONE_2_AREA_CODE, LABEL_PHONE_2).fieldErrors());
        }

        boolean zipSupplied = !FieldValidationFlag.isNeverSupplied(request.zipCode());
        if (stateSupplied && zipSupplied && stateOutcome.isValid()) {
            errors.addAll(this.addressValidation.validateStateZipCombination(
                    request.stateCode(), request.zipCode(), FIELD_STATE_CODE, FIELD_ZIP_CODE)
                    .fieldErrors());
        }

        if (errors.isEmpty()) {
            return;
        }

        // WHY : Assumptions: the field names are de-duplicated while KEEPING first-seen order. The
        //       cross-field check names the state a second time when the pairing fails, and a repeated
        //       name would render the same field twice in the error array, which a client binding
        //       errors to inputs would show as two markers on one control.
        Set<String> offending = new LinkedHashSet<>();
        for (ApiError.FieldError error : errors) {
            offending.add(error.field());
        }
        ApiError.FieldError first = errors.get(0);
        throw new ClientInputException(
                ApiError.CODE_VALIDATION, List.copyOf(offending), first.state(), first.message());
    }

    /**
     * Loads the account master row for update.
     *
     * @param accountId the account to load
     * @return the managed row, never {@code null}
     * @throws NoSuchElementException if no such account exists
     */
    private Account loadAccount(long accountId) {
        return this.accounts.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "no account master row exists for account " + accountId));
    }

    /**
     * Loads the account's customer master row for update, through the by-account cross-reference path.
     *
     * @param accountId the account whose customer is required
     * @return the managed row, never {@code null}
     * @throws NoSuchElementException if the account has no cross-reference row, or the row names a
     *     customer the customer master does not hold
     */
    private Customer loadCustomer(long accountId) {
        return this.crossReferences.findByAccountIdOrderByCardNumAsc(accountId).stream()
                .findFirst()
                .map(row -> row.getCustomerId())
                .flatMap(this.customers::findById)
                .orElseThrow(() -> new NoSuchElementException(
                        "no customer could be resolved for account " + accountId));
    }

    /**
     * Refuses a submission whose precondition does not name the state now stored.
     *
     * <p>Assumptions: a blank precondition is refused rather than treated as "no opinion". Treating it as
     * permission would make the check opt-in, and a caller that simply omitted the header would get the
     * silent-overwrite behaviour the check exists to remove.</p>
     *
     * <p>Trade-offs: the refusal carries the reference's own verbatim message rather than a message
     * describing revisions, and the reason is that this outcome IS the reference's outcome -- the record
     * changed under the submitter. Naming revisions instead would publish the mechanism and lose the text
     * the screen has always shown.</p>
     *
     * @param account the loaded account; must not be {@code null}
     * @param customer the loaded customer; must not be {@code null}
     * @param expectedRevision the caller's precondition
     * @throws RecordConflictException if the precondition is blank or does not match
     */
    private static void requireCurrentRevision(Account account, Customer customer,
            String expectedRevision) {
        if (expectedRevision.isBlank() || !revisionOf(account, customer).equals(expectedRevision)) {
            // WHY : Assumptions: the stale-version kind is raised and NO message is composed here. The
            //       shared advice selects the sentence from the kind, which is what keeps every
            //       user-visible string in one place; composing one here would create a second sentence
            //       for one condition with no rule for choosing between them.
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                    customer.getVersion());
        }
    }

    /**
     * Renders the revision token for a loaded pair of rows.
     *
     * @param account the loaded account; must not be {@code null}
     * @param customer the loaded customer; must not be {@code null}
     * @return the opaque token, never {@code null}
     */
    private static String revisionOf(Account account, Customer customer) {
        return account.getVersion() + REVISION_SEPARATOR + customer.getVersion();
    }
}
