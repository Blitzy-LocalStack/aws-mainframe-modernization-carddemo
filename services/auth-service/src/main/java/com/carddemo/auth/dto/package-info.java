/**
 * Carries the request and response records of the CardDemo sign-on and user
 * administration API.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and
 * nothing else. A type or test named below that has no file yet is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work
 * from -- which type belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first is that its
 * inventory reads as present tense unless the distinction is declared, which is
 * what this section is for; the sentence above is the single place a reader has to
 * look to tell a target from a measurement.</p>
 *
 * <p>Three statements define the charter of this package, and everything below
 * elaborates one of them. The types declared here are API request and response
 * records and nothing else. Their component order and their declared widths are
 * derived from the reference BMS symbolic maps and the reference
 * {@code app/cpy} copybooks rather than chosen locally. And this package
 * declares no pagination type of its own and no error type of its own, because
 * each of those shapes is owned once by {@code com.carddemo.common} and is
 * referenced from here instead of being restated.
 *
 * <p>Seven {@code .java} files constitute this package and no more: this
 * descriptor, and six records -- {@code SignOnRequest}, {@code SignOnResponse},
 * {@code UserSummary}, {@code UserResponse}, {@code CreateUserRequest} and
 * {@code UpdateUserRequest}. Between them they carry the payloads of the five
 * reference online programs {@code COSGN00C}, {@code COUSR00C},
 * {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C}.
 *
 * <h2>Where the declared widths come from</h2>
 *
 * <p>One reference record is the width authority for every component in this
 * package. {@code app/cpy/CSUSR01Y.cpy} declares {@code 01 SEC-USER-DATA} at
 * line 17 and six subordinate fields at lines 18 to 23: an eight-character
 * {@code SEC-USR-ID} at zero-based byte position 0, a twenty-character
 * {@code SEC-USR-FNAME} at 8, a twenty-character {@code SEC-USR-LNAME} at 28,
 * an eight-character {@code SEC-USR-PWD} at 48, a one-character
 * {@code SEC-USR-TYPE} at 56 and a twenty-three-character
 * {@code SEC-USR-FILLER} at 57. Those widths sum to exactly eighty bytes, which
 * is the whole record, so the arithmetic is self-checking: a component width
 * that disagrees with this copybook is wrong by construction rather than by
 * opinion.
 *
 * <p>{@code SEC-USR-FILLER} has no component here. It pads the record out to
 * its declared length and carries no value a caller can supply or read, so
 * reproducing it as a transfer-object component would put a field on the wire
 * that means nothing at either end. The drop is recorded rather than silent.
 *
 * <h2>Where the component order comes from</h2>
 *
 * <p>The copybook settles the widths; it does not settle the order any one
 * screen presents them in, and the two genuinely differ. The record runs
 * identifier, first name, last name, credential, type. The reference add-user
 * map {@code app/cpy-bms/COUSR01.CPY} runs {@code FNAMEI} at line 60,
 * {@code LNAMEI} at line 66, {@code USERIDI} at line 72 and {@code USRTYPEI} at
 * line 84, putting the first name ahead of the identifier. The reference
 * update-user map {@code app/cpy-bms/COUSR02.CPY} runs {@code USRIDINI} at line
 * 60, {@code FNAMEI} at line 66, {@code LNAMEI} at line 72 and
 * {@code USRTYPEI} at line 84, putting the identifier first because that screen
 * retrieves before it edits. Both authorities are named in this charter for
 * that reason, and neither substitutes for the other.
 *
 * <p>Assumptions: the symbol names differ between maps and are not
 * interchangeable. The identifier symbol is {@code USERIDI} in
 * {@code app/cpy-bms/COSGN00.CPY} at line 72 and in
 * {@code app/cpy-bms/COUSR01.CPY} at line 72, but {@code USRIDINI} in
 * {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY}, both at
 * line 60; {@code app/cpy-bms/COUSR00.CPY} carries no such symbol at all,
 * naming its ten grid rows {@code USRID01I} through {@code USRID10I} from line
 * 78 and their type cells {@code UTYPE01I} through {@code UTYPE10I} from line
 * 96. A line number alone identifies nothing across these five files, which is
 * why every citation in this charter names the file beside the line.
 *
 * <h2>What this package deliberately does not declare</h2>
 *
 * <p>This list is as much a part of the charter as the contents are, because an
 * absence in a package is invisible, and an author who cannot see why something
 * is missing supplies it.
 *
 * <p>No pagination type. The list screen pages, and the envelope it pages with
 * is declared once, in {@code com.carddemo.common.web}, as
 * {@code PageResponse}: four components in this order, being the page items, a
 * first-key cursor, a last-key cursor and a more-pages flag. Both cursors are
 * opaque strings and either may be absent. {@code UserSummary} is only the
 * element type that envelope carries; the envelope itself is never redeclared
 * here, and no page-number, page-size, total-count or total-page component
 * exists anywhere in this package.
 *
 * <p>Alternatives Considered: a page wrapper local to this one service was
 * evaluated and rejected. The reference list program browses by key and holds
 * its cursor between screen turns, so the target reproduces a keyed cursor
 * rather than a counted position; paging by counted position skips and repeats
 * rows when rows are inserted between two page reads, which a keyed cursor
 * cannot do. Declaring a second envelope here would also let this service drift
 * from the seven others that page the same way, and that drift would surface as
 * inconsistent paging behaviour between screens rather than as a build failure.
 *
 * <p>No error type. {@code com.carddemo.common.error} owns the problem shape as
 * {@code ApiError}, including the per-field error array a rejected submission
 * returns; that per-field entry is a record nested inside {@code ApiError} and
 * not a separate top-level type. No local error record, no local validation
 * flag, no local exception handler and no local correlation filter belongs
 * here.
 *
 * <p>No message component, and no length-clamping path either. The reference
 * message field is {@code ERRMSGI PIC X(78)} in all five maps, at
 * {@code app/cpy-bms/COSGN00.CPY} line 84, {@code app/cpy-bms/COUSR00.CPY}
 * line 372, {@code app/cpy-bms/COUSR01.CPY} line 90,
 * {@code app/cpy-bms/COUSR02.CPY} line 90 and
 * {@code app/cpy-bms/COUSR03.CPY} line 84. The longest message these five
 * programs emit is forty-four characters, so the declared width is never
 * approached, and messages travel in {@code ApiError} in any case. No record
 * here carries message text and none truncates one.
 *
 * <p>No presentation component. The reference programs distinguish three
 * message tones by writing a colour attribute into the output map: green for
 * success, red for error and neutral for information. A terminal attribute is
 * not data, so no record here carries a colour, a severity or a tone.
 *
 * <p>No monetary component. This bounded context holds an identifier, two names
 * and a one-character type, and no balance, limit, rate or amount of any kind,
 * so the exact decimal discipline the migration applies wherever money does
 * appear has nothing to bind to in this package.
 *
 * <p>No timestamp component. The owning schema migration declares the user
 * table with exactly five columns -- the identifier, the first name, the last
 * name, the type and the identity-provider subject reference -- and no created,
 * updated or audit column among them. The shared twenty-six-character timestamp
 * contract that {@code com.carddemo.common} publishes is consequently imported
 * by nothing in this package, which follows from that column list rather than
 * from an oversight in these records.
 *
 * <p>No response code from the reference transaction monitor. Three of the
 * reference programs write monitor response and reason codes to a display
 * stream while diagnosing a failed file operation. Those codes describe the
 * internals of a data store to whoever reads them, so they stop at the service
 * boundary: no record here carries one, and none is returned to a caller.
 *
 * <h2>Identity, and why no record is trusted with it</h2>
 *
 * <p>The one-character type has exactly two admissible values, and
 * {@code app/cpy/COCOM01Y.cpy} is their sole authority: line 26 declares
 * {@code CDEMO-USER-TYPE PIC X(01)} and lines 27 and 28 name its two condition
 * values as the quoted literals {@code 'A'} for administrator and {@code 'U'}
 * for user. {@code app/cpy/CSUSR01Y.cpy} line 22 declares the same width and
 * names no values at all, so it settles the width and cannot settle the domain.
 *
 * <p>Assumptions: a type component appearing on a record here is submitted or
 * reported data, never an authorisation input. In the reference system the
 * caller's type travels in the communication area, which is storage the
 * terminal hands back, so a caller could in principle assert its own type. The
 * target instead reads the caller's type from a signed token claim that the
 * caller cannot author. Any component named for the user type therefore
 * describes the user being administered, and a handler that consulted one to
 * decide what the caller may do would reintroduce precisely the weakness the
 * signed claim removes.
 *
 * <p>The stored credential is not carried forward. The reference record
 * declares an eight-character plaintext {@code SEC-USR-PWD} at
 * {@code app/cpy/CSUSR01Y.cpy} line 21 and the reference sign-on program
 * compares it directly; the target delegates that comparison to the managed
 * identity provider, retains only a subject reference in the user table, and
 * returns no credential on any response record here. The divergence is
 * deliberate and is documented in the migration's security and identity notes.
 * A credential presented at sign-on lives only on the inbound request for the
 * duration of that exchange: no response record echoes it and no column stores
 * it.
 *
 * <h2>Decision record</h2>
 *
 * <p>Refactoring Rationale: the reference presentation layer holds one field
 * definition per screen, so the same user identifier is declared across five
 * separate maps and the same twenty-character name twice within a single map.
 * The records here collapse those repetitions to one component per concept per
 * payload, which is a structural change only. Every width and every admissible
 * value survives it unchanged, because the reference source is the
 * specification these records encode and not a draft they improve upon.
 *
 * <p>Assumptions: this package depends on four contracts it does not own and
 * cannot verify locally. The field widths at {@code app/cpy/CSUSR01Y.cpy} lines
 * 18 to 22. The per-screen component order in the five maps named above. The
 * two-value type domain at {@code app/cpy/COCOM01Y.cpy} lines 26 to 28. And the
 * five-column shape of the owning user table, which governs the published API
 * contract wherever the two disagree.
 *
 * <p>Assumptions: no executable oracle exists for this bounded context. All
 * five reference programs are online programs written against the transaction
 * monitor's command-level interface, and {@code tests/README.md} records at its
 * lines 83 to 85 that such programs "cannot run end-to-end without a CICS
 * runtime (absent on the runner)". Nothing asserted in this package can
 * therefore be settled by running a reference program. Every width, every
 * component order and every admissible value stated here is instead verifiable
 * by reading the cited file at the cited line, which is why the citations are
 * exact.
 *
 * <p>Trade-offs: the two shared shapes are referenced from another module
 * rather than declared here, which couples this package to that module and
 * means a reader of these six records opens two packages to see a complete
 * response. The house doctrine is why that coupling is accepted.
 * {@code tests/README.md} states it as an instruction at its lines 540 to 542,
 * "never duplicate a layout; keep it single-sourced from app/cpy/", and two
 * competing declarations of one page envelope are that same duplication one
 * level above a record layout.
 *
 * <p>Trade-offs: this charter is long for a package that declares no executable
 * code, and the length is deliberate. No sibling package in this service
 * carries a charter of its own, so this file is the only package-level place
 * the record contract is stated. A shorter descriptor would satisfy the
 * documentation gate while leaving the derivation authorities, the deliberate
 * absences and the identity boundary unrecorded, and that is the state in which
 * a locally declared page type gets added.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file contains one Javadoc block and one package
 * declaration and nothing else -- no type, no annotation, no import and no line
 * comment. A package declaration needs no import, and anything that would
 * require one belongs in one of the six records instead.
 *
 * <p>Trade-offs: the block carries no parameter, return or exception
 * at-clauses, and no authorship, version or release marker either. The
 * project's Explainability rule enumerates four docstring elements at its lines
 * 18 to 21, and only the first of them, purpose, has any meaning for a package:
 * a package takes no parameters, returns nothing and raises nothing, so the
 * other three are recorded here as inapplicable rather than answered with
 * invented tags. The three authorship-style tags are absent because the
 * repository ruleset omits the whole family of checks that would ask for them,
 * and the version control history answers those three questions more reliably
 * than a comment maintained by hand.
 *
 * <p>Assumptions: this file is pure ASCII with no byte-order mark, and that
 * restriction is load-bearing rather than cosmetic. This charter quotes
 * {@code tests/README.md}, whose lines 542 and 548 carry a non-breaking hyphen
 * where an ordinary one is expected. That character is indistinguishable from
 * an ordinary hyphen on screen yet behaves differently in a search, so both
 * quotations here are retyped with an ASCII hyphen-minus rather than copied.
 * The build declares UTF-8 for the source encoding and for the documentation
 * gate's charset, so ASCII is a strict subset of what is configured and nothing
 * is lost mechanically.
 *
 * <p>Assumptions: two independent checks require this file, and neither is
 * redundant. The project's Explainability rule requires a docstring on every
 * module entry point at its line 15, and a package declaration is that entry
 * point in Java; {@code package-info.java} is the only compilation unit able to
 * carry package-level Javadoc, so this file is required rather than decorative.
 * The repository ruleset then enforces the requirement in two halves:
 * {@code JavadocPackage} asserts that this file exists in any directory holding
 * an audited source file, while {@code MissingJavadocPackage} asserts that the
 * file carries Javadoc. An empty file satisfies the first and fails the second,
 * so stripping this comment would not merely weaken the charter, it would fail
 * the build. No escape written into this source can relax that: the ruleset
 * configures the file-based suppression filter alone, that filter is set to
 * fail when its companion file is absent, and the companion carries no entry.
 */
package com.carddemo.auth.dto;
