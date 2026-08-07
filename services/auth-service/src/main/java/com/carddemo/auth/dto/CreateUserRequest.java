package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the values a new user row is created from, in the order the add-user screen validates them.
 *
 * <h2>Where the four components come from, and why their order is not a style choice</h2>
 *
 * <p>Assumptions: the component order is the physical field order of the add-user map.
 * {@code app/cpy-bms/COUSR01.CPY} declares {@code FNAMEI PIC X(20)} at L60,
 * {@code LNAMEI PIC X(20)} at L66, {@code USERIDI PIC X(8)} at L72, a credential field at L78 that
 * this record omits for the reason given below, and {@code USRTYPEI PIC X(1)} at L84. The reference
 * program checks those fields in exactly that sequence, in one {@code EVALUATE TRUE} whose branches
 * test the given name at {@code app/cbl/COUSR01C.cbl} L118, the family name at L124, the identifier
 * at L130, the credential at L136 and the type at L142, with their sentences at L120, L126, L132,
 * L138 and L144. That construct stops at its first matching branch, so map order decides which
 * sentence a caller who left several fields blank is told first, and a first sentence is an
 * externally observable interface under transformation rule T8. Reordering these components would
 * change observable behaviour rather than tidy a declaration, which is why the order is recorded
 * here as a contract rather than left to look incidental.
 *
 * <p>Assumptions: the widths come from the 80-byte security record, and its arithmetic is
 * self-checking. {@code app/cpy/CSUSR01Y.cpy} opens {@code 01 SEC-USER-DATA} at L17 and declares six
 * subordinate fields at L18 to L23: {@code SEC-USR-ID PIC X(08)} at byte position 0,
 * {@code SEC-USR-FNAME PIC X(20)} at 8, {@code SEC-USR-LNAME PIC X(20)} at 28,
 * {@code SEC-USR-PWD PIC X(08)} at 48, {@code SEC-USR-TYPE PIC X(01)} at 56 and
 * {@code SEC-USR-FILLER PIC X(23)} at 57. Those widths sum to 8 + 20 + 20 + 8 + 1 + 23, which is
 * exactly the 80 bytes of the whole record, so a component width disagreeing with this copybook is
 * wrong by construction rather than by opinion. Each retained width therefore has two independent
 * sources, the record above and the map named in the preceding paragraph, and neither is an
 * inference from the other.
 *
 * <p>Assumptions: one reference program uses two different field orders for two different purposes,
 * and both are authoritative for the thing they govern. The validation chain at
 * {@code app/cbl/COUSR01C.cbl} L118 to L142 runs in screen order, the given name first. The
 * persistence block at L154 to L158 runs in record order, moving {@code USERIDI} into
 * {@code SEC-USR-ID} first at L154 and so following {@code app/cpy/CSUSR01Y.cpy} L18. This record is
 * the transport surface a caller fills in, so it takes the screen order; {@code UserResponse} is a
 * projection of the stored row, so it takes the record order, which is also the column order the
 * owning schema migration declares for {@code auth.users}. The two orders are stated together here
 * because they look like an inconsistency between two sibling records and are not one: harmonising
 * them would silence the first-error contract on one side or the column correspondence on the other.
 *
 * <p>Assumptions: every component of this record is placed by a map line, so the screen order is
 * total and nothing is appended after it. That was not always so: a fifth component named
 * {@code cognitoSub} sat at the end precisely because it had no map line, and it has been withdrawn
 * for the reason recorded under the subject reference below.
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
 * <p>Refactoring Rationale: this record's own face of that arrangement is the create path, and it is
 * named rather than left implicit. {@code app/cbl/COUSR01C.cbl} L157 moves
 * {@code PASSWDI OF COUSR1AI} into {@code SEC-USR-PWD}, and the {@code EXEC CICS WRITE} spanning
 * L240 to L248, with {@code RIDFLD (SEC-USR-ID)} at L244, then writes all 80 bytes including that
 * one. So the create operation is where the cleartext value entered storage in the first place, and
 * it has no target analogue: with no component to carry a credential, this record supplies nothing
 * for such a move to read. The map field it would have been collected in is
 * {@code app/cpy-bms/COUSR01.CPY} L78, {@code 02 PASSWDI PIC X(8).}, and it is cited explicitly so
 * that its absence from the four components reads as a decision and not as a field overlooked while
 * transcribing a five-field map into a four-component record.
 *
 * <p>Refactoring Rationale: one validation branch disappears with the component, and dropping it
 * silently is what Rule 1 forbids at its lines 38 to 41, which rule out leaving a non-obvious choice
 * undocumented when a reasonable alternative exists. {@code app/cbl/COUSR01C.cbl} L136 to L140 tests
 * the credential field for blankness and reports {@code 'Password can NOT be empty...'} at L138;
 * there is nothing here for that branch to test, so it has no target analogue, and the same holds
 * for its twin in the update chain at {@code app/cbl/COUSR02C.cbl} L200. Two further reference sites
 * fall away with it: L169 of that program moves the stored credential back onto the update screen,
 * and L227 to L229 compares a submitted credential against the stored one and marks the row modified
 * when they differ. The reasonable alternative was to keep a password component here and hash it
 * locally, which is rejected because it would put a credential back into this service's request
 * bodies and require a column in its schema to receive one, reinstating two of the four faces above
 * in order to avoid one design decision.
 *
 * <p>Refactoring Rationale: the reference itself supplies the precedent for a user-administration
 * surface without the credential, which makes this shape evidence rather than assertion.
 * {@code app/cbl/COUSR03C.cbl} L165 to L167 is a three-move echo, {@code SEC-USR-FNAME} to
 * {@code FNAMEI}, {@code SEC-USR-LNAME} to {@code LNAMEI} and {@code SEC-USR-TYPE} to
 * {@code USRTYPEI}, and it moves nothing else, while {@code app/cpy-bms/COUSR03.CPY} contains the
 * string {@code PASSWD} zero times, so that map has no field a credential could be written into. One
 * of the five reference screens already worked without it.
 *
 * <p>Assumptions: no credential is created anywhere on this path, and no subject reference is
 * accepted on it either. This service provisions the pool account itself and reads the subject back
 * from the provider's own response, so the subject is an OUTPUT of creating a user and never an input
 * to it; it appears on {@code UserResponse} and not here.
 *
 * <h2>Why this record is not unified with the update payload</h2>
 *
 * <p>Assumptions: the add screen and the update screen order their fields differently, and the
 * difference is visible in two maps of identical length. Both {@code app/cpy-bms/COUSR01.CPY} and
 * {@code app/cpy-bms/COUSR02.CPY} are 164 lines and both declare a field at L60, L66, L72, L78 and
 * L84, but the identifiers at the first three of those lines differ: L60, L66 and L72 are
 * {@code FNAMEI}, {@code LNAMEI} and {@code USERIDI} in the add map and {@code USRIDINI},
 * {@code FNAMEI} and {@code LNAMEI} in the update map, while L78 and L84 coincide. The two
 * validation chains follow their own maps exactly, the add chain testing in the sequence
 * {@code app/cbl/COUSR01C.cbl} L118, L124, L130, L136, L142 and the update chain in the sequence
 * {@code app/cbl/COUSR02C.cbl} L180, L186, L192, L198, L204, with sentences at L182, L188, L194,
 * L200 and L206. Since each chain latches its first failure, each map's order fixes that operation's
 * first sentence, and under transformation rule T8 that sentence is part of what a caller observes.
 *
 * <p>Refactoring Rationale: composing the two request bodies from a shared part is rejected in both
 * the forms it could take -- an {@code allOf} in the published contract, and a shared base record or
 * shared interface here -- and the reason is a specific change in behaviour rather than a preference
 * about shape. The properties
 * common to both bodies are the given name, the family name and the type; the only property this body
 * carries and the other does not is the identifier. A composed definition would therefore emit the
 * three shared properties together and append that one extra, producing the order given name, family
 * name, type, identifier for create -- which places the type ahead of the
 * identifier when {@code app/cbl/COUSR01C.cbl} tests the identifier at L130 and the type at L142. A
 * caller submitting a request with both of those blank would then be told about the type where the
 * reference tells it about the identifier. One shared base record or one shared interface between the
 * two would force the same single order and carry the same consequence, so neither is declared: this
 * record extends nothing and implements nothing, and the published contract declares two independent
 * request schemas with no {@code allOf} composing one from the other.
 *
 * <p>Assumptions: the two bodies also differ in membership, not only in order, so even setting the
 * ordering argument aside they are not one shape. The identifier is a component here because create
 * is where it is assigned, whereas update addresses an existing row and takes it from the request
 * path instead, which keeps a request from naming one user in its path and another in its body. The
 * update body consequently carries three properties where this one carries four, and a shared
 * definition would have to make one of those four optional to fit both, which would publish as
 * optional a value that a create request cannot omit.
 *
 * <p>Refactoring Rationale: this paragraph used to name a second membership difference -- a subject
 * reference "on create only, because the binding between a pool account and a row is established
 * once" -- and put the create body at five properties against the update body's three. That component
 * has been withdrawn, so the membership difference is now the identifier alone and the counts are four
 * against three. The argument is unchanged in substance and weaker only in arithmetic: one property
 * that would have to be published as optional is still one too many.
 *
 * <h2>The identifier is the key being assigned</h2>
 *
 * <p>Assumptions: the identifier is the primary key, and the reference proves it at the point of
 * writing. {@code app/cpy/CSUSR01Y.cpy} L18 declares {@code SEC-USR-ID PIC X(08)} at byte position 0
 * of the security record, {@code app/cpy-bms/COUSR01.CPY} L72 presents the same field to a terminal
 * as {@code USERIDI PIC X(8)}, and the {@code EXEC CICS WRITE} at {@code app/cbl/COUSR01C.cbl} L240
 * to L248 names it as the record key twice over, at {@code RIDFLD (SEC-USR-ID)} on L244 and
 * {@code KEYLENGTH (LENGTH OF SEC-USR-ID)} on L245. That is why the owning migration declares the
 * column as an eight-position character type and the table's primary key, and why the value travels
 * in this body rather than being generated: a create request chooses the key.
 *
 * <p>Assumptions: a duplicate is refused rather than absorbed, and that outcome is not this record's
 * to express. The reference stacks two conditions into one arm, {@code WHEN DFHRESP(DUPKEY)} at
 * {@code app/cbl/COUSR01C.cbl} L260 and {@code WHEN DFHRESP(DUPREC)} at L261, and reports
 * {@code 'User ID already exist...'} at L263 -- carried across exactly as written there, including
 * the missing letter and the absent space before the ellipsis, because transformation rule T8 takes
 * the literal as the program writes it, not as a reader might assume it was meant. The target reports
 * that collision as a 409 with
 * that sentence, carried by {@code com.carddemo.common.error.ApiError}. It is named here so a reader
 * knows where the case is handled, and no component is added for it: uniqueness is a fact about
 * stored rows, which a request body cannot assert about itself.
 *
 * <p>Assumptions: no character class is asserted on the identifier, and the omission is deliberate.
 * The reference declares the field as {@code PIC X(08)} and the column is an eight-position
 * character type, neither of which restricts which characters may appear, so a pattern invented here
 * would refuse identifiers the migrated data may legitimately contain. Only the width and the
 * non-blank condition are asserted, which is exactly what the two sources state.
 *
 * <h2>The one-character type, and the three places its domain is enforced</h2>
 *
 * <p>Assumptions: {@code app/cpy/COCOM01Y.cpy} is the sole authority for the two admissible values.
 * Its L26 declares {@code CDEMO-USER-TYPE PIC X(01)} and its L27 and L28 name the two condition
 * values as the quoted literals {@code 'A'} for administrator and {@code 'U'} for ordinary user.
 * Neither of the two obvious alternative sources can settle the domain. {@code app/cpy/CSUSR01Y.cpy}
 * L22 declares the same one-character width but carries no {@code 88}-level at all, so it fixes the
 * width and names no value; and the add-user program tests its type field for blankness only, at
 * {@code app/cbl/COUSR01C.cbl} L142, so it too names no value. Citing either for the domain would
 * attribute a value set to a line that does not contain one.
 *
 * <p>Assumptions: asserting membership here is a narrowing of what the reference accepted at its own
 * transport edge, and it is recorded as such rather than presented as equivalent. Because L142 tests
 * only for blankness, the reference screen would pass any single non-blank character through to
 * storage, and the file it wrote to declared no value constraint either. The target refuses a third
 * value at three separate points instead: here, by the pattern on {@code userType}, which is the
 * form a caller reads from the published contract; again in {@code com.carddemo.auth.service}, whose
 * ordered chain decides which field reports first; and last in the database, by
 * {@code CHECK (user_type IN ('A','U'))} on {@code auth.users}, which holds for every write path
 * including one that never passes through this service. The reference had no equivalent of that
 * third point at all, which is the substance of the narrowing. Removing any one of the three leaves
 * a route by which a third value reaches storage.
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
 * <h2>The subject reference is deliberately not a component of this record</h2>
 *
 * <p>Refactoring Rationale: this record carried a fifth component, {@code cognitoSub}, declared
 * {@code @NotNull UUID} and documented as "the subject identifier of the already-provisioned Cognito
 * account", "supplied by whatever provisioned that account, because this service creates no pool
 * account". It has been withdrawn, and the reason is not tidiness. The subject is the ONLY link
 * between a presented token and this row -- the column is {@code UUID NOT NULL UNIQUE} -- so a
 * caller able to choose it was a caller able to decide which pool identity a new row authenticates
 * as. An administrator creating a row could bind it to another person's subject, or to a subject
 * whose pool account carries the administrator group while the row says {@code 'U'}, and the two
 * sides would disagree with the signed claim winning every authorization decision. Nothing in the
 * request could have detected either case, because a well-formed UUID is all the shape a client had
 * to satisfy.
 *
 * <p>Assumptions: withdrawing the component is only half the fix and the other half is what makes it
 * work. {@code com.carddemo.auth.service.CognitoUserProvisioningService} now creates the pool account
 * and its group membership from the four components below, and reads the subject back out of the
 * provider's response, so the row and the pool identity are established by one act and cannot drift.
 * The subject remains a component of {@code com.carddemo.auth.dto.UserResponse}, where it is an
 * output: a caller learns which subject its row was bound to and cannot nominate one.
 *
 * <p>Alternatives Considered: keeping the component and validating it against the pool -- reading the
 * named subject back and refusing one that does not exist, or whose group disagrees with
 * {@code userType}. Rejected because it answers a question that should not be asked. The check would
 * be a round trip per request against a value the service can simply produce, it would still admit a
 * real subject belonging to a different person, and it would leave a pool account created by some
 * unnamed other party as a precondition of using this operation at all -- which is the coupling the
 * withdrawal removes.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: the trailing padding is dropped. {@code SEC-USR-FILLER PIC X(23)} at
 * {@code app/cpy/CSUSR01Y.cpy} L23, byte position 57, carries the record out to its declared length
 * of 80 bytes. No program and no map addresses it, so it holds no value a caller could supply, and
 * reproducing it would put a component on the wire that means nothing at either end. Transformation
 * rule T1 drops {@code FILLER} and records the drop per record; this paragraph is that record for
 * this one.
 *
 * <p>Assumptions: no message component, and no length-clamping path either. The reference message
 * field is {@code ERRMSGI PIC X(78)} in all five auth maps -- {@code app/cpy-bms/COSGN00.CPY} L84,
 * {@code app/cpy-bms/COUSR00.CPY} L372, {@code app/cpy-bms/COUSR01.CPY} L90,
 * {@code app/cpy-bms/COUSR02.CPY} L90 and {@code app/cpy-bms/COUSR03.CPY} L84 -- and 78 is the width
 * in every one of them, while the longest text those five programs emit is the 44 characters of
 * {@code 'You are already at the bottom of the page...'} at {@code app/cbl/COUSR00C.cbl} L273, so
 * the declared width is never approached and a truncating path would have nothing to do. The success
 * text this operation produced is composed rather than stored: {@code app/cbl/COUSR01C.cbl} L255 to
 * L258 strings {@code 'User '}, the identifier trimmed at its first space, and
 * {@code ' has been added ...'} into one sentence. That sentence is not returned as a component
 * either; the browser client holds every user-visible string in its own catalog and composes it from
 * the identifier it receives, so a server-rendered copy would be a second source for one string.
 * Failure text travels in {@code com.carddemo.common.error.ApiError}, whose per-field list carries a
 * constraint failure keyed by the component that raised it.
 *
 * <p>Assumptions: no confirmation component. The reference confirms a create with terminal-form
 * mechanics rather than with data: {@code app/cbl/COUSR01C.cbl} L252 performs
 * {@code INITIALIZE-ALL-FIELDS}, which blanks all five input fields at L287 to L295 so the screen is
 * ready for the next entry, and L254 writes a colour attribute into the output map to tint the
 * message green. A blanked screen and a tinted attribute are properties of a 3270 display, not
 * values a caller submits, so neither has an analogue in a request body. A client that wants a
 * confirmation step takes it before issuing the request, which is why no {@code confirmed} component
 * exists here for a server to re-check.
 *
 * <p>Assumptions: three further reference surfaces stop at the service boundary. No component
 * carries a colour, a severity or a tone, for the reason just given about L254. No component carries
 * the transaction monitor's response or reason codes; the equivalent diagnostic line in this program,
 * at {@code app/cbl/COUSR01C.cbl} L268, is commented out, which is a fact about this site rather
 * than a general rule and is cited as itself, but even where such a line is live it writes data-store
 * internals to a display stream and no request body should carry them. And the head block every map
 * opens with, transaction name, two title lines, date and time, is screen chrome and is excluded
 * entirely. No component describes a position in a result set either, because this record creates one
 * row; the page envelope this migration uses is declared once in {@code com.carddemo.common.web} and
 * carries {@code UserSummary}, not this type. And no timestamp component, because the owning
 * migration declares no created, updated or audit column among its five.
 *
 * <h2>What the annotations decide, and what they do not</h2>
 *
 * <p>Assumptions: these constraints are a transport-level guard on the shape of the body, and they
 * are not the authority on the order failures are reported in. The ordered chain that reproduces the
 * reference's latching sequence lives in {@code com.carddemo.auth.service}, and it has to, because
 * that sequence is not expressible as a list of body properties: the identifier is checked twice in
 * the reference, once for blankness at {@code app/cbl/COUSR01C.cbl} L130 and again for collision by
 * the write at L240 to L248 whose duplicate arms sit at L260 and L261, and the second of those is a
 * question about stored rows that no body-shape constraint can ask. Declaration order in this file
 * therefore records provenance, per the first section above; it does not drive response order, and a
 * reader should not infer that moving an annotation changes which sentence a caller sees first.
 *
 * <p>Alternatives Considered: implementing {@code com.carddemo.common.error.FieldOrdering}, the
 * opt-in interface a request record uses to declare its own screen's check order so the shared
 * advice latches the first entry's sentence. The sign-on body does implement it, which makes the
 * omission here worth explaining rather than assuming. It is not adopted because it would state half
 * of this operation's order: the interface takes body property names, so it can express the four
 * blank checks but not the collision check that follows them, and the order would then live partly in
 * a name list here and partly in the service that performs the rest. Keeping the whole sequence in
 * one place leaves one thing to read when the reported order is in question. The consequence of
 * declining it is registered rather than hidden: without the interface the shared advice accumulates
 * every field entry instead of latching one, which is the divergence the register records as
 * {@code D-ERROR-ACCUMULATION}, and it applies to this body.
 *
 * <p>Assumptions: a blank check and a non-blank annotation match exactly, which is why no custom
 * predicate is written for them. Each reference branch tests its field against
 * {@code SPACES OR LOW-VALUES} -- a fixed-width screen field arrives filled with blanks when the
 * user types nothing and with low values when the map was never populated -- and the non-blank
 * annotation refuses an absent value, an empty string and a string of whitespace alike, so the three
 * states the reference treats as empty are the three it refuses.
 *
 * <p>Assumptions: that choice makes this type stricter than one facet of the published contract, and
 * the gap is recorded here rather than left for someone to discover as a surprise. The contract
 * declares a minimum length of one on the two names and the identifier, and a minimum length of one
 * admits a value of nothing but spaces, which the non-blank constraint above refuses. The reference
 * settles which of the two is right: its branches treat {@code SPACES} as empty, so a name of three
 * spaces is a blank name to the screen this operation replaces, and refusing it is parity rather
 * than added strictness. Where the contract expresses the same rule it does so with an unanchored
 * non-whitespace pattern beside the minimum length, as the sign-on request schema does for its two
 * properties; the create schema currently states the minimum length alone, so a body carrying a
 * whitespace-only name satisfies the schema and is then refused here. That belongs to the schema to
 * express and is named here so the asymmetry is attributable, not silently absorbed. Loosening this
 * constraint to close it is the wrong direction, because it would accept into storage a blank name
 * the reference never accepted.
 *
 * <p>Trade-offs: the type carries three constraints where two would refuse the same values, and the
 * cost is paid deliberately. A value of two characters breaches both the exact-length constraint and
 * the pattern, and an empty string breaches the non-blank constraint as well, so one mistake can
 * produce two or three entries in the per-field list, each naming {@code userType}, because that list
 * holds one entry per violation rather than one per component. The alternative was to keep the
 * pattern alone, which already admits exactly one character and so implies the length. It is not
 * taken because the published contract declares a minimum length of one, a maximum length of one and
 * the enumeration as three separate facets, and these annotations are what the contract document is
 * generated from; asserting only the pattern would publish two length facets that this type does not
 * enforce, leaving the contract describing a stricter shape than the code checks.
 *
 * <p>Assumptions: no value is normalised on the way in. Nothing here trims a submitted string,
 * folds its case or pads it to the column width. The reference has no analogue to trim, since a
 * fixed-width screen field is padded by the terminal rather than by the user, and the case-sensitive
 * type domain is load-bearing: a COBOL condition name compares the bytes of its field against the
 * literal it was declared with, so {@code CDEMO-USRTYP-ADMIN} at {@code app/cpy/COCOM01Y.cpy} L27 is
 * satisfied by {@code 'A'} and not by {@code 'a'}. Folding case here would let this type accept a
 * value the reference condition refuses, and the database check on {@code auth.users} would then
 * reject on write what this contract had published as valid. Padding to the column width belongs to
 * {@code com.carddemo.auth.mapper}, which is where the storage representation is this context's
 * concern; a request arrives unpadded and is not the place to introduce it.
 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: no executable oracle exists for this bounded context, so every obligation below
 * falls on assertions against cited lines rather than against a reference run. All five auth
 * programs are online programs written against the transaction monitor's command-level interface,
 * and {@code tests/README.md} records at its lines 83 to 85 that such programs cannot run end to end
 * without a CICS runtime, which is absent on the runner. Nothing stated in this file can therefore
 * be settled by running a reference program or by comparing a reference screen, and every width,
 * order and admissible value here is instead verifiable by reading the cited file at the cited line,
 * which is why the citations are exact.
 *
 * <p>Assumptions: seven assertions are required of whatever tests this record. That it declares
 * exactly these four components in exactly this order, matching the four properties of the published
 * create schema. That its order differs from the update body's, and that neither type extends or
 * implements a shared supertype, which is the assertion that would fail first if the composition
 * rejected above were ever introduced. That a 9-character identifier is refused while an
 * 8-character one is accepted, pinning the bound at the width {@code app/cpy/CSUSR01Y.cpy} L18
 * declares rather than one position either side of it. That a 21-character name is refused while a
 * 20-character one is accepted, for each of the two names separately. That {@code userType} admits
 * {@code 'A'} and {@code 'U'} and refuses everything else, which means an absent value, an empty
 * string, a blank, the lower-case form of either letter and the two letters together are each
 * rejected. That each refusal carries the sentence named in the constant below it, since a sentence
 * carried across from the reference is only preserved if something checks it. And that the string
 * form renders neither name, stated as an assertion about the rendered
 * text rather than about the override's presence, because a rendering that named a component and
 * then printed its value would satisfy the weaker check while disclosing exactly what the stronger
 * one forbids.
 *
 * @param firstName the new user's given name, of at most 20 characters, as
 *     {@code SEC-USR-FNAME PIC X(20)} declares at {@code app/cpy/CSUSR01Y.cpy} L19, byte position 8
 *     of the 80-byte security record, and as {@code FNAMEI PIC X(20)} presents it at
 *     {@code app/cpy-bms/COUSR01.CPY} L60. It is required and must not be blank, and it is checked
 *     first by the reference chain at {@code app/cbl/COUSR01C.cbl} L118, which is why it is the
 *     first component declared here
 * @param lastName the new user's family name, of at most 20 characters, as
 *     {@code SEC-USR-LNAME PIC X(20)} declares at {@code app/cpy/CSUSR01Y.cpy} L20, byte position 28
 *     of that record, and as {@code LNAMEI PIC X(20)} presents it at
 *     {@code app/cpy-bms/COUSR01.CPY} L66. It is required and must not be blank, and it is checked
 *     second, at {@code app/cbl/COUSR01C.cbl} L124; it is a second component of the same declared
 *     width as the given name rather than a longer or shorter one
 * @param userId the identifier of the row being created and its primary key, of at most 8
 *     characters, as {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} L18, byte
 *     position 0 of that record, and as {@code USERIDI PIC X(8)} presents it at
 *     {@code app/cpy-bms/COUSR01.CPY} L72. That it is the key is settled by
 *     {@code RIDFLD (SEC-USR-ID)} at {@code app/cbl/COUSR01C.cbl} L244, inside the write spanning
 *     L240 to L248. It is required and must not be blank, is checked third at L130, and must not
 *     already be in use: a collision is refused with a 409 rather than overwriting the stored row
 * @param userType the new user's role, one character, as {@code SEC-USR-TYPE PIC X(01)} declares at
 *     {@code app/cpy/CSUSR01Y.cpy} L22, byte position 56 of that record, and as
 *     {@code USRTYPEI PIC X(1)} presents it at {@code app/cpy-bms/COUSR01.CPY} L84. It is
 *     {@code 'A'} for administrator or {@code 'U'} for ordinary user and nothing else, per
 *     {@code app/cpy/COCOM01Y.cpy} L26 to L28, which is the sole authority for that domain. It is
 *     required and must not be blank, and is checked last of the four at
 *     {@code app/cbl/COUSR01C.cbl} L142. Choosing the administrator value grants that user the
 *     authority these operations themselves require, so it is the one component here with an
 *     authorisation consequence
 */
