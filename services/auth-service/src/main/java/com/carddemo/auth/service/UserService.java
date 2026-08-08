package com.carddemo.auth.service;

import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.mapper.UserMapper;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * Serves the four user-administration transactions the reference reached from its administrative menu.
 *
 * <h2>Which reference programs this replaces</h2>
 *
 * <p>This class is the target successor to four reference online programs, and the mapping is
 * one-to-one with its four groups of methods:</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} -- the ten-row keyed browse, which becomes {@link #list};</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} -- the add screen, which becomes {@link #create};</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} -- the update screen, whose load arm becomes {@link #read} and
 *       whose save arm becomes {@link #update};</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} -- the delete screen, whose load arm is the same {@link #read}
 *       and whose destructive arm becomes {@link #delete}.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the two reference programs that both display a row before acting on it --
 * update and delete -- collapse onto ONE read method here rather than two. Their load arms perform the
 * same keyed read of the same dataset and differ only in which screen they populate, and a screen is
 * not this layer's concern. Keeping two would have meant two methods whose bodies were identical and
 * whose names differed only by the operation that happened to follow.
 *
 * <h2>The pool and the row are written together, and the row is the authority</h2>
 *
 * <p>Assumptions: three of the five operations touch two stores -- the relational row and the managed
 * identity pool -- and the ordering rule is the same in all three: the pool is changed in the direction
 * that FAILS CLOSED, and the row is the authority on whether a user exists. Creation provisions the pool
 * account first, because the row cannot be written without the subject the pool mints; update writes the
 * row first and then brings the pool in line, because the row is what the request describes; deletion
 * removes the row first and then the account, because a row without an account is a user who cannot sign
 * on while an account without a row is an identity with no membership -- and the first of those is the
 * safer intermediate state.
 *
 * <p>Trade-offs: there is no distributed transaction across the two stores and none is attempted. The
 * pool is a managed service reached over HTTP and has no enlistable transaction, so the only options
 * were a compensating action or a saga; a saga would introduce observable intermediate states the
 * reference does not have. What is done instead is stated per operation: creation compensates by
 * withdrawing the account it provisioned, and update and deletion leave a repairable, visible
 * inconsistency rather than an invisible one. This mirrors the decision recorded for the batch posting
 * unit of work in the migration plan, where a saga was likewise rejected because it would make partial
 * states observable.
 *
 * <h2>Every failure sentence here is the reference's own</h2>
 *
 * <p>Assumptions: transformation rule T8 carries user-visible strings across character for character,
 * and every sentence this class raises is transcribed from a cited reference line rather than authored.
 * That includes their punctuation: the four validation literals and the not-found, conflict and write
 * failure literals carry NO space before the ellipsis, while the unchanged-body literal DOES, exactly as
 * the programs write them. It also includes one the reference itself got wrong -- the delete failure
 * sentence says "Update", because {@code app/cbl/COUSR03C.cbl} line 332 says "Update" -- which is
 * carried across rather than corrected, because the string is externally observable and correcting it
 * would be an undocumented divergence in output.
 *
 * <h2>The order failures are reported in is decided here, and only here</h2>
 *
 * <p>Assumptions: two of the request bodies this class receives state in their own documentation that the
 * ordered chain reproducing the reference's latching sequence lives in this package -- see
 * {@link com.carddemo.auth.dto.CreateUserRequest} and {@link com.carddemo.auth.dto.UpdateUserRequest},
 * each of which declines the shared kernel's opt-in field-ordering interface because that interface takes
 * body property names and neither operation's sequence is expressible as one. Create's fourth step is a
 * question about stored rows; update's first step tests a path variable. This class holds both sequences
 * whole, which is what those two records point a reader at.
 *
 * <p>Refactoring Rationale: the annotations on those records cannot carry the order on their own. Bean
 * Validation evaluates every constraint on a body in no defined order, so with the annotations alone a
 * body failing two checks produced whichever entry the provider happened to emit first, and the shared
 * advice reported a generic aggregate sentence rather than one of the reference's own. The reference
 * chains are {@code EVALUATE TRUE}, which stops at the first true condition:
 * {@code app/cbl/COUSR01C.cbl} opens its chain at line 117 and every arm ends by sending the screen, so a
 * body with a blank first name and a blank user type produces the first-name sentence at line 120 and
 * never the user-type sentence at line 144. The ordered chains below raise on the FIRST failing field, so
 * exactly one sentence and one field entry reach the caller, which is what the reference produced.
 *
 * <p>Trade-offs: the four blank checks are therefore stated twice -- once as an annotation on the record,
 * where they refuse a body before a handler runs, and once here, where they fix the order. The duplication
 * is accepted because the two do different work: the annotation is the coarse gate that keeps an invalid
 * body out of the service at all, and the chain is what decides which of several simultaneous failures the
 * caller is told about. Removing the annotations would make the records unusable to any other caller;
 * removing the chain would lose the order.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>Assumptions: it performs no authorization check of its own. Every operation it serves requires the
 * administrative authority, and that requirement is enforced once in the filter chain, which matches the
 * two user-administration path patterns and requires the administrative authority on both before any
 * handler runs. Repeating the check here would put the rule in two places, and the copy that went stale
 * would be the one nobody tested.
 *
 * <p>Assumptions: it holds no credential and creates none. The reference update program moves the stored
 * credential back onto the screen at {@code app/cbl/COUSR02C.cbl} line 169 and re-persists a submitted
 * one at line 228; neither has a counterpart here, because {@code auth.users} declares no column that
 * could hold one and the pool performs the comparison. A password for a newly created account is
 * generated by the pool and never passes through this service.
 */
@Service
public class UserService {

    /**
     * The sentence the reference writes when a user identifier is absent.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 132, and reused on the delete
     * path where {@code app/cbl/COUSR03C.cbl} lines 147 and 179 write the identical string. It carries
     * no space before its ellipsis, as those programs write it.
     */
    static final String MESSAGE_USER_ID_REQUIRED = "User ID can NOT be empty...";

    /**
     * The sentence the reference writes when a first name is absent.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 120, whose arm condition is at
     * line 118, and identical to what {@code app/cbl/COUSR02C.cbl} writes at line 188 for its own second
     * arm at line 186. One literal therefore serves both the create and the update chain, as it does in
     * the reference.
     */
    static final String MESSAGE_FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /**
     * The sentence the reference writes when a last name is absent.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 126, whose arm condition is at
     * line 124, and identical to what {@code app/cbl/COUSR02C.cbl} writes at line 194 for its arm at line
     * 192.
     */
    static final String MESSAGE_LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /**
     * The sentence the reference writes when a user type is absent.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 144, whose arm condition is at
     * line 142, and identical to what {@code app/cbl/COUSR02C.cbl} writes at line 206 for its arm at line
     * 204. Both reference arms test only for absence and neither tests membership, which is why the
     * membership sentence below is a separate value rather than a reuse of this one.
     */
    static final String MESSAGE_USER_TYPE_REQUIRED = "User Type can NOT be empty...";

    /**
     * The sentence a user type outside the two admitted values is refused with.
     *
     * <p>Assumptions: this sentence has NO reference line, because the reference never asked the question.
     * Its two arms, {@code app/cbl/COUSR01C.cbl} line 142 and {@code app/cbl/COUSR02C.cbl} line 204, test
     * the field against {@code SPACES OR LOW-VALUES} and nothing further, so a screen could store any
     * single character. Membership was only ever INTERPRETED downstream, by the condition names at
     * {@code app/cpy/COCOM01Y.cpy} lines 27 and 28. The value is written to match the sentence the two
     * request records already publish for their own domain constraint, so the coarse gate and this chain
     * refuse the same value with the same words rather than with two wordings for one rule.
     *
     * <p>Assumptions: it ends in an ellipsis like every reference literal beside it, which is not
     * decoration. The shared advice carries a service's own sentence onto a response only when it passes a
     * provenance gate, and that gate requires the terminator; a sentence without it would be replaced by a
     * generic one and the refusal would say nothing about the user type.
     */
    static final String MESSAGE_USER_TYPE_DOMAIN = "User Type must be A or U...";

    /**
     * The sentence the reference writes when the submitted identifier already has a row.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 263, reproduced with its
     * grammatical error intact -- "exist" rather than "exists". Correcting it would change a string a
     * client may match on and a user may recognise, and an externally observable string is part of the
     * interface whether or not it reads well.
     *
     * <p>Assumptions: the reference folds two distinct file responses onto this one sentence, its
     * duplicate-key arm at line 260 and its duplicate-record arm at line 261 falling through together,
     * so one status with one sentence is what it actually produced rather than a simplification of it.
     *
     * <p>Assumptions: this literal is public where the six beside it are package-private, and the
     * asymmetry follows from where each is rendered. The other six reach a response through the shared
     * advice, which reads the sentence off the failure the service raised, so nothing outside this
     * package needs to name them. This one is rendered by a controller-local handler, because the shared
     * advice's conflict renderer carries a constant sentence from a small set of contention kinds and none
     * of them is a duplicate key. Publishing it gives that handler one value to render rather than a
     * second copy of a string the contract publishes character for character.
     */
    public static final String MESSAGE_USER_ID_EXISTS = "User ID already exist...";

    /**
     * The sentence the reference writes when a row could not be added.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR01C.cbl} line 270, with no space before the
     * ellipsis. It satisfies the shared advice's provenance gate, which is what lets it reach the 500
     * body rather than being replaced by a generic sentence.
     */
    static final String MESSAGE_UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * The sentence the reference writes when no row carries the identifier.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR02C.cbl} lines 342 and 379, where the update
     * program writes it on both its load arm and its save arm, and identical to what
     * {@code app/cbl/COUSR03C.cbl} writes at lines 289 and 325. One literal therefore serves the read,
     * update and delete paths, as it does in the reference.
     */
    static final String MESSAGE_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * The sentence the reference writes when a change could not be written.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR02C.cbl} line 386. It is ALSO the sentence
     * the delete program writes for a failed delete, at {@code app/cbl/COUSR03C.cbl} line 332 -- naming
     * "Update" on a delete, which reads as a copy of the update program's handler that was never
     * reworded for its new home. It is carried across as it stands and used on both paths, because the
     * string is externally observable; the wrong verb is documented rather than fixed.
     */
    static final String MESSAGE_UNABLE_TO_UPDATE = "Unable to Update User...";

    /**
     * The sentence the reference writes when an update would change nothing.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR02C.cbl} line 239. Unlike the other six
     * literals here it DOES carry a space before its ellipsis, and the difference is reproduced because
     * the program writes it that way.
     *
     * <p>Assumptions: the reference writes this one in RED, at line 241, where the same message field is
     * written neutral at line 338 and green at line 371 in the same program. The colour is what settles
     * it as a rejection rather than as advice, which is why this sentence is raised as a refusal and not
     * returned on a success body.
     */
    static final String MESSAGE_MODIFY_TO_UPDATE = "Please modify to update ...";

    /**
     * The sentence the reference writes when the user file could not be read.
     *
     * <p>Assumptions: transcribed from {@code app/cbl/COUSR00C.cbl}, which writes it at lines 610, 644
     * and 678 -- once for each of the three read paths the browse uses -- with no space before the
     * ellipsis. One literal for three paths is the reference's own arrangement.
     */
    static final String MESSAGE_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    // WHY : Assumptions: no width or truncation rule governs any literal above, and the absence is stated
    //       rather than left to be rediscovered. The reference message field is PIC X(78) in all four user
    //       maps -- app/cpy-bms/COUSR00.CPY line 372, COUSR01.CPY line 90, COUSR02.CPY line 90 and
    //       COUSR03.CPY line 84 -- while the published contract caps a field entry's message at 75. The
    //       longest sentence this whole context can produce is the 44-character bottom-of-page guard at
    //       app/cbl/COUSR00C.cbl line 273, so neither bound is reachable and a truncation path would be
    //       code no input could execute.
    // WHY : Assumptions: the reference's colour byte is its severity channel, and that is what settles
    //       which HTTP status class each sentence above belongs to. One field, ERRMSGC, carries all three
    //       severities in app/cbl/COUSR02C.cbl alone: red at line 241 for the unchanged-body refusal,
    //       neutral at line 338 for the save prompt, and green at line 371 for the completed update. Red
    //       maps to a 4xx, green to a 2xx, and neutral to neither -- a neutral sentence is advice about
    //       what to press next, so the two neutral literals at COUSR02C lines 336 to 337 and
    //       app/cbl/COUSR03C.cbl line 283 have no constant here at all and no server state behind them.
    // WHY : Assumptions: the shared unmapped-key sentence has no counterpart here either. Every one of the
    //       four user programs writes CCDA-MSG-INVALID-KEY from app/cpy/CSMSG01Y.cpy lines 20 to 21 when
    //       the terminal sent an attention identifier the program does not handle -- COUSR00C line 135,
    //       COUSR01C line 101, COUSR02C line 129 and COUSR03C line 128. An unhandled attention identifier
    //       is a 3270 concept: over HTTP an unsupported action is an unrouted method or path, which the
    //       framework answers before any handler runs. Carrying the sentence would mean inventing a
    //       request shape that could produce it.

    /**
     * The number of rows one page of the list carries.
     *
     * <p>Assumptions: ten is the reference screen's own row count, and three independent places in the
     * baseline agree on it. The scratch table is declared {@code OCCURS 10 TIMES} at
     * {@code app/cbl/COUSR00C.cbl} line 57; both fill loops are bounded by the same ten, the forward one
     * running until its index reaches eleven at lines 300 to 306 and the backward one counting down from
     * ten at lines 354 to 360; and {@code app/cpy-bms/COUSR00.CPY} declares ten identical row groups of
     * which the tenth is the last, its fields at lines 348, 354, 360 and 366. The committed contract
     * declares the same number as {@code maxItems: 10} on the page's item array and states that the size
     * is set by the contract rather than chosen by the caller, which is why no page-size parameter exists.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * The name every cursor this class issues is sealed under.
     *
     * <p>Assumptions: the binding authenticates the query a token was issued for, so a token minted for
     * this listing cannot be presented to another service's paged read even by a caller holding both.
     * The value names the listing rather than the table, because the binding is about the QUERY and a
     * second listing over the same table would be a different query.
     */
    private static final String CURSOR_QUERY_NAME = "auth.users.list";

    /**
     * The scope a forward-paging token is sealed under.
     *
     * <p>Assumptions: the direction is folded into the seal, not merely checked against a parameter, and
     * the committed contract states that property of its cursor: "It is bound to the DIRECTION it was
     * minted for as well, so replaying the previous-page position with direction next cannot silently
     * answer with the wrong page." A token presented with the other direction fails to open and the
     * request is refused, which is what makes ONE cursor parameter as safe as two named ones while
     * removing the both-supplied and mismatched-pair cases that two parameters would have to define
     * errors for.
     */
    private static final String CURSOR_SCOPE_FORWARD = "direction:next";

    /** The scope a backward-paging token is sealed under, the counterpart of the forward scope. */
    private static final String CURSOR_SCOPE_BACKWARD = "direction:previous";

    /**
     * The parameter value that selects a backward page.
     *
     * <p>Assumptions: the two admitted values are {@code next} and {@code previous} in lower case,
     * because that is the enumeration the committed contract declares for the direction parameter. Only
     * the backward value is named as a constant, since the forward one is the default and is selected by
     * everything that is not this value.
     */
    private static final String DIRECTION_PREVIOUS = "previous";

    /** The key an unopenable cursor refusal is attributed to. */
    private static final String FIELD_CURSOR = "cursor";

    // WHY : Assumptions: each key below is the request-body or path property name that corresponds to the
    //       field the reference homed its cursor to beside the message, which is what makes the mapping
    //       auditable rather than chosen. The create chain homes to FNAMEL, LNAMEL, USERIDL and USRTYPEL at
    //       app/cbl/COUSR01C.cbl lines 122, 128, 134 and 146; the update chain homes to USRIDINL, FNAMEL,
    //       LNAMEL and USRTYPEL at app/cbl/COUSR02C.cbl lines 184, 190, 196 and 208; the delete chain homes
    //       to USRIDINL at app/cbl/COUSR03C.cbl lines 149 and 181. The published contract names the same
    //       four of its six keys for this context, so a client marking a control finds the name it expects.
    //       The keys are NOT derived from the templated highlight copybook app/cpy/CSSETATY.cpy: no
    //       program in this domain copies it, each of the four copying exactly eight books of which that is
    //       not one, and each compares the attention identifier against DFHAID constants directly.

    /** The key a refusal blamed on the identifier carries. */
    private static final String FIELD_USER_ID = "userId";

    /** The key a refusal blamed on the first name carries. */
    private static final String FIELD_FIRST_NAME = "firstName";

    /** The key a refusal blamed on the last name carries. */
    private static final String FIELD_LAST_NAME = "lastName";

    /** The key a refusal blamed on the user type carries. */
    private static final String FIELD_USER_TYPE = "userType";

    // WHY : Assumptions: the two admitted values come from app/cpy/COCOM01Y.cpy lines 27 and 28, where the
    //       shared communication area names them as QUOTED single characters -- unlike the program-context
    //       values two lines below at 30 and 31, which are bare numerics. They are NOT taken from
    //       app/cpy/CSUSR01Y.cpy line 22, which declares the stored field as PIC X(01) and defines no
    //       condition name at all; that copybook carries the width, not the domain.

    /** The stored user type that carries administrative authority. */
    private static final String USER_TYPE_ADMIN = "A";

    /** The stored user type that carries ordinary authority. */
    private static final String USER_TYPE_USER = "U";

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** The rows this service reads and writes, being the relational successor to the reference file. */
    private final UserRepository users;

    /** The anti-corruption layer between the stored row and the published shapes. */
    private final UserMapper mapper;

    /** The managed-identity side of a user, which every write here keeps in step with the row. */
    private final CognitoUserProvisioningService provisioning;

    // WHY : Assumptions: the sealer is injected rather than constructed, because it holds signing key
    //       material that the shared kernel reads from configuration once per application context. A
    //       locally built one would need that material passed through this class, and two sealers with
    //       different keys would issue tokens neither could open.
    private final CursorToken cursorToken;

    /**
     * Builds the service over its four collaborators.
     *
     * @param users the repository over {@code auth.users}; must not be {@code null}
     * @param mapper the mapper between the stored row and the published shapes; must not be {@code null}
     * @param provisioning the managed-identity side of a user row; must not be {@code null}
     * @param cursorToken the sealer the paged read issues and redeems its cursors with; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public UserService(UserRepository users, UserMapper mapper,
            CognitoUserProvisioningService provisioning, CursorToken cursorToken) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken must not be null");
    }

    /**
     * Reads one page of users positioned by key, in the direction the caller asked for.
     *
     * <p>Purpose: this is the migrated form of {@code app/cbl/COUSR00C.cbl}, whose browse filled a
     * ten-row screen and reported whether another row followed.
     *
     * <p>Assumptions: paging is by KEY and not by ordinal, and the reference had already made that
     * choice. It stored the page's last key and first key as real key values, at
     * {@code app/cbl/COUSR00C.cbl} lines 435 and 389, and held no count of rows consumed anywhere; its
     * screen's page-number field is display-only, moved to the screen at lines 327 and 376 and never
     * used to position a read. So this is a one-for-one substitution rather than an approximation. It
     * also matters for correctness: under concurrent insertion the number of rows preceding a position
     * changes between requests, so an offset-paged reader silently skips some rows and shows others
     * twice, while a key already read keeps its place in the ordering whatever is inserted around it.
     *
     * <p>Assumptions: both cursors are EXCLUSIVE -- the row a cursor names is not returned again -- which
     * the reference established with an extra read issued before each page was filled: a further forward
     * read at line 289 and the mirrored backward read at line 343, each stepping past the cursor record
     * itself.
     *
     * <p>Assumptions: reaching an end of the file is a SUCCESS and never a failure, which is what the
     * reference's five boundary messages amount to. A page that lands on the last row returns with no
     * further page available; a move that could not happen at all returns the boundary page. Neither is
     * an error, and none of the five reference sentences is returned as a message here -- the browser
     * client composes them from the envelope, which is where every user-visible string of this migration
     * lives.
     *
     * <p>Assumptions: those five sentences are TWO classes and the client needs both, which is why the
     * envelope reports the direction's availability rather than a single exhausted flag. Two are GUARDS,
     * written when the caller asked to move past a boundary already known to have been reached:
     * {@code app/cbl/COUSR00C.cbl} tests the page number at line 248 and writes the top guard at line 251,
     * and tests the next-page flag at line 270 and writes the bottom guard at line 273 -- in each case
     * without touching the file. Three are ARRIVALS, written when the browse itself struck a boundary while
     * walking: the start-browse not-found arm at line 600 writes line 603, the forward end-of-file arm at
     * line 634 writes line 637, and the backward end-of-file arm at line 668 writes line 671. The last two
     * are distinct sentences and not one repeated, and the backward arrival differs again from the
     * start-browse arrival, so all five are carried separately by whatever renders them. Every one of them
     * accompanied a rendered screen rather than an abend, so every one of them is a success here.
     *
     * <p>Assumptions: a cursor naming a row that has since been deleted is repositioned rather than
     * refused, and the reference behaves the same way. Its start-browse arm at line 600 keys on the
     * not-found response specifically -- the response for a key that is not there -- and still writes a
     * sentence and sends the screen. A strict inequality against a key no row carries positions the window
     * at the next surviving key in the direction asked for, so the caller receives a page rather than a
     * refusal, and an opening sentinel that no row can match returns the first page rather than nothing.
     *
     * <p>Assumptions: one row more than a page is read, and the surplus is what settles whether a
     * further page follows. That is the reference's own device -- it discovered a further row rather
     * than counting the file -- and it is why the query limit is the page size plus one.
     *
     * @param cursor the sealed position to continue from, or {@code null} for the opening page
     * @param direction {@code previous} to read backwards, or {@code null} or {@code next} to read
     *     forwards; meaningful only alongside a cursor
     * @param subject the authenticated caller the issued cursors are sealed against, so a page issued to
     *     one administrator cannot be replayed by another; must not be {@code null} or blank
     * @return one page of at most ten summaries in ascending identifier order, with the two boundary
     *     cursors and the forward availability indicator; never {@code null}
     * @throws NullPointerException if {@code subject} is {@code null}
     * @throws ClientInputException if the supplied cursor cannot be opened -- malformed, altered,
     *     expired, or issued for a different query, subject or direction -- carrying the cursor's own key
     * @throws IllegalStateException if the store could not be read, carrying the reference sentence for a
     *     failed lookup
     */
    // WHY : Trade-offs: this read runs under the datasource's default snapshot isolation, where the
    //       reference file was defined READINTEG(UNCOMMITTED) at app/csd/CARDDEMO.CSD line 90. The two are
    //       not equivalent and the difference is visible: an uncommitted-read browse could return a record
    //       image a concurrent task had written and not yet committed, so a list could momentarily show a
    //       half-applied name change and then show it undone. A committed-read snapshot cannot, so that
    //       flicker disappears. What it costs is that a page is answered from the snapshot taken when this
    //       transaction began, so a row committed by another request one instant later is absent from a
    //       page a caller might reasonably have expected to contain it; the caller sees it on its next page
    //       request. Neither behaviour is expressible in the other, so this is recorded as a divergence.
    // WHY : Refactoring Rationale: the reference serialised every task that touched this file, and nothing
    //       here reproduces that. app/csd/CARDDEMO.CSD line 91 defines STRINGS(1), which permits exactly one
    //       concurrent VSAM string against the dataset, so a second task wanting the file waited for the
    //       first whatever either was doing. A connection-pooled relational datasource has no equivalent
    //       single-string ceiling, so concurrent list, read and write requests proceed together. The
    //       serialisation carried no business meaning to preserve -- it was a limit of the access method,
    //       not a rule about users -- so removing it changes how many requests run at once and changes no
    //       request's answer.
    @Transactional(readOnly = true)
    public PageResponse<UserSummary> list(String cursor, String direction, String subject) {

        Objects.requireNonNull(subject, "subject must not be null");

        boolean backward = DIRECTION_PREVIOUS.equals(direction);
        String binding = cursorBinding(subject, backward);

        // WHY : Assumptions: a direction supplied WITHOUT a cursor is not refused here, and the contract
        //       says why: the direction defaults to forward and "with no cursor supplied returns the
        //       first page". A backward direction with no cursor therefore reads the first page rather
        //       than failing, which is the same answer the reference gives when the backward key it holds
        //       is its low-value sentinel.
        String cursorKey = cursor == null || cursor.isBlank() ? null : openCursor(binding, cursor);

        // WHY : Assumptions: the sentinel for an opening forward page is the empty string and for an
        //       opening backward page is a value ordering above every stored key. The identifier column
        //       is CHAR(8) over the reference's own character set, so the empty string orders below every
        //       stored key and a run of the highest admissible character orders above every one. The
        //       reference uses the same device in the other representation, moving low values into its
        //       forward key and high values into its backward key.
        String position = cursorKey != null ? cursorKey
                : backward ? BACKWARD_OPENING_SENTINEL : FORWARD_OPENING_SENTINEL;

        List<User> window = readWindow(position, backward);

        return page(window, backward, subject);
    }

    /**
     * Reads the whole of one user row.
     *
     * <p>Purpose: this is the load arm of both {@code app/cbl/COUSR02C.cbl} and
     * {@code app/cbl/COUSR03C.cbl}, which perform the same keyed read and differ only in the screen they
     * populate.
     *
     * @param userId the identifier of the row to read, as it arrived in the request path; must not be
     *     {@code null}
     * @return the stored row, including the subject reference the list projection omits; never
     *     {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws ClientInputException if the identifier is blank, carrying the reference sentence for an
     *     absent identifier
     * @throws NoSuchElementException if no row carries the identifier, carrying the reference sentence
     *     for a row that was not found
     * @throws IllegalStateException if the store could not be read, carrying the reference sentence for a
     *     failed lookup
     */
    @Transactional(readOnly = true)
    public UserResponse read(String userId) {
        return this.mapper.toResponse(require(userId));
    }

    /**
     * Creates one user row together with the managed-identity account it authenticates as.
     *
     * <p>Purpose: this is the migrated form of {@code app/cbl/COUSR01C.cbl}.
     *
     * <p>Assumptions: the pool account is provisioned BEFORE the row is written, and the order is forced
     * rather than chosen. The row's subject column is not nullable and the subject is minted by the pool,
     * so there is no value to write the row with until the account exists. The window that creates is
     * closed by the compensating withdrawal below rather than left open.
     *
     * <p>Refactoring Rationale: the duplicate identifier is detected by the PRIMARY KEY rather than by a
     * read-then-insert, and the difference is not stylistic. A check followed by an insert has a window
     * between them in which a concurrent request can insert the same key, so one of the two would
     * silently overwrite or fail with an untranslated constraint error. The reference had no such window
     * because its file manager decided the duplicate itself -- its duplicate-key and duplicate-record
     * arms at lines 260 and 261 are responses to the write, not to a prior read -- so keying on the
     * constraint reproduces the reference's own arrangement.
     *
     * <p>Trade-offs: an existence probe still runs before the pool account is provisioned, which
     * duplicates the constraint's later verdict for the common case. It is kept because without it a
     * caller repeating a create for an existing identifier would provision a pool account, fail on the
     * constraint, and rely on the compensating withdrawal to remove it -- three provider calls and a
     * transient second identity for a request that was always going to be refused. The probe makes the
     * ordinary refusal cost one indexed read; the constraint remains the authority for the race.
     *
     * <p>Assumptions: the ordered chain runs before anything else, and its position is what makes the
     * reported order the reference's. {@code app/cbl/COUSR01C.cbl} evaluates its arms at lines 118, 124,
     * 130 and 142 and only then, guarded at line 153, moves the fields into the record and writes it at
     * line 159. Checking after the probe or after provisioning would report a duplicate or a provider fault
     * for a body the reference would have refused on a blank field.
     *
     * @param request the validated new row's values; must not be {@code null}
     * @return the row as stored, as a subsequent read would return it, including the subject it was bound
     *     to; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if a submitted field is blank or the user type is outside the two
     *     admitted values, carrying the FIRST failing field's own reference sentence and that field's key
     * @throws DuplicateUserException if a row already carries the submitted identifier, carrying the
     *     reference sentence for that condition
     * @throws IllegalStateException if the row could not be written or the pool account could not be
     *     created, carrying the reference sentence for a failed add
     */
    // WHY : Trade-offs: this boundary, and the two other write boundaries in this class, are a decision
    //       taken here rather than a constraint carried over. None of the four user programs issues
    //       EXEC CICS SYNCPOINT at all -- a count over each of app/cbl/COUSR00C.cbl, COUSR01C.cbl,
    //       COUSR02C.cbl and COUSR03C.cbl returns zero, where app/cbl/COACTUPC.cbl returns two and
    //       app/cbl/COCRDUPC.cbl one -- so there is no baseline commit scope for these operations to
    //       reproduce. The reference got its unit of work implicitly, by ending the CICS task, which
    //       app/csd/CARDDEMO.CSD backs with ACTION(BACKOUT) on each of the four transaction definitions at
    //       lines 455, 465, 475 and 485. Declaring one transaction per public write reproduces that
    //       all-or-nothing shape explicitly. What it costs is that a caller can no longer observe a
    //       partially applied write -- which the reference could not offer either, so nothing observable is
    //       given up.
    @Transactional
    public UserResponse create(CreateUserRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        checkCreateOrder(request);

        String userId = request.userId();

        if (exists(userId)) {
            LOG.info("event=auth.user.create-refused reason=duplicate userId={}", userId);
            throw new DuplicateUserException(MESSAGE_USER_ID_EXISTS);
        }

        UUID subject = provision(request);

        try {
            User stored = this.users.save(this.mapper.toEntity(request, subject));
            LOG.info("event=auth.user.created userId={}", userId);
            return this.mapper.toResponse(stored);

            // WHY : Assumptions: the integrity violation is caught SEPARATELY from other store failures
            //       because it is the race the probe above cannot close, and its answer is a conflict
            //       rather than a fault. It is the only condition on this path that is the caller's to
            //       act on, and the action is to choose another identifier.
        } catch (DataIntegrityViolationException duplicate) {
            withdrawQuietly(userId, "duplicate-on-insert");
            LOG.info("event=auth.user.create-refused reason=duplicate-race userId={}", userId);
            throw new DuplicateUserException(MESSAGE_USER_ID_EXISTS);

            // WHY : Refactoring Rationale: the compensating withdrawal is what makes provisioning before
            //       writing safe. Without it a failed insert would leave a pool account that can
            //       authenticate and has no row, so a later create for the same identifier would fail on
            //       the pool's own duplicate-username condition -- which this service deliberately does
            //       NOT translate into a client-facing conflict, because the authority for a duplicate
            //       identifier is the primary key on auth.users. The identifier would have become
            //       permanently unusable through a path no operator could see.
        } catch (DataAccessException unwritable) {
            withdrawQuietly(userId, "insert-failed");
            throw unableTo(MESSAGE_UNABLE_TO_ADD, "insert-" + unwritable.getClass().getSimpleName());
        }
    }

    /**
     * Applies an update to one user row and brings its pool account in line.
     *
     * <p>Purpose: this is the save arm of {@code app/cbl/COUSR02C.cbl}, its
     * {@code UPDATE-USER-INFO} paragraph at line 177.
     *
     * <p>Assumptions: a body matching the stored row in every field is REFUSED, and that is the
     * reference's behaviour rather than an addition. It compared each submitted field against the record
     * at lines 219 to 234 and, where nothing differed, took the else branch at line 237 and wrote
     * {@code 'Please modify to update ...'} in red at lines 239 to 241. The colour settles it as a
     * rejection: the same field is written neutral at line 338 and green at line 371 in the same
     * program.
     *
     * <p>Refactoring Rationale: the comparison is performed HERE and not in the mapper, and the two
     * places are not in conflict. The mapper assigns unconditionally and records why: a persistence
     * provider compares against its own loaded snapshot, so assigning an identical value issues no
     * statement and re-implementing the reference's four arms there would duplicate a decision already
     * made one layer down. What the provider cannot do is produce the reference's 400, because
     * suppressing a statement is invisible to a caller. The comparison therefore exists once, at the
     * layer that owns the outcome, and the mapper still owns the assignment.
     *
     * <p>Assumptions: the comparison is on logical values rather than stored images. Each reference arm
     * compares a space-padded screen field against a space-padded record field, whereas the values here
     * are ordinary strings against a variable-width column and a one-character column, and the mapper
     * has already stripped the padding a fixed-width column carries. Comparing images would report a
     * change whenever padding differed.
     *
     * <p>Refactoring Rationale: the reference reached this behaviour from TWO keys and only one of them is
     * reproduced. {@code app/cbl/COUSR02C.cbl} evaluates the attention identifier at line 108 and its
     * save key arm at lines 122 to 123 performs the update, which is the arm this method is. Its back key
     * arm at line 111 ALSO performs it, at line 112, before choosing where to return at line 119 -- so a
     * user pressing back to leave the screen silently saved whatever was on it. Navigation is a client
     * concern here and issues no call, so leaving the screen cannot write. Reproducing it would mean
     * writing on a request that asked to go somewhere, which no HTTP method could honestly express.
     *
     * <p>Refactoring Rationale: refusing an unchanged body is DIRTY DETECTION and not concurrency control,
     * and the two are close enough in shape to be worth separating explicitly. Concurrency control asks
     * whether the row moved under the caller, and answers a conflict; this asks whether the caller asked
     * for anything, and answers a rejected request. The account-update program in the same baseline does
     * the other thing and says so in its own words at {@code app/cbl/COACTUPC.cbl} lines 521 to 522, whose
     * literal tells the user the record was changed by someone else. Nothing in this class produces that
     * outcome, because none of the four user programs detects it.
     *
     * @param userId the identifier of the row to change, from the request path; must not be {@code null}
     * @param request the validated values the row is to hold; must not be {@code null}
     * @return the row as stored after the change; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ClientInputException if the identifier is blank, if a submitted field is blank, if the user
     *     type is outside the two admitted values, or if every submitted value already matches the stored
     *     row -- carrying, in each case, the reference sentence for that condition, and a field key for all
     *     but the last
     * @throws NoSuchElementException if no row carries the identifier
     * @throws IllegalStateException if the change could not be written or the pool could not be brought
     *     in line, carrying the reference sentence for a failed update
     */
    @Transactional
    public UserResponse update(String userId, UpdateUserRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        // WHY : Assumptions: the whole chain runs BEFORE the row is read, which is the reference's own
        //       sequence and not merely a convenient one. app/cbl/COUSR02C.cbl evaluates all five arms of
        //       UPDATE-USER-INFO at lines 180 to 213 and only then, guarded at line 215, moves the
        //       identifier and performs its keyed read at lines 216 and 217. Reading first would answer a
        //       body with a blank first name and an unknown identifier with the not-found sentence, where
        //       the reference answers with the first-name sentence.
        checkUpdateOrder(userId, request);

        // WHY : Refactoring Rationale: this is a plain read-modify-write with no version property and no
        //       lock hint, where the account-update program in the same baseline hand-rolls a before-image
        //       comparison -- a change flag at app/cbl/COACTUPC.cbl line 168, the condition name and its
        //       literal at lines 521 to 522, and an X-over-9 REDEFINES snapshot block opening at line 669
        //       whose identifier pair is at lines 671 to 673. None of the four user programs contains any
        //       equivalent, so there is no baseline conflict detection to reproduce and adding one would
        //       introduce a refusal the reference never produced.
        // WHY : Assumptions: the reason the user programs need no before-image is that their lock never
        //       spans client think-time. app/cbl/COUSR02C.cbl reads with the UPDATE option at line 328
        //       inside the verb at lines 322 to 331 and rewrites at lines 360 to 366 -- and the rewrite
        //       carries neither RIDFLD nor KEYLENGTH, because the file manager reuses the record the
        //       immediately preceding read-for-update identified, within one task. app/cbl/COUSR03C.cbl
        //       does the same, reading with UPDATE at line 275 and deleting with a verb at lines 307 to
        //       311 that names only the dataset. app/csd/CARDDEMO.CSD line 93 defines UPDATEMODEL(LOCKING)
        //       and line 89 RLSACCESS(NO), so the lock is pessimistic and held entirely inside that one
        //       task. A read and a write inside one transaction here occupy the same span.
        User stored = require(userId);
        String previousUserType = stored.getUserType();

        if (matchesStoredRow(stored, request)) {
            LOG.info("event=auth.user.update-refused reason=unchanged userId={}",
                    stored.getUserId());
            // WHY : Assumptions: the refusal names NO field, and the contract states the same: no single
            //       field is at fault when every one of them matches. The shared advice keys an
            //       unattributed entry to the request as a whole, which is what lets a form show the
            //       sentence without marking a control that is not wrong.
            throw new ClientInputException(ApiError.CODE_VALIDATION, MESSAGE_MODIFY_TO_UPDATE);
        }

        this.mapper.applyUpdate(request, stored);

        try {
            User written = this.users.save(stored);

            // WHY : Assumptions: the pool is brought in line AFTER the row is written and inside the same
            //       transaction, so a provider failure rolls the row back and leaves the two stores
            //       agreeing. That is the opposite ordering from creation, and deliberately: creation
            //       cannot write the row first because it needs the subject the pool mints, whereas here
            //       the row already exists and the pool holds only a projection of it.
            this.provisioning.synchronise(written.getUserId(), written.getFirstName(),
                    written.getLastName(), previousUserType, written.getUserType());

            LOG.info("event=auth.user.updated userId={}", written.getUserId());
            return this.mapper.toResponse(written);

        } catch (DataAccessException unwritable) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "update-" + unwritable.getClass().getSimpleName());

            // WHY : Assumptions: a provider failure here is reported with the SAME sentence as a store
            //       failure, because from the caller's position they are one outcome: the change it asked
            //       for did not take effect. The internal reason distinguishes them for an operator, and
            //       the transaction rolls the row back so the outcome is truthful.
        } catch (SdkException providerFault) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "update-provider-" + providerFault.getClass().getSimpleName());
        }
    }

    /**
     * Deletes one user row and the managed-identity account behind it.
     *
     * <p>Purpose: this is the destructive arm of {@code app/cbl/COUSR03C.cbl}.
     *
     * <p>Assumptions: the row is removed BEFORE the pool account, and the order is chosen for the state
     * an interruption leaves behind. A row removed with its account still present is an identity that can
     * authenticate and holds no membership in this context, so it is refused at every guarded route; an
     * account removed with its row still present is a row whose user cannot sign on at all and which no
     * later operation can repair without reprovisioning. The first is the recoverable direction.
     *
     * <p>Assumptions: an absent pool account does not fail the deletion. The row is this context's
     * authority on whether the user exists, so a row found with no account behind it must still be
     * deletable -- the withdrawal treats absence as success for exactly that reason.
     *
     * <p>Assumptions: the confirmation the reference required as a second keystroke is not checked here.
     * It is a property of the request rather than of the row, so it is enforced at the adapter where the
     * parameter arrives; this method deletes what it is told to delete.
     *
     * <p>Refactoring Rationale: that the confirmation is REQUIRED at all, rather than assumed, preserves a
     * property the delete program actually has and the update program does not. In
     * {@code app/cbl/COUSR03C.cbl} the destructive paragraph is reached from exactly one key: the arm at
     * lines 121 to 122 performs it, and the back key arm at lines 111 to 118 goes straight to choosing
     * where to return, with no destructive call anywhere in it. So navigating away from the delete screen
     * deleted nothing, where navigating away from the update screen saved -- the save-on-navigate defect is
     * that program's alone. The migrated shape keeps the delete side's property by making the confirmation
     * a required parameter, so a request that does not carry it removes nothing.
     *
     * @param userId the identifier of the row to delete, from the request path; must not be {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws ClientInputException if the identifier is blank
     * @throws NoSuchElementException if no row carries the identifier
     * @throws IllegalStateException if the row could not be deleted or the pool account could not be
     *     removed, carrying the reference sentence the delete program writes for that condition -- which
     *     names "Update", as that program writes it
     */
    @Transactional
    public void delete(String userId) {

        User stored = require(userId);

        try {
            this.users.delete(stored);
            this.provisioning.withdraw(stored.getUserId());
            LOG.warn("event=auth.user.deleted userId={}", stored.getUserId());

            // WHY : Refactoring Rationale: the sentence a failed delete reports names "Update", and that is
            //       carried across rather than reworded. app/cbl/COUSR03C.cbl line 332 writes
            //       'Unable to Update User...' on the delete path, and the wording's own home is
            //       app/cbl/COUSR02C.cbl lines 386 to 387, where the same literal reports a failed rewrite.
            //       The two programs are clones: their COPY statements sit on byte-identical line numbers
            //       49, 60, 62, 63, 64, 65, 67 and 68, differing only in which mapset line 60 names, and
            //       both carry live diagnostic DISPLAY statements in the same two positions -- COUSR03C
            //       lines 294 and 330 against COUSR02C lines 347 and 384. The delete program's handler was
            //       copied and never reworded for its new home. The baseline emits the update wording on
            //       the delete path; the Java encodes the same string; the divergence from intent is
            //       documented, not fixed.
            // WHY : Trade-offs: carrying a sentence that names the wrong operation costs clarity for
            //       whoever reads it, and the alternative -- a delete-specific wording -- costs parity on a
            //       string the contract publishes verbatim under transformation rule T8 and that a client
            //       may match on. Parity wins because the wording is externally observable and the
            //       confusion is not, being confined to one failure path whose internal reason below names
            //       the operation exactly.
        } catch (DataAccessException undeletable) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "delete-" + undeletable.getClass().getSimpleName());

        } catch (SdkException providerFault) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "delete-provider-" + providerFault.getClass().getSimpleName());
        }
    }

    /**
     * Refuses a create body at its FIRST failing field, in the order the add screen checked them.
     *
     * <p>Purpose: this is the {@code EVALUATE TRUE} chain of {@code app/cbl/COUSR01C.cbl}, which opens at
     * line 117 and whose every arm ends by sending the screen, so the first true condition is the only one
     * a user ever heard about.
     *
     * <p>Assumptions: the order is the add MAP's field order and not the record's, and the two genuinely
     * differ inside that one program. Validation walks the screen: {@code app/cpy-bms/COUSR01.CPY} declares
     * {@code FNAMEI} first at line 60, then {@code LNAMEI} at 66, {@code USERIDI} at 72 and
     * {@code USRTYPEI} at 84, and the arms at lines 118, 124, 130 and 142 follow exactly that sequence.
     * Storage walks the copybook: the guarded block at lines 153 to 160 moves the identifier first, at line
     * 154, because {@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-ID} first at line 18. Both orders
     * survive here rather than being reconciled -- the request record declares screen order and drives this
     * chain, while the response record and the entity declare record order -- because collapsing them would
     * change which sentence a caller is shown first.
     *
     * <p>Refactoring Rationale: the reference's fourth arm has no counterpart in this chain. Lines 136 to
     * 140 test the credential field and write the sentence at line 138, and line 157 then moves that field
     * into {@code SEC-USR-PWD}, declared at {@code app/cpy/CSUSR01Y.cpy} line 21 at offset 48 of its
     * 80-byte record. The migrated schema declares no column that could hold it and this service holds no
     * credential, so there is no field for the arm to test. The omission is recorded here rather than left
     * as a silently shorter chain: a reader comparing the two sequences would otherwise find four arms
     * where the reference has five and have no way to tell which was dropped or why.
     *
     * @param request the body to check, already past the record's own coarse constraints
     * @throws ClientInputException naming the first failing field, carrying that field's reference
     *     sentence, and marking the field blank or not-acceptable according to which check refused it
     */
    private void checkCreateOrder(CreateUserRequest request) {
        requireSupplied(FIELD_FIRST_NAME, request.firstName(), MESSAGE_FIRST_NAME_REQUIRED);
        requireSupplied(FIELD_LAST_NAME, request.lastName(), MESSAGE_LAST_NAME_REQUIRED);
        requireSupplied(FIELD_USER_ID, request.userId(), MESSAGE_USER_ID_REQUIRED);
        requireSupplied(FIELD_USER_TYPE, request.userType(), MESSAGE_USER_TYPE_REQUIRED);
        requireKnownUserType(request.userType());
    }

    /**
     * Refuses an update at its FIRST failing field, in the order the update screen checked them.
     *
     * <p>Purpose: this is the {@code UPDATE-USER-INFO} chain of {@code app/cbl/COUSR02C.cbl}, whose
     * {@code EVALUATE TRUE} opens at line 179 with arms at lines 180, 186, 192, 198 and 204.
     *
     * <p>Assumptions: the identifier is checked FIRST and the reference is why. Its first arm at line 180
     * tests the identifier and writes the sentence at line 182, ahead of the two names and the type -- the
     * opposite of the add screen's order, where the identifier is third. On this operation the identifier
     * arrives as a path variable rather than as a body property, which is precisely why the request record
     * could not declare this sequence through the shared kernel's field-ordering interface and why the whole
     * chain is here.
     *
     * <p>Assumptions: the identifier is immutable selection context, so it is checked for presence and
     * never for change. The reference reaches this screen with the identifier already settled -- either typed
     * on the screen or carried in from the list's selection column, which
     * {@code app/cbl/COUSR00C.cbl} moves at lines 101 to 102 of its own caller -- and its dirty-check block
     * at lines 219 to 234 compares the two names, the credential and the type, never the identifier.
     *
     * <p>Refactoring Rationale: the fourth reference arm, lines 198 to 202 with its sentence at line 200,
     * tests the credential and has no counterpart, for the same reason the add chain's fourth arm has none:
     * the field it tests does not exist in this context. The reference then re-displayed the stored
     * credential in clear at line 169, inside the refill block at lines 166 to 172, and re-persisted a
     * submitted one through the dirty-check arm at lines 227 to 229. Neither has a counterpart either.
     *
     * @param userId the identifier as it arrived in the request path
     * @param request the body to check, already past the record's own coarse constraints
     * @throws ClientInputException naming the first failing field, carrying that field's reference
     *     sentence, and marking the field blank or not-acceptable according to which check refused it
     */
    private void checkUpdateOrder(String userId, UpdateUserRequest request) {
        requireSupplied(FIELD_USER_ID, userId, MESSAGE_USER_ID_REQUIRED);
        requireSupplied(FIELD_FIRST_NAME, request.firstName(), MESSAGE_FIRST_NAME_REQUIRED);
        requireSupplied(FIELD_LAST_NAME, request.lastName(), MESSAGE_LAST_NAME_REQUIRED);
        requireSupplied(FIELD_USER_TYPE, request.userType(), MESSAGE_USER_TYPE_REQUIRED);
        requireKnownUserType(request.userType());
    }

    /**
     * Refuses one field that the reference would have treated as never filled in.
     *
     * <p>Assumptions: the emptiness test is the shared kernel's never-supplied primitive rather than the
     * platform's general blank test, because the reference compares each screen field against
     * {@code SPACES OR LOW-VALUES} -- two figurative constants, each requiring every character position to
     * match -- and the primitive reproduces exactly that: absent, empty, wholly spaces or wholly low
     * values. The general blank test would additionally treat a tab as absent, which equals neither
     * constant, and would treat a run of low values as present, which equals one of them.
     *
     * <p>Assumptions: the field is marked blank rather than merely rejected, and the published contract
     * turns that distinction into presentation: its two admitted states are the rejected-value one and the
     * blank one, and it states that a caller rendering the baseline's presentation draws the asterisk
     * marker for the blank state only. That asterisk is the reference's own marker for an unfilled field,
     * so a blank arm that reported the rejected-value state would suppress it.
     *
     * @param field the request property or path name to attribute the refusal to
     * @param value the value as it arrived, which may be {@code null}
     * @param sentence the reference literal for this field's absence
     * @throws ClientInputException if the value is one the reference would have read as never filled in
     */
    private void requireSupplied(String field, String value, String sentence) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            LOG.info("event=auth.user.rejected reason=field-absent field={}", field);
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.BLANK, sentence);
        }
    }

    /**
     * Refuses a user type outside the two values the domain admits.
     *
     * <p>Assumptions: this check is a NARROWING of the reference and is stated as one. The two screen arms,
     * {@code app/cbl/COUSR01C.cbl} line 142 and {@code app/cbl/COUSR02C.cbl} line 204, test only for
     * absence, so the reference would store any single character a terminal could send; membership was
     * enforced nowhere at input and was only interpreted afterwards, by the condition names at
     * {@code app/cpy/COCOM01Y.cpy} lines 27 and 28 that give {@code 'A'} and {@code 'U'} their meanings.
     * A third value therefore reached storage and then matched neither condition, so every downstream test
     * of it fell through -- a user who was neither an administrator nor an ordinary user. Refusing it at
     * the boundary is the divergence, and it is deliberate.
     *
     * <p>Trade-offs: the same rule is now asserted in three places -- the pattern constraint on each
     * request record, this check, and the column constraint in {@code V1__auth.sql} -- and the repetition
     * is accepted for what each one covers that the others cannot. The record constraint refuses a body
     * before a handler runs but says nothing about a value reaching this service by any other path; this
     * check refuses such a value with the operation's own sentence and field key, where the column
     * constraint would surface as an integrity violation with no field attribution; and the column
     * constraint is the only one that also governs rows written by the migration loaders, which do not run
     * through this service at all.
     *
     * @param userType the submitted user type, already known to be non-blank
     * @throws ClientInputException if the value is neither of the two admitted ones, marking the field as
     *     supplied-and-rejected rather than blank
     */
    private void requireKnownUserType(String userType) {
        if (!USER_TYPE_ADMIN.equals(userType) && !USER_TYPE_USER.equals(userType)) {
            LOG.info("event=auth.user.rejected reason=user-type-domain field={}", FIELD_USER_TYPE);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_USER_TYPE,
                    FieldValidationFlag.NOT_OK, MESSAGE_USER_TYPE_DOMAIN);
        }
    }

    /**
     * The value that orders below every stored identifier, opening a forward page.
     *
     * <p>Assumptions: the empty string is used rather than a null, because the repository's forward query
     * takes a strict inequality and a null would make the predicate unknown for every row -- returning
     * nothing where the whole point is to return the first page. The reference achieves the same with low
     * values in a fixed-width field.
     */
    private static final String FORWARD_OPENING_SENTINEL = "";

    /**
     * The value that orders above every stored identifier, opening a backward page.
     *
     * <p>Assumptions: a run of the highest printable character, eight of them because the column is eight
     * wide, so it orders above every identifier the column can hold. The reference moves high values into
     * its backward key for the same purpose. It is a sentinel and never a stored value: the identifier
     * domain the reference uses is letters and digits, so no row can collide with it.
     */
    private static final String BACKWARD_OPENING_SENTINEL = "\u007e\u007e\u007e\u007e\u007e\u007e\u007e\u007e";

    /**
     * Reads one row more than a page from the position, in the direction asked for.
     *
     * <p>Assumptions: the surplus row is what settles whether a further page follows, which is the
     * reference's own device rather than a count of the file.
     *
     * @param position the exclusive key to read from
     * @param backward whether to read descending below the position rather than ascending above it
     * @return the rows read, at most one more than a page, ascending for a forward read and descending
     *     for a backward one; never {@code null}
     * @throws IllegalStateException if the store could not be read
     */
    private List<User> readWindow(String position, boolean backward) {
        try {
            Limit limit = Limit.of(PAGE_SIZE + 1);
            return backward
                    ? this.users.findByUserIdLessThanOrderByUserIdDesc(position, limit)
                    : this.users.findByUserIdGreaterThanOrderByUserIdAsc(position, limit);
        } catch (DataAccessException unreadable) {
            throw unableTo(MESSAGE_UNABLE_TO_LOOKUP,
                    "list-" + unreadable.getClass().getSimpleName());
        }
    }

    /**
     * Turns a read window into the published page envelope.
     *
     * <p>Assumptions: which end the surplus row sits at depends on the direction walked. A backward walk
     * was read descending and is reversed into ascending order for presentation, so its surplus row is
     * the earliest key and sits first before reversal; a forward walk's sits last. Removing the wrong end
     * would silently drop a row the caller should have seen.
     *
     * <p>Assumptions: the rows are always presented ASCENDING, whichever direction was walked, because
     * the contract declares the item array in ascending identifier order and the reference screen always
     * displayed a page top to bottom in key order regardless of which key had been pressed to reach it.
     *
     * <p>Assumptions: the two boundary cursors are sealed under the two DIRECTION scopes rather than one
     * binding, so the leading cursor can only be presented as a backward move and the trailing one only
     * as a forward move. That is what the contract means by a cursor bound to its direction.
     *
     * <p>Alternatives Considered: positioning by ordinal offset, which is what a page number invites and
     * what the reference screen appears at first glance to use. It is not adopted, and the reason is a
     * correctness one rather than a preference: an offset window is defined by how many rows precede it, and
     * that count changes when any row before the window is inserted or deleted, so between two requests a
     * caller silently skips rows and sees others twice. A key-bounded window cannot drift, because its
     * boundary is a value carried in the data rather than a count of rows scanned. The reference's page
     * number is display chrome and was never a position: {@code app/cbl/COUSR00C.cbl} moves it to the screen
     * at lines 327 and 376 and adjusts it at lines 309 to 310, 320 to 321 and 367 to 369, and no read
     * anywhere in the program is positioned from it -- both walks are positioned from a stored KEY, at lines
     * 242 and 265. So declining offsets keeps the reference's own mechanism rather than replacing it.
     *
     * <p>Refactoring Rationale: both cursors are derived from the rows actually returned, where the
     * reference derived them from two invariant screen slots, and the difference is observable rather than
     * cosmetic. Its capture paragraph switches on the row index at line 386: the first-row arm at line 387
     * stores the
     * leading key, with the second receiver on line 389, and the tenth-row arm at line 433 stores the
     * trailing key, with the second receiver on line 435. On any short final page the index never reaches
     * ten, so the tenth-row arm never fires and the trailing key still holds the value the PREVIOUS page
     * left there. Taking the trailing key from the last row that was returned makes it name a row the caller
     * was actually shown, on a full page and a short one alike, and an empty page names no boundary at all.
     *
     * @param window the rows read, at most one more than a page, in the order the query returned them
     * @param backward whether the window was walked backwards
     * @param subject the authenticated caller the two issued cursors are sealed against
     * @return the page envelope; never {@code null}
     */
    private PageResponse<UserSummary> page(List<User> window, boolean backward, String subject) {

        List<User> rows = new ArrayList<>(window);
        boolean more = rows.size() > PAGE_SIZE;
        if (more) {
            rows.remove(backward ? 0 : rows.size() - 1);
        }
        if (rows.isEmpty()) {
            // WHY : Assumptions: an empty page carries NO cursor in either direction, which
            //       PageResponse.empty expresses. There is no row to name a boundary with, and issuing a
            //       cursor over the sentinel the query started from would hand a caller a position it
            //       could page from for ever without moving.
            LOG.debug("event=auth.user.list-empty backward={}", backward);
            return PageResponse.empty();
        }
        if (backward) {
            // WHY : Assumptions: a backward walk was read DESCENDING so the nearest row comes first, and
            //       it is reversed into ascending order because the contract declares the item array in
            //       ascending identifier order. The reference screen likewise always displayed a page top
            //       to bottom in key order, whichever key had been pressed to reach it.
            Collections.reverse(rows);
        }

        // WHY : Assumptions: the projection is the MAP's four fields and nothing is taken from the
        //       reference's own scratch table, which is deliberately not modelled. app/cbl/COUSR00C.cbl
        //       declares WS-USER-DATA at lines 56 to 64 -- a ten-occurrence group whose row is a selection
        //       character, a single COMBINED name field of 25 characters at line 62, and a type field
        //       declared 8 wide at line 64. Neither of those two widths matches anything that is stored:
        //       app/cpy/CSUSR01Y.cpy declares the two names 20 each at lines 19 and 20 and the type 1 at
        //       line 22, and the map agrees with the record exactly -- its row-one fields are 20, 20 and 1
        //       at app/cpy-bms/COUSR00.CPY lines 84, 90 and 96. The table is also dead: the display
        //       paragraph at lines 384 to 441 writes the map fields directly and never writes a row of it.
        //       Taking a width from it would have merged the two names and widened the type eightfold.
        List<UserSummary> items = new ArrayList<>(rows.size());
        for (User row : rows) {
            items.add(this.mapper.toSummary(row));
        }

        String leading = rows.get(0).getUserId();
        String trailing = rows.get(rows.size() - 1).getUserId();

        // WHY : Assumptions: forward availability is the surplus row on a FORWARD walk and is
        //       unconditionally true on a backward one. A caller that has just moved backwards came from
        //       a page that exists, so a further page forwards demonstrably follows -- the one it left --
        //       and reporting otherwise would strand it at the position it had just retreated from.
        boolean hasNext = backward || more;

        return PageResponse.ofRows(items,
                this.cursorToken.seal(cursorBinding(subject, true), leading),
                this.cursorToken.seal(cursorBinding(subject, false), trailing),
                hasNext);
    }

    /**
     * Opens a supplied cursor, translating a refusal into a caller-input failure.
     *
     * @param binding the binding the token must have been sealed under
     * @param cursor the token as received from the caller
     * @return the raw key the token carries, for the repository predicate
     * @throws ClientInputException if the token cannot be opened, carrying the cursor parameter's key
     */
    private String openCursor(String binding, String cursor) {
        try {
            return this.cursorToken.open(binding, cursor);

            // WHY : Assumptions: the sealer's own refusal is already a caller-input failure, so it is
            //       re-raised keyed to the cursor parameter rather than replaced. What is added is the
            //       field key: the contract states that the array "identifies the offending parameter by
            //       name", and the sealer does not know the name the parameter has on this operation.
        } catch (CursorToken.InvalidCursorException refused) {
            LOG.info("event=auth.user.list-refused reason=cursor field={}", FIELD_CURSOR);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_CURSOR,
                    refused.getMessage());
        }
    }

    /**
     * Loads the row an operation addresses, or refuses the request the way the reference does.
     *
     * <p>Assumptions: the blank check precedes the read and carries the reference's own sentence for an
     * absent identifier, because the reference checks presence before it reads -- at
     * {@code app/cbl/COUSR02C.cbl} lines 146 to 151 and 180 to 185, and at
     * {@code app/cbl/COUSR03C.cbl} lines 147 and 179 -- and answers a blank field differently from a key
     * it could not find.
     *
     * <p>Assumptions: the identifier is folded to upper case under an invariant locale before the read,
     * matching what the sign-on path does and for the same reason: the reference folds the identifier
     * before its keyed read, and an invariant locale is what stops the platform default deriving a
     * different key from the same characters.
     *
     * @param userId the identifier as received; must not be {@code null}
     * @return the stored row; never {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws ClientInputException if the identifier is blank
     * @throws NoSuchElementException if no row carries the identifier
     * @throws IllegalStateException if the store could not be read
     */
    private User require(String userId) {

        Objects.requireNonNull(userId, "userId must not be null");

        if (userId.isBlank()) {
            LOG.info("event=auth.user.rejected reason=user-id-absent field={}", FIELD_USER_ID);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_USER_ID,
                    MESSAGE_USER_ID_REQUIRED);
        }

        String key = userId.trim().toUpperCase(Locale.ROOT);

        Optional<User> found;
        try {
            found = this.users.findById(key);
        } catch (DataAccessException unreadable) {
            throw unableTo(MESSAGE_UNABLE_TO_LOOKUP,
                    "read-" + unreadable.getClass().getSimpleName());
        }

        // WHY : Assumptions: the absence is raised as the standard no-such-element failure because the
        //       shared advice maps exactly that type onto 404 and carries the sentence it holds onto the
        //       body when the sentence passes its provenance gate. A different type would answer 500 with
        //       a generic sentence, and the operation's published 404 names this literal specifically.
        return found.orElseThrow(() -> {
            LOG.info("event=auth.user.not-found userId={}", key);
            return new NoSuchElementException(MESSAGE_USER_ID_NOT_FOUND);
        });
    }

    /**
     * Reports whether a row already carries the identifier, translating a store failure.
     *
     * @param userId the identifier to probe for
     * @return {@code true} when a row exists for it
     * @throws IllegalStateException if the store could not be read, carrying the reference sentence for a
     *     failed add, because on the only path that probes, a failure means the add cannot proceed
     */
    private boolean exists(String userId) {
        try {
            return this.users.existsById(userId);
        } catch (DataAccessException unreadable) {
            throw unableTo(MESSAGE_UNABLE_TO_ADD,
                    "duplicate-probe-" + unreadable.getClass().getSimpleName());
        }
    }

    /**
     * Provisions the pool account a new row will be bound to, translating a provider failure.
     *
     * <p>Assumptions: the pool's own duplicate-username condition is translated to a FAULT and not to the
     * caller-facing conflict, because the authority on whether a user identifier is taken is the primary
     * key on {@code auth.users} and the probe above has already found no row. Reaching this condition
     * therefore means an account exists whose row was never written -- an inconsistency an operator has
     * to resolve, not something a caller can correct by choosing another identifier.
     *
     * <p>Alternatives Considered: having the caller supply the subject reference on the create body, so that
     * this service wrote a row and provisioned nothing. Rejected because the subject is minted by the pool
     * and nothing outside it can produce a value the pool will later recognise, so a caller-supplied subject
     * would either be an identifier the pool never issued or would require the caller to have provisioned
     * the account itself -- moving account creation out of this service without removing this service's
     * dependence on it. The create body accordingly declares four properties and no subject.
     *
     * <p>Assumptions: no credential passes through this method or anywhere else in this class. The pool
     * mints the initial one for an account it creates and the migration's infrastructure writes the seed
     * users' passwords straight into the managed secret store, so the only place a credential is handled at
     * all is the sign-on collaborator that presents one for verification. That is what replaces the
     * reference's arrangement, where the credential sat in clear in the record at
     * {@code app/cpy/CSUSR01Y.cpy} line 21.
     *
     * @param request the validated new row's values
     * @return the subject the provider minted; never {@code null}
     * @throws IllegalStateException if the account could not be created, carrying the reference sentence
     *     for a failed add
     */
    private UUID provision(CreateUserRequest request) {
        try {
            return this.provisioning.provision(request.userId(), request.firstName(),
                    request.lastName(), request.userType());

        } catch (UsernameExistsException orphaned) {
            LOG.error("event=auth.user.create-failed reason=pool-account-orphaned userId={}",
                    request.userId());
            throw unableTo(MESSAGE_UNABLE_TO_ADD, "pool-account-orphaned");

        } catch (SdkException providerFault) {
            throw unableTo(MESSAGE_UNABLE_TO_ADD,
                    "provision-" + providerFault.getClass().getSimpleName());
        }
    }

    /**
     * Withdraws a provisioned account on a failure path without masking the failure being handled.
     *
     * <p>Assumptions: a failure of the withdrawal itself is logged and swallowed, which is the one place
     * in this class an exception is discarded. It is deliberate: this runs while another failure is being
     * raised, and letting a compensation failure propagate would replace the failure the caller needs to
     * hear about with one about cleanup. The orphaned account is recorded at error level so an operator
     * still learns of it.
     *
     * @param userId the provider username to remove
     * @param reason the internal cause of the failure being compensated, for the log only
     */
    private void withdrawQuietly(String userId, String reason) {
        try {
            this.provisioning.withdraw(userId);
        } catch (SdkException stillPresent) {
            LOG.error("event=auth.user.compensation-failed userId={} reason={} exception={}",
                    userId, reason, stillPresent.getClass().getName());
        }
    }

    /**
     * Reports whether an update request would change nothing about the stored row.
     *
     * @param stored the row as loaded, with fixed-width padding already stripped by the mapper
     * @param request the validated values submitted
     * @return {@code true} when all three submitted values already match the row
     */
    private static boolean matchesStoredRow(User stored, UpdateUserRequest request) {
        return Objects.equals(stored.getFirstName(), request.firstName())
                && Objects.equals(stored.getLastName(), request.lastName())
                && Objects.equals(stored.getUserType(), request.userType());
    }

    /**
     * Composes the binding a cursor of this listing is sealed and opened under.
     *
     * <p>Assumptions: the caller's name is part of the binding, so a page issued to one administrator
     * cannot be replayed by another. The direction is carried as the query SCOPE, which is the third part
     * the sealer's composition takes; the composition is length-prefixed rather than delimiter-joined, so
     * no value of either part can be made to look like the other.
     *
     * @param subject the authenticated caller's name; must not be blank
     * @param backward whether the token is for a backward move
     * @return the composed binding; never {@code null}
     */
    private static String cursorBinding(String subject, boolean backward) {
        return CursorToken.binding(CURSOR_QUERY_NAME, subject,
                backward ? CURSOR_SCOPE_BACKWARD : CURSOR_SCOPE_FORWARD);
    }

    /**
     * Builds the failure that answers an operation this service could not complete.
     *
     * <p>Assumptions: the raised type is exactly the standard illegal-state one, and that is load
     * bearing. The shared advice carries a service's own sentence onto the 500 body only when the type is
     * exactly that one and the sentence passes its provenance gate -- ending in an ellipsis, inside the
     * declared width, holding no long run of digits -- and each of the reference sentences above
     * satisfies all three. Any other type would render a generic sentence, and each operation's published
     * 500 names its reference literal specifically.
     *
     * @param sentence the reference literal the caller is to be told, chosen per operation
     * @param reason the internal cause to record in the log; never reaches the caller
     * @return the failure to throw; never {@code null}
     */
    private static IllegalStateException unableTo(String sentence, String reason) {
        LOG.error("event=auth.user.failed reason={}", reason);
        return new IllegalStateException(sentence);
    }

    /**
     * Reports that a row already carries the submitted user identifier.
     *
     * <p>Purpose: this type exists so the adapter can answer 409 with the reference's own sentence for
     * the condition. The shared kernel's own conflict type carries a constant sentence chosen from a small
     * set of contention kinds -- a stale version, an unavailable lock, a referenced row -- none of which
     * describes a duplicate primary key, and none of whose sentences is the literal this contract
     * publishes.
     *
     * <p>Assumptions: it is declared here, nested in the service that decides the conflict, following the
     * idiom {@code com.carddemo.common.web.CursorToken.InvalidCursorException} establishes in the shared
     * kernel: a refusal type belongs with the code that decides the refusal.
     *
     * <p>Assumptions: it extends the standard illegal-state type rather than a framework status exception
     * so that a caller inside the application sees an ordinary failure. It is nonetheless answered as 409
     * rather than 500, because the adapter declares a handler for this exact type and a
     * controller-local handler is preferred over any advice for exceptions raised in that controller.
     */
    public static final class DuplicateUserException extends IllegalStateException {

        /**
         * The serialization version of this refusal.
         *
         * <p>Assumptions: declared because the supertype is serializable, so a class inheriting
         * serializability without it gets a value derived from its structure and adding a field would
         * silently change that value. Nothing serializes this type today.</p>
         */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the conflict with the sentence the adapter will render.
         *
         * @param message the sentence to carry, always {@link #MESSAGE_USER_ID_EXISTS} in this service;
         *     must not be {@code null}
         */
        DuplicateUserException(String message) {
            super(message);
        }
    }
}
