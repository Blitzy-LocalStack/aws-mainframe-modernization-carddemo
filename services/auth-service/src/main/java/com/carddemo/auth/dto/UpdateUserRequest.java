package com.carddemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the values an existing user row may change, in the order the update screen validates them.
 *
 * <p>This is the body of the update operation the published contract declares as
 * {@code PUT /api/v1/auth/users/{userId}}, which requires the {@code carddemo-admin} authority and
 * answers with {@code com.carddemo.auth.dto.UserResponse}. Two of the three facts worth knowing
 * about this record are absences: it carries no identifier component and no credential component.
 * An absence documents nothing by itself, so each is argued at length below, and both arguments are
 * placed here because this is the only file in the service that states them.
 *
 * <p>Refactoring Rationale: this paragraph previously said that the four packages consuming this type
 * -- {@code com.carddemo.auth.api}, {@code com.carddemo.auth.service},
 * {@code com.carddemo.auth.mapper} and {@code com.carddemo.auth.domain} -- were "not yet authored",
 * and used that as the reason the two absences had to be argued here. All four exist, so the claim was
 * false and the reason it gave for the argument's placement no longer held. The reason is restated as
 * ownership rather than as absence: nothing on the path below has a natural place to explain why a
 * five-field reference map became a three-component record, because each participant sees only its own
 * end of the transformation. What is recorded here is the actual path, so a reader can follow the value
 * instead of inferring it.
 *
 * <h2>Who owns this type and what happens to an instance of it</h2>
 *
 * <p>Assumptions: exactly one production caller constructs an instance, and it is the framework rather
 * than any class in this service: {@code com.carddemo.auth.api.UserController} declares this record as
 * the request body of its update handler, so an instance is deserialised from JSON and bean-validated
 * before that handler is entered. Nothing in this service builds one, which is why this record declares
 * no factory and no builder.
 *
 * <p>Assumptions: the instance then travels one hop and stops. The handler passes it and the path
 * identifier to {@code com.carddemo.auth.service.UserService}, which loads the addressed row, applies
 * the ordered validation chain the annotations here cannot express -- see the section on what the
 * annotations decide -- and hands the record to
 * {@code com.carddemo.auth.mapper.UserMapper#applyUpdate(UpdateUserRequest, com.carddemo.auth.domain.User)}.
 * That mapper is the only place the three components are read individually, and it is where the storage
 * representation of each is decided. The record itself never reaches
 * {@code com.carddemo.auth.repository}, is never persisted, and is never returned: the response of the
 * update operation is {@code com.carddemo.auth.dto.UserResponse} built from the stored row after the
 * write, so a caller reads back what was stored rather than what it sent.</p>
 *
 * <h2>Where the three components come from, and why their order is not a style choice</h2>
 *
 * <p>Assumptions: the component order is the physical field order of the update-user map.
 * {@code app/cpy-bms/COUSR02.CPY} declares {@code USRIDINI PIC X(8)} at L60, which travels in the
 * request path for the reason given three sections down, {@code FNAMEI PIC X(20)} at L66,
 * {@code LNAMEI PIC X(20)} at L72, a credential field at L78 that this record omits, and
 * {@code USRTYPEI PIC X(1)} at L84. Striking the two non-body fields out of that sequence leaves
 * exactly the given name, the family name and the type, in that order. The reference program checks
 * the same five fields in the same sequence, in one {@code EVALUATE TRUE} whose branches test the
 * identifier at {@code app/cbl/COUSR02C.cbl} L180, the given name at L186, the family name at L192,
 * the credential at L198 and the type at L204, with their sentences at L182, L188, L194, L200 and
 * L206. That construct stops at its first matching branch, so map order decides which sentence a
 * caller who left several fields blank is told first, and a first sentence is an externally
 * observable interface under transformation rule T8. Reordering these components would change
 * observable behaviour rather than tidy a declaration, which is why the order is recorded here as a
 * contract and not left to look incidental.
 *
 * <p>Assumptions: the published contract states the same three-property order and the same
 * per-property provenance independently, so the order has two sources rather than one. Its update
 * request schema lists {@code firstName}, {@code lastName} and {@code userType} in that sequence,
 * requires all three, admits no further property, and cites {@code app/cbl/COUSR02C.cbl} line 188
 * as the check behind the first, line 194 behind the second and line 206 behind the third. Those are
 * the three surviving branches of the chain named above, in the order that chain runs them.
 *
 * <p>Assumptions: the widths come from the 80-byte security record, and its arithmetic is
 * self-checking. {@code app/cpy/CSUSR01Y.cpy} opens {@code 01 SEC-USER-DATA} at L17 and declares six
 * subordinate fields at L18 to L23: {@code SEC-USR-ID PIC X(08)} at byte position 0,
 * {@code SEC-USR-FNAME PIC X(20)} at 8, {@code SEC-USR-LNAME PIC X(20)} at 28,
 * {@code SEC-USR-PWD PIC X(08)} at 48, {@code SEC-USR-TYPE PIC X(01)} at 56 and
 * {@code SEC-USR-FILLER PIC X(23)} at 57. Those widths sum to 8 + 20 + 20 + 8 + 1 + 23, which is
 * exactly the 80 bytes of the whole record, so a component width disagreeing with this copybook is
 * wrong by construction rather than by opinion. Each retained width therefore has two independent
 * sources, the record above and the map named two paragraphs up, and neither is an inference from
 * the other.
 *
 * <h2>The credential is not carried forward</h2>
 *
 * <p>Refactoring Rationale: the reference stores and compares the credential in cleartext.
 * {@code app/cpy/CSUSR01Y.cpy} L21 declares {@code SEC-USR-PWD PIC X(08)} at byte position 48 of the
 * security record, and {@code app/cbl/COSGN00C.cbl} L223 tests {@code IF SEC-USR-PWD = WS-USER-PWD}
 * directly against the value read out of that record. The target encodes a different arrangement:
 * the Cognito user pool holds the credential and performs that comparison, while {@code auth.users}
 * keeps only {@code cognito_sub}, the subject reference the owning migration declares as the table's
 * fifth column. This record therefore declares no password component. The baseline does what L21 and
 * L223 describe, the Java encodes the arrangement just named, and the divergence is documented.
 *
 * <p>Refactoring Rationale: three of that arrangement's reference faces belong to this record's own
 * program and map, more than to any other type in this package, so they are named individually
 * rather than gestured at. First, {@code app/cbl/COUSR02C.cbl} L169 moves {@code SEC-USR-PWD} into
 * {@code PASSWDI OF COUSR2AI}: the stored cleartext value is written back onto the terminal every
 * time the screen loads a row. Second, L227 to L229 compares a submitted credential against the
 * stored one at L227, moves it into {@code SEC-USR-PWD} at L228 and sets {@code USR-MODIFIED-YES} at
 * L229, so the update path re-persists it. Third, the map declares both ends of that traffic,
 * {@code 02 PASSWDI PIC X(8).} at {@code app/cpy-bms/COUSR02.CPY} L78 for what is keyed in and
 * {@code 02 PASSWDO PIC X(8).} at L152 for what is written back. None of the three has a target
 * analogue: with no component to carry a credential there is nothing for L169 to echo, nothing for
 * L228 to move and no property for either map field to correspond to. The second half of that
 * follows and is stated so it is not assumed: the target returns no credential on any response
 * either, which is why {@code com.carddemo.auth.dto.UserResponse} carries none and why the read,
 * create and update operations can all answer with that one type.
 *
 * <p>Refactoring Rationale: one validation branch disappears with the component, and dropping it
 * without saying so is what Rule 1 forbids at its lines 38 to 41, which rule out leaving a
 * non-obvious choice undocumented when a reasonable alternative exists. This program's credential
 * branch is {@code app/cbl/COUSR02C.cbl} L198, testing {@code PASSWDI} against
 * {@code SPACES OR LOW-VALUES}, and it reports {@code 'Password can NOT be empty...'} at L200; the
 * add-user program's twin is {@code app/cbl/COUSR01C.cbl} L136 to L140, reporting the same sentence
 * at L138. Neither has a target analogue, and both are omitted for the one reason rather than two:
 * there is no component here for such a branch to test. The reasonable alternative was to keep a
 * password component and transform it inside this service, which is rejected because it would put a
 * credential back into this service's request bodies and require a column in {@code auth.users} to
 * receive one, reinstating two of the faces above in order to avoid one design decision.
 *
 * <p>Refactoring Rationale: the reference itself supplies the precedent for exactly this
 * composition, which makes the post-removal shape evidence rather than assertion.
 * {@code app/cbl/COUSR03C.cbl} L165 to L167 is a three-move echo -- {@code SEC-USR-FNAME} to
 * {@code FNAMEI} at L165, {@code SEC-USR-LNAME} to {@code LNAMEI} at L166 and
 * {@code SEC-USR-TYPE} to {@code USRTYPEI} at L167 -- and it moves nothing else, while
 * {@code app/cpy-bms/COUSR03.CPY} contains the string {@code PASSWD} zero times, so that map has no
 * field a credential could be written into at all. Those three moves are this record's three
 * components, in this record's order. Striking L78 and L152 out of the update map does not invent a
 * shape: it arrives at one that a neighbouring reference screen already used.
 *
 * <h2>The identifier addresses the row and does not travel in the body</h2>
 *
 * <p>Assumptions: {@code app/cpy-bms/COUSR02.CPY} L60 declares {@code 02 USRIDINI PIC X(8).}, the
 * field an operator keys to choose which row to edit, and three independent facts place it in the
 * request path rather than in this body. The first is that the reference never sends it back. The
 * load block at {@code app/cbl/COUSR02C.cbl} L166 to L172 moves exactly four values into the map,
 * the given name at L167, the family name at L168, the credential at L169 and the type at L170, and
 * it does not re-echo the identifier; the only traffic in that field runs the other way, at L162,
 * where the keyed value is moved into {@code SEC-USR-ID} to locate the record. A value the program
 * reads to find a row and never writes back is a selection key, not one of the values being edited.
 *
 * <p>Assumptions: the second fact is that the identifier is the primary key.
 * {@code app/cpy/CSUSR01Y.cpy} L18 places {@code SEC-USR-ID PIC X(08)} at byte position 0 of the
 * security record, and the {@code EXEC CICS WRITE} spanning {@code app/cbl/COUSR01C.cbl} L240 to
 * L248 names it as the record key twice over, at {@code RIDFLD (SEC-USR-ID)} on L244 and
 * {@code KEYLENGTH (LENGTH OF SEC-USR-ID)} on L245. That is why the owning migration declares
 * {@code user_id} as an eight-position character column and the table's primary key. A key is what a
 * request addresses, and an update addresses a row that already exists.
 *
 * <p>Assumptions: the third fact is the consequence of carrying it in both places, and it is the
 * reason this is not merely a matter of taste. A body property beside a path variable gives one
 * request two statements of which user it concerns, and nothing decides which wins when they differ:
 * the operation would have to arbitrate, and whichever way it arbitrated, a caller could address one
 * row in the path and name another in the body. The published contract records the same point on
 * this operation, that the path carries which row to change while the body carries only what may
 * change, so the identifier appears once per request and cannot be contradicted between the two.
 *
 * <p>Assumptions: the chain's first branch does not disappear with the component, it moves. The
 * blank check at {@code app/cbl/COUSR02C.cbl} L180, reporting {@code 'User ID can NOT be empty...'}
 * at L182, becomes a constraint on the path variable at the controller method rather than on this
 * body, where the contract declares it as a required path parameter of at most eight characters with
 * no character-class restriction. It is recorded here so that a later reader looking for the first
 * of the five reference branches finds where it went instead of concluding it was lost and
 * restoring a fourth component to hold it.
 *
 * <h2>Why this record is not unified with the create payload</h2>
 *
 * <p>Assumptions: the add screen and the update screen order their fields differently, and the
 * difference is visible in two maps of identical length. Both {@code app/cpy-bms/COUSR01.CPY} and
 * {@code app/cpy-bms/COUSR02.CPY} are 164 lines and both declare a field at L60, L66, L72, L78 and
 * L84, but the identifiers at the first three of those lines differ: L60, L66 and L72 are
 * {@code FNAMEI}, {@code LNAMEI} and {@code USERIDI} in the add map and {@code USRIDINI},
 * {@code FNAMEI} and {@code LNAMEI} in the update map, while L78 and L84 coincide. The two
 * validation chains follow their own maps exactly, the add chain testing in the sequence
 * {@code app/cbl/COUSR01C.cbl} L118, L124, L130, L136, L142 with sentences at L120, L126, L132, L138
 * and L144, and the update chain in the sequence {@code app/cbl/COUSR02C.cbl} L180, L186, L192,
 * L198, L204 with sentences at L182, L188, L194, L200 and L206. Since each chain latches its first
 * failure, each map's order fixes that operation's first sentence, and under transformation rule T8
 * that sentence is part of what a caller observes.
 *
 * <p>Refactoring Rationale: composing the two request bodies from a shared part is rejected in both
 * the forms it could take -- an {@code allOf} in the published contract, and a shared base record or
 * a shared interface here -- and the reason is a specific change in behaviour rather than a
 * preference about shape. The properties common to both bodies are the given name, the family name
 * and the type; the only property the create body carries and this one does not is the identifier. A
 * composed definition would therefore emit the three shared properties together and append that one
 * extra, producing the order given name, family name, type, identifier for create, which places the
 * type ahead of the identifier when {@code app/cbl/COUSR01C.cbl} tests the identifier at L130 and
 * the type at L142. A caller submitting a create request with both of those blank would then be told
 * about the type where the reference tells it about the identifier. A shared base record or a shared
 * interface between the two would force the same single order and carry the same consequence, so
 * this record declares no supertype of any kind, and the published contract declares two independent
 * request schemas with no {@code allOf} composing one from the other.
 *
 * <p>Assumptions: the two bodies also differ in membership, not only in order, so even setting the
 * ordering argument aside they are not one shape. The create body carries four properties where this
 * one carries three, and the difference is the identifier alone: create is where the key is assigned,
 * so it travels in that body, whereas update addresses an existing row and takes it from the request
 * path instead. A shared definition spanning both would therefore have to publish the identifier as
 * optional in order to fit this body, which would describe as optional a value a create request
 * cannot omit. The abstraction would also carry no weight in the other direction: a base record
 * holding only the intersection of the two would be the three components declared here, leaving the
 * create record to declare its extra one anyway, so the shared part would save nothing while
 * imposing the single order the paragraph above rejects.
 *
 * <h2>The change check survives; its credential arm does not</h2>
 *
 * <p>Assumptions: the reference refuses to rewrite a row that nothing altered, and that behaviour is
 * preserved even though no component of this record expresses it. After reading the stored row it
 * compares field by field and sets {@code USR-MODIFIED-YES} on each difference, at
 * {@code app/cbl/COUSR02C.cbl} L219 to L222 for the given name, L223 to L226 for the family name,
 * L227 to L229 for the credential and L231 to L234 for the type; it rewrites only inside
 * {@code IF USR-MODIFIED-YES} at L236, and takes the else branch at L237 when nothing differed,
 * writing {@code 'Please modify to update ...'} at L239 and a red attribute into the message field
 * at L241. The colour is what makes that outcome a refusal rather than advice: this one program
 * writes the same message field in three colours, red at L241, neutral at L338 and green at L371, so
 * the target reports the no-change case as a 400 carrying that sentence. The comparison itself moves
 * to {@code com.carddemo.auth.service}, which is the only layer that can perform it, because it
 * needs the stored row and a request body cannot see one.
 *
 * <p>Assumptions: two things follow, and both are absences. No component records whether the row was
 * altered -- there is no {@code modified}, {@code dirty} or {@code changed} property -- because a
 * body cannot assert a fact about a row it has not read, and a caller-supplied flag would be a
 * second, unverifiable answer to a question the service already answers by comparing. And no
 * component carries the L239 sentence: it and every other failure text travel in
 * {@code com.carddemo.common.error.ApiError}, so that one response shape reports this outcome
 * alongside every constraint failure rather than each operation inventing its own carrier.
 *
 * <p>Assumptions: the credential arm at L227 to L229 is the one part of that check with no target
 * analogue, and the reason is the same as in the credential section above rather than a new one:
 * there is no submitted credential to compare and no stored credential to compare it against, since
 * {@code auth.users} has no column for one. Three arms of the reference check survive as comparisons
 * over three components; the fourth has nothing at either end.
 *
 * <h2>The one-character type, and the three places its domain is enforced</h2>
 *
 * <p>Assumptions: {@code app/cpy/COCOM01Y.cpy} is the sole authority for the two admissible values.
 * Its L26 declares {@code CDEMO-USER-TYPE PIC X(01)} and its L27 and L28 name the two condition
 * values as the quoted literals {@code 'A'} for administrator and {@code 'U'} for ordinary user.
 * Neither of the two obvious alternative sources can settle the domain.
 * {@code app/cpy/CSUSR01Y.cpy} L22 declares the same one-character width but carries no
 * {@code 88}-level at all, so it fixes the width and names no value; and this record's own program
 * tests its type field for blankness only, at {@code app/cbl/COUSR02C.cbl} L204, so it too names no
 * value. Citing either for the domain would attribute a value set to a line that does not contain
 * one.
 *
 * <p>Assumptions: asserting membership here is a narrowing of what the reference accepted at its own
 * transport edge, and it is recorded as such rather than presented as equivalent. Because L204 tests
 * only for blankness, the reference screen would pass any single non-blank character through to
 * storage, and the file it wrote to declared no value constraint either. The target refuses a third
 * value at three separate points instead: here, by the pattern on {@code userType}, which is the
 * form a caller reads from the published contract as a two-member enumeration; again in
 * {@code com.carddemo.auth.service}, whose ordered chain decides which field reports first; and last
 * in the database, by {@code CHECK (user_type IN ('A','U'))} on {@code auth.users}, which holds for
 * every write path including one that never passes through this service. The reference had no
 * equivalent of that third point at all, which is the substance of the narrowing. Removing any one
 * of the three leaves a route by which a third value reaches storage.
 *
 * <p>Trade-offs: {@code userType} stays a one-character {@code String} rather than becoming a named
 * type over the two values. The declared width is part of what a caller sees --
 * {@code SEC-USR-TYPE PIC X(01)} occupies one position and the published contract declares a minimum
 * length of one, a maximum length of one and the two-member enumeration -- and a one-character string
 * reproduces all of that without introducing a type name the contract does not carry. What is given
 * up is real: there is no compile-time exhaustiveness check, so a handler branching on this component
 * gets no complaint from the compiler when it omits one of the two cases. That is accepted because
 * the domain is refused at the three points just named, so an out-of-domain value never reaches such
 * a handler to fall through it.
 *
 * <p>Assumptions: changing this component is the one edit on this operation with an authorisation
 * consequence, which the published contract also records at the property. Moving a row from
 * {@code 'U'} to {@code 'A'} grants that user the authority every operation on this path itself
 * requires, and moving it the other way removes it. No component is added for that: the effect
 * belongs to the value, and the check that the caller may make it belongs to the operation.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: the trailing padding is dropped. {@code SEC-USR-FILLER PIC X(23)} at
 * {@code app/cpy/CSUSR01Y.cpy} L23, byte position 57, carries the security record out to its declared
 * length of 80 bytes. No program and no map addresses it, so it holds no value a caller could supply,
 * and reproducing it would put a component on the wire that means nothing at either end.
 * Transformation rule T1 drops {@code FILLER} and records the drop per record; this paragraph is that
 * record for this one.
 *
 * <p>Assumptions: no subject reference component. {@code auth.users} keeps {@code cognito_sub} as its
 * fifth column, declared unique and not null, but the value is produced when this service provisions
 * the pool account and is read back out of the provider's response, so it is an output of creating a
 * user rather than an input to any operation. It appears on {@code com.carddemo.auth.dto.UserResponse}
 * and is a component of neither request record. Accepting it here would be worse than redundant: the
 * subject is the only link between a presented claim and this row, so a caller able to submit one on
 * an update would be a caller able to repoint an existing row at a different pool identity, and no
 * check on the body could detect it because a well-formed identifier is the whole of the shape such a
 * property would have to satisfy.
 *
 * <p>Assumptions: no message component, and no length-clamping path either. The reference message
 * field is {@code ERRMSGI PIC X(78)} in all five auth maps -- {@code app/cpy-bms/COSGN00.CPY} L84,
 * {@code app/cpy-bms/COUSR00.CPY} L372, {@code app/cpy-bms/COUSR01.CPY} L90,
 * {@code app/cpy-bms/COUSR02.CPY} L90 and {@code app/cpy-bms/COUSR03.CPY} L84 -- and 78 is the width
 * in every one of them, while the longest text those five programs emit is the 44 characters of
 * {@code 'You are already at the bottom of the page...'} at {@code app/cbl/COUSR00C.cbl} L273, so the
 * declared width is never approached and a truncating path would have nothing to do. The success text
 * this operation produced is composed rather than stored: {@code app/cbl/COUSR02C.cbl} L372 to L375
 * strings {@code 'User '}, the identifier trimmed at its first space, and
 * {@code ' has been updated ...'} into one sentence, the literal sitting on L374. That sentence is
 * not returned as a component either; the browser client holds every user-visible string in its own
 * catalog and composes it from the identifier it already put in the request path, so a
 * server-rendered copy would be a second source for one string.
 *
 * <p>Assumptions: four further reference surfaces stop at the service boundary. No component carries
 * a colour, a severity or a tone, even though this program genuinely varies all three -- red at
 * {@code app/cbl/COUSR02C.cbl} L241, neutral at L338 and green at L371 -- because those are attribute
 * bytes of a 3270 display and the distinction they draw is carried instead by the HTTP status and by
 * the shape of the response body. No component carries the transaction monitor's response or reason
 * codes; this program does write them to a display stream, at the live diagnostic lines L347 and L384,
 * and that is a fact about those two sites rather than a general rule, but a data-store internal has
 * no place in a request body. The head block every map opens with, transaction name, two title lines,
 * date and time, is screen chrome and is excluded entirely. And no component describes a position in a
 * result set, because this record addresses one row; the page envelope this migration uses is declared
 * once in {@code com.carddemo.common.web} and carries {@code com.carddemo.auth.dto.UserSummary}, not
 * this type.
 *
 * <p>Assumptions: no timestamp component, and the reason is a property of the target data model rather
 * than an omission here. The owning migration declares five columns and none of them is a created,
 * updated or audit column, so a successful update leaves no record in this schema of when it happened.
 * Adding a component to carry one would put a value on the wire that nothing stores and that no caller
 * could verify afterwards; if such a trace is wanted it belongs in the migration first, and this
 * paragraph is where a reader learns it is absent by design.
 *
 * <h2>What the annotations decide, and what they do not</h2>
 *
 * <p>Assumptions: these constraints are a transport-level guard on the shape of the body, and they are
 * not the authority on the order failures are reported in. The ordered chain that reproduces the
 * reference's latching sequence lives in {@code com.carddemo.auth.service}, and it has to, because
 * that sequence is not expressible as a list of body properties: its first branch,
 * {@code app/cbl/COUSR02C.cbl} L180 with its sentence at L182, tests the identifier, which on this
 * operation is a path variable and not a property of this body at all. Declaration order in this file
 * therefore records provenance, per the first section above; it does not drive response order, and a
 * reader should not infer that moving an annotation changes which sentence a caller sees first.
 *
 * <p>Alternatives Considered: implementing {@code com.carddemo.common.error.FieldOrdering}, the opt-in
 * interface a request record uses to declare its own screen's check order so the shared advice latches
 * the first entry's sentence. The sign-on body does declare it, which makes the omission here worth
 * explaining rather than assuming, and the create body declines it for its own reason. It is not
 * adopted because it would state only part of this operation's order: the interface takes body
 * property names, and the first of the five reference branches tests a path variable, so the sequence
 * would live partly in a name list here and partly at the controller that validates the path. Keeping
 * the whole sequence in one place leaves one thing to read when the reported order is in question. The
 * consequence of declining it is registered rather than hidden: without the interface the shared advice
 * accumulates every field entry instead of latching one, which is the divergence the register records
 * as {@code D-ERROR-ACCUMULATION}, and it applies to this body.
 *
 * <p>Assumptions: a blank check and a non-blank annotation match exactly, which is why no custom
 * predicate is written for the two names. Each reference branch tests its field against
 * {@code SPACES OR LOW-VALUES} -- a fixed-width screen field arrives filled with blanks when the user
 * types nothing and with low values when the map was never populated -- and the non-blank annotation
 * refuses an absent value, an empty string and a string of whitespace alike, so the three states the
 * reference treats as empty are the three it refuses.
 *
 * <p>Assumptions: that choice agrees with the published contract exactly, which is worth stating
 * because the agreement rests on two facets working together rather than on one. The update schema
 * declares a minimum length of one on each name and, beside it, a pattern requiring at least one
 * non-whitespace character; a minimum length of one on its own would admit a name of twenty spaces,
 * which the reference branches at {@code app/cbl/COUSR02C.cbl} L186 and L192 treat as blank. With both
 * facets present the schema and this type refuse the same set of values, so no caller can satisfy the
 * published shape and then be refused here.
 *
 * <p>Alternatives Considered: a partial-update shape, in which all three components are optional and a
 * caller sends only what it means to alter. It is rejected because it cannot express the distinction
 * the reference relies on. The update screen loads the whole set at
 * {@code app/cbl/COUSR02C.cbl} L166 to L172 and resubmits the whole set, and its branches at L186, L192
 * and L204 each treat a blank field as an error rather than as an instruction to leave the stored value
 * alone; with optional components, an omitted property and a property cleared to blanks would arrive as
 * the same request, so the operation would have to choose between refusing a clear and silently
 * ignoring one. Requiring all three on every call keeps the two cases distinct: a value present and
 * blank is refused, exactly as L186, L192 and L204 refuse it, and there is no third state to interpret.
 *
 * <p>Assumptions: no value is normalised on the way in. Nothing here trims a submitted string, folds
 * its case or pads it to the column width. The reference has no analogue to trim, since a fixed-width
 * screen field is padded by the terminal rather than by the user, and the case-sensitive type domain is
 * load-bearing: a COBOL condition name compares the bytes of its field against the literal it was
 * declared with, so {@code CDEMO-USRTYP-ADMIN} at {@code app/cpy/COCOM01Y.cpy} L27 is satisfied by
 * {@code 'A'} and not by its lower-case form. Folding case here would let this type accept a value the
 * reference condition refuses, and the database check on {@code auth.users} would then reject on write
 * what this contract had published as valid. Padding to the column width belongs to
 * {@code com.carddemo.auth.mapper}, which is where the storage representation is this context's
 * concern; a request arrives unpadded and is not the place to introduce it.
 *
 * <p>Trade-offs: the type component carries three constraints where two would refuse the same values,
 * and the cost is paid deliberately. A value of two characters breaches both the exact-length
 * constraint and the pattern, and an empty string breaches the non-blank constraint as well, so one
 * mistake can produce two or three entries in the per-field list, each naming {@code userType}, because
 * that list holds one entry per violation rather than one per component. The alternative was to keep
 * the pattern alone, which already admits exactly one character and so implies the length. It is not
 * taken because the published contract declares a minimum length of one, a maximum length of one and
 * the enumeration as three separate facets, and these annotations are what the contract document is
 * generated from; asserting only the pattern would publish two length facets that this type does not
 * enforce, leaving the contract describing a stricter shape than the code checks.
 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: no executable oracle exists for this bounded context, so every obligation below
 * falls on assertions against cited lines rather than against a reference run. All five auth programs
 * are online programs written against the transaction monitor's command-level interface, and
 * {@code tests/README.md} records at its lines 83 to 85 that such programs cannot run end to end
 * without a CICS runtime, which is absent on the runner. Nothing stated in this file can therefore be
 * settled by running a reference program or by comparing a reference screen, and every width, order and
 * admissible value here is instead verifiable by reading the cited file at the cited line, which is why
 * the citations are exact.
 *
 * <p>Assumptions: six assertions are required of whatever tests this record. That it declares exactly
 * these three components in exactly this order, matching the three properties of the published update
 * schema. That it declares neither an identifier component nor a credential component, which are the
 * two assertions that would fail first if either absence were ever undone. That it declares no
 * supertype, and that its component order and membership differ from the create body's, which is the
 * assertion that would fail first if the composition rejected above were introduced. That a
 * 21-character name is refused while a 20-character one is accepted, for each of the two names
 * separately, pinning the bound at the width {@code app/cpy/CSUSR01Y.cpy} L19 and L20 declare rather
 * than one position either side of it. That {@code userType} admits {@code 'A'} and {@code 'U'} and
 * refuses everything else, which means an absent value, an empty string, a blank, the lower-case form
 * of either letter and the two letters together are each rejected. And that the string form renders
 * neither name, stated as an assertion about the rendered text rather than about the override's
 * presence, because a rendering that named a component and then printed its value would satisfy the
 * weaker check while disclosing exactly what the stronger one forbids.
 *
 * <p>Assumptions: each tag below names its Java type in prose, and the redundancy with the
 * declaration is deliberate rather than something to tidy away. Javadoc has no type slot in a
 * {@code @param} tag, so prose is the only place the type element of Rule 1's parameter clause can
 * be satisfied for this language, and {@code docs/CODE_DOCUMENTATION_STANDARD.md} has already ruled
 * that that element is addressed to the documentation and is not qualified by language, withdrawing
 * an earlier wording that excused it wherever a signature states the same fact more reliably.
 * Dropping the type from a tag here would leave the clause two thirds satisfied while every
 * mechanical gate still reported a pass, which is the shape of failure Rule 1's validation clause
 * exists to catch.
 *
 * @param firstName the user's given name as it is to stand after the update, a {@code String} of at
 *     most 20 characters, as {@code SEC-USR-FNAME PIC X(20)} declares at
 *     {@code app/cpy/CSUSR01Y.cpy} L19, byte position 8 of the 80-byte security record, and as
 *     {@code FNAMEI PIC X(20)} presents it at {@code app/cpy-bms/COUSR02.CPY} L66. It is required
 *     and must not be blank, and it is the first of the three checked by the reference chain, at
 *     {@code app/cbl/COUSR02C.cbl} L186, which is why it is the first component declared here
 * @param lastName the user's family name as it is to stand after the update, a {@code String} of at
 *     most 20 characters, as {@code SEC-USR-LNAME PIC X(20)} declares at
 *     {@code app/cpy/CSUSR01Y.cpy} L20, byte position 28 of that record, and as
 *     {@code LNAMEI PIC X(20)} presents it at {@code app/cpy-bms/COUSR02.CPY} L72. It is required
 *     and must not be blank, and it is checked second, at {@code app/cbl/COUSR02C.cbl} L192; it is a
 *     second component of the same declared width as the given name rather than a longer or shorter
 *     one
 * @param userType the role the submitter is ASKING for, which is not the same claim the two names
 *     above carry and the difference is load-bearing. A {@code String} of exactly one character, as
 *     {@code SEC-USR-TYPE PIC X(01)} declares at {@code app/cpy/CSUSR01Y.cpy} L22, byte position 56 of
 *     that record, and as {@code USRTYPEI PIC X(1)} presents it at {@code app/cpy-bms/COUSR02.CPY} L84.
 *     It is {@code 'A'} for administrator or {@code 'U'} for ordinary user and nothing else, per
 *     {@code app/cpy/COCOM01Y.cpy} L26 to L28, which is the sole authority for that domain. It is
 *     required and must not be blank, and is checked last of the three at
 *     {@code app/cbl/COUSR02C.cbl} L204. Where the names stand as submitted once the update is applied,
 *     this value stands only if the identity provider's group membership moved with it: the authority a
 *     request is matched against is derived from the signed {@code cognito:groups} claim, not from
 *     {@code auth.users.user_type}, so this component is a request for an authority change and not a
 *     description of one. {@code com.carddemo.auth.mapper.UserMapper} refuses to apply a value here that
 *     differs from the row's, and {@code com.carddemo.auth.service.IdentitySyncService} is what moves
 *     both together
 */
// WHY : Refactoring Rationale: the two name components publish a non-whitespace pattern into the
//       GENERATED document beside their non-blank constraint, and the review that required it named this
//       record specifically. The committed update schema declares that facet on both names; the document
//       generated from these annotations did not, because a non-blank constraint renders as a minimum
//       length and nothing else -- so the served description of this body admitted a name of twenty
//       spaces that this record refuses. It is a schema-documentation annotation and not a second runtime
//       constraint, because the non-blank constraint already refuses exactly those values and a @Pattern
//       would report one fault twice. The expression is declared once, on
//       SignOnRequest.NON_WHITESPACE_PATTERN, where the full argument is recorded.
// WHY : Assumptions: userType takes no such pattern, matching both the committed schema's own note on
//       the same property and the sibling create body. Its domain constraint admits exactly "A" and
//       "U", so a presence pattern would restate a rule the domain states more precisely.
public record UpdateUserRequest(
        @NotBlank(message = MESSAGE_FIRST_NAME_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = NAME_MAX_LENGTH, message = MESSAGE_FIRST_NAME_TOO_LONG) String firstName,
        @NotBlank(message = MESSAGE_LAST_NAME_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = NAME_MAX_LENGTH, message = MESSAGE_LAST_NAME_TOO_LONG) String lastName,
        @NotBlank(message = MESSAGE_USER_TYPE_REQUIRED)
        @Size(min = USER_TYPE_LENGTH, max = USER_TYPE_LENGTH,
                message = MESSAGE_USER_TYPE_LENGTH)
        @Pattern(regexp = USER_TYPE_DOMAIN, message = MESSAGE_USER_TYPE_DOMAIN) String userType) {

    /**
     * The number of positions the reference declares for each of the two name fields.
     *
     * <p>Assumptions: 20 is read from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy} L19 and from {@code SEC-USR-LNAME PIC X(20)} at L20, which declare
     * the same width, and the update-user map repeats it for both fields at
     * {@code app/cpy-bms/COUSR02.CPY} L66 and L72. One constant serves both components because the
     * reference gives them one width, not because the two happen to agree today.
     *
     * <p>Alternatives Considered: writing 20 straight into each annotation. Rejected because two
     * distinct widths govern this record's three character components, 20 here and 1 for the type,
     * and a bare number inside an annotation is the one place a reader cannot tell which copybook
     * line it was read from. A named constant leaves exactly one line to check against L19 and L20.
     *
     * <p>Trade-offs: sharing one constant across the two name components couples them, so a change
     * to one name width alone would have to split this constant rather than edit it. That is accepted
     * because the coupling is the contract: L19 and L20 are two fields of one 80-byte record whose
     * widths sum with the others to exactly that length, so changing one of them alone would break
     * that arithmetic rather than be absorbed by it.
     */
    private static final int NAME_MAX_LENGTH = 20;

    /**
     * The number of positions the reference declares for the user type, used as both bounds.
     *
     * <p>Assumptions: 1 is read from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy}
     * L22 and from {@code USRTYPEI PIC X(1)} at {@code app/cpy-bms/COUSR02.CPY} L84. It is applied as
     * the minimum and the maximum of one size constraint because the published contract declares a
     * minimum length of one and a maximum length of one as two separate facets, and one constant used
     * twice keeps those two facets from drifting apart into two numbers that could disagree.
     */
    private static final int USER_TYPE_LENGTH = 1;

    /**
     * The expression the submitted user type has to match in full.
     *
     * <p>Assumptions: the two admitted characters come from {@code app/cpy/COCOM01Y.cpy} L27 and L28,
     * which name them as the quoted literals {@code 'A'} and {@code 'U'} under the
     * {@code CDEMO-USER-TYPE PIC X(01)} declared at L26. They are written as an explicit
     * two-character class rather than as a wider alternation, so the domain this constant describes
     * cannot quietly widen. No anchor is written because none is needed: a pattern constraint is
     * satisfied only when the whole value matches, so a one-character class admits exactly one
     * character and refuses the two letters together without one.
     *
     * <p>Assumptions: the expression is case-sensitive, and that is load-bearing. A COBOL condition
     * name compares the bytes of its field against the literal it was declared with, so
     * {@code CDEMO-USRTYP-ADMIN} at L27 is satisfied by {@code 'A'} and not by its lower-case form.
     * Admitting the lower-case letters would let this type accept values the reference condition
     * refuses, and the check constraint on {@code auth.users} would then reject on write what this
     * contract had published as valid.
     */
    private static final String USER_TYPE_DOMAIN = "[AU]";

    /**
     * The sentence reported when the given name is absent or blank.
     *
     * <p>Assumptions: the text is carried across character for character from
     * {@code app/cbl/COUSR02C.cbl} L188, the sentence the reference reports when its L186 branch
     * finds {@code FNAMEI} equal to {@code SPACES OR LOW-VALUES}. Transformation rule T8 takes the
     * literal as it is written, which includes the ellipsis with no space before it. Ellipsis spacing
     * in this program is per-literal rather than uniform, and the contrast sits inside the one
     * program: this sentence and its two siblings below have no space before the ellipsis, while the
     * no-change sentence at L239 and the save prompt at L336 each carry one. Neither form is
     * normalised toward the other.
     *
     * <p>Alternatives Considered: leaving the validation provider's default, which reads "must not be
     * blank". Rejected because the reference sentence is a user-visible string that rule T8 requires
     * be preserved, and a caller that saw the provider default would receive different text for the
     * same condition than the screen this operation replaces reported.
     */
    private static final String MESSAGE_FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /**
     * The sentence reported when the family name is absent or blank.
     *
     * <p>Assumptions: carried across character for character from {@code app/cbl/COUSR02C.cbl} L194,
     * the sentence reported by the L192 branch, on the same grounds as the given name above. It is a
     * separate constant rather than a shared one because the two sentences differ in their leading
     * words, so a single shared sentence would name the wrong field in one of the two responses.
     */
    private static final String MESSAGE_LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /**
     * The sentence reported when the user type is absent or blank.
     *
     * <p>Assumptions: carried across character for character from {@code app/cbl/COUSR02C.cbl} L206,
     * the sentence reported by the L204 branch. That branch tests for blankness only, so this
     * sentence covers exactly the absent case and says nothing about which values are admissible; the
     * two sentences below cover the length and the domain, neither of which the reference screen
     * checked.
     */
    private static final String MESSAGE_USER_TYPE_REQUIRED = "User Type can NOT be empty...";

    /**
     * The sentence reported when the given name exceeds its declared width.
     *
     * <p>Assumptions: this sentence has no reference counterpart, and the absence is not an
     * oversight. The reference collects the given name in {@code FNAMEI PIC X(20)} at
     * {@code app/cpy-bms/COUSR02.CPY} L66, a fixed-width screen field that cannot physically hold a
     * twenty-first character, so the program has no over-length branch and there is no literal to
     * carry across under rule T8. The condition exists only because a JSON body has no such physical
     * bound, so the sentence is authored for the target rather than reproduced from the reference.
     *
     * <p>Assumptions: the authored sentence follows the ellipsis idiom of the three branch sentences
     * it sits beside, with no space before the ellipsis, so that a caller receiving a mixture of
     * carried and authored sentences in one response sees one idiom rather than two.
     *
     * <p>Alternatives Considered: leaving the provider default, which reads "size must be between 0
     * and 20". Rejected because it states a lower bound of zero that the non-blank constraint beside
     * it contradicts, so a caller reading it would be told an empty value is acceptable in the same
     * response that refuses one. Alternatives Considered: reusing the absence sentence above.
     * Rejected because it would tell a caller that submitted twenty-one characters that it submitted
     * none.
     */
    private static final String MESSAGE_FIRST_NAME_TOO_LONG =
            "First Name must be at most " + NAME_MAX_LENGTH + " characters...";

    /**
     * The sentence reported when the family name exceeds its declared width.
     *
     * <p>Assumptions: authored for the same reason as the given name's over-length sentence above,
     * since {@code LNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR02.CPY} L72 is likewise a fixed-width
     * screen field with no over-length branch behind it. It names the family name rather than sharing
     * one sentence with the given name, so that a caller is told which of the two fields it was.
     */
    private static final String MESSAGE_LAST_NAME_TOO_LONG =
            "Last Name must be at most " + NAME_MAX_LENGTH + " characters...";

    /**
     * The sentence reported when the user type is not exactly one character long.
     *
     * <p>Assumptions: authored, because the reference screen field {@code USRTYPEI PIC X(1)} at
     * {@code app/cpy-bms/COUSR02.CPY} L84 holds one character by construction and the program's only
     * check on it, at {@code app/cbl/COUSR02C.cbl} L204, tests for blankness. The length is written
     * as a word rather than interpolated from {@code USER_TYPE_LENGTH}, because a sentence reading
     * "exactly 1 character" would be the one place in this file where a number is rendered where a
     * word is what a reader expects, and the constant is already the single source for the two bounds
     * the constraint asserts.
     */
    private static final String MESSAGE_USER_TYPE_LENGTH =
            "User Type must be exactly one character...";

    /**
     * The sentence reported when the user type is one character but not an admitted one.
     *
     * <p>Assumptions: authored, and it is the sentence that reports the narrowing this record applies
     * to the reference's transport-level acceptance. The reference screen checked its type field for
     * blankness only at {@code app/cbl/COUSR02C.cbl} L204 and would pass any single non-blank
     * character to storage, so there is no reference sentence for an out-of-domain value to carry
     * across. The two letters are named in the sentence, taken from {@code app/cpy/COCOM01Y.cpy} L27
     * and L28, so a caller learns the whole domain from the refusal rather than having to read the
     * contract to discover it.
     */
    private static final String MESSAGE_USER_TYPE_DOMAIN = "User Type must be A or U...";

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Assumptions: the literal deliberately matches the one
     * {@code com.carddemo.auth.dto.CreateUserRequest}, {@code com.carddemo.auth.dto.UserResponse} and
     * {@code com.carddemo.auth.dto.UserSummary} use for their name components, so a log store holding
     * lines from several of these types shows one placeholder vocabulary rather than four. The
     * constant is named for the class of data it hides rather than for the literal, because a reader
     * of this file needs to know which components are withheld and a reader of a log line needs only
     * to know that something was.
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Renders this request for diagnostics with both names withheld.
     *
     * <p>Refactoring Rationale: the string form a record generates for itself lists every component
     * beside its value, which on this record means the given name and family name of a real person. A
     * request body is stringified in exactly the places that happens without anyone intending it: the
     * framework includes the bound target in the message of the exception it raises when a constraint
     * on that target fails, so the ordinary outcome of a caller mistyping the type field would be a
     * log line carrying both names in full. This override keeps the generated form's shape and its
     * component order and substitutes {@code REDACTED_PERSONAL} for the two.
     *
     * <p>Trade-offs: one component of three prints in full, and this record has no row locator to
     * print beside it. {@code userType} prints because it is a two-valued role classification
     * carrying no personal content, and it is the single most useful value when a role-related
     * refusal is being traced. The identifier that {@code com.carddemo.auth.dto.CreateUserRequest}
     * prints for correlation is not available here, because on this operation it is a path variable
     * rather than a component, so what identifies the row in a diagnostic record is the request path
     * and the correlation identifier that
     * {@code com.carddemo.common.error.ApiError} carries alongside it. What is given up is the
     * ability to tell two instances apart from their printed form alone: two updates submitting the
     * same role print identically. That is accepted rather than closed by printing a name, because
     * the name is the value this method exists to withhold.
     *
     * <p>Assumptions: only the string form is narrowed. The component accessors are untouched and so
     * is the generated equality behaviour, so the values a caller submitted still reach the service
     * and the mapper in full. The exposure being closed is incidental stringification, not the
     * deliberate act of reading a component that was submitted on purpose.
     *
     * @return a {@code String} holding a single-line description of this record that names every
     *     component in contract order, in which the given name and the family name are each
     *     represented by a placeholder and never rendered; never {@code null}
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order
        //   included, so that a reader who knows what a record prints is not led to think some other
        //   type produced this line. Only the two withheld values depart from it.
        return "UpdateUserRequest[firstName=" + REDACTED_PERSONAL
                + ", lastName=" + REDACTED_PERSONAL
                + ", userType=" + userType
                + "]";
    }
}
