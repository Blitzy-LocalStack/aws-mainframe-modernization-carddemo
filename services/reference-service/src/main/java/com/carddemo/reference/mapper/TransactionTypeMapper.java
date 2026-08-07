package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;

/**
 * Converts between the transaction-type entity and its wire shapes.
 *
 * <p>Purpose: the single place the transaction-type record's representation concerns are resolved --
 * the trailing padding of its fixed-character key, and the fact that its version is a target addition
 * rather than a baseline field.</p>
 *
 * <p>Assumptions: this class is stateless and final with a private constructor, so it is not a
 * component and cannot be instantiated. A conversion that depends on nothing gains nothing from being
 * a bean.</p>
 */
public final class TransactionTypeMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private TransactionTypeMapper() {
        // WHY : Assumptions: a private constructor rather than an abstract class, because an abstract
        //       class invites a subclass and this type has no behaviour to extend.
        throw new AssertionError("TransactionTypeMapper is not instantiable");
    }

    /**
     * Renders one stored type as the shape the contract publishes.
     *
     * <p>Assumptions: the outbound trim is defence for a value this service did not write. The
     * inbound path below trims a description before it is stored, so a row written through this
     * service carries none; a row loaded by the extract-transform-load path out of a fixed-width
     * source can. Trimming here is therefore not the same act as trimming inbound, and it is not a
     * re-pad either: the package convention is that a description is trimmed on the way in and never
     * padded again on the way out, and this preserves that for rows that arrived by another route.</p>
     *
     * <p>Assumptions: the key is passed through the same helper only because it is a no-op on it. The
     * column is {@code CHAR(2)} and a valid code occupies both characters, so there is nothing to
     * remove -- what would be wrong is trimming a key whose padding is significant, and this key's is
     * not, unlike the disclosure group's.</p>
     *
     * @param entity the stored row to render; must not be {@code null}
     * @return the response shape, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static TransactionTypeResponse toResponse(TransactionType entity) {
        return new TransactionTypeResponse(
                trimTrailing(entity.getTypeCd()),
                trimTrailing(entity.getDescription()),
                entity.getVersion());
    }

    /**
     * Builds a new entity from a create request.
     *
     * <p>Assumptions: the version is not taken from the request and cannot be. It is written by the
     * persistence provider, the migration defaults it to zero, and the create shape carries no version
     * member at all -- there is no revision to state for a row that does not yet exist.</p>
     *
     * @param request the validated create body; must not be {@code null}
     * @return a new unsaved entity, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public static TransactionType toNewEntity(TransactionTypeCreateRequest request) {
        // WHY : Assumptions: the description is trimmed HERE, on the way in, which is the package
        //       convention and not merely tidiness. The column is VARCHAR, so it stores exactly what
        //       it is given; a value stored with trailing blanks would then compare unequal to the
        //       same text without them in every later filter, and no constraint would report it.
        //       The key is NOT trimmed, because its declared width is part of the contract.
        return new TransactionType(
                request.typeCd(), trimForStorage(request.description()));
    }

    /**
     * Normalises a description for storage, which for this context means removing trailing blanks.
     *
     * <p>Purpose: this is the inbound half of the package trim boundary, exposed for the service layer
     * so that a replace stores the same normalised form a create does. It is a separate member from the
     * outbound helper deliberately: the two are the same operation today, and naming them separately is
     * what lets one change without silently changing the other -- an outbound trim protects a value
     * loaded by another path, while this one decides what is stored.</p>
     *
     * <p>Assumptions: this must never be applied to a key. A fixed-character key's declared width is
     * part of the contract, and the disclosure group's padding is load-bearing.</p>
     *
     * @param description the description a caller supplied, possibly {@code null}
     * @return the value without trailing blanks, or {@code null} when the input was {@code null}
     */
    public static String trimForStorage(String description) {
        return trimTrailing(description);
    }

    /**
     * Removes trailing blanks, treating an absent value as absent.
     *
     * <p>Assumptions: only the trailing side is stripped. A leading blank in a description is content
     * the source record can hold, and removing it would change a published value rather than remove
     * padding.</p>
     *
     * @param value the stored value, possibly {@code null}
     * @return the value without trailing blanks, or {@code null} when the input was {@code null}
     */
    static String trimTrailing(String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