public record CreateUserRequest(
        @NotBlank(message = MESSAGE_FIRST_NAME_REQUIRED)
        @Size(max = NAME_MAX_LENGTH, message = MESSAGE_FIRST_NAME_TOO_LONG) String firstName,
        @NotBlank(message = MESSAGE_LAST_NAME_REQUIRED)
        @Size(max = NAME_MAX_LENGTH, message = MESSAGE_LAST_NAME_TOO_LONG) String lastName,
        @NotBlank(message = MESSAGE_USER_ID_REQUIRED)
        @Size(max = USER_ID_MAX_LENGTH, message = MESSAGE_USER_ID_TOO_LONG) String userId,
        @NotBlank(message = MESSAGE_USER_TYPE_REQUIRED)
        @Size(min = USER_TYPE_LENGTH, max = USER_TYPE_LENGTH,
                message = MESSAGE_USER_TYPE_LENGTH)
        @Pattern(regexp = USER_TYPE_DOMAIN, message = MESSAGE_USER_TYPE_DOMAIN) String userType) {

    /**
     * The number of positions the reference declares for each of the two name fields.
     *
     * <p>Assumptions: 20 is read from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy} L19 and from {@code SEC-USR-LNAME PIC X(20)} at L20, which declare
     * the same width, and the add-user map repeats it for both fields at
     * {@code app/cpy-bms/COUSR01.CPY} L60 and L66. One constant serves both components because the
     * reference gives them one width, not because the two happen to agree today.
     *
     * <p>Alternatives Considered: writing 20 straight into each annotation. Rejected because three
     * distinct widths govern this record's four character components, 20 here, 8 for the identifier
     * and 1 for the type, and a bare number inside an annotation is the one place a reader cannot
     * tell which copybook line it was read from. A named constant leaves exactly one line to check
     * against L19 and L20.
     *
     * <p>Trade-offs: sharing one constant across the two name components couples them, so a change
     * to one name width alone would have to split this constant rather than edit it. That is accepted
     * because the coupling is the contract: L19 and L20 are two fields of one 80-byte record whose
     * widths sum with the others to exactly that length, so changing one of them alone would break
     * that arithmetic rather than be absorbed by it.
     */
    private static final int NAME_MAX_LENGTH = 20;

    /**
     * The number of positions the reference declares for the user identifier.
     *
     * <p>Assumptions: 8 is read from {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy}
     * L18, and {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY} L72 states the same width
     * independently, so the figure has two sources and neither is an inference from the other. The
     * owning migration declares the column as a character type of exactly this many positions and the
     * published contract declares the same maximum length, so all four agree.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * The number of positions the reference declares for the user type, used as both bounds.
     *
     * <p>Assumptions: 1 is read from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy}
     * L22 and from {@code USRTYPEI PIC X(1)} at {@code app/cpy-bms/COUSR01.CPY} L84. It is applied as
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
     * {@code app/cbl/COUSR01C.cbl} L120, the sentence the reference reports when its L118 branch
     * finds {@code FNAMEI} equal to {@code SPACES OR LOW-VALUES}. Transformation rule T8 takes the
     * literal as it is written, which includes the ellipsis with no space before it. The spacing is
     * per-literal in this program rather than uniform, and the contrast is inside this very file:
     * this sentence and its three siblings below have no space before the ellipsis, while the success
     * fragment at L257 carries both a leading space and a space before its ellipsis. Neither form is
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
     * <p>Assumptions: carried across character for character from {@code app/cbl/COUSR01C.cbl} L126,
     * the sentence reported by the L124 branch, on the same grounds as the given name above. It is a
     * separate constant rather than a shared one because the two sentences differ in their leading
     * words, so a single shared sentence would name the wrong field in one of the two responses.
     */
    private static final String MESSAGE_LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /**
     * The sentence reported when the identifier is absent or blank.
     *
     * <p>Assumptions: carried across character for character from {@code app/cbl/COUSR01C.cbl} L132,
     * the sentence reported by the L130 branch. It covers absence only. The distinct condition of an
     * identifier that is present but already in use is reported by the collision sentence the
     * reference emits at L263, which is raised where the row is written rather than where the body is
     * checked, so it is not a constraint message and does not appear in this file.
     */
    private static final String MESSAGE_USER_ID_REQUIRED = "User ID can NOT be empty...";

    /**
     * The sentence reported when the user type is absent or blank.
     *
     * <p>Assumptions: carried across character for character from {@code app/cbl/COUSR01C.cbl} L144,
     * the sentence reported by the L142 branch. That branch tests for blankness only, so this
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
     * {@code app/cpy-bms/COUSR01.CPY} L60, a fixed-width screen field that cannot physically hold a
     * twenty-first character, so the program has no over-length branch and there is no literal to
     * carry across under rule T8. The condition exists only because a JSON body has no such physical
     * bound, so the sentence is authored for the target rather than reproduced from the reference.
     *
     * <p>Assumptions: the authored sentence follows the ellipsis idiom of the program it accompanies,
     * with no space before the ellipsis, which is the form every error sentence in
     * {@code app/cbl/COUSR01C.cbl} uses. The sign-on body's equivalent authored sentence spaces its
     * ellipsis instead, because the program behind it does; the two records differ here for that
     * reason and not by accident, and neither is aligned to the other.
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
     * since {@code LNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR01.CPY} L66 is likewise a fixed-width
     * screen field with no over-length branch behind it. It names the family name rather than sharing
     * one sentence with the given name, so that a caller is told which of the two fields it was.
     */
    private static final String MESSAGE_LAST_NAME_TOO_LONG =
            "Last Name must be at most " + NAME_MAX_LENGTH + " characters...";

    /**
     * The sentence reported when the identifier exceeds its declared width.
     *
     * <p>Assumptions: authored, because {@code USERIDI PIC X(8)} at
     * {@code app/cpy-bms/COUSR01.CPY} L72 cannot hold a ninth character and the reference therefore
     * has no over-length branch to take a literal from. The width is interpolated from
     * {@code USER_ID_MAX_LENGTH} rather than written out as a digit, so the sentence and the
     * constraint that raises it cannot come to state different numbers.
     */
    private static final String MESSAGE_USER_ID_TOO_LONG =
            "User ID must be at most " + USER_ID_MAX_LENGTH + " characters...";

    /**
     * The sentence reported when the user type is not exactly one character long.
     *
     * <p>Assumptions: authored, because the reference screen field {@code USRTYPEI PIC X(1)} at
     * {@code app/cpy-bms/COUSR01.CPY} L84 holds one character by construction and the program's only
     * check on it, at {@code app/cbl/COUSR01C.cbl} L142, tests for blankness. The length is written
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
     * blankness only at {@code app/cbl/COUSR01C.cbl} L142 and would pass any single non-blank
     * character to storage, so there is no reference sentence for an out-of-domain value to carry
     * across. The two letters are named in the sentence, taken from
     * {@code app/cpy/COCOM01Y.cpy} L27 and L28, so a caller learns the whole domain from the refusal
     * rather than having to read the contract to discover it.
     */
    private static final String MESSAGE_USER_TYPE_DOMAIN = "User Type must be A or U...";

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Assumptions: the literal deliberately matches the one
     * {@code com.carddemo.auth.dto.UserResponse} and {@code com.carddemo.auth.dto.UserSummary} use
     * for their name components, and the one {@code com.carddemo.auth.dto.SignOnResponse} uses for
     * its tokens, so a log store holding lines from several of these types shows one placeholder
     * vocabulary rather than four. The constant is named for the class of data it hides rather than
     * for the literal, because a reader of this file needs to know which components are withheld and
     * a reader of a log line needs only to know that something was.
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Renders this request for diagnostics with both names withheld.
     *
     * <p>Refactoring Rationale: the string form a record generates for itself lists every component
     * beside its value, which on this record means the given name and family name of a real person. A
     * request body is stringified in exactly
     * the places that happens without anyone intending it: the framework includes the bound target in
     * the message of the exception it raises when a constraint on that target fails, so the ordinary
     * outcome of a caller mistyping one field would be a log line carrying the other three values in
     * full. This override keeps the generated form's shape and its component order and substitutes
     * {@code REDACTED_PERSONAL} for the two.
     *
     * <p>Refactoring Rationale: this block used to name a third withheld value, the identity
     * provider's subject, and put the incidental disclosure at four values. The subject is no longer a
     * component of this record -- it is provisioned by the service and returned on the response -- so
     * the withheld set is the two names and the incidental disclosure is three values. The override
     * itself already substituted only two placeholders; what was stale was the prose describing it,
     * which is the failure mode a reader is least able to detect.
     *
     * <p>Trade-offs: two components print in full and the choice of which is the substance of this
     * method. {@code userId} prints because it is the row locator, an eight-character logon
     * identifier assigned by an administrator rather than a fact about the person, and because a
     * rendering that identified no row could correlate nothing and would defeat the purpose of having
     * one. {@code userType} prints because it is a two-valued role classification carrying no
     * personal content, and it is the single most useful value when a role-related refusal is being
     * traced. What is given up is the ability to tell two instances apart from their printed form
     * alone whenever they name the same row.
     *
     * <p>Assumptions: only the string form is narrowed. Component equality and hashing are untouched,
     * and the values a caller submitted still reach the service and the mapper through the component
     * accessors, so nothing about the operation's behaviour changes. The exposure being closed is
     * incidental stringification, not the deliberate act of reading a component that was submitted on
     * purpose.
     *
     * @return a single-line description of this record naming every component in contract order, in
     *     which the given name and the family name are each represented by a placeholder and never
     *     rendered
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order
        //   included, so that a reader who knows what a record prints is not led to think some other
        //   type produced this line. Only the two withheld values depart from it.
        return "CreateUserRequest[firstName=" + REDACTED_PERSONAL
                + ", lastName=" + REDACTED_PERSONAL
                + ", userId=" + userId
                + ", userType=" + userType
                + "]";
    }
}
