package com.carddemo.reporting.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.domain.StatementTransactionView;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.mapper.ReportingDtoMapper;
import com.carddemo.reporting.repository.StatementAccountRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository;
import com.carddemo.reporting.repository.StatementCustomerRepository;
import com.carddemo.reporting.repository.StatementTransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Describes one cardholder statement: who it is for, what it totals, and where its two artifacts are.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} together with its file-handling subroutine
 * {@code app/cbl/CBSTM03B.CBL} produces two artifacts per card, a plain-text statement and a markup
 * one, at the two declared lengths {@code app/jcl/CREASTMT.JCL} L89 and L94 fix. Those artifacts are
 * <b>written by the nightly task</b> into an object store, which is what replaces the two output data
 * sets. This service does not write them: it reads the same relations the generator reads, reports what
 * the statement covers, and names the two stored artifacts so a caller can collect them. Splitting the
 * two responsibilities is what keeps a request-time read from producing a document that would then
 * disagree with the one the nightly run produced.
 *
 * <h2>Assumptions: the read follows the reference's own order and nothing is guessed</h2>
 *
 * <p>The generator resolves a card through four reads in a fixed order: the cross-reference walk at
 * {@code 1000-XREFFILE-GET-NEXT.} L345, then the customer at {@code 2000-CUSTFILE-GET.} L368, then the
 * account at {@code 3000-ACCTFILE-GET.} L392, and then that card's transactions. This service issues the
 * same four reads through the four statement repository roles, in the same order, so a failure occurs at
 * the same point in the sequence and names the same relation.
 *
 * <h2>Assumptions: a request carries a card number and this service masks it before reading</h2>
 *
 * <p>Every relation in the reporting schema presents a card number masked to twelve asterisks and its
 * last four digits, so a stored number cannot be matched against them directly. This service masks the
 * requested number through the mapper that owns masking for this context and reads on the masked form.
 * Alternatives Considered: reading on the stored number. Rejected because the privilege does not exist:
 * the login role this module authenticates as is granted the views alone and every one of them masks.
 *
 * <p>Assumptions: a masked value is not a card identity, so the fingerprint decides which card a
 * statement is for. Two cards sharing a last-four mask identically. The transaction relation projects a
 * per-card grouping token beside the masked number, and this service groups on that token: if the
 * transactions returned for one masked number carry more than one token, two cards have collided and the
 * request is <b>refused</b> rather than answered. Trade-offs: that refusal denies a statement to two
 * genuine cardholders until the request distinguishes them, and it is accepted because the alternative
 * is a statement whose totals are the sum of two cards' activity, which is wrong for both of them and
 * says so nowhere.
 *
 * <h2>Assumptions: the two artifact locations are derived from a convention and never stored</h2>
 *
 * <p>The bucket and the two key prefixes are configuration, and the key within the prefix is derived
 * from the account identifier and the card's masked tail. Deriving rather than storing means no
 * relation has to be written to record where an artifact went, which matters because this context holds
 * no writable relation at all; it also means a caller can construct the same location independently,
 * which is what the baseline's fixed data-set names gave for free.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Service
public class StatementService {

    /**
     * Property key of the object store the two statement artifacts are written to.
     */
    public static final String OUTPUT_BUCKET_PROPERTY = "carddemo.reporting.s3.output-bucket";

    /**
     * Property key of the key prefix the statement artifacts sit under.
     */
    public static final String STATEMENT_PREFIX_PROPERTY = "carddemo.reporting.s3.statement-prefix";

    /**
     * Maximum number of one card's transactions a single request will total.
     *
     * <p>Trade-offs: the read is bounded, and the bound is a decision rather than a default. The
     * baseline's statement generator holds its transactions in a fixed working-storage table and
     * overruns it without a bound check -- the existing COBOL suite records the measured thresholds --
     * so an unbounded read here would be less faithful rather than more. The bound is set well above
     * the baseline's measured same-card threshold so that every statement the reference can render is
     * one this service can total, and a card above it is refused rather than totalled short.</p>
     */
    public static final int MAX_STATEMENT_TRANSACTIONS = 2_000;

    /**
     * File name suffix of the plain-text artifact.
     *
     * <p>Assumptions: the two suffixes distinguish the two artifacts within one key prefix, which is
     * what {@code app/jcl/CREASTMT.JCL} does with two separate data-set names at L87 and L92. They are
     * not read from configuration because they are part of the key convention rather than of the
     * deployment, and a configurable suffix would let two environments disagree about where an artifact
     * a caller already holds a link to can be found.</p>
     */
    public static final String PLAIN_TEXT_SUFFIX = ".txt";

    /**
     * File name suffix of the markup artifact.
     */
    public static final String HTML_SUFFIX = ".html";

