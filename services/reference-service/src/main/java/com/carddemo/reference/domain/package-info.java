//=============================================================================
// WHY : Assumptions: every column name, SQL type, width and nullability stated
//       in the charter below was read out of
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which is the authority for the physical shape of
//       this schema. Where this prose and that migration could ever disagree,
//       the migration is right and this file is the defect. Naming the authority
//       here, once, is what stops six entity authors from each inferring a
//       column type from a copybook picture clause and arriving at six answers.
// WHY : Alternatives Considered: this charter is authored before any of the six
//       entity types it governs, and the alternative was to withhold it until
//       they existed. Rejected, because the rulings recorded below are exactly
//       the questions each author would otherwise settle alone -- whether a
//       category code is character or numeric data, whether a disclosure group
//       id may be trimmed, which entities carry a version counter -- and six
//       independent settlements of one question is how a schema acquires two
//       meanings. The cost accepted is that the inventory below reads as
//       present tense when most of it is still a target, which the second
//       section states plainly rather than leaving to inference.
/**
 * Persistence mapping of the reference-data bounded context: six JPA entities over the six tables
 * of the PostgreSQL {@code reference} schema, each transcribed from a named COBOL or Db2 baseline
 * contract.
 *
 * <h2>Purpose</h2>
 *
 * <p>The package root is {@code com.carddemo.reference} and {@code domain} is its persistence
 * layer. A type here maps a Java object onto a physical row and does nothing else. It carries no
 * business rule, because a transcribed COBOL paragraph becomes a named method in
 * {@code com.carddemo.reference.service}; no request or response shape, because those live in
 * {@code com.carddemo.reference.dto}; no query, because keyed and paged access lives in
 * {@code com.carddemo.reference.repository}; and no byte offset or padding convention, because
 * every representation concern of a declared-length VSAM record belongs to the codecs in
 * {@code com.carddemo.common.codec} and to {@code com.carddemo.reference.mapper}. Exactly one
 * decision is left for a type here to make, and constraining it is why this charter exists: which
 * physical column, of which type and width, in which schema, each baseline field lands on.</p>
 *
 * <p>Assumptions: the {@code reference} schema is created neither here nor by this service's own
 * migration. Schema, role and grant bootstrap belongs to
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, so every mapping in this package assumes
 * the schema already exists and never attempts to create it.</p>
 *
 * <h2>The target contract, and the state of this directory</h2>
 *
 * <p>Assumptions: every entity name, table name and count below describes this package's
 * <b>target contract</b>, not the set of files present beside this charter. At the point this
 * charter was authored the directory holds this descriptor alone, so each of the six types named
 * below is <b>planned rather than missing</b>, and a reader who cannot open one has found the
 * expected state rather than a gap. The closed set is seven compilation units: this descriptor and
 * the six entities. Nothing else belongs here.</p>
 *
 * <h2>The six entities and the tables they map</h2>
 *
 * <p>Each entry gives the entity, the table it maps, the baseline contract it is transcribed from,
 * and the columns as {@code V1__reference.sql} declares them. Every path below that begins
 * {@code app/} is reference material, read but never modified.</p>
 *
 * <dl>
 *   <dt>{@code TransactionType} maps {@code reference.transaction_types}</dt>
 *   <dd>From {@code 01 TRAN-TYPE-RECORD}, {@code app/cpy/CVTRA03Y.cpy}, a record of declared
 *       length 60. Columns: {@code type_cd CHAR(2)} as the primary key, from L5
 *       {@code TRAN-TYPE PIC X(02)}; {@code description VARCHAR(50)}, from L6
 *       {@code TRAN-TYPE-DESC PIC X(50)}; and {@code version BIGINT NOT NULL DEFAULT 0}. The L7
 *       {@code FILLER PIC X(08)} is the one padding field of this record and is not carried across;
 *       2 + 50 + 8 accounts for all 60 declared bytes, so no field is left unexamined. The Db2
 *       objects of the transaction-type extension corroborate the two data columns
 *       independently.</dd>
 *
 *   <dt>{@code TransactionCategory} maps {@code reference.transaction_categories}</dt>
 *   <dd>From {@code 01 TRAN-CAT-RECORD}, {@code app/cpy/CVTRA04Y.cpy}, a record of declared length
 *       60. Columns: {@code type_cd CHAR(2)} and {@code cat_cd CHAR(4)} forming the composite
 *       primary key, from L6 and L7, both of which sit under the L5 group {@code TRAN-CAT-KEY};
 *       {@code description VARCHAR(50)}, from L8; and {@code version BIGINT NOT NULL DEFAULT 0}.
 *       The L9 {@code FILLER PIC X(04)} is not carried across; 2 + 4 + 50 + 4 accounts for all 60
 *       declared bytes. This table also carries the one referential rule of the schema, a foreign
 *       key on {@code type_cd} declared {@code ON DELETE RESTRICT}, which the baseline states at
 *       {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 to L7 and which surfaces to a
 *       caller as HTTP 409 rather than as a driver error.</dd>
 *
 *   <dt>{@code DisclosureGroup} maps {@code reference.disclosure_groups}</dt>
 *   <dd>From {@code 01 DIS-GROUP-RECORD}, {@code app/cpy/CVTRA02Y.cpy}, a record of declared length
 *       50. Columns: {@code acct_group_id CHAR(10)}, {@code tran_type_cd CHAR(2)} and
 *       {@code tran_cat_cd CHAR(4)} forming the composite primary key, from L6, L7 and L8, all
 *       three under the L5 group {@code DIS-GROUP-KEY}; and {@code interest_rate NUMERIC(6,2)},
 *       from L9 {@code DIS-INT-RATE PIC S9(04)V99}. The L10 {@code FILLER PIC X(28)} is not carried
 *       across; 10 + 2 + 4 + 6 + 28 accounts for all 50 declared bytes. This entity carries no
 *       version column, which the version ruling below explains.</dd>
 *
 *   <dt>{@code UsPhoneAreaCode} maps {@code reference.us_phone_area_codes}</dt>
 *   <dd>From {@code app/cpy/CSLKPCDY.cpy} L24, {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}.
 *       Columns: {@code area_cd CHAR(3)} as the primary key and {@code code_class CHAR(1)}
 *       constrained by a check to the two admitted values {@code 'G'} and {@code 'E'}. Assumptions:
 *       this copybook holds its allow-lists as condition names over a working-storage field rather
 *       than as a record, so there is no layout and no padding field to account for here or in the
 *       two entries that follow. Membership is the whole content: the baseline tests a candidate
 *       value against a list of literals, and the migrated form of that test is whether a row
 *       exists.</dd>
 *
 *   <dt>{@code UsState} maps {@code reference.us_states}</dt>
 *   <dd>From {@code app/cpy/CSLKPCDY.cpy} L1012, {@code US-STATE-CODE-TO-EDIT PIC X(2)}, whose L1013
 *       condition name carries 56 literals. One column, {@code state_cd CHAR(2)}, as the primary
 *       key. Assumptions: {@code CHAR} rather than a varying-width type because {@code bpchar}
 *       ignores trailing blanks on comparison, so a code arriving as a padded two-byte value from a
 *       declared-width source still matches its row. Under {@code VARCHAR(2)} the same probe would
 *       return nothing and would not raise an error either, so the mismatch would surface only as an
 *       address that another service reports as invalid.</dd>
 *
 *   <dt>{@code UsStateZipPrefix} maps {@code reference.us_state_zip_prefixes}</dt>
 *   <dd>From {@code app/cpy/CSLKPCDY.cpy} L1071, whose L1072 subordinate
 *       {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} carries 240 literals at L1073. One column,
 *       {@code state_zip_cd CHAR(4)}, as the primary key, holding the state code and the two leading
 *       postal digits together in that order. Assumptions: one concatenated column rather than two,
 *       because the baseline builds the four bytes and tests the pair as a unit; splitting it would
 *       oblige every reader to reassemble it before comparing. The leading postal digit is
 *       significant, so that half cannot become numeric without losing a code beginning with
 *       zero.</dd>
 * </dl>
 *
 * <h2>Four rulings that bind every type in this package</h2>
 *
 * <p>Each ruling below is recorded once, here, so that the six entities can cite this descriptor
 * instead of restating the argument six times and risking six variants of it.</p>
 *
 * <p>Assumptions: <b>ruling one, a transaction-category code is character data.</b> Both
 * {@code cat_cd} and {@code tran_cat_cd} are {@code CHAR(4)} in the schema and {@code String} in
 * Java, never {@code Long} and never {@code Integer}. This is a deliberate departure from applying
 * the migration's copybook-is-normative rule mechanically, because the copybooks declare the field
 * numeric: {@code app/cpy/CVTRA04Y.cpy} L7 states {@code TRAN-CAT-CD PIC 9(04)} and
 * {@code app/cpy/CVTRA02Y.cpy} L8 states {@code DIS-TRAN-CAT-CD PIC 9(04)}. A numeric picture would
 * otherwise map to an integer column, so a reasonable alternative genuinely exists and leaving the
 * choice undocumented is the omission the project Explainability rule names. Four further sources
 * carry the field as character and all four depend on its leading zeros:
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3 declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}; {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}
 * L42 to L43 generates the host variable as {@code 10 DCL-TRC-TYPE-CATEGORY} with
 * {@code PIC X(4).}; {@code app/data/ASCII/trancatg.txt} stores the codes zero-padded to four
 * digits and keys them by positional concatenation, its first key being literally {@code 010001},
 * which is type {@code 01} followed by category {@code 0001}; and {@code app/jcl/TRANCATG.jcl} L40
 * defines {@code KEYS(6 0)}, a six-byte key that balances only if this field occupies four
 * declared-width bytes beside the two of the type code. An integer column would store {@code 0001}
 * as 1, and a key assembled from that value would not locate its own row.</p>
 *
 * <p>Assumptions: <b>ruling two, the disclosure account-group id is ten characters wide and is
 * never trimmed.</b> {@code acct_group_id} is {@code CHAR(10)}, and neither a stored value nor a
 * probe may be trimmed on either side of the comparison. The space padding is part of the stored
 * key rather than a formatting artefact. {@code app/cbl/CBACT04C.cbl} declares the field as
 * {@code PIC X(10)} at L79 and, when a rate lookup misses, falls back at L437 by moving the
 * seven-character literal {@code 'DEFAULT'} into it before re-reading; a short literal moved into a
 * ten-byte alphanumeric field is left-justified and space-filled, so the key actually searched for
 * is {@code DEFAULT} followed by three spaces. That is precisely how the seed file
 * {@code app/data/ASCII/discgrp.txt} stores those rows. Its 51 records of 50 bytes hold exactly
 * three group values at 17 rows each, and two of the three, the default group and the zero-rate
 * group, are space-padded to exactly ten characters in the first ten bytes.
 * {@code app/jcl/DISCGRP.jcl} L40 confirms the composite key as {@code KEYS(16 0)}, which balances
 * as 10 plus 2 plus 4. Trimming either side would leave the padded stored value and the unpadded
 * probe as distinct values, and the default-rate fallback would then find nothing.</p>
 *
 * <p>Assumptions: <b>ruling three, the interest rate is an exact scaled decimal.</b>
 * {@code interest_rate} is {@code NUMERIC(6,2)} in the schema and {@code java.math.BigDecimal} at
 * scale 2 in Java. No binary floating-point type is admissible anywhere on this path:
 * {@code float}, {@code double}, {@code java.lang.Float}, {@code java.lang.Double} and a bare JSON
 * number are all excluded, and the value travels as a JSON string so that no client parses it into
 * a double at the boundary. The baseline field is {@code DIS-INT-RATE PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy} L9, a zoned decimal carrying its sign as an overpunch in the
 * trailing byte, which is itself an exact base-ten encoding. The three distinct rate values in
 * {@code app/data/ASCII/discgrp.txt} occupy six bytes each: five leading digits, then one overpunch
 * byte carrying the final digit together with the sign. The stored forms are {@code 00150} plus the
 * positive-zero overpunch for 15.00, {@code 00250} plus the same byte for 25.00, and {@code 00000}
 * plus the same byte for 0.00. That trailing byte is identical in all 51 rows, so no seeded rate is
 * negative, and {@code NUMERIC} carries the whole encoding across unchanged.</p>
 *
 * <p>Trade-offs: the reason this ruling is absolute rather than proportionate is that the rate is an
 * operand and not an output. {@code app/cbl/CBACT04C.cbl} computes, at L464 to L465,
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, and the result is written as a generated interest
 * transaction that then posts to a balance. A value such as 15.00 or 2.50 has no exact binary
 * floating-point representation, so the error would enter the multiplication before the division
 * and settle into money that a statement reports and a customer is charged. The compromise accepted
 * is that arithmetic on these values is more verbose than an operator on a primitive, which is a
 * cost paid at the keyboard rather than in the ledger.</p>
 *
 * <p>Assumptions: <b>ruling four, the phone area codes are one table with a classification column,
 * and the two sublists are a partition.</b> {@code us_phone_area_codes} is a single entity carrying
 * a two-value {@code code_class}, not two entities and not a pair of boolean flags. The reason is a
 * property of the copybook that can be computed rather than estimated. In
 * {@code app/cpy/CSLKPCDY.cpy} the broad condition name at L30 carries 490 literals, the
 * general-purpose list at L521 carries 410 and the easily-recognisable list at L931 carries 80.
 * None of the three contains a duplicate; the general and easily-recognisable sets are
 * <b>disjoint</b>, sharing no member at all; and their union is <b>exhaustive</b> over the broad
 * set, equal to it exactly, which 410 plus 80 equalling 490 reflects. Because the partition is
 * total, every code has a class and the column is not nullable with no absent case to represent;
 * because it is disjoint, no code has two classes and one column suffices where a flag pair would
 * admit both-true and both-false rows that the copybook cannot express. Two entities would instead
 * split a domain the copybook keeps on one field and would leave the broad 490-code test, the only
 * one any caller performs, to a union of two queries rather than a primary-key probe. The three
 * lookup entities hold 490 plus 56 plus 240 distinct rows, which is the 786 that, with the 76 rows
 * of the three reference tables, makes up the 862 rows the seed migration loads.</p>
 *
 * <h2>Package-scope decisions</h2>
 *
 * <p>Alternatives Considered: <b>a composite primary key is a nested type, not a separate file.</b>
 * The two entities with multi-column keys declare their identifier as a nested
 * {@code public static class} annotated {@code @Embeddable} and reference it with
 * {@code @EmbeddedId}. The two names are settled here so that the sibling {@code repository},
 * {@code service} and {@code mapper} packages can consume them without guessing:
 * {@code TransactionCategory.TransactionCategoryId}, whose components are {@code typeCd} and
 * {@code catCd}, and {@code DisclosureGroup.DisclosureGroupId}, whose components are
 * {@code acctGroupId}, {@code tranTypeCd} and {@code tranCatCd}. Both implement
 * {@code java.io.Serializable} and override {@code equals} and {@code hashCode}, which an
 * identifier type must do to be usable as a map key and to compare by value. Two alternatives were
 * weighed and rejected. Standalone top-level identifier files were rejected because the contents of
 * this package are a closed set of seven compilation units and two more would make nine, so the
 * one-descriptor-plus-six-entities shape a reader is told to expect would no longer hold.
 * {@code @IdClass} was rejected because it requires the identifier fields to be declared a second
 * time on the entity itself, which is a duplicated declaration that can drift. The remaining four
 * entities have single-column keys and take a plain {@code @Id String}.</p>
 *
 * <p>Assumptions: <b>a version column exists on two of the six entities and on no others.</b>
 * {@code TransactionType} and {@code TransactionCategory} each carry an optimistic-lock counter,
 * mapped {@code @Version} onto the {@code version BIGINT NOT NULL DEFAULT 0} column that
 * {@code V1__reference.sql} declares for both of their tables. {@code DisclosureGroup},
 * {@code UsPhoneAreaCode}, {@code UsState} and {@code UsStateZipPrefix} carry none, because their
 * tables declare none. The asymmetry is deliberate and it follows the maintenance path: the two
 * transaction-reference tables are the only ones this context replaces a row in, through the
 * reference-data screens, and the published contract answers a conflicting replace with HTTP 409
 * carrying the version the row now holds. The other four are seeded lookup data with read-only
 * operations, so a counter there would be written once and never read while suggesting a
 * maintenance path that does not exist. The mechanism is the baseline's own rather than an addition:
 * the extension's update program snapshots the record it read, carries a data-changed flag,
 * compares the snapshot against the stored row before committing, and commits only when the two
 * agree. That is optimistic concurrency across a pseudo-conversational gap, and a counter is the
 * same guarantee expressed natively.</p>
 *
 * <p>Trade-offs: the alternative of having the replace request echo back the description it read,
 * and comparing that instead of a counter, was rejected because it checks only the fields a client
 * chose to echo, so a column added later would fall silently outside the check whereas a counter
 * covers the whole row by construction. The compromise accepted is that two entities in this
 * package behave differently from the other four, which is a genuine inconsistency and is the
 * reason it is recorded here rather than left for a reader to notice. This service runs with
 * Hibernate schema management disabled, so no startup check compares these mappings against the
 * tables; alignment between the two rests on this charter and on review, which is why the column
 * list above is stated per entity rather than summarised.</p>
 *
 * <p>Alternatives Considered: <b>every mapping names its schema explicitly.</b> Each
 * {@code @Table} in this package declares {@code schema = "reference"} rather than omitting the
 * attribute and relying on the JDBC {@code search_path} that the sibling
 * {@code com.carddemo.reference.config} pins. Relying on the path was rejected because it couples
 * entity resolution to connection configuration owned by a different file, and the integration-test
 * profile that starts a throwaway database is the path most likely to lack that pin, so the failure
 * would appear as a missing table in a test rather than as a misconfiguration where it was made.
 * Explicit qualification matches the schema the migration creates these tables in, cannot conflict
 * with a pinned path, and removes a class of resolution failure at the cost of one attribute per
 * entity.</p>
 *
 * <p>Alternatives Considered: <b>no annotation processor generates any member here, and no entity
 * is a record.</b> Lombok is rejected across the project because a generated accessor cannot carry
 * the Javadoc the Explainability rule requires on every member, so the very documentation gate this
 * package is audited by would have nothing to read. A {@code record} is unusable for a JPA entity,
 * which needs a no-argument constructor and mutable non-final state that a record cannot provide.
 * Worth noting for anyone who reaches for one regardless: {@code RECORD_DEF} is among the
 * declaration kinds the Checkstyle type-documentation check inspects, and the companion check sets
 * {@code allowMissingParamTags} to false, so a record would additionally owe a documented parameter
 * at-clause for each of its components. Each entity is therefore an ordinary class with explicitly
 * written accessors, each carrying its own documentation.</p>
 *
 * <p>Assumptions: <b>the exclusion of binary floating point in this package is enforced by the
 * build, not only by review.</b> This is worth stating precisely, because the opposite is easy to
 * assume from the rule's name. The shared architecture test's money rule was originally scoped to
 * the shared money package alone and has since been widened: its subject set is now every
 * production type under {@code com.carddemo}, this package included, and it rejects
 * {@code float}, {@code double}, {@code java.lang.Float} and {@code java.lang.Double} in a field, a
 * parameter or a return type, including as a generic type argument. That test is published as a test
 * artifact by the shared module and re-run inside every module that consumes it, so it is evaluated
 * against this package's own compiled classes rather than only against the shared kernel. A
 * {@code double} introduced on {@code interest_rate} would therefore fail the build here.</p>
 *
 * <p>Trade-offs: what that gate does <b>not</b> establish is worth being equally clear about, so
 * that a green build is not mistaken for proof of correctness. The rule reads declared member types;
 * it does not read arithmetic. It cannot tell whether a scale is 2, whether a rounding mode is
 * half-up, or whether a product was formed before a quotient as the interest calculation requires.
 * Those properties are carried by the shared money type and by the unit tests over it, and they
 * remain a review obligation on every method written here.</p>
 *
 * <h2>Layering, and who may read these types</h2>
 *
 * <p>Assumptions: the shared architecture test's domain-isolation rule takes any {@code domain}
 * package as its subject and forbids a type there from depending on either AWS SDK generation, on
 * Spring Web or on Jakarta Servlet types. It deliberately does <b>not</b> forbid
 * {@code jakarta.persistence} or Spring Data, so the persistence annotations these entities are
 * built from are explicitly permitted rather than merely tolerated; a wider ban would reject the
 * mapping that is the whole content of this package. That rule tolerates an empty subject set only
 * because the shared kernel declares no domain package of its own, so the tolerance is not an
 * invitation: this is among the first domain packages of the migration, and the rule stops being
 * vacuous as soon as these types land. Its list of forbidden roots is not to be broadened from
 * here.</p>
 *
 * <p>Assumptions: the companion rule forbids a class under one bounded-context package root from
 * depending on a {@code domain} class owned by a different root, while permitting any of them to
 * depend on {@code com.carddemo.common}. One consequence is worth recording because the data
 * invites the mistake: the three lookup tables are read during account maintenance by
 * {@code account-service}, which neither owns nor seeds them. That service reaches this data
 * through this service's HTTP contract and its transfer objects, and may never import
 * {@code com.carddemo.reference.domain}. A code missing from the seed therefore does not fail here
 * at all; it surfaces as an address rejected in another service.</p>
 *
 * <p>Assumptions: layering has exactly one owner. The Checkstyle configuration deliberately
 * contains no import-control module, so the architecture test is the single place these boundaries
 * are declared, and a second declaration is not to be added here. Nor does this package declare an
 * entity scan: the application entry point is annotated at {@code com.carddemo.reference}, one level
 * above this package, which already places these entities inside the scanned root.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} compilation unit can carry. This file is therefore load-bearing rather
 * than decorative. Two Checkstyle modules enforce that independently and neither is redundant:
 * {@code JavadocPackage} inspects the file set and requires this file to exist in any directory
 * holding an audited source file, while {@code MissingJavadocPackage} inspects the parsed tree and
 * requires it to carry Javadoc. A descriptor reduced to a bare package statement satisfies the
 * first and fails the second, which is why prose is the deliverable and this file's mere existence
 * is not. Both run at the {@code validate} phase with violations failing the build, and the
 * configuration provides no comment-based or annotation-based suppression, so there is no in-file
 * way to opt out of either and none is attempted.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels, which is the only placement a package makes
 * available. No parameter, return or exception at-clause appears anywhere above, because a package
 * declaration accepts no argument, yields no value and raises nothing; writing one out empty would
 * invent a tag with no body for the at-clause check to reject, so omitting them is the compliant
 * reading of the rule rather than a departure from it. The written convention every block here
 * follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <p>Assumptions: the baseline beneath the {@code app/} tree is the specification for everything
 * above and is reference material throughout, cited by path and line but never modified. Where the
 * migrated behaviour departs from the baseline deliberately, the departure is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is maintained elsewhere and
 * referenced from here rather than reproduced.</p>
 */
package com.carddemo.reference.domain;

