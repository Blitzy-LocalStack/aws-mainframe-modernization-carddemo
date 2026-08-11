package com.carddemo.reference.api;

import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.service.DisclosureGroupService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The disclosure-rate lookup, the one operation the contract publishes over that table.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class binds the three components of a disclosure-group key from the request path, pads the
 * first of them to the width the stored key carries, and hands the three values to
 * {@link DisclosureGroupService}. It holds no rule of its own. The substitution of the default group on
 * a miss, the discrimination between a rate the requested group supplied and one the substitution
 * supplied, and the decision that neither read answered all live in that collaborator, as the charter
 * beside this file requires of every class in this package.</p>
 *
 * <h2>The record this operation reads, checked digit by digit</h2>
 *
 * <p>Assumptions: the key is a composite group and not three loose parameters, and the whole record is
 * accounted for rather than sampled. The layout at {@code app/cpy/CVTRA02Y.cpy} opens the record at its
 * line 4 and opens the key group {@code DIS-GROUP-KEY} at line 5, spanning lines 6 to 8:
 * {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code DIS-TRAN-TYPE-CD PIC X(02)} and
 * {@code DIS-TRAN-CAT-CD PIC 9(04)}, so the key is 10 plus 2 plus 4, which is the first 16 contiguous
 * bytes of the record. Line 9 declares {@code DIS-INT-RATE PIC S9(04)V99}, six bytes, and line 10
 * declares the record's one {@code FILLER} as {@code PIC X(28)}. The widths sum 10 + 2 + 4 + 6 + 28 = 50,
 * which is the {@code RECLN = 50} the layout states in its own line 2 comment, and that arithmetic is
 * the evidence no field of the record was overlooked in deriving this operation. The dataset definition
 * corroborates both figures independently from a second source: {@code app/jcl/DISCGRP.jcl} declares
 * {@code KEYS(16 0)} at its line 40 and {@code RECORDSIZE(50 50)} at line 41.</p>
 *
 * <h2>Why the rate crosses this class untouched</h2>
 *
 * <p>Alternatives Considered: the rate travels as the shared money type from the moment the mapper
 * admits it until Jackson writes it, and nothing in this class re-types, rounds, formats or stringifies
 * it. Two other carriers were evaluated and both were rejected for a specific reason.
 * {@code java.math.BigDecimal} was rejected because the serialiser that produces the string form is
 * registered against the money TYPE -- {@code com.carddemo.common.money.MoneyModule} adds it with
 * {@code addSerializer(Money.class, ...)} at its line 271 and the matching deserialiser at line 272 --
 * so a member declared as the raw decimal would silently miss the registration and be written as a bare
 * JSON number instead. That is the worst available failure mode: it compiles, it runs, the value looks
 * right in a log, and the defect only appears in a client that parsed the number into an IEEE-754 binary
 * approximation and carried the inexactness into everything derived from it. A member pre-formatted into
 * a {@code String} by this class was rejected as well, because it moves a presentation concern into the
 * transport and gives up the arithmetic type safety the consumer of a rate actually needs. The published
 * contract independently fixes the wire form: its {@code InterestRate} schema declares
 * {@code type: string} with the expression {@code ^-?[0-9]{1,4}\.[0-9]{2}$}, never a numeric type, and a
 * member typed as the money type is exactly what produces it.</p>
 *
 * <p>Trade-offs: carrying a scaled decimal rather than a primitive costs an allocation per response and
 * obliges every consumer to parse the string it receives before using it, which is measurably more work
 * than reading a number field directly. That cost is accepted because the stored value is an exact
 * scaled decimal on both sides of the boundary and the string is the only wire form that preserves it: the
 * layout declares {@code DIS-INT-RATE PIC S9(04)V99} at line 9 of {@code app/cpy/CVTRA02Y.cpy}, four
 * integer digits and two decimal places with a sign, which
 * {@code db/migration/V1__reference.sql} carries as {@code NUMERIC(6,2)}. The encoding in the seeded data
 * shows the sign is real and not decorative: the rate field of the row at line 18 of
 * {@code app/data/ASCII/discgrp.txt} reads <code>00150{</code>, whose trailing brace is the
 * zoned-decimal sign overpunch standing for a positive final digit of zero, so the six characters decode
 * to fifteen and no cents. A value that survived that encoding intact would be a poor thing to lose at
 * the last hop for the sake of one allocation.</p>
 *
 * <p>Trade-offs: this operation answers with one {@link DisclosureGroupRateResponse} and never with the
 * shared keyset page envelope, which makes it the odd shape in a package whose other read families all
 * page. A fully specified 16-byte composite key resolves at most one row, so wrapping the answer in a
 * cursor envelope would publish a boundary that could never advance and a next-page flag that would
 * always be false. The compromise accepted is exactly that inconsistency of shape, in exchange for not
 * asking a caller to unwrap a collection of one; the published contract settles it the same way, in that
 * it declares no page-size, no page-number and no positional-skip parameter on this operation, and names
 * a single object schema as the success body. No pagination concern arises in this class at all, and no
 * row is ever addressed by its position in the table rather than by its key.</p>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>Refactoring Rationale: this class resolves a rate and never computes interest with it. The rate is
 * an operand: {@code app/cbl/CBACT04C.cbl} opens {@code 1300-COMPUTE-INTEREST} at its line 462 and, in
 * the single {@code COMPUTE} statement spanning lines 464 and 465, multiplies {@code TRAN-CAT-BAL} by
 * {@code DIS-INT-RATE} and divides that product by twelve hundred into {@code WS-MONTHLY-INT} -- forming
 * the product at full precision BEFORE the division. Re-ordering those two steps to divide
 * first changes the intermediate precision and yields a different final cent on many balances, so the
 * order is part of the behavioural contract rather than an implementation detail. That contract belongs
 * to the interest job in {@code batch-service}, which owns the balance the rate is applied to, and
 * publishing any preview of the computation here would split one arithmetic contract across two
 * deployables where only one of them can be the authority for it. This class therefore performs no
 * arithmetic of any kind on the value it returns.</p>
 *
 * <p>Assumptions: no method here carries an authority annotation, and no error mapping is declared in
 * this module. Authority is decided once for the whole module by HTTP method, and the one controller
 * advice the platform declares -- the single such class in {@code com.carddemo.common.error} -- owns
 * every exception-to-status mapping this operation relies on; both rulings are recorded in the charter
 * beside this file, and a second advice declared here would compete with the shared one for the same
 * exception types, with the observable symptom being a mapped status silently reverting.</p>
 */
@RestController
@RequestMapping(DisclosureGroupController.BASE_PATH)
public class DisclosureGroupController {

    /** The collection path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/disclosure-groups";

    /** The item path, carrying all three key components in the order the contract declares. */
    public static final String ITEM_PATH = "/{acctGroupId}/{tranTypeCd}/{tranCatCd}";

    /** The path variable naming the account group. */
    public static final String PARAM_ACCT_GROUP_ID = "acctGroupId";

    /** The path variable naming the transaction type. */
    public static final String PARAM_TRAN_TYPE_CD = "tranTypeCd";

    /** The path variable naming the transaction category. */
    public static final String PARAM_TRAN_CAT_CD = "tranCatCd";

    /**
     * The declared width the account group is padded to before the key is built.
     *
     * <p>Assumptions: aliased from the entity that owns the column rather than written as a literal, for
     * the reason the four constants below record. This one is the width the padding helper in this class
     * pads TO, so a literal here and a different literal on the entity would produce a padded value the
     * composite key then refuses for being the wrong width -- a disagreement that surfaces as a refusal
     * rather than as a mismatch anyone could see.</p>
     */
    private static final int ACCT_GROUP_ID_WIDTH = DisclosureGroup.ACCT_GROUP_ID_WIDTH;

    /**
     * The shortest account group the contract admits.
     *
     * <p>Refactoring Rationale: the three bounds and three expressions declared here and below are
     * transcribed from the schemas this operation's path parameters reference in
     * {@code openapi/reference-api.yaml} -- {@code AccountGroupId}, {@code TransactionTypeCode} and
     * {@code TransactionCategoryCode}. They are declared because the handler previously bound all three
     * segments as unconstrained text, so a malformed segment reached the identity type in the domain
     * layer, whose refusal is a bare {@code IllegalArgumentException}. The shared advice tests for the
     * caller-refusal subtype and deliberately not for its supertype, so that refusal rendered as a 500 --
     * telling a caller its own malformed path was the service's fault -- while the contract publishes 400
     * for exactly this case.</p>
     */
    private static final int ACCT_GROUP_ID_MIN_WIDTH = 1;

    /**
     * The characters an account group may not contain, as a regular expression.
     *
     * <p>Assumptions: this is the {@code AccountGroupId} schema's own expression, which excludes the C0
     * control range and the delete character and admits everything else up to the declared width. It is
     * transcribed rather than tightened: the seeded groups are alphanumeric, but the contract admits any
     * printable text and narrowing it here would refuse a request the document accepts.</p>
     */
    private static final String ACCT_GROUP_ID_PATTERN = "^[^\\u0000-\\u001F\\u007F]{1,10}$";

    /**
     * The exact width of a transaction type, from the {@code TransactionTypeCode} schema.
     *
     * <p>Refactoring Rationale: the four constants declared here and below are ALIASES for the values the
     * two entities that own those codes publish, rather than copies of the expressions. They were copies
     * first, and the copies were withdrawn once a second and a third boundary needed the same two
     * expressions: three transcriptions of one domain, applied at three different addresses, can drift into
     * disagreeing without anything failing, because each is only ever exercised by requests to its own
     * route. The aliases are kept as named members rather than the references being inlined into the
     * annotations, so that the middle and third components of this key still read as this operation's own
     * contract at the point of use.</p>
     *
     * <p>Assumptions: the two codes in this composite key ARE the transaction type and category codes --
     * {@code app/cpy/CVTRA02Y.cpy} names them {@code DIS-TRAN-TYPE-CD} at line 7 and
     * {@code DIS-TRAN-CAT-CD} at line 8 as the second and third members of the key group opened at line
     * 5, and {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares the same pair at lines 2 and
     * 3 as {@code TRC_TYPE_CODE CHAR(2)} and {@code TRC_TYPE_CATEGORY CHAR(4)}, the two columns its line
     * 5 makes that table's primary key. Referencing their owning entities is therefore a statement of
     * that shared identity and not a convenience.</p>
     */
    private static final int TRAN_TYPE_CD_WIDTH = TransactionType.TYPE_CD_WIDTH;

    /** The closed domain of a transaction type, as the type entity publishes it. */
    private static final String TRAN_TYPE_CD_PATTERN = TransactionType.TYPE_CD_PATTERN;

    /**
     * The exact width of a transaction category, as the category entity publishes it.
     *
     * <p>Assumptions: the category travels as four CHARACTERS and is never converted to a number on the
     * way through this class, even though {@code app/cpy/CVTRA02Y.cpy} declares it at line 8 with the
     * numeric picture {@code DIS-TRAN-CAT-CD PIC 9(04)}. Its leading zeros are part of the key: the
     * seeded categories run {@code 0001} through {@code 0005}, and a numeric reading would address
     * {@code 0001} as {@code 1} and match no row. Two sources unrelated to that copybook agree on the
     * character reading -- {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares
     * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at its line 3, and the seeded row at line 18 of
     * {@code app/data/ASCII/discgrp.txt} carries the literal characters {@code 0001} in those four
     * positions -- and {@code db/migration/V1__reference.sql} settles the stored column as
     * {@code CHAR(4)}. A width hazard sits next to this one and is worth naming so the two are not
     * conflated: line 483 of {@code app/cbl/CBACT04C.cbl} moves a TWO-character literal {@code '05'}
     * into {@code TRAN-CAT-CD} on the transaction record, which is a different field of a different
     * width and is not this key component.</p>
     */
    private static final int TRAN_CAT_CD_WIDTH = TransactionCategory.CAT_CD_WIDTH;

    /** The closed domain of a transaction category, as the category entity publishes it. */
    private static final String TRAN_CAT_CD_PATTERN = TransactionCategory.CAT_CD_PATTERN;

    /**
     * The rules this controller delegates to.
     *
     * <p>Assumptions: this single collaborator is injected through the constructor rather than assigned
     * into the field directly, so the class can be built with a test stub and exercised through the web
     * layer alone, with no data source and no container. That is what lets the constraint cases for this
     * route assert a status and a named field without a database behind them.</p>
     */
    private final DisclosureGroupService service;

    /**
     * Builds the controller over the rate resolver.
     *
     * @param service the rate rules, as a {@link DisclosureGroupService}; must not be {@code null}
     */
    public DisclosureGroupController(DisclosureGroupService service) {
        this.service = service;
    }

    /**
     * Answers the rate that applies to one account group, type and category.
     *
     * <p>Assumptions: each segment is constrained to the schema its published path parameter references,
     * so a malformed segment is refused BEFORE the value reaches the domain identity type. The framework
     * raises its own method-validation failure, which the shared advice renders as the 400 this operation
     * publishes with the offending segment named -- where the same value previously produced a 500 from a
     * bare domain refusal the advice does not classify.</p>
     *
     * <p>Refactoring Rationale: a miss on the account group asked for is an ORDINARY outcome of this
     * operation and answers 200, not 404. The reference paragraph settles that at line 422 of
     * {@code app/cbl/CBACT04C.cbl}, whose test {@code IF DISCGRP-STATUS = '00' OR '23'} sets the same
     * successful result for BOTH statuses, and {@code '23'} is the record-not-found status; line 436 then
     * turns that very status into the substituted read rather than into a failure, and lines 417 to 419
     * announce the intent on the way with {@code 'DISCLOSURE GROUP RECORD MISSING'} followed by
     * {@code 'TRY WITH DEFAULT GROUP CODE'}. Reading a first miss as an error would invert the most
     * invertible fact in this operation, because the failing shape still compiles, still returns a body
     * on an exact hit, and only misreports the case the substitution exists to serve. The response says
     * which of the two reads answered, so a caller never has to infer it.</p>
     *
     * <p>Alternatives Considered: the terminal case -- neither the group asked for nor the substituted
     * group having a row for that type and category -- could have been surfaced as a 500, on the reasoning
     * that the reference program does not survive it: line 455 of {@code app/cbl/CBACT04C.cbl} reports
     * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} and line 458 performs
     * {@code 9999-ABEND-PROGRAM}, because the second paragraph's status test at line 446 accepts only the
     * clean status where the first accepted two. That was rejected: 500 tells a caller the service
     * malfunctioned, whereas the fact being reported is that a key has no row, which 404 states without
     * publishing an internal failure. The migrated form is therefore 404 carrying the shared error body.
     * The baseline behaves as its lines 455 and 458 describe and is not altered; the Java reports the
     * condition instead of terminating; and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Trade-offs: answering 404 accepts that a defect in the seeded reference data reaches a caller
     * looking like an ordinary absent record, which is the one reading of that status this operation
     * cannot narrow. It is acceptable because the substitution rows are seeded and mandatory --
     * {@code db/migration/V2__seed_reference.sql} inserts them, carried across from the seventeen rows of
     * {@code app/data/ASCII/discgrp.txt} whose first ten characters are the default group -- so the
     * condition is reachable only when seeding did not complete, which is a deployment fault rather than
     * a request a caller could correct. The alternative of hiding it entirely would leave a caller with
     * no status at all for a key that genuinely resolves to nothing.</p>
     *
     * @param acctGroupId the account group asked for, as a {@code String} the contract admits from one to
     *     ten characters, padded here to the width the stored key carries
     * @param tranTypeCd the transaction type, as a two-character {@code String} over the closed domain
     *     {@code 01} through {@code 99}, carried through unchanged because the substitution replaces the
     *     account group alone
     * @param tranCatCd the transaction category, as a {@code String} of exactly four digits retaining its
     *     leading zeros, carried through unchanged for the same reason
     * @return a {@link DisclosureGroupRateResponse} carrying the group asked for, the group that
     *     answered, the type, the category, the rate and a statement of whether the substitution supplied
     *     it; never {@code null}
     * @throws DisclosureGroupService.DisclosureGroupNotFoundException when neither the group asked for
     *     nor the substituted group has a row for that type and category, which the shared advice renders
     *     as the 404 this operation publishes because the type derives from
     *     {@code java.util.NoSuchElementException}
     */
    @GetMapping(path = ITEM_PATH)
    public DisclosureGroupRateResponse getDisclosureGroupRate(
            @PathVariable(name = PARAM_ACCT_GROUP_ID)
            @Size(min = ACCT_GROUP_ID_MIN_WIDTH, max = ACCT_GROUP_ID_WIDTH)
            @Pattern(regexp = ACCT_GROUP_ID_PATTERN) String acctGroupId,
            @PathVariable(name = PARAM_TRAN_TYPE_CD)
            @Size(min = TRAN_TYPE_CD_WIDTH, max = TRAN_TYPE_CD_WIDTH)
            @Pattern(regexp = TRAN_TYPE_CD_PATTERN) String tranTypeCd,
            @PathVariable(name = PARAM_TRAN_CAT_CD)
            @Size(min = TRAN_CAT_CD_WIDTH, max = TRAN_CAT_CD_WIDTH)
            @Pattern(regexp = TRAN_CAT_CD_PATTERN) String tranCatCd) {

        // WHY : Assumptions: only the FIRST component is transformed on the way in, and the other two
        //       reach the resolver exactly as the caller sent them. That asymmetry is the substitution's
        //       own shape: line 437 of app/cbl/CBACT04C.cbl overwrites FD-DIS-ACCT-GROUP-ID alone and
        //       line 444 re-reads the SAME file, leaving DIS-TRAN-TYPE-CD and DIS-TRAN-CAT-CD untouched,
        //       so what the substitution resolves is the default group's row FOR THE SAME TYPE AND
        //       CATEGORY and never one global default rate. The seeded data shows why that distinction
        //       has teeth: app/data/ASCII/discgrp.txt carries seventeen default-group rows, and two of
        //       them sharing category 0001 hold different rates -- 00150{ at line 18 for type 01 against
        //       00000{ at line 22 for type 02 -- so collapsing the pair to a single default row would
        //       return one of those two rates for both types. The branch itself stays in the service,
        //       because this package holds no business rule.

        // WHY : Alternatives Considered: the collaborator publishes the same lookup under two names, and
        //       this route binds the resolving one. Its other name carries the transcription of the
        //       reference paragraph and this one delegates to it verbatim, so the two cannot diverge in
        //       behaviour; both declare the read-only transaction, so either would obtain the boundary
        //       when entered from outside the bean. Calling the transcription name directly was rejected
        //       on two counts that have nothing to do with behaviour: the collaborator's own
        //       documentation records this name as the one the route uses, so switching would falsify a
        //       statement in a file this class does not own, and the constraint cases for this route
        //       stub this name, so a switch would leave them exercising a stub that answers nothing
        //       while still reporting a bound and refused segment correctly.
        return this.service.resolveRate(padToStoredWidth(acctGroupId), tranTypeCd, tranCatCd);
    }

    /**
     * Right-pads an account group with blanks to the width the stored key carries.
     *
     * <p>Assumptions: padding happens HERE, at the edge, and not in the service or the identity type. The
     * contract admits a group from one to ten characters because a caller naturally writes the literal
     * without its padding, while the stored key is exactly ten characters wide because the baseline moves
     * a short literal into a ten-byte alphanumeric field and the platform space-fills it. Padding at the
     * edge is what lets both facts stand: a caller sends what it reads, and the key built is the key the
     * seed contains.</p>
     *
     * <p>Assumptions: the substituted group is ten characters wide with three trailing spaces, and this
     * helper is what makes a caller's unpadded spelling of it address the same rows. Line 437 of
     * {@code app/cbl/CBACT04C.cbl} moves the SEVEN-character literal {@code 'DEFAULT'} into the field
     * declared {@code PIC X(10)} at line 6 of {@code app/cpy/CVTRA02Y.cpy}, and an alphanumeric move into
     * a wider alphanumeric field left-justifies and space-fills, so the key actually read is
     * {@code 'DEFAULT'} followed by three spaces. The seeded data confirms it byte for byte at line 18 of
     * {@code app/data/ASCII/discgrp.txt}, whose first ten characters are exactly that, and
     * {@link DisclosureGroupService#DEFAULT_ACCT_GROUP_ID} carries the padding as part of its value for
     * the same reason. Nothing in this class trims that padding away.</p>
     *
     * <p>Assumptions: this pads on the RIGHT only and never trims. Trimming would search for a key no row
     * carries, and because a miss falls back to the substituted group rather than failing, the symptom
     * would be silent accrual at another group's rate instead of a refusal.</p>
     *
     * @param acctGroupId the account group as the caller sent it, as a {@code String}, which may be
     *     shorter than the stored width or already at it
     * @return the group as a {@code String} padded on the right to its stored width, or the value
     *     unchanged when it is already at or over that width or is {@code null}
     */
    private static String padToStoredWidth(String acctGroupId) {
        if (acctGroupId == null || acctGroupId.length() >= ACCT_GROUP_ID_WIDTH) {
            return acctGroupId;
        }
        return acctGroupId + " ".repeat(ACCT_GROUP_ID_WIDTH - acctGroupId.length());
    }
}
