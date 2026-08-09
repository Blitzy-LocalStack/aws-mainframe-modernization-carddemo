//=============================================================================
// WHY : Assumptions: the charter beside this file, package-info.java, records the
//       rulings every class in this package obeys -- how a line citation
//       resolves, which type a category code takes, how wide an account group
//       is, where the one money conversion lives and what the disclosure
//       fallback may assume. Its Contract 2 governs this file directly. The
//       rulings are CITED below and deliberately not restated, because a
//       restated ruling can drift while still reading as agreement.
// WHY : Assumptions: every line number cited here is a PHYSICAL line number,
//       read at that address rather than found by searching for a printed
//       sequence value. The one program transcribed here, app/cbl/CBACT04C.cbl,
//       is 652 physical lines and carries no printed sequence field, so a
//       physical address is the only address it has.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       modified. Where the migrated behaviour departs from it deliberately the
//       departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is maintained
//       elsewhere and referenced from here rather than authored here.
//=============================================================================
package com.carddemo.reference.service;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.mapper.DisclosureGroupMapper;
import com.carddemo.reference.repository.DisclosureGroupRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the interest rate that applies to one account group, transaction type and category.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the migrated form of two paragraphs of the reference interest program:
 * {@code 1200-GET-INTEREST-RATE}, which opens at line 415 of {@code app/cbl/CBACT04C.cbl}, and the
 * separate paragraph it delegates to on a miss, {@code 1200-A-GET-DEFAULT-INT-RATE}, which opens at
 * line 443. It performs the two keyed reads those paragraphs perform, reports which of the two
 * answered, and raises a diagnosable condition when neither does. Its consumers are
 * {@code com.carddemo.reference.api} over HTTP and, across the service boundary, the interest
 * accrual that owns the computation.</p>
 *
 * <p>Assumptions: this class computes NOTHING. It reads a rate and reports where the rate came from.
 * The accrual that multiplies the rate by a balance is a batch concern implemented there, for the
 * reason set out under the exact-arithmetic heading below.</p>
 *
 * <p>Assumptions: AAP Rule T9 (structure changes, behaviour does not) governs everything below. Two
 * keyed reads become two repository calls, a paragraph becomes a method and a status test becomes an
 * empty result, but which rate answers for which key is unchanged. Exactly ONE behavioural difference
 * is intended, and it is the terminal path described further down: where the reference program ends
 * the process, this class raises a condition a caller can act on. That difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} and is not restated as a general
 * licence -- no other divergence is introduced here.</p>
 *
 * <p>Trade-offs: parity for this class rests on transcribed logic checked against the record layout in
 * {@code app/cpy/CVTRA02Y.cpy} and the column contract of the owning schema, NOT on a byte comparison
 * of its own output. The repository's COBOL oracle under {@code tests/} covers batch flows, so the
 * business rules of the program transcribed here -- the substitution among them -- are asserted there
 * against the reference program itself; but no golden master compares this class's reply to anything,
 * because the reply is a shape the reference program never produced. That is stated rather than
 * glossed so nobody reads a green suite as byte-level proof of this file.</p>
 *
 * <h2>A rate of zero is a value; an absent row is not</h2>
 *
 * <p>Assumptions: a rate of zero and an absent row are semantically different outcomes and are never
 * allowed to collapse into one another. This is the load-bearing assumption of the whole class, and it
 * is not obvious from the reading paragraphs themselves -- it comes from the caller. Line 213 of
 * {@code app/cbl/CBACT04C.cbl} performs the rate lookup, and line 214 then guards what follows with
 * {@code IF DIS-INT-RATE NOT = 0}. That guard spans BOTH of the next two statements: line 215
 * performs {@code 1300-COMPUTE-INTEREST} and line 216 performs {@code 1400-COMPUTE-FEES}, and line
 * 217 closes the guard. The generated interest transaction is not written by the caller either: line
 * 468, INSIDE {@code 1300-COMPUTE-INTEREST}, performs {@code 1300-B-WRITE-TX}. So a rate of zero
 * suppresses the computation, the fee step and the transaction write together.</p>
 *
 * <p>Assumptions: the seed proves the case is ordinary rather than theoretical.
 * {@code app/data/ASCII/discgrp.txt} holds 51 rows of 50 bytes, and one of its three account groups
 * carries a zero rate on all 17 of its rows while the other two carry a zero rate on 6 and 7 rows
 * respectively. Those rows are present, readable and correct; they simply accrue nothing.</p>
 *
 * <p>Trade-offs: the consequence accepted here is that this class cannot use an empty result to mean
 * two things, so it needs a distinct terminal condition and cannot lean on a zero return value as a
 * shorthand. The compromise is worth it because both confusions are silent in opposite directions:
 * reporting a zero rate as absent would send the caller down the substitution path and accrue at
 * another group's rate where the reference program accrues nothing, and reporting an absent row as a
 * zero rate would accrue nothing while reporting success, so no operator would ever learn that a
 * group is missing from the seeded data.</p>
 *
 * <h2>The account group is ten characters wide and is never trimmed</h2>
 *
 * <p>Assumptions: the trailing spaces of an account group value are part of the stored key rather than
 * formatting, and no component of the key is trimmed here on either side of a comparison. Line 437 of
 * {@code app/cbl/CBACT04C.cbl} performs the substitution with
 * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}. The literal is SEVEN characters; the receiving field
 * is ten, declared {@code PIC X(10)} at line 79 of the same program and at line 6 of
 * {@code app/cpy/CVTRA02Y.cpy}. An alphanumeric move into a wider alphanumeric field is
 * left-justified and space-filled, so the key the reference program actually searches with is the
 * seven-character literal followed by three spaces. That is why the target column is declared
 * {@code CHAR(10)} and why {@link #DEFAULT_ACCT_GROUP_ID} carries its padding as part of its value.</p>
 *
 * <p>Assumptions: the seed agrees with the reading. Two of the three distinct account groups in the
 * first ten bytes of {@code app/data/ASCII/discgrp.txt} are space-padded from seven characters to
 * ten, the substituted group among them.</p>
 *
 * <p>Assumptions: ownership of the width is split deliberately and this class holds neither half.
 * Widening a short value to ten characters happens at the edge, in
 * {@code com.carddemo.reference.api}, because a caller naturally writes a group without its padding;
 * refusing a value that is not exactly ten characters happens in
 * {@code DisclosureGroup.DisclosureGroupId}, whose constructor checks each component. This class
 * passes what it received into that constructor unaltered, so a wrong width is refused there rather
 * than silently reshaped here into a key that addresses no row.</p>
 *
 * <h2>The substitution replaces the group component alone</h2>
 *
 * <p>Assumptions: only the account group is substituted, and the transaction type and category are
 * carried through unchanged. All three key components are assembled by the caller -- line 210 moves
 * the account group, line 211 the category and line 212 the type -- and line 437 rewrites exactly one
 * of the three. The other two are still holding the values lines 211 and 212 put there when the
 * second read is performed at line 444.</p>
 *
 * <p>Trade-offs: a substitution that also generalised the type or the category would be simpler to
 * express as one query and would always find a row, which is precisely why it is not done. The rate
 * it returned would belong to a different product, and because the lookup succeeds there would be no
 * error to notice -- only wrong money.</p>
 *
 * <h2>Why the two reads stay two reads</h2>
 *
 * <p>Alternatives Considered: folding both lookups into a single statement -- an alternation over the
 * requested and the substituted group resolved by a null-coalescing expression, or a union ordered so
 * that the requested group sorts first. Rejected on two independent grounds.</p>
 *
 * <p>Alternatives Considered: the first ground is that the two reads do not share failure semantics,
 * so collapsing them would erase a distinction the reference program draws. The first read tolerates
 * the not-found status: line 422 accepts {@code '00'} OR {@code '23'}, and a status of {@code '23'}
 * is what line 436 tests to decide whether to substitute at all. The second read does not tolerate
 * it: line 446 accepts {@code '00'} alone. A first miss is therefore recoverable by construction and
 * a second miss is terminal by construction, and one statement returning one row cannot express
 * two different meanings for an empty result.</p>
 *
 * <p>Alternatives Considered: the second ground is that the reply has to say which group answered.
 * {@code DisclosureGroupRateResponse} publishes the requested group, the applied group and a boolean
 * indicator, and no property of a disclosure row records how it was reached, so the layer that
 * performed the reads is the only layer that knows. A single collapsed query would return a row that
 * looked identical either way, and an account group missing from the seeded data would then have no
 * symptom at all.</p>
 *
 * <p>Assumptions: what makes the substitution safe is a structural property of the seeded data, not
 * an accident. {@code app/data/ASCII/discgrp.txt} is 2601 bytes holding 51 records of 50 bytes, split
 * 17 rows to each of its three account groups, and all three groups carry the SAME 17
 * type-and-category pairs. Seventeen is the uniform per-group cardinality rather than anything
 * special to the substituted group, and that uniformity is the invariant: any pair that exists under
 * some group is guaranteed to exist under the substituted group too. Were the seed loaded with a
 * group covering a pair the substituted group does not, a first miss on that pair would become a
 * terminal miss rather than a substitution.</p>
 *
 * <h2>The substitution changes the answer, so it cannot be optimised away</h2>
 *
 * <p>Trade-offs: two reads cost more than one, and the reason that cost is accepted is that the
 * second read genuinely returns different money. The rates in the seed are distributed differently
 * per group: one group carries zero on 6 rows, 15.00 on 8 and 25.00 on 3; the substituted group
 * carries zero on 7 rows, 15.00 on 7 and 25.00 on 3; the third carries zero on all 17. The
 * difference is not statistical -- the type-and-category pair 07 and 0001 is rated 15.00 under the
 * first group and 0.00 under the substituted group, so for that pair a substitution turns an accrual
 * into no accrual at all. A reader who assumes both reads yield the same rate and removes one would
 * change the money for that pair without changing any test that only exercises the others.</p>
 *
 * <h2>The substitution is not total, so a terminal miss is real</h2>
 *
 * <p>Assumptions: the substitution resolves most misses and not all of them, so the terminal path is
 * reachable and is handled rather than assumed away. The reference data carries 18 distinct
 * type-and-category pairs in {@code app/data/ASCII/trancatg.txt} while each account group in
 * {@code app/data/ASCII/discgrp.txt} carries 17, and the difference is exactly one pair: type 01 with
 * category 0005, whose description in the category extract is {@code Interest Amount}. That pair has
 * no disclosure row under ANY group, the substituted group included, so the hole is disclosure-side
 * only and is not a gap in the category data.</p>
 *
 * <p>Assumptions: the absence is coherent rather than an omission to be papered over -- no interest
 * accrues on the posting of interest itself. It is therefore never answered with a synthesised zero
 * rate. A synthesised zero would be indistinguishable from the genuine zero rows described above,
 * which is the exact confusion the first heading of this document forbids.</p>
 *
 * <p>Assumptions: the reference program treats a second miss as unsurvivable, and the migrated form
 * reports it instead of terminating the process. On anything other than {@code '00'} the second
 * paragraph displays its read-failure message at line 455 and performs {@code 9999-ABEND-PROGRAM} at
 * line 458; that paragraph opens at line 628, displays its own message at line 629, moves 0 to the
 * timing operand at line 630 and 999 to the abend code at line 631, and calls the environment abend
 * service at line 632 -- with a timing operand of 0 meaning immediate termination without normal
 * termination processing. The baseline abends there; the Java raises
 * {@link DisclosureGroupNotFoundException} carrying the same operator-facing values as an
 * {@link AbendDetail}, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>The rate is exact base ten, and nothing here computes with it</h2>
 *
 * <p>Assumptions: the rate is an exact scaled decimal at every hop -- {@code PIC S9(04)V99} at line 9
 * of {@code app/cpy/CVTRA02Y.cpy}, {@code NUMERIC(6,2)} in the owning schema, and
 * {@code java.math.BigDecimal} on the entity. It is published as
 * {@code com.carddemo.common.money.Money}, which serialises as a JSON string. No binary
 * floating-point type appears anywhere in this file, in any member it declares, or in anything it
 * returns.</p>
 *
 * <p>Assumptions: this matters because the rate is an operand and not a display value, and the
 * consumer that multiplies it has an order of operations to preserve. AAP Rule T4 (arithmetic order
 * is preserved) requires the accrual to form the product at full precision FIRST and only then
 * divide with an explicit scale and rounding mode, because lines 464 and 465 of
 * {@code app/cbl/CBACT04C.cbl} compute
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with the multiplication
 * parenthesised in the reference source itself, the divisor 1200 being twelve months times one
 * hundred percent. Dividing first is not a rearrangement of equals: a rate of 15.00 divided by 1200
 * is 0.0125, which at scale 2 becomes 0.01, understating the intended 0.0125 factor by 20 percent
 * before it ever meets a balance. The note is recorded here, on the class that owns the rate
 * contract, for the benefit of every consumer of it; this class performs no arithmetic of its own.</p>
 *
 * <p>Trade-offs: it is worth being precise about which half of that is mechanically enforced, so that
 * a green build is not mistaken for proof of correctness. Rule A3 of the shared layering test rejects
 * a binary floating-point field, parameter or return type on any production class beneath the
 * analysed root {@code com.carddemo}, and the test runs inside this module against this module's own
 * compiled classes, so a binary floating-point member declared in this file fails the build rather
 * than waiting for a reviewer. An earlier descriptor scoped that rule to the shared money package
 * alone and this file deliberately does not repeat that narrower claim, because understating a gate
 * invites a reader to add a redundant second one. What the rule cannot do is read arithmetic: it
 * cannot tell whether a scale is 2, whether a rounding mode is half-up, or whether a product was
 * formed before a quotient. Those three remain a review obligation, and keeping every computation out
 * of this class is part of how that obligation is kept small.</p>
 *
 * <h2>The reply is assembled by the mapper and never here</h2>
 *
 * <p>Assumptions: {@code com.carddemo.reference.mapper.DisclosureGroupMapper} is the ONLY place in
 * this service where a stored decimal is admitted to the money type, and this class obtains its reply
 * from that mapper rather than constructing one. The reason is a serialisation contract rather than
 * tidiness: {@code com.carddemo.common.money.MoneyModule} binds its serialiser to the money TYPE, so
 * a reply field left as a plain decimal would silently emit a JSON number where the published
 * contract specifies a string, and a client parsing that number through a binary floating-point
 * representation would lose exactness at the boundary a customer actually reads.</p>
 *
 * <p>Assumptions: the division of labour follows from that. The mapper takes the fallback indicator as
 * an explicit argument instead of inferring it, because inference is impossible on its side -- the
 * row it converts carries no trace of how it was found -- and this class is the layer that performed
 * the reads and therefore the only one that knows.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <ul>
 *   <li><b>No paging of any kind.</b> Every method here answers with one reply, and the reference
 *       program resolves one rate per balance row and never enumerates rates, so there is no walk to
 *       page. No window positioned by ordinal is available anywhere in this package, because such a
 *       window skips and repeats rows when inserts land concurrently.</li>
 *   <li><b>No second exception advice.</b> The 404 and the leak-free rendering of it come from
 *       {@code com.carddemo.common.error.GlobalExceptionHandler}, which this module inherits from the
 *       shared kernel. The framework selects one handler per exception type, so a second advice
 *       declared in this module could take precedence unpredictably.</li>
 *   <li><b>No status code, no response entity and no HTTP vocabulary.</b> This class raises a
 *       condition; the advice above decides the status and composes the
 *       {@code com.carddemo.common.error.ApiError} body.</li>
 *   <li><b>No write of any kind.</b> This table is loaded by the seed migration that ships with the
 *       module and is never modified by the running system, which is also why its entity carries no
 *       concurrency counter for this path to read.</li>
 *   <li><b>No caching and no read replica.</b> Both reads are primary-key probes, and a second copy
 *       of the data would add a staleness window the reference program cannot exhibit.</li>
 *   <li><b>No code generation.</b> No annotation processor and no mapping generator supplies any
 *       member here, because a generated member cannot carry the documentation that user-specified
 *       Rule 1 (Explainability) requires of it, and the Javadoc audit grants no annotation an
 *       exemption from needing one.</li>
 * </ul>
 */
@Service
public class DisclosureGroupService {

    /**
     * The account group substituted when the group asked for has no row, space-padded to ten
     * characters.
     *
     * <p>Assumptions: the padding is part of the value and is applied HERE rather than left to a
     * comparison to supply. Line 437 of {@code app/cbl/CBACT04C.cbl} moves the seven-character
     * literal {@code 'DEFAULT'} into a field declared {@code PIC X(10)} at line 6 of
     * {@code app/cpy/CVTRA02Y.cpy}, and an alphanumeric move into a wider alphanumeric field is
     * left-justified and space-filled, so the key the reference program searches with is that literal
     * followed by three spaces -- ten characters in total, which is how the seeded rows store it.</p>
     *
     * <p>Assumptions: the padding is not optional on this path even as a matter of taste, because
     * {@code DisclosureGroup.DisclosureGroupId} refuses a component that is not exactly its declared
     * width. A seven-character value here would be refused at key construction rather than quietly
     * searching for a row that does not exist.</p>
     */
    public static final String DEFAULT_ACCT_GROUP_ID = "DEFAULT   ";

    /**
     * The sentence reported when neither the group asked for nor the substituted group has a row.
     *
     * <p>Assumptions: the trailing ellipsis is load-bearing and is not decoration. The shared
     * exception advice renders a not-found message verbatim only when it carries the reference
     * message terminator and stays inside the reference message width, and otherwise substitutes its
     * own generic sentence. Removing the ellipsis would not fail a build; it would quietly replace
     * this wording in the 404 body with the generic one.</p>
     */
    public static final String MESSAGE_RATE_NOT_FOUND = "Disclosure group rate NOT found...";

    /**
     * The abend code the reference program moves before terminating, four characters wide.
     *
     * <p>Assumptions: the value is read from line 631 of {@code app/cbl/CBACT04C.cbl}, which moves
     * 999 to the abend code operand, and it is carried as text rather than as a number because the
     * component it populates is declared {@code PIC X(4)} and is operator-facing text that no
     * arithmetic ever consumes.</p>
     */
    private static final String ABEND_CODE_RATE_LOOKUP = "999";

    /**
     * The program named as the culprit of a terminal rate miss, eight characters or fewer.
     *
     * <p>Assumptions: the reference program itself is named rather than the Java class that detected
     * the condition, because this component is operator-facing and an operator tracing a missing
     * disclosure row needs the artifact the business rule lives in. It is also the value the
     * reference program's own abend would have carried.</p>
     */
    private static final String ABEND_CULPRIT_RATE_LOOKUP = "CBACT04C";

    /**
     * The reason text of a terminal rate miss, reproduced from the reference program verbatim.
     *
     * <p>Assumptions: carried character for character under AAP Rule T8 (user-visible strings are
     * verbatim) from line 455 of {@code app/cbl/CBACT04C.cbl}, which displays it immediately before
     * performing the abend paragraph at line 458. It is 38 characters and so fits the 50-character
     * reason component without being shortened, which is why this literal rather than the wider
     * message component carries it.</p>
     */
    private static final String ABEND_REASON_RATE_LOOKUP = "ERROR READING DEFAULT DISCLOSURE GROUP";

    /**
     * The message text of a terminal rate miss, reproduced from the reference program verbatim.
     *
     * <p>Assumptions: carried character for character under AAP Rule T8 (user-visible strings are
     * verbatim) from line 629 of {@code app/cbl/CBACT04C.cbl}, the first statement of the abend
     * paragraph. It is retained even though the migrated form does not terminate, because an operator
     * comparing a migrated diagnostic against a reference job log needs the same two strings to
     * appear together.</p>
     */
    private static final String ABEND_MSG_RATE_LOOKUP = "ABENDING PROGRAM";

    /**
     * Read access to the disclosure-group table.
     *
     * <p>Assumptions: this is the only collaborator the class has. The conversion to the published
     * reply is reached statically because
     * {@code com.carddemo.reference.mapper.DisclosureGroupMapper} is a stateless utility whose
     * constructor is private, so there is no instance of it to inject; see the constructor for why
     * that split is deliberate rather than an omission.</p>
     */
    private final DisclosureGroupRepository groups;

    /**
     * Builds the service over the one repository it reads.
     *
     * <p>Assumptions: constructor injection, and no other form. A field or setter injected
     * collaborator would let an instance exist in a half-built state, and it would also make this
     * class untestable without a container -- the module's own unit cases construct it directly with
     * a stub repository, which only a constructor makes possible.</p>
     *
     * <p>Alternatives Considered: injecting the mapper as a second constructor argument was
     * evaluated and is not possible against its actual shape: it is a final class with a private
     * constructor exposing one static conversion, so there is no bean to receive. Keeping it static
     * is the deliberate choice behind that shape -- the conversion reads no store, decides no rule
     * and holds no state, so an instance would add a lifecycle to manage and a seam to stub without
     * making any behaviour configurable. The property that matters for this class is that the
     * conversion stays the single place a stored decimal becomes the money type, and being static
     * does not weaken it.</p>
     *
     * @param groups the {@code DisclosureGroupRepository} this service reads both the requested and
     *     the substituted group through; supplied by the container and must not be {@code null}
     */
    public DisclosureGroupService(DisclosureGroupRepository groups) {
        this.groups = groups;
    }

    /**
     * Resolves the rate for one three-part key, substituting the default group on a miss.
     *
     * <p>This is the migrated form of {@code 1200-GET-INTEREST-RATE}, which opens at line 415 of
     * {@code app/cbl/CBACT04C.cbl}, together with the substitution branch that paragraph opens at line
     * 436. It reads the key exactly as asked for; if no row carries it, it reads again with the account
     * group replaced by {@link #DEFAULT_ACCT_GROUP_ID} and the type and category left alone; and if
     * neither read answers it raises rather than inventing a rate.</p>
     *
     * <p>Assumptions: no component is trimmed, padded or re-cased on the way to either read. A trimmed
     * probe would search for a key no row carries, and because a first miss substitutes rather than
     * failing, the symptom would not be an error at the lookup but an accrual at another group's
     * rate.</p>
     *
     * @param acctGroupId the account group asked for, as a {@code String} at the ten declared
     *     characters of the stored key including any trailing spaces; echoed back on the reply
     *     unchanged whether or not the substitution answered
     * @param tranTypeCd the two-character transaction type, as a {@code String}, carried into both
     *     reads unchanged because the reference substitution replaces the account group alone
     * @param tranCatCd the four-character transaction category, as a {@code String} retaining its
     *     leading zeros, carried into both reads unchanged for the same reason
     * @return a {@code DisclosureGroupRateResponse} carrying the group asked for, the group that
     *     answered, the type, the category, the rate as an exact money value and a boolean saying
     *     whether the substitution supplied it; never {@code null}
     * @throws DisclosureGroupNotFoundException when neither the group asked for nor the substituted
     *     group has a row for that type and category, which is a reachable terminal condition rather
     *     than an impossible one because the substitution is not total
     * @throws IllegalArgumentException if any component is not exactly its declared width, refused by
     *     the identity type this method builds its keys with rather than reshaped here
     */
    @Transactional(readOnly = true)
    public DisclosureGroupRateResponse findRate(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        Optional<DisclosureGroup> requested =
                readRequestedGroupRate(acctGroupId, tranTypeCd, tranCatCd);
        if (requested.isPresent()) {
            // WHY : Assumptions: an answer from the requested group is reported with the indicator
            //       false even when the caller asked about the substituted group by name. The
            //       indicator records whether a SUBSTITUTION happened, not which group answered, and
            //       line 436 of app/cbl/CBACT04C.cbl reaches the substitution only after a miss --
            //       so a direct read of the default group is a direct hit there too.
            return DisclosureGroupMapper.toResponse(
                    acctGroupId, requested.get(), RateSource.REQUESTED_GROUP.isDefaultGroupApplied());
        }

        // WHY : Assumptions: reaching this line is the migrated form of the status 23 test at line 436
        //       of app/cbl/CBACT04C.cbl, and it is an ordinary outcome rather than a failure. The
        //       first read tolerates a miss because line 422 accepts that status alongside the clean
        //       one; only a read that failed for some OTHER reason is fatal there, and in the migrated
        //       form such a failure arrives as an exception from the provider rather than as an empty
        //       result, so it never reaches this branch at all.
        Optional<DisclosureGroup> substituted = readDefaultGroupRate(tranTypeCd, tranCatCd);
        if (substituted.isPresent()) {
            return DisclosureGroupMapper.toResponse(
                    acctGroupId, substituted.get(), RateSource.DEFAULT_GROUP.isDefaultGroupApplied());
        }

        // WHY : Assumptions: both reads having missed is the one outcome the reference program does not
        //       survive, and it is reported here rather than reproduced. Line 446 accepts only the
        //       clean status, so line 455 displays its message and line 458 performs the abend, which
        //       calls the environment abend service at line 632 with a timing operand of 0. The Java
        //       raises instead, carrying the same operator-facing values, and the divergence is
        //       registered in docs/architecture/cobol-to-service-traceability.md.
        throw new DisclosureGroupNotFoundException(
                acctGroupId, tranTypeCd, tranCatCd, terminalMissAbendDetail());
    }

    /**
     * Resolves the rate for one three-part key under the name this module's callers already bind to.
     *
     * <p>Assumptions: this method exists so that one lookup has one implementation. It delegates
     * verbatim to {@link #findRate(String, String, String)}, which carries the transcription and the
     * reasoning, and it adds no behaviour of its own.</p>
     *
     * <p>Refactoring Rationale: it DOES declare its own read-only transaction, and the sentence here
     * previously said the opposite -- that no demarcation was needed "since the delegate declares its own
     * and a caller of either name gets the same read-only boundary". That is not how the framework's
     * transaction advice works. The advice lives in a proxy around this bean, and a call from one member
     * of the bean to another goes straight down the {@code this} reference, so the proxy is never
     * traversed and the annotation on the delegate is not consulted. This method is the one the route
     * calls, so under the old arrangement the route-facing lookup ran with NO transaction at all: its two
     * reads each took and returned a connection separately, they could observe different committed states,
     * and the read-only hint that lets the driver and the database skip write bookkeeping was never
     * applied. Annotating the entry point is what makes the documented boundary real, and the annotation
     * on the delegate is retained so a direct caller of that name is equally covered -- a nested call
     * inside an active transaction joins it rather than starting a second.</p>
     *
     * <p>Alternatives Considered: renaming the single entry point and updating its callers was
     * evaluated and rejected. The controller in {@code com.carddemo.reference.api} and this module's
     * behavioural cases are already written against this name and already pass; renaming would edit
     * files owned elsewhere to no behavioural end, and a delegating name whose body is one statement
     * cannot drift from the method it forwards to. Retaining both names also keeps the transcription
     * discoverable under the name that matches the paragraph it migrates.</p>
     *
     * <p>Trade-offs: with both names annotated, an internal call from {@link #findRate(String, String,
     * String)} to this method would join the caller's transaction rather than start a second, which is
     * the propagation default and is what is wanted; no such call exists, and the delegation runs the
     * other way.</p>
     *
     * @param acctGroupId the account group asked for, as a {@code String} at the ten declared
     *     characters of the stored key including any trailing spaces
     * @param tranTypeCd the two-character transaction type, as a {@code String}
     * @param tranCatCd the four-character transaction category, as a {@code String} retaining its
     *     leading zeros
     * @return the same {@code DisclosureGroupRateResponse} {@link #findRate(String, String, String)}
     *     answers with, reporting which group supplied the rate; never {@code null}
     * @throws DisclosureGroupNotFoundException when neither the group asked for nor the substituted
     *     group has a row for that type and category
     * @throws IllegalArgumentException if any component is not exactly its declared width
     */
    @Transactional(readOnly = true)
    public DisclosureGroupRateResponse resolveRate(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        return findRate(acctGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Reads the row for the key exactly as asked for, reporting an absent row as an empty result.
     *
     * <p>This is the migrated form of the read at line 416 of {@code app/cbl/CBACT04C.cbl}, the first
     * statement of {@code 1200-GET-INTEREST-RATE}, whose {@code INVALID KEY} phrase at line 417
     * displays {@code 'DISCLOSURE GROUP RECORD MISSING'} at line 418 and
     * {@code 'TRY WITH DEFAULT GROUP CODE'} at line 419 before the paragraph goes on to test the
     * status.</p>
     *
     * <p>Assumptions: those two displays are diagnostic narration in the reference program and are not
     * reproduced as log statements here, because the migrated form reports the same two facts
     * structurally -- a miss is an empty result, and what follows a miss is the substituted read the
     * next method performs. Emitting them per miss would write two lines for every balance row whose
     * group is absent, which for a group missing from the seeded data is every row it owns. They are
     * cited rather than emitted so that a reader tracing the paragraph still finds them.</p>
     *
     * <p>Assumptions: an empty result here means the key is absent and nothing worse. A read that
     * failed for some other reason surfaces as an exception from the persistence provider, which is
     * the migrated counterpart of the status test at line 422 rejecting anything other than the clean
     * or not-found statuses and performing the abend at line 434 after displaying
     * {@code 'ERROR READING DISCLOSURE GROUP FILE'} at line 431.</p>
     *
     * @param acctGroupId the account group asked for, as a {@code String} at its ten declared
     *     characters, passed to the identity type unaltered
     * @param tranTypeCd the two-character transaction type, as a {@code String}
     * @param tranCatCd the four-character transaction category, as a {@code String}
     * @return an {@code Optional} holding the matching {@code DisclosureGroup} with its rate, or an
     *     empty {@code Optional} when no row carries that key, which is the condition the caller
     *     substitutes on
     * @throws IllegalArgumentException if any component is not exactly its declared width, refused by
     *     the identity type's constructor
     */
    private Optional<DisclosureGroup> readRequestedGroupRate(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        return this.groups.findByIdIs(new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd));
    }

    /**
     * Reads the row for the substituted account group, holding the type and category unchanged.
     *
     * <p>This is the migrated form of {@code 1200-A-GET-DEFAULT-INT-RATE}, which opens at line 443 of
     * {@code app/cbl/CBACT04C.cbl}. It is a SEPARATE paragraph there rather than a loop back into the
     * paragraph that calls it, and the separation carries meaning: it has no substitution of its own,
     * so there is no second fallback and no possibility of recursion.</p>
     *
     * <p>Assumptions: the paragraph's status test is STRICTER than that of the paragraph that
     * delegates to it, and the difference is the whole reason a terminal condition exists. Line 422
     * accepts the clean status OR the not-found status, which is what makes a first miss recoverable;
     * line 446 accepts the clean status ALONE. A second miss is therefore unconditionally fatal in the
     * reference program by construction rather than by policy -- it displays its message at line 455
     * and performs the abend at line 458.</p>
     *
     * <p>Assumptions: the read at line 444 is bare -- it carries no {@code INVALID KEY} phrase and no
     * {@code END-READ}, unlike the read at line 416 -- so the reference program distinguishes a miss
     * from a clean read there only through the status test that follows. The migrated form reports the
     * miss as an empty result and lets the caller raise, and that divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: this method takes no account group parameter, and the omission is deliberate
     * rather than an oversight. The substituted group is not a caller's choice: line 437 moves one
     * specific literal, and admitting a parameter would let a caller nominate a different fallback
     * group and quietly accrue at a rate the reference program never consults.</p>
     *
     * @param tranTypeCd the two-character transaction type, as a {@code String}, carried through from
     *     the read that missed because line 437 rewrites only the account group and leaves the value
     *     line 212 placed in this component untouched
     * @param tranCatCd the four-character transaction category, as a {@code String}, carried through
     *     unchanged for the same reason with respect to line 211
     * @return an {@code Optional} holding the substituted group's {@code DisclosureGroup} with its
     *     rate, or an empty {@code Optional} when even that group has no row for the pair, which is
     *     the terminal condition
     * @throws IllegalArgumentException if either component is not exactly its declared width, refused
     *     by the identity type's constructor
     */
    private Optional<DisclosureGroup> readDefaultGroupRate(String tranTypeCd, String tranCatCd) {

        return this.groups.findByIdIs(
                new DisclosureGroupId(DEFAULT_ACCT_GROUP_ID, tranTypeCd, tranCatCd));
    }

    /**
     * Builds the structured operator record that stands in for the reference program's termination.
     *
     * <p>Assumptions: the four components are drawn from the reference program rather than composed
     * afresh, so that an operator can line a migrated diagnostic up against a reference job log. The
     * code is the 999 moved at line 631 of {@code app/cbl/CBACT04C.cbl}; the culprit is that program;
     * the reason is the message displayed at line 455 immediately before the abend is performed at
     * line 458; and the message is the one displayed at line 629 as the abend paragraph's first
     * statement.</p>
     *
     * <p>Assumptions: the values are constant for every terminal miss and carry no key components,
     * because this record is operator-facing text that the shared error rendering may surface outside
     * the process. The key that missed travels on the exception instead, where it reaches the
     * operational log without being published in an error body.</p>
     *
     * @return a fully populated {@code AbendDetail} whose four components are normalised to their
     *     declared widths by its own constructor; never {@code null}
     */
    private static AbendDetail terminalMissAbendDetail() {

        // WHY : Assumptions: this is the HARD abend of the two similarly named paragraphs in this
        //       migration, and the two must not be merged. app/cbl/CBACT04C.cbl names its paragraph
        //       9999-ABEND-PROGRAM at line 628 and it genuinely terminates -- line 632 calls the
        //       environment abend service with the timing operand line 630 sets to 0, meaning
        //       immediate termination with no normal termination processing, and the program performs
        //       it from seventeen separate sites. app/app-transaction-type-db2/cbl/COBTUPDT.cbl names
        //       its paragraph 9999-ABEND at lines 230 to 233 and does NOT terminate: it displays a
        //       message and moves 4 to the return code, leaving its read loop free to take the next
        //       record. A reader who has seen the soft one could reasonably assume this one is also a
        //       soft reject, which is why the severities are recorded here at the point the abend
        //       values are built. Only the hard paragraph is transcribed by this method.
        return new AbendDetail(
                ABEND_CODE_RATE_LOOKUP,
                ABEND_CULPRIT_RATE_LOOKUP,
                ABEND_REASON_RATE_LOOKUP,
                ABEND_MSG_RATE_LOOKUP);
    }

    /**
     * Which of the two reads answered, and therefore what the published indicator says.
     *
     * <p>Assumptions: this exists so that the boolean the reply carries is derived from a named
     * outcome rather than written as a bare literal at each call. The two arguments the conversion
     * takes at the two call sites differ only by that boolean, and both call sites also pass the same
     * account group, so a transposed or mistyped literal would compile and would misreport every
     * substitution while returning a rate that is otherwise correct.</p>
     *
     * <p>Alternatives Considered: a record pairing the row with the outcome was evaluated and
     * rejected, and so was passing the literals directly. A record would add a wrapper that every
     * branch immediately unpacks, and its components would each need their own documented parameter
     * tag for no gain in meaning; bare literals would leave the one detail worth naming unnamed. An
     * enum names both outcomes, cannot be transposed with the account group because the types differ,
     * and keeps the mapping from outcome to indicator in exactly one place.</p>
     */
    public enum RateSource {

        /** The group asked for had its own row, so no substitution took place. */
        REQUESTED_GROUP(false),

        /** The group asked for had no row and the substituted group answered instead. */
        DEFAULT_GROUP(true);

        /** Whether this outcome represents a substitution, as published on the reply. */
        private final boolean defaultGroupApplied;

        /**
         * Binds one outcome to the indicator value the reply publishes for it.
         *
         * @param defaultGroupApplied {@code true} when this outcome means the substituted group
         *     answered, {@code false} when the group asked for had a row of its own
         */
        RateSource(boolean defaultGroupApplied) {
            this.defaultGroupApplied = defaultGroupApplied;
        }

        /**
         * Reports whether this outcome means the substituted group supplied the rate.
         *
         * @return {@code true} for a substitution and {@code false} for a direct read, which is the
         *     value the reply's indicator carries
         */
        public boolean isDefaultGroupApplied() {
            return this.defaultGroupApplied;
        }
    }

    /**
     * Raised when neither the group asked for nor the substituted group has a row for the pair.
     *
     * <p>Assumptions: this is the terminal condition the substitution cannot resolve, and it is
     * reachable rather than defensive -- one type-and-category pair has no disclosure row under any
     * group, as the class document sets out. The reference program does not survive this outcome: it
     * displays its message at line 455 of {@code app/cbl/CBACT04C.cbl} and performs the abend at line
     * 458. The Java reports it instead, carrying the same operator-facing values as an
     * {@link AbendDetail}, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Alternatives Considered: a separate top-level exception file was evaluated and rejected. The
     * charter beside this file settles the inventory of this package, and a condition raised by
     * exactly one method of exactly one class has no second consumer to justify a file of its own;
     * nesting keeps the condition and the reasoning that produces it in one place a reader arrives at
     * together.</p>
     *
     * <p>Alternatives Considered: extending the general unchecked base directly was evaluated and
     * rejected in favour of extending the no-such-element type, which is itself an unchecked
     * exception. Two properties decided it. The shared advice in
     * {@code com.carddemo.common.error.GlobalExceptionHandler} already answers that type with a 404
     * and renders a message carrying the reference terminator verbatim, so this condition reaches the
     * published contract's 404 without a second advice being declared in this module -- which the
     * charter forbids outright. And the semantics agree exactly: the requested element is absent. A
     * distinct subclass is still declared rather than raising the base type bare, because it remains
     * discriminable by its own fully qualified class name for a consumer that walks a cause chain by
     * name, and because it can carry the key that missed alongside the abend record where the bare
     * type could carry only a sentence.</p>
     *
     * <p>Assumptions: the message is exactly {@link #MESSAGE_RATE_NOT_FOUND} and carries no key
     * component. The key travels on the accessors below so that it reaches an operational log, while
     * the sentence a client sees names no account group -- an error body that echoed the key back
     * would widen what an unauthenticated probe can learn about which groups exist.</p>
     */
    public static final class DisclosureGroupNotFoundException extends NoSuchElementException {

        /**
         * The serialization identity of this exception, declared rather than generated.
         *
         * <p>Assumptions: the inherited hierarchy is serializable, so a value is declared explicitly to
         * keep it stable if a member is ever added. A generated identity changes with the shape of the
         * class, which would break a peer holding a serialized copy for no functional reason.</p>
         */
        private static final long serialVersionUID = 1L;

        /** The account group that was asked for and had no row. */
        private final String requestedAcctGroupId;

        /** The two-character transaction type of the key that missed. */
        private final String tranTypeCd;

        /** The four-character transaction category of the key that missed. */
        private final String tranCatCd;

        /** The structured operator record standing in for the reference program's termination. */
        private final AbendDetail abendDetail;

        /**
         * Builds the condition, recording the key that missed and the operator record for it.
         *
         * @param requestedAcctGroupId the account group that was asked for, as a {@code String} at its
         *     ten declared characters including any trailing spaces, retained untrimmed so that a log
         *     shows the key exactly as it was searched for
         * @param tranTypeCd the two-character transaction type of the key that missed, as a
         *     {@code String}
         * @param tranCatCd the four-character transaction category of the key that missed, as a
         *     {@code String} retaining its leading zeros
         * @param abendDetail the {@code AbendDetail} carrying the reference program's abend code,
         *     culprit, reason and message for this condition
         */
        public DisclosureGroupNotFoundException(String requestedAcctGroupId, String tranTypeCd,
                String tranCatCd, AbendDetail abendDetail) {

            // WHY : Assumptions: the sentence passed to the inherited constructor is the shared
            //       catalogue value and nothing is interpolated into it. The shared advice renders a
            //       not-found message verbatim only when it matches the reference message shape, so a
            //       sentence with a key spliced in would fail that test and be replaced wholesale by
            //       the generic sentence -- losing the specific wording rather than adding detail to
            //       it.
            super(MESSAGE_RATE_NOT_FOUND);
            this.requestedAcctGroupId = requestedAcctGroupId;
            this.tranTypeCd = tranTypeCd;
            this.tranCatCd = tranCatCd;
            this.abendDetail = abendDetail;
        }

        /**
         * Returns the account group that was asked for, untrimmed.
         *
         * @return the requested account group as a {@code String} at its ten declared characters
         */
        public String getRequestedAcctGroupId() {
            return this.requestedAcctGroupId;
        }

        /**
         * Returns the transaction type of the key that missed.
         *
         * @return the two-character transaction type as a {@code String}
         */
        public String getTranTypeCd() {
            return this.tranTypeCd;
        }

        /**
         * Returns the transaction category of the key that missed.
         *
         * @return the four-character transaction category as a {@code String} with leading zeros intact
         */
        public String getTranCatCd() {
            return this.tranCatCd;
        }

        /**
         * Returns the structured operator record built for this condition.
         *
         * @return the {@code AbendDetail} carrying the reference program's abend code, culprit, reason
         *     and message
         */
        public AbendDetail getAbendDetail() {
            return this.abendDetail;
        }
    }
}
