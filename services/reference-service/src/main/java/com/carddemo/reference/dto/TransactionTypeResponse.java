package com.carddemo.reference.dto;

/**
 * One transaction type as this service publishes it, satisfying the contract schema
 * {@code TransactionType}.
 *
 * <p>Purpose: this is the outbound wire shape of a transaction-type read, of a create and of a
 * replace, and it is at the same time the element type carried inside
 * {@code com.carddemo.common.web.PageResponse} by the keyset-paged list. It holds no logic,
 * validates nothing and reaches no store: {@code com.carddemo.reference.mapper} builds it from the
 * stored entity and {@code com.carddemo.reference.api} serialises it. A persistence annotation or a
 * rule check on this type would be a layering fault rather than a convenience, and the layering
 * test published by {@code common-lib} is what keeps that statement enforceable rather than
 * aspirational.</p>
 *
 * <p>Assumptions: the two data members are the whole of the baseline record, and their declared
 * widths are not a judgement call. {@code app/cpy/CVTRA03Y.cpy} declares
 * {@code TRAN-TYPE PIC X(02)} at its line 5 and {@code TRAN-TYPE-DESC PIC X(50)} at its line 6, its
 * own header giving the record length as 60. The Db2 table definition
 * {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} declares the same pair as
 * {@code TR_TYPE CHAR(2) NOT NULL} and {@code TR_DESCRIPTION VARCHAR(50) NOT NULL}. The DCLGEN host
 * structure {@code dcl/DCLTRTYP.dcl} beside it carries {@code DCL-TR-TYPE PIC X(2)} at its line 38
 * and generates the description at its lines 42 to 46 as a length halfword paired with
 * {@code DCL-TR-DESCRIPTION-TEXT PIC X(50)}, which is DCLGEN's signature for a varying-length
 * column. And the maintenance screen map {@code cpy-bms/COTRTUP.cpy} reaches the terminal with
 * {@code TRTYPCDI PIC X(2)} at its line 60 and {@code TRTYDSCI PIC X(50)} at its line 66.
 * Independent sources agreeing on both widths is what makes any deviation here an error rather than
 * a preference.</p>
 *
 * <p>Assumptions: the code is character data of a declared width while the description is character
 * data of a declared maximum, and the difference between the two is deliberate. The code's width is
 * part of the key contract, which is why
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} stores it as
 * {@code type_cd CHAR(2)}: a category key is formed by positional concatenation, so the seed row
 * {@code 010001} in {@code app/data/ASCII/trancatg.txt} is the type {@code 01} followed by the
 * category {@code 0001}, and a code rendered as {@code 1} rather than {@code 01} would not locate
 * its own categories. The description's trailing blanks are padding to the record's declared length
 * rather than data, which is why the same migration stores it as {@code description VARCHAR(50)}
 * and why the Db2 definition reached a varying-length column for it independently. Both members are
 * therefore published as {@code String} and neither as a numeric type, so that a leading zero
 * survives the round trip and a caller can echo a value back to address the row it read. The seeded
 * domain is the codes {@code '01'} through {@code '07'} of {@code app/data/ASCII/trantype.txt},
 * whose first row reads {@code 01} then {@code Purchase}.</p>
 *
 * <p>Refactoring Rationale: the {@code FILLER PIC X(08)} at line 7 of {@code app/cpy/CVTRA03Y.cpy}
 * is not carried across, and it is the one filler of that record -- 2 plus 50 plus 8 accounts for
 * every one of the 60 declared bytes. Those bytes are padding to a positional record length, which
 * a JSON object has no use for: a reader walking a flat sixty-byte record needs to know where the
 * next record begins, whereas a member of an object is delimited by the encoding itself. Migration
 * rule T1 makes the drop the rule for every record rather than a choice taken at this one, and it
 * also requires that the drop be recorded, which is what this paragraph is for. The seed rows carry
 * {@code 00000000} in those bytes, so the omission discards no value a caller could have read.</p>
 *
 * <p>Alternatives Considered: a narrower {@code TransactionTypeSummary} for list rows was evaluated
 * and rejected, so this one type serves both the collection and the single resource. The browse map
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy} builds its row from
 * {@code TRTSEL1I PIC X(1)} at line 78, {@code TRTTYP1I PIC X(2)} at line 84 and
 * {@code TRTYPD1I PIC X(50)} at line 90, while the maintenance map carries the latter pair alone;
 * remove the selection character and the two are the same fields, so a summary type would restate
 * this one member for member and give a reader two shapes of one payload to keep in agreement. The
 * selection character does not travel at all, because choosing a row here is a route and an HTTP
 * method rather than a character typed into a field. The contract corroborates the conclusion
 * independently: the {@code items} member of its {@code TransactionTypePage} schema is a reference
 * to this very schema, so the response type IS the page item type.</p>
 *
 * <p>Assumptions: the set this type is paged through is ordered by the type code ascending, and no
 * member of this type is a paging position. {@code V1__reference.sql} declares
 * {@code CONSTRAINT pk_transaction_types PRIMARY KEY (type_cd)}, and the baseline asserts the same
 * order through the unique index on {@code TRANSACTION_TYPE (TR_TYPE ASC)} that
 * {@code app/app-transaction-type-db2/ddl/XTRNTYPE.ddl} declares.
 * {@code com.carddemo.common.web.PageResponse} carries {@code items}, {@code firstKey},
 * {@code lastKey} and {@code hasNext} and nothing else -- no backward indicator, no window size and
 * no row tally -- and each cursor it carries is an opaque token minted by this service. A consumer
 * must therefore never send the code published here as a cursor, and a paging member added to this
 * type would publish a position the contract does not declare.</p>
 *
 * <p>Assumptions: the version is not a baseline field, and it is present because the published
 * contract requires it.
 * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml} declares the
 * {@code TransactionType} schema with {@code typeCd}, {@code description} and {@code version} all
 * required and with {@code additionalProperties} false, so a body omitting the version does not
 * satisfy the schema it claims to satisfy; the same document declares the version required on the
 * replace body; and {@code V1__reference.sql} supplies it as
 * {@code version BIGINT NOT NULL DEFAULT 0}. Reconciling a type against that document rather than
 * re-deriving its members from a copybook is the precedence this package's charter states, and it
 * is what resolves the apparent discrepancy between a record of two data fields and a wire shape of
 * three members.</p>
 *
 * <p>Alternatives Considered: withholding the version from the read, which would leave this type at
 * the two members the copybook declares. Rejected on a specific defect rather than on taste. The
 * replace body requires a version, so a caller that had never been given one could not compose a
 * compliant replace at all; and the optimistic-lock check this migration carries over from
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} -- which snapshots the record it read into
 * {@code TTUP-OLD-DETAILS} at its lines 328 to 331, compares that snapshot against the stored row
 * at its line 783 and commits only when the two still agree -- would have no operand on this side
 * of the boundary. Publishing the counter is what makes that same guarantee expressible without a
 * client re-sending the whole record it read.</p>
 *
 * <p>Trade-offs: the description is published with the record's trailing padding removed, so a
 * consumer comparing it against a value taken straight out of a fixed-width extract has to pad it
 * back to the declared width itself. That cost is accepted because the alternative fails in a way
 * that is hard to see: carrying the padding would make every equality comparison on the wire depend
 * on invisible trailing blanks, and a value that compares unequal to the same text without them
 * reports no error anywhere.</p>
 *
 * <p>Parameters, return values and exceptions at the type level: the three record components are
 * this type's parameters and each carries its own at-clause below. A type declaration returns
 * nothing and raises nothing, so no return or exception at-clause belongs here, and the canonical
 * accessors the record form supplies return their component unchanged, which is the trivial case the
 * Explainability rule admits a single line for. That inapplicability is stated rather than passed
 * over in silence, because the same rule counts a docstring omitting parameters or return values
 * among its forbidden patterns, and a reader has to be able to tell a declared inapplicability from
 * an oversight.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is
 * never modified. Where this context's behaviour departs from the baseline's, the departure is
 * recorded in {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere
 * and is referenced from here rather than authored here.</p>
 *
 * @param typeCd the transaction type code as a {@code String} of the two characters
 *     {@code TRAN-TYPE PIC X(02)} declares at line 5 of {@code app/cpy/CVTRA03Y.cpy}, stored as
 *     {@code type_cd CHAR(2)}; carried as characters so that a leading zero survives the round trip,
 *     and never to be treated as a paging cursor
 * @param description what this type means to a user, as a {@code String} of at most the fifty
 *     characters {@code TRAN-TYPE-DESC PIC X(50)} declares at line 6 of that copybook, stored as
 *     {@code description VARCHAR(50)} and published with the record's trailing padding removed
 *     because that padding is not data
 * @param version the revision of the stored row this reply describes, as a {@code long} matching the
 *     {@code version BIGINT} column; a replace sends it back unchanged so that a write against a row
 *     another caller has moved on is refused rather than overwriting that change. A seeded row
 *     carries zero, the value the migration declares as the column default, so zero is a legitimate
 *     value to send rather than a missing one
 */
public record TransactionTypeResponse(String typeCd, String description, long version) {
}
