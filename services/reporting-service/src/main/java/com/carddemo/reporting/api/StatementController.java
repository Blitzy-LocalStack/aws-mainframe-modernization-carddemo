package com.carddemo.reporting.api;

import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionCollection;
import com.carddemo.reporting.service.StatementService;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary of the statement surface, replacing a pair of batch statement programs.
 *
 * <p>This controller carries the two operations that surface {@code app/cbl/CBSTM03A.CBL} at 924
 * lines and {@code app/cbl/CBSTM03B.CBL} at 230 lines. The upper-case extension on each is
 * load-bearing: a lower-case citation of either one is a dead reference. The baseline is reference
 * material, read as the specification and never modified.
 *
 * <p>Two operations are exposed and they differ in what part of one statement they return.
 * {@link #generateStatement(StatementRequest)} returns the heading figures, the assembled total and
 * the two artifact locations; {@link #listStatementTransactions(StatementRequest)} returns the
 * transactions those artifacts were built from, and nothing else. A caller rendering the lines takes
 * the second; a caller that only needs to know where the artifacts are, or what the statement
 * totals, takes the first and does not pay to transport the row window it would discard, which
 * {@link StatementService#MAX_RESPONSE_TRANSACTIONS} caps at 1,000.
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
 * <p>Alternatives Considered: an operation returning a rendered statement artifact itself, plain-text
 * or markup. Rejected because this module is granted no object-store client: the two artifacts are
 * written by the batch task and delivered from object storage through the content distribution, and a
 * fetch-and-relay operation here would put this service on the delivery path for a document it does
 * not own. What the description returns instead is the two locations, so the caller reaches the
 * artifact through the path that owns it.
 *
 * <p>Assumptions: no exception handler and no authority annotation is declared here, for the reasons
 * recorded on {@link ReportController} and in the package charter. A card with no cross-reference,
 * customer or account row raises {@link NoSuchElementException}, which the shared advice renders as
 * the not-found status this service publishes.
 */
@RestController
@RequestMapping(path = StatementController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@OnlineWriteGateExempt(reason =
        "Both operations render an existing statement and persist nothing. They are POSTs because"
        + " the account and card identifiers they select on must travel in a request body rather"
        + " than in a request line the load balancer records. A rendered statement is a read of"
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
    public static final String BASE_PATH = "/api/v1/reports/statements";

    /**
     * Sub-path of the whole-document operation.
     */
    public static final String TRANSACTIONS_PATH = "/transactions";

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
     * Describes one card's statement: its heading figures, its total and its two artifact locations.
     *
     * @param request the card whose statement is wanted, and optionally the account it is expected to
     *     belong to; validated declaratively before this method is entered
     * @return the statement description, carrying the protective response headers
     * @throws NoSuchElementException if the card has no cross-reference, customer or account row
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatementResponse> generateStatement(
            @Valid @RequestBody StatementRequest request) {
        return protectively(statements.describe(request));
    }

    /**
     * Returns the transactions one card's statement was rendered from.
     *
     * <p>Refactoring Rationale: the body carries the TRANSACTIONS alone, where an earlier revision
     * returned the whole document -- the heading summary and the transactions together. The published
     * contract declares two operations here, one for the summary and one for the rows, and the reason
     * is size rather than taste: a statement carries as many rows as the card has activity, a count no
     * contract fixes, and a caller that wants only the heading figures and the two artifact locations
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
     * @return the statement's transactions, carrying the protective response headers
     * @throws NoSuchElementException if the card has no cross-reference, customer or account row
     */
    @PostMapping(path = TRANSACTIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatementTransactionCollection> listStatementTransactions(
            @Valid @RequestBody StatementRequest request) {
        // WHY : Assumptions: the transactions are taken from the composed document rather than read
        //       separately, so the rows this operation returns are the rows the rendered artifacts were
        //       built from. A second, independent query would be able to return a row the rendered
        //       statement does not contain, and a caller reconciling the two would have no way to tell
        //       which one was the statement.
        StatementDocument document = statements.compose(request);
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
        return ResponseEntity.ok()
                .header(CONTENT_TYPE_OPTIONS_HEADER, NOSNIFF)
                .header(CONTENT_SECURITY_POLICY_HEADER, STATEMENT_POLICY)
                .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_DISPOSITION)
                .body(payload);
    }
}
