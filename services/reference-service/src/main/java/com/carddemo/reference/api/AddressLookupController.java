package com.carddemo.reference.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.UsPhoneAreaCode;
import com.carddemo.reference.domain.UsState;
import com.carddemo.reference.domain.UsStateZipPrefix;
import com.carddemo.reference.dto.LookupPageRequest;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import com.carddemo.reference.dto.UsStateResponse;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;
import com.carddemo.reference.mapper.LookupMapper;
import com.carddemo.reference.repository.UsPhoneAreaCodeRepository;
import com.carddemo.reference.repository.UsStateRepository;
import com.carddemo.reference.repository.UsStateZipPrefixRepository;
import com.carddemo.reference.service.ReferencePaging;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six operations over the three seeded address allow-lists.
 *
 * <p>Purpose: binds the browse and the read of the area-code, state and state-and-postal-prefix tables.
 * Each of the three is a membership test with no rule beyond whether a row exists, so this controller
 * reads its repositories directly.</p>
 *
 * <p>Alternatives Considered: a service layer between these six operations and their three tables was
 * evaluated and rejected, and the sibling {@code service} charter records the same decision -- it would
 * forward six calls and add a file. The two transaction-reference tables are the opposite case, with
 * version comparison, referential refusal and three distinct write behaviours, and every one of those
 * goes through a service.</p>
 *
 * <p>Alternatives Considered: three controllers, one per table, were evaluated and rejected. They would
 * be the same file three times over -- the same paging assembly, the same not-found refusal, the same
 * absent authority annotation -- for three tables that exist to answer one question, whether an address
 * a caller supplied is one this system admits. Declaring the three base paths as constants and mapping
 * each method to its own keeps the contract's paths exactly while writing the shared shape once.</p>
 *
 * <p>Assumptions: none of these three values is trimmed or padded anywhere on this path. Every one
 * occupies its declared width exactly, so there is nothing to remove, and on the prefix a trim would be
 * actively wrong -- its two halves are concatenated, so a shortened key would probe for something the
 * baseline never probes for.</p>
 */
@RestController
public class AddressLookupController {

    /** The area-code collection path the contract declares. */
    public static final String AREA_CODE_PATH = "/api/v1/reference/us-phone-area-codes";

    /** The state collection path the contract declares. */
    public static final String STATE_PATH = "/api/v1/reference/us-states";

    /** The state-and-postal-prefix collection path the contract declares. */
    public static final String ZIP_PREFIX_PATH = "/api/v1/reference/us-state-zip-prefixes";

    /** The cursor binding of the area-code browse. */
    public static final String AREA_CODE_BINDING = "reference-area-code-list";

    /** The cursor binding of the state browse. */
    public static final String STATE_BINDING = "reference-state-list";

    /** The cursor binding of the prefix browse. */
    public static final String ZIP_PREFIX_BINDING = "reference-state-zip-prefix-list";

    /** The number of rows one lookup page publishes. */
    public static final int PAGE_SIZE = 20;

    /** The verbatim refusal when no seeded area code matches. */
    public static final String MESSAGE_AREA_CODE_NOT_FOUND = "Phone area code NOT found...";

    /** The verbatim refusal when no seeded state matches. */
    public static final String MESSAGE_STATE_NOT_FOUND = "State code NOT found...";

    /** The verbatim refusal when no seeded prefix matches. */
    public static final String MESSAGE_ZIP_PREFIX_NOT_FOUND = "State ZIP prefix NOT found...";

    /** The query parameter carrying the paging position. */
    private static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction. */
    private static final String PARAM_DIRECTION = "direction";

    /** The query parameter carrying the area-code classification filter. */
    private static final String PARAM_CODE_CLASS = "codeClass";

    /** Access to the seeded area codes. */
    private final UsPhoneAreaCodeRepository areaCodes;

    /** Access to the seeded state codes. */
    private final UsStateRepository states;

