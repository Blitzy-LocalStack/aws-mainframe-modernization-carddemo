package com.carddemo.reference.service;

import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryListRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.mapper.TransactionCategoryMapper;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction-category rules, the child side of the transaction-reference feature.
 *
 * <p>Purpose: this class owns the category browse, read and the three write operations the contract
 * publishes. Its consumer is {@code com.carddemo.reference.api}.</p>
 *
 * <p>Assumptions: the browse orders on both key halves and a position therefore names both. The
 * position is sealed as one token carrying the two halves separated by a character neither half can
 * contain, so a caller never sees or assembles a composite key.</p>
 *
 * <p>Assumptions: a create that names a type which does not exist is refused by the foreign key the
 * migration declares rather than by a read here. That is the same authority a restricted delete rests
 * on, and pre-checking would put a second opinion beside a constraint that is already conclusive.</p>
 */
@Service
public class TransactionCategoryService {

    /** The cursor binding for this browse, distinct from every other browse in this service. */
    public static final String CURSOR_BINDING = "reference-transaction-category-list";

    /** The number of rows one page publishes, matching the type browse. */
    public static final int PAGE_SIZE = 10;

    /** The verbatim refusal when no category carries the key asked for. */
    public static final String MESSAGE_CATEGORY_NOT_FOUND = "Transaction category NOT found...";

    /**
     * The separator between the two key halves inside a sealed position.
     *
     * <p>Assumptions: a vertical bar, because neither half can contain one -- the type half is two
     * characters from a published pattern and the category half is four digits. A separator a half could
     * contain would make one position parse as another.</p>
     */
    private static final char POSITION_SEPARATOR = '|';

    /** Access to the category table. */
    private final TransactionCategoryRepository categories;

    /**
     * Builds the service over the repository it reads.
     *
     * @param categories access to the category table; must not be {@code null}
     */
    public TransactionCategoryService(TransactionCategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * Returns one keyset page of categories, optionally narrowed to a single type.
     *
     * <p>Assumptions: narrowing to one type changes which walks are used rather than filtering a page
     * after reading it. A filter applied after the bound would publish a page shorter than the window
     * and would make the further-page flag describe the unfiltered set.</p>
     *
     * @param request the validated paging and filter parameters; must not be {@code null}
     * @param cursorToken the sealer that mints and opens the opaque position; must not be {@code null}
     * @return one page of categories
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if a supplied position is not
     *     a position this browse minted
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionCategoryResponse> list(
            TransactionCategoryListRequest request, CursorToken cursorToken) {

        String position = request.cursor() == null
                ? null
                : cursorToken.open(CURSOR_BINDING, request.cursor());
        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);
        String typeFilter = request.typeCode();

        List<TransactionCategory> rows =
                walk(position, backward, limit, typeFilter);

        return ReferencePaging.page(rows, PAGE_SIZE, backward, CURSOR_BINDING, cursorToken,
                TransactionCategoryMapper::toResponse, TransactionCategoryService::positionOf);
    }

    /**
     * Chooses and performs the bounded walk the request describes.
     *
     * @param position the opened two-part position, or {@code null} on a first page
     * @param backward whether the walk is backward
     * @param limit the bound, one greater than the window
     * @param typeFilter the type to narrow to, or {@code null} for every type
     * @return the rows in ascending key order
     */
    private List<TransactionCategory> walk(
            String position, boolean backward, Limit limit, String typeFilter) {

        if (position == null) {
            return typeFilter == null
                    ? this.categories.findFirstPage(limit)
                    : this.categories.findFirstPageOfType(typeFilter, limit);
        }
        int separator = position.indexOf(POSITION_SEPARATOR);
        String typeHalf = position.substring(0, separator);
        String categoryHalf = position.substring(separator + 1);

        List<TransactionCategory> rows;
        if (backward) {
            rows = new ArrayList<>(typeFilter == null
                    ? this.categories.findPageBefore(typeHalf, categoryHalf, limit)
                    : this.categories.findPageOfTypeBefore(typeFilter, categoryHalf, limit));
            Collections.reverse(rows);
            return rows;
        }
        return typeFilter == null
                ? this.categories.findPageAfter(typeHalf, categoryHalf, limit)
                : this.categories.findPageOfTypeAfter(typeFilter, categoryHalf, limit);
    }

    /**
     * Renders the two-part ordering key of one row as the value a position seals.
     *
     * @param entity the row whose position is wanted
     * @return the two halves joined by the separator, never {@code null}
     */
    private static String positionOf(TransactionCategory entity) {
        return entity.getTypeCd() + POSITION_SEPARATOR + entity.getCatCd();
    }

    /**
     * Reads one category by both halves of its key.
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @return the category as the contract publishes it
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that key
     */
    @Transactional(readOnly = true)
    public TransactionCategoryResponse read(String typeCd, String catCd) {
        return TransactionCategoryMapper.toResponse(require(typeCd, catCd));
    }

    /**
     * Adds a category, refusing a key that already exists.
     *
     * @param request the validated create body; must not be {@code null}
     * @return the stored category as the contract publishes it
     * @throws RecordConflictException when a category already carries that key
     */
    @Transactional
    public TransactionCategoryResponse create(TransactionCategoryCreateRequest request) {
        TransactionCategoryId id =
                new TransactionCategoryId(request.typeCd(), request.catCd());
        if (this.categories.findByIdIs(id).isPresent()) {
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION);
        }
        return TransactionCategoryMapper.toResponse(
                this.categories.save(TransactionCategoryMapper.toNewEntity(request)));
    }

    /**
     * Replaces the description of an existing category, reporting a miss rather than inserting.
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @param request the validated replace body carrying the new description and the version read
     * @return the stored category as the contract publishes it
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that key
     * @throws RecordConflictException carrying the stored version when the version supplied is stale
     */
    @Transactional
    public TransactionCategoryResponse replace(
            String typeCd, String catCd, TransactionCategoryUpdateRequest request) {

        TransactionCategory stored = require(typeCd, catCd);
        if (stored.getVersion() != request.version()) {
            throw new RecordConflictException(
                    RecordConflictException.Kind.STALE_VERSION, stored.getVersion());
        }
        stored.setDescription(TransactionTypeMapper.trimForStorage(request.description()));
        return TransactionCategoryMapper.toResponse(this.categories.save(stored));
    }

    /**
     * Deletes a category.
     *
     * <p>Assumptions: a category is the child of the referential rule rather than its parent, so no
     * delete of one is ever refused for referential reasons. Nothing in this schema references it.</p>
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that key
     */
    @Transactional
    public void delete(String typeCd, String catCd) {
        this.categories.delete(require(typeCd, catCd));
    }

    /**
     * Reads a category or raises the verbatim refusal.
     *
     * @param typeCd the two-character type half
     * @param catCd the four-digit category half
     * @return the stored row, never {@code null}
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that key
     */
    private TransactionCategory require(String typeCd, String catCd) {
        Optional<TransactionCategory> found =
                this.categories.findByIdIs(new TransactionCategoryId(typeCd, catCd));
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_CATEGORY_NOT_FOUND);
        }
        return found.get();
    }
}
