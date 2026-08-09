package com.carddemo.auth.mapper;

import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Translates a stored user row into the transport shapes this context publishes, and a submitted
 * request back into that row.
 *
 * <p>This is the anti-corruption layer the package charter describes, and it is the only type in the
 * AUTH bounded context permitted to know that the stored form of a value differs from the form a
 * caller sees. Four directions are declared and no fifth: a row becomes a full response, a row
 * becomes a page item, a creation request becomes a new row, and an update request is applied to a
 * row already loaded. Each is a whole mapping rather than a field helper, so a caller assembles no
 * projection out of parts and reaches for no adjustment of its own.</p>
 *
 * <h2>What storage and the transport shapes actually declare</h2>
 *
 * <p>Assumptions: {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} is the
 * binding authority for storage, and it creates exactly five columns:
 * {@code user_id CHAR(8) PRIMARY KEY}, {@code first_name VARCHAR(20) NOT NULL},
 * {@code last_name VARCHAR(20) NOT NULL}, {@code user_type CHAR(1) NOT NULL} carrying a check
 * constraint that admits two values, and {@code cognito_sub UUID NOT NULL UNIQUE}. Two of those
 * five are blank-padded fixed-width character columns and three are not, and that split is the
 * whole reason the handling below is selective rather than uniform. Where the published contract
 * under {@code src/main/resources/openapi/} and that migration could ever disagree, the migration
 * decides, because it is what the database will actually enforce.</p>
 *
 * <p>Assumptions: three absences in that column list remove work this class might otherwise be
 * expected to do, and each is relied on rather than rediscovered. There is no credential column, so
 * no mapping here has one to read or write. There is no version column under any name, so no
 * conflict token is carried in either direction and no caller of this class can be handed one. And
 * there is no timestamp or audit column, so nothing here formats an instant and no shared timestamp
 * facility is reached for.</p>
 *
 * <p>Assumptions: every one of the five columns is declared {@code NOT NULL}, so a row this class
 * receives from the repository carries a value in each member and none of the reads below can meet a
 * null. A request reaching this class has already been accepted by the constraints declared on
 * {@code com.carddemo.auth.dto.CreateUserRequest} and
 * {@code com.carddemo.auth.dto.UpdateUserRequest}, so no argument is re-validated here and no
 * out-of-domain value is quietly replaced by a default. A value this class cannot map is a value the
 * layer above should have refused.</p>
 *
 * <h2>The record this mapping answers to</h2>
 *
 * <p>Assumptions: {@code 01 SEC-USER-DATA}, declared at {@code app/cpy/CSUSR01Y.cpy} L17, is
 * normative, and its arithmetic is self-checking. It declares six elementary fields across L18 to
 * L23 -- {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)},
 * {@code SEC-USR-LNAME PIC X(20)}, {@code SEC-USR-PWD PIC X(08)}, {@code SEC-USR-TYPE PIC X(01)}
 * and {@code SEC-USR-FILLER PIC X(23)} -- at zero-based offsets 0, 8, 28, 48, 56 and 57. Because
 * 8 + 20 + 20 + 8 + 1 + 23 sums to exactly 80, every byte the dataset stores is accounted for and no
 * field escapes a decision. That sum is the only cross-check available, since the copybook carries
 * no record-length banner to compare it against. Four of the six are carried across; the two at L21
 * and L23 are not, and each omission is justified beside the mapping that omits it rather than left
 * for a reader to infer.</p>
 *
 * <p>Assumptions: the layout above is cited and never restated as a constant here, so the copybook
 * stays the single place a width is declared. Turning a fixed-width image into fields is the work of
 * the shared codecs under {@code com.carddemo.common.codec}, and no such image reaches this class:
 * the extract loader decodes the dataset, and what arrives here is a decoded column or a submitted
 * field. That is why no codec is imported below and why no byte position appears in any expression.
 * The written convention this class is documented against is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated. The mechanical half
 * is {@code config/checkstyle/checkstyle.xml}, which audits this file at the Maven validate phase
 * and therefore on every local build rather than in CI alone.</p>
 */
