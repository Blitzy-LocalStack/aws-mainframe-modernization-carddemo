package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body that adds a transaction type, satisfying the contract schema of the same name.
 *
 * <p>Purpose: this is the inbound wire shape of the create operation and nothing beyond it. It holds
 * no logic, reaches no store and carries no persistence annotation:
 * {@code com.carddemo.reference.api.TransactionTypeController} binds a JSON body onto it under
 * {@code @Valid}, {@code com.carddemo.reference.mapper.TransactionTypeMapper} turns it into an
 * unsaved entity, and {@code com.carddemo.reference.service.TransactionTypeService} decides whether
 * a row holds that code. The constraints declared below are the field contract -- a width, a
 * character domain and a presence requirement -- and not the business rules, which is why no
 * uniqueness test and no foreign-key test appears here. Either would need a repository, and a shape
 * that reached a store to answer a question about itself would invert the layering that the rules
 * published by {@code common-lib} keep enforceable rather than aspirational.</p>
 *
 * <p>Alternatives Considered: one shared write shape serving both the create and the replace was
 * evaluated and rejected. It would have to declare the code member optional, because a replace does
 * not carry one -- {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} addresses the row it is
 * changing through the key it holds, and the migrated replace addresses it through the request path.
 * An optional member cannot be validated as mandatory, so a create submitted without a code would
 * satisfy the shape and reach the service layer to be refused there, turning a refusal the shape
 * itself could make into a branch someone has to write and test. The published contract reaches the
 * same conclusion independently: {@code openapi/reference-api.yaml} declares
 * {@code TransactionTypeCreateRequest} requiring {@code typeCd} and {@code description} and carrying
 * no version member, and declares {@code TransactionTypeReplaceRequest} beside it requiring
 * {@code description} and {@code version} with the code absent, both with
 * {@code additionalProperties} false. Two schemas whose required member sets genuinely differ cannot
 * both be satisfied strictly by one Java shape.</p>
 *
 * <p>Refactoring Rationale: the baseline serves both paths from one record, so a single shared write
 * shape is the intuitive design and its absence here is the decision that needs stating.
 * {@code COTRTUPC.cbl} declares one {@code TTYP-UPDATE-RECORD} at its line 130 --
 * {@code TTUP-UPDATE-TTYP-TYPE PIC X(02)} at line 134 and
 * {@code TTUP-UPDATE-TTYP-TYPE-DESC PIC X(50)} at line 135 -- and serves an add and a change from it
 * alike, while the batch driver {@code COBTUPDT.cbl} dispatches one {@code WS-INPUT-REC} on an
 * action character at its line 110. That reuse costs the baseline nothing, because the action arrives
 * in the same buffer as the data and one layout can therefore mean two things without ambiguity. A
 * JSON body has no companion action field: the method and the route carry the action instead, the
 * discriminator has nothing to be, and the two bodies are then free to require different members.
 * The Java publishes two shapes where the baseline holds one record, and the divergence is recorded
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the code is constrained to an exact width while the description is constrained to a
 * maximum, and the asymmetry is deliberate rather than an inconsistency between two members of one
 * shape. Both are declared at a single width in the record -- {@code TRAN-TYPE PIC X(02)} at line 5
 * of {@code app/cpy/CVTRA03Y.cpy} and {@code TRAN-TYPE-DESC PIC X(50)} at line 6 -- so what differs
 * is what the padding means. The code's width is part of the key contract, which is why
 * {@code db/migration/V1__reference.sql} stores it as {@code type_cd CHAR(2)}: a category key is
 * formed by positional concatenation, so the seed row {@code 010001} in
 * {@code app/data/ASCII/trancatg.txt} is the type {@code 01} followed by the category {@code 0001},
 * and a code arriving as {@code 1} rather than {@code 01} would not locate its own categories. The
 * description's trailing blanks are padding to the record length rather than data, which is why the
 * same migration stores it as {@code description VARCHAR(50)} and why
 * {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} declares
 * {@code TR_DESCRIPTION VARCHAR(50) NOT NULL} against {@code TR_TYPE CHAR(2) NOT NULL}. Both members
 * are {@code String} and neither is a numeric type, so a leading zero survives the round trip; a
 * numeric member would admit a sign and an exponent the contract does not, and would discard the
 * leading zero the key depends on.</p>
 *
 * <p>Assumptions: the {@code FILLER PIC X(08)} the layout carries is not a member of this shape, and
 * migration rule T1 requires the drop be recorded rather than left to be noticed.
 * {@code app/cpy/CVTRA03Y.cpy} declares it at its line 7, and {@code X(02)} plus {@code X(50)} plus
 * {@code X(08)} accounts for every one of the sixty bytes the record header declares, so nothing is
 * overlooked by omitting it. Those eight bytes pad a positional record length, which a JSON object has
 * no need of, and the seed rows carry zeros in them, so the omission discards no value a caller could
 * have supplied.</p>
 *
 * <p>Assumptions: no action-code member exists here and none belongs here, because the two action
 * domains the baseline maintains are not one domain and merging them is the likely way such a member
 * would arrive. The online row-selection domain admits exactly {@code 'D'} and {@code 'U'}, declared
 * as {@code 88 SELECT-OK VALUES 'D', 'U'} at line 183 of
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. The batch dispatch domain admits {@code 'A'},
 * {@code 'U'}, {@code 'D'} and {@code '*'} from line 111 of {@code COBTUPDT.cbl} onward, the last
 * standing for an input line to ignore rather than for an action at all. One enumeration covering both
 * would admit an add on a screen that offers no add,
 * and a selection character in a file that has no screen. Here the action is neither character: it is
 * the POST method on the collection route, so no discriminator is carried and none is to be added.</p>
 *
 * <p>Assumptions: a refusal of either member surfaces as an entry in the {@code fieldErrors} array of
 * {@code com.carddemo.common.error.ApiError}, each entry carrying a
 * {@code com.carddemo.common.validation.FieldValidationFlag} state, and that mapping is owned by
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, which {@code ReferenceApplication}
 * imports -- this module declares no second advice, because two advices competing for one exception
 * resolve by a precedence a reader cannot see. Two properties of that flag invert by accident. The
 * valid state is the LOW-VALUE character and not the digit zero -- {@code COTRTUPC.cbl} line 95
 * declares {@code 88 FLG-TRANFILTER-ISVALID VALUE LOW-VALUES} beside
 * {@code 88 FLG-TRANFILTER-NOT-OK VALUE '0'} -- while one copybook away
 * {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} declares {@code 88 WS-DB2-OK VALUE '0'}, so no
 * assumption about what zero means may travel between the two families. And blank is a subset of error
 * rather than a third peer state, because {@code app/cpy/CSSETATY.cpy} tests the two together in one
 * disjunction, which is why a consumer asks {@code FieldValidationFlag.isError()} and never compares
 * against a constant.</p>
 *
 * <p>Assumptions: the baseline separates a key failure from a value failure structurally, and the two
 * are reported as different kinds of thing for that reason. {@code COTRTUPC.cbl} declares the code's
 * flag bare, as {@code 05 WS-EDIT-TTYP-FLAG} at its line 94, and nests the description's beneath a
 * group of its own -- {@code 05 WS-NON-KEY-FLAGS} at line 99 holding
 * {@code 10 WS-EDIT-DESC-FLAGS} at line 100. A failure on the code is an identity failure: the value
 * names no row that can exist, so nothing further about the request can be judged against a row. A
 * failure on the description is a value failure on a row whose identity is settled. Flattening the
 * two into one undifferentiated list would invite a caller to resubmit a reworded description while
 * the code that made the request unanswerable is still wrong.</p>
 *
 * <p>Assumptions: the presentation the baseline drives from those flags does not travel.
 * {@code CSSETATY.cpy} gates its highlight on the session structure's re-entry discriminator, which a
 * stateless handler has no equivalent of, so error presentation is driven by the response body alone;
 * and the asterisk it moves for a blank field targets the map's output subfield rather than the
 * attribute subfield, making it a rendering constant rather than a flag value, so it is published on
 * {@code FieldValidationFlag} and appears in no member here. The aggregate sentence accompanying the
 * array is held to seventy-five characters, {@code ApiError.MESSAGE_RENDERING_WIDTH}, read from
 * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 of {@code app/cpy/CVCRD01Y.cpy} -- not the
 * {@code ERRMSGI PIC X(78)} a screen map declares, which is the field a terminal pads a message into,
 * and not the {@code WS-RETURN-MSG PIC X(80)} of the batch console, which is a separate regime.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is never
 * modified. Where this context's behaviour departs from the baseline's, the departure is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere and is
 * referenced from here rather than authored here.</p>
 *
 * @param typeCd the transaction type code to create, as a mandatory {@code String} of exactly the two
 *     characters {@code TRAN-TYPE PIC X(02)} declares at line 5 of {@code app/cpy/CVTRA03Y.cpy} and
 *     {@code type_cd CHAR(2)} stores; the admitted values run {@code '01'} through {@code '99'} and
 *     {@code '00'} is refused, and the member is mandatory because it is the primary key
 *     {@code TRNTYPE.ddl} declares and a create is the one operation that chooses it
 * @param description what the new type means to a user, as a mandatory {@code String} of at most the
 *     fifty characters {@code TRAN-TYPE-DESC PIC X(50)} declares at line 6 of that copybook; a
 *     maximum rather than an exact width because {@code description VARCHAR(50)} stores the value
 *     without the record's padding, and mandatory because {@code TR_DESCRIPTION} is declared
 *     {@code NOT NULL}
 */
