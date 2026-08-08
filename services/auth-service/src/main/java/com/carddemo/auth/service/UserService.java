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
     * advice's conflict renderer carries a fixed sentence from a small set of contention kinds and none
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

    /**
     * The number of rows one page of the list carries.
     *
     * <p>Assumptions: ten is the reference screen's own row count, established by
     * {@code app/cpy-bms/COUSR00.CPY} declaring ten identical row groups of which the tenth is the last
     * -- its fields at lines 348, 354, 360 and 366. The committed contract declares the same number as
     * {@code maxItems: 10} on the page's item array and states that the size is set by the contract
     * rather than chosen by the caller, which is why no page-size parameter exists.
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

    /** The key a refusal blamed on the identifier carries. */
    private static final String FIELD_USER_ID = "userId";

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
     * @param request the validated new row's values; must not be {@code null}
     * @return the row as stored, as a subsequent read would return it, including the subject it was bound
     *     to; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws DuplicateUserException if a row already carries the submitted identifier, carrying the
     *     reference sentence for that condition
     * @throws IllegalStateException if the row could not be written or the pool account could not be
     *     created, carrying the reference sentence for a failed add
     */
    @Transactional
    public UserResponse create(CreateUserRequest request) {

        Objects.requireNonNull(request, "request must not be null");

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
     * @param userId the identifier of the row to change, from the request path; must not be {@code null}
     * @param request the validated values the row is to hold; must not be {@code null}
     * @return the row as stored after the change; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ClientInputException if the identifier is blank, or if every submitted value already
     *     matches the stored row, carrying the reference sentence for that condition and no field
     *     attribution
     * @throws NoSuchElementException if no row carries the identifier
     * @throws IllegalStateException if the change could not be written or the pool could not be brought
     *     in line, carrying the reference sentence for a failed update
     */
    @Transactional
    public UserResponse update(String userId, UpdateUserRequest request) {

        Objects.requireNonNull(request, "request must not be null");

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

        } catch (DataAccessException undeletable) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "delete-" + undeletable.getClass().getSimpleName());

        } catch (SdkException providerFault) {
            throw unableTo(MESSAGE_UNABLE_TO_UPDATE,
                    "delete-provider-" + providerFault.getClass().getSimpleName());
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
     * the condition. The shared kernel's own conflict type carries a fixed sentence chosen from a small
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
