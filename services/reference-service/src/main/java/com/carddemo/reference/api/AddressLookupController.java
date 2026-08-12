package com.carddemo.reference.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.dto.LookupPageRequest;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import com.carddemo.reference.dto.UsStateResponse;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;
import com.carddemo.reference.service.AddressLookupService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six operations over the three seeded address allow-lists.
 *
 * <p>Purpose: binds the browse and the read of the area-code, state and state-and-postal-prefix tables.
 * It validates each request's shape, converts the paging direction from the spelling the contract
 * publishes into this module's own vocabulary, and delegates every read to
 * {@link AddressLookupService}. It reaches no repository and holds no selection logic.</p>
 *
 * <p>⚠️ Refactoring Rationale: this class used to inject the three repositories and the position sealer
 * and to carry the whole keyset selection inline -- choosing among four query members per browse on the
 * direction and the filter, bounding each walk, reversing a backward page and assembling the envelope.
 * The paragraph that stood here argued that a service layer "would forward six calls and add a file", and
 * that argument was wrong on both counts: the service it was declining does not forward, it OWNS the
 * selection, and the file it was avoiding is where three near-identical four-way conditionals can be
 * corrected once. The AAP's ports-and-adapters rule is explicit that a controller reaches no store, and
 * the review found this the one place in the module that did. What remains here is exactly what this
 * layer owns: the published paths, the parameter shapes and their constraints, the direction conversion,
 * and delegation.</p>
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

    /**
     * The exact width of an area code, from the {@code PhoneAreaCodeValue} schema.
     *
     * <p>Refactoring Rationale: the three widths and three expressions declared here and below are
     * transcribed from the schemas the item routes' published path parameters reference --
     * {@code PhoneAreaCodeValue}, {@code StateCodeValue} and {@code StateZipPrefixValue}. They are declared
     * because each of those routes previously bound its segment as unconstrained text and read for it, so a
     * value outside the published domain matched no seeded row and was answered 404 with the domain's
     * "NOT found" sentence. That answer is wrong in a way a caller acts on: it says the code is not seeded
     * when what is actually true is that the value cannot be a code at all, and the two call for different
     * corrections. They are declared privately rather than published because this class is their only
     * consumer -- unlike the two transaction codes, which are shared with the disclosure-group read and are
     * therefore published on the entities that own them.</p>
     */
    private static final int AREA_CODE_WIDTH = 3;

    /** The closed domain of an area code: exactly three digits, leading zeros retained. */
    private static final String AREA_CODE_PATTERN = "^[0-9]{3}$";

    /** The exact width of a state code, from the {@code StateCodeValue} schema. */
    private static final int STATE_CODE_WIDTH = 2;

    /** The closed domain of a state code: two upper-case letters. */
    private static final String STATE_CODE_PATTERN = "^[A-Z]{2}$";

    /** The exact width of a state-and-prefix pair, from the {@code StateZipPrefixValue} schema. */
    private static final int ZIP_PREFIX_WIDTH = 4;

    /** The closed domain of a state-and-prefix pair: two upper-case letters then two digits. */
    private static final String ZIP_PREFIX_PATTERN = "^[A-Z]{2}[0-9]{2}$";

    /** The query parameter carrying the paging position. */
    private static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction. */
    private static final String PARAM_DIRECTION = "direction";

    /** The query parameter carrying the area-code classification filter. */
    private static final String PARAM_CODE_CLASS = "codeClass";

    /** The one collaborator: the service owning the three lookups' reads, paging and refusals. */
    private final AddressLookupService lookups;

    /**
     * Builds the controller over the address-lookup service.
     *
     * <p>⚠️ Refactoring Rationale: one collaborator where there were four. The three repositories and the
     * position sealer are now held by that service, which is the layer entitled to hold them; a controller
     * whose constructor named a repository was the visible form of the layering the review reported.</p>
     *
     * @param lookups the service owning the three lookups; must not be {@code null}
     * @throws NullPointerException if {@code lookups} is {@code null}
     */
    public AddressLookupController(AddressLookupService lookups) {
        this.lookups = Objects.requireNonNull(lookups, "lookups");
    }

    /**
     * Answers one keyset page of seeded area codes, optionally narrowed to one classification.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction as the caller spelled it, one of the two lower-case values
     *     the contract publishes, absent meaning forward
     * @param codeClass the classification filter, absent meaning unfiltered
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position is not transferable between callers
     * @return one page of area codes with its sealed positions
     */
    @GetMapping(path = AREA_CODE_PATH)
    public PageResponse<PhoneAreaCodeResponse> listUsPhoneAreaCodes(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            // WHY : ⚠️ Refactoring Rationale: bound as a STRING and converted below, because a
            //       parameter declared as the enumeration is bound by Enum.valueOf against the
            //       CONSTANT NAME -- so the two lower-case values this contract publishes, and the
            //       only two the browser client sends, were refused while NEXT and PREVIOUS were
            //       accepted. The whole argument, and the converter alternative that was rejected,
            //       is recorded on PageDirection.fromRequestParameter.
            @RequestParam(name = PARAM_DIRECTION, required = false) String direction,
            @RequestParam(name = PARAM_CODE_CLASS, required = false)
            @Pattern(regexp = LookupPageRequest.CODE_CLASS_PATTERN) String codeClass,
            Principal principal) {

        return this.lookups.listAreaCodes(
                new LookupPageRequest(cursor, PageDirection.fromRequestParameter(direction), codeClass),
                principal.getName());
    }

    /**
     * Answers one seeded area code by its value.
     *
     * @param areaCd the three-digit code to read
     * @return the area code and its classification
     * @throws NoSuchElementException carrying the verbatim refusal when the code is not seeded
     */
    @GetMapping(path = AREA_CODE_PATH + "/{areaCd}")
    public PhoneAreaCodeResponse getUsPhoneAreaCode(
            @PathVariable(name = "areaCd")
            @Size(min = AREA_CODE_WIDTH, max = AREA_CODE_WIDTH)
            @Pattern(regexp = AREA_CODE_PATTERN) String areaCd) {
        return this.lookups.readAreaCode(areaCd);
    }

    /**
     * Answers one keyset page of seeded state codes.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction as the caller spelled it, one of the two lower-case values
     *     the contract publishes, absent meaning forward
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position is not transferable between callers
     * @return one page of state codes with its sealed positions
     */
    @GetMapping(path = STATE_PATH)
    public PageResponse<UsStateResponse> listUsStates(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            // WHY : ⚠️ Refactoring Rationale: bound as a STRING and converted below, because a
            //       parameter declared as the enumeration is bound by Enum.valueOf against the
            //       CONSTANT NAME -- so the two lower-case values this contract publishes, and the
            //       only two the browser client sends, were refused while NEXT and PREVIOUS were
            //       accepted. The whole argument, and the converter alternative that was rejected,
            //       is recorded on PageDirection.fromRequestParameter.
            @RequestParam(name = PARAM_DIRECTION, required = false) String direction,
            Principal principal) {

        return this.lookups.listStates(
                new LookupPageRequest(cursor, PageDirection.fromRequestParameter(direction), null),
                principal.getName());
    }

    /**
     * Answers one seeded state code by its value.
     *
     * @param stateCd the two-character code to read
     * @return the state code
     * @throws NoSuchElementException carrying the verbatim refusal when the code is not seeded
     */
    @GetMapping(path = STATE_PATH + "/{stateCd}")
    public UsStateResponse getUsState(
            @PathVariable(name = "stateCd")
            @Size(min = STATE_CODE_WIDTH, max = STATE_CODE_WIDTH)
            @Pattern(regexp = STATE_CODE_PATTERN) String stateCd) {
        return this.lookups.readState(stateCd);
    }

    /**
     * Answers one keyset page of seeded state-and-postal-prefix pairs.
     *
     * @param cursor the paging position a previous reply minted, absent on a first request
     * @param direction the paging direction as the caller spelled it, one of the two lower-case values
     *     the contract publishes, absent meaning forward
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position is not transferable between callers
     * @return one page of prefixes with its sealed positions
     */
    @GetMapping(path = ZIP_PREFIX_PATH)
    public PageResponse<UsStateZipPrefixResponse> listUsStateZipPrefixes(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            // WHY : ⚠️ Refactoring Rationale: bound as a STRING and converted below, because a
            //       parameter declared as the enumeration is bound by Enum.valueOf against the
            //       CONSTANT NAME -- so the two lower-case values this contract publishes, and the
            //       only two the browser client sends, were refused while NEXT and PREVIOUS were
            //       accepted. The whole argument, and the converter alternative that was rejected,
            //       is recorded on PageDirection.fromRequestParameter.
            @RequestParam(name = PARAM_DIRECTION, required = false) String direction,
            Principal principal) {

        return this.lookups.listZipPrefixes(
                new LookupPageRequest(cursor, PageDirection.fromRequestParameter(direction), null),
                principal.getName());
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
            @PathVariable(name = "stateZipCd")
            @Size(min = ZIP_PREFIX_WIDTH, max = ZIP_PREFIX_WIDTH)
            @Pattern(regexp = ZIP_PREFIX_PATTERN) String stateZipCd) {
        return this.lookups.readZipPrefix(stateZipCd);
    }
}