public record TransactionTypeCreateRequest(

        // WHY : Assumptions: the pattern is spelt as two alternatives rather than as a two-digit
        //       repetition because it has to REFUSE '00', which a repetition admits. The contract
        //       publishes it as ^(?:0[1-9]|[1-9][0-9])$ and the baseline refuses zero outright,
        //       composing 'Tran Type code must not be zero.' from the label at line 826 of
        //       COTRTUPC.cbl and the literal at line 961.
        // WHY : Assumptions: character ranges stand in for the digit shorthand because the
        //       shorthand's reach depends on a matcher flag Bean Validation does not set, so the
        //       ranges say what is meant whatever that flag holds. The anchors the contract writes
        //       are omitted because Bean Validation matches a value entire rather than searching
        //       within it, so an unanchored expression here admits exactly what the anchored one does.
        // WHY : Trade-offs: the size constraint restates a length the pattern also enforces. The
        //       duplication is accepted so that a wrong length is reported as a length failure
        //       rather than as a pattern failure, which is the difference between a message a
        //       caller can act on and one that only says the value was refused.
        @NotBlank
        @Size(min = 2, max = 2)
        @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])")
        String typeCd,

        // WHY : Assumptions: the character domain is the baseline's own rather than an invention.
        //       Its edit at lines 849 to 899 of COTRTUPC.cbl converts every admitted character to a
        //       space and then trims, treating a value that trims empty as clean; the letters and
        //       digits it converts are the literals at lines 230 to 237 -- 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
        //       'abcdefghijklmnopqrstuvwxyz' and '0123456789'. A space needs no entry in that list
        //       because a supplied space is indistinguishable from a converted one and the same trim
        //       removes both, which is why the space is admitted here without being enumerated there.
        // WHY : Assumptions: at least one character must not be a space, so the pattern brackets a
        //       mandatory alphanumeric between two optional runs. An all-blank value is refused by
        //       the shape rather than stored as a description, matching the baseline's own blank
        //       branch at lines 854 to 859, which answers ' must be supplied.' from line 866.
        @NotBlank
        @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*")
        String description) {
}