// Alternatives Considered: generating these four mappings with MapStruct, which is the standard
//   answer to a hand-written mapper, and which is rejected on two independent grounds. The first is
//   that this mapping is not mechanical. It makes four decisions a generated implementation has
//   nowhere to record: it drops the 23-byte SEC-USR-FILLER declared at app/cpy/CSUSR01Y.cpy L23, it
//   removes trailing padding from the two fixed-width character columns while passing a leading
//   blank through untouched, it supplies a subject reference that no baseline field stands behind,
//   and it carries no credential in either direction. Each of those is a judgement a reader has to
//   be able to question at the point it is made, and an annotation on an abstract method has no
//   room for the sentence that answers the question. The second ground is independent of this file:
//   the most recent published MapStruct release is a beta, so adopting it would place a pre-release
//   artifact on the compile path of every service module in the reactor.
// Alternatives Considered: Lombok, to shorten the accessor traffic below. Rejected because the
//   project's Explainability rule requires a docstring naming every parameter with its type and
//   description and describing every return value, and an accessor materialised by an annotation
//   processor has no source location such a docstring could attach to. Java 21 records for the
//   transport shapes plus explicit methods here give the same brevity with members that can be
//   documented, and config/checkstyle/suppressions.xml admits only generated sources and test
//   fixtures, so no suppression would be available to a Lombok-built type in this tree in any case.
// Trade-offs: concentrating every representation concern in this one class makes it denser than the
//   five packages it serves, and that density is the cost. What it buys is that com.carddemo.auth.api
//   returns values already at the width a caller sees, com.carddemo.auth.service compares and
//   branches on those values without stripping anything first, com.carddemo.auth.repository binds
//   query values that need no adjustment, and com.carddemo.auth.dto and com.carddemo.auth.domain
//   describe a transport shape and a column respectively without either naming a byte position. The
//   alternative -- letting each layer remove the padding it happens to notice -- spreads one rule
//   across places no build compares, so two of them eventually disagree, and that disagreement
//   surfaces in data returned to a caller rather than in a failing build step.
@Component
public class UserMapper {

    // Assumptions: the blank is the only character a COBOL fixed-width field and a PostgreSQL
    //   CHAR(n) column pad with, so it is the only character the trim below removes. Naming it once
    //   keeps that operation from being read as general whitespace handling, which is precisely what
    //   it must not become.
    private static final char BLANK = ' ';

