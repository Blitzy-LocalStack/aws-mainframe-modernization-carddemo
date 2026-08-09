package com.carddemo.authorization.api;

import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthPageQuery;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.service.PendingAuthDetailService;
import com.carddemo.authorization.service.PendingAuthSummaryService;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read surface of the pending credit-card authorization context.
 *
 * <p><strong>Purpose.</strong> Bind, validate and delegate the two read operations
 * {@code src/main/resources/openapi/authorization-api.yaml} publishes: the account-scoped list at
 * {@code POST /api/v1/authorizations/search} and the single-row read at
 * {@code GET /api/v1/authorizations/&#123;key&#125;}. It holds validation and HTTP concerns only; every
 * rule transcribed from the reference COBOL lives in {@code com.carddemo.authorization.service}.
 *
 * <p>Refactoring Rationale: this named the list as {@code GET /api/v1/authorizations}, which the contract no
 * longer publishes and this class no longer serves. The listing is a POST on a {@code /search} subresource
 * because its selector is an account identifier together with an opaque paging cursor, and both are values
 * that must not appear in a request line: a query string reaches access logs, proxy logs and browser
 * history, and the cursor is a sealed token whose whole purpose is that it is not a value a caller may
 * compose. A retired route named in a class summary is the most misleading kind of stale documentation,
 * because a reader building a client from it gets a 405 with nothing to explain it, so the correction is
 * stated rather than the sentence quietly rewritten.
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
     * The literal path the paged listing is requested at.
     *
     * <p>Refactoring Rationale: the listing was a {@code GET} on {@link #BASE_PATH} carrying its account
     * scope, cursor and direction as query parameters. It is a {@code POST} on this literal segment
     * because the scope is an account identifier and a query string is part of the request line, which the
     * load balancer writes into its access log itself before any application code runs. The scope was
     * REQUIRED, so every request to this listing disclosed one account identifier into that log.</p>
     *
     * <p>Assumptions: a literal segment beneath the collection rather than a {@code POST} on the
     * collection itself, matching the card context's search path. A {@code POST} to a collection reads as
     * a create, and this contract publishes none -- a pending authorization comes into existence only when
     * the message-driven half of this context accepts a request.</p>
     *
     * <p>Assumptions: the overlap with the single-authorization path resolves in this operation's favour
     * for the documented reason that a literal pattern is selected ahead of a templated one, and because a
     * sealed selector cannot spell this literal -- it begins with a version marker and is far longer.</p>
     */
    public static final String SEARCH_PATH = "/search";





    /**
     * The list behaviour, which owns the page size and the look-ahead probe.
     */
    private final PendingAuthSummaryService summaries;

    /**
     * The single-row read behaviour.
     */
    private final PendingAuthDetailService detail;

    /**
     * The clock the screen representation's rendered instant is read from.
     *
     * <p>Assumptions: the instant is the SERVER'S and is never accepted from the caller, for the same
     * reason the batch business date is a job parameter rather than a clock read -- it is evidence of when
     * the response was produced, and a caller able to set it could label a response with an instant at
     * which nothing happened. A clock is injected rather than read statically so the value is fixable in a
     * test without freezing time for the process.</p>
     */
    private final Clock clock;

    /** The reference transaction identifier the screen representation reports. */
    private static final String SCREEN_TRANSACTION_NAME = "CP01";

    /** The first line of the shared title band, from {@code cpy/COTTL01Y.cpy}. */
    private static final String SCREEN_TITLE_01 = "CardDemo";

    /** The reference program name the screen representation reports. */
    private static final String SCREEN_PROGRAM_NAME = "COPAUS1C";

    /** The second line of the shared title band. */
    private static final String SCREEN_TITLE_02 = "Pending Authorization Details";

    /**
     * Builds the controller over the two read services.
     *
     * @param summaries the list service; must not be {@code null}
     * @param detail the single-row read service; must not be {@code null}
     * @param clock the clock the screen representation's rendered instant is read from; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PendingAuthController(PendingAuthSummaryService summaries,
            PendingAuthDetailService detail, Clock clock) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.detail = Objects.requireNonNull(detail, "detail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
     * @param query the search criteria -- the required account scope, an optional sealed cursor and an
     *     optional direction. The scope travels in this body rather than in a query string because it is
     *     an account identifier; the reasoning is on {@link PendingAuthPageQuery}
     * @param principal the authenticated caller, supplied by the framework; the boundary tokens this
     *     operation issues are sealed against its name, so a page issued to one operator cannot be
     *     replayed by another
     * @return HTTP 200 carrying the account summary, one page of rows newest first, and the boundary
     *     sentence when the request was a paging move that had already reached a boundary; never
     *     {@code null}
     */
    @OnlineWriteGateExempt(reason =
            "A paged READ of the pending-authorization summary. It is a POST because its criteria"
            + " carry an account identifier and a sealed cursor. Nothing is written, and an operator"
            + " reviewing pending authorizations during the batch window is exactly the case reads"
            + " are kept available for; the fraud marking is a PUT elsewhere and is not exempt.")
    @PostMapping(path = SEARCH_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PendingAuthListView> list(
            @Valid @RequestBody PendingAuthPageQuery query,
            Principal principal) {

        // WHY : Refactoring Rationale: the three criteria arrived as query parameters and now arrive as
        //       members of a validated body. The constraints moved WITH them onto
        //       PendingAuthPageQuery rather than being restated here, so a caller experiences the same
        //       refusals and the same baseline message text as before; what changed is only that the
        //       account identifier no longer travels in the request line, where the load balancer's
        //       access log would have retained it.
        return ResponseEntity.ok(this.summaries.list(Long.valueOf(query.accountId()), query.cursor(),
                query.direction(), principal.getName()));
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

    /**
     * Reads the same authorization in the SCREEN shape, with the title band and message the terminal
     * carried.
     *
     * <p>Refactoring Rationale: this endpoint is added because the service operation behind it had no
     * caller. It, and the navigation operation below it, were reachable only from their tests while the
     * service package's documentation described them as published -- so the screen shape the migration
     * derived field by field from {@code cpy-bms/COPAU01.cpy} could not actually be fetched, and the
     * single-page application's authorization-detail route had nothing to render its chrome from.</p>
     *
     * <p>Assumptions: this is a SECOND representation of one resource rather than a replacement for the
     * first. The plain reading above is the record; this one is the record plus the terminal's chrome, and
     * a client that does not draw a 3270 screen has no use for the second. Alternatives Considered:
     * content negotiation on the same path, which was rejected because both representations are JSON and a
     * custom media type would make the distinction invisible in a browser's network panel and in a log.</p>
     *
     * <p>Assumptions: every chrome value is derived HERE from constants and the clock, and none is accepted
     * from the caller. The transaction name, the two title lines and the program name identify the
     * reference screen this response reproduces, so a caller able to set them could label a response as
     * having come from a screen that never produced it; and the rendered instant must be the server's for
     * the same reason the batch business date is a parameter rather than a clock read -- it is evidence.</p>
     *
     * @param key the sealed selector taken from the {@code key} property of a list row; must not be blank
     * @param principal the authenticated caller, supplied by the framework; the selector is redeemed
     *     against its name, so a selector issued to another operator is refused rather than honoured
     * @return HTTP 200 carrying the screen-shaped projection with its primary account number rendered to
     *     its last four digits; never {@code null}
     */
    @GetMapping(path = "/{key}/screen")
    public ResponseEntity<PendingAuthDetailResponse> readForScreen(
            @PathVariable(name = "key")
            @NotBlank
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String key,
            Principal principal) {

        PendingAuthDetailMapper.ScreenContext context = new PendingAuthDetailMapper.ScreenContext(
                SCREEN_TRANSACTION_NAME, SCREEN_TITLE_01, SCREEN_PROGRAM_NAME, SCREEN_TITLE_02,
                LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC), null);
        return ResponseEntity.ok(this.detail.readForScreen(key, principal.getName(), context));
    }

    /**
     * Reads the authorization immediately following the one a selector names, which is the forward paging
     * move.
     *
     * <p>Assumptions: the response reports END OF DATA rather than answering 404 when nothing follows,
     * because "there is no next" is a successful answer to this question and the reference says so on the
     * screen rather than refusing the request. A 404 would be indistinguishable from a selector that named
     * nothing at all, and the caller would have no way to tell a boundary from a bad key.</p>
     *
     * @param key the sealed selector of the authorization currently displayed; must not be blank
     * @param principal the authenticated caller, supplied by the framework; the selector is redeemed
     *     against its name
     * @return HTTP 200 carrying the following authorization, or the end-of-data indicator when the one
     *     named is the last; never {@code null}
     */
    @GetMapping(path = "/{key}/next")
    public ResponseEntity<PendingAuthDetailService.NextAuthorization> readNext(
            @PathVariable(name = "key")
            @NotBlank
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String key,
            Principal principal) {

        return ResponseEntity.ok(this.detail.readNext(key, principal.getName()));
    }
}
