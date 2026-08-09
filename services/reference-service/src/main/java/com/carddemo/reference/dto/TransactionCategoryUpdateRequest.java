package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The request body that replaces the description of one transaction category, satisfying the
 * contract schema {@code TransactionCategoryReplaceRequest}.
 *
 * <p>Purpose: this is the inbound wire shape of the category replace operation and nothing beyond
 * it. It holds no logic, reaches no store and carries no persistence annotation:
 * {@code com.carddemo.reference.api.TransactionCategoryController} binds a JSON body onto it under
 * {@code @Valid} while taking both halves of the addressed key from the request path, and
 * {@code com.carddemo.reference.service.TransactionCategoryService} decides whether a row holds
 * that key and whether the revision the caller sent still agrees with the stored one. The
 * constraints declared below are the field contract -- a width, a character domain and a presence
 * requirement -- and not the business rules, which is why no uniqueness test, no foreign-key test
 * and no existence lookup appears here. Each of those needs a repository, and a shape that reached
 * a store to answer a question about itself would invert the layering that the rules published by
 * {@code common-lib} keep enforceable rather than aspirational. This is the narrowest write shape
 * in the package, and that narrowness is entirely a consequence of where its key lives rather than
 * of anything having gone missing from it.</p>
 *
 * <p>Alternatives Considered: neither half of the composite key appears in this body, and the
 * absence of both is the one thing that distinguishes this shape from
 * {@code TransactionCategoryCreateRequest} beside it, so it is stated here rather than left to be
 * read as two oversights. The alternative evaluated was echoing the key back alongside the
 * description, which every caller already holds because it has just addressed the row with it. It
 * was rejected because a second copy of a value the request has already settled admits a case that
 * does not otherwise exist: a replace addressed to the category {@code 0001} beneath the type
 * {@code 01} whose body announces some other pair. A contract that admits that case has to define
 * which copy prevails and then police the disagreement on every request, and neither answer is
 * free -- preferring the path discards a value the caller deliberately supplied, while preferring
 * the body turns a replace into a re-keying that the route does not describe. What makes the option
 * worse here than on the parent type is arity. This key has two members rather than one:
 * {@code app/cpy/CVTRA04Y.cpy} groups {@code TRAN-TYPE-CD PIC X(02)} at its line 6 and
 * {@code TRAN-CAT-CD PIC 9(04)} at line 7 under one {@code TRAN-CAT-KEY} at line 5, and
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} names both in the composite
 * {@code PRIMARY KEY(TRC_TYPE_CODE,TRC_TYPE_CATEGORY)} at its line 5. So the type half and the
 * category half could each disagree with the path independently, and the contract would have to
 * define and police two mismatches where the parent has one, for no gain on either. Declining both
 * members removes the cases instead of answering them. The published contract reaches the same
 * conclusion independently: {@code openapi/reference-api.yaml} declares
 * {@code TransactionCategoryReplaceRequest} requiring {@code description} and {@code version} with
 * {@code additionalProperties} false, so a body carrying either code is refused as an unrecognised
 * member rather than reconciled against the path. {@code TransactionCategoryCreateRequest} does
 * require both codes, because a create is the one operation that chooses them. One consequence is
 * worth stating plainly so that no reader restores a member helpfully: because no four-character
 * category code is declared here, no leading-zero guarantee is enforced here either, and the
 * requirement that {@code 0001} never render as {@code 1} governs the path parameter that
 * {@code TransactionCategoryController} declares instead.</p>
 *
 * <p>Refactoring Rationale: the version member is the native expression of a check the baseline
 * already performs by hand, and reading it as a new requirement invented for the migration is the
 * likely misreading. {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} is the maintenance
 * program this context migrates, and it snapshots the record it read into
 * {@code TTUP-OLD-DETAILS} at its lines 328 to 331, holds the edited values beside it in
 * {@code TTUP-NEW-DETAILS} at lines 332 to 335, compares the two in
 * {@code 1205-COMPARE-OLD-NEW} at line 783 -- upper-cased and trimmed, so a difference only of case
 * or of padding is not counted as a change -- and reaches its {@code SYNCPOINT} at line 454 only
 * when the two still agree. When they do not, it selects the message-valued condition
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} declared at its lines 183 and 184, whose text is
 * {@code Record changed by some one else. Please review}, and answers by re-showing the record at
 * line 1586. That text is carried across character for character under migration rule T8,
 * including the two-word spelling of {@code some one} and the absence of a closing full stop, the
 * period after the closing quote in the source being the end of the COBOL sentence rather than part
 * of the message; {@code common-lib} publishes the one spelling as
 * {@code ApiError.COACTUPC_RECORD_CHANGED} so that no context has to respell it. The baseline
 * compares a snapshot because a read-for-update lock was never held across client think-time: a
 * CICS task ends at every screen turn, so no task survives to hold a lock while a user reads the
 * screen, and comparing a before-image is the only check available to it. The Java carries a
 * revision on the request and lets the persistence layer detect the disagreement, which states the
 * same guarantee natively and covers every column of the row by construction, where a compared
 * before-image covers only the members it echoed. The divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Trade-offs: the version is required rather than optional, and the cost accepted is that every
 * caller has to read a row before it may replace one. The alternative admits a write that cannot be
 * checked at all: with no revision supplied there is nothing to compare against, so the only
 * behaviours left are to overwrite whatever is stored or to refuse the request regardless. The
 * first is precisely the lost update this member exists to prevent, and the second refuses the same
 * requests a required member refuses, only deeper in the call and less legibly. The cost is also
 * one the baseline already imposes, since its maintenance screen fetches and displays the record
 * before it will accept an edit, so a user there reads before writing too.</p>
 *
 * <p>Assumptions: this resource answers HTTP 409 for more than one cause and the status alone does
 * not say which, so a reader should not treat the causes as interchangeable. A revision that no
 * longer agrees with the stored row on this replace is one cause, and this shape participates in
 * that one alone. A refused delete on the parent type is the other, and it is raised one resource
 * away rather than here: the {@code ON DELETE RESTRICT} foreign key that
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares across its lines 6 and 7 over
 * {@code CARDDEMO.TRANSACTION_TYPE (TR_TYPE)} refuses to remove a type while categories still
 * reference it, and {@code db/migration/V1__reference.sql} preserves that as
 * {@code fk_transaction_categories_type}. The two causes meet on one status here for a reason worth
 * seeing: the rows this shape writes are exactly the rows whose existence makes the parent's delete
 * fail. A caller acts differently on each -- re-read and resubmit against this resource, remove the
 * dependent categories first against the parent -- so the sentence in the response body is what
 * discriminates them, and only the revision conflict reports a value, as a {@code fieldErrors}
 * entry keyed {@code version} carrying the revision the row holds. The contract enumerates a third
 * beside them, a row that could not be taken for update, whose text
 * {@code Could not lock record for update} the same program declares at its lines 181 and 182. That
 * mapping belongs to {@code com.carddemo.common.error.GlobalExceptionHandler}, which
 * {@code ReferenceApplication} imports; this module declares no second advice, because two advices
 * competing for one exception resolve by a precedence a reader cannot see. Nothing here carries a
 * status code, an error member or an exception type.</p>
 *
 * <p>Assumptions: a refusal of either member surfaces as an entry in the {@code fieldErrors} array
 * of {@code com.carddemo.common.error.ApiError}, each entry carrying a
 * {@code com.carddemo.common.validation.FieldValidationFlag} state, and two properties of that flag
 * are counter-intuitive enough to invert by accident. The valid state is the low-value character
 * and not the digit zero: {@code COTRTUPC.cbl} declares
 * {@code 88 FLG-DESCRIPTION-ISVALID VALUE LOW-VALUES} at its line 101, beside
 * {@code 88 FLG-DESCRIPTION-NOT-OK VALUE '0'} at line 102 and
 * {@code 88 FLG-DESCRIPTION-BLANK VALUE 'B'} at line 103. And the digit zero carries the opposite
 * polarity one copybook away, where {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} declares
 * {@code 88 WS-DB2-OK VALUE '0'} at its line 26 against {@code 88 WS-DB2-ERROR VALUE '1'} at line
 * 27 -- zero means acceptable in that family and unacceptable in this one, so no assumption about
 * what zero means may travel between the two. Blank is a subset of error rather than a third peer
 * state, because {@code app/cpy/CSSETATY.cpy} tests the two together in a single disjunction at its
 * lines 18 and 19, which is why a consumer asks {@code FieldValidationFlag.isError()} and never
 * compares against a constant. The aggregate sentence accompanying the array is held to
 * seventy-five characters, {@code ApiError.MESSAGE_RENDERING_WIDTH}, read from
 * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 of {@code app/cpy/CVCRD01Y.cpy} and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29 beside it -- not the {@code ERRMSGI PIC X(78)} the
 * screen map declares at line 78 of {@code cpy-bms/COTRTUP.cpy}, which is the field a terminal pads
 * a message into, and not the {@code WS-RETURN-MSG PIC X(80)} that {@code COBTUPDT.cbl} declares at
 * its line 61, which is a batch console width from a separate regime.</p>
 *
 * <p>Assumptions: the baseline separates a key failure from a value failure structurally, and that
 * division is what explains why this shape can only ever report the second kind.
 * {@code COTRTUPC.cbl} declares the key field's edit flag bare, as {@code 05 WS-EDIT-TTYP-FLAG} at
 * its line 94, and nests the description's beneath a group of its own -- {@code 05 WS-NON-KEY-FLAGS}
 * at line 99 holding {@code 10 WS-EDIT-DESC-FLAGS} at line 100. Only the non-key side of that
 * division has a member on this request, because both key halves travel in the path, so a caller
 * addressing a pair that names no row is answered 404 against the path rather than through a
 * {@code fieldErrors} entry this body could have produced. That is the reason this shape's error
 * array is narrower than the create request's, which carries its whole key in the body and can
 * therefore fail on either side of the same division. The asymmetry is visible in the baseline's own
 * declaration rather than being an accident of this migration.</p>
 *
 * <p>Refactoring Rationale: three of the four members of the baseline's category record are absent
 * from this shape rather than two, and the third omission is the {@code FILLER PIC X(04)} that
 * {@code app/cpy/CVTRA04Y.cpy} declares at its line 9. It is the one filler of that record, and
 * {@code X(02)} plus {@code 9(04)} plus {@code X(50)} plus {@code X(04)} accounts for every one of
 * the sixty bytes the copybook header declares at its line 2. Those four bytes pad the record out to
 * a positional length, a need a JSON object does not have: a reader walking a flat record has to
 * know where the next record begins, whereas a member of an object is delimited by the encoding
 * itself. Migration rule T1 makes the drop the rule for every record rather than a choice taken at
 * this one, and it also requires that the drop be recorded, which is what this paragraph is for. The
 * seed rows in {@code app/data/ASCII/trancatg.txt} carry the four characters {@code 0000} in those
 * bytes on every row, a constant of the layout rather than a value a caller could have supplied or
 * a reader could interpret.</p>
 *
 * <p>Assumptions: this type's name differs from the contract schema it satisfies, and the difference
 * is deliberate rather than an oversight. The charter in {@code package-info} settles the suffix
 * pairing {@code CreateRequest} and {@code UpdateRequest} as what lets a reader tell one write shape
 * from its neighbour without opening either file, and records across its lines 84 and 85 the
 * correspondence to {@code TransactionCategoryReplaceRequest}, so that neither name has to be
 * inferred from the other.</p>
 *
 * <p>Parameters, return values and exceptions at the type level: the two record components are this
 * type's parameters and each carries its own at-clause below. A type declaration returns nothing and
 * raises nothing, so no return or exception at-clause belongs here, and the canonical accessors the
 * record form supplies return their component unchanged -- neither of them trims, pads or normalises
 * -- which is the trivial case the Explainability rule admits a single line for. Stating that
 * inapplicability rather than passing over it in silence is deliberate: the same rule counts a
 * docstring omitting parameters or return values among its forbidden patterns, so a reader has to be
 * able to tell a declared inapplicability from an oversight.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is
 * never modified. Where this context's behaviour departs from the baseline's, the departure is
 * recorded in {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere
 * and is referenced from here rather than authored here.</p>
 *
 * @param description the replacement description to store in place of the one the row holds, as a
 *     mandatory {@code String} of at most the fifty characters
 *     {@code TRAN-CAT-TYPE-DESC PIC X(50)} declares at line 8 of {@code app/cpy/CVTRA04Y.cpy} and
 *     {@code TRC_CAT_DATA VARCHAR(50)} declares at line 4 of {@code TRNTYCAT.ddl}, stored as
 *     {@code description VARCHAR(50)}; a maximum rather than an exact width because the column holds
 *     the value without the record's padding, it must carry at least one character that is not a
 *     space, and it is mandatory because the column is declared {@code NOT NULL}
 * @param version the revision the caller read with the record and intends to replace, as a mandatory
 *     {@code Long} matching the {@code version BIGINT} column; it exists so that a write against a
 *     row another caller has already moved on is refused with HTTP 409 instead of overwriting that
 *     caller's change, and it is a counter rather than a timestamp, so comparing two values tells a
 *     caller that they differ and never when or by whom. Zero is a legitimate value to send rather
 *     than a missing one, because the migration declares the column defaulting to zero and every
 *     seeded row therefore carries it
 */
public record TransactionCategoryUpdateRequest(

        // WHY :
        //   Assumptions: the character domain is the baseline's own rather than an invention. The
        //     alphanumeric edit 1230-EDIT-ALPHANUM-REQD at line 849 of
        //     app/app-transaction-type-db2/cbl/COTRTUPC.cbl converts every admitted character to a
        //     space at its lines 878 to 880 and then treats a value that trims empty as clean at
        //     lines 882 to 886; the letters and digits it converts are the literals declared across
        //     its lines 232 to 237, namely LIT-UPPER holding 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
        //     LIT-LOWER holding 'abcdefghijklmnopqrstuvwxyz' and LIT-NUMBERS holding '0123456789'.
        //     A space needs no entry in that list because a supplied space cannot be told apart
        //     from a converted one and the same trim removes both, which is why the space is
        //     admitted here without being enumerated there, and why the comment at line 875 of that
        //     program describes the domain as alphabets, numbers and space alike.
        //   Assumptions: at least one character must not be a space, so the expression brackets a
        //     mandatory alphanumeric between two optional runs. An all-blank description is refused
        //     by the shape rather than stored, matching the baseline's own blank branch at lines 854
        //     to 859, which answers ' must be supplied.' from line 866; a value carrying anything
        //     outside the domain is answered ' can have numbers or alphabets only.' from line 893.
        //   Assumptions: the width is a ceiling rather than an equality because the trailing blanks
        //     of the fifty-byte field are padding to a positional record length rather than data.
        //     db/migration/V1__reference.sql stores the value as description VARCHAR(50) and
        //     app/app-transaction-type-db2/ddl/TRNTYCAT.ddl declares TRC_CAT_DATA VARCHAR(50)
        //     NOT NULL for that same reason, so requiring exactly fifty characters here would
        //     oblige every caller to pad a value the store then holds unpadded.
        //   Trade-offs: the lower bound restates a presence requirement the blank constraint and the
        //     expression both already carry. The duplication is accepted so that an empty value is
        //     reported as a length failure a caller can act on rather than only as a pattern
        //     failure, which says no more than that the value was refused.
        @NotBlank
        @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*")
        String description,

        // WHY :
        //   Trade-offs: the revision is declared as the boxed type, accepting a reference that can
        //     be null in a shape whose other member cannot be. The primitive was the alternative and
        //     it cannot express this contract: it would take zero for an omitted member, and zero is
        //     a legitimate revision because db/migration/V1__reference.sql declares version BIGINT
        //     NOT NULL DEFAULT 0 and every seeded row carries it, so an omission would arrive
        //     indistinguishable from a first replace of a seeded row and would be checked against
        //     the wrong revision rather than refused. The boxed type is what lets the presence
        //     constraint answer that at the edge instead of leaving the service to guess.
        //     TransactionCategoryResponse declares the same value as a primitive precisely because a
        //     stored row always has one.
        //   Assumptions: zero is admitted rather than refused, which is why the bound is at-or-above
        //     zero rather than strictly above it. RecordVersion in openapi/reference-api.yaml
        //     declares minimum 0 for the same reason, and a bound excluding zero would make every
        //     seeded row unreplaceable until something else had already replaced it.
        //   Assumptions: the comparison against the stored revision happens outside this shape, at
        //     line 209 of service/TransactionCategoryService.java, because a shape cannot compare
        //     itself against a row it is not permitted to read. Only presence and range are
        //     answerable here, which is why those are the only two constraints declared.
        @NotNull
        @PositiveOrZero
        Long version) {
}