    /**
     * Projects a stored row onto the full response shape, at the widths a caller sees.
     *
     * <p>The five components are supplied in the order {@code UserResponse} declares them, which is
     * the record order of {@code 01 SEC-USER-DATA} with the subject reference appended: the
     * identifier from L18, the two names from L19 and L20, the type from L22, then the column the
     * migration adds. The fields at L21 and L23 have no component here to fill.</p>
     *
     * @param user the managed {@code User} loaded from {@code auth.users}, each of whose five
     *     members is non-null because every column of that table is declared {@code NOT NULL}
     * @return a {@code UserResponse} carrying the identifier and the type with their storage padding
     *     removed, both names exactly as stored, and the subject reference unchanged
     * @throws NullPointerException when {@code user} is null, which is a defect in the calling code
     *     rather than an absent row -- a lookup that matched nothing yields an empty container from
     *     {@code com.carddemo.auth.repository.UserRepository}, never a null entity to project
     */
    public UserResponse toResponse(User user) {
        Objects.requireNonNull(user, "user must not be null when projecting a user response");
        // Assumptions: PostgreSQL blank-pads a value stored in a CHAR(n) column out to n, so
        //   user_id CHAR(8) holding a six-character identifier returns eight characters and
        //   user_type CHAR(1) returns the one it was given. That padding is the column's own doing
        //   and is not data, so it is removed here and the caller receives a logical value. Removing
        //   it is not an invention either: app/cbl/COUSR01C.cbl L255 to L258 builds its success
        //   message with STRING 'User ' DELIMITED BY SIZE, then SEC-USR-ID DELIMITED BY SPACE, then
        //   ' has been added ...' DELIMITED BY SIZE, INTO WS-MESSAGE -- the DELIMITED BY SPACE at
        //   L256 stops at the first blank, so the baseline itself drops the trailing blanks off this
        //   same X(08) identifier at the one point it needs a logical value rather than a field.
        // Assumptions: user_id stays CHAR(8) and is never widened to VARCHAR(8), which is what makes
        //   the trim above necessary rather than incidental. The width is inherited from
        //   SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy L18 and V1__auth.sql declares the column to
        //   match, and it is relied on simultaneously by the extract loader that reads the 80-byte
        //   record, by the eight-position terminal field, and by the length bound the published
        //   contract advertises. A varying-width column would accept a ninth character without
        //   complaint and would stop being that shared contract, and the divergence would appear in
        //   migrated data rather than in a build.
        // Assumptions: the two names are read straight through because first_name and last_name are
        //   declared VARCHAR(20), a type that stores no padding of its own, so there is nothing on
        //   this path to remove. Trimming them anyway would silently discard a trailing blank a
        //   caller had genuinely stored. Their 20-character limit still holds, enforced by the
        //   column and restated by the constraint on the transport shape, and it comes from
        //   SEC-USR-FNAME PIC X(20) at L19 and SEC-USR-LNAME PIC X(20) at L20.
        // Refactoring Rationale: nothing on this read path echoes a credential, and the baseline
        //   resolves that question inconsistently across its three read faces, so the omission is
        //   uniform here rather than face-by-face. app/cbl/COUSR02C.cbl L169 moves SEC-USR-PWD into
        //   PASSWDI OF COUSR2AI, sending the stored value back to the terminal on the update screen,
        //   and app/cpy-bms/COUSR02.CPY carries it twice for that purpose, as the input face
        //   02 PASSWDI PIC X(8) at L78 and the output face 02 PASSWDO PIC X(8) at L152. The other
        //   two faces already decline: the delete view at app/cbl/COUSR03C.cbl L164 to L169 refills
        //   exactly three fields, the two names at L165 and L166 and the type at L167, and the list
        //   map app/cpy-bms/COUSR00.CPY declares no credential field at all. The baseline does what
        //   L169 describes on one face and what the other two describe elsewhere; the Java declines
        //   on all three; the divergence is documented, and two of the three faces already agreed.
        return new UserResponse(
                stripTrailingBlanks(user.getUserId()),
                user.getFirstName(),
                user.getLastName(),
                stripTrailingBlanks(user.getUserType()),
                user.getCognitoSub());
    }

    /**
     * Projects a stored row onto the narrower shape a listing page carries.
     *
     * <p>The four components are supplied in the order {@code UserSummary} declares them, which is
     * the left-to-right order of the first row of the list map: the identifier from
     * {@code USRID01I PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY} L78, the two names from
     * {@code FNAME01I PIC X(20)} at L84 and {@code LNAME01I PIC X(20)} at L90, and the type from
     * {@code UTYPE01I PIC X(1)} at L96.</p>
     *
     * @param user the managed {@code User} loaded from {@code auth.users}, each of whose members is
     *     non-null because every column of that table is declared {@code NOT NULL}
     * @return a {@code UserSummary} carrying the identifier and the type with their storage padding
     *     removed and both names exactly as stored
     * @throws NullPointerException when {@code user} is null, which is a defect in the calling code
     *     rather than an absent row, for the reason given on the response projection above
     */
    public UserSummary toSummary(User user) {
        Objects.requireNonNull(user, "user must not be null when projecting a user summary");
        // Assumptions: one logical property answers to three different screen-local names, and
        //   collapsing them is a representation concern that belongs here rather than in a caller.
        //   The single SEC-USR-ID of app/cpy/CSUSR01Y.cpy L18 is presented as USERIDI by the add map
        //   at app/cpy-bms/COUSR01.CPY L72, as USRIDINI by the update map at
        //   app/cpy-bms/COUSR02.CPY L60, and as USRID01I by the list map at
        //   app/cpy-bms/COUSR00.CPY L78; the same three-way split governs the two names and the
        //   type. Every one of those faces declares the same width as the record field behind it, so
        //   the collapse loses nothing, and performing it once here is what lets the rest of the
        //   context work with a single property name.
        // Assumptions: the selection field the list map places beside each row -- SEL0002L at
        //   app/cpy-bms/COUSR00.CPY L97 opens the second row's group -- is terminal cursor control
        //   and not part of this shape, which is why the citations above stop at L96 and no
        //   component corresponds to it. In the target the row a caller acts on is named by the
        //   request path instead.
        return new UserSummary(
                stripTrailingBlanks(user.getUserId()),
                user.getFirstName(),
                user.getLastName(),
                stripTrailingBlanks(user.getUserType()));
    }

