package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body that adds a transaction category, satisfying the contract schema of the same
 * name.
 *
 * <p>Purpose: this is the inbound wire shape of the category create operation and nothing beyond
 * it. It carries both halves of the composite key the caller chooses, together with the description.
 * It holds no logic, reaches no store and declares no persistence annotation:
 * {@code com.carddemo.reference.api.TransactionCategoryController} binds a JSON body onto it under
 * {@code @Valid}, {@code com.carddemo.reference.mapper.TransactionCategoryMapper} turns it into an
 * unsaved entity, and {@code com.carddemo.reference.service.TransactionCategoryService} decides
 * whether a row already holds that key and whether the type it names exists. The constraints
 * declared below are the field contract -- a width, a character domain and a presence requirement --
 * and not the business rules, which is why no uniqueness test and no referential test appears here.
 * Either would need a repository, and a shape that reached a store to answer a question about itself
 * would invert the layering that the rules published by {@code common-lib} keep enforceable rather
 * than aspirational.</p>
 *
 * <p>Assumptions: {@code catCd} is carried as a {@code String} and is never an integer or any other
 * numeric type, which is the load-bearing decision of this file. An integer renders the stored value
 * {@code 0001} as {@code 1}, and those leading zeros are not presentation: they are part of the
 * primary key this request supplies. The Db2 lineage states that character nature outright and
 * governs. {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at its line 3 and names it in the composite
 * {@code PRIMARY KEY(TRC_TYPE_CODE,TRC_TYPE_CATEGORY)} at its line 5, and the DCLGEN host structure
 * beside it generates that same column across lines 42 and 43 of
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} as {@code DCL-TRC-TYPE-CATEGORY PIC X(4)},
 * a character host variable and not a numeric one. The VSAM copybook declares the field as
 * {@code TRAN-CAT-CD PIC 9(04)} at line 7 of {@code app/cpy/CVTRA04Y.cpy}, and the two declarations
 * do not disagree about a single byte: a display numeric occupies one character position per digit,
 * so for the value {@code 0001} those four positions hold the characters {@code 0}, {@code 0},
 * {@code 0} and {@code 1}. {@code TransactionCategoryResponse} carries the fuller argument and is
 * the place to read it rather than have it restated here. A create is where the consequence bites
 * hardest, because the value this member supplies becomes the key the caller must use afterwards to
 * address the row it has just made, and a key that renders differently from the way it is stored is
 * a key that cannot be echoed back.</p>
 *
 * <p>Alternatives Considered: one shared write shape serving both the create and the replace was
 * evaluated and rejected. A shared shape would have to declare both key members optional, because a
 * replace carries neither -- the contract declares {@code TransactionCategoryReplaceRequest} beside
 * this schema requiring only {@code description} and {@code version}, since together the two codes
 * are the primary key and are addressed in the request path. An optional member cannot be validated
 * as mandatory, so a create submitted with no category code would satisfy the shape and reach the
 * service layer to be refused there, turning a refusal the shape itself can make into a branch
 * someone has to write and test. Forfeiting mandatory-key validation is the specific cost that
 * decided this, and it weighs more here than on the type create, because this key has two halves and
 * a shared shape would surrender the validation of both.</p>
 *
 * <p>Refactoring Rationale: the baseline serves both paths from one record, so a single shared write
 * shape is the intuitive design and its absence is the decision that needs stating.
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} declares one {@code TTYP-UPDATE-RECORD} at
 * its line 130 and serves an add and a change from it alike, while the batch driver
 * {@code COBTUPDT.cbl} reads every input line into one {@code WS-INPUT-REC} at its line 71 and
 * dispatches on an action character. That reuse costs the baseline nothing, because the action
 * arrives in the same buffer as the data, so one layout can mean two things without ambiguity. A
 * JSON body has no companion action field: the method and the route carry the action instead, the
 * discriminator has nothing to be, and the two bodies are then free to require different members.
 * The Java publishes two shapes where the baseline holds one record, and the divergence is recorded
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Trade-offs: the two code members are declared as siblings rather than nested inside a key
 * sub-record, even though {@code app/cpy/CVTRA04Y.cpy} groups them together under
 * {@code TRAN-CAT-KEY} at its line 5. A nested member would serialise as a nested JSON object,
 * which is not the shape the contract declares: it lists {@code typeCd}, {@code catCd} and
 * {@code description} as required members of one object and sets {@code additionalProperties} false,
 * so a nested key would satisfy neither the member list nor the refusal of members the schema does
 * not name. The compromise accepted is that the grouping the copybook expresses is no longer visible
 * in the declaration, so the fact that these two members form ONE key rather than two independent
 * attributes has to be carried in prose -- and it is carried here, because a reader who took
 * {@code catCd} for a globally unique identifier would believe a create may choose it freely, when
 * it is unique only within the type named beside it.</p>
 *
 * <p>Refactoring Rationale: the {@code FILLER PIC X(04)} at line 9 of
 * {@code app/cpy/CVTRA04Y.cpy} is not a member of this shape, and the drop is recorded here rather
 * than left to be noticed. It is the one filler of that record, and {@code X(02)} plus
 * {@code 9(04)} plus {@code X(50)} plus {@code X(04)} accounts for every one of the sixty bytes the
 * copybook header declares at its line 2. Those four bytes pad the record out to a positional
 * length, a need a JSON object does not have: a reader walking a flat record has to know where the
 * next record begins, whereas a member of an object is delimited by the encoding itself. Migration
 * rule T1 makes the drop the rule for every record rather than a choice taken at this one, and it
 * also requires that the drop be recorded, which is what this paragraph is for. The seed rows in
 * {@code app/data/ASCII/trancatg.txt} carry {@code 0000} in those bytes, so the omission discards no
 * value a caller could have supplied.</p>
 *
 * <p>Assumptions: {@code typeCd} is validated here as a well-formed code and not as an existing one,
 * so a request satisfying every constraint below can still be refused. The type must already exist:
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares across its lines 6 and 7
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE)
 * ON DELETE RESTRICT}, which
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} preserves as
 * {@code fk_transaction_categories_type}, so a category naming an absent type is refused by the
 * database rather than stored as an orphan. That check is deliberately not implemented here: it can
 * only be answered against stored data, which needs a repository this layer must not reference, and
 * a constraint pretending to answer it would leave two places able to disagree about one question.
 * The refusal is rendered by {@code com.carddemo.common.error.GlobalExceptionHandler}, which
 * {@code ReferenceApplication} imports and which this module never supplements with a second advice,
 * because two advices competing for one exception resolve by a precedence a reader cannot see. This
 * shape therefore carries no status member and no error member; the dependency is documented so that
 * a reader understands why an apparently well-formed request can still fail.</p>
 *
 * <p>Assumptions: no action-code member exists here and none belongs here, because the two action
 * domains the baseline maintains are not one domain and merging them is the likely way such a member
 * would arrive. The online row-selection domain admits exactly {@code 'D'} and {@code 'U'}, declared
 * as {@code 88 SELECT-OK VALUES 'D', 'U'} at line 183 of
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. The batch dispatch domain admits
 * {@code 'A'} at line 111 of {@code COBTUPDT.cbl}, {@code 'U'} at line 114, {@code 'D'} at line 117
 * and {@code '*'} at line 120, the last standing for an input line to ignore rather than for an
 * action at all. One enumeration covering both would admit an add on a screen that offers no add,
 * and a selection character in a file that has no screen. Here the action is neither character: it
 * is the POST method on the collection route, so no discriminator is carried and none is to be
 * added.</p>
 *
 * <p>Assumptions: a refusal of any member surfaces as an entry in the {@code fieldErrors} array of
 * {@code com.carddemo.common.error.ApiError}, each entry carrying a
 * {@code com.carddemo.common.validation.FieldValidationFlag} state. Two properties of that flag are
 * counter-intuitive enough to invert by accident. The valid state is the low-value character and not
 * the digit zero: {@code COTRTUPC.cbl} declares {@code 88 FLG-TRANFILTER-ISVALID VALUE LOW-VALUES}
 * at its line 95, beside {@code 88 FLG-TRANFILTER-NOT-OK VALUE '0'} at line 96 and
 * {@code 88 FLG-TRANFILTER-BLANK VALUE 'B'} at line 97. And the digit zero carries the opposite
 * polarity one copybook away, where {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} declares
 * {@code 88 WS-DB2-OK VALUE '0'} at its line 26 against {@code 88 WS-DB2-ERROR VALUE '1'} at its
 * line 27 -- zero means OK in that family and not-OK in this one, so no assumption about what zero
 * means may travel between them. Blank is a subset of error rather than a third peer state, because
 * {@code app/cpy/CSSETATY.cpy} tests the two together in one disjunction at its lines 18 and 19,
 * which is why a consumer asks {@code FieldValidationFlag.isError()} and never compares against a
 * constant. The aggregate sentence accompanying the array is held to seventy-five characters,
 * {@code ApiError.MESSAGE_RENDERING_WIDTH}, read from {@code CCARD-ERROR-MSG PIC X(75)} at line 28
 * of {@code app/cpy/CVCRD01Y.cpy} and {@code CCARD-RETURN-MSG PIC X(75)} at line 29 beside it --
 * neither the {@code ERRMSGI PIC X(78)} the screen map declares at line 78 of
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy}, which is the field a terminal pads a
 * message into, nor the {@code WS-RETURN-MSG PIC X(80)} that {@code COBTUPDT.cbl} declares at its
 * line 61, which is a batch console width from a separate regime.</p>
 *
 * <p>Assumptions: the baseline separates a key failure from a value failure structurally, and this
 * request is the one write shape on which both kinds are possible, because it carries its whole key
 * in the body. {@code COTRTUPC.cbl} declares the key field's flag bare, as
 * {@code 05 WS-EDIT-TTYP-FLAG} at its line 94, and nests the description's beneath a group of its
 * own -- {@code 05 WS-NON-KEY-FLAGS} at line 99 holding {@code 10 WS-EDIT-DESC-FLAGS} at line 100.
 * That distinction is preserved in reporting rather than flattened away. A failure on
 * {@code typeCd} or {@code catCd} is an identity or lookup failure: the value names no row that can
 * exist, or names a parent that does not, so nothing further about the request can be judged against
 * a row. A failure on {@code description} is a value failure on a row whose identity is settled.
 * Reporting both as one undifferentiated list would invite a caller to resubmit a reworded
 * description while the key that made the request unanswerable is still wrong.</p>
 *
 * <p>Assumptions: the seeded value domain is narrower than the contract's, and the two are not the
 * same statement. {@code app/data/ASCII/trancatg.txt} carries the type codes {@code '01'},
 * {@code '02'}, {@code '03'}, {@code '04'}, {@code '05'}, {@code '06'} and {@code '07'} and the
 * category codes {@code 0001}, {@code 0002}, {@code 0003}, {@code 0004} and {@code 0005}, which is
 * what the data holds rather than what a create may choose: the contract admits any four digits in
 * {@code catCd} and any two digits other than {@code '00'} in {@code typeCd}. A create is precisely
 * the operation that extends that domain, so the seed extract must not be read as an
 * enumeration.</p>
 *
 * <p>Parameters, return values and exceptions at the type level: the three record components are
 * this type's parameters and each carries its own at-clause below. A type declaration returns
 * nothing and raises nothing, so no return or exception at-clause belongs here, and the canonical
 * accessors the record form supplies return their component unchanged -- not one of them pads,
 * normalises or zero-fills the category code -- which is the trivial case the Explainability rule
 * admits a single line for. Stating that inapplicability rather than passing over it in silence is
 * deliberate: the same rule counts a docstring omitting parameters or return values among its
 * forbidden patterns, so a reader has to be able to tell a declared inapplicability from an
 * oversight.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is
 * never modified. Where this context's behaviour departs from the baseline's, the departure is
 * recorded in {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere
 * and is referenced from here rather than authored here.</p>
 *
 * @param typeCd the existing transaction type this category is created beneath, as a mandatory
 *     {@code String} of exactly the two characters {@code TRAN-TYPE-CD PIC X(02)} declares at line 6
 *     of {@code app/cpy/CVTRA04Y.cpy} and {@code TRC_TYPE_CODE CHAR(2)} declares at line 2 of
 *     {@code TRNTYCAT.ddl}, stored as {@code type_cd CHAR(2)}; the admitted values run {@code '01'}
 *     through {@code '99'} and {@code '00'} is refused, it is carried as characters so that the
 *     leading zero of a value such as {@code '01'} survives the round trip, and it must name a type
 *     that already exists, which the foreign key decides rather than any constraint here
 * @param catCd the category code to create, as a mandatory {@code String} of exactly four digits
 *     whose leading zeros are part of the value and are preserved verbatim, so that a caller
 *     supplying {@code 0001} creates a row keyed {@code 0001} and never one keyed {@code 1}; it is
 *     four characters wide per {@code TRAN-CAT-CD PIC 9(04)} at line 7 of that copybook, per
 *     {@code TRC_TYPE_CATEGORY CHAR(4)} at line 3 of {@code TRNTYCAT.ddl} and per
 *     {@code DCL-TRC-TYPE-CATEGORY PIC X(4)} at lines 42 and 43 of {@code DCLTRCAT.dcl}, stored as
 *     {@code cat_cd CHAR(4)}, unique only within the type named beside it rather than globally, and
 *     never an integer or any other numeric type
 * @param description what the new category means to a user, as a mandatory {@code String} of at
 *     most the fifty characters {@code TRAN-CAT-TYPE-DESC PIC X(50)} declares at line 8 of that
 *     copybook and {@code TRC_CAT_DATA VARCHAR(50)} declares at line 4 of {@code TRNTYCAT.ddl}; a
 *     maximum rather than an exact width because {@code description VARCHAR(50)} stores the value
 *     without the record's padding, it must carry at least one character that is not a space, and it
 *     is mandatory because the column is declared {@code NOT NULL}
 */
public record TransactionCategoryCreateRequest(

        // WHY : Assumptions: the pattern is spelt as two alternatives rather than as a two-digit
        //       repetition because it has to REFUSE '00', which a repetition admits. The contract
        //       publishes it as ^(?:0[1-9]|[1-9][0-9])$ and the baseline refuses zero outright,
        //       composing 'Tran Type code must not be zero.' from the label at line 826 of
        //       COTRTUPC.cbl and the literal at line 961.
        // WHY : Assumptions: character ranges stand in for the digit shorthand because the
        //       shorthand's reach depends on a matcher flag Bean Validation does not set, so the
        //       ranges say what is meant whatever that flag holds. The anchors the contract writes
        //       are omitted because Bean Validation matches a value entire rather than searching
        //       within it, so an unanchored expression here admits exactly what the anchored one
        //       does.
        @NotBlank
        @Size(min = 2, max = 2)
        @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])")
        String typeCd,

        // WHY : Assumptions: exactly four digits are required, neither fewer nor more, because the
        //       baseline keys this row by positional concatenation rather than by two delimited
        //       fields. The first key in app/data/ASCII/trancatg.txt is literally 010001, the type
        //       01 followed by the category 0001, and the sixty-byte layout balances only if this
        //       field occupies four bytes beside the two of the type code. A three-character value
        //       would therefore not merely be short, it would shift every byte after it.
        // WHY : Trade-offs: the size constraint restates a length the pattern also enforces. The
        //       duplication is accepted so that a wrong length is reported as a length failure
        //       rather than as a pattern failure, which is the difference between a message a
        //       caller can act on and one saying only that the value was refused. It earns its
        //       keep more on this member than on the others, because a caller who dropped the
        //       leading zeros is told the width is wrong instead of that the value is malformed.
        @NotBlank
        @Size(min = 4, max = 4)
        @Pattern(regexp = "[0-9]{4}")
        String catCd,

        // WHY : Assumptions: the character domain is the baseline's own rather than an invention.
        //       Its edit at lines 849 to 899 of COTRTUPC.cbl converts every admitted character to
        //       a space and then trims, treating a value that trims empty as clean; the letters and
        //       digits it converts are the literals declared across its lines 230 to 237, namely
        //       LIT-UPPER holding 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' at line 233, LIT-LOWER holding
        //       'abcdefghijklmnopqrstuvwxyz' at line 235 and LIT-NUMBERS holding '0123456789' at
        //       line 237. A space needs no entry in that list because a supplied space is
        //       indistinguishable from a converted one and the same trim removes both, which is
        //       why the space is admitted here without being enumerated there.
        // WHY : Assumptions: at least one character must not be a space, so the pattern brackets a
        //       mandatory alphanumeric between two optional runs. An all-blank value is refused by
        //       the shape rather than stored as a description, matching the baseline's own blank
        //       branch, which answers ' must be supplied.' composed from the field's own label.
        @NotBlank
        @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*")
        String description) {
}