    /**
     * The card-ordered transaction traversal.
     */
    private final StatementTransactionRepository transactions;

    /**
     * The sequential cross-reference traversal, used here for its keyed lookup.
     */
    private final StatementCardXrefRepository cardXrefs;

    /**
     * The keyed customer lookup.
     */
    private final StatementCustomerRepository customers;

    /**
     * The keyed account lookup.
     */
    private final StatementAccountRepository accounts;

    /**
     * The clock the generation timestamp is read from.
     */
    private final Clock clock;

    /**
     * The object store the two statement artifacts are written to.
     */
    private final String outputBucket;

    /**
     * The key prefix the statement artifacts sit under.
     */
    private final String statementPrefix;

    /**
     * Records the collaborators and the artifact location this service composes from.
     *
     * <p>Assumptions: the clock is injected rather than read from the system, so the generation
     * timestamp a response carries is assertable. That is the same discipline the baseline applies when
     * it injects a business date as a job parameter at {@code app/jcl/INTCALC.jcl} L22 rather than
     * reading one from a clock.</p>
     *
     * @param transactions the card-ordered transaction traversal; must not be {@code null}
     * @param cardXrefs the cross-reference role; must not be {@code null}
     * @param customers the keyed customer lookup; must not be {@code null}
     * @param accounts the keyed account lookup; must not be {@code null}
     * @param clock the clock the generation timestamp is read from; must not be {@code null}
     * @param outputBucket the object store the artifacts are written to, bound from
     *     {@value #OUTPUT_BUCKET_PROPERTY}
     * @param statementPrefix the key prefix the artifacts sit under, bound from
     *     {@value #STATEMENT_PREFIX_PROPERTY}
     */
    public StatementService(
            StatementTransactionRepository transactions,
            StatementCardXrefRepository cardXrefs,
            StatementCustomerRepository customers,
            StatementAccountRepository accounts,
            Clock clock,
            @Value("${" + OUTPUT_BUCKET_PROPERTY + "}") String outputBucket,
            @Value("${" + STATEMENT_PREFIX_PROPERTY + "}") String statementPrefix) {
        this.transactions = transactions;
        this.cardXrefs = cardXrefs;
        this.customers = customers;
        this.accounts = accounts;
        this.clock = clock;
        this.outputBucket = outputBucket;
        this.statementPrefix = statementPrefix;
    }

    /**
     * Describes the statement for one requested card.
     *
     * <p>Assumptions: the read runs in a read-only transaction so that the four reads see one snapshot.
     * Without it a statement could name a customer read before a concurrent correction and an account
     * balance read after it, and the two would describe different states of one cardholder.</p>
     *
     * @param request the statement request, carrying the card number and optionally the account it is
     *     expected to belong to; must not be {@code null}
     * @return a description of that card's statement, naming both stored artifacts; never {@code null}
     * @throws ClientInputException if the request carries no card number, or if the requested account
     *     is not the account the cross-reference names for that card, or if two cards collide under one
     *     masked rendering
     * @throws NoSuchElementException if the cross-reference, the customer or the account has no row for
     *     the requested card, which is the target's equivalent of the reference abending at
     *     {@code 9999-ABEND-PROGRAM.} L921 on the two reads that carry no end-of-file arm
     */
    @Transactional(readOnly = true)
    public StatementResponse describe(StatementRequest request) {
        return compose(request).statement();
    }

    /**
     * Assembles one card's statement as a whole document: the heading figures and every transaction
     * the statement lists.
     *
     * <p>Refactoring Rationale: this is the single implementation and {@link #describe(StatementRequest)}
     * delegates to it, rather than the two reading independently. The heading total is a sum over the
     * very rows the document lists, so two implementations would compute one figure twice from two
     * reads and could disagree if a row landed between them -- a statement whose stated total did not
     * match its own lines, with nothing to detect it.
     *
     * <p>Trade-offs: a caller wanting only the heading and the two artifact locations therefore pays
     * for the transaction read it will discard, bounded at {@link #MAX_STATEMENT_TRANSACTIONS}. The
     * alternative was a second read path computing the total by aggregate, which would have removed
     * that cost and reintroduced the disagreement it exists to prevent.
     *
     * @param request the card whose statement is wanted, and optionally the account it is expected to
     *     belong to; must not be {@code null}
     * @return that card's statement document; never {@code null}
     * @throws ClientInputException if the request carries no card number, or if the requested account
     *     is not the account the cross-reference names for that card, or if two cards collide under one
     *     masked rendering
     * @throws NoSuchElementException if the cross-reference, the customer or the account has no row for
     *     the requested card, which is the target's equivalent of the reference abending at
     *     {@code 9999-ABEND-PROGRAM.} L921 on the two reads that carry no end-of-file arm
     */
    @Transactional(readOnly = true)
    public StatementDocument compose(StatementRequest request) {
        String requestedCard = request.cardNumber();
        if (requestedCard == null || requestedCard.isBlank()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "cardNumber",
                    "cardNumber must be supplied");
        }