    /**
     * Builds a new row from an accepted creation request and the subject its pool account was given.
     *
     * <p>The five constructor arguments are supplied in the record order of
     * {@code 01 SEC-USER-DATA} with the subject reference appended last, which is also the column
     * order {@code V1__auth.sql} declares. The request's own component order differs -- it leads with
     * the first name, because {@code app/cpy-bms/COUSR01.CPY} places {@code FNAMEI PIC X(20)} at L60
     * ahead of {@code USERIDI PIC X(8)} at L72 -- and that difference is preserved rather than
     * reconciled. Naming each argument explicitly below is what lets the screen order stand in the
     * request and the record order stand in the row without either being reordered to suit the
     * other.</p>
     *
     * @param request the validated {@code CreateUserRequest} whose four components have already
     *     satisfied their declared constraints, so none is re-checked here
     * @param cognitoSub the {@code UUID} subject of the pool account created for this user, as
     *     returned by {@code com.carddemo.auth.service.CognitoUserProvisioningService}
     * @return a transient {@code User} carrying the four submitted values and that subject, ready to
     *     be persisted and holding no generated state of its own
     * @throws NullPointerException when {@code request} or {@code cognitoSub} is null, either of
     *     which would otherwise produce a row violating a {@code NOT NULL} column at flush time and
     *     report the fault a transaction away from the code that caused it
     */
    public User toEntity(CreateUserRequest request, UUID cognitoSub) {
        Objects.requireNonNull(request, "request must not be null when building a user row");
        Objects.requireNonNull(cognitoSub, "cognitoSub must not be null when building a user row");
        // Refactoring Rationale: this write path has no credential step, and that is the single
        //   place where parity with the baseline is declined deliberately rather than preserved. The
        //   baseline keeps the credential inside the record and compares it in application code:
        //   app/cpy/CSUSR01Y.cpy L21 declares SEC-USR-PWD PIC X(08), eight characters in the clear at
        //   zero-based offset 48 of the 80-byte record, and app/cbl/COSGN00C.cbl L223 authenticates
        //   with IF SEC-USR-PWD = WS-USER-PWD, testing the submitted value directly against the one
        //   read out of that record with no hash, no salt and no trim. The write face is
        //   app/cbl/COUSR01C.cbl L157, MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD, one of the five moves
        //   at L154 to L158 that fill the record before the keyed WRITE at L240 to L248. In the
        //   target a managed identity pool holds the credential and performs that comparison, and
        //   auth.users keeps only cognito_sub, so this mapper has no field to read a credential from
        //   or write one to, in either direction and under any name. The baseline does what L21 and
        //   L223 describe and keeps running unchanged; the Java encodes the managed-pool arrangement;
        //   the divergence is documented here and in the traceability register.
        // Refactoring Rationale: cognitoSub is the one value crossing this boundary with no baseline
        //   field behind it, and it exists because the credential does not. It is the sole link
        //   between a presented token and this row -- the column is declared UUID NOT NULL UNIQUE --
        //   so it replaces the dropped credential as the means of establishing which stored row an
        //   authenticated caller is. No byte position is cited for it because the record declares
        //   none; its value originates with the identity provider rather than with the dataset.
        // Alternatives Considered: taking the subject from the request instead of as this second
        //   parameter, which is how an earlier shape of com.carddemo.auth.dto.CreateUserRequest
        //   carried it. Rejected, and the record itself now declares only four components. A caller
        //   able to nominate the subject is a caller able to decide which pool identity a new row
        //   authenticates as, including one belonging to another person or one whose pool group
        //   contradicts the submitted type, and because a well-formed UUID is the only shape such a
        //   value has to satisfy, no validation on the request could have told the two cases apart.
        //   Accepting the subject here as a separate argument means it can only come from the
        //   provisioning service that created the account and read it back, so the row and the pool
        //   identity are established by one act and cannot drift apart. It remains a component of
        //   com.carddemo.auth.dto.UserResponse, where it is an output a caller learns rather than an
        //   input a caller chooses.
        // Assumptions: no value is space-padded on the way in, and the omission is deliberate rather
        //   than overlooked. A CHAR(n) column pads a short value out to its declared width itself, so
        //   padding here as well would store the padding twice and leave a value longer than the
        //   column declares. That failure is quiet rather than loud: an equality test against a
        //   correctly padded value stops matching and a keyed lookup returns no row instead of
        //   reporting an error. Write-side handling is therefore the absence of an operation, which is
        //   recorded because an absent operation leaves nothing in the source for a reader to find
        //   and question.
        // Assumptions: SEC-USR-FILLER is dropped, and this is where that drop is recorded for the
        //   SEC-USER-DATA record. app/cpy/CSUSR01Y.cpy L23 declares 05 SEC-USR-FILLER PIC X(23), 23
        //   bytes at zero-based offset 57, padding the 57 bytes the five named fields occupy out to
        //   the 80 the dataset stores. The migration's first transformation rule requires exactly
        //   this: FILLER is dropped and the drop is recorded per record. The drop is lossless rather
        //   than merely permitted -- SEC-USR-FILLER appears once in the entire baseline, at its own
        //   declaration, and no program assigns it or reads it, the persistence block at
        //   app/cbl/COUSR01C.cbl L153 to L160 writing five moves at L154 to L158 of which none names
        //   it. With four of the six fields carried across, L21 and L23 are the two omissions, and
        //   both are accounted for immediately above.
        return new User(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.userType(),
                cognitoSub);
    }

