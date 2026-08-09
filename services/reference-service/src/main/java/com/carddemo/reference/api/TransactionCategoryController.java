package com.carddemo.reference.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryListRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.service.TransactionCategoryService;
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
 * The five transaction-category operations the contract publishes.
 *
 * <p>Purpose: binds and validates the category browse, read, create, replace and delete, and delegates
 * each to {@code TransactionCategoryService}.</p>
 *
 * <p>Assumptions: both key halves travel as path segments in the order the contract declares, type then
 * category, and neither appears in a body. A key stated twice is a key that can disagree with itself.</p>
 */
@RestController
@RequestMapping(TransactionCategoryController.BASE_PATH)
public class TransactionCategoryController {

    /** The collection path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/transaction-categories";

    /** The item path, relative to the collection, carrying both key halves in order. */
    public static final String ITEM_PATH = "/{typeCd}/{catCd}";

    /** The path variable naming the type half. */
    public static final String PARAM_TYPE_CD = "typeCd";

    /** The path variable naming the category half. */
    public static final String PARAM_CAT_CD = "catCd";

    /** The query parameter carrying the paging position. */
    public static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction. */
    public static final String PARAM_DIRECTION = "direction";

    /** The query parameter carrying the type-code filter. */
    public static final String PARAM_TYPE_CODE = "typeCode";

    /** The query parameter carrying the description filter. */
    public static final String PARAM_DESCRIPTION = "description";

    /** The rules this controller delegates to. */
    private final TransactionCategoryService service;

    /** The sealer that mints and opens an opaque paging position. */
    private final CursorToken cursorToken;

    /**
     * Builds the controller over its two collaborators.
     *
     * @param service the category rules; must not be {@code null}
     * @param cursorToken the position sealer; must not be {@code null}
     */
    public TransactionCategoryController(
            TransactionCategoryService service, CursorToken cursorToken) {
        this.service = service;
        this.cursorToken = cursorToken;
    }

    /**
     * Answers one keyset page of categories, optionally narrowed to one type.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction, absent meaning forward
     * @param typeCode an exact type-code filter, absent meaning every type
     * @param description a description filter, absent meaning unfiltered
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position is not transferable between callers
     * @return one page of categories with its sealed positions
     */
    @GetMapping
    public PageResponse<TransactionCategoryResponse> listTransactionCategories(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false) PageDirection direction,
            @RequestParam(name = PARAM_TYPE_CODE, required = false)
            @Size(min = TransactionCategoryListRequest.TYPE_CODE_LENGTH,
                    max = TransactionCategoryListRequest.TYPE_CODE_LENGTH)
            @Pattern(regexp = TransactionCategoryListRequest.TYPE_CODE_PATTERN) String typeCode,
            @RequestParam(name = PARAM_DESCRIPTION, required = false)
            @Size(min = TransactionCategoryListRequest.DESCRIPTION_MIN_LENGTH,
                    max = TransactionCategoryListRequest.DESCRIPTION_MAX_LENGTH) String description,
            Principal principal) {

        return this.service.list(
                new TransactionCategoryListRequest(cursor, direction, typeCode, description),
                this.cursorToken, principal.getName());
    }

    /**
     * Answers one category by both halves of its key.
     *
     * <p>Assumptions: both segments are constrained to the schemas their published path parameters
     * reference, so a malformed segment is refused BEFORE the value reaches the composite identity type.
     * That ordering is the point rather than a nicety: the identity type refuses a component of the wrong
     * width with a bare {@link IllegalArgumentException}, the shared advice tests for the caller-refusal
     * subtype and deliberately not for its supertype, and the result was a 500 telling a caller its own
     * malformed path was the service's fault. All three of this record's item routes share the identity
     * type and therefore shared the defect, which is why all three carry the constraints.</p>
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @return the category as the contract publishes it
     */
    @GetMapping(path = ITEM_PATH)
    public TransactionCategoryResponse getTransactionCategory(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd,
            @PathVariable(name = PARAM_CAT_CD)
            @Size(min = TransactionCategory.CAT_CD_WIDTH, max = TransactionCategory.CAT_CD_WIDTH)
            @Pattern(regexp = TransactionCategory.CAT_CD_PATTERN) String catCd) {
        return this.service.read(typeCd, catCd);
    }

    /**
     * Adds a category.
     *
     * @param request the create body carrying both key halves and the description
     * @return the stored category as the contract publishes it
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCategoryResponse createTransactionCategory(
            @Valid @RequestBody TransactionCategoryCreateRequest request) {
        return this.service.create(request);
    }

    /**
     * Replaces the description of an existing category.
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @param request the replace body carrying the new description and the version read
     * @return the stored category as the contract publishes it
     */
    @PutMapping(path = ITEM_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionCategoryResponse replaceTransactionCategory(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd,
            @PathVariable(name = PARAM_CAT_CD)
            @Size(min = TransactionCategory.CAT_CD_WIDTH, max = TransactionCategory.CAT_CD_WIDTH)
            @Pattern(regexp = TransactionCategory.CAT_CD_PATTERN) String catCd,
            @Valid @RequestBody TransactionCategoryUpdateRequest request) {
        return this.service.replace(typeCd, catCd, request);
    }

    /**
     * Deletes a category.
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     */
    @DeleteMapping(path = ITEM_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransactionCategory(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd,
            @PathVariable(name = PARAM_CAT_CD)
            @Size(min = TransactionCategory.CAT_CD_WIDTH, max = TransactionCategory.CAT_CD_WIDTH)
            @Pattern(regexp = TransactionCategory.CAT_CD_PATTERN) String catCd) {
        this.service.delete(typeCd, catCd);
    }
}
