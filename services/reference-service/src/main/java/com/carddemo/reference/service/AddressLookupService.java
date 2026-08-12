package com.carddemo.reference.service;

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
import com.carddemo.reference.mapper.UsPhoneAreaCodeMapper;
import com.carddemo.reference.mapper.UsStateMapper;
import com.carddemo.reference.mapper.UsStateZipPrefixMapper;
import com.carddemo.reference.repository.UsPhoneAreaCodeRepository;
import com.carddemo.reference.repository.UsStateRepository;
import com.carddemo.reference.repository.UsStateZipPrefixRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three seeded address lookups: their keyset browses, their item reads and their refusals.
 *
 * <h2>Purpose</h2>
 *
 * <p>This service owns every read of {@code reference.us_phone_area_codes}, {@code reference.us_states}
 * and {@code reference.us_state_zip_prefixes} that the published contract exposes. Three of its members
 * answer one keyset page each and three answer one row each, and it is the only place in this module that
 * holds the three repositories, the position sealer for these browses, and the verbatim refusals a
 * missing row is reported with.
 *
 * <p>⚠️ Refactoring Rationale: this class exists because the controller was doing all of it. The adapter
 * injected the three repositories and the sealer directly and carried the whole keyset selection inline --
 * choosing among four query members per browse on the direction and the filter, bounding the walk,
 * reversing a backward page in memory and assembling the envelope. That is data access and business
 * selection in a layer whose charter is transport validation, status mapping and delegation, and the AAP's
 * ports-and-adapters rule is explicit that a controller reaches no store. The consequence was not
 * theoretical: the selection could not be exercised except through a request, so a paging property could
 * only be asserted with an HTTP dispatcher assembled around it, and the three browses' identical logic had
 * no single place it could be corrected in. What moved here is exactly the data access and the selection;
 * what stayed there is the parameter shape, the published paths and the conversion of a query value into
 * this module's own vocabulary.
 *
 * <p>⚠️ Refactoring Rationale: the projection is taken from the three per-entity mapper beans --
 * {@link UsPhoneAreaCodeMapper}, {@link UsStateMapper} and {@link UsStateZipPrefixMapper} -- and NOT from
 * the static {@code LookupMapper} the controller used, which is deleted. There were two implementations of
 * one projection: a static utility with three one-line members, and three documented beans that no code
 * called at all. Two implementations of one conversion is one more than can be kept correct, and the pair
 * that survives is the pair that carries the reasoning: each bean's members record, with cited baseline
 * lines, why a key of declared width is published exactly as stored while a trimmed candidate is not. The
 * static class carried none of that.
 *
 * <p>Assumptions: the mappers are injected rather than reached statically, which is what makes them
 * substitutable in a unit test of this class and is why they were written as beans in the first place. The
 * paging helper {@link ReferencePaging} is reached statically instead, because it holds no state and has
 * no alternative implementation to substitute; the distinction is between a collaborator and a function.
 *
 * <p>Assumptions: every member is read-only and declares it. A lookup table is loaded by a migration and a
 * cutover and is never written through this contract, so a read-only boundary is not a precaution here but
 * a statement of the surface: there is no write member for a caller to reach.
 *
 * <p>Trade-offs: the three browses remain three members rather than one generic member parameterised over
 * entity, key, query and mapper. That generalisation was written out and rejected: the four query members
 * each browse selects among are named differently on each repository and cannot be reached through one
 * interface without declaring one, so the generic form needs a functional parameter per query -- four
 * suppliers, a key extractor and a mapper per call -- and the call site becomes longer and less readable
 * than the member it replaced. The duplication that remains is a four-way conditional repeated three
 * times, which is visible and identical rather than abstracted and slightly different.
 */
@Service
public class AddressLookupService {

    /**
     * Rows in one page of any of the three lookups, and the published window.
     *
     * <p>Assumptions: twenty rather than the ten the user browse uses, because none of these three tables
     * has a reference screen whose row array fixes the figure. The value is published here so the request
     * shape's own bound can cite one number rather than declaring a second.</p>
     */
    public static final int PAGE_SIZE = 20;

    /** The cursor binding of the area-code browse, so a position cannot be replayed on another browse. */
    public static final String AREA_CODE_BINDING = "reference-area-code-list";

    /** The cursor binding of the state browse. */
    public static final String STATE_BINDING = "reference-state-list";

    /** The cursor binding of the state-and-prefix browse. */
    public static final String ZIP_PREFIX_BINDING = "reference-state-zip-prefix-list";

    /** The verbatim refusal when no seeded area code matches. */
    public static final String MESSAGE_AREA_CODE_NOT_FOUND = "Phone area code NOT found...";

    /** The verbatim refusal when no seeded state matches. */
    public static final String MESSAGE_STATE_NOT_FOUND = "State code NOT found...";

    /** The verbatim refusal when no seeded prefix matches. */
    public static final String MESSAGE_ZIP_PREFIX_NOT_FOUND = "State ZIP prefix NOT found...";

