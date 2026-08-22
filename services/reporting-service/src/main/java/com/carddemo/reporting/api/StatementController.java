package com.carddemo.reporting.api;

import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionCollection;
import com.carddemo.reporting.service.ArtifactStore;
import com.carddemo.reporting.service.StatementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary of the statement surface, replacing a pair of batch statement programs.
 *
 * <p>This controller carries the three operations that surface {@code app/cbl/CBSTM03A.CBL} at 924
 * lines and {@code app/cbl/CBSTM03B.CBL} at 230 lines. The upper-case extension on each is
 * load-bearing: a lower-case citation of either one is a dead reference. The baseline is reference
 * material, read as the specification and never modified.
 *
 * <p>Three operations are exposed and they differ in what part of one statement they return.
 * {@link #generateStatement(StatementRequest)} returns the heading figures, the assembled total and,
 * where the store holds them, the location of each rendered artifact;
 * {@link #listStatementTransactions(StatementRequest)} returns the transactions those artifacts were
 * built from, and nothing else; {@link #collectArtifact(String)} returns one rendered artifact's
 * bytes. A caller rendering the lines takes the second; a caller that only needs to know whether the
 * artifacts exist, or what the statement totals, takes the first and does not pay to transport the row
 * window it would discard, which {@link StatementService#MAX_RESPONSE_TRANSACTIONS} caps at 1,000; a
 * caller collecting the rendered document takes the third with the selector the first returned.
 *
 * <p>Refactoring Rationale: both method names are the published operation identifiers verbatim --
 * an earlier revision named them {@code describeStatement} and {@code getStatementDocument}. The
 * documentation library derives an operation identifier from the method name when none is annotated,
 * so a method named otherwise makes the document served at run time disagree with the document
 * committed beside it, in a field {@code ReportingApiContractTest} pins on the committed side only.
 * Naming the method after the operation removes the divergence at its source rather than adding a
 * second annotation to paper over it.
 *
 * <p>Trade-offs: both operations are {@code POST} even though both are reads, and the
 * reason is disclosure rather than semantics. A statement is selected by a primary account number,
 * and a request line is written into the access log of every intermediary between the browser and
 * this service, into browser history and into a referrer header -- none of which this service can
 * redact after the fact. Carrying the selector in a request body keeps it out of all of them. The
 * same trade was already made once in this migration, by the card-selector lookup operation, and for
 * the same reason.
 *
 * <p>Alternatives Considered: a {@code GET} keyed on an opaque sealed selector, which would have made
 * both operations cacheable and idempotent by method. Rejected for this context because the statement
 * surface has no list operation from which a caller could obtain such a selector -- the reference
 * reaches a statement by walking the cross-reference in a batch job, not by browsing a screen -- so a
 * selector would have to be minted from the very number the caller already holds, which is a round
 * trip that buys nothing over placing the number in a body directly.
 *
 * <p>⚠️ Refactoring Rationale: this controller DOES now serve the rendered artifact, and the paragraph
 * this replaces argued the opposite on two premises that were both untrue. It said this module is
 * granted no object-store client, while {@code ObjectStoreConfig} contributes one unconditionally and
 * three classes in this module already use it. And it said the caller reaches the artifact "through the
 * path that owns it", while no such path exists: nothing serves the dataset bucket to a caller, and the
 * bucket policy refuses access from outside the VPC endpoint, so the locations the description returned
 * were unreachable by every caller of this surface. A review found the consequence rather than the
 * argument. The delivery path is now {@link #collectArtifact(String)}, and what makes that acceptable
 * where a fetch-and-relay was not is that the artifact is served as an opaque attachment under this
 * surface's own authorization -- see the rationale on that operation, which records why a pre-signed
 * URL and a widened bucket policy were both rejected in its place.
 *
 * <p>Assumptions: no exception handler and no authority annotation is declared here, for the reasons
 * recorded on {@link ReportController} and in the package charter. A card with no cross-reference,
 * customer or account row raises {@link NoSuchElementException}, which the shared advice renders as
 * the not-found status this service publishes.
 */
@RestController
@RequestMapping(path = StatementController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@OnlineWriteGateExempt(reason =
        "All three operations read an existing statement and persist nothing. The two describing"
        + " operations are POSTs because the account and card identifiers they select on must travel"
        + " in a request body rather than in a request line the load balancer records; the third is a"
        + " GET because an opaque selector discloses neither. A rendered statement is a read of"
        + " already-posted data, so it stays available while the window is closed.")
public class StatementController {

    /**
     * Root path of every operation this controller carries.
     *
     * <p>Refactoring Rationale: the path sits BENEATH the reports prefix, and an earlier revision
     * rooted it at {@code /api/v1/statements}. Two things made that wrong rather than merely
     * different. The published contract at {@code src/main/resources/openapi/reporting-api.yaml}
     * declares these operations under the reports prefix, and that document is what
     * {@code ui/src/api/reporting.ts} composes its request targets from; and the edge forwards exactly
     * one prefix to this service -- the listener rule in {@code infra/envs/&#123;dev,prod&#125;/main.tf}
     * and the route keys in {@code infra/modules/api-gateway-http/variables.tf} both name
     * {@code /api/v1/reports} -- so a second root would not have been routable at all. A statement is
     * a report of this context, so the nesting is also what the surface means.
     */
    public static final String BASE_PATH = StatementService.STATEMENTS_BASE_PATH;

    /**
     * Sub-path of the whole-document operation.
     */
    public static final String TRANSACTIONS_PATH = "/transactions";

    /**
     * Name of the path variable carrying the opaque artifact selector.
     */
    public static final String SELECTOR_VARIABLE = "selector";

    /**
     * Sub-path of the artifact collection operation, relative to {@value #BASE_PATH}.
     *
     * <p>Assumptions: this is DERIVED from {@link StatementService#ARTIFACT_LOCATION_PREFIX} rather
     * than typed independently, so the route that serves an artifact and the location a statement
     * response publishes for it cannot drift apart. The service composes the location and this
     * controller serves it, and a review found exactly what happens when the two are written
     * separately: the published location named an object nothing writes.
     */
    public static final String ARTIFACTS_PATH =
            StatementService.ARTIFACTS_SEGMENT + "{" + SELECTOR_VARIABLE + "}";

    /**
     * Shape a selector must have before the store is consulted.
     *
     * <p>Assumptions: the pattern is DERIVED from the tokeniser rather than written out, so a change
     * of token width cannot leave the guard admitting a width the tokeniser no longer emits. The
     * alphabet is the unpadded URL-safe base64 set {@code OpaqueIdentifier} encodes into, and the
     * length is {@code OpaqueIdentifier.TOKEN_LENGTH}; the expression stays a compile-time constant,
     * which is what lets it sit in the constraint annotation below.
     *
     * <p>Measured: removing this constraint from the parameter below fails exactly
     * {@code StatementControllerTest.aMalformedSelectorIsRefusedBeforeTheStore} with
     * {@code Status expected:<400> but was:<500>} -- the malformed value reaches the store, which is
     * both the wrong status and one call further than it needed to go.
     *
     * <p>Trade-offs: a selector of the wrong SHAPE is refused with 400 while a well-formed selector
     * that names nothing is answered with 404, and the asymmetry is deliberate. The shape is published
     * in this service's own contract, so refusing a malformed value discloses nothing a caller could
     * not already read -- and it saves a call to the object store for a value that cannot name an
     * artifact. Which well-formed selectors are real is NOT published, so those are all answered
     * alike.
     */
    public static final String SELECTOR_PATTERN =
            "^[A-Za-z0-9_-]{" + OpaqueIdentifier.TOKEN_LENGTH + "}$";

    /**
     * Value of the content-type options header set on every response from this controller.
     *
     * <p>Assumptions: this instructs a browser not to infer a content type other than the declared
     * one. It matters here and not on every controller in the migration because a statement payload
     * carries free text taken from the customer and merchant name fields of the reference record --
     * {@code CUST-FIRST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy} L6 constrains a width and not
     * an alphabet -- so a payload whose type a browser inferred as markup could be rendered rather
     * than parsed.
     */
    public static final String NOSNIFF = "nosniff";

    /**
     * Name of the content-type options header.
     */
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    /**
     * Name of the content security policy header.
     */
    public static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";

    /**
     * Policy set on every response from this controller.
     *
     * <p>Assumptions: this is defence in depth behind the escaping the statement markup
     * mapper performs, and it is not a substitute for it. The mapper escapes every dynamic value into
     * its context and refuses a value it cannot render, which is the control that actually prevents
     * injected markup from executing; this policy is what limits the consequence if a payload from
     * this controller is ever displayed as a document instead of parsed as data. Denying every
     * fetch directive and sandboxing the result means such a document can load nothing and run
     * nothing.
     *
     * <p>Trade-offs: the policy is maximally restrictive, which is safe precisely because these
     * responses are data and never documents -- there is no stylesheet, script or image a legitimate
     * consumer of this payload needs to load. Applying the same value to a response that was meant to
     * render would break it, which is why it is set here rather than globally.
     */
    public static final String STATEMENT_POLICY = "default-src 'none'; sandbox";

    /**
     * Disposition set on every response from this controller.
     *
     * <p>Assumptions: attachment disposition tells a browser to save rather than display, which
     * removes the case the policy above exists to contain rather than merely constraining it. It is
     * stated without a filename, because a filename derived from a card or an account would put an
     * identifier into a value the browser writes to the file system.
     */
    public static final String ATTACHMENT_DISPOSITION = "attachment";

    private final StatementService statements;

    /**
     * Creates the controller over the service it delegates to.
     *
     * @param statements the statement assembly service; must not be {@code null}
     * @throws NullPointerException if the collaborator is {@code null}
     */
    public StatementController(StatementService statements) {
        this.statements = Objects.requireNonNull(statements, "statements");
    }

    /**
     * Describes one card's statement: its heading figures, its total, and -- for an operator -- the
     * location of each rendered artifact the store holds.
     *
     * <p>⚠️ Assumptions: what this answer may disclose is decided from the caller's own claims and not
     * from the request, because the artifacts it would otherwise point at are RUN-WIDE. A review found
     * this operation handing every caller entitled to one card's statement the selectors of the objects
     * holding the whole portfolio's statements; the audience derived below is the request edge's half of
     * the fix, and the group rule on the collection route in {@code SecurityConfig} is the other half.
     * Neither alone is sufficient: without the audience an ordinary caller is told an address it is
     * refused at, and without the rule the address works.
     *
     * @param request the card whose statement is wanted, and optionally the account it is expected to
     *     belong to; validated declaratively before this method is entered
     * @param authentication the validated authentication the chain established, supplied by the
     *     framework; {@code null} when no authentication is present, which is treated as the narrowest
     *     audience
     * @return the statement description, carrying the protective response headers
     * @throws NoSuchElementException if the card has no cross-reference, customer or account row
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatementResponse> generateStatement(
            @Valid @RequestBody StatementRequest request, Authentication authentication) {
        return protectively(statements.describe(request, audienceOf(authentication)));
    }

    /**
     * Returns the transactions one card's statement was rendered from.
     *
     * <p>Refactoring Rationale: the body carries the TRANSACTIONS alone, where an earlier revision
     * returned the whole document -- the heading summary and the transactions together. The published
     * contract declares the summary and the rows as separate operations, and the reason
     * is size rather than taste: a statement carries as many rows as the card has activity, a count no
     * contract fixes, and a caller that wants only the heading figures and the artifact locations
     * should not have to receive them. The summary operation above answers that caller.
     *
     * <p>Assumptions: the body returned here is a BOUNDED window, and the two bounds in play belong to
     * different destinations rather than being one bound stated twice. The rendered artifact carries no
     * fixed arity at all, which is divergence D-2, owned and documented by {@link StatementService} and
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}; the reference reached
     * its two static dimensions through a single working-storage index measuring
     * 51 x (16 + 10 x 334) = 51 x 3356 = 171,156 characters. The response is a surface the reference
     * never published at all, and it is capped separately by
     * {@link StatementService#MAX_RESPONSE_TRANSACTIONS} at 1,000 rows under the distinct marker
     * D-STMT-RESPONSE-BOUNDED. An earlier revision of this paragraph attributed the response bound to
     * D-2 and stated that no such bound existed, which described neither destination accurately.
     *
     * <p>Assumptions: a capped window stays measurable rather than silent, and this operation now says so
     * in its own body. The response carries the card's true transaction count and a derived truncation
     * flag beside the rows, both taken from the same composed document, so a caller learns whether and by
     * how much the window was capped without holding a heading from another operation. Refactoring
     * Rationale: this paragraph appealed to the heading the SUMMARY operation returns, which is a
     * different operation with a different body -- so for a caller of this one the bound was silent.
     *
     * <p>Assumptions: this is still not a page and carries no cursor. The set is closed by the
     * statement's own period, so there is no open-ended sequence to walk, which is the argument recorded
     * on {@link StatementTransactionCollection}. What the two added members publish is the FACT of a
     * bound rather than a way to step past it; a caller needing every row of an exceptionally long
     * history reads the rendered artifact, which carries no bound at all.
     *
     * @param request the card whose statement is wanted, and optionally the account it is expected to
     *     belong to; validated declaratively before this method is entered
     * @param authentication the validated authentication the chain established, supplied by the
     *     framework; {@code null} when no authentication is present, which is treated as the narrowest
     *     audience
     * @return the statement's transactions, carrying the protective response headers
     * @throws NoSuchElementException if the card has no cross-reference, customer or account row
     */
    @PostMapping(path = TRANSACTIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatementTransactionCollection> listStatementTransactions(
            @Valid @RequestBody StatementRequest request, Authentication authentication) {
        // WHY : Assumptions: the transactions are taken from the composed document rather than read
        //       separately, so the rows this operation returns are the rows the rendered artifacts were
        //       built from. A second, independent query would be able to return a row the rendered
        //       statement does not contain, and a caller reconciling the two would have no way to tell
        //       which one was the statement.
        // WHY : Assumptions: the audience is passed even though this operation returns no artifact
        //       location of its own, because the composed document carries the heading the summary
        //       operation returns and the run-wide reads behind it are the reads a cardholder request
        //       must not perform. Passing the widest audience here would make this operation the way
        //       round the rule the summary operation applies.
        StatementDocument document = statements.compose(request, audienceOf(authentication));
        // WHY : Refactoring Rationale: the card's TRUE transaction count is now published beside the
        //       rows, and it costs no extra read -- the composed document already carries the heading
        //       the summary operation returns, and this method was discarding it. Without it this
        //       operation was the one place the service's response bound was undetectable: the argument
        //       recorded for that bound is that a caller compares the rows it received against the
        //       count in the heading, and this operation returns no heading, so a bounded statement and
        //       a whole one were the same body to a caller of it.
        return protectively(StatementTransactionCollection.of(
                document.transactions(), document.statement().transactionCount()));
    }

    /**
     * Streams one stored statement artifact to an authenticated caller.
     *
     * <p>⚠️ Refactoring Rationale: this operation exists because the two operations above published
     * artifact locations that nothing served. The dataset bucket refuses access outside the VPC
     * endpoint, so a caller holding a location had no way to collect the artifact it named, and the
     * only alternatives were to widen the bucket policy or to hand out pre-signed URLs. Both were
     * rejected: widening the policy makes every artifact of every run reachable from the internet on
     * the strength of a key name, and a pre-signed URL is a bearer credential in a query string that
     * outlives the session, appears in browser history and in any intermediary's access log, and
     * carries none of this surface's authorization rules. Streaming through this operation keeps
     * collection inside the same token, the same rules and the same audit trail as every other read.
     *
     * <p>Assumptions: BOTH artifacts are served as a binary attachment rather than with their own
     * media types. The markup artifact is HTML built from cardholder data, and returning it as
     * {@code text/html} invites a browser to render it in this origin -- which is the case the
     * content-security policy and the attachment disposition on this controller exist to prevent, and
     * declaring it renderable would undo them for the one response that actually carries the markup.
     * Trade-offs: a caller that wants to display the markup must save it and open it itself, which is
     * the cost of not making a statement a page this service hosts.
     *
     * <p>Measured: adding {@code text/html} to the producible types of this operation fails exactly
     * {@code StatementControllerTest.anArtifactCannotBeNegotiatedAsMarkup}, which stops reporting 406
     * and instead reaches the handler -- {@code Cannot invoke "OpenArtifact.sizeBytes()" because
     * "artifact" is null}, the collaborator never having been stubbed for a request that should not
     * have arrived. So the single producible type is what refuses the negotiation, not an accident of
     * the harness.
     *
     * <p>Assumptions: the response declares its length from the store's own count and the body is
     * streamed rather than buffered, so a run covering a large customer base does not become a
     * function of this service's heap. The stream is closed by the message converter after the body is
     * written.
     *
     * <p>Assumptions: a selector of the wrong shape is refused before the store is consulted, by the
     * declarative constraint below, and the shared advice renders that as 400 with this contract's own
     * error shape. A well-formed selector that names nothing is answered with 404, indistinguishably
     * from an artifact the store does not hold -- the reasoning for the asymmetry is on
     * {@link #SELECTOR_PATTERN}.
     *
     * <p>⚠️ Assumptions: this operation is admitted to the ADMINISTRATIVE group alone, and the rule
     * enforcing that lives in {@code SecurityConfig} where every other rule of this surface lives. The
     * two artifacts it serves hold every cardholder's statement in the run, so admitting an ordinary
     * group claim here made one card's entitlement a handle on the whole portfolio -- which is what a
     * review found. No check is performed in this method: the chain refuses before the handler is
     * entered, and a second check here would either restate the rule or drift from it.
     *
     * @param selector the opaque selector taken from an operator's statement response
     * @return the artifact bytes, carrying the protective response headers
     * @throws NoSuchElementException if the selector names no artifact of the published run, or names
     *     one the store does not hold -- rendered as 404 by the shared handler
     */
    @GetMapping(path = ARTIFACTS_PATH, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> collectArtifact(
            @PathVariable(SELECTOR_VARIABLE) @Pattern(regexp = SELECTOR_PATTERN) String selector) {
        ArtifactStore.OpenArtifact artifact = statements.collectArtifact(selector);
        return protectiveBuilder()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.sizeBytes())
                .body(new InputStreamResource(artifact.content()));
    }

    /**
     * Decides how much of a statement run an answer to this caller may disclose.
     *
     * <p>Assumptions: the administrative group authority is the operator audience and everything else is
     * the cardholder audience, which matches the group rule on the collection route exactly -- so a
     * caller is only ever told about an artifact it is admitted to collect. The two group names are the
     * migration of the reference's two user kinds, the {@code 'A'} and {@code 'U'} values that
     * {@code app/cpy/COCOM01Y.cpy} L27 and L28 name, and the authority strings come from the shared
     * converter rather than being spelled here so the edge and the chain cannot disagree about them.
     *
     * <p>Assumptions: an ABSENT authentication is the cardholder audience. It cannot occur behind the
     * deployed chain, which authenticates every business address, so the value chosen decides only what
     * a context without the chain does -- and the narrowest answer is the only safe default for a
     * question about disclosure. Alternatives Considered: refusing outright when no authentication is
     * present, which would move an authorization decision out of the chain and into a handler and would
     * duplicate a refusal the chain already renders with this contract's error shape.
     *
     * @param authentication the authentication the chain established, or {@code null} when none is
     * @return the audience this caller's answer is assembled for; never {@code null}
     */
    private static StatementService.ArtifactAudience audienceOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return StatementService.ArtifactAudience.CARDHOLDER;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (JwtRoleConverter.ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return StatementService.ArtifactAudience.OPERATOR;
            }
        }
        return StatementService.ArtifactAudience.CARDHOLDER;
    }

    /**
     * Wraps a payload with the three protective headers every statement response carries.
     *
     * <p>Assumptions: the three are set together in one helper rather than being repeated per
     * operation, because a header re-typed at each operation is a header that will eventually be
     * typed differently -- and a statement response missing one of them would be indistinguishable,
     * from the outside, from one that never needed it.
     *
     * @param <T> the payload type
     * @param payload the body to return; must not be {@code null}
     * @return the payload with the protective headers applied
     */
    private static <T> ResponseEntity<T> protectively(T payload) {
        return protectiveBuilder().body(payload);
    }

    /**
     * Starts a response carrying the three protective headers, for a body that needs more of the
     * builder than {@link #protectively(Object)} exposes.
     *
     * <p>Assumptions: the three headers are set in ONE place and both response paths pass through it,
     * because the artifact response is the one that most needs them -- it is the only response whose
     * body is markup a browser could render -- and a second, hand-assembled builder is exactly how one
     * of the three would eventually be left off.
     *
     * @return a builder with the three protective headers already applied, never {@code null}
     */
    private static ResponseEntity.BodyBuilder protectiveBuilder() {
        return ResponseEntity.ok()
                .header(CONTENT_TYPE_OPTIONS_HEADER, NOSNIFF)
                .header(CONTENT_SECURITY_POLICY_HEADER, STATEMENT_POLICY)
                .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_DISPOSITION);
    }
}
