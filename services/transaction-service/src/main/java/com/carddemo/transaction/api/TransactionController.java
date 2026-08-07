package com.carddemo.transaction.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the three operations of the transaction resource.
 *
 * <p>Purpose: this class makes the browse, the detail read and the capture reachable. Before it existed the
 * module compiled, its services were tested and its contract was published, and no request could reach any
 * of them, which is the defect it closes.</p>
 *
 * <p>Assumptions: the paged browse takes its filter, cursor and direction as query parameters rather than
 * as a request body, because a browse is a read and a read with a body cannot be cached, bookmarked or
 * retried by an intermediary. The reference carries the same three values in its communication area, which
 * is neither a body nor a query string, so nothing about the reference decides this.</p>
 */
@RestController
@RequestMapping(path = TransactionController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionController {

    /** The collection path the transaction resource is served at. */
    public static final String BASE_PATH = "/api/v1/transactions";

    /** The member path a single transaction is addressed by. */
    public static final String ITEM_PATH = "/{transactionId}";

    /** The query parameter the browse's transaction-identifier filter is supplied in. */
    public static final String PARAM_TRANSACTION_ID = "transactionId";

    /** The query parameter the browse's opaque cursor is supplied in. */
    public static final String PARAM_CURSOR = "cursor";

    /** The query parameter the browse's direction is supplied in. */
    public static final String PARAM_DIRECTION = "direction";

    /** The paged browse this controller delegates its list operation to. */
    private final TransactionListService listService;

    /** The detail read this controller delegates its member operation to. */
    private final TransactionViewService viewService;

    /** The capture this controller delegates its create operation to. */
    private final TransactionAddService addService;

    /** The cursor seam, held here because key material must not reach the service layer. */
    private final CursorToken cursorToken;

    /**
     * Builds the controller over the three services and the cursor seam.
     *
     * @param listService the paged browse; must not be {@code null}
     * @param viewService the detail read; must not be {@code null}
     * @param addService the capture; must not be {@code null}
     * @param cursorToken the sealing and opening seam for the browse cursor; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionController(TransactionListService listService, TransactionViewService viewService,
            TransactionAddService addService, CursorToken cursorToken) {
        this.listService = Objects.requireNonNull(listService, "listService must not be null");
        this.viewService = Objects.requireNonNull(viewService, "viewService must not be null");
        this.addService = Objects.requireNonNull(addService, "addService must not be null");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken must not be null");
    }

    /**
     * Reads one page of transactions in the direction requested.
     *
     * @param transactionIdFilter the sixteen digit identifier to restrict the page to, or {@code null}
     *     when the whole relation is browsed
     * @param cursor the opaque cursor the previous page reported, or {@code null} for the first page
     * @param direction the direction to read in, or {@code null} to take the request's own default
     * @param principal the authenticated caller, supplied by the framework; the page's boundary tokens
     *     are bound to its name, so a cursor issued to another operator is refused rather than honoured
     * @return the page envelope carrying its rows and its two boundary keys, never {@code null}
     */
    @GetMapping
    public PageResponse<TransactionListItemResponse> listTransactions(
            @RequestParam(name = PARAM_TRANSACTION_ID, required = false) String transactionIdFilter,
            @RequestParam(name = PARAM_CURSOR, required = false) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false)
                    TransactionListRequest.Direction direction,
            Principal principal) {

        TransactionListRequest request =
                new TransactionListRequest(transactionIdFilter, cursor, direction);
        // WHY : Assumptions: the authenticated name is passed DOWN rather than read by the service from
        //       a thread-bound security context. The service folds it into the cursor binding, so a
        //       page's tokens open only for the caller they were issued to; taking it as a parameter is
        //       what keeps a null subject a refusal instead of a silently anonymous binding, and it is
        //       the same arrangement the authorization context's selector-bearing handlers use.
        return this.listService.listTransactions(request, this.cursorToken, principal.getName());
    }

    /**
     * Reads one transaction by its identifier.
     *
     * @param transactionId the sixteen digit identifier taken from the request path, never {@code null}
     * @return the detail shape for that transaction, never {@code null}
     */
    @GetMapping(path = ITEM_PATH)
    public TransactionDetailResponse viewTransaction(
            @PathVariable(name = PARAM_TRANSACTION_ID) String transactionId) {
        return this.viewService.viewTransaction(transactionId);
    }

    /**
     * Captures a transaction, or answers with the prompt the reference answers an unconfirmed turn with.
     *
     * <p>Assumptions: the response status is 200 on every outcome this method returns normally, including
     * the confirmed capture. A created status would be the ordinary choice, and it is not taken because the
     * same shape is returned for an unconfirmed turn that wrote nothing, so a status distinguishing the two
     * would have to be decided from the presence of a generated identifier -- which is a business
     * discrimination made in a controller. The published contract declares both statuses for this
     * operation and describes which outcome each names.</p>
     *
     * @param request the submitted capture, bean-validated before this method is entered; must not be
     *     {@code null}
     * @return the acknowledgement carrying the generated identifier when the capture was written, and the
     *     prompt shape carrying no identifier otherwise; never {@code null}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionAddResponse addTransaction(@Valid @RequestBody TransactionAddRequest request) {
        return this.addService.addTransaction(request);
    }
}