    /** Access to the seeded area codes. */
    private final UsPhoneAreaCodeRepository areaCodes;

    /** Access to the seeded state codes. */
    private final UsStateRepository states;

    /** Access to the seeded state-and-prefix pairs. */
    private final UsStateZipPrefixRepository zipPrefixes;

    /** The sealer that mints and opens an opaque paging position. */
    private final CursorToken cursorToken;

    /** The projection of one stored area-code row into its published shape. */
    private final UsPhoneAreaCodeMapper areaCodeMapper;

    /** The projection of one stored state row into its published shape. */
    private final UsStateMapper stateMapper;

    /** The projection of one stored state-and-prefix row into its published shape. */
    private final UsStateZipPrefixMapper zipPrefixMapper;

    /**
     * Builds the service over the three lookup tables, the position sealer and the three projections.
     *
     * @param areaCodes access to the seeded area codes; must not be {@code null}
     * @param states access to the seeded state codes; must not be {@code null}
     * @param zipPrefixes access to the seeded state-and-prefix pairs; must not be {@code null}
     * @param cursorToken the position sealer; must not be {@code null}
     * @param areaCodeMapper the area-code projection; must not be {@code null}
     * @param stateMapper the state projection; must not be {@code null}
     * @param zipPrefixMapper the state-and-prefix projection; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AddressLookupService(
            UsPhoneAreaCodeRepository areaCodes,
            UsStateRepository states,
            UsStateZipPrefixRepository zipPrefixes,
            CursorToken cursorToken,
            UsPhoneAreaCodeMapper areaCodeMapper,
            UsStateMapper stateMapper,
            UsStateZipPrefixMapper zipPrefixMapper) {

        this.areaCodes = Objects.requireNonNull(areaCodes, "areaCodes");
        this.states = Objects.requireNonNull(states, "states");
        this.zipPrefixes = Objects.requireNonNull(zipPrefixes, "zipPrefixes");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken");
        this.areaCodeMapper = Objects.requireNonNull(areaCodeMapper, "areaCodeMapper");
        this.stateMapper = Objects.requireNonNull(stateMapper, "stateMapper");
        this.zipPrefixMapper = Objects.requireNonNull(zipPrefixMapper, "zipPrefixMapper");
    }

    /**
     * Answers one keyset page of seeded area codes, optionally narrowed by classification.
     *
     * <p>Assumptions: the classification is passed to the paging binding as this browse's single narrowing
     * element, so a position minted under a filter cannot be opened without it. A page walked under one
     * filter and continued under another would silently skip or repeat rows, which is the failure the
     * binding exists to make impossible rather than merely unlikely.</p>
     *
     * @param request the paging position, direction and classification filter; must not be {@code null}
     * @param subject the authenticated caller's name, sealed into every position this page mints; must not
     *     be {@code null}
     * @return one page of area codes with its two sealed positions and its forward availability; never
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws com.carddemo.common.error.ClientInputException if a direction arrives without a position
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the position was not minted by
     *     this browse, for this caller, under this filter and for this direction
     */
    @Transactional(readOnly = true)
    public PageResponse<PhoneAreaCodeResponse> listAreaCodes(LookupPageRequest request, String subject) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(subject, "subject");