    /** Access to the seeded state-and-prefix pairs. */
    private final UsStateZipPrefixRepository zipPrefixes;

    /** The sealer that mints and opens an opaque paging position. */
    private final CursorToken cursorToken;

    /**
     * Builds the controller over the three lookup tables and the position sealer.
     *
     * @param areaCodes access to the seeded area codes; must not be {@code null}
     * @param states access to the seeded state codes; must not be {@code null}
     * @param zipPrefixes access to the seeded state-and-prefix pairs; must not be {@code null}
     * @param cursorToken the position sealer; must not be {@code null}
     */
    public AddressLookupController(
            UsPhoneAreaCodeRepository areaCodes,
            UsStateRepository states,
            UsStateZipPrefixRepository zipPrefixes,
            CursorToken cursorToken) {
        this.areaCodes = areaCodes;
        this.states = states;
        this.zipPrefixes = zipPrefixes;
        this.cursorToken = cursorToken;
    }

    /**
     * Answers one keyset page of seeded area codes, optionally narrowed to one classification.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction, absent meaning forward
     * @param codeClass the classification filter, absent meaning unfiltered
     * @return one page of area codes with its sealed positions
     */
    @GetMapping(path = AREA_CODE_PATH)
    public PageResponse<PhoneAreaCodeResponse> listUsPhoneAreaCodes(
            @RequestParam(name = PARAM_CURSOR, required = false) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false) PageDirection direction,
            @RequestParam(name = PARAM_CODE_CLASS, required = false) String codeClass) {

        LookupPageRequest request = new LookupPageRequest(cursor, direction, codeClass);
        String position = position(request, AREA_CODE_BINDING);
        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);

        List<UsPhoneAreaCode> rows;
        if (codeClass == null) {
            rows = position == null
                    ? this.areaCodes.findAllByOrderByAreaCodeAsc(limit)
                    : backward
                            ? reversed(this.areaCodes
                                    .findByAreaCodeLessThanOrderByAreaCodeDesc(position, limit))
                            : this.areaCodes
                                    .findByAreaCodeGreaterThanOrderByAreaCodeAsc(position, limit);
        } else {
            rows = position == null
                    ? this.areaCodes.findByCodeClassOrderByAreaCodeAsc(codeClass, limit)
                    : backward
                            ? reversed(this.areaCodes
                                    .findByCodeClassAndAreaCodeLessThanOrderByAreaCodeDesc(
                                            codeClass, position, limit))
                            : this.areaCodes
                                    .findByCodeClassAndAreaCodeGreaterThanOrderByAreaCodeAsc(
                                            codeClass, position, limit);
        }
        return ReferencePaging.page(
                rows, PAGE_SIZE, backward, AREA_CODE_BINDING, this.cursorToken,
                LookupMapper::toResponse, UsPhoneAreaCode::getAreaCode);
    }

    /**
     * Answers one seeded area code by its value.
     *
     * @param areaCd the three-digit code to read
     * @return the area code and its classification
     * @throws NoSuchElementException carrying the verbatim refusal when the code is not seeded
     */
    @GetMapping(path = AREA_CODE_PATH + "/{areaCd}")
    public PhoneAreaCodeResponse getUsPhoneAreaCode(@PathVariable(name = "areaCd") String areaCd) {
        Optional<UsPhoneAreaCode> found = this.areaCodes.findByAreaCode(areaCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_AREA_CODE_NOT_FOUND);
        }
        return LookupMapper.toResponse(found.get());
    }

    /**
     * Answers one keyset page of seeded state codes.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction, absent meaning forward
     * @return one page of state codes with its sealed positions
     */
    @GetMapping(path = STATE_PATH)
    public PageResponse<UsStateResponse> listUsStates(
            @RequestParam(name = PARAM_CURSOR, required = false) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false) PageDirection direction) {

        LookupPageRequest request = new LookupPageRequest(cursor, direction, null);
        String position = position(request, STATE_BINDING);
        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);

        List<UsState> rows = position == null
                ? this.states.findAllByOrderByStateCodeAsc(limit)
                : backward
                        ? reversed(this.states
                                .findByStateCodeLessThanOrderByStateCodeDesc(position, limit))
                        : this.states
                                .findByStateCodeGreaterThanOrderByStateCodeAsc(position, limit);

        return ReferencePaging.page(
                rows, PAGE_SIZE, backward, STATE_BINDING, this.cursorToken,
                LookupMapper::toResponse, UsState::getStateCode);
    }

    /**
     * Answers one seeded state code by its value.
     *
     * @param stateCd the two-character code to read
     * @return the state code
     * @throws NoSuchElementException carrying the verbatim refusal when the code is not seeded
     */
    @GetMapping(path = STATE_PATH + "/{stateCd}")
    public UsStateResponse getUsState(@PathVariable(name = "stateCd") String stateCd) {
        Optional<UsState> found = this.states.findByStateCode(stateCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_STATE_NOT_FOUND);
        }
        return LookupMapper.toResponse(found.get());
    }

    /**
     * Answers one keyset page of seeded state-and-postal-prefix pairs.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction, absent meaning forward
     * @return one page of prefixes with its sealed positions
     */
    @GetMapping(path = ZIP_PREFIX_PATH)
    public PageResponse<UsStateZipPrefixResponse> listUsStateZipPrefixes(
            @RequestParam(name = PARAM_CURSOR, required = false) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false) PageDirection direction) {

        LookupPageRequest request = new LookupPageRequest(cursor, direction, null);
        String position = position(request, ZIP_PREFIX_BINDING);
        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);

        List<UsStateZipPrefix> rows = position == null
                ? this.zipPrefixes.findAllByOrderByStateZipCdAsc(limit)
                : backward
                        ? reversed(this.zipPrefixes
                                .findByStateZipCdLessThanOrderByStateZipCdDesc(position, limit))
                        : this.zipPrefixes
                                .findByStateZipCdGreaterThanOrderByStateZipCdAsc(position, limit);

        return ReferencePaging.page(
                rows, PAGE_SIZE, backward, ZIP_PREFIX_BINDING, this.cursorToken,
                LookupMapper::toResponse, UsStateZipPrefix::getStateZipCd);
    }

    /**
     * Answers one seeded state-and-postal-prefix pair by its value.
     *
     * @param stateZipCd the state code followed by the two leading postal digits
     * @return the prefix pair
     * @throws NoSuchElementException carrying the verbatim refusal when the pair is not seeded
     */
    @GetMapping(path = ZIP_PREFIX_PATH + "/{stateZipCd}")
    public UsStateZipPrefixResponse getUsStateZipPrefix(
            @PathVariable(name = "stateZipCd") String stateZipCd) {
        Optional<UsStateZipPrefix> found = this.zipPrefixes.findByStateZipCd(stateZipCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_ZIP_PREFIX_NOT_FOUND);
        }
        return LookupMapper.toResponse(found.get());
    }

    /**
     * Opens a supplied position under the binding of the browse that minted it.
     *
     * @param request the paging parameters
     * @param binding the cursor binding of this browse
     * @return the opened key, or {@code null} when no position was supplied
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the position was not minted
     *     by this browse
     */
    private String position(LookupPageRequest request, String binding) {
        return request.cursor() == null
                ? null
                : this.cursorToken.open(binding, request.cursor());
    }

    /**
     * Reverses a descending backward walk into ascending order.
     *
     * <p>Assumptions: reversing in memory is safe only because the walk was bounded first. Reversing an
     * unbounded walk would require having read the whole table, which is what the bound exists to
     * prevent.</p>
     *
     * @param <E> the entity type walked
     * @param descending the rows as the backward query returned them
     * @return a new list in ascending key order
     */
    private static <E> List<E> reversed(List<E> descending) {
        List<E> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return ascending;
    }
}