        // WHY : Assumptions: the masking is delegated to the mapper that owns it for this context
        //       rather than performed here. The module charter names that mapper as the sole boundary
        //       where a primary account number may be narrowed, and a second narrowing rule in this
        //       service would give one value two renderings -- so a lookup could mask one way while a
        //       response masked the other and the two would silently fail to match.
        // WHY : Assumptions: the operation is idempotent, which is what makes it safe to apply to a
        //       value a caller may already have narrowed. Its own contract records that applying it to
        //       the full number and to the canonical masked form yields the same sixteen characters, so
        //       a caller passing either reaches the same relation row.
        String maskedCard = ReportingDtoMapper.maskPrimaryAccountNumber(requestedCard);

        CardXrefView xref = cardXrefs.findByCardNum(maskedCard)
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row for the requested card"));
        requireExpectedAccount(request, xref);

        CustomerView customer = customers.findByCustomerId(xref.getCustomerId())
                .orElseThrow(() -> new NoSuchElementException(
                        "no customer row for customer " + xref.getCustomerId()));
        AccountView account = accounts.findByAccountId(xref.getAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "no account row for account " + xref.getAccountId()));

        // WHY : Assumptions: the read is keyed on the masked rendering rather than on the whole
        //       relation, because a card later in the card-first ordering would otherwise fall outside
        //       the first page and the response would report a statement with no activity -- which a
        //       reader cannot tell from a genuine one. The repository records the same reasoning at
        //       the method it belongs to.
        List<StatementTransactionView> forCard =
                transactions.findByKeyCardNumberOrderByKeyTransactionIdAsc(
                        maskedCard, Limit.of(MAX_STATEMENT_TRANSACTIONS));
        requireSingleCard(forCard);

        Money total = Money.ZERO;
        for (StatementTransactionView row : forCard) {
            total = total.plus(row.amount());
        }

        StatementResponse heading = new StatementResponse(
                maskedCard,
                String.valueOf(account.getAccountId()),
                assembledName(customer),
                total,
                forCard.size(),
                artifactUri(account.getAccountId(), maskedCard, PLAIN_TEXT_SUFFIX),
                artifactUri(account.getAccountId(), maskedCard, HTML_SUFFIX),
                TimestampFormatter.formatNow(clock));

        return new StatementDocument(heading, forCard.stream()
                .map(row -> toResponse(maskedCard, row))
                .toList());
    }

    /**
     * Renders one projected transaction row as the statement line a caller receives.
     *
     * <p>Assumptions: the masked card number is passed in rather than read from the row's own key.
     * The two are equal -- the projection already narrows the number, which is why this module can
     * read on it at all -- and passing the value the request resolved makes that equality the caller's
     * single source rather than a coincidence each row is trusted to reproduce.
     *
     * <p>Assumptions: the merchant identifier is rendered as a digit string and not as a JSON number,
     * on the same rule every identifier in this migration follows: a JSON number is parsed into
     * IEEE-754 binary floating point by most clients and leading zeros are lost, so an identifier
     * crossing a boundary as a number can come back a different identifier.
     *
     * @param maskedCard the narrowed primary account number the request resolved
     * @param row the projected transaction row
     * @return the statement line for that row
     * @throws IllegalArgumentException if the row's own components fall outside the response contract
     */
    private static StatementTransactionResponse toResponse(
            String maskedCard, StatementTransactionView row) {
        return new StatementTransactionResponse(
                maskedCard,
                row.key().transactionId(),
                row.typeCode(),
                row.categoryCode(),
                row.source(),
                row.description(),
                row.amount(),
                row.merchantId() == null ? null : String.valueOf(row.merchantId()),
                row.merchantName(),
                row.merchantCity(),
                row.merchantPostalCode(),
                stamp(row.originatingTimestamp()),
                stamp(row.processingTimestamp()));
    }

    /**
     * Renders an instant as the baseline-compatible twenty-six-character stamp, preserving absence.
     *
     * <p>Assumptions: {@code null} is carried through rather than replaced with a formatted epoch or
     * with blanks. The statement record declares both stamps as opaque character fields and admits an
     * all-blank value, so an absent stamp is a state the reference has; substituting any reading would
     * assert a time nothing recorded.
     *
     * @param value the instant to render, or {@code null}
     * @return the twenty-six-character rendering, or {@code null} when {@code value} is {@code null}
     */
    private static String stamp(LocalDateTime value) {
        return value == null ? null : TimestampFormatter.format(value);
    }

    /**
     * Refuses a request whose stated account is not the account the cross-reference names.
     *
     * <p>Assumptions: the account on the request is optional and is treated as an assertion rather than
     * as a selector, because the cross-reference is what resolves a card to an account and a request
     * cannot override it. Checking it is nonetheless worth doing: a caller that holds a stale
     * card-to-account association would otherwise receive a statement correctly attributed to a
     * different account and would attribute it to the one it asked about.</p>
     *
     * @param request the statement request
     * @param xref the cross-reference row resolved for the requested card
     * @throws ClientInputException if the request states an account other than the resolved one
     */
    private static void requireExpectedAccount(StatementRequest request, CardXrefView xref) {
        String stated = request.accountId();
        if (stated == null || stated.isBlank()) {
            return;
        }
        if (Long.parseLong(stated) != xref.getAccountId()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "accountId",
                    "the requested card resolves to a different account than the one stated");
        }
    }

    /**
     * Refuses a masked collision, in which two distinct cards render to one masked form.
     *
     * <p>Assumptions: an empty result is legitimate and is not a collision. A card with no activity in
     * the period has no transactions and therefore no token, and the reference produces a statement for
     * it -- the cross-reference walk drives one statement per card regardless of activity -- so an empty
     * list passes rather than refusing.</p>
     *
     * <p>Trade-offs: this checks and discards rather than returning the shared token. Nothing downstream
     * needs the token -- the artifact keys are derived from the account identifier and the visible tail,
     * and the totals are summed from the rows themselves -- so returning it would publish a value derived
     * from a whole primary account number to a caller with no use for it.</p>
     *
     * @param rows the transactions returned for one masked card number
     * @throws ClientInputException if the rows carry more than one grouping token, which means two
     *     distinct cards mask to one rendering
     */
    private static void requireSingleCard(List<StatementTransactionView> rows) {
        Set<String> tokens = rows.stream()
                .map(StatementTransactionView::cardFingerprint)
                .collect(Collectors.toUnmodifiableSet());
        if (tokens.size() > 1) {
            // WHY : Assumptions: the message states how many cards collided and names neither the
            //       masked rendering nor any token. The count is what a caller needs to understand the
            //       refusal; the rendering carries four digits of a primary account number and a token
            //       is derived from the whole of one, so neither belongs in a message that may be
            //       logged.
            throw new ClientInputException(ApiError.CODE_VALIDATION, "cardNumber",
                    "the requested card number masks to a rendering shared by " + tokens.size()
                            + " distinct cards, so a statement cannot be attributed");
        }
    }

    /**
     * Assembles the customer name the statement heads with.
     *
     * <p>Assumptions: the three parts are joined with one blank literal after each, unconditionally,
     * which is what {@code app/cbl/CBSTM03A.CBL} L462-L469 does. An absent middle name therefore leaves
     * a blank <b>pair</b> in the assembled name, and that pair is load-bearing: the markup rendering cuts
     * at it while the plain-text rendering does not, so the two artifacts show different names for such
     * a customer. That is registered as {@code D-STMT-PAIRED-BLANK-NAME} in
     * {@code docs/architecture/cobol-to-service-traceability.md} and is reproduced here rather than
     * reconciled, because collapsing the pair would change the bytes of the first line of every
     * plain-text statement.</p>
     *
     * @param customer the customer row resolved for the requested card
     * @return the assembled name, with one blank after each of the three parts and any absent part
     *     contributing only its blank
     */
    private static String assembledName(CustomerView customer) {
        String middle = customer.getMiddleName() == null ? "" : customer.getMiddleName();
        return customer.getFirstName() + " " + middle + " " + customer.getLastName();
    }

    /**
     * Builds the location of one stored statement artifact.
     *
     * <p>Assumptions: the key is derived from the account identifier and the masked card's last four
     * digits rather than from the stored card number, so no primary account number appears in an object
     * key. An object key is written to an access log by the store itself and by every intermediary that
     * serves it, which is the same exposure the card context's addressing decision turns on.</p>
     *
     * @param accountId the account the statement is attributed to
     * @param maskedCard the masked rendering of the card the statement is for
     * @param suffix the artifact suffix, either {@value #PLAIN_TEXT_SUFFIX} or {@value #HTML_SUFFIX}
     * @return the artifact location, never {@code null}
     */
    private String artifactUri(Long accountId, String maskedCard, String suffix) {
        String tail = maskedCard.substring(maskedCard.length() - 4);
        return "s3://" + outputBucket + "/" + statementPrefix + accountId + "-" + tail + suffix;
    }
}
