package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import com.carddemo.reference.domain.UsState;
import com.carddemo.reference.domain.UsStateZipPrefix;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import com.carddemo.reference.dto.UsStateResponse;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;

/**
 * Converts the three seeded address allow-lists into their wire shapes.
 *
 * <p>Purpose: one mapper for the three lookup tables rather than three, because the conversion is the
 * same in each case -- a fixed-width code read out and published as it stands.</p>
 *
 * <p>Assumptions: none of these three values is trimmed, and that is the difference between this
 * mapper and the two that handle descriptions. Every one of these codes occupies its declared width
 * exactly: three characters for an area code, two for a state, four for a state-and-prefix pair. There
 * is no padding to remove, so a trim would be a no-op that implied padding might exist -- and on the
 * prefix it would be actively wrong, since trimming a value whose halves are concatenated would
 * silently accept a three-character key.</p>
 */
public final class LookupMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private LookupMapper() {
        throw new AssertionError("LookupMapper is not instantiable");
    }

    /**
     * Renders one area code and its classification.
     *
     * @param entity the stored row; must not be {@code null}
     * @return the response shape, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static PhoneAreaCodeResponse toResponse(UsPhoneAreaCode entity) {
        return new PhoneAreaCodeResponse(entity.getAreaCode(), entity.getCodeClass());
    }

    /**
     * Renders one state code.
     *
     * @param entity the stored row; must not be {@code null}
     * @return the response shape, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static UsStateResponse toResponse(UsState entity) {
        return new UsStateResponse(entity.getStateCode());
    }

    /**
     * Renders one state-and-postal-prefix pair.
     *
     * @param entity the stored row; must not be {@code null}
     * @return the response shape, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static UsStateZipPrefixResponse toResponse(UsStateZipPrefix entity) {
        return new UsStateZipPrefixResponse(entity.getStateZipCd());
    }
}
