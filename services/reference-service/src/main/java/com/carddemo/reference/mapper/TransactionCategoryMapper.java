package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;

/**
 * Converts between the transaction-category entity and its wire shapes.
 *
 * <p>Purpose: the single place the category record's representation concerns are resolved -- the
 * leading zeros of its numeric-pictured code, which must survive the round trip, and the padding of
 * its description.</p>
 *
 * <p>Assumptions: the category code is NOT trimmed and is not converted to a number at any point. Its
 * leading zeros are part of the key: the seed keys the row by positional concatenation, so a code
 * rendered as {@code 1} where the row stores {@code 0001} is a value a caller cannot echo back to
 * address the row it just read.</p>
 */
public final class TransactionCategoryMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private TransactionCategoryMapper() {
        throw new AssertionError("TransactionCategoryMapper is not instantiable");
    }

    /**
     * Renders one stored category as the shape the contract publishes.
     *
     * @param entity the stored row to render; must not be {@code null}
     * @return the response shape, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static TransactionCategoryResponse toResponse(TransactionCategory entity) {
        return new TransactionCategoryResponse(
                TransactionTypeMapper.trimTrailing(entity.getTypeCd()),
                entity.getCatCd(),
                TransactionTypeMapper.trimTrailing(entity.getDescription()),
                entity.getVersion());
    }

    /**
     * Builds a new entity from a create request.
     *
     * <p>Assumptions: both key halves are passed through exactly as validated, with no padding added
     * and none removed. The identity type checks each half against its declared width and refuses a
     * value that is short, so a code that lost its leading zeros upstream is refused here rather than
     * stored as a second row for one logical category.</p>
     *
     * @param request the validated create body; must not be {@code null}
     * @return a new unsaved entity, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws IllegalArgumentException if either key half is not exactly its declared width
     */
    public static TransactionCategory toNewEntity(TransactionCategoryCreateRequest request) {
        // WHY : Assumptions: the description is trimmed on the way in and neither key half is. The
        //       category code's leading zeros are load-bearing and its width is exact, so a trim on
        //       it would be the one edit that turns a valid key into a key matching no row.
        return new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(request.typeCd(), request.catCd()),
                TransactionTypeMapper.trimForStorage(request.description()));
    }
}
