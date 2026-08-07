package com.carddemo.authorization.api;

import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.service.PendingAuthDetailService;
import com.carddemo.authorization.service.PendingAuthSummaryService;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read surface of the pending credit-card authorization context.
 *
 * <p><strong>Purpose.</strong> Bind, validate and delegate the two read operations
 * {@code src/main/resources/openapi/authorization-api.yaml} publishes: the account-scoped list at
 * {@code GET /api/v1/authorizations} and the single-row read at
 * {@code GET /api/v1/authorizations/&#123;key&#125;}. It holds validation and HTTP concerns only; every
 * rule transcribed from the reference COBOL lives in {@code com.carddemo.authorization.service}.
 *
 * <p>Lineage: the list carries across transaction {@code CPVS}, which
 * {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd} L49 and L50 define against
 * {@code cbl/COPAUS0C.cbl}; the read carries across transaction {@code CPVD}, defined at L39 and L40
 * against {@code cbl/COPAUS1C.cbl}. That tree is reference material this migration reads and never
 * modifies.
 *
 * <p>Assumptions: this class touches no entity, injects no repository and declares no transaction
 * boundary, which is the package charter's central assertion rather than an omission. An entity returned
 * from here would be serialised without passing through
 * {@code com.carddemo.authorization.mapper}, and the primary-account-number masking that package
 * performs would then simply not be applied -- a leak that would not announce itself, because the
 * response would be well formed and would merely carry a field it was never meant to carry.
 *
 * <p>Assumptions: no authority is checked here. The {@code carddemo-user} authority both operations
 * declare is enforced once, in {@code config/SecurityConfig.java}, against
 * {@code READ_PATH_PATTERN}. Annotating it again per method would be a second place the matrix could
 * differ from the published contract.
 *
 * <p>Refactoring Rationale: this class is deliberately NOT annotated {@code @Validated}, and the omission
 * is recorded because the annotation is the obvious way to make parameter constraints run and it is the
 * wrong one here. An earlier revision carried it. It switches parameter validation from the framework's
 * built-in mechanism -- which raises {@code HandlerMethodValidationException} -- to an AOP proxy that
 * raises {@code ConstraintViolationException} instead, and the shared advice in
 * {@code com.carddemo.common.error} declares a handler for the first and none for the second. Every
 * refusal of an account scope would therefore have fallen through to the catch-all and been answered as
 * HTTP 500: the caller would have been told its own malformed input was a server fault, and the two
 * verbatim sentences this contract publishes would have been unreachable. Without the annotation the
 * built-in mechanism applies, because the framework detects the constraints on these parameters itself.
 */
@RestController
@RequestMapping(path = PendingAuthController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class PendingAuthController {

    /**
     * The collection path this controller is mounted at.
     *
     * <p>Assumptions: written literally and NOT injected from configuration, because
     * {@code config/SecurityConfig.java} matches its read rule on the same prefix as a literal for the
     * same reason: a property change could otherwise move the route out from under the gate, and a path
     * gate that matches nothing fails open.</p>
     */
    public static final String BASE_PATH = "/api/v1/authorizations";

    /**
     * The eleven-digit domain the account scope is published in.
     *
     * <p>Assumptions: the value is a digit STRING and not a number. The datum is
     * {@code PA-ACCT-ID PIC S9(11) COMP-3} at {@code cpy/CIPAUSMY.cpy} L19 and the column is an integer,
     * but a leading zero is data in this identifier and a JSON or query number would lose it before any
     * validation could see it.</p>
     */
    private static final String ACCOUNT_ID_DOMAIN = "^[0-9]{11}$";

    /**
     * The two directions the contract publishes for a paging move.
     *
     * <p>Assumptions: constrained here as well as in the service, and the duplication is deliberate. The
     * constraint here refuses an unpublished value with a per-field entry keyed {@code direction} before
     * any handler runs, which is what the contract describes; the service's own check is what makes the
     * rule hold for any caller reaching it other than through this route.</p>
     */
    private static final String DIRECTION_DOMAIN = "^(next|previous)$";

    /**
     * The sentence the reference program displays when the account scope is blank.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS0C.cbl} L268 to L269,
     * INCLUDING the absence of a space before its ellipsis, which differs from the sibling sentence below.
     * Transformation rule T8 requires every user-visible string to be carried across unchanged, and the
     * contract publishes this one as the aggregate and as the per-field entry of a 400.</p>
     */
    private static final String MESSAGE_ACCOUNT_ID_REQUIRED = "Please enter Acct Id...";

    /**
     * The sentence the reference program displays when the account scope carries a non-digit.
     *
     * <p>Assumptions: carried character for character from {@code cbl/COPAUS0C.cbl} L277 to L278,
     * including the single space before its ellipsis. The reference tests it only after the blank test
     * passed, at L272, and the shared advice reproduces that short-circuit by latching the first rejected
     * parameter's sentence as the aggregate.</p>
     */
    private static final String MESSAGE_ACCOUNT_ID_NUMERIC = "Acct Id must be Numeric ...";

    /**
     * The list behaviour, which owns the page size and the look-ahead probe.
     */
    private final PendingAuthSummaryService summaries;

    /**
     * The single-row read behaviour.
     */
    private final PendingAuthDetailService detail;

    /**
     * Builds the controller over the two read services.
     *
     * @param summaries the list service; must not be {@code null}
     * @param detail the single-row read service; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PendingAuthController(PendingAuthSummaryService summaries,
            PendingAuthDetailService detail) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.detail = Objects.requireNonNull(detail, "detail must not be null");
    }

    /**
     * Lists one account's pending authorizations, one key-positioned page at a time.
     *
     * <p>Assumptions: the cursor is OPTIONAL and its absence -- not an empty string -- asks for the
     * opening page, because the reference program opens one by moving low values into its forward position
     * at {@code cbl/COPAUS0C.cbl} L422, and low values and spaces are two distinct empty states in that
     * program rather than one.
     *
     * <p>Assumptions: the account scope is converted to a number HERE, after its digit constraint has
     * passed, so the parse cannot fail. Parsing before validation would raise a platform parse failure
     * that the shared advice answers as a server fault, which is the correct answer for an unvalidated
     * parse and the wrong answer for a caller's malformed input.
     *
     * @param accountId the account whose authorizations are wanted, as exactly eleven digits; must not be
     *     blank
     * @param cursor the sealed paging position to continue from, or {@code null} for the opening page
     * @param direction {@code next} or {@code previous}, or {@code null} to default to next; meaningful
     *     only alongside a cursor
     * @param principal the authenticated caller, supplied by the framework; the boundary tokens this
     *     operation issues are sealed against its name, so a page issued to one operator cannot be
     *     replayed by another
     * @return HTTP 200 carrying the account summary, one page of rows newest first, and the boundary
     *     sentence when the request was a paging move that had already reached a boundary; never
     *     {@code null}
     */
    @GetMapping
    public ResponseEntity<PendingAuthListView> list(
            @RequestParam(name = "accountId")
            @NotBlank(message = MESSAGE_ACCOUNT_ID_REQUIRED)
            @Pattern(regexp = ACCOUNT_ID_DOMAIN, message = MESSAGE_ACCOUNT_ID_NUMERIC)
            String accountId,
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = "direction", required = false)
            @Pattern(regexp = DIRECTION_DOMAIN)
            String direction,
            Principal principal) {

        return ResponseEntity.ok(this.summaries.list(Long.valueOf(accountId), cursor, direction,
                principal.getName()));
    }

    /**
     * Reads the full state of the one pending authorization a sealed selector names.
     *
     * <p>Assumptions: the selector's shape is constrained here so a value that is not a sealed token is
     * refused before any handler runs, which is what the contract states of this parameter. The
     * constraint is on shape alone; authentication, expiry and payload arity are the sealer's to decide,
     * and it decides them inside the service.
     *
     * @param key the sealed selector taken from the {@code key} property of a list row; must not be blank
     * @param principal the authenticated caller, supplied by the framework; the selector is redeemed
     *     against its name, so a selector issued to another operator is refused rather than honoured
     * @return HTTP 200 carrying the authorization's full state with its primary account number rendered to
     *     its last four digits; never {@code null}
     */
    @GetMapping(path = "/{key}")
    public ResponseEntity<PendingAuthDetailView> read(
            @PathVariable(name = "key")
            @NotBlank
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String key,
            Principal principal) {

        return ResponseEntity.ok(this.detail.read(key, principal.getName()));
    }
}
