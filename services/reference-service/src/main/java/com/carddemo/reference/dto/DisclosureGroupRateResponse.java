package com.carddemo.reference.dto;

import com.carddemo.common.money.Money;

/**
 * The interest rate that applies to one combination of account group, transaction type and
 * transaction category, satisfying the contract schema {@code DisclosureGroupRate}.
 *
 * <p>Purpose: this is the outbound wire shape of the disclosure-group rate lookup, the read that
 * interest accrual performs once for every transaction-category balance it processes. It carries the
 * rate together with a statement of which account group actually supplied it, and it is the migrated
 * form of the fifty-byte record {@code app/cpy/CVTRA02Y.cpy} declares. It holds no logic, validates
 * nothing, decodes nothing and reaches no store: {@code com.carddemo.reference.mapper} builds it from
 * the stored row, {@code com.carddemo.reference.service} decides which row answered, and
 * {@code com.carddemo.reference.api} serialises it. A persistence annotation, a lookup, a fallback
 * decision or a rounding step declared on this type would be a layering fault rather than a
 * convenience, and the layering test published by {@code common-lib} is what keeps that statement
 * enforceable rather than aspirational.</p>
 *
 * <h2>The rate member carries a money type, and that is the load-bearing decision of this file</h2>
 *
 * <p>Alternatives Considered: declaring {@code interestRate} as a {@code java.math.BigDecimal} was
 * evaluated and rejected, and it is worth being blunt about why, because the wrong choice here fails
 * silently rather than loudly. {@code com.carddemo.common.money.MoneyModule} registers its serialiser
 * against the {@code Money} <b>type</b> -- its line 271 binds the handler to {@code Money.class}, and
 * its line 324 emits the value through the generator's string method. A member declared as the general
 * decimal type matches no registered handler, falls through to the default treatment and is written as
 * a bare JSON number. Nothing anywhere reports that: the module still compiles, the service still
 * starts, the mapper still populates the member exactly, and a test that reads the member back through
 * this type still passes, because the value is exact on both sides of the boundary and only its
 * rendering has changed. The declared type is therefore the whole of the enforcement, which is why the
 * reasoning is stated here rather than left to be inferred from a module registration two packages
 * away.</p>
 *
 * <p>Assumptions: a JSON number is unacceptable for a concrete reason rather than as a matter of house
 * preference. Most clients parse a JSON number into an IEEE-754 binary floating point value, which
 * destroys exactness at the boundary the user actually sees, and a browser is one of those clients.
 * The rate is not a value that is merely displayed and then discarded; it is an <b>operand</b>. Lines
 * 464 and 465 of {@code app/cbl/CBACT04C.cbl} state
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, so the rate is multiplied by
 * a balance and the quotient is written as a generated interest transaction that then posts to that
 * same balance. A representation error introduced when the rate crossed the wire would enter the
 * multiplication before the division and settle into money a statement reports and a customer is
 * charged, and it would stay plausible while being wrong.</p>
 *
 * <p>Migration rule T3 requires exact fixed point end to end and pins one representation per layer
 * for the whole system, admitting no exception: {@code NUMERIC(6,2)} in the database, which is what
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} declares for
 * {@code interest_rate}; a decimal carried at a scale of exactly two and reduced {@code HALF_UP}
 * inside Java, which is what {@code Money} guarantees of every value it admits; and a JSON
 * <b>string</b> on the wire, which is what the contract declares, its {@code InterestRate} schema
 * being typed string with a pattern requiring exactly two fractional digits and {@code '15.00'} among
 * its examples. The prohibition on IEEE-754 binary floating point in the money path is not carried by
 * review either:
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * asserts it, and that class is packaged and re-run against this module's own compiled classes, so a
 * breach fails a build rather than a review.</p>
 *
 * <h2>The key members, their widths and the padding contract</h2>
 *
 * <p>Assumptions: an account group identifier occupies the full ten characters
 * {@code DIS-ACCT-GROUP-ID PIC X(10)} declares at line 6 of {@code app/cpy/CVTRA02Y.cpy}, its
 * trailing spaces belong to the stored key rather than to formatting, and nothing in this type trims
 * them or supplies them. The seed extract {@code app/data/ASCII/discgrp.txt} settles the point by
 * literal: the account group values it holds are {@code A000000000}, {@code 'DEFAULT   '} and
 * {@code 'ZEROAPR   '}, each occupying all ten positions, so the fallback key is the seven characters
 * of {@code DEFAULT} followed by three spaces, and every record in that extract is exactly fifty bytes
 * wide. The interest program produces that same padded key without being asked to: line 437 of
 * {@code app/cbl/CBACT04C.cbl} moves the seven-character literal {@code 'DEFAULT'} into the ten-byte
 * alphanumeric field its line 79 declares, and a shorter literal moved into a longer alphanumeric
 * field is left-justified and space-filled. {@code V1__reference.sql} carries that padding across as
 * {@code acct_group_id CHAR(10)} and
 * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql} seeds the
 * fallback rows under the literal {@code 'DEFAULT   '} for exactly that reason. A member of this type
 * that had silently trimmed the value would publish a key that no longer addresses the row it was read
 * from.</p>
 *
 * <p>Assumptions: the padding governs what is published here and not what a caller has to send, and
 * the two are worth separating so a reader does not conclude that the request path is fussy. Because
 * the column is {@code CHAR(10)}, whose comparison disregards trailing blanks, an unpadded
 * {@code DEFAULT} and its blank-filled form address the same rows, which is what lets the contract
 * admit a group identifier of anything up to ten characters on the way in. What arrives back out of a
 * {@code CHAR(10)} column is the padded form, and this type publishes it unaltered.</p>
 *
 * <p>Assumptions: the transaction category code is character data whose content happens to be digits,
 * so it is declared as a string and its leading zeros survive, a row stored as {@code 0001} being
 * published as {@code 0001} and never as {@code 1}. The baseline states that character reading rather
 * than the target choosing it: {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at its line 3, and the DCLGEN host structure beside it,
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}, generates the same column across its lines 42
 * and 43 as {@code DCL-TRC-TYPE-CATEGORY PIC X(4)}, a character host variable and not a numeric one.
 * The fuller argument, including why an integer member would cost a caller the ability to echo a key
 * back, is set out once on {@code TransactionCategoryResponse} and is not repeated at length here. The
 * same reading holds for the two-character transaction type code beside it.</p>
 *
 * <p>Assumptions: the rate this type carries has already been decoded, and no encoding concern belongs
 * at this layer. The rates in {@code app/data/ASCII/discgrp.txt} are held as zoned decimal with a sign
 * overpunch in the trailing byte, and the distinct rate fields it holds are the six-byte literals
 * <code>00000&#123;</code>, <code>00150&#123;</code> and <code>00250&#123;</code>, in which the
 * trailing brace is the positive-zero overpunch carrying the final digit together with the sign, so
 * they denote {@code 0.00}, {@code 15.00} and {@code 25.00} respectively. Turning those bytes into a
 * decimal is the work of {@code com.carddemo.common.codec.ZonedDecimalCodec} and of the
 * extract-transform-load package that loads the extract, and it happens once, at the edge. This type
 * transports the decoded value and nothing else, so no decoding step, sign-stripping step or scaling
 * step may be added to it: a second decoder for one encoding is how two callers come to disagree about
 * a rate.</p>
 *
 * <p>Trade-offs: the three key members are declared as siblings rather than nested inside a key
 * sub-record, even though {@code app/cpy/CVTRA02Y.cpy} groups them together under
 * {@code DIS-GROUP-KEY} at its line 5. A nested member would serialise as a nested JSON object, which
 * is not the shape the contract declares: its {@code DisclosureGroupRate} schema lists all six members
 * flat and sets {@code additionalProperties} false, so a nested key would satisfy neither the member
 * list nor the refusal of members the schema does not name. Flattening also matches the way a rate is
 * addressed, through three path segments rather than through one sixteen-character token. The
 * compromise accepted is that the grouping the copybook expresses is no longer visible in the
 * declaration, so the fact that three of these members form ONE primary key has to be carried in prose
 * -- and it is carried here, because a reader who took the category code for a globally unique
 * identifier would look up the wrong row. {@code V1__reference.sql} states the same grouping as
 * {@code PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)}, in that order.</p>
 *
 * <p>Refactoring Rationale: the {@code FILLER PIC X(28)} at line 10 of
 * {@code app/cpy/CVTRA02Y.cpy} is not carried across, and it is the one filler of that record --
 * ten plus two plus four plus six plus twenty-eight accounts for every one of the fifty bytes the
 * record's own header states at its line 2. Those bytes pad the record out to a positional length,
 * which a JSON object has no use for: a reader walking a flat fifty-byte record has to know where the
 * next record begins, whereas a member of an object is delimited by the encoding itself. Migration
 * rule T1 makes the drop the rule for every record rather than a choice taken at this one, and it also
 * requires that the drop be recorded, which is what this paragraph is for. The seed rows carry zeros
 * in those bytes, so the omission discards no value a caller could have read.</p>
 *
 * <h2>The fallback is reported rather than inferred</h2>
 *
 * <p>Alternatives Considered: returning the rate alone and leaving the caller to compare the echoed
 * group identifier against the one it asked for was evaluated and rejected. That is inference rather
 * than contract: it obliges every caller to reimplement the comparison, it obliges each of them to
 * decide independently whether a trailing-blank difference counts, and it gives none of them a way to
 * tell a caller that asked about the default group directly, which is a direct hit, from one whose own
 * group had no row at all. {@code defaultGroupApplied} states the outcome once, at the source that
 * knows it. The two outcomes mean different things operationally: a rate that arrived by fallback is a
 * signal that an account group is absent from the seeded reference data, which is a data defect worth
 * surfacing, whereas a direct hit is not.</p>
 *
 * <p>Assumptions: the fallback exists because the baseline substitutes rather than fails, and this
 * type reports that substitution without performing it. Lines 436 to 439 of
 * {@code app/cbl/CBACT04C.cbl} test the lookup status for the miss value {@code '23'}, move the
 * literal {@code 'DEFAULT'} into the group field and re-read through the paragraph at its line 443, so
 * the rate returned may belong to a group the caller never named. {@code V2__seed_reference.sql} seeds
 * the {@code 'DEFAULT   '} rows precisely so that path resolves, and those rows are not optional:
 * where the fallback itself finds nothing the baseline does not continue but reports the condition at
 * its line 455 and drives to the abend paragraph invoked at its line 458, an outcome the contract
 * renders as 404 rather than as a rate. Choosing which of the two lookups answered belongs to
 * {@code com.carddemo.reference.service}; this type only records the answer, and a member here that
 * computed it would put one decision in two places.</p>
 *
 * <p>Assumptions: reporting the group that answered is a documented departure from the baseline rather
 * than a convenience, and the baseline has no room for it because it overwrites its own key. Having
 * moved {@code 'DEFAULT'} into the group field at line 437, the program's field no longer records
 * which group was asked about, so after the second read nothing downstream can tell a fallback rate
 * from a specific one. Carrying both values keeps the same rate selection while making the
 * substitution visible, so a caller reconciling a charge against a rate table is not left inferring
 * it. The departure is registered as {@code D-APPLIED-GROUP-VISIBLE} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Parameters, return values and exceptions at the type level: the six record components are this
 * type's parameters and each carries its own at-clause below. A type declaration returns nothing and
 * raises nothing, so no return or exception at-clause belongs here, and the canonical accessors the
 * record form supplies return their component unchanged -- not one of them rescales, rounds,
 * reformats, pads or trims a value, least of all the rate -- which is the trivial case the
 * Explainability rule admits a single line for and which, being compiler-supplied, needs none. That
 * inapplicability is stated rather than passed over in silence, because the same rule counts a
 * docstring omitting parameters or return values among its forbidden patterns, and a reader has to be
 * able to tell a declared inapplicability from an oversight.</p>
 *
 * <p>Every baseline artifact cited above is read as the specification for this migration and is never
 * modified. Where this context's behaviour departs from the baseline's, the departure is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere and is
 * referenced from here rather than authored here.</p>
 *
 * @param requestedAcctGroupId the account group the caller asked about, echoed back exactly as
 *     received and neither trimmed nor padded by this type, as a {@code String} of up to the ten
 *     characters {@code DIS-ACCT-GROUP-ID PIC X(10)} declares at line 6 of
 *     {@code app/cpy/CVTRA02Y.cpy} and stored as {@code acct_group_id CHAR(10)}
 * @param appliedAcctGroupId the account group whose row actually supplied the rate, as a
 *     {@code String} of that same ten-character width under that same no-trim contract; equal to
 *     {@code requestedAcctGroupId} when the requested group had a row of its own, and the
 *     space-padded default group, the seven characters of {@code DEFAULT} followed by three spaces,
 *     when the fallback answered
 * @param tranTypeCd the transaction type of the rate returned, echoed back unchanged because the
 *     fallback substitutes the account group alone, as a {@code String} of exactly the two characters
 *     {@code DIS-TRAN-TYPE-CD PIC X(02)} declares at line 7 of that copybook and
 *     {@code TRC_TYPE_CODE CHAR(2)} declares at line 2 of {@code TRNTYCAT.ddl}, stored as
 *     {@code tran_type_cd CHAR(2)}, carried as characters so that the leading zero of a value such as
 *     {@code '01'} survives the round trip, and never {@code '00'}
 * @param tranCatCd the transaction category of the rate returned, likewise echoed back unchanged, as
 *     a {@code String} of exactly four digits whose leading zeros are part of the value, so that a row
 *     stored as {@code 0001} is published as {@code 0001} and never as {@code 1}; four characters wide
 *     per {@code DIS-TRAN-CAT-CD PIC 9(04)} at line 8 of that copybook, per
 *     {@code TRC_TYPE_CATEGORY CHAR(4)} at line 3 of {@code TRNTYCAT.ddl} and per
 *     {@code DCL-TRC-TYPE-CATEGORY PIC X(4)} at lines 42 and 43 of {@code DCLTRCAT.dcl}, stored as
 *     {@code tran_cat_cd CHAR(4)}, unique only within its parent transaction type rather than
 *     globally, and never an integer or any other numeric type
 * @param interestRate the annual percentage rate that applies, as a
 *     {@code com.carddemo.common.money.Money} carrying an exact fixed-point decimal held at a scale of
 *     exactly two and reduced {@code HALF_UP}, four integer digits and two fractional per
 *     {@code DIS-INT-RATE PIC S9(04)V99} at line 9 of that copybook and per
 *     {@code interest_rate NUMERIC(6,2)}; it travels the wire as a quoted JSON string, rendered by
 *     {@code com.carddemo.common.money.MoneyModule} so that its exactness survives the boundary, it is
 *     signed because the stored column is signed, and it is an operand of the accrual computation at
 *     lines 464 and 465 of {@code app/cbl/CBACT04C.cbl} rather than a value merely displayed
 * @param defaultGroupApplied whether the fallback answered, as a {@code boolean} that is {@code true}
 *     exactly when {@code appliedAcctGroupId} is the default group and the caller asked about a
 *     different one, and {@code false} when the requested group had its own row, the case of a caller
 *     asking about the default group directly included
 */
public record DisclosureGroupRateResponse(
        String requestedAcctGroupId,
        String appliedAcctGroupId,
        String tranTypeCd,
        String tranCatCd,
        Money interestRate,
        boolean defaultGroupApplied) {
}