        String codeClass = request.codeClass();
        String position = position(request, AREA_CODE_BINDING, subject, codeClass);
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
                rows, PAGE_SIZE, backward,
                ReferencePaging.binding(AREA_CODE_BINDING, subject, true, codeClass),
                ReferencePaging.binding(AREA_CODE_BINDING, subject, false, codeClass),
                this.cursorToken, this.areaCodeMapper::toResponse, UsPhoneAreaCode::getAreaCode);
    }

    /**
     * Answers one seeded area code by its value.
     *
     * @param areaCd the three-digit code to read; must not be {@code null}
     * @return the area code and its classification; never {@code null}
     * @throws NullPointerException if {@code areaCd} is {@code null}
     * @throws NoSuchElementException carrying {@link #MESSAGE_AREA_CODE_NOT_FOUND} when the code is not
     *     seeded
     */
    @Transactional(readOnly = true)
    public PhoneAreaCodeResponse readAreaCode(String areaCd) {
        Objects.requireNonNull(areaCd, "areaCd");
        Optional<UsPhoneAreaCode> found = this.areaCodes.findByAreaCode(areaCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_AREA_CODE_NOT_FOUND);
        }
        return this.areaCodeMapper.toResponse(found.get());
    }

    /**
     * Answers one keyset page of seeded state codes.
     *
     * @param request the paging position and direction; must not be {@code null}
     * @param subject the authenticated caller's name, sealed into every position this page mints; must not
     *     be {@code null}
     * @return one page of state codes with its two sealed positions; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws com.carddemo.common.error.ClientInputException if a direction arrives without a position
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the position was not minted by
     *     this browse, for this caller and for this direction
     */
    @Transactional(readOnly = true)
    public PageResponse<UsStateResponse> listStates(LookupPageRequest request, String subject) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(subject, "subject");

        String position = position(request, STATE_BINDING, subject);
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
                rows, PAGE_SIZE, backward,
                ReferencePaging.binding(STATE_BINDING, subject, true),
                ReferencePaging.binding(STATE_BINDING, subject, false),
                this.cursorToken, this.stateMapper::toResponse, UsState::getStateCode);
    }

    /**
     * Answers one seeded state code by its value.
     *
     * @param stateCd the two-character code to read; must not be {@code null}
     * @return the state code; never {@code null}
     * @throws NullPointerException if {@code stateCd} is {@code null}
     * @throws NoSuchElementException carrying {@link #MESSAGE_STATE_NOT_FOUND} when the code is not seeded
     */
    @Transactional(readOnly = true)
    public UsStateResponse readState(String stateCd) {
        Objects.requireNonNull(stateCd, "stateCd");
        Optional<UsState> found = this.states.findByStateCode(stateCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_STATE_NOT_FOUND);
        }
        return this.stateMapper.toResponse(found.get());
    }

    /**
     * Answers one keyset page of seeded state-and-postal-prefix pairs.
     *
     * @param request the paging position and direction; must not be {@code null}
     * @param subject the authenticated caller's name, sealed into every position this page mints; must not
     *     be {@code null}
     * @return one page of prefix pairs with its two sealed positions; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws com.carddemo.common.error.ClientInputException if a direction arrives without a position
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the position was not minted by
     *     this browse, for this caller and for this direction
     */
    @Transactional(readOnly = true)
    public PageResponse<UsStateZipPrefixResponse> listZipPrefixes(LookupPageRequest request,
            String subject) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(subject, "subject");

        String position = position(request, ZIP_PREFIX_BINDING, subject);
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
                rows, PAGE_SIZE, backward,
                ReferencePaging.binding(ZIP_PREFIX_BINDING, subject, true),
                ReferencePaging.binding(ZIP_PREFIX_BINDING, subject, false),
                this.cursorToken, this.zipPrefixMapper::toResponse, UsStateZipPrefix::getStateZipCd);
    }

    /**
     * Answers one seeded state-and-postal-prefix pair by its value.
     *
     * @param stateZipCd the state code followed by the two leading postal digits; must not be {@code null}
     * @return the prefix pair; never {@code null}
     * @throws NullPointerException if {@code stateZipCd} is {@code null}
     * @throws NoSuchElementException carrying {@link #MESSAGE_ZIP_PREFIX_NOT_FOUND} when the pair is not
     *     seeded
     */
    @Transactional(readOnly = true)
    public UsStateZipPrefixResponse readZipPrefix(String stateZipCd) {
        Objects.requireNonNull(stateZipCd, "stateZipCd");
        Optional<UsStateZipPrefix> found = this.zipPrefixes.findByStateZipCd(stateZipCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_ZIP_PREFIX_NOT_FOUND);
        }
        return this.zipPrefixMapper.toResponse(found.get());
    }

    /**
     * Refuses a malformed paging pair, then opens a supplied position under the binding that minted it.
     *
     * <p>⚠️ Refactoring Rationale: the narrowing tuple is now supplied BY THE CALLER, one element for the
     * area-code browse and none for the other two. It used to be fixed inside this member, which always
     * passed {@code request.codeClass()} -- one element, {@code null} for the two browses that have no
     * filter -- and the paragraph that stood here argued that passing it uniformly was safe because an
     * absent element composes as its own scope value. That argument is sound about this member in
     * isolation and wrong about the pair of members that have to agree. The state and prefix browses seal
     * their two boundary positions with NO narrowing element, so a position minted under
     * {@code scope(forward)} was being opened under {@code scope(forward, unfiltered)} -- two different
     * bindings. Every position either of those two browses minted was therefore unopenable, and paging
     * past their first page was impossible: a caller that sent a position straight back was told it was
     * not a position this service had minted. Requiring the tuple as an argument is what makes the two
     * sides of one browse state the same thing, because a browse now writes its tuple where it seals and
     * where it opens and a reader can compare them. Assumptions: this was found by an added case that
     * mints a real position with the deployed seal and sends it back, rather than by inspection -- a
     * mocked seal would have opened anything.</p>
     *
     * @param request the paging parameters
     * @param binding the cursor binding of this browse
     * @param subject the authenticated caller's identity, sealed into the binding
     * @param narrowing the filter values this browse walks under, in the same order and of the same arity
     *     as the tuple its {@code page} call seals its boundaries with; empty for a browse with no filter
     * @return the opened key, or {@code null} when no position was supplied
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the position was not minted by
     *     this browse, for this caller, under this filter and for this direction
     * @throws com.carddemo.common.error.ClientInputException if a paging direction arrives without the
     *     position it would move from
     */
    private String position(LookupPageRequest request, String binding, String subject,
            String... narrowing) {

        ReferencePaging.requireCursorForDirection(request.cursor(), request.direction());
        return ReferencePaging.openPosition(this.cursorToken, binding, subject, request.cursor(),
                request.direction(), narrowing);
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
