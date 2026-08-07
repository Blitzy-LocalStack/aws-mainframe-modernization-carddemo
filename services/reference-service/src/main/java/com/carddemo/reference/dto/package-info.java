/**
 * Wire shapes of the reference-data context, and the charter that names every one of them.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type in this package is a payload: a request shape a caller sends, or a response shape
 * this context answers with. Nothing here reads a datastore, decides a rule or holds state between
 * requests. {@code com.carddemo.reference.mapper} translates between these shapes and
 * {@code com.carddemo.reference.domain}, {@code com.carddemo.reference.api} serialises them, and
 * {@code com.carddemo.reference.service} carries the rules the baseline programs encode. A
 * persistence annotation, a repository reference or a service reference on a type here is a
 * layering fault rather than a shortcut, and the layering test published by {@code common-lib} is
 * what makes that statement enforceable rather than aspirational.
 *
 * <p>This descriptor also settles something no other artifact settles: the NAMES of those shapes.
 * The published contract names schemas, the migration names columns, and neither names a Java type.
 * The sibling packages {@code mapper}, {@code service} and {@code api} are written against whatever
 * this package is called, so the inventory below is a decision with dependents rather than a
 * description of a directory listing.
 *
 * <h2>Parameters, return values, exceptions or errors</h2>
 *
 * <p>A package declaration accepts no argument, yields no value and raises nothing, so this block
 * carries no parameter, return or exception at-clause. User-specified Rule 1 (Explainability)
 * enumerates those three elements at its lines 19 to 21 for callable code; they have no subject on
 * a package descriptor, and they are omitted deliberately rather than written out empty. The rule's
 * purpose element is the section above, and every section below is its inline-rationale element
 * applied to a decision this package owns.
 *
 * <h2>The authoritative contract</h2>
 *
 * <p>{@code services/reference-service/src/main/resources/openapi/reference-api.yaml} is
 * authoritative for every member of every shape named below. It is the OpenAPI 3.1 document
 * springdoc serves and the document the browser client at {@code ui/src/api/reference.ts} is
 * written against, so it is the one place a caller and this service can both be held to. Reconcile
 * a type against that document rather than re-deriving its members from a screen map or a copybook:
 * the document has already resolved the disagreements between those sources, and re-deriving is how
 * two shapes of one payload come into existence.
 *
 * <p>{@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} settles
 * the underlying column types -- {@code type_cd CHAR(2)}, {@code cat_cd CHAR(4)},
 * {@code acct_group_id CHAR(10)}, {@code description VARCHAR(50)},
 * {@code interest_rate NUMERIC(6,2)} and {@code version BIGINT} -- and where that migration and the
 * document disagree, the migration wins, which is the precedence the document itself declares in
 * its own header.
 *
 * <h2>Type inventory and naming convention</h2>
 *
 * <p>The convention is one {@code Request} and one {@code Response} per operation, and each
 * {@code Response} is named after the component schema it satisfies. A new operation therefore
 * arrives here as one request shape and one response shape at most, and a reader holding a schema
 * name can find its Java counterpart without searching. The contract schema each response satisfies
 * is given beside it.
 *
 * <ul>
 *   <li>Response shapes: {@code TransactionTypeResponse} for {@code TransactionType},
 *       {@code TransactionCategoryResponse} for {@code TransactionCategory},
 *       {@code DisclosureGroupRateResponse} for {@code DisclosureGroupRate},
 *       {@code PhoneAreaCodeResponse} for {@code UsPhoneAreaCode}, {@code UsStateResponse} for
 *       {@code UsState}, and {@code UsStateZipPrefixResponse} for {@code UsStateZipPrefix}.</li>
 *   <li>Write requests: {@code TransactionTypeCreateRequest}, {@code TransactionTypeUpdateRequest},
 *       {@code TransactionCategoryCreateRequest} and {@code TransactionCategoryUpdateRequest}.</li>
 *   <li>Read and paging requests: {@code TransactionTypeListRequest},
 *       {@code TransactionCategoryListRequest} and {@code LookupPageRequest}. The first two carry
 *       the paging position, the direction, the type-code filter and the description filter the
 *       transaction-type and transaction-category browses declare. {@code LookupPageRequest} serves
 *       the seeded address lookups, carrying the paging position and direction those browses share
 *       together with the code-class filter the phone-area-code browse alone declares.</li>
 *   <li>Date conversion: {@code DateConversionRequest}, which binds the required date and the
 *       optional mask the date-evaluation read declares as query parameters, and
 *       {@code DateConversionResponse} for {@code DateEvaluationResult}.</li>
 *   <li>Reference-data maintenance batch: {@code MaintenanceActionBatchRequest} for
 *       {@code MaintenanceActionBatchRequest} and {@code MaintenanceActionBatchResponse} for
 *       {@code MaintenanceActionBatchResponse}, together with the two element shapes those two
 *       carry -- {@code MaintenanceActionRequest} for the element schema {@code MaintenanceAction}
 *       and {@code MaintenanceActionOutcomeResponse} for {@code MaintenanceActionOutcome}. Four
 *       types rather than two, because the contract declares the element schemas separately and an
 *       array member typed as anything looser would leave each element unvalidated.</li>
 * </ul>
 *
 * <p>Where a name above differs from the schema it satisfies, the correspondence is recorded here
 * so that neither name has to be guessed at from the other. Assumptions:
 * {@code TransactionTypeUpdateRequest} satisfies the contract schema
 * {@code TransactionTypeReplaceRequest} and {@code TransactionCategoryUpdateRequest} satisfies
 * {@code TransactionCategoryReplaceRequest}, both reached through the operations
 * {@code replaceTransactionType} and {@code replaceTransactionCategory};
 * {@code PhoneAreaCodeResponse} satisfies {@code UsPhoneAreaCode}; and
 * {@code DateConversionResponse} satisfies {@code DateEvaluationResult}. The two maintenance element
 * shapes are the remaining pair: {@code MaintenanceActionRequest} satisfies {@code MaintenanceAction}
 * and {@code MaintenanceActionOutcomeResponse} satisfies {@code MaintenanceActionOutcome}, each
 * taking the suffix that says which direction it travels in, which the bare schema names do not. Alternatives Considered:
 * mirroring each schema name character for character was evaluated and rejected on one ground --
 * the suffix pair is what makes the convention mechanically checkable, and a type ending in
 * {@code ReplaceRequest} beside a type ending in {@code CreateRequest} breaks the pairing that lets
 * a reader tell a request from a response without opening either file. Trade-offs: a reader
 * reconciling a Java type against the document consults this paragraph once, which is the accepted
 * cost of a uniform suffix.
 *
 * <p>Assumptions: the inventory above is the set the sibling packages are written against, and the
 * document remains authoritative over it. An operation the document declares whose shapes are not
 * enumerated here -- the reference-data maintenance batch at
 * {@code POST /api/v1/reference/maintenance-actions}, whose body and reply are the contract schemas
 * {@code MaintenanceActionBatchRequest} and {@code MaintenanceActionBatchResponse} -- takes its
 * shapes under the convention stated above, named after the schema each satisfies, rather than
 * under a second convention invented at the point of need. Recording that here rather than leaving
 * the inventory to read as exhaustive is the difference between a charter a reader can trust and
 * one a reader has to verify against the document line by line.
 *
 * <p>Refactoring Rationale: the batch shapes named in the bullet above are now settled here rather
 * than left to that convention. Deferring them was defensible while nothing consumed them and stopped
 * being so once the batch operation had a caller, because this descriptor's stated job is to settle
 * the NAMES the sibling packages are written against -- and a name derived independently by a mapper
 * author and by a controller author is how two shapes of one payload come into existence. The four
 * names are recorded rather than reasoned about a second time at each point of use.</p>
 *
 * <p>Assumptions: every type named in this inventory is <b>landed</b> as a compilation unit beside
 * this descriptor. Nothing named here is outstanding, so a reader who cannot open one of them has
 * found a gap rather than the expected state. Where a future shape is genuinely planned it is to be
 * marked so at its own entry, because a blanket statement over the whole package is what once
 * instructed a reader to treat every absence as intended.</p>
 *
 * <h2>Deliberate absences</h2>
 *
 * <p>Each shape below is missing on purpose. A reader who does not find one of them here would
 * otherwise supply it in good faith, and everything under {@code app/} is REFERENCE-ONLY material
 * read as the specification for this migration and never modified, so the reasoning has to live on
 * this side of the boundary.
 *
 * <ul>
 *   <li><b>No list-row summary shape.</b> Alternatives Considered: a {@code TransactionTypeSummary}
 *       and a {@code TransactionCategorySummary} narrower than their response types were evaluated
 *       and rejected, because they would restate those types member for member. The browse screen's
 *       row is the triple {@code TRTSEL1I PIC X(1)}, {@code TRTTYP1I PIC X(2)} and
 *       {@code TRTYPD1I PIC X(50)} at lines 78, 84 and 90 of
 *       {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy}; the maintenance screen's detail
 *       is the pair {@code TRTYPCDI PIC X(2)} and {@code TRTYDSCI PIC X(50)} at lines 60 and 66 of
 *       {@code COTRTUP.cpy} beside it. Drop the selection character and the two are identical --
 *       and it is dropped, because choosing a row is a route and an HTTP method here rather than a
 *       character typed into a field. The document corroborates the conclusion independently: its
 *       page envelopes declare their {@code items} member as the entity schema itself, so the
 *       response type IS the page item type carried by
 *       {@code com.carddemo.common.web.PageResponse}.</li>
 *   <li><b>No delete-request shape.</b> Alternatives Considered: a body naming the key to remove
 *       was evaluated and rejected. The key travels in the request path, so the body would be read
 *       by nothing and would give a caller a second place to state a key -- with no rule to follow
 *       when the two disagree. The document declares no request body on either delete operation.
 *       The refusal a caller may receive instead of a deletion is decided against the stored data,
 *       a transaction type a category still references, rather than against anything the caller
 *       could have sent.</li>
 *   <li><b>No disclosure-group list request.</b> Assumptions: the document exposes the disclosure
 *       rate as a read of one three-part key and offers no browse over the group table, so there is
 *       no collection for a list request to page through. That is the operand shape the reader of
 *       this data needs: {@code app/cbl/CBACT04C.cbl} resolves a single rate per balance row at its
 *       lines 415 and 443, falling back to the default group, rather than enumerating rates.</li>
 *   <li><b>No body shape for the rate lookup.</b> Alternatives Considered: a request record for the
 *       three-part key was evaluated and rejected. The account group, transaction type and
 *       transaction category bind directly as path parameters, and inventing a body for a read
 *       would add wire surface the document does not declare -- surface an intermediary is free to
 *       drop, which turns a well-formed call into a puzzling absence rather than a clear
 *       rejection.</li>
 * </ul>
 *
 * <h2>Decisions that belong to the package rather than to one type</h2>
 *
 * <p><b>Create and update are separate request shapes per entity.</b> The baseline uses one record
 * for both paths. {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} declares
 * {@code TTYP-UPDATE-RECORD} at line 130 as {@code TTUP-UPDATE-TTYP-TYPE PIC X(02)} at line 134,
 * {@code TTUP-UPDATE-TTYP-TYPE-DESC PIC X(50)} at line 135 and {@code FILLER PIC X(08)} at line
 * 136, and its own header comment on line 132 gives the record length as 60. The batch driver
 * {@code COBTUPDT.cbl} does the same with a single {@code WS-INPUT-REC} at lines 71 to 77 --
 * {@code INPUT-REC-TYPE PIC X(1)}, {@code INPUT-REC-NUMBER PIC X(2)} and
 * {@code INPUT-REC-DESC PIC X(50)} -- which it dispatches on the action character at its line 110.
 *
 * <p>Alternatives Considered: one shared write shape per entity, mirroring that record, was
 * evaluated and rejected. It would have to declare the key member optional, because the update path
 * does not carry one, and an optional key cannot be validated as mandatory on the create path -- so
 * a create submitted without a key would reach the service layer to be refused there instead of
 * being refused by the shape itself. Refactoring Rationale: the record the baseline reuses is a
 * flat buffer of declared width read from a file, where one layout for two actions costs nothing
 * because the action arrives beside it in the same buffer. A JSON body has no such companion field;
 * the method and the path carry the action instead, and the document declares the two bodies with
 * genuinely different member sets -- the create body requires the key and the description and
 * carries no version, while the replace body carries the description and the version and takes the
 * key from the path. Two shapes state that difference; one shape hides it.
 *
 * <p><b>The paging members are written out in each list request rather than inherited.</b>
 * Trade-offs: a Java record cannot extend a class, so inheritance is unavailable to these types
 * without abandoning the record form that keeps them immutable and their members documented. A
 * nested paging sub-record was the remaining alternative and was rejected on a wire-visible ground:
 * a nested object binds as a dotted or bracketed query parameter, which is a different query string
 * from the one the document declares, so the shapes would no longer match the contract they exist
 * to satisfy. The accepted cost is that the position and direction members appear in
 * {@code TransactionTypeListRequest}, {@code TransactionCategoryListRequest} and
 * {@code LookupPageRequest} rather than in one place, and that a change to paging is therefore a
 * change in each of them.
 *
 * <h2>Invariants every type here obeys</h2>
 *
 * <p><b>One. A code is a string, and its leading zeros are part of it.</b> The document declares
 * the transaction-category code as a string of exactly four digits, gives {@code '0001'} and
 * {@code '0005'} among its examples, and records that nothing in the migrated system compares it
 * numerically. The stored column is {@code cat_cd CHAR(4)}; the baseline host variable is
 * {@code DCL-TRC-TYPE-CATEGORY PIC X(4)} at lines 42 to 43 of
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}; and the seed row
 * {@code 010001Regular Sales Draft} in {@code app/data/ASCII/trancatg.txt} carries the type
 * {@code 01} followed by the category {@code 0001}. Alternatives Considered: an integer member was
 * evaluated and rejected -- it renders {@code 0001} as {@code 1}, and a key that renders
 * differently from the way it is stored is a key a caller cannot echo back to address the row it
 * just read. The same holds for the type code, the account group, the area code, the state code and
 * the state and postal-code prefix, each of which is character data of a declared width.
 *
 * <p><b>Two. A rate is an exact decimal value and travels as a JSON string.</b> The document
 * declares the interest rate as a string carrying always exactly two fractional digits, with
 * {@code '15.00'} among its examples; the stored column is {@code interest_rate NUMERIC(6,2)}; and
 * inside this service the value is a {@code java.math.BigDecimal} at scale 2 rounded
 * {@code HALF_UP}, reached through {@code com.carddemo.common.money.Money} and serialised by
 * {@code com.carddemo.common.money.MoneyModule}, which renders through that value type's
 * plain-string accessor. Migration rule T3 states the obligation for the whole system, and it holds
 * here without exception.
 *
 * <p>Assumptions: the rate is an arithmetic operand rather than a value merely displayed, which is
 * what makes the typing consequential instead of stylistic. {@code app/cbl/CBACT04C.cbl} computes
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at its lines 464 to 465, so a rate that had passed
 * through IEEE-754 binary floating point would carry its representation error into every interest
 * transaction that computation generates, and the error would be invisible: the result stays
 * plausible while being wrong. No IEEE-754 binary floating point appears anywhere in this package.
 * Alternatives Considered: a JSON number was evaluated and rejected for the same reason one step
 * further out -- most clients parse a JSON number into an IEEE-754 binary value, so publishing a
 * number would surrender the exactness at the boundary even though it is held exactly on both sides
 * of it.
 *
 * <p><b>Three. An identifier is a digit-validated string.</b> Assumptions: the baseline itself
 * settles this rather than the target choosing it. {@code app/cpy/CVCRD01Y.cpy} declares each
 * identifier as character data and redefines it as numeric immediately beside it -- the account
 * identifier at lines 34 to 36, the card number at lines 37 to 39 and the customer identifier at
 * lines 40 to 42 -- and {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} does the same for its
 * diagnostic code at lines 44 to 46. The decisive detail is {@code VALUE SPACES} on the character
 * declaration of each pair: a numeric declaration cannot hold spaces, so the character declaration
 * is the one that carries the value and the numeric redefinition exists for arithmetic alone. A
 * request shape here therefore accepts the digits as text and constrains them by pattern and
 * length, and never as a numeric type that would silently accept a sign, an exponent or a value
 * with its leading zeros discarded.
 *
 * <p><b>Four. A validation failure is a per-field array rather than one concatenated sentence.</b>
 * Migration rule T7 turns the baseline's per-field validation flags into structure:
 * {@code com.carddemo.common.error.ApiError} carries a {@code fieldErrors} member, and each entry
 * is a {@code FieldError} of field identity, a
 * {@code com.carddemo.common.validation.FieldValidationFlag} state and the help text for that
 * field. Assumptions: the array is keyed by field identity and not by condition, and one condition
 * may therefore contribute several entries. The date edit reports the year, the month and the day
 * together from a single verdict, so an array keyed by condition would have to discard all but one
 * of those identities or invent a composite one, and a caller could not mark the offending inputs.
 *
 * <p><b>Five. The aggregate message width this package answers in is 75.</b>
 * {@code ApiError.MESSAGE_RENDERING_WIDTH} is 75, read from {@code CCARD-ERROR-MSG PIC X(75)} at
 * line 28 of {@code app/cpy/CVCRD01Y.cpy} and {@code CCARD-RETURN-MSG PIC X(75)} on line 29 beside
 * it, and both online programs of this context compose at that same width --
 * {@code WS-RETURN-MSG PIC X(75)} at line 249 of
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and at line 167 of {@code COTRTUPC.cbl}.
 * Assumptions: the near misses are recorded because each is a plausible wrong answer. The screen
 * maps declare {@code ERRMSGI PIC X(78)}, at line 78 of {@code COTRTUP.cpy} and line 228 of
 * {@code COTRTLI.cpy}, which is the field a terminal pads a message into rather than the width at
 * which the message is composed. And {@code COBTUPDT.cbl} declares {@code WS-RETURN-MSG PIC X(80)}
 * at its line 61, which is the console width a batch driver displays through, a separate regime
 * from this interface.
 *
 * <p><b>Six. Paging is by key, and no window size travels.</b>
 * {@code com.carddemo.common.web.PageResponse} is exactly {@code items}, {@code firstKey},
 * {@code lastKey} and {@code hasNext} -- no previous-page flag, no page size, no page number and no
 * total count -- so a list request shape carries the paging position and the direction and nothing
 * further. Assumptions: the document declares no page-size, page-number, offset or total-pages
 * parameter and states that none may be added, so the window is the service's to decide and a size
 * member on a request shape here would publish a parameter the contract does not.
 *
 * <p>Alternatives Considered: paging by ordinal is shorter to express and was rejected on a
 * behavioural ground rather than a preference -- a window taken by ordinal skips rows and repeats
 * rows when another caller inserts or deletes between two requests, because what precedes the
 * ordinal is re-counted against a changed set, while a position that names a key cannot do either.
 * Reference data is maintained through the very operations these shapes serve, so that is a real
 * case rather than a hypothetical one. Assumptions: a direction is meaningful only alongside a
 * position and the two are sent together or not at all; and the baseline's forward and backward
 * cursors treat the position they are handed asymmetrically, inclusively going forward and
 * exclusively going back, so preserving that asymmetry is the service layer's obligation and is not
 * encoded in these shapes.
 *
 * <h2>What no type here does</h2>
 *
 * <ul>
 *   <li><b>No import from another bounded context.</b> Shared types arrive from
 *       {@code com.carddemo.common} alone, and the ones named above are the whole of it:
 *       {@code web.PageResponse}, {@code error.ApiError}, {@code validation.FieldValidationFlag},
 *       {@code money.Money} and {@code money.MoneyModule}. Assumptions: an import reaching into
 *       another context's types would compile inside this one reactor and then break the moment the
 *       two contexts are deployed as separate containers, which is the arrangement this migration
 *       targets, and the layering test published by {@code common-lib} refuses it outright.</li>
 *   <li><b>No generated accessor.</b> Assumptions: Rule 1 requires a docstring on every member at
 *       its line 15, and an annotation processor that writes accessors cannot write their
 *       documentation, so the accessors a record form already gives are used and each component
 *       carries its own at-param entry. This is a documentation constraint rather than a preference
 *       about brevity.</li>
 *   <li><b>No renamed member.</b> Renames exist elsewhere in this migration and are registered in
 *       the traceability document named below; none of them has a subject in this context, so a
 *       member here carries the name the document declares.</li>
 *   <li><b>No suppression of the documentation gate.</b> {@code config/checkstyle/checkstyle.xml}
 *       registers no comment filter and no annotation filter, so neither a suppression comment nor
 *       a suppression annotation has any effect on it; its companion suppressions file covers
 *       generated sources and test fixtures and reaches nothing under {@code src/main/java}. A
 *       member that cannot be documented is a member that does not belong here.</li>
 * </ul>
 *
 * <h2>Divergence register</h2>
 *
 * <p>Where this context's behaviour departs from the baseline's, the baseline is left exactly as it
 * stands and the departure is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}, the register of every documented
 * behavioural divergence in this migration. That document is owned elsewhere: read it, and neither
 * author nor edit it from here.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Two gates require it, and they require different things of it. User-specified Rule 1
 * (Explainability) names a module entry point as a documentation subject at its line 15, and in
 * Java a package-level Javadoc comment has nowhere to live except a {@code package-info}
 * compilation unit. {@code config/checkstyle/checkstyle.xml} then enforces that clause in two
 * halves which a reader is otherwise likely to mistake for a duplication: {@code JavadocPackage}
 * sits at {@code Checker} level and asserts only that this file EXISTS, while
 * {@code MissingJavadocPackage} sits inside {@code TreeWalker} and asserts that it CARRIES Javadoc.
 * A file holding nothing but a package declaration satisfies the first and fails the second.
 * {@code services/pom.xml} binds that gate to the Maven {@code validate} phase with a violation
 * threshold of warning and a fail-on-violation flag, so a lapse here stops every module of the
 * reactor before a single class is compiled. Rule 1 closes at its line 43 with a gate that is
 * conjunctive: a docstring and a rationale comment are both required, and code missing either one
 * fails review.
 */
package com.carddemo.reference.dto;
