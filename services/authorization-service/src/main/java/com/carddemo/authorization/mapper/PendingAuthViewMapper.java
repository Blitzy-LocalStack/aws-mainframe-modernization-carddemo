package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryView;
import com.carddemo.authorization.service.AccountContextClient;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The anti-corruption layer between the persistent authorization rows and the HTTP bodies this context
 * publishes.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Refactoring Rationale: {@code src/main/resources/openapi/authorization-api.yaml} is the contract of
 * record for this context's HTTP boundary, and until this class existed there was no code path from the two
 * JPA entities to the bodies that contract publishes. A contract with no adapter is a contract nothing can
 * serve: the shapes differed in every dimension that matters -- the entities expose numeric identifiers
 * where the contract publishes fixed-width digit strings, {@code BigDecimal} where the contract publishes
 * fixed-point strings, a full primary account number where the contract publishes a masked one, a
 * three-part composite key where the contract publishes one sealed selector, and a stored response code
 * where the list contract publishes a derived approval character. Each of those five is a decision, and
 * this class is the one place they are made.
 *
 * <p>Assumptions: every one of those conversions belongs at this boundary and nowhere else. An entity that
 * masked its own card number could not serve the reply encoder, which must echo all sixteen digits an
 * acquirer sent; an entity that returned {@link Money} would put a presentation type in the persistence
 * layer; and a repository that sealed its own cursors would need the signing key.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>Assumptions: nothing here composes a value for display. The reference programs assemble the card
 * expiry around a solidus, the response reason around a separator and the fraud mark around its report
 * date on the way to the terminal; those compositions are the browser client's, and the records this class
 * builds carry the stored values instead. The module's {@code PendingAuthDetailResponse} and
 * {@code PendingAuthSummaryResponse} records are the record of what the terminal displayed -- the
 * specification the UI screens implement and the module's own tests assert -- and are not HTTP bodies, so
 * this class does not build them.
 *
 * <p>Trade-offs: this class holds no repository and performs no query, so it cannot decide what a page
 * contains. It converts what it is handed. The cost is that a caller must read the rows and settle the
 * cursor boundaries before calling {@link #toListView}; what it buys is that every conversion here is a
 * pure function of its arguments and is therefore testable without a database.
 *
 * <h2>Why this is not a component</h2>
 *
 * <p>Refactoring Rationale: this class carries no stereotype annotation and is constructed by whichever
 * component owns the cursor sealer, which is the opposite of the module's {@code AuthorizationMessageMapper}
 * and is deliberate. That mapper depends on the validation engine, which the framework supplies to every
 * application unconditionally; this one depends on {@link CursorToken}, which holds signing key material and
 * is therefore not a bean anywhere in this migration -- no service publishes one, and none may, because a
 * default signing key in a configuration file is a committed secret. Annotating this class would make an
 * application context fail to start on a missing dependency, which is a strictly worse state than the one
 * this fix set out to leave. Alternatives Considered: publishing a sealer bean here alongside the mapper.
 * Rejected because the key must then be resolved from configuration in every profile, and the decision about
 * where a signing key comes from belongs with the component that first needs one rather than with a
 * converter.</p>
 */
public class PendingAuthViewMapper {

    /**
     * The query name that every authorization row selector's binding is composed from.
     *
     * <p>Assumptions: the binding is authenticated into the token but not carried by it, so a token sealed
     * under one binding cannot be presented to a query that opens under another. The value names the
     * resource rather than the operation, because the same selector addresses a row on the list, the read
     * and the fraud operations, and three different query names would make a selector taken from a list
     * unusable on the other two.</p>
     *
     * <p>Refactoring Rationale: this was the WHOLE binding until the subject was folded in, and a bare
     * query name is not a sufficient binding. A selector sealed under it authenticated the resource and
     * nothing else, so a selector one authorized operator obtained from their own list opened for every
     * other authorized operator -- and the published cursor contract at
     * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} states that a
     * token is bound to the subject it was issued for, which that arrangement made untrue. The name is
     * retained as ONE PART of the binding and the composition moved to
     * {@link CursorToken#binding(String, String, String)}, which takes the three parts separately so that
     * a caller cannot pass a name where a binding is required.</p>
     *
     * <p>Refactoring Rationale: it stays a constant rather than a literal at each call site, because a
     * binding that differs by one character between sealing and opening fails authentication and presents
     * as a rejected cursor rather than as a mismatch -- a failure mode that is expensive to diagnose and
     * trivial to prevent.</p>
     */
    public static final String CURSOR_QUERY_NAME = "authorization-row";

    /**
     * The character separating the three parts of a row's raw cursor key.
     *
     * <p>Assumptions: a colon cannot occur inside any of the three parts, all three being decimal digit
     * strings, so the composition is unambiguously reversible. This is why the parts are joined rather
     * than fixed-width padded: padding would have to agree with a width nothing else in the target
     * declares.</p>
     */
    public static final char KEY_PART_SEPARATOR = ':';

    /**
     * The number of parts a row selector's payload carries: the account, the date and the time.
     *
     * <p>Assumptions: three, because that is the arity of the composite key at migration L561 and
     * therefore the arity {@link #sealKey(PendingAuthDetailKey)} writes. It is published as a constant so
     * the sealer and {@link #openKey(String, String)} cannot disagree about it, which is the one way a token this
     * service issued could be refused by this service.</p>
     */
    public static final int KEY_PART_COUNT = 3;

    /**
     * The contract field name a refused PATH selector is keyed by.
     *
     * <p>Assumptions: {@code key} is one of the six per-field keys the shared 400 response of
     * {@code src/main/resources/openapi/authorization-api.yaml} declares its handlers can emit, named
     * there as "the selector in the path of the detail and fraud operations". It is a compiled constant so
     * the emitted key and the published set cannot drift apart silently.</p>
     */
    public static final String SELECTOR_FIELD = "key";

    /**
     * The contract field name a refused PAGING cursor is keyed by.
     *
     * <p>Assumptions: {@code cursor} is a different member of that same published set, named there as
     * "the paging token of the list operation". The two names are kept distinct because the two values
     * arrive in different places -- a path segment and a query parameter -- and a client can only correct
     * the one it actually sent.</p>
     */
    public static final String CURSOR_FIELD = "cursor";

    /**
     * The stable token operational tooling matches a refused sealed value on.
     *
     * <p>Assumptions: this is deliberately NOT the response code a client reads. The response code is the
     * same value for every refused input, so it distinguishes nothing in a log; this one names the
     * internal cause a first responder greps for.</p>
     */
    public static final String SELECTOR_REFUSAL_CODE = "AUTH_SELECTOR_REFUSED";

    /**
     * The response code that means the authorization was approved.
     *
     * <p>Assumptions: read from the literal tested at {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}
     * L536, and the same value the detail screen tests at {@code cbl/COPAUS1C.cbl} L311.</p>
     */
    public static final String RESPONSE_CODE_APPROVED = "00";

    /**
     * The number of characters the published point-of-sale entry mode occupies.
     *
     * <p>Assumptions: two, from {@code PA-POS-ENTRY-MODE PIC 9(02)} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L38 and the two-character wire field at
     * {@code cpy/CCPAURQY.cpy} L30. The width is declared here rather than inlined because the response
     * schema publishes the same number as an exact two-digit pattern, and a reader checking the two
     * against each other should find one named source on this side.</p>
     */
    public static final int POS_ENTRY_MODE_WIDTH = 2;

    /**
     * Seals row selectors and is the only collaborator this mapper holds.
     */
    private final CursorToken cursorToken;

    /**
     * Builds a mapper over the application's cursor sealer.
     *
     * <p>Refactoring Rationale: the sealer is injected rather than constructed here, because it holds the
     * signing key and its lifetime, both of which are configuration this class has no business reading. It
     * is also the same instance the repository-side cursor opener uses, which is what guarantees that a
     * token this mapper issues is one that opener accepts.</p>
     *
     * @param cursorToken the application's cursor sealer, never {@code null}
     * @throws NullPointerException if {@code cursorToken} is {@code null}
     */
    public PendingAuthViewMapper(CursorToken cursorToken) {
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken must not be null");
    }

    /**
     * Converts one summary row into the account-level block the list body carries.
     *
     * <p>Assumptions: the two identifiers are zero-padded to their declared widths rather than rendered as
     * the shortest digit string. The columns are {@code BIGINT} and an integer column drops a leading zero,
     * but the contract publishes both as fixed-width digit strings because the reference fields are
     * {@code PIC S9(11)} and {@code PIC S9(09)} and an account identifier with a leading zero is a value
     * they admit. Padding here is therefore restoring the declared form, not decorating it.</p>
     *
     * @param summary the persistent summary row, never {@code null}
     * @param customer the customer display fields the account context resolved, or {@code null} when it
     *     resolved none; the four screen fields are published as {@code null} in that case
     * @return the summary block in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code summary} is {@code null}, or if any column the contract marks
     *     required is unset on it
     */
    public PendingAuthSummaryView toSummaryView(PendingAuthSummary summary,
            AccountContextClient.CustomerDisplay customer) {
        Objects.requireNonNull(summary, "summary must not be null");
        return new PendingAuthSummaryView(
                pad(summary.getAccountId(), PendingAuthSummaryView.ACCOUNT_ID_WIDTH, "accountId"),
                pad(summary.getCustomerId(), PendingAuthSummaryView.CUSTOMER_ID_WIDTH, "customerId"),
                summary.getAuthStatus(),
                summary.getAccountStatus1(),
                summary.getAccountStatus2(),
                summary.getAccountStatus3(),
                summary.getAccountStatus4(),
                summary.getAccountStatus5(),
                money(summary.getCreditLimit()),
                money(summary.getCashLimit()),
                money(summary.getCreditBalance()),
                money(summary.getCashBalance()),
                summary.getApprovedAuthCount(),
                summary.getDeclinedAuthCount(),
                money(summary.getApprovedAuthAmount()),
                money(summary.getDeclinedAuthAmount()),
                // WHY : Assumptions: the four display fields are passed straight through and are NOT
                //       padded to their map widths here, unlike the two identifiers above. The widths in
                //       the screen record are a rendering contract the screen response applies; padding
                //       them at this layer would put trailing blanks into a JSON string that a client
                //       other than the screen would then have to trim.
                //       Assumptions: an ABSENT customer becomes four nulls rather than four blanks,
                //       because null is the one value that distinguishes "not resolved" from "resolved
                //       and empty" at this layer -- the screen collapses both to blanks, but a diagnostic
                //       reading this record can still tell which happened.
                customer == null ? null : customer.customerName(),
                customer == null ? null : customer.addressLine1(),
                customer == null ? null : customer.addressLine2(),
                customer == null ? null : customer.phoneNumber1());
    }

    /**
     * Converts one authorization row into the list item the page carries.
     *
     * <p>Assumptions: the amount is the APPROVED amount and not the requested one.
     * {@code cbl/COPAUS0C.cbl} L525 moves {@code PA-APPROVED-AMT} into the edited work field its five row
     * moves then use, so the approved amount is what the reference list displayed. The two differ on a
     * decline, where the approved amount is zero.</p>
     *
     * @param detail the persistent authorization row, never {@code null}
     * @param subject the authenticated principal the row's selector is issued to, which for a bearer-token
     *     caller is the token's subject claim; must not be {@code null} or blank, because a selector bound
     *     to no subject opens for every other authorized operator
     * @return the list item in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code detail} or {@code subject} is {@code null}, or if any column
     *     the contract marks required is unset on it
     * @throws IllegalArgumentException if {@code subject} is blank, or if the row's match status is outside
     *     its four-value domain, which the item's own constructor refuses
     */
    public PendingAuthRowView toRowView(PendingAuthDetail detail, String subject) {
        return rowViewBoundTo(detail, cursorBinding(subject));
    }

    /**
     * Converts one authorization row into a list item whose selector is sealed under an already-composed
     * binding.
     *
     * <p>Refactoring Rationale: this exists so that a page composes its binding ONCE and every row on it is
     * sealed under the identical value. The public entry point above composes per row, which is correct for
     * a single row and wasteful for a page; splitting the two keeps the public surface expressed in the
     * subject a caller actually holds while letting the page avoid recomposing a constant.</p>
     *
     * @param detail the persistent authorization row, never {@code null}
     * @param binding the composed cursor binding the selector is sealed under, as produced by
     *     {@link #cursorBinding(String)}, never {@code null} or blank
     * @return the list item in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code detail} is {@code null}, or if any column the contract marks
     *     required is unset on it
     * @throws IllegalArgumentException if the row's match status is outside its four-value domain, which
     *     the item's own constructor refuses
     */
    private PendingAuthRowView rowViewBoundTo(PendingAuthDetail detail, String binding) {
        Objects.requireNonNull(detail, "detail must not be null");
        return new PendingAuthRowView(
                sealKey(detail.getId(), binding),
                detail.getTransactionId(),
                detail.getAuthOrigDate(),
                detail.getAuthOrigTime(),
                detail.getAuthType(),
                approvalStatusOf(detail.getAuthRespCode()),
                detail.getMatchStatus(),
                money(detail.getApprovedAmount()),
                CardNumberMasker.mask(detail.getCardNum()));
    }

    /**
     * Converts one authorization row into the full detail body.
     *
     * <p>Assumptions: the point-of-sale entry mode is rendered as the digits of its stored small integer
     * rather than as a JSON number, because the reference field is characters. An absent mode stays absent
     * rather than becoming a zero, a zero being a valid entry mode in its own right.</p>
     *
     * <p>Refactoring Rationale: the mode is rendered at a FIXED WIDTH OF TWO with the leading zero
     * restored, where an earlier revision emitted {@code String.valueOf} and so published {@code "5"} for
     * a stored 5. {@code PA-POS-ENTRY-MODE} is {@code PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38 and the
     * wire field at {@code cpy/CCPAURQY.cpy} L30 is two characters, so the two digits ARE the field and
     * the leading zero is part of it, not formatting. The narrowest spelling of the number lost that
     * zero, which broke the fixed-width contract for every single-digit mode -- a client re-encoding the
     * value onto the wire would emit one character where the payload declares two, shifting every
     * following field. Padding here rather than in the client keeps the restoration at the one boundary
     * that knows the declared width.</p>
     *
     * @param detail the persistent authorization row, never {@code null}
     * @param subject the authenticated principal the body's selector is re-issued to, which for a
     *     bearer-token caller is the token's subject claim; must not be {@code null} or blank
     * @return the detail body in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code detail} or {@code subject} is {@code null}, or if any column
     *     the contract marks required is unset on it
     * @throws IllegalArgumentException if {@code subject} is blank, or if the row's match status is outside
     *     its four-value domain, which the body's own constructor refuses
     */
    public PendingAuthDetailView toDetailView(PendingAuthDetail detail, String subject) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key = Objects.requireNonNull(detail.getId(), "detail id must not be null");
        return new PendingAuthDetailView(
                sealKey(key, cursorBinding(subject)),
                pad(key.getAccountId(), PendingAuthSummaryView.ACCOUNT_ID_WIDTH, "accountId"),
                key.getAuthDate(),
                key.getAuthTime(),
                detail.getAuthOrigDate(),
                detail.getAuthOrigTime(),
                CardNumberMasker.mask(detail.getCardNum()),
                detail.getAuthType(),
                detail.getCardExpiryDate(),
                detail.getMessageType(),
                detail.getMessageSource(),
                detail.getAuthIdCode(),
                detail.getAuthRespCode(),
                detail.getAuthRespReason(),
                detail.getProcessingCode(),
                money(detail.getTransactionAmount()),
                money(detail.getApprovedAmount()),
                detail.getMerchantCategoryCode(),
                detail.getAcqrCountryCode(),
                posEntryMode(detail.getPosEntryMode()),
                detail.getMerchantId(),
                detail.getMerchantName(),
                detail.getMerchantCity(),
                detail.getMerchantState(),
                detail.getMerchantZip(),
                detail.getTransactionId(),
                detail.getMatchStatus(),
                detail.getAuthFraud(),
                detail.getFraudReportDate());
    }

    /**
     * Assembles the whole list body from a summary row, the rows on this page and the page's own state.
     *
     * <p>Assumptions: the caller has already decided which rows belong on the page and whether a further
     * page exists, because both are properties of the query it ran and neither can be recovered from the
     * rows alone. In particular a full page and a final page can each carry the same number of rows, so
     * {@code hasNext} is information only the caller holds.</p>
     *
     * <p>Refactoring Rationale: the two boundary tokens are sealed here from the first and last rows rather
     * than accepted as arguments, so that a page's cursors are always the identities of rows the caller
     * actually received. Accepting them would let a caller publish a boundary pointing at a row it filtered
     * away, which a client would then send back and receive rows it had already seen.</p>
     *
     * @param summary the persistent summary row for the account, never {@code null}
     * @param rows the authorization rows on this page in the order they are to be published, never
     *     {@code null} and never containing {@code null}
     * @param hasNext whether a further page follows this one, as the query that produced {@code rows}
     *     established
     * @param hasPrevious whether a page precedes the one being rendered, established by the caller
     *     that ran the read; it is not derived from the leading boundary token, which every page carrying
     *     rows supplies
     * @param screenMessage the navigation-boundary sentence for this request, or {@code null} when the
     *     request was not a paging move that had already reached a boundary
     * @param subject the authenticated principal this page and every selector on it are issued to, which
     *     for a bearer-token caller is the token's subject claim; must not be {@code null} or blank
     * @param customer the customer display fields the account context resolved for this summary, or
     *     {@code null} when it resolved none; read ONCE per page rather than per row, because every
     *     authorization beneath one summary belongs to the same account and so the same customer
     * @return the list body in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code summary}, {@code rows} or {@code subject} is {@code null}, or
     *     if {@code rows} contains {@code null}
     * @throws IllegalArgumentException if {@code subject} is blank, if {@code hasNext} is {@code true} while
     *     {@code rows} is empty, or if {@code screenMessage} is not one of the three reference sentences;
     *     the page envelope refuses the second and the body refuses the third
     */
    public PendingAuthListView toListView(PendingAuthSummary summary,
            List<PendingAuthDetail> rows, boolean hasNext, boolean hasPrevious, String screenMessage,
            String subject, AccountContextClient.CustomerDisplay customer) {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(rows, "rows must not be null");

        // Refactoring Rationale: the binding is composed once here and its refusal of a blank subject is
        //   therefore reached even on an EMPTY page, which seals nothing. Composing it only inside the
        //   sealing helper would accept a subject-less caller wherever the query matched no rows and refuse
        //   it everywhere else, which is a contract that holds by accident of the data.
        String binding = cursorBinding(subject);

        List<PendingAuthRowView> items = new ArrayList<>(rows.size());
        for (PendingAuthDetail row : rows) {
            items.add(rowViewBoundTo(Objects.requireNonNull(row, "rows must not contain null"), binding));
        }

        // WHY : Assumptions: an empty page carries no boundary tokens at all rather than carrying the
        //       tokens of the rows that would have been on it. There are no such rows, and the page
        //       envelope's own contract refuses a boundary without rows for exactly that reason.
        String firstKey = items.isEmpty() ? null : items.get(0).key();
        String lastKey = items.isEmpty() ? null : items.get(items.size() - 1).key();
        // WHY : Refactoring Rationale: backward availability is carried through rather than inferred from
        //       the leading boundary token, which every page carrying rows supplies. Only the caller that
        //       ran the read knows whether a row lies before the page, and the reference distinguishes the
        //       two cases explicitly -- its top-of-page sentence at L381 fires when nothing precedes.
        PageResponse<PendingAuthRowView> page = items.isEmpty()
                ? PageResponse.empty()
                : PageResponse.ofRows(items, firstKey, lastKey, hasNext, hasPrevious);
        return new PendingAuthListView(toSummaryView(summary, customer), page, screenMessage);
    }

    /**
     * Derives the one-character approval status the list publishes from the stored response code.
     *
     * <p>Assumptions: the reference test is an equality against one value with an unconditional
     * {@code ELSE} at {@code cbl/COPAUS0C.cbl} L536-L539, so every code other than the approved one --
     * including an absent code -- yields the declined character. Reproducing the {@code ELSE} rather than
     * enumerating the decline codes is what keeps a code this migration has not seen from producing no
     * character at all.</p>
     *
     * @param authRespCode the stored response code, which may be {@code null}
     * @return {@link PendingAuthRowView#APPROVAL_STATUS_APPROVED} when the code is the approved value and
     *     {@link PendingAuthRowView#APPROVAL_STATUS_DECLINED} otherwise, never {@code null}
     */
    public static String approvalStatusOf(String authRespCode) {
        return RESPONSE_CODE_APPROVED.equals(authRespCode)
                ? PendingAuthRowView.APPROVAL_STATUS_APPROVED
                : PendingAuthRowView.APPROVAL_STATUS_DECLINED;
    }

    /**
     * Recovers the three-part row identity a sealed selector stands for.
     *
     * <p><strong>Purpose.</strong> This is the exact inverse of the private sealer below, and it is public
     * because the selector arrives from a client while the seal is issued to one. Both halves live in this
     * class so the binding constant, the separator and the part order are stated once: a second opener
     * written against the same token elsewhere would be a second place any of those three could be got
     * wrong, and a mismatch would surface as an authentication failure on a token this service itself
     * issued.
     *
     * <p>Assumptions: the recovered account identifier is a SCOPE and not merely one third of a key. The
     * reference detail path never reaches a child occurrence without positioning on its parent first --
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L439 issues the parent read qualified on
     * the account and only its L465 reads the child -- so a caller acting on the row this selector names
     * is acting within the account the selector carries, and a service must not widen that to any account
     * the caller happens to name elsewhere in the same request.
     *
     * <p>Alternatives Considered: returning the three parts as a record of strings and leaving the caller
     * to build the key. Rejected because the middle and last parts are the DECODED date and time rather
     * than the nines complements the reference segment stores, and a caller assembling the key itself is a
     * caller that could reintroduce the complement; returning the key type directly leaves no assembly
     * step to get wrong.
     *
     * <p>Trade-offs: a selector whose payload is well sealed but malformed -- the wrong number of parts, or
     * a part that is not a number -- is refused with the same exception type as a selector that failed
     * authentication, so a client cannot tell the two apart. That is deliberate: this service is the only
     * issuer, so a sealed token with a malformed payload cannot arise from correct client behaviour, and
     * distinguishing the cases would describe the payload format to a caller the contract tells not to
     * parse it.
     *
     * @param selector the sealed selector as received from the client, taken from the {@code key} property
     *     of a list row; must not be {@code null}
     * @param subject the authenticated principal the sealed value was issued to, of type
     *     {@code String}; must not be {@code null} or blank, because a token bound to no subject is
     *     redeemable by every other authorized operator
     * @return the account identifier, decoded Julian authorization date and decoded millisecond
     *     authorization time the selector names, never {@code null}
     * @throws NullPointerException if {@code selector} is {@code null}
     * @throws InvalidSelectorException if the selector is not of the sealed shape, fails authentication,
     *     has expired, or carries a payload that is not three numeric parts separated by
     *     {@link #KEY_PART_SEPARATOR}
     */
    public PendingAuthDetailKey openKey(String selector, String subject) {
        return openSealed(selector, SELECTOR_FIELD, subject);
    }

    /**
     * Recovers the row identity a sealed PAGING cursor stands for.
     *
     * <p><strong>Purpose.</strong> Serve the list operation's {@code cursor} query parameter, which
     * carries the same sealed value as a row selector because a page boundary IS a row -- the list body's
     * two boundary tokens are taken from the first and last rows of the page it returns.
     *
     * <p>Refactoring Rationale: this differs from {@link #openKey(String, String)} in exactly one respect, the
     * name the refusal is keyed by, and it is a separate method rather than a parameter on that one so no
     * caller has to choose the key. The contract publishes a CLOSED set of per-field keys, and
     * {@code cursor} and {@code key} are two distinct members of it, so a malformed paging position keyed
     * {@code key} would ask a client to correct a parameter its request does not carry. Passing the name
     * in from a controller would put a contract field name in the layer the charter says holds no
     * representation concern, and would let the wrong one be passed.
     *
     * <p>Refactoring Rationale: the account the request scopes itself to is a PARAMETER here and is
     * compared against the account the cursor carries, because the two are supplied separately and can
     * disagree. The seal already stops a caller forging a position, but it does not stop a caller
     * REPLAYING a position this service legitimately issued for one account while naming a different one
     * in the scope parameter. Without the comparison the repository predicate would then be built from the
     * scope while the position came from elsewhere, answering a page of the scoped account positioned
     * inside a different one. The reference program has no equivalent check because its position and its
     * account both live in one structure it alone writes; a stateless boundary receives them from a
     * client, so the agreement has to be asserted.
     *
     * @param cursor the sealed paging position as received from the client, taken from the page
     *     envelope's {@code firstKey} or {@code lastKey}; must not be {@code null}
     * @param accountScope the account the request scoped itself to, which the cursor must agree with;
     *     must not be {@code null}
     * @param subject the authenticated principal the sealed value was issued to, of type
     *     {@code String}; must not be {@code null} or blank, because a token bound to no subject is
     *     redeemable by every other authorized operator
     * @return the account identifier, decoded Julian authorization date and decoded millisecond
     *     authorization time the cursor names, never {@code null}
     * @throws NullPointerException if {@code cursor} or {@code accountScope} is {@code null}
     * @throws InvalidSelectorException if the cursor is not of the sealed shape, fails authentication, has
     *     expired, carries a payload that is not three numeric parts, or names an account other than
     *     {@code accountScope}
     */
    public PendingAuthDetailKey openCursor(String cursor, Long accountScope, String subject) {
        Objects.requireNonNull(accountScope, "accountScope must not be null");
        PendingAuthDetailKey position = openSealed(cursor, CURSOR_FIELD, subject);
        if (!accountScope.equals(position.getAccountId())) {
            // WHY : Trade-offs: this is reported as a refused CURSOR rather than as a forbidden request,
            //       and the message names neither account. A 403 would tell a caller probing accounts that
            //       the position it replayed was valid somewhere, and naming either identifier would put
            //       one of them in a response body and a log line; a refused cursor is both true and
            //       uninformative to a prober, and the remedy -- take a fresh cursor from this account's
            //       own list -- is the same either way.
            throw new InvalidSelectorException(CURSOR_FIELD,
                    "sealed value was issued for a different account scope than this request states");
        }
        return position;
    }

    /**
     * Verifies one sealed token and parses its three-part payload, attributing any refusal to one field.
     *
     * @param token the sealed value as received from the client; must not be {@code null}
     * @param fieldKey the contract field name a refusal is keyed by, one of {@link #SELECTOR_FIELD} or
     *     {@link #CURSOR_FIELD}; must not be {@code null}
     * @param subject the authenticated principal the token was issued to; must not be {@code null} or
     *     blank
     * @return the three-part key the payload carries, never {@code null}
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws InvalidSelectorException if the token fails verification or its payload is not three
     *     numeric parts
     */
    private PendingAuthDetailKey openSealed(String token, String fieldKey, String subject) {
        Objects.requireNonNull(token, "token must not be null");

        String cursorKey;
        try {
            cursorKey = this.cursorToken.open(cursorBinding(subject), token);
        } catch (CursorToken.InvalidCursorException refused) {
            // WHY : Refactoring Rationale: the shared sealer's own refusal is RE-KEYED here rather than
            //       propagated. That refusal is keyed to the sealer's own field name, which is correct for
            //       every caller that opens a paging cursor and wrong for the two operations whose sealed
            //       value arrives in a PATH SEGMENT the contract names key. Re-keying at this one boundary
            //       is what keeps the emitted key inside the closed set the contract publishes, and the
            //       sealer's message is carried through unchanged because it already names only the shape,
            //       the length or the age of the token and never its characters.
            throw new InvalidSelectorException(fieldKey, refused.getMessage());
        }

        // WHY : Assumptions: the split is bounded at one MORE than the key's arity so that a payload
        //       carrying an extra part is detected instead of being silently folded into the last one. An
        //       unbounded split would drop trailing empty parts, and a split bounded at the arity itself
        //       would absorb any further separator into the final element, where it would then fail to
        //       parse as a number for a reason the message could not state accurately.
        String[] parts = token(cursorKey);
        if (parts.length != KEY_PART_COUNT) {
            throw new InvalidSelectorException(fieldKey,
                    "sealed value carries " + parts.length + " payload parts where a pending"
                            + " authorization identity carries " + KEY_PART_COUNT);
        }

        try {
            return new PendingAuthDetailKey(Long.valueOf(parts[0]), Integer.valueOf(parts[1]),
                    Integer.valueOf(parts[2]));
        } catch (IllegalArgumentException malformed) {
            // WHY : Refactoring Rationale: the catch is IllegalArgumentException where it was the
            //       narrower NumberFormatException, because the key type now enforces its own domain and
            //       raises the wider type for a part that parses as a number but is not a date or a time
            //       the column admits. Leaving the narrower catch would let that refusal escape as a 500
            //       from a value the client supplied, which is the one outcome this whole boundary exists
            //       to prevent; NumberFormatException is a subtype, so the parse failure still lands here.
            // WHY : Trade-offs: the refused text is NOT quoted, here or in the message above. The first
            //       part of this payload is an account identifier, and a diagnostic that echoed a payload
            //       it could not fully parse would put that identifier into an error body and a log line
            //       -- the one destination the masking this class performs does not reach. The expected
            //       ORDER is named instead, which is what a client debugging its own echoing needs and is
            //       the most this class can say without disclosing a value.
            throw new InvalidSelectorException(fieldKey,
                    "sealed value payload does not carry three numeric parts, each within the domain"
                            + " its column declares, in the order account identifier, authorization"
                            + " date, authorization time");
        }
    }

    /**
     * Splits one verified payload into its parts, bounded so an extra part stays visible.
     *
     * @param cursorKey the raw payload the sealer returned; must not be {@code null}
     * @return the payload's parts, one element longer than the key arity when the payload carried more
     *     parts than it should; never {@code null}
     */
    private static String[] token(String cursorKey) {
        return cursorKey.split(String.valueOf(KEY_PART_SEPARATOR), KEY_PART_COUNT + 1);
    }

    /**
     * Seals one row's three-part identity into the opaque selector a client sends back.
     *
     * <p>Assumptions: the three parts are joined in the physical key order -- account, then date, then time
     * -- because that is the order the composite key declares and the order a keyset predicate compares
     * them in. A different order here would still round-trip but would stop the opened key from being
     * usable directly in that predicate.</p>
     *
     * @param key the row's composite key, never {@code null}
     * @param binding the composed cursor binding the selector is sealed under, as produced by
     *     {@link #cursorBinding(String)}, never {@code null} or blank
     * @return the sealed selector, never {@code null}
     * @throws NullPointerException if {@code key} is {@code null} or any of its three parts is unset
     */
    private String sealKey(PendingAuthDetailKey key, String binding) {
        Objects.requireNonNull(key, "key must not be null");
        Long accountId = Objects.requireNonNull(key.getAccountId(), "key accountId must not be null");
        Integer authDate = Objects.requireNonNull(key.getAuthDate(), "key authDate must not be null");
        Integer authTime = Objects.requireNonNull(key.getAuthTime(), "key authTime must not be null");
        String cursorKey = accountId + String.valueOf(KEY_PART_SEPARATOR) + authDate
                + String.valueOf(KEY_PART_SEPARATOR) + authTime;
        return this.cursorToken.seal(binding, cursorKey);
    }

    /**
     * Composes the binding this context's row selectors are sealed and opened under.
     *
     * <p>Assumptions: the query scope is {@link CursorToken#SCOPE_NONE}. The one value that could look like
     * a narrowing predicate is the account whose authorizations are being listed, and it is not one: the
     * account is part of the row's own composite key and is therefore already inside the sealed payload, so
     * binding it as a scope as well would refuse a selector whose only difference from the sealed one was
     * that the client had moved on to a different account's list -- while adding nothing, because a selector
     * carrying account A's key cannot address a row of account B in the first place.</p>
     *
     * <p>Refactoring Rationale: this is exposed rather than private because the operations that OPEN a
     * selector -- the row read and the fraud mark -- must compose the identical binding, and the failure mode
     * of two sites composing it differently is an authentication failure that surfaces as a rejected cursor
     * rather than as a mismatch. Publishing the composer is what makes the two sites unable to disagree; the
     * alternative of publishing the finished binding cannot work, because the binding depends on the caller.</p>
     *
     * @param subject the authenticated principal the selector is issued to, of type {@code String}; must not
     *     be {@code null} or blank
     * @return the composed binding, never {@code null}
     * @throws NullPointerException if {@code subject} is {@code null}
     * @throws IllegalArgumentException if {@code subject} is blank, because a selector bound to no subject is
     *     redeemable by every other authorized operator
     */
    public static String cursorBinding(String subject) {
        return CursorToken.binding(CURSOR_QUERY_NAME, subject, CursorToken.SCOPE_NONE);
    }

    /**
     * Renders one identifier as exactly its declared width of digits.
     *
     * @param value the stored identifier, never {@code null}
     * @param width the declared width the contract publishes
     * @param component the component name to name in a refusal
     * @return the identifier as {@code width} digits, zero-padded on the left, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is negative or needs more than {@code width}
     *     digits, either of which would produce a body the contract does not describe
     */
    private static String pad(Long value, int width, String component) {
        Objects.requireNonNull(value, component + " must not be null");
        if (value < 0) {
            throw new IllegalArgumentException(component
                    + " must not be negative, the contract publishing it as unsigned digits");
        }
        String digits = Long.toString(value);
        if (digits.length() > width) {
            throw new IllegalArgumentException(component + " needs " + digits.length()
                    + " digits but the contract publishes exactly " + width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Wraps one persistent amount as fixed-point money.
     *
     * <p>Assumptions: an absent amount becomes {@link Money#ZERO} rather than staying {@code null}, because
     * every amount on both bodies is a required member. The reference screens show a zero in the same
     * situation -- an unset packed field reads as zero, not as blank -- so this is the baseline's own
     * rendering rather than a substitution.</p>
     *
     * @param amount the stored amount, which may be {@code null}
     * @return the amount as fixed-point money at scale two, never {@code null}
     * @throws IllegalArgumentException if the stored amount is outside the money domain, which
     *     {@link Money#of(BigDecimal)} refuses
     */
    private static Money money(BigDecimal amount) {
        return amount == null ? Money.ZERO : Money.of(amount);
    }

    /**
     * Renders the point-of-sale entry mode as exactly the two digits its picture declares.
     *
     * <p>Assumptions: an absent mode is published as {@code null} and NOT as {@code "00"}. Zero is a
     * real entry mode in its own right, so substituting it for absence would make a row that recorded no
     * mode indistinguishable from one that recorded mode zero.</p>
     *
     * <p>Alternatives Considered: reusing {@link #pad(Long, int, String)} by widening the stored short to
     * a long. Rejected because that helper refuses a {@code null} outright, which is the one input this
     * field legitimately has, and because its refusal messages are phrased for required identifiers. The
     * two are near-duplicates of five lines; keeping them separate lets each state its own nullability
     * rule instead of one carrying a flag for the other's.</p>
     *
     * @param mode the stored entry mode, which may be {@code null}
     * @return the mode as exactly {@value #POS_ENTRY_MODE_WIDTH} digits with any leading zero restored,
     *     or {@code null} when no mode was stored
     * @throws IllegalArgumentException if the stored mode is negative or needs more than {@value
     *     #POS_ENTRY_MODE_WIDTH} digits, neither of which the segment's unsigned two-digit picture can
     *     hold and neither of which the published pattern would match
     */
    private static String posEntryMode(Short mode) {
        if (mode == null) {
            return null;
        }
        if (mode < 0) {
            throw new IllegalArgumentException("posEntryMode must not be negative, the segment"
                    + " declaring it as unsigned digits, but was: " + mode);
        }
        String digits = Short.toString(mode);
        if (digits.length() > POS_ENTRY_MODE_WIDTH) {
            throw new IllegalArgumentException("posEntryMode needs " + digits.length()
                    + " digits but the contract publishes exactly " + POS_ENTRY_MODE_WIDTH);
        }
        return "0".repeat(POS_ENTRY_MODE_WIDTH - digits.length()) + digits;
    }

    /**
     * Signals that a sealed selector or paging cursor this client presented could not be redeemed.
     *
     * <p><strong>Purpose.</strong> Carry a refused sealed value to the shared advice as a caller-input
     * refusal keyed by the contract field the value arrived in, so it is answered as HTTP 400 with a
     * per-field entry rather than as an internal failure.
     *
     * <p>Assumptions: this type is NESTED in the class that raises it, which is the convention the two
     * existing subclasses of the shared refusal follow -- one nested in the sealer and one in the wire
     * codec. Nesting is what makes the supertype's redaction obligation checkable: the guarantee is that
     * every message names only the shape, the length or the age of a token and never its characters, and
     * that guarantee can only be read off the code if every message is composed within sight of the
     * class that declares it.
     *
     * <p>Alternatives Considered: reusing the sealer's own nested refusal for both cases. Rejected because
     * that type fixes its field key to the sealer's paging vocabulary, and two of the three operations
     * here receive their sealed value in a path segment the contract names differently; a client told to
     * correct a {@code cursor} it never sent cannot act on the refusal.
     */
    public static final class InvalidSelectorException extends ClientInputException {

        /** The serialisation version, fixed because this type's shape is its inherited message alone. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates a refusal attributed to one named contract field.
         *
         * <p>Assumptions: the stable code is fixed rather than accepted from the raise site, because every
         * refusal of this type has one remedy -- start the browse again from its opening page and take a
         * fresh selector from the row -- so distinguishing them in operational tooling would separate
         * occurrences a first responder handles identically. The FIELD is a parameter because the two
         * places a sealed value arrives are two different things for a client to correct.
         *
         * @param field the contract field name the refused value arrived in, one of
         *     {@link PendingAuthViewMapper#SELECTOR_FIELD} or {@link PendingAuthViewMapper#CURSOR_FIELD};
         *     must not be {@code null}
         * @param message what was wrong with the presented value, in terms of its shape, its length, its
         *     age or its payload arity, and never in terms of the characters it carried; must not be
         *     {@code null}
         */
        InvalidSelectorException(String field, String message) {
            super(SELECTOR_REFUSAL_CODE, field, message);
        }
    }
}