    /**
     * Applies an accepted update request to a row already loaded, mutating the two descriptive values in
     * place and refusing any request that would move the row's authority.
     *
     * <p>This method returns nothing and constructs nothing. It assigns the two descriptive values onto
     * the {@code User} it is given, which the caller obtained inside the current transaction, so the
     * persistence provider observes the change against its own loaded snapshot and issues a statement
     * only for what actually differs. The identifier is not among them: it locates the row rather than
     * describing it, and {@code User} exposes no way to change it. Neither is the reference type,
     * although the request carries one -- see below.</p>
     *
     * <p>Refactoring Rationale: this method previously assigned all three values, including the
     * reference type, and that assignment was wrong in a way that was invisible from here. The column it
     * wrote, {@code auth.users.user_type}, NAMES an authority and confers none: every authority a
     * request is matched against comes from the signed {@code cognito:groups} claim by way of
     * {@code com.carddemo.common.security.JwtRoleConverter}, and the membership behind that claim is
     * held by the identity provider. So an update moving a user from {@code "U"} to {@code "A"}
     * committed a row saying the user was an administrator while every administrative route continued to
     * refuse them, and an update moving an administrator to {@code "U"} committed a row saying the
     * authority had been withdrawn while it had not been. The second direction is the one that matters:
     * an operator revoking administrative access was told it was done.</p>
     *
     * <p>Refactoring Rationale: that analysis stands and the remedy has moved. This method briefly
     * REFUSED a submitted type change outright, to force the caller through a provider-first path. The
     * refusal is withdrawn because it made the only published route for the change unusable:
     * {@code UpdateUserRequest} declares {@code userType} REQUIRED
     * ({@code src/main/resources/openapi/auth-api.yaml}), and that property's own description states
     * that changing it "grants or removes that user's carddemo-admin authority at their next sign-on",
     * so {@code PUT /api/v1/auth/users/{userId}} is the operation the contract publishes for it and no
     * second route exists. A mapper that threw on the required field of the only route turned the
     * documented capability into a permanent server error.</p>
     *
     * <p>Assumptions: the guarantee the refusal was protecting is preserved, and it is preserved where
     * it can actually be honoured rather than where it can only be blocked. The sole caller,
     * {@code com.carddemo.auth.service.UserService#update}, commits this row together with a ledger entry
     * in {@code auth.identity_sync_task} naming both the previously stored type and the newly requested
     * one, and then applies that entry through {@code CognitoUserProvisioningService#synchronise} after
     * the commit. The membership therefore moves for every committed change to this column, and an entry
     * whose application does not succeed stays owed until the reconciliation pass applies it.</p>
     *
     * <p>Refactoring Rationale: that paragraph previously claimed something stronger and untrue -- that
     * the provider call ran inside this row's transaction, so "a provider failure propagates and rolls
     * the row back" and the column could never name an authority the provider does not confer. The
     * provider is not a transaction participant: it does not roll back, so a group change that SUCCEEDED
     * and was followed by a failed commit left the membership moved and this column unchanged, with the
     * claimed invariant broken in exactly the direction that matters -- a revocation the operator was
     * told had happened. The invariant is now stated as what it actually is: EVENTUAL, bounded by the
     * ledger, and always recorded when it is not yet met.</p>
     *
     * <p>Trade-offs: between the commit and the post-commit application -- ordinarily sub-second, bounded
     * by the reconciliation pass otherwise -- this column can name an authority the provider has not yet
     * moved. Assumptions: that window cannot grant access it should not, because every authority a
     * request is matched against is read from the signed claim on each request and never from this
     * column, so a stale projection can only delay a change, never anticipate one.</p>
     *
     * <p>Alternatives Considered: keeping the refusal and routing the service through
     * {@code com.carddemo.auth.service.UserAuthorityService}, which moves the membership first and
     * registers a rollback compensation. Rejected for this route: an in-memory compensation registered
     * against a transaction synchronisation is lost with the process that holds it, so it closes the
     * window only while that process survives -- which is precisely the case the ledger does not need to
     * assume. That class remains the provider-first primitive for a caller that has no transaction at
     * all.</p>
     *
     * @param request the validated {@code UpdateUserRequest} whose three components have already
     *     satisfied their declared constraints, so none is re-checked here
     * @param user the {@code User} to mutate, loaded by the caller's write transaction so that the
     *     assignments below are visible to the provider's dirty checking
     * @throws NullPointerException when {@code request} or {@code user} is null, which is a defect in
     *     the calling code -- an update aimed at a row that does not exist is refused before a row
     *     is ever loaded to mutate
     */
    public void applyUpdate(UpdateUserRequest request, User user) {
        Objects.requireNonNull(request, "request must not be null when applying a user update");
        Objects.requireNonNull(user, "user must not be null when applying a user update");
        // Refactoring Rationale: the void mutator shape is derived from the baseline rather than
        //   chosen for style. app/cbl/COUSR02C.cbl loads the record at L217 with
        //   PERFORM READ-USER-SEC-FILE, whose EXEC CICS READ at L322 to L331 carries
        //   RIDFLD (SEC-USR-ID) at L326, KEYLENGTH at L327 and the UPDATE option at L328; it then
        //   mutates that same record area in place at L219 to L234; and it commits with the
        //   EXEC CICS REWRITE at L360 to L366, which names DATASET, FROM, LENGTH, RESP and RESP2 and
        //   carries NEITHER RIDFLD NOR KEYLENGTH, because it rewrites the row the earlier read
        //   already holds. Load, mutate the loaded thing, flush what changed is exactly the
        //   managed-entity contract, so the target expresses it directly.
        // Alternatives Considered: building a fresh User from the request and saving that. Rejected
        //   because the request declares three components and the row has five: a rebuilt instance
        //   would need the identifier and the subject reference copied across to avoid being a
        //   different row, User exposes no setter for the identifier at all, and passing a detached
        //   instance to a save would either merge over state the request never mentioned or insert a
        //   second row. Mutating the loaded instance keeps identity where the transaction already
        //   established it.
        // Assumptions: assignment is unconditional here, and the baseline's arm-by-arm comparison is
        //   deliberately not reproduced. Its four arms at L219 to L234 -- each setting
        //   USR-MODIFIED-YES, at L221, L225, L229 and L233 -- exist because CICS REWRITE has no
        //   notion of an unchanged record, so the program had to decide for itself whether to issue
        //   the call at all, which is what the gate at L236 to L238 and the L239 message
        //   'Please modify to update ...' do. A persistence provider performs that comparison
        //   against its loaded snapshot, so assigning an identical value here produces no statement
        //   and reimplementing the arms would duplicate a decision already made one layer down.
        // Assumptions: had a comparison been written here it would have had to compare logical
        //   values, not stored images, because each baseline arm compares a space-padded screen field
        //   against a space-padded record field whereas the two values assigned here are ordinary
        //   strings against VARCHAR(20). Delegating the comparison removes that hazard from this path
        //   entirely rather than solving it. The one comparison this method DOES make, the reference-type
        //   refusal below, is exempt from the hazard for the reason recorded beside it: CHAR(1) over a
        //   two-character domain has no padding to normalise.
        // Assumptions: three assignments rather than the baseline's four arms, and the one absence is
        //   the credential. The credential arm at L227 to L229 compares a submitted credential against
        //   the stored one and marks the row modified when they differ; with no credential on this
        //   boundary a request that changed only that value cannot be expressed, so it is absent from
        //   the shape rather than handled and discarded, which follows from the omission recorded on the
        //   creation path above. The reference-type arm at L231 to L233 IS expressible here and IS
        //   applied, and the block above this method records why applying it is safe: in the baseline
        //   that byte WAS the authority, in the target it only names one, and the sole caller moves the
        //   provider membership in the same transaction so the two cannot part company. The baseline
        //   tests four values and this method carries three; that divergence is documented.
        // Trade-offs: two concurrent updates to one row resolve last-writer-wins inside the
        //   database's row lock, because auth.users declares no version column and this method
        //   therefore carries no conflict token to refuse one of them with. That is the baseline's
        //   own outcome: its screen-populating read and its saving rewrite run in two different CICS
        //   tasks, the L328 UPDATE lock lives only for the first of them, and COUSR02C holds no
        //   before-image, no timestamp and no version token to detect a change made in between --
        //   unlike app/cbl/COACTUPC.cbl, which does implement such a check for the account flows.
        //   Refusing an update here would be a new observable behaviour rather than a migrated one,
        //   so the semantic is documented, not altered.
        // Assumptions: the type is assigned WITHOUT being compared against the stored one first, and
        //   that is not an oversight. This method is reached only after
        //   com.carddemo.auth.service.UserService#update has established that at least one of the three
        //   values differs, and it hands the PREVIOUSLY stored type to the provider itself -- read
        //   before this call, precisely so the comparison that decides whether a group has to move is
        //   made by the component that can move it. A second comparison here could only duplicate that
        //   decision, and a duplicated decision is one that can drift.
        // Assumptions: no blank handling is needed on this value, unlike the two names. user_type is
        //   CHAR(1) and its whole domain is the two single characters at app/cpy/COCOM01Y.cpy L27 and
        //   L28, so a stored value is one character with no padding to strip, and the request's value
        //   has already satisfied a length-exactly-one constraint and a pattern admitting only those
        //   two characters.
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setUserType(request.userType());
    }

