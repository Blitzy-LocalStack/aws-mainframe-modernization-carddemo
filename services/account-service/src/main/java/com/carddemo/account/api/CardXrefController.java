package com.carddemo.account.api;

import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.service.AccountViewService;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the card cross-reference lookup this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the read
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} performs at its paragraph
 * {@code 5100-READ-XREF-RECORD}. In the reference system that program reads {@code CCXREF} directly, because
 * every file in the region is reachable from every program in it. In the migrated system the cross-reference
 * belongs to this context, so the read becomes a call on this context's published contract -- which is what
 * keeps one owner per table and one place the row's representation is decided.</p>
 *
 * <p>Assumptions: the operation is a {@code POST} although it is a read with no side effect, and the reason
 * is disclosure rather than semantics. Its key is a primary account number, and a request path or query
 * string is recorded by the load balancer's access log, by the gateway's execution log and by every
 * intermediary between them -- none of which the migration's security mapping permits to hold that value. A
 * request body is written to none of them. The trade-off this accepts, and why it costs nothing for the one
 * consumer, is recorded on {@link CardXrefLookupRequest}.</p>
 *
 * <p>Assumptions: an absent row is answered with 404 and never with a 200 carrying an empty document. The
 * consumer distinguishes the two and depends on the distinction: it treats 404 as a decision input -- the
 * reference declines such a request with its card-not-found reason -- and treats a 200 whose body it cannot
 * build a cross-reference from as a dependency failure that rolls its transaction back. A 200 with nothing in
 * it would therefore be read as a broken contract rather than as an absent card, which is the opposite of
 * what it would mean.</p>
 *
 * <p>Assumptions: the handler carries no business rule at all. It validates, reads, projects and reports
 * absence; the decision about what an absent card means belongs to the caller, because it is the caller that
 * has an authorization to decide.</p>
 */
@RestController
@RequestMapping(CardXrefController.BASE_PATH)
public class CardXrefController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: this is a constant rather than a literal in the annotation, so that the load-balancer
     * rule and the gateway route that forward this prefix can be checked against it by a test rather than by
     * a reader comparing two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/card-xrefs";

    /**
     * The path segment the lookup operation sits at, relative to {@link #BASE_PATH}.
     */
    public static final String LOOKUP_PATH = "/lookup";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, not a repository and not the mapper. The
     * charter for this package forbids both of those here, and the migration plan assigns this layer binding
     * and validation while assigning the rules one layer down; a controller holding a repository would place
     * the transaction boundary at the HTTP binding and would make the boundary unenforceable.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @throws NullPointerException if {@code reads} is {@code null}
     */
    public CardXrefController(AccountViewService reads) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
    }

    /**
     * Resolves a primary account number to the account and customer it belongs to.
     *
     * <p>Assumptions: the transaction boundary sits on the service method this handler calls rather than on
     * this handler, so the read-only declaration governs the unit of work rather than the request binding.</p>
     *
     * <p>Assumptions: the absence is signalled by throwing rather than by returning an empty body, and the
     * exception type is the one the shared advice already renders as 404 with this system's problem document.
     * Returning a bare 404 from here instead would answer without the body every published contract in this
     * migration declares for a not-found.</p>
     *
     * @param request the lookup request carrying the card number; must satisfy its declared constraints
     * @return the account and customer the card resolves to
     * @throws NoSuchElementException if the card is not cross-referenced, which the shared advice renders as
     *     404 -- and which the consumer reads as its card-not-found decision input
     */
    @PostMapping(path = LOOKUP_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardXrefView lookup(@Valid @RequestBody CardXrefLookupRequest request) {
        return this.reads.resolveCardCrossReference(request.cardNumber());
    }
}
