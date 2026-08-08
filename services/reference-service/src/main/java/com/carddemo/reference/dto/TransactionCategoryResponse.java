package com.carddemo.reference.dto;

/**
 * One transaction category as this service publishes it, satisfying the contract schema
 * {@code TransactionCategory}.
 *
 * <p>Purpose: this is the outbound wire shape of a transaction-category read, of a create and of a
 * replace, and it is at the same time the element type carried inside
 * {@code com.carddemo.common.web.PageResponse} by the keyset-paged browse. It holds no logic,
 * validates nothing and reaches no store: {@code com.carddemo.reference.mapper} builds it from the
 * stored row and {@code com.carddemo.reference.api} serialises it. A persistence annotation, a rule
 * check or an exception mapping declared on this type would be a layering fault rather than a
 * convenience, and the layering test published by {@code common-lib} is what keeps that statement
 * enforceable rather than aspirational.</p>
 *
 * <p>Alternatives Considered: an integer member for {@code catCd} was evaluated and rejected, and
 * this is the load-bearing decision of the file. An integer renders the stored value {@code 0001}
 * as {@code 1}, and the leading zeros are not presentation -- they are part of the key. Three
 * independent baseline sources carry this field as characters and agree with one another.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at its line 3, a character column, and names it in the
 * composite {@code PRIMARY KEY(TRC_TYPE_CODE,TRC_TYPE_CATEGORY)} at its line 5. The DCLGEN host
 * structure beside it, {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}, generates the same
 * column across its lines 42 and 43 as {@code DCL-TRC-TYPE-CATEGORY PIC X(4)}, a character host
 * variable and not a numeric one, so the program that reads the table reads characters. And the seed
 * extract {@code app/data/ASCII/trancatg.txt} stores the codes zero-padded, its first row reading
 * {@code 010001Regular Sales Draft}, which is the type {@code 01} followed by the category
 * {@code 0001} in the concatenated key form. Two consequences make the integer option a defect
 * rather than a matter of taste: a key that renders differently from the way it is stored is a key a
 * caller cannot echo back to address the row it just read, and a single category is addressed
 * through two path segments, the type code and then the category code, so a caller composing that
 * path from an integer would ask for the segment {@code 1} for a row stored under {@code 0001}. The
 * contract closes the question independently -- it declares this member as a string of exactly four
 * digits, records that nothing in the migrated system compares it numerically, and gives
 * {@code '0001'} and {@code '0005'} among its examples.</p>
 *
 * <p>Assumptions: the character form governs the wire and the column even though
 * {@code app/cpy/CVTRA04Y.cpy} declares the field as {@code TRAN-CAT-CD PIC 9(04)} at its line 7,
 * and the two declarations do not actually disagree about a single byte. That picture is a display
 * numeric, which occupies one character position per digit, so for the value {@code 0001} those four
 * positions hold the characters {@code 0}, {@code 0}, {@code 0} and {@code 1}; the numeric picture
 * describes what a program may compute on rather than a narrower storage form. The Db2 lineage above
 * states that character reading outright, which is why
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} settles the
 * column as {@code cat_cd CHAR(4)}. Nothing here is a behavioural divergence, and it is worth being
 * explicit about that so a reader does not go looking for one: the byte content the baseline writes
 * and the byte content this member carries are the same, and the digits-only half of the picture is
 * expressed by the contract's four-digit pattern rather than discarded.</p>
 *
 * <p>Refactoring Rationale: the {@code FILLER PIC X(04)} at line 9 of
 * {@code app/cpy/CVTRA04Y.cpy} is not carried across, and it is the one filler of that record --
 * 2 plus 4 plus 50 plus 4 accounts for every one of the 60 declared bytes its own header states.
 * Those bytes pad the record out to a positional length, which a JSON object has no use for: a
 * reader walking a flat 60-byte record has to know where the next record begins, whereas a member of
 * an object is delimited by the encoding itself. Migration rule T1 makes the drop the rule for every
 * record rather than a choice taken at this one, and it also requires that the drop be recorded,
 * which is what this paragraph is for. The seed rows carry {@code 0000} in those bytes, so the
 * omission discards no value a caller could have read.</p>
 *
 * <p>Trade-offs: the two code members are declared as siblings rather than nested inside a key
 * sub-record, even though {@code app/cpy/CVTRA04Y.cpy} groups them together under
 * {@code TRAN-CAT-KEY} at its line 5. A nested member would serialise as a nested JSON object, which
 * is not the shape the contract declares: its {@code TransactionCategory} schema lists
 * {@code typeCd}, {@code catCd}, {@code description} and {@code version} as required members of one
 * object and sets {@code additionalProperties} false, so a nested key would satisfy neither the
 * member list nor the refusal of members the schema does not name. Flattening also matches the way a
 * category is addressed, through two path segments rather than through one six-character token. The
 * compromise accepted is that the grouping the copybook expresses is no longer visible in the
 * declaration, so the fact that these two members form ONE key rather than two independent
 * attributes has to be carried in prose -- and it is carried here and in the paragraph below,
 * because a reader who took {@code catCd} for a globally unique identifier would look up the wrong
 * row.</p>
 *
 * <p>Assumptions: the keyset key of the browse this type is paged through is the composite
 * {@code (type code ASC, category code ASC)}, and no member of this type is a paging position.
 * {@code V1__reference.sql} declares
 * {@code CONSTRAINT pk_transaction_categories PRIMARY KEY (type_cd, cat_cd)}, and the baseline
 * asserts the same order through {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl}, whose
 * {@code CREATE UNIQUE INDEX} names {@code (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)}.
 * {@code com.carddemo.common.web.PageResponse} carries {@code items}, {@code firstKey},
 * {@code lastKey} and {@code hasNext} and nothing else -- no backward indicator, no window size and
 * no row tally -- and each cursor it carries is an opaque token minted by this service. A consumer
 * must therefore never send either code published here as a cursor, and a paging member added to
 * this type would publish a position the contract does not declare.</p>
 *
 * <p>Assumptions: a row of this shape is what can refuse a transaction-type deletion, and that
 * refusal is rendered elsewhere. {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
 * across its lines 6 and 7 {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES
 * CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT}, which {@code V1__reference.sql} preserves
 * as {@code fk_transaction_categories_type}. Deleting a type that a category still names is
 * therefore refused by the database, the refusal arrives as SQLSTATE {@code 23503}, and
 * {@code com.carddemo.common.error.GlobalExceptionHandler} renders it as HTTP 409. This type carries
 * no part of that: no status member, no error member, and no advice of any kind. The mapping is
 * inherited from {@code common-lib} and is never re-declared in this module, because a second
 * renderer for one condition is how two services come to answer that condition differently. The
 * relationship is recorded here only so that a reader of this type understands why a row of it can
 * block an operation on another resource.</p>
 *
 * <p>Assumptions: {@code version} is not a baseline field and is present because the published
 * contract requires it. The {@code TransactionCategory} schema declares it required alongside the
 * other three members, and {@code V1__reference.sql} supplies it as
 * {@code version BIGINT NOT NULL DEFAULT 0}. A caller reads it with the row and sends it back on a
 * replace, so a write against a row another caller has moved on is refused rather than overwriting
 * that change. It is a revision counter rather than a timestamp: comparing two values tells a caller
 * only that they differ. A seeded row carries zero, the value the migration declares as the column
 * default, so zero is a legitimate value to send rather than a missing one.</p>
 *
 * <p>Assumptions: the seeded value domain is narrower than the contract's, and the two are not the
 * same statement. {@code app/data/ASCII/trancatg.txt} carries the type codes {@code '01'},
 * {@code '02'}, {@code '03'}, {@code '04'}, {@code '05'}, {@code '06'} and {@code '07'} and the
 * category codes {@code 0001}, {@code 0002}, {@code 0003}, {@code 0004} and {@code 0005}, which is
 * what the data holds rather than what the contract permits: the contract admits any four digits in
 * {@code catCd} and any two digits other than {@code '00'} in {@code typeCd}. A value reaching this
 * type is therefore not confined to the seeded literals, and a reader must not read that extract as
 * an enumeration.</p>
 *
 * <p>Parameters, return values and exceptions at the type level: the four record components are this
 * type's parameters and each carries its own at-clause below. A type declaration returns nothing and
 * raises nothing, so no return or exception at-clause belongs here, and the canonical accessors the
 * record form supplies return their component unchanged -- not one of them pads, normalises or
 * reformats a value, least of all the category code -- which is the trivial case the Explainability
 * rule admits a single line for. That inapplicability is stated rather than passed over in silence,
 * because the same rule counts a docstring omitting parameters or return values among its forbidden
 * patterns, and a reader has to be able to tell a declared inapplicability from an oversight.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is
 * never modified. Where this context's behaviour departs from the baseline's, the departure is
 * recorded in {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere
 * and is referenced from here rather than authored here.</p>
 *
 * @param typeCd the transaction type this category belongs to, as a {@code String} of the two
 *     characters {@code TRAN-TYPE-CD PIC X(02)} declares at line 6 of
 *     {@code app/cpy/CVTRA04Y.cpy} and {@code TRC_TYPE_CODE CHAR(2)} declares at line 2 of
 *     {@code TRNTYCAT.ddl}, stored as {@code type_cd CHAR(2)}; it must name an existing transaction
 *     type, which the foreign key enforces, and it is carried as characters so that the leading zero
 *     of a value such as {@code '01'} survives the round trip
 * @param catCd the category code, as a {@code String} of exactly four digits whose leading zeros are
 *     part of the value and are preserved verbatim, so that a row stored as {@code 0001} is
 *     published as {@code 0001} and never as {@code 1}; four characters wide per
 *     {@code TRAN-CAT-CD PIC 9(04)} at line 7 of that copybook, per
 *     {@code TRC_TYPE_CATEGORY CHAR(4)} at line 3 of {@code TRNTYCAT.ddl} and per
 *     {@code DCL-TRC-TYPE-CATEGORY PIC X(4)} at lines 42 and 43 of {@code DCLTRCAT.dcl}, stored as
 *     {@code cat_cd CHAR(4)}, unique only within its parent type rather than globally, and never an
 *     integer or any other numeric type
 * @param description what this category means to a user, as a {@code String} of at most the fifty
 *     characters {@code TRAN-CAT-TYPE-DESC PIC X(50)} declares at line 8 of that copybook and
 *     {@code TRC_CAT_DATA VARCHAR(50)} declares at line 4 of {@code TRNTYCAT.ddl}, stored as
 *     {@code description VARCHAR(50)} and published with the record's trailing padding removed
 *     because that padding is not data
 * @param version the revision of the stored row this reply describes, as a {@code long} matching the
 *     {@code version BIGINT} column; a replace sends it back unchanged so that a write against a row
 *     another caller has moved on is refused rather than overwriting that change. A seeded row
 *     carries zero, which is a value to send rather than a value to withhold
 */
public record TransactionCategoryResponse(
        String typeCd,
        String catCd,
        String description,
        long version) {
}
