package com.carddemo.reference.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.service.TransactionTypeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five transaction-type operations the contract publishes.
 *
 * <p>Purpose: binds and validates the transaction-type browse, read, create, replace and delete, and
 * delegates each to {@code TransactionTypeService}. It decides nothing.</p>
 */
@RestController
@RequestMapping(TransactionTypeController.BASE_PATH)
public class TransactionTypeController {

    /** The collection path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/transaction-types";

    /** The item path, relative to the collection. */
    public static final String ITEM_PATH = "/{typeCd}";

    /** The path variable naming the type code. */
    public static final String PARAM_TYPE_CD = "typeCd";

    /** The query parameter carrying the paging position. */
    public static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction. */
    public static final String PARAM_DIRECTION = "direction";

    /** The query parameter carrying the type-code filter. */
    public static final String PARAM_TYPE_CODE = "typeCode";

    /** The query parameter carrying the description filter. */
    public static final String PARAM_DESCRIPTION = "description";

    /** The rules this controller delegates to. */
    private final TransactionTypeService service;

    /** The sealer that mints and opens an opaque paging position. */
    private final CursorToken cursorToken;

    /**
     * Builds the controller over its two collaborators.
     *
     * @param service the transaction-type rules; must not be {@code null}
     * @param cursorToken the position sealer; must not be {@code null}
     */
    public TransactionTypeController(TransactionTypeService service, CursorToken cursorToken) {
        this.service = service;
        this.cursorToken = cursorToken;
    }

    /**
     * Answers one keyset page of transaction types.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction, absent meaning forward
     * @param typeCode an exact type-code filter, absent meaning unfiltered
     * @param description a description filter, absent meaning unfiltered
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position is not transferable between callers
     * @return one page of types with its sealed positions
     */
    @GetMapping
    public PageResponse<TransactionTypeResponse> listTransactionTypes(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false) PageDirection direction,
            @RequestParam(name = PARAM_TYPE_CODE, required = false)
            @Size(min = TransactionTypeListRequest.TYPE_CODE_LENGTH,
                    max = TransactionTypeListRequest.TYPE_CODE_LENGTH)
            @Pattern(regexp = TransactionTypeListRequest.TYPE_CODE_PATTERN) String typeCode,
            @RequestParam(name = PARAM_DESCRIPTION, required = false)
            @Size(min = TransactionTypeListRequest.DESCRIPTION_MIN_LENGTH,
                    max = TransactionTypeListRequest.DESCRIPTION_MAX_LENGTH) String description,
            Principal principal) {

        return this.service.list(
                new TransactionTypeListRequest(cursor, direction, typeCode, description),
                this.cursorToken, principal.getName());
    }

    /**
     * Answers one transaction type by its code.
     *
     * <p>Assumptions: the segment is constrained to the {@code TransactionTypeCode} schema its published
     * path parameter references, so a code outside that domain is refused with the 400 the contract
     * publishes rather than read for and reported absent. Unconstrained, a two-character value the domain
     * excludes -- or a value of the wrong width entirely -- reached the keyed read, matched nothing, and was
     * answered 404 'Transaction type NOT found...', which tells a caller the row is missing when what is
     * actually wrong is the request.</p>
     *
     * @param typeCd the two-character code to read
     * @return the type as the contract publishes it
     */
    @GetMapping(path = ITEM_PATH)
    public TransactionTypeResponse getTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd) {
        return this.service.read(typeCd);
    }

    /**
     * Adds a transaction type.
     *
     * @param request the create body
     * @return the stored type as the contract publishes it
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionTypeResponse createTransactionType(
            @Valid @RequestBody TransactionTypeCreateRequest request) {
        return this.service.create(request);
    }

    /**
     * Replaces the description of an existing transaction type.
     *
     * @param typeCd the code of the type to replace
     * @param request the replace body carrying the new description and the version read
     * @return the stored type as the contract publishes it
     */
    @PutMapping(path = ITEM_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionTypeResponse replaceTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd,
            @Valid @RequestBody TransactionTypeUpdateRequest request) {
        return this.service.replace(typeCd, request);
    }

    /**
     * Deletes a transaction type.
     *
     * <p>Assumptions: no body is returned, which is what the contract declares by answering 204. A type
     * whose categories still exist is refused with 409 by the constraint rather than by this method.</p>
     *
     * @param typeCd the code of the type to delete
     */
    @DeleteMapping(path = ITEM_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd) {
        this.service.delete(typeCd);
    }
}