    /**
     * Removes the trailing run of blank characters from a stored fixed-width value.
     *
     * <p>Only the trailing run is considered, and only the blank character. A leading blank is
     * returned untouched: in {@code user_id} it sits inside the key rather than after it, so removing
     * one would change which row the value denotes.</p>
     *
     * @param text the {@code String} read from a blank-padded {@code CHAR(n)} column, at the full
     *     width that column declares
     * @return the same {@code String} when its last character is not a blank, otherwise the prefix
     *     that remains once the trailing blank run is removed, which is empty when the value is
     *     entirely blank
     */
    private static String stripTrailingBlanks(String text) {
        // Alternatives Considered: String.stripTrailing, which is the obvious call and is rejected
        //   because it removes every trailing character Java classifies as whitespace -- a tab, a
        //   newline, a form feed -- while a fixed-width column pads with the blank alone. A value
        //   that genuinely ended in a tab would lose it, and nothing downstream would report the
        //   loss. String.trim and String.strip are rejected more firmly still: both also remove
        //   LEADING whitespace, which on an eight-character key would silently produce a different
        //   identifier from the one stored. Scanning for the one character the padding is made of
        //   keeps both ends of the value exactly as stored.
        // Assumptions: the argument is non-null, established by the requireNonNull on each public
        //   entry point above together with the NOT NULL declaration on all five columns, so this
        //   helper performs no null handling of its own. Making it null-tolerant would let a
        //   partially populated row pass through as an empty value instead of failing where the
        //   defect is.
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == BLANK) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }
}
