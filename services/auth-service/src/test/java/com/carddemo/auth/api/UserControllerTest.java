package com.carddemo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.auth.config.SecurityConfig;
import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.CreatedUserResponse;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.service.UserService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.servlet.Filter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web-layer slice assertions for the five user-administration routes, with the deployed chain in front.
 *
 * <h2>What this class asserts</h2>
 *
 * <p>Purpose: this class holds, for {@link UserController}, the six subjects the package descriptor
 * beside it fixes for this package -- HTTP status, response-body shape, verbatim message text,
 * per-field error keys, authority enforcement and statelessness. The transcribed validation chains
 * themselves, the dirty-check semantics of the update path and the keyset orchestration are asserted
 * in the sibling {@code com.carddemo.auth.service} package against a mocked repository, so nothing
 * here restates them.</p>
 *
 * <p>The behavioural specification is four reference programs, each read on disk for this class:
 * {@code app/cbl/COUSR00C.cbl} at 695 lines, {@code app/cbl/COUSR01C.cbl} at 299,
 * {@code app/cbl/COUSR02C.cbl} at 414 and {@code app/cbl/COUSR03C.cbl} at 359. They are
 * reference-only and are never modified. No executable oracle exists for any of them:
 * {@code tests/README.md} records on L83 to L85 that the online programs cannot run end to end
 * without a CICS runtime, which the build host does not have, and scopes its end-to-end tier on L29
 * to the daily batch chain alone. Every expected value below was therefore read from the reference
 * source and its symbolic maps. These assertions encode the documented rules; they do not redefine
 * them, which is the verb that same file uses of its own tests on L35 and L36.</p>
 *
 * <h2>How the slice is assembled, and why not with the slice annotation</h2>
 *
 * <p>Alternatives Considered: the servlet slice annotation is the obvious wiring and is unavailable
 * on this module's test class path. Spring Boot 4 moved it out of
 * {@code spring-boot-test-autoconfigure} into the separate artifact
 * {@code org.springframework.boot:spring-boot-webmvc-test}, which
 * {@code services/auth-service/pom.xml} does not declare and which
 * {@code spring-boot-starter-test} does not pull in. Adding the artifact would mean editing a
 * sibling-owned build file, so the gap is reported here rather than patched, and the slice is
 * assembled from types that are on the path -- exactly as the sibling {@link AuthControllerTest}
 * assembles its own.</p>
 *
 * <p>Alternatives Considered: a full application test was rejected. It would start the persistence
 * layer and run the schema migration in order to reach an adapter whose assertions are statuses,
 * body members and message sentences, which would make an adapter regression and a host unable to
 * start a database container indistinguishable in the report. It would also create the decoder bean
 * {@code config/SecurityConfig.java} declares, whose factory resolves the provider document while
 * the context refreshes and cannot complete against this module's deliberately unreachable test
 * issuer.</p>
 *
 * <p>Alternatives Considered: {@code spring-security-test} is likewise absent from this module's test
 * scope, so its request post-processors cannot mint an authentication. A token is therefore presented
 * the way a caller presents one, in an authorization header, and a substituted {@link JwtDecoder}
 * answers for it. The substitution is not merely a stand-in for the missing artifact; it exercises
 * more of the deployed path than the artifact would, because the resource-server filter, the deployed
 * {@link SecurityConfig#jwtAuthenticationConverter(String, String)} and the deployed
 * {@link JwtRoleConverter} all run, so the authority derivation under assertion is the deployed one
 * and not a value a post-processor injected past it.</p>
 *
 * <p>Assumptions: the chain is obtained by CALLING the deployed configuration's own two methods
 * rather than by registering that class, and the distinction is why {@link SliceWiring} exists.
 * Registering the configuration would also register its decoder factory, which issues a
 * provider-document request during refresh. Calling the two methods that matter leaves every rule,
 * every refusal renderer and the group-to-authority translation exactly as deployed while the one
 * bean that needs a route to a provider is substituted.</p>
 *
 * <p>Assumptions: the authority is the group name VERBATIM and the deployed rules test it with the
 * authority-family predicate. That is settled by the finished
 * {@code services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java},
 * whose {@code ADMIN_AUTHORITY} is the group name with no framework role prefix, and by
 * {@code config/SecurityConfig.java} L759 to L761, which applies {@code hasAnyAuthority} over its
 * rule table. The pairing is load-bearing: were a prefix ever added to the emitted authority, the
 * role-family predicate would look for an authority the converter never produces, match nothing, and
 * refuse every administrative request with 403 while the context started cleanly. The cases below
 * detect that from both sides, one admitting the administrator and one refusing the ordinary
 * caller.</p>
 *
 * <p>Assumptions: the guard is decided by the path rules inside the filter chain and by nothing on
 * the adapter's own methods, which is why installing that chain is what makes every authorization
 * case below mean anything. {@code config/SecurityConfig.java} L172 to L174 enable web security only;
 * the file declares no method-security enablement anywhere, and the adapter carries no authorization
 * annotation of any kind. Were the guard ever moved onto an annotation while that enablement stayed
 * absent, the annotation would be inert and the rules would be the only thing still refusing; were a
 * rule removed in favour of one, the route would open silently. The cases below assert the rules,
 * which is where the decision actually is -- the deployed table applies its entries at L759 to L761
 * and refuses everything it did not grant at L769.</p>
 *
 * <p>Assumptions: the correlation filter is NOT installed and no case asserts on it. The shared
 * kernel's auto-configuration owns its registration and {@code config/SecurityConfig.java} declares
 * neither a second registration nor an in-chain instance, so there is no per-service mechanism for
 * this class to exercise and asserting the header here would assert a wiring this slice invented.</p>
 *
 * <h2>The administrative guard, and the evidence it rests on</h2>
 *
 * <p>Assumptions: every one of the five routes demands the administrator authority, with no
 * read-only exception for the list and the detail, and that is a reading of the reference rather
 * than a preference. Four independent strands agree. The sign-on program moves the stored user type
 * into the session structure at {@code app/cbl/COSGN00C.cbl} L227, tests the administrator condition
 * name at L230, and transfers to the administrative menu at L232 or to the ordinary menu at L237.
 * The administrative menu dispatches from a data-driven table and holds no program literal of its
 * own -- {@code app/cbl/COADM01C.cbl} L146 transfers to a table entry, and a census over that file
 * for the four user programs returns nothing -- while the table itself,
 * {@code app/cpy/COADM02Y.cpy}, names them at L29, L34, L39 and L44, each under an option label
 * whose trailing parenthesised literal reads {@code (Security)}, at L28, L33, L38 and L43. The
 * ordinary menu names none of them: {@code app/cpy/COMEN02Y.cpy} holds eleven program entries, at
 * L28, L34, L40, L46, L52, L58, L64, L71, L77, L83 and L89, and a census over that file for the
 * four user programs returns nothing at all. And each of the four returns to the administrative
 * menu by name whichever key ends it, at
 * {@code app/cbl/COUSR00C.cbl} L126, {@code app/cbl/COUSR01C.cbl} L94,
 * {@code app/cbl/COUSR02C.cbl} L114 and L125, and {@code app/cbl/COUSR03C.cbl} L113 and L124.</p>
 *
 * <p>Assumptions: the resource definitions are menu-structural evidence and NOT independent
 * administrator-only evidence, and saying so is what keeps the guard honestly grounded.
 * {@code app/csd/CARDDEMO.CSD} does carry authorization attributes and they are explicitly
 * DISABLED: the paired resource-security and command-security attributes are set to no at L314,
 * L324, L334, L344, L354, L364, L375, L385, L396, L406, L416, L426, L436, L446, L456, L466, L476 and
 * L486, eighteen occurrences with not one affirmative, and the transaction work area is zero at the
 * same eighteen. Transaction security was external to the definitions and that external manager is
 * not ported; its role is filled by least-privilege task roles plus identity-pool groups, documented
 * as a mapping in {@code docs/architecture/security-and-identity.md}. What the definitions do
 * establish is the routing: the four programs are reached by their own transaction family, at L449
 * with L450, L459 with L460, L469 with L470 and L479 with L480, rather than through any screen an
 * ordinary caller reaches.</p>
 *
 * <h2>Two contracts derived from the reference rather than invented</h2>
 *
 * <p>Assumptions: the browse state of the reference is ALREADY a keyset cursor, which is why the
 * page envelope needs no ordinal position. {@code app/cbl/COUSR00C.cbl} declares the overlay inline
 * in its own storage at L67 -- a census over {@code app/cpy} for its prefix returns nothing, so it
 * is in no copybook -- with a leading key at L68, a trailing key at L69, an ordinal display counter
 * at L70 and a forward-availability flag at L71 whose two condition names are on L72 and L73. That
 * flag is set by reading ONE ROW MORE than fits: L300 fills ten rows and the eleventh read at L311
 * decides between the affirmative at L313 and the negative at L315 before the browse ends at L325.
 * The ordinal counter never participates in a key predicate -- positioning is driven entirely by the
 * identifier at L240, L242, L263 and L265, and the counter's two control-flow uses are the guard at
 * L248 and the compound test at L365 with L366.</p>
 *
 * <p>Trade-offs: the reference carries a forward-availability flag with no backward counterpart
 * anywhere at L71 to L73, so a target envelope reporting only the forward answer would have been
 * faithful. The deployed envelope reports both, and the widening is accepted for a concrete reason:
 * a client that has stepped forward cannot otherwise tell an exhausted backward direction from an
 * unexplored one, which the reference client could tell from its own ordinal counter and this one
 * has none of. What is given up is a one-to-one component correspondence with L71.</p>
 *
 * <p>Alternatives Considered: paging by ordinal position was evaluated and rejected. Under concurrent
 * inserts an offset request skips rows and repeats rows, because the ordinal of a row moves when a
 * row before it is inserted, whereas reading by key from the same identifier the reference browsed
 * cannot skip or repeat. The reference's own eleventh-row probe at L311 to L316 is the same
 * technique, so keyset paging preserves observable behaviour that offset paging would change.</p>
 *
 * <p>Assumptions: the two request records declare their components in the order their own reference
 * program examines its fields, and the two orders DIFFER, so they are asserted separately below and
 * never through one shared case. The create order is the screen order of
 * {@code app/cpy-bms/COUSR01.CPY}, whose input fields sit at L60, L66, L72, L78 and L84, and which
 * {@code app/cbl/COUSR01C.cbl} tests in that sequence at L118, L124, L130, L136 and L142. The update
 * order is the screen order of {@code app/cpy-bms/COUSR02.CPY}, whose input fields sit at the same
 * five line numbers but in a different sequence, and which {@code app/cbl/COUSR02C.cbl} tests at
 * L180, L186, L192, L198 and L204 -- identifier first. One parameterised case assuming a single
 * unified order would pass against an implementation that had them the wrong way round, which is the
 * specific damage being avoided.</p>
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Assumptions: the attention-identifier complaint is excluded because it has no counterpart here.
 * {@code app/cpy/CSMSG01Y.cpy} declares that constant on L20 with its literal on L21, and
 * {@code app/cbl/COUSR02C.cbl} L129 and {@code app/cbl/COUSR03C.cbl} L128 move it on the arm a key
 * other than the mapped ones reaches. A REST request carries no attention identifier, so the concept
 * belongs to the browser client's own message catalogue.</p>
 *
 * <p>Assumptions: the five paging sentences of {@code app/cbl/COUSR00C.cbl} are excluded because
 * this bounded context carries none of them, and the absence is reported rather than filled. The
 * reference distinguishes two guard sentences, on L251 and L273, from three arrival sentences, on
 * L603, L637 and L671, and the five differ in wording and in measured length -- 41, 44, 33, 42 and
 * 39 characters. A repository-wide census places every one of them in the transaction and
 * authorization contexts and in neither the roster service nor its adapter, so there is no sentence
 * on this route to assert and writing one here would invent behaviour. What IS asserted, because it
 * is this adapter's own contract, is the status the reference's own absent-cursor arm implies: L600
 * keys that arm on the not-found response and NOT on end-of-file, and L600 to L606 merely position
 * at the start and repaint, so a cursor naming no row is answered 200 and never 404.</p>
 *
 * <p>Assumptions: no truncation case exists to write, because no sentence any of these operations
 * emits can reach the declared width. The message field is one character short of eighty positions
 * in all four symbolic maps -- at {@code app/cpy-bms/COUSR00.CPY} L372, {@code COUSR01.CPY} L90,
 * {@code COUSR02.CPY} L90 and {@code COUSR03.CPY} L84, with the output faces at L728, L164, L164 and
 * L152 -- while the longest sentence any of them carries measures 44 characters. A truncation case
 * would oblige an implementation to hold a branch that cannot execute.</p>
 *
 * <p>Assumptions: the shape of the loaded row derives from the reference's declared fields and not
 * from its unreferenced storage. {@code app/cbl/COUSR00C.cbl} L56 and L57 declare a ten-slot table
 * holding a concatenated name at L62 and a widened type at L64, and a census over the whole file
 * returns those four declaration lines and no procedural reference at all, so the block is dead.
 * The two names are therefore carried separately at their own declared widths and the type at one
 * character, which {@code app/cpy-bms/COUSR00.CPY} L96 independently states.</p>
 *
 * <h2>What the documentation gate does and does not check</h2>
 *
 * <p>Assumptions: {@code config/checkstyle/checkstyle.xml} audits the presence and completeness of
 * every block below, down to private methods, and its summary module inspects Javadoc summaries only.
 * It never reads an inline comment, so the half of Rule 1 that requires a stated reason for a
 * non-obvious decision is not mechanically checkable: this file can pass the validate phase and still
 * fail review under that rule's validation gate. The written convention is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} and the rule outranks both it and the ruleset.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class UserControllerTest {

    /**
     * The sentence the reference answers a taken identifier with, at {@code COUSR01C.cbl} L263.
     *
     * <p>Assumptions: transformation rule T8 carries every user-visible string across character for
     * character and specification section 0.9.1 makes these sentences an externally observable
     * interface, so the missing plural on the verb is part of the value and is not corrected. The
     * reference reaches this sentence from TWO arms, a duplicate key at L260 and a duplicate record
     * at L261, which fall through to one shared body from L262 and produce one sentence between
     * them. The literal is written here rather than read from the service constant so that a change
     * to either is a visible difference between two files rather than a silent agreement.</p>
     */
    private static final String MESSAGE_IDENTIFIER_TAKEN = "User ID already exist...";

    /**
     * The reference sentence for an identifier that names no row, at {@code COUSR02C.cbl} L342.
     *
     * <p>Assumptions: the same sentence is written on all four of the reference's absent-row arms --
     * the read and the rewrite of the update program at L342 and L379, and the read and the delete of
     * the delete program at {@code COUSR03C.cbl} L289 and L325 -- so one value covers them and no
     * variant is invented for any one arm.</p>
     */
    private static final String MESSAGE_IDENTIFIER_NOT_FOUND = "User ID NOT found...";

    /**
     * The reference sentence for a submission that changed nothing, at {@code COUSR02C.cbl} L239.
     *
     * <p>Assumptions: the trailing space before the ellipsis is part of the value. The two guard
     * sentences of the list program end their ellipsis with no preceding space while this one does,
     * and normalising either way would alter a string the specification treats as observable.</p>
     */
    private static final String MESSAGE_NOTHING_MODIFIED = "Please modify to update ...";

    /**
     * The reference sentence for a store write that failed, at {@code COUSR02C.cbl} L386.
     *
     * <p>Refactoring Rationale: this same sentence is what the reference writes on the DELETE failure
     * arm as well, at {@code COUSR03C.cbl} L332 inside the paragraph labelled at L305, reached from
     * the catch-all arm at L329. The wording says update on a path that deletes. The reference is
     * reference-only and is not altered; the target encodes the sentence as written, and the mismatch
     * is recorded in {@code docs/architecture/cobol-to-service-traceability.md} rather than
     * corrected. Provenance corroborates the copy: the update and delete programs copy the same eight
     * books at byte-identical line numbers 49, 60, 62, 63, 64, 65, 67 and 68.</p>
     *
     * <p>Trade-offs: fidelity was chosen over a clearer sentence on the delete path. What that costs
     * is that an operator reading the delete failure sees a word describing the wrong verb; what it
     * buys is that a client matching on the sentence sees exactly what the reference produced, which
     * is the property rule T8 exists to hold.</p>
     */
    private static final String MESSAGE_UNABLE_TO_UPDATE = "Unable to Update User...";

    /**
     * The reference sentence for a read that could not be completed, at {@code COUSR00C.cbl} L610.
     *
     * <p>Assumptions: the reference writes the identical text on all three of the list program's
     * catch-all arms, at L610, L644 and L678, each preceded by a live console write at L608, L642 and
     * L676. One shared value is therefore correct and three variants would be an invention.</p>
     */
    private static final String MESSAGE_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /**
     * The reference sentence for an absent first name, at {@code COUSR01C.cbl} L120.
     *
     * <p>Assumptions: the update program writes the same sentence on its own arm at L188, so the
     * value is shared between the two operations while the ORDER the two examine their fields in is
     * not, which is why the two orders are asserted separately below.</p>
     */
    private static final String MESSAGE_FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /**
     * The reference sentence for an absent identifier, at {@code COUSR01C.cbl} L132.
     *
     * <p>Assumptions: the delete program writes the same sentence at L147 and L179 and the update
     * program at L148 and L182, in every case as the first arm of its chain, so this value is the
     * one the create operation reports for its own third component and the one the others report
     * first.</p>
     */
    private static final String MESSAGE_IDENTIFIER_REQUIRED = "User ID can NOT be empty...";

    /**
     * A store diagnostic that must never reach a response body.
     *
     * <p>Assumptions: the shape deliberately resembles the response and reason codes the reference
     * wrote to the operator console at {@code COUSR00C.cbl} L608, L642 and L676 and at
     * {@code COUSR02C.cbl} L347 and L384, so that a body asserted free of this string is asserted
     * free of exactly that class of detail. The console was read by an operator inside the system
     * boundary; a response body is read by whoever called.</p>
     *
     * <p>Alternatives Considered: at each of the three failure cases below this value is handed to the
     * substituted service as the CAUSE of the refusal rather than appended to the refusal's sentence,
     * and the choice is what gives the three non-disclosure assertions their force. A fixture that
     * concatenated the two would leave the body legitimately carrying this string, so an assertion
     * that the body excludes it would then hold just as well for a renderer that excludes nothing --
     * it would pass while distinguishing nothing.</p>
     */
    private static final String STORE_DIAGNOSTIC = "RESP=13 REAS=80 store-internal detail";

    /**
     * An identifier of the width the reference declares, standing for a row that exists.
     *
     * <p>Assumptions: eight positions is the reference field width, declared at
     * {@code app/cpy/CSUSR01Y.cpy} L18 and stated independently by the screen field at
     * {@code app/cpy-bms/COUSR03.CPY} L60, so a value of this width is one the reference could have
     * held.</p>
     */
    private static final String STORED_IDENTIFIER = "USER0001";

    /**
     * A second identifier of the same width, standing for a row a caller is creating.
     *
     * <p>Assumptions: it differs from {@link #STORED_IDENTIFIER} so that the location header asserted
     * on the create cannot pass by echoing a value some other case put there.</p>
     */
    private static final String CREATED_IDENTIFIER = "USER0042";

    /**
     * An identifier of the declared width that names no row.
     *
     * <p>Assumptions: it is eight positions wide on purpose, so that the absent-row case exercises
     * the store answer rather than the width bound, which the over-long case exercises separately.</p>
     */
    private static final String ABSENT_IDENTIFIER = "NOSUCH01";

    /**
     * An identifier one position wider than the reference field.
     *
     * <p>Assumptions: nine positions cannot be a key the reference held, because the field is eight
     * at {@code app/cpy/CSUSR01Y.cpy} L18. Without a bound on the path segment such a value would
     * reach the store as a key that cannot exist and be answered as a missing row, reporting a row
     * as absent when the request was malformed.</p>
     */
    private static final String OVER_LONG_IDENTIFIER = "USER00001";

    /**
     * A value present on the wire yet blank, which is the condition the reference tests.
     *
     * <p>Assumptions: every arm of the reference chains tests its field against spaces or low values
     * -- {@code COUSR01C.cbl} L118, L124, L130, L136 and L142 -- so a run of spaces is a rejected
     * submission rather than an absent member.</p>
     */
    private static final String BLANK_SUBMISSION = "   ";

    /**
     * The one-character administrator code the reference condition name tests for.
     *
     * <p>Assumptions: the quoted value is read from {@code app/cpy/COCOM01Y.cpy} L27, whose sibling
     * on L28 is the ordinary code. Those two condition names are the whole domain, and the copybook
     * holding the stored field, {@code app/cpy/CSUSR01Y.cpy}, declares the same width at L22 while
     * carrying no condition name at all, so it is a second witness to the domain rather than its
     * authority.</p>
     */
    private static final String ADMIN_TYPE_CODE = "A";

    /**
     * The one-character ordinary-caller code, read from {@code app/cpy/COCOM01Y.cpy} L28.
     */
    private static final String ORDINARY_TYPE_CODE = "U";

    /**
     * A submitted first name within the reference field width.
     *
     * <p>Assumptions: twenty positions is the declared width of both name fields, at
     * {@code app/cpy/CSUSR01Y.cpy} L19 and L20 and again at {@code app/cpy-bms/COUSR01.CPY} L60 and
     * L66, and this value is well inside it so that no case below fails for a width reason it did not
     * intend to test.</p>
     */
    private static final String SUBMITTED_FIRST_NAME = "Ada";

    /** A submitted second name, within the same twenty-position width. */
    private static final String SUBMITTED_LAST_NAME = "Lovelace";

    /** A replacement first name, so that an update case can observe a changed value. */
    private static final String REPLACEMENT_FIRST_NAME = "Grace";

    /** A replacement second name, so that an update case can observe a changed value. */
    private static final String REPLACEMENT_LAST_NAME = "Hopper";

    /**
     * The subject claim the substituted decoder answers with.
     *
     * <p>Assumptions: the resource server derives the caller name from the subject claim, and the
     * list operation passes that name to the service as the value its cursors are sealed against, so
     * this value is what the pass-through assertion below expects to arrive.</p>
     */
    private static final String TOKEN_SUBJECT = "11111111-2222-3333-4444-555555555555";

    /**
     * The pool subject the substituted service reports on a stored row.
     *
     * <p>Assumptions: it differs from {@link #TOKEN_SUBJECT} because one is the caller's identity and
     * the other is the identity of the row being administered, and a case that confused them would
     * pass while asserting the wrong thing.</p>
     */
    private static final UUID ROW_SUBJECT = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /**
     * The managed-secret entry name the substituted service reports on a created row.
     *
     * <p>Assumptions: a distinctive value, unlike any other literal this class declares, so the two
     * assertions it carries can both be exact: that the created body publishes it, and that no OTHER
     * rendered body or refusal echoes it. A value that resembled an identifier could satisfy the first
     * while making the second unable to distinguish a leak from a coincidence.</p>
     *
     * <p>Refactoring Rationale: this literal was a credential and is now a LOCATOR. The created body
     * carries the name of the entry holding the account's first credential rather than the credential
     * itself, because a credential in a response body is retained by every proxy log on the path; the
     * shape here mirrors the real derivation -- a configured prefix, the fixed infix and a truncated
     * digest of the identifier -- so a case asserting the published shape is not passing on a value the
     * service could never produce.</p>
     */
    private static final String CREATED_CREDENTIAL_SECRET_NAME =
            "carddemo/dev/auth/runtime-user/9f2c4a7b1e6d05384c9a1b2d3e4f5061";

    /**
     * The opaque value a request presents in its authorization header.
     *
     * <p>Assumptions: it is not a token of any provider and cannot be one, since the decoder that
     * resolves it is substituted and nothing verifies a signature here. What the value buys is a
     * distinctive string that a rendered refusal body can be asserted not to echo.</p>
     */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /**
     * A value carrying the shape a sealed cursor token has, standing for the leading boundary.
     *
     * <p>Assumptions: the page envelope refuses a boundary component that is not a sealed token,
     * precisely so that a raw keyset key cannot be published to a client, so a readable stand-in
     * cannot be used. The shape is a version segment, a sixteen-character encoded segment and a
     * trailing segment, separated by periods, and this value reproduces it without holding a real
     * authentication code -- this class asserts that the adapter passes the envelope through
     * untouched, not that a token verifies, which is asserted where tokens are minted.</p>
     */
    private static final String SEALED_FIRST_KEY =
            CursorToken.VERSION + ".Zmlyc3Qtbm9uY2U5." + "A".repeat(43);

    /** A second value of the same sealed shape, standing for the trailing boundary. */
    private static final String SEALED_LAST_KEY =
            CursorToken.VERSION + ".bGFzdC1ub25jZTk5." + "B".repeat(43);

    /** A third value of the same sealed shape, standing for a cursor a caller sends back. */
    private static final String SEALED_SUPPLIED_CURSOR =
            CursorToken.VERSION + ".c3VwcGxpZWQtY3Vy." + "C".repeat(43);

    /**
     * The backward direction the contract enumerates, read from the adapter's own domain expression.
     *
     * <p>Assumptions: the adapter bounds the parameter with the two-value alternation it publishes,
     * so this value is one of exactly two the route admits and the case below sends the other kind to
     * assert the refusal.</p>
     */
    private static final String DIRECTION_BACKWARD = "previous";

    /** A direction outside the enumeration, so that the bound has something to refuse. */
    private static final String DIRECTION_UNSUPPORTED = "backwards";

    /**
     * The serialiser the request bodies below are written with.
     *
     * <p>Assumptions: the mapper is built directly rather than taken from the slice, because the
     * bodies below are inputs and the response shape is asserted by the response matchers and by the
     * member census helpers.</p>
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * A fixed instant, so that a stamped problem body is reproducible from this file alone.
     *
     * <p>Assumptions: the stamp is caller-supplied. The shared error record takes its timestamp as a
     * constructor argument and the shared timestamp formatter publishes no no-argument accessor, so a
     * body's instant comes from whichever clock the advice and the adapter were built with, and
     * pinning that clock is the only way to make the value predictable.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-08T09:14:27.481903Z");

    /**
     * The application context holding the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Trade-offs: one context is refreshed for the whole class rather than one per case, and the
     * substituted beans are reset before each case to keep them independent. Building the security
     * chain is the expensive half of the refresh and a per-case context would rebuild it while
     * asserting nothing further; the accepted cost is that a case reconfiguring a bean rather than
     * restubbing it would leak, which is why nothing below does.</p>
     */
    private static AnnotationConfigWebApplicationContext context;

    /** The entry point every request below is issued through, with the deployed chain installed. */
    private static MockMvc mockMvc;

    /** The substituted user-administration service, so that no case here reaches a store. */
    private static UserService users;

    /** The substituted token decoder, which answers for the header a caller presents. */
    private static JwtDecoder jwtDecoder;

    /**
     * Refreshes the context once and installs the deployed security chain in front of the adapter.
     *
     * <p>Assumptions: the chain is added to the entry point explicitly. A chain is a container-level
     * filter in a running service and this entry point installs no filter it is not given, so
     * omitting this step would leave every authorization assertion below passing for the wrong reason
     * -- the request would reach the handler with no filter having examined it. The first case below
     * exists to prove the installation took, by requiring a challenge on a protected route.</p>
     */
    @BeforeAll
    static void refreshSliceContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SliceWiring.class);
        context.refresh();

        users = context.getBean(UserService.class);
        jwtDecoder = context.getBean(JwtDecoder.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * Closes the context so that the class leaves no refreshed application behind it.
     */
    @AfterAll
    static void closeSliceContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Clears the substituted beans so that each case starts from no stubbing and no recorded call.
     */
    @BeforeEach
    void resetSubstitutedBeans() {
        reset(users);
        reset(jwtDecoder);
    }

    /**
     * Asserts every one of the five routes challenges a caller that presents no token at all.
     *
     * <p>Assumptions: the challenge is recognised by three marks together, because any one alone is
     * weaker than the claim. The status is 401, the body carries the shared unauthenticated code and
     * the body carries the shared unauthenticated sentence; checking the status alone would miss a
     * renderer that answered with no body, which is what the framework default does and what the
     * deployed chain replaces.</p>
     *
     * <p>Assumptions: the service is asserted untouched afterwards. A chain that authenticated
     * nothing would let each request reach the handler and the handler would call the service, so the
     * absence of any interaction is what distinguishes a refusal decided before the handler from one
     * decided inside it.</p>
     *
     * @throws Exception if any of the five requests cannot be performed
     */
    @Test
    @DisplayName("all five routes challenge a caller presenting no token")
    void allFiveRoutesChallengeAnUnauthenticatedCaller() throws Exception {
        for (MockHttpServletRequestBuilder request : everyRosterRequest()) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED))
                    .andExpect(jsonPath("$.message")
                            .value(ApiErrorSecurityHandlers.MESSAGE_UNAUTHENTICATED));
        }

        verifyNoInteractions(users);
    }

    /**
     * Asserts every one of the five routes refuses a caller holding only the ordinary authority.
     *
     * <p>Assumptions: the ordinary group is a legitimate, fully authenticated caller, so the refusal
     * must arrive as 403 and specifically not as 401. The deployed rule table widens an
     * ordinary-required rule to admit an administrator but never the reverse, so a caller in the
     * ordinary group alone reaches none of these five operations -- which on this context is the
     * widest available consequence, since the five create, alter and delete the very rows that decide
     * who is an administrator.</p>
     *
     * @throws Exception if any of the five requests cannot be performed
     */
    @Test
    @DisplayName("all five routes refuse a caller holding only the ordinary authority")
    void allFiveRoutesRefuseTheOrdinaryAuthority() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

        for (MockHttpServletRequestBuilder request : everyRosterRequest()) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN))
                    .andExpect(jsonPath("$.message")
                            .value(GlobalExceptionHandler.MESSAGE_FORBIDDEN));
        }

        verifyNoInteractions(users);
    }

    /**
     * Asserts the administrator authority admits every one of the five routes to its own handler.
     *
     * <p>Assumptions: admission is asserted as the SUCCESS STATUS each operation declares rather than
     * as the mere absence of a refusal, and that is only possible here because this slice registers
     * the real handlers. The sibling sign-on slice can assert admission on this path only negatively,
     * since it registers no roster handler and an admitted request there finds none; this case is
     * therefore the positive half of that pair and the reason it lives in this class.</p>
     *
     * @throws Exception if any of the five requests cannot be performed
     */
    @Test
    @DisplayName("the administrator authority admits all five routes to their handlers")
    void theAdministratorAuthorityAdmitsAllFiveRoutes() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.list(any(), any(), anyString())).thenReturn(pageOfOneRow());
        when(users.create(any(CreateUserRequest.class))).thenReturn(createdRow());
        when(users.read(STORED_IDENTIFIER)).thenReturn(storedRow());
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class)))
                .thenReturn(replacedRow());

        mockMvc.perform(listRequest().header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(createRequest(validCreateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isCreated());
        mockMvc.perform(readRequest(STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(updateRequest(STORED_IDENTIFIER, validUpdateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(confirmedDeleteRequest(STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isNoContent());
    }

    /**
     * Asserts a token whose group claim is absent yields no authority, no failure and no echo.
     *
     * <p>Assumptions: an absent claim is a legitimate token shape and the deployed converter treats it
     * as an empty collection rather than raising or answering null, because a pool omits the claim
     * entirely for a caller in no group. The observable consequence is a refusal on an authority
     * ground and specifically NOT a fault, so a 500 here would mean the converter raised on a claim a
     * real pool can legitimately omit.</p>
     *
     * <p>Assumptions: the body is additionally asserted not to echo the presented value, because a
     * rendered refusal is the one place token content could leak into a response.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a token with no group claim is refused on authority grounds and echoes nothing")
    void aTokenWithNoGroupClaimIsRefusedAndEchoesNothing() throws Exception {
        stubDecoderWithGroups(null);

        String body = mockMvc.perform(listRequest()
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(PRESENTED_TOKEN, TOKEN_SUBJECT);
        verifyNoInteractions(users);
    }

    /**
     * Asserts a type code supplied in a request body is never honoured for the authority decision.
     *
     * <p>Refactoring Rationale: the reference derived this decision from a value that had made a round
     * trip through the terminal. {@code app/cbl/COSGN00C.cbl} L227 moves the stored type into the
     * session structure and L230 tests the administrator condition name over it, and that structure is
     * handed back to the terminal on every turn -- {@code app/cbl/COUSR02C.cbl} L94 shows a program
     * reading it straight back in from the passed area. A client could therefore in principle return a
     * type it had not been given. Here the group arrives in a signed claim, and a claim is not
     * something a client can assert, so a body member naming the administrator code changes nothing.
     * Without this case that property is untested, because the happy paths never try it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a type code in the request body never grants the administrative authority")
    void aTypeCodeInTheBodyNeverGrantsAuthority() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

        mockMvc.perform(createRequest(MAPPER.writeValueAsString(new CreateUserRequest(
                        SUBMITTED_FIRST_NAME, SUBMITTED_LAST_NAME, CREATED_IDENTIFIER,
                        ADMIN_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isForbidden());

        verify(users, never()).create(any(CreateUserRequest.class));
    }

    /**
     * Asserts the list route answers 200 carrying every component of the page envelope.
     *
     * <p>Assumptions: all five components are asserted, and the member census is asserted as an
     * EQUALITY against the envelope record's own declared components rather than as a spot check of
     * the ones this case cares about. An equality is what refuses an added member: a body that gained
     * a row count or an ordinal page number would satisfy every individual matcher below and fail the
     * census, which is the drift this case exists to catch.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the list route answers 200 with the whole page envelope and no other member")
    void theListRouteAnswersTheWholePageEnvelope() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.list(isNull(), isNull(), eq(TOKEN_SUBJECT))).thenReturn(pageOfOneRow());

        String body = mockMvc.perform(listRequest()
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(STORED_IDENTIFIER))
                .andExpect(jsonPath("$.items[0].firstName").value(SUBMITTED_FIRST_NAME))
                .andExpect(jsonPath("$.items[0].lastName").value(SUBMITTED_LAST_NAME))
                .andExpect(jsonPath("$.items[0].userType").value(ORDINARY_TYPE_CODE))
                .andExpect(jsonPath("$.firstKey").value(SEALED_FIRST_KEY))
                .andExpect(jsonPath("$.lastKey").value(SEALED_LAST_KEY))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberNamesOf(body))
                .containsExactlyInAnyOrderElementsOf(declaredComponentsOf(PageResponse.class));
        assertThat(memberNamesOf(MAPPER.readTree(body).get("items").get(0).toString()))
                .containsExactlyInAnyOrderElementsOf(declaredComponentsOf(UserSummary.class));
    }

    /**
     * Asserts the cursor and the direction reach the service exactly as the caller sent them.
     *
     * <p>Assumptions: the cursor is opaque to this layer, so the assertion is a pass-through and not a
     * decode. The adapter bounds its length and nothing more; opening it is the service's, which is
     * why a case here that expected a decoded key would be asserting a responsibility this package
     * does not hold.</p>
     *
     * <p>Assumptions: the third argument is the caller name the resource server derived from the
     * subject claim, so this case also proves the adapter passes the AUTHENTICATED identity rather
     * than anything the caller supplied alongside the cursor.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the cursor, the direction and the authenticated caller reach the service unaltered")
    void theCursorAndDirectionReachTheServiceUnaltered() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.list(any(), any(), anyString())).thenReturn(PageResponse.empty());

        mockMvc.perform(listRequest()
                        .param("cursor", SEALED_SUPPLIED_CURSOR)
                        .param("direction", DIRECTION_BACKWARD)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk());

        verify(users).list(SEALED_SUPPLIED_CURSOR, DIRECTION_BACKWARD, TOKEN_SUBJECT);
    }

    /**
     * Asserts a direction outside the published enumeration is refused before the service runs.
     *
     * <p>Assumptions: the refusal is asserted together with the service being untouched, because a
     * bound that admitted the value and let the service reject it would answer the same status while
     * having moved the decision out of the layer that publishes it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a direction outside the enumeration answers 400 and the service is never reached")
    void aDirectionOutsideTheEnumerationAnswers400() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(listRequest()
                        .param("cursor", SEALED_SUPPLIED_CURSOR)
                        .param("direction", DIRECTION_UNSUPPORTED)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest());

        verify(users, never()).list(any(), any(), any());
    }

    /**
     * Asserts the list route declares exactly the two cursor parameters and no positional one.
     *
     * <p>Alternatives Considered: asserting the absence of a positional parameter by name was
     * evaluated and rejected, because an absence check can only refuse the names it happens to
     * enumerate and a differently spelled one would slip past. The declared set is asserted as an
     * EQUALITY instead, so any third request parameter fails this case whatever it is called. Paging
     * by ordinal position is what is being kept out: under concurrent inserts it skips rows and
     * repeats rows, since the ordinal of a row moves when a row before it is inserted, whereas
     * reading by key from the identifier the reference browsed at {@code app/cbl/COUSR00C.cbl} L240,
     * L242, L263 and L265 cannot skip or repeat.</p>
     *
     * @throws Exception if the handler method cannot be resolved, which a rename would cause and which
     *     should surface here rather than as a silently narrowed assertion
     */
    @Test
    @DisplayName("the list route declares exactly the two cursor parameters")
    void theListRouteDeclaresExactlyTheTwoCursorParameters() throws Exception {
        Method handler = UserController.class.getDeclaredMethod(
                "listUsers", String.class, String.class, Principal.class);

        assertThat(requestParameterNamesOf(handler)).containsExactly("cursor", "direction");
    }

    /**
     * Asserts a cursor that names no row is answered 200 positioned at the start, never 404.
     *
     * <p>Assumptions: this is the reference's own outcome and the arm it takes is the reason. The
     * browse start at {@code app/cbl/COUSR00C.cbl} L588 keys its absent-key arm on the NOT-FOUND
     * response at L600 and not on end-of-file, and that arm at L600 to L606 merely marks the file
     * exhausted and repaints the screen. Answering 404 here would turn a repaint into a missing
     * resource and would tell a caller its cursor named something that had been deleted, when the
     * reference simply showed the first page.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a cursor naming no row answers 200 with an empty page rather than 404")
    void aCursorNamingNoRowAnswers200() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.list(eq(SEALED_SUPPLIED_CURSOR), any(), anyString()))
                .thenReturn(PageResponse.empty());

        mockMvc.perform(listRequest()
                        .param("cursor", SEALED_SUPPLIED_CURSOR)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /**
     * Asserts a store read that could not be completed answers 500 with the reference sentence.
     *
     * <p>Assumptions: the sentence is carried into the body while the diagnostic that provoked it is
     * not, and both halves matter. The reference wrote the diagnostic to the operator console at
     * {@code app/cbl/COUSR00C.cbl} L608 and the sentence to the screen at L610, so the split is the
     * reference's own; here the console's half stays in the service log, where the correlation
     * identifier joins the two records, and the caller's half is the sentence alone.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a failed store read answers 500 with the reference sentence and no diagnostic")
    void aFailedStoreReadAnswers500WithoutTheDiagnostic() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        // Assumptions: the thrown class is exactly the standard illegal-state one, and a subclass would
        // not do. The shared advice tests that class by EQUALITY before it carries any sentence into
        // the body -- GlobalExceptionHandler.java L1650 to L1652, with the reason for preferring an
        // equality to an instance test recorded at L1640 to L1643 -- and it consults only the message,
        // never the cause. Thrown as a subclass, this case would receive the generic internal sentence
        // and would then assert nothing about the sentence the reference itself writes.
        when(users.list(any(), any(), anyString())).thenThrow(
                new IllegalStateException(MESSAGE_UNABLE_TO_LOOKUP,
                        new IllegalStateException(STORE_DIAGNOSTIC)));

        String body = mockMvc.perform(listRequest()
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_INTERNAL))
                .andExpect(jsonPath("$.message").value(MESSAGE_UNABLE_TO_LOOKUP))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(STORE_DIAGNOSTIC);
    }

    /**
     * Asserts the create route answers 201 with the stored row and a header addressing it.
     *
     * <p>Assumptions: the location header is asserted as a whole value rather than as a suffix,
     * because the collection prefix is part of the address a client will follow and a header carrying
     * only the identifier would be resolved against the wrong base.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the create route answers 201 with a location header addressing the row")
    void theCreateRouteAnswers201WithALocationHeader() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.create(any(CreateUserRequest.class))).thenReturn(createdRow());

        mockMvc.perform(createRequest(validCreateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        UserController.COLLECTION_PATH + "/" + CREATED_IDENTIFIER))
                .andExpect(jsonPath("$.userId").value(CREATED_IDENTIFIER))
                .andExpect(jsonPath("$.userType").value(ADMIN_TYPE_CODE));
    }

    /**
     * Asserts a taken identifier answers 409 with the reference sentence, missing plural and all.
     *
     * <p>Assumptions: this refusal is the ADAPTER's own and not the shared advice's, which is why it
     * is asserted here at all. The shared advice maps a stale version, an unavailable lock and a
     * referenced row to that status, and none of their sentences is this literal; the adapter
     * therefore declares a handler for the service's own duplicate refusal, and this case is what
     * proves that handler is reached rather than the advice. It also asserts the field array is
     * EMPTY, because the submitted identifier is well formed and attributing the failure to it would
     * point a caller at a value that is not at fault.</p>
     *
     * @throws Exception if the request cannot be performed, or if the refusal cannot be constructed
     */
    @Test
    @DisplayName("a taken identifier answers 409 with the reference sentence and an empty array")
    void aTakenIdentifierAnswers409() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.create(any(CreateUserRequest.class))).thenAnswer(invocation -> {
            throw duplicateUser();
        });

        mockMvc.perform(createRequest(validCreateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT))
                .andExpect(jsonPath("$.message").value(MESSAGE_IDENTIFIER_TAKEN))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    /**
     * Asserts the create route reports one entry for every component that failed, and no other key.
     *
     * <p>Refactoring Rationale: the reference reported ONE failing field per turn, because its chain
     * is a single evaluation whose first matching arm wins -- {@code app/cbl/COUSR01C.cbl} L117 to
     * L151, with arms at L118, L124, L130, L136 and L142 and a catch-all at L148 -- so the remaining
     * fields were never examined and one sentence reached the screen. The target reports every failing
     * component in one answer, so a caller corrects them all at once instead of resubmitting once per
     * field. The widening is documented rather than silent and is registered as
     * {@code D-ERROR-ACCUMULATION} in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the aggregate sentence on a MULTI-field refusal is the shared generic one and not
     * a reference sentence, and that follows from a deliberate choice recorded on the request record
     * itself. The shared advice latches the leading entry's own sentence only for a body that opts into
     * the ordering interface, and this record declines it because the interface takes body property
     * names while this operation's real sequence continues past the body -- the identifier is checked
     * for blankness at L130 and again for collision by the write at L240 to L248, whose duplicate arms
     * sit at L260 and L261, and no body-shape constraint can ask a question about stored rows. The
     * ordered chain that reproduces the reference's latching sequence therefore lives in the sibling
     * service package, which is also where it is asserted.</p>
     *
     * <p>Assumptions: the keys are compared as a SET and not as a sequence, because the accumulation
     * order at this layer is the validator's iteration order rather than a declared one. Asserting a
     * sequence here would encode whichever order one validator release happens to produce and would
     * make this case fail on an upgrade that changed nothing observable.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the create route reports an entry for every failing component and no other key")
    void theCreateRouteReportsAnEntryForEveryFailingComponent() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        String body = mockMvc.perform(createRequest(MAPPER.writeValueAsString(
                        new CreateUserRequest(BLANK_SUBMISSION, BLANK_SUBMISSION, BLANK_SUBMISSION,
                                BLANK_SUBMISSION)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(new LinkedHashSet<>(fieldErrorKeysOf(body)))
                .containsExactlyInAnyOrderElementsOf(declaredComponentsOf(CreateUserRequest.class));
        verify(users, never()).create(any(CreateUserRequest.class));
    }

    /**
     * Asserts a blank first name alone is answered with that field's own reference sentence.
     *
     * <p>Assumptions: exactly one entry is expected because exactly one component was submitted
     * blank, so this case and the order case above assert opposite halves of the same array: one
     * entry when one field fails, every entry in declared order when all of them do.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank first name answers 400 with that field's reference sentence")
    void aBlankFirstNameAnswersItsOwnReferenceSentence() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(createRequest(MAPPER.writeValueAsString(new CreateUserRequest(
                        BLANK_SUBMISSION, SUBMITTED_LAST_NAME, CREATED_IDENTIFIER,
                        ADMIN_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("firstName"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(MESSAGE_FIRST_NAME_REQUIRED));

        verify(users, never()).create(any(CreateUserRequest.class));
    }

    /**
     * Asserts a blank identifier is answered with the identifier's own reference sentence and key.
     *
     * <p>Assumptions: the key is the transport name of the component the reference's cursor move
     * identifies. The reference has no per-field array to key at all -- a copybook census over the
     * five programs of this context returns nothing for the templated highlight book -- and signals
     * the field in error solely by moving minus one into the screen field's length subfield, at
     * {@code app/cbl/COUSR01C.cbl} L134 for this one. The cursor target is therefore the only
     * per-field signal the reference carries and it is what this key derives from.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank identifier answers 400 keyed to the identifier with its own sentence")
    void aBlankIdentifierAnswersItsOwnReferenceSentence() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(createRequest(MAPPER.writeValueAsString(new CreateUserRequest(
                        SUBMITTED_FIRST_NAME, SUBMITTED_LAST_NAME, BLANK_SUBMISSION,
                        ADMIN_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("userId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(MESSAGE_IDENTIFIER_REQUIRED));

        verify(users, never()).create(any(CreateUserRequest.class));
    }

    /**
     * Asserts the read route answers 200 with the whole row and no member beyond its declared ones.
     *
     * <p>Refactoring Rationale: the member census is the observable half of divergence D-4. The
     * reference held a fourth field on this record, declared at {@code app/cpy/CSUSR01Y.cpy} L21, and
     * the update program moved the stored value straight back onto the screen at
     * {@code app/cbl/COUSR02C.cbl} L169, so displaying a row disclosed it. The target's row carries no
     * such column and no such member, and this case asserts the member set as an EQUALITY so that
     * reintroducing one fails here rather than passing unnoticed. Two alternatives were rejected:
     * porting the field as stored, which would carry the disclosure forward unchanged, and keeping a
     * local digested column, which would leave this context holding a credential it has no reason to
     * hold now that the pool owns them. The divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} and is the one place in this
     * migration where parity is explicitly declined.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the read route answers 200 with exactly the members its record declares")
    void theReadRouteAnswersExactlyTheDeclaredMembers() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.read(STORED_IDENTIFIER)).thenReturn(storedRow());

        String body = mockMvc.perform(readRequest(STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(STORED_IDENTIFIER))
                .andExpect(jsonPath("$.firstName").value(SUBMITTED_FIRST_NAME))
                .andExpect(jsonPath("$.lastName").value(SUBMITTED_LAST_NAME))
                .andExpect(jsonPath("$.userType").value(ORDINARY_TYPE_CODE))
                .andExpect(jsonPath("$.cognitoSub").value(ROW_SUBJECT.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberNamesOf(body))
                .containsExactlyInAnyOrderElementsOf(declaredComponentsOf(UserResponse.class));
    }

    /**
     * Asserts an identifier that names no row answers 404 with the reference sentence.
     *
     * <p>Assumptions: the substituted service raises the standard no-such-element type, because that
     * is the type the shared advice claims for an absent row -- {@code GlobalExceptionHandler.java}
     * L925 declares the handler and L926 opens the method that renders it. A refusal type of this
     * class's own invention would fall to the catch-all arm and be rendered as 500, so the case would
     * assert a mapping the deployed advice does not make.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an identifier naming no row answers 404 with the reference sentence")
    void anIdentifierNamingNoRowAnswers404() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.read(ABSENT_IDENTIFIER))
                .thenThrow(new NoSuchElementException(MESSAGE_IDENTIFIER_NOT_FOUND));

        mockMvc.perform(readRequest(ABSENT_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.message").value(MESSAGE_IDENTIFIER_NOT_FOUND));
    }

    /**
     * Asserts a path segment wider than the reference field answers 400 rather than 404.
     *
     * <p>Assumptions: the bound is declared on the path parameter as well as inside the request
     * records because a path segment is covered by none of them. Without it a nine-character segment
     * would reach the store as a key that cannot exist and be answered as a missing row, reporting a
     * row as absent when the request was malformed -- two different things a caller must be able to
     * tell apart.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an over-long path segment answers 400 rather than 404")
    void anOverLongPathSegmentAnswers400() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(readRequest(OVER_LONG_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest());

        verify(users, never()).read(any());
    }

    /**
     * Asserts the update route answers 200 with the row as stored and requires no confirmation.
     *
     * <p>Refactoring Rationale: the reference committed this update on TWO keys, one of which was the
     * key that navigates away. {@code app/cbl/COUSR02C.cbl} L111 and L112 perform the update on the
     * back key and only then navigate at L119, while L122 and L123 perform it on the dedicated save
     * key; the enter key at L109 and L110 merely loads. The target reproduces the dedicated save and
     * not the navigating one, because a REST update is an explicit mutating intent and no navigation
     * verb mutates. Reproducing the navigating save would mean a back action mutated state, which has
     * no addressable analogue over HTTP and would make the observable interface non-idempotent in a
     * way a browser client could not express.</p>
     *
     * <p>Trade-offs: an operator who relied on the back key saving loses that shortcut, and the
     * reference's own guidance sentence at L336 -- which invites the dedicated key -- was never a
     * program-enforced gate precisely because the back key also committed. What is gained is that
     * every mutation on this route is a request a caller made on purpose.</p>
     *
     * <p>Assumptions: the request carries no confirmation parameter and is not refused for the lack of
     * one, and the handler is additionally asserted to declare none, so the asymmetry with the delete
     * route is a property of the signature rather than of this one request.</p>
     *
     * @throws Exception if the request cannot be performed, or if the handler cannot be resolved
     */
    @Test
    @DisplayName("the update route answers 200 and declares no confirmation parameter")
    void theUpdateRouteAnswers200AndRequiresNoConfirmation() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class)))
                .thenReturn(replacedRow());

        mockMvc.perform(updateRequest(STORED_IDENTIFIER, validUpdateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value(REPLACEMENT_FIRST_NAME))
                .andExpect(jsonPath("$.lastName").value(REPLACEMENT_LAST_NAME))
                .andExpect(jsonPath("$.userType").value(ADMIN_TYPE_CODE));

        Method handler = UserController.class.getDeclaredMethod(
                "updateUser", String.class, UpdateUserRequest.class);
        assertThat(requestParameterNamesOf(handler)).isEmpty();
    }

    /**
     * Asserts a submission that changed nothing answers 400 with the reference sentence.
     *
     * <p>Assumptions: this is DIRTY DETECTION and not concurrency control, and conflating the two
     * would give the wrong status. The reference compares the submitted values against the stored ones
     * field by field -- {@code app/cbl/COUSR02C.cbl} L219 to L222, L223 to L226, L227 to L230 and L231
     * to L234, four comparisons -- and when none differed it writes this sentence at L239 in red at
     * L241 instead of rewriting the record. It rejects the submission because NOTHING CHANGED, which
     * is the semantic opposite of a contention refusal rejecting one because something else changed.
     * Only three of those four comparisons have a target counterpart, the fourth being the field
     * divergence D-4 removed, and stating that keeps a reader from concluding the reference compared
     * three.</p>
     *
     * <p>Refactoring Rationale: there is no version column on this context's row and no
     * contention path to reach, so answering 409 here would publish a conflict this context cannot
     * have. The reference held its lock inside one task instead -- its read takes the update option at
     * L328 while the resource definition sets the locking update model at
     * {@code app/csd/CARDDEMO.CSD} L93 and no record-level sharing at L89 -- so it needed no
     * before-image comparison of the kind the account-update program hand-rolls, and none of the five
     * programs of this context contains one.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a submission that changed nothing answers 400 with the reference sentence")
    void aSubmissionThatChangedNothingAnswers400() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class))).thenThrow(
                new ClientInputException(ApiError.CODE_VALIDATION, MESSAGE_NOTHING_MODIFIED));

        mockMvc.perform(updateRequest(STORED_IDENTIFIER, validUpdateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message").value(MESSAGE_NOTHING_MODIFIED))
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()));
    }

    /**
     * Asserts the update route reports its own components and never the create route's fourth one.
     *
     * <p>Assumptions: this case is separate from the create case above and must stay separate, because
     * the two operations report DIFFERENT key sets and the difference is the reference's own. The
     * screen order at {@code app/cpy-bms/COUSR02.CPY} places the identifier at L60 ahead of the two
     * names at L66 and L72, where {@code app/cpy-bms/COUSR01.CPY} places it third at L72, and
     * {@code app/cbl/COUSR02C.cbl} examines the identifier FIRST at L180 while
     * {@code app/cbl/COUSR01C.cbl} examines it third at L130. On this operation the identifier travels
     * in the path rather than in the body, so it is not among the keys this refusal can carry at all,
     * and the two records differ in arity as well -- four components against three. A single
     * parameterised case covering both routes would have to pick one expectation and would then pass
     * against an implementation that had reported the other route's key set.</p>
     *
     * <p>Assumptions: the aggregate restates the leading reported entry rather than a fixed sentence,
     * because a path variable and a body arrive together on this route and the shared advice answers
     * that pair from its parameter handler, which latches the first accumulated entry. The property
     * asserted is therefore the self-consistency the handler guarantees -- the aggregate is one of the
     * entries and specifically the first -- rather than a particular sentence, which would encode one
     * validator release's iteration order.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the update route reports its own key set and restates its leading entry")
    void theUpdateRouteReportsItsOwnKeySet() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        String body = mockMvc.perform(updateRequest(STORED_IDENTIFIER,
                        MAPPER.writeValueAsString(new UpdateUserRequest(
                                BLANK_SUBMISSION, BLANK_SUBMISSION, ORDINARY_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(new LinkedHashSet<>(fieldErrorKeysOf(body)))
                .containsExactlyInAnyOrder("firstName", "lastName")
                .isSubsetOf(declaredComponentsOf(UpdateUserRequest.class))
                .doesNotContain("userId");
        assertThat(MAPPER.readTree(body).path("message").asString())
                .isEqualTo(MAPPER.readTree(body).path("fieldErrors").get(0).path("message")
                        .asString());
        verify(users, never()).update(any(), any(UpdateUserRequest.class));
    }

    /**
     * Asserts the update route answers a single blank name with that field's reference sentence.
     *
     * <p>Assumptions: the sentence is the one {@code app/cbl/COUSR02C.cbl} writes on its own arm at
     * L188, which happens to be the same text the create program writes at L120, while the KEY SETS
     * of the two operations differ as the case above records. Asserting the sentence on each route
     * separately is what would catch an implementation that had wired one route's message catalogue
     * to the other's fields.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the update route answers a single blank name with that field's reference sentence")
    void theUpdateRouteAnswersASingleBlankNameWithItsReferenceSentence() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(updateRequest(STORED_IDENTIFIER, MAPPER.writeValueAsString(
                        new UpdateUserRequest(BLANK_SUBMISSION, REPLACEMENT_LAST_NAME,
                                ADMIN_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("firstName"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(MESSAGE_FIRST_NAME_REQUIRED));

        verify(users, never()).update(any(), any(UpdateUserRequest.class));
    }

    /**
     * Asserts a store write that failed answers 500 with the reference sentence and no diagnostic.
     *
     * <p>Assumptions: the sentence expected here is the write path's own and deliberately not the read
     * path's. The reference wrote a different one on each -- {@code app/cbl/COUSR02C.cbl} L386 for a
     * write that could not be completed and {@code app/cbl/COUSR00C.cbl} L610 for a read that could
     * not -- so a single shared expectation across the two cases would let either sentence satisfy
     * both and neither case would pin its own. Why the diagnostic is handed over as the refusal's
     * cause rather than folded into its sentence is recorded once, on the value itself.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a failed store write answers 500 with the reference sentence and no diagnostic")
    void aFailedStoreWriteAnswers500WithoutTheDiagnostic() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class))).thenThrow(
                new IllegalStateException(MESSAGE_UNABLE_TO_UPDATE,
                        new IllegalStateException(STORE_DIAGNOSTIC)));

        String body = mockMvc.perform(updateRequest(STORED_IDENTIFIER, validUpdateBody())
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(MESSAGE_UNABLE_TO_UPDATE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(STORE_DIAGNOSTIC);
    }

    /**
     * Asserts a confirmed delete answers 204 with no body at all.
     *
     * <p>Assumptions: the deployed adapter declares this operation as carrying no content --
     * {@code UserController.java} L502 and L503 declare it and L531 answers it -- so there is no body
     * for a success sentence to travel in. The reference did write one, at
     * {@code app/cbl/COUSR03C.cbl} L318 to L321 with the literal at L320 and the advisory colour set
     * at L317 inside the arm opened at L314, and the adapter's own contract records at L463 and L464
     * that it is not returned.</p>
     *
     * <p>Alternatives Considered: asserting 200 with that sentence was evaluated and rejected. It
     * would assert a contract this adapter does not publish, so the case would fail on its own
     * expectation rather than on any defect in the code under assertion, and the emptiness of the body
     * -- the one property this status actually promises -- would go unasserted.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a confirmed delete answers 204 with no body")
    void aConfirmedDeleteAnswers204WithNoBody() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        MvcResult answered = mockMvc.perform(confirmedDeleteRequest(STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(answered.getResponse().getContentAsString()).isEmpty();
        verify(users).delete(STORED_IDENTIFIER);
    }

    /**
     * Asserts a delete without an affirmative confirmation is refused and removes nothing.
     *
     * <p>Refactoring Rationale: the reference gave the delete ONE dedicated key and no other.
     * {@code app/cbl/COUSR03C.cbl} L121 and L122 perform the removal on the dedicated key, while the
     * back key branch at L111 to L118 carries no removal at all and the cancel key at L123 to L125
     * carries none either -- which is exactly where the update program differs, its own back key
     * having committed. Displaying a row and destroying one were therefore never the same request. A
     * keystroke cannot survive as a keystroke over HTTP, so the parameter is the faithful analogue of
     * that single dedicated key; without it a prefetch, a retried request or a crawler following a
     * link could destroy a row.</p>
     *
     * <p>Assumptions: both spellings of a missing confirmation are asserted, the parameter absent and
     * the parameter present and negative, because a bound that only refused the absent case would let
     * an explicit negative through as though it were an affirmative.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("a delete without an affirmative confirmation is refused and removes nothing")
    void aDeleteWithoutAffirmativeConfirmationRemovesNothing() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        mockMvc.perform(delete(UserController.COLLECTION_PATH + "/" + STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmed"));

        mockMvc.perform(delete(UserController.COLLECTION_PATH + "/" + STORED_IDENTIFIER)
                        .param("confirmed", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmed"));

        verify(users, never()).delete(any());
    }

    /**
     * Asserts a failed delete answers with the sentence the reference wrote on that very arm.
     *
     * <p>Assumptions: the sentence says update on a path that deletes, and it is asserted exactly as
     * written. {@code app/cbl/COUSR03C.cbl} reaches it from the catch-all arm at L329, writes the
     * diagnostic to the console at L330, sets its error flag at L331 and writes this text at L332 and
     * L333, inside the paragraph labelled at L305 whose removal verb takes only three operands at L308
     * to L310. The reference is not modified and the wording is not corrected; it is recorded in
     * {@code docs/architecture/cobol-to-service-traceability.md} as a divergence-free carry of a
     * mismatched sentence, because rule T8 makes the string itself the contract.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a failed delete answers the reference's own update wording, verbatim")
    void aFailedDeleteAnswersTheReferenceUpdateWording() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        doThrow(new IllegalStateException(MESSAGE_UNABLE_TO_UPDATE,
                        new IllegalStateException(STORE_DIAGNOSTIC)))
                .when(users).delete(STORED_IDENTIFIER);

        String body = mockMvc.perform(confirmedDeleteRequest(STORED_IDENTIFIER)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(MESSAGE_UNABLE_TO_UPDATE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(STORE_DIAGNOSTIC);
    }

    /**
     * Asserts no route creates a session and no answer carries a session cookie.
     *
     * <p>Refactoring Rationale: the reference was strictly pseudo-conversational, so all continuity
     * between screen turns travelled in one passed structure that the terminal handed back -- the
     * update program reads it straight back in at {@code app/cbl/COUSR02C.cbl} L94, and the resource
     * definitions give every transaction a zero-length work area at eighteen places, so there was no
     * second state channel for anything to hide in. That structure decomposes into a client-side route
     * history, a signed identity claim and a request path, and nothing replaces it in this process.
     * What that buys is concrete rather than stylistic: with no session affinity and no server-side
     * session store, any task can answer any request and a task replaced mid-conversation costs a
     * caller nothing, which is what makes horizontally-scaled tasks behind a load balancer viable.
     * Permitting a session would reintroduce the sticky routing that arrangement exists to avoid.</p>
     *
     * <p>Assumptions: the re-entry discriminator of {@code app/cpy/COCOM01Y.cpy} L29, whose two
     * condition values sit on L30 and L31, has no counterpart at all, so error presentation on these
     * routes is driven purely by the response body and never by a remembered turn. The absence is a
     * negative property, so it has to be asserted on purpose or it is simply untested.</p>
     *
     * @throws Exception if any of the five requests cannot be performed
     */
    @Test
    @DisplayName("no route creates a session and no answer carries a session cookie")
    void noRouteCreatesASession() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.list(any(), any(), anyString())).thenReturn(PageResponse.empty());
        when(users.create(any(CreateUserRequest.class))).thenReturn(createdRow());
        when(users.read(STORED_IDENTIFIER)).thenReturn(storedRow());
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class)))
                .thenReturn(replacedRow());

        for (MockHttpServletRequestBuilder request : everyRosterRequest()) {
            MvcResult answered = mockMvc
                    .perform(request.header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andReturn();

            assertThat(answered.getRequest().getSession(false)).isNull();
            assertThat(answered.getResponse().getCookies()).isEmpty();
        }
    }

    /**
     * Asserts the three mutating routes are not refused for carrying no forgery token.
     *
     * <p>Trade-offs: the forgery protection is disabled in the deployed chain because there is no
     * ambient credential for a forged request to ride on -- authentication here is a bearer token a
     * client attaches deliberately, never a cookie a browser sends on its own -- so the
     * confused-deputy condition cannot arise. The cost is that adding any cookie-authenticated route
     * to this service would make that reasoning wrong, and this case is what would then have to
     * change alongside it, which is why the property is asserted rather than assumed.</p>
     *
     * @throws Exception if any of the three requests cannot be performed
     */
    @Test
    @DisplayName("the three mutating routes are not refused for carrying no forgery token")
    void theMutatingRoutesAreNotRefusedForAMissingForgeryToken() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        when(users.create(any(CreateUserRequest.class))).thenReturn(createdRow());
        when(users.update(eq(STORED_IDENTIFIER), any(UpdateUserRequest.class)))
                .thenReturn(replacedRow());

        assertNoChainRefusal(mockMvc.perform(createRequest(validCreateBody())
                .header(HttpHeaders.AUTHORIZATION, bearerHeader())).andReturn());
        assertNoChainRefusal(mockMvc.perform(updateRequest(STORED_IDENTIFIER, validUpdateBody())
                .header(HttpHeaders.AUTHORIZATION, bearerHeader())).andReturn());
        assertNoChainRefusal(mockMvc.perform(confirmedDeleteRequest(STORED_IDENTIFIER)
                .header(HttpHeaders.AUTHORIZATION, bearerHeader())).andReturn());
    }

    /**
     * Asserts no refusal body of any route carries a member the error record does not declare.
     *
     * <p>Assumptions: this is the error-body half of the member census the success paths assert, and it
     * closes the same gap from the other side: a rendered refusal is where an implementation is most
     * likely to reach for extra context, and a body carrying a member beyond the shared error record's
     * own components would be a shape no published contract describes. The keys of the per-field array
     * are additionally asserted to be a SUBSET of the submitted record's components, which is what
     * refuses an entry attributed to a member the record does not declare.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refusal body carries no member and no field key beyond the declared ones")
    void aRefusalBodyCarriesNoUndeclaredMember() throws Exception {
        stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

        String body = mockMvc.perform(createRequest(MAPPER.writeValueAsString(
                        new CreateUserRequest(BLANK_SUBMISSION, SUBMITTED_LAST_NAME,
                                CREATED_IDENTIFIER, ADMIN_TYPE_CODE)))
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(declaredComponentsOf(ApiError.class))
                .containsAll(memberNamesOf(body));
        assertThat(declaredComponentsOf(CreateUserRequest.class))
                .containsAll(fieldErrorKeysOf(body));
    }

    /**
     * Builds one request for each of the five routes, in the order the contract publishes them.
     *
     * <p>Assumptions: the five are built together so that a case iterating them cannot omit one, which
     * is the failure mode an authorization assertion written route by route is most prone to: a rule
     * table gains a path, one case is updated and the others are not, and the untested route is the
     * one that was permitted by accident. The mutating three carry a body so that a refusal decided by
     * the chain is reached before body validation could answer first and mask it.</p>
     *
     * @return the five request builders, list then create then read then update then delete; never
     *     {@code null} and never empty
     */
    private static List<MockHttpServletRequestBuilder> everyRosterRequest() {
        return List.of(
                listRequest(),
                createRequest(validCreateBody()),
                readRequest(STORED_IDENTIFIER),
                updateRequest(STORED_IDENTIFIER, validUpdateBody()),
                confirmedDeleteRequest(STORED_IDENTIFIER));
    }

    /**
     * Builds a request for the collection route.
     *
     * @return a builder addressing the published collection path; never {@code null}
     */
    private static MockHttpServletRequestBuilder listRequest() {
        return get(UserController.COLLECTION_PATH);
    }

    /**
     * Builds a create request carrying the supplied body.
     *
     * @param body the serialised request body to submit; must not be {@code null}
     * @return a builder addressing the collection path with a JSON content type; never {@code null}
     */
    private static MockHttpServletRequestBuilder createRequest(String body) {
        return post(UserController.COLLECTION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * Builds a read request for one row.
     *
     * @param userId the identifier to place in the path segment; must not be {@code null}
     * @return a builder addressing that row; never {@code null}
     */
    private static MockHttpServletRequestBuilder readRequest(String userId) {
        return get(UserController.COLLECTION_PATH + "/" + userId);
    }

    /**
     * Builds an update request for one row, carrying the supplied body and no confirmation.
     *
     * @param userId the identifier to place in the path segment; must not be {@code null}
     * @param body the serialised request body to submit; must not be {@code null}
     * @return a builder addressing that row with a JSON content type; never {@code null}
     */
    private static MockHttpServletRequestBuilder updateRequest(String userId, String body) {
        return put(UserController.COLLECTION_PATH + "/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * Builds a delete request for one row, carrying the affirmative confirmation.
     *
     * @param userId the identifier to place in the path segment; must not be {@code null}
     * @return a builder addressing that row with the confirmation supplied; never {@code null}
     */
    private static MockHttpServletRequestBuilder confirmedDeleteRequest(String userId) {
        return delete(UserController.COLLECTION_PATH + "/" + userId).param("confirmed", "true");
    }

    /**
     * Serialises a create body every declared constraint of the request record accepts.
     *
     * @return the serialised body; never {@code null}
     * @throws JacksonException if the record cannot be serialised, which a component type change would
     *     cause and which should surface here rather than as an unexplained refusal
     */
    private static String validCreateBody() throws JacksonException {
        return MAPPER.writeValueAsString(new CreateUserRequest(
                SUBMITTED_FIRST_NAME, SUBMITTED_LAST_NAME, CREATED_IDENTIFIER, ADMIN_TYPE_CODE));
    }

    /**
     * Serialises an update body every declared constraint of the request record accepts.
     *
     * @return the serialised body; never {@code null}
     * @throws JacksonException if the record cannot be serialised, for the reason above
     */
    private static String validUpdateBody() throws JacksonException {
        return MAPPER.writeValueAsString(new UpdateUserRequest(
                REPLACEMENT_FIRST_NAME, REPLACEMENT_LAST_NAME, ADMIN_TYPE_CODE));
    }

    /**
     * Builds the row the substituted service reports for an existing identifier.
     *
     * @return a transfer record standing for a stored row of the ordinary type; never {@code null}
     */
    private static UserResponse storedRow() {
        return new UserResponse(STORED_IDENTIFIER, SUBMITTED_FIRST_NAME, SUBMITTED_LAST_NAME,
                ORDINARY_TYPE_CODE, ROW_SUBJECT);
    }

    /**
     * Builds the created body the substituted service reports for a newly created identifier.
     *
     * <p>⚠️ Refactoring Rationale: this fixture answers with the create body rather than the read row,
     * because the create operation's own answer changed shape. It previously returned the read
     * projection, which meant the one-time credential the pool account was created with existed
     * nowhere: not in the response, not in a column and not in any later operation. The row half is
     * still built through the read projection and composed by {@code CreatedUserResponse.of}, so the
     * five row values here cannot drift from the ones every other case asserts.</p>
     *
     * @return a transfer record standing for a created row of the administrator type, carrying the
     *     credential its account was created with; never {@code null}
     */
    private static CreatedUserResponse createdRow() {
        return CreatedUserResponse.of(
                new UserResponse(CREATED_IDENTIFIER, SUBMITTED_FIRST_NAME, SUBMITTED_LAST_NAME,
                        ADMIN_TYPE_CODE, ROW_SUBJECT),
                CREATED_CREDENTIAL_SECRET_NAME);
    }

    /**
     * Builds the row the substituted service reports after an accepted update.
     *
     * @return a transfer record whose two names differ from the stored row, so that a case asserting
     *     the changed values cannot pass by echoing the unchanged ones; never {@code null}
     */
    private static UserResponse replacedRow() {
        return new UserResponse(STORED_IDENTIFIER, REPLACEMENT_FIRST_NAME, REPLACEMENT_LAST_NAME,
                ADMIN_TYPE_CODE, ROW_SUBJECT);
    }

    /**
     * Builds a page carrying one summary row and both sealed boundaries.
     *
     * <p>Assumptions: a further page is reported affirmatively and an earlier one negatively, which is
     * a state the envelope's own constructor accepts only when the trailing boundary is present. That
     * pairing is the reference's: its forward-availability flag at {@code app/cbl/COUSR00C.cbl} L71 is
     * set from a read beyond the page at L311, so a page reporting more ahead is a page that knows
     * where ahead begins.</p>
     *
     * @return a one-row page with both boundaries sealed; never {@code null}
     */
    private static PageResponse<UserSummary> pageOfOneRow() {
        return PageResponse.ofRows(
                List.of(new UserSummary(STORED_IDENTIFIER, SUBMITTED_FIRST_NAME,
                        SUBMITTED_LAST_NAME, ORDINARY_TYPE_CODE)),
                SEALED_FIRST_KEY, SEALED_LAST_KEY, true);
    }

    /**
     * Stubs the substituted decoder to resolve any presented token into a token with these groups.
     *
     * <p>Assumptions: a null argument means the group claim is ABSENT from the token rather than
     * present and empty, and the two are different token shapes -- a pool omits the claim entirely for
     * a caller in no group. The claim name is the shared kernel's own constant, so a rename there
     * reaches this stub instead of leaving it asserting against a name the converter no longer
     * reads.</p>
     *
     * <p>Assumptions: the token carries a subject and a validity window because the resource server
     * derives the caller name from the subject, which the list route then passes to the service. No
     * validator runs, since the decoder that would apply them is the substituted one.</p>
     *
     * @param groups the group names to carry in the claim, or {@code null} to omit the claim entirely
     */
    private static void stubDecoderWithGroups(List<String> groups) {
        Jwt.Builder token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", "RS256")
                .subject(TOKEN_SUBJECT)
                .issuedAt(FIXED_INSTANT.minusSeconds(60))
                .expiresAt(FIXED_INSTANT.plusSeconds(600));
        if (groups != null) {
            token.claim(JwtRoleConverter.GROUPS_CLAIM, groups);
        }
        when(jwtDecoder.decode(anyString())).thenReturn(token.build());
    }

    /**
     * Builds the authorization header value a caller presents a bearer token in.
     *
     * @return the header value, scheme included; never {@code null}
     */
    private static String bearerHeader() {
        return "Bearer " + PRESENTED_TOKEN;
    }

    /**
     * Asserts an answered request was refused by neither of the chain's two refusal decisions.
     *
     * <p>Assumptions: both refusals are recognisable by two marks together and both are checked,
     * because either alone is weaker than the claim. A challenged request answers 401 and carries the
     * shared unauthenticated code; a denied one answers 403 and carries the shared forbidden code.
     * Checking the status alone would miss a renderer that answered a refusal under another status,
     * and checking the body alone would miss one that carried no body.</p>
     *
     * @param answered the result to inspect; must not be {@code null}
     * @throws Exception if the response body cannot be read
     */
    private static void assertNoChainRefusal(MvcResult answered) throws Exception {
        assertThat(answered.getResponse().getStatus())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value())
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(answered.getResponse().getContentAsString())
                .doesNotContain(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED,
                        GlobalExceptionHandler.CODE_FORBIDDEN);
    }

    /**
     * Reads the component names a record declares, in declaration order.
     *
     * <p>Assumptions: declaration order is the order the shared advice reports per-field entries in,
     * so reading it reflectively is what lets the two order cases above assert a sequence without
     * writing it out. A written sequence would have to be edited whenever a record gained a component,
     * and an editor who forgot would leave the case asserting a prefix of the truth and passing.</p>
     *
     * @param recordType the record class to read; must not be {@code null} and must be a record
     * @return the component names in declaration order; never {@code null} and never empty for the
     *     records this class passes
     */
    private static List<String> declaredComponentsOf(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Reads the member names a serialised object carries, at its top level only.
     *
     * @param json the serialised object to inspect; must not be {@code null}
     * @return the member names, in the order they were written; never {@code null}
     * @throws JacksonException if the value cannot be parsed, which a body that is not an object would
     *     cause and which should surface here rather than as an empty set silently satisfying a census
     */
    private static Set<String> memberNamesOf(String json) throws JacksonException {
        Set<String> names = new LinkedHashSet<>();
        JsonNode tree = MAPPER.readTree(json);
        for (String name : tree.propertyNames()) {
            names.add(name);
        }
        return names;
    }

    /**
     * Reads the field keys of a refusal body's per-field array, in the order the body carries them.
     *
     * @param json the serialised refusal body to inspect; must not be {@code null}
     * @return the keys in array order, empty when the body carries no array; never {@code null}
     * @throws JacksonException if the body cannot be parsed, for the reason given above
     */
    private static List<String> fieldErrorKeysOf(String json) throws JacksonException {
        List<String> keys = new ArrayList<>();
        for (JsonNode entry : MAPPER.readTree(json).path("fieldErrors")) {
            keys.add(entry.path("field").asString());
        }
        return keys;
    }

    /**
     * Reads the request-parameter names one handler method declares, in declaration order.
     *
     * <p>Assumptions: the names are read from the binding annotation rather than from the Java
     * parameter names, because a build that does not retain parameter names would otherwise report
     * synthetic ones and the census would compare the wrong strings. A parameter carrying no binding
     * annotation -- the caller principal on the list route -- is not a request parameter and is
     * omitted.</p>
     *
     * @param handler the handler method to inspect; must not be {@code null}
     * @return the declared request-parameter names in declaration order, empty when the method
     *     declares none; never {@code null}
     */
    private static List<String> requestParameterNamesOf(Method handler) {
        List<String> names = new ArrayList<>();
        for (Parameter parameter : handler.getParameters()) {
            RequestParam binding = parameter.getAnnotation(RequestParam.class);
            if (binding != null) {
                names.add(binding.name());
            }
        }
        return names;
    }

    /**
     * Builds the duplicate refusal the service raises, whose constructor is package-private to it.
     *
     * <p>Assumptions: it is built reflectively because the constructor is package-private in
     * {@code com.carddemo.auth.service}, which is deliberate there -- the conflict is decided in one
     * place -- and this class sits in a different package. Widening the visibility to let a test build
     * one would weaken the property that visibility exists to hold.</p>
     *
     * @return the refusal to throw from the substituted service; never {@code null}
     * @throws ReflectiveOperationException if the constructor cannot be resolved, which a rename would
     *     cause and which should surface here rather than as a silently skipped case
     */
    private static Throwable duplicateUser() throws ReflectiveOperationException {
        var constructor = UserService.DuplicateUserException.class
                .getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UserService.MESSAGE_USER_ID_EXISTS);
    }

    /**
     * The wiring this slice refreshes: the adapter, the shared advice and the deployed filter chain.
     *
     * <p>Assumptions: the chain and the token converter are obtained by CALLING the deployed
     * configuration's own methods rather than by registering that class, and the distinction is the
     * whole reason this type exists. Registering the configuration would also register its decoder
     * factory, which resolves the provider document while the context refreshes and cannot complete
     * against this module's deliberately unreachable test issuer. Calling the two methods that matter
     * leaves every rule, every refusal renderer and the group-to-authority translation exactly as
     * deployed while the one bean that needs a route to a provider is substituted.</p>
     *
     * <p>Assumptions: the two group names handed to the converter factory are the shared kernel's own
     * compiled constants. That factory compares what it is given against those constants and refuses
     * to start on a mismatch, so passing them is what keeps this slice's authority derivation
     * identical to a deployment's rather than merely similar to it.</p>
     *
     * <p>Trade-offs: the MVC infrastructure is enabled here rather than inherited from a started
     * application, so endpoints an actuator contributes at run time are absent from this slice. That
     * is why this class asserts nothing about the operational paths and leaves them to the sibling
     * sign-on slice, which reasons about the one it needs.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SliceWiring {

        /**
         * Supplies the fixed clock the adapter and the shared advice stamp their bodies from.
         *
         * @return a clock pinned to the instant this class fixes; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted user-administration service the adapter delegates to.
         *
         * <p>Assumptions: the service is substituted rather than partially wired, because the
         * transcribed validation chains and the dirty-check semantics it owns are asserted against a
         * mocked repository in the sibling service package. Asserting them twice would give a later
         * change two places to update and one place to forget.</p>
         *
         * @return a substitute for the service, with no stubbing applied; never {@code null}
         */
        @Bean
        UserService users() {
            return mock(UserService.class);
        }

        /**
         * Supplies the adapter under assertion, built over the substituted service.
         *
         * @param users the substituted service to delegate to; must not be {@code null}
         * @param clock the clock the adapter stamps its conflict body from; must not be {@code null}
         * @return the adapter, wired exactly as a deployment wires it; never {@code null}
         */
        @Bean
        UserController userController(UserService users, Clock clock) {
            return new UserController(users, clock);
        }

        /**
         * Supplies the shared error advice, so that the rendered body shape is the deployed one.
         *
         * <p>Assumptions: the advice is registered as a bean of its own type and is discovered because
         * it carries the advice stereotype, which is how the resolver finds it in a running service
         * too. It is registered explicitly because it lives outside this context's component-scan root
         * and reaches a deployment through the shared kernel's auto-configuration, which a slice
         * assembled by hand does not apply.</p>
         *
         * @param clock the clock the advice stamps its bodies from; must not be {@code null}
         * @return the shared advice; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }

        /**
         * Supplies the substituted decoder the resource-server filter resolves a presented token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied; never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed token-to-authentication converter, group names included.
         *
         * @return the converter the deployed configuration builds, carrying the shared
         *     group-to-authority translation; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param converter the deployed token-to-authentication converter; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds; never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, converter, clock);
        }
    }

}
