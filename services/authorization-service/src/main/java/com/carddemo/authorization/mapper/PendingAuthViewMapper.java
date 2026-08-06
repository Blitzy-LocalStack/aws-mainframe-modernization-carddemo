package com.carddemo.authorization.mapper;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryView;
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
     * The cursor binding under which every authorization row selector is sealed and opened.
     *
     * <p>Assumptions: the binding is authenticated into the token but not carried by it, so a token sealed
     * under this string cannot be presented to a query that opens under another. The value names the
     * resource rather than the operation, because the same selector addresses a row on the list, the read
     * and the fraud operations, and three different bindings would make a selector taken from a list
     * unusable on the other two.</p>
     *
     * <p>Refactoring Rationale: it is a constant on the mapper rather than a literal at each call site,
     * because a binding that differs by one character between sealing and opening fails authentication and
     * presents as a rejected cursor rather than as a mismatch -- a failure mode that is expensive to
     * diagnose and trivial to prevent.</p>
     */
    public static final String CURSOR_BINDING = "authorization-row";

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
     * The response code that means the authorization was approved.
     *
     * <p>Assumptions: read from the literal tested at {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}
     * L536, and the same value the detail screen tests at {@code cbl/COPAUS1C.cbl} L311.</p>
     */
    public static final String RESPONSE_CODE_APPROVED = "00";

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
     * @return the summary block in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code summary} is {@code null}, or if any column the contract marks
     *     required is unset on it
     */
    public PendingAuthSummaryView toSummaryView(PendingAuthSummary summary) {
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
                money(summary.getDeclinedAuthAmount()));
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
     * @return the list item in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code detail} is {@code null}, or if any column the contract marks
     *     required is unset on it
     * @throws IllegalArgumentException if the row's match status is outside its four-value domain, which
     *     the item's own constructor refuses
     */
    public PendingAuthRowView toRowView(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        return new PendingAuthRowView(
                sealKey(detail.getId()),
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
     * rather than as a JSON number, because the contract bounds it as one or two digits and the reference
     * field is characters. An absent mode stays absent rather than becoming a zero, a zero being a valid
     * entry mode in its own right.</p>
     *
     * @param detail the persistent authorization row, never {@code null}
     * @return the detail body in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code detail} is {@code null}, or if any column the contract marks
     *     required is unset on it
     * @throws IllegalArgumentException if the row's match status is outside its four-value domain, which
     *     the body's own constructor refuses
     */
    public PendingAuthDetailView toDetailView(PendingAuthDetail detail) {
        Objects.requireNonNull(detail, "detail must not be null");
        PendingAuthDetailKey key = Objects.requireNonNull(detail.getId(), "detail id must not be null");
        return new PendingAuthDetailView(
                sealKey(key),
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
                detail.getPosEntryMode() == null ? null : String.valueOf(detail.getPosEntryMode()),
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
     * @param screenMessage the navigation-boundary sentence for this request, or {@code null} when the
     *     request was not a paging move that had already reached a boundary
     * @return the list body in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code summary} or {@code rows} is {@code null}, or if {@code rows}
     *     contains {@code null}
     * @throws IllegalArgumentException if {@code hasNext} is {@code true} while {@code rows} is empty, or
     *     if {@code screenMessage} is not one of the three reference sentences; the page envelope refuses
     *     the first and the body refuses the second
     */
    public PendingAuthListView toListView(PendingAuthSummary summary,
            List<PendingAuthDetail> rows, boolean hasNext, String screenMessage) {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(rows, "rows must not be null");

        List<PendingAuthRowView> items = new ArrayList<>(rows.size());
        for (PendingAuthDetail row : rows) {
            items.add(toRowView(Objects.requireNonNull(row, "rows must not contain null")));
        }

        // WHY : Assumptions: an empty page carries no boundary tokens at all rather than carrying the
        //       tokens of the rows that would have been on it. There are no such rows, and the page
        //       envelope's own contract refuses a boundary without rows for exactly that reason.
        String firstKey = items.isEmpty() ? null : items.get(0).key();
        String lastKey = items.isEmpty() ? null : items.get(items.size() - 1).key();
        PageResponse<PendingAuthRowView> page =
                PageResponse.ofRows(items, firstKey, lastKey, hasNext);
        return new PendingAuthListView(toSummaryView(summary), page, screenMessage);
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
     * Seals one row's three-part identity into the opaque selector a client sends back.
     *
     * <p>Assumptions: the three parts are joined in the physical key order -- account, then date, then time
     * -- because that is the order the composite key declares and the order a keyset predicate compares
     * them in. A different order here would still round-trip but would stop the opened key from being
     * usable directly in that predicate.</p>
     *
     * @param key the row's composite key, never {@code null}
     * @return the sealed selector, never {@code null}
     * @throws NullPointerException if {@code key} is {@code null} or any of its three parts is unset
     */
    private String sealKey(PendingAuthDetailKey key) {
        Objects.requireNonNull(key, "key must not be null");
        Long accountId = Objects.requireNonNull(key.getAccountId(), "key accountId must not be null");
        Integer authDate = Objects.requireNonNull(key.getAuthDate(), "key authDate must not be null");
        Integer authTime = Objects.requireNonNull(key.getAuthTime(), "key authTime must not be null");
        String cursorKey = accountId + String.valueOf(KEY_PART_SEPARATOR) + authDate
                + String.valueOf(KEY_PART_SEPARATOR) + authTime;
        return this.cursorToken.seal(CURSOR_BINDING, cursorKey);
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
}
