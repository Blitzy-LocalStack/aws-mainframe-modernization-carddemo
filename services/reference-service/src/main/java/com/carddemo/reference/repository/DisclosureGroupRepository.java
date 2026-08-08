//=============================================================================
// WHY : Assumptions: the charter beside this file, package-info.java, is where
//       the rulings every interface in this package obeys are recorded -- how a
//       line citation resolves, which type a category code takes, how wide the
//       account group is, and what may bound a walk. They are CITED below and
//       deliberately not restated, because a restated ruling can drift while
//       still reading as agreement, and the charter says so of itself.
// WHY : Assumptions: every line number cited in this file is a PHYSICAL line
//       number, verified by reading the line at that address and never by
//       searching for a printed sequence value. The one program transcribed
//       here, app/cbl/CBACT04C.cbl, is 652 physical lines and carries no
//       printed sequence field, so a physical address is the only address it
//       has and the two cannot disagree. That is not true of every program in
//       the baseline, and the charter records the hazard in full.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       modified; where the migrated behaviour departs from it deliberately the
//       departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is
//       maintained elsewhere and referenced rather than reproduced.
//=============================================================================
package com.carddemo.reference.repository;

import com.carddemo.reference.domain.DisclosureGroup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.disclosure_groups}: one read of the interest rate a three-part key
 * discloses, and nothing else.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface is the migrated form of the keyed read that interest accrual performs once per
 * balance row, and it holds nothing besides: no rule, no transfer object, no arithmetic and no attempt
 * at an HTTP status. The rate this read returns is consumed by
 * {@code com.carddemo.reference.service.DisclosureGroupService}, which owns the decision described
 * under the fallback heading below, and the mapping onto published shapes lives in
 * {@code com.carddemo.reference.mapper}.</p>
 *
 * <p>Assumptions: the charter records at its lines 101 to 108 that this interface offers one keyed
 * finder and no walk at all, on the ground that the baseline reads this data by key twice over and
 * never browses it. The surface below is that ruling and not a reduction of a larger one.</p>
 *
 * <h2>The declared finder and the inherited one beside it</h2>
 *
 * <p>{@code JpaRepository} already supplies {@code findById}, which takes the same identity and returns
 * the same {@code Optional}, so the finder declared below duplicates a predicate the base interface
 * would generate anyway. It is declared explicitly regardless, and the reason is documentary rather
 * than functional.</p>
 *
 * <p>Alternatives Considered: relying on the inherited {@code findById} alone and declaring no member
 * here. Rejected, because the two contracts a caller of this read has to know -- that the account group
 * arrives at its declared width, and that an empty result is the condition the fallback turns on rather
 * than an error -- would then have no member to attach to. An inherited method carries the framework's
 * own documentation, not this migration's, so those contracts would live only in prose on the type
 * while the member a caller actually invokes said nothing. The declared finder gives them a home on the
 * signature that consumers bind to.</p>
 *
 * <p>Trade-offs: the cost of that choice is two spellings of one read, since declaring this member does
 * not withdraw the inherited one. It is accepted because both resolve to the identical primary-key
 * predicate, so the two cannot return different rows; the alternative traded a documented contract for
 * a cosmetic reduction in surface.</p>
 *
 * <h2>The account group is ten characters wide and is never trimmed</h2>
 *
 * <p>Assumptions: the trailing spaces in an account group value are part of the stored key rather than
 * formatting, on either side of a comparison. {@code app/cbl/CBACT04C.cbl} declares the field at line 79
 * as {@code 10 FD-DIS-ACCT-GROUP-ID           PIC X(10).} inside the key group opened at line 78, which
 * line 50 names as the record key. When a rate lookup misses, line 437 falls back by moving the
 * seven-character literal {@code 'DEFAULT'} into that ten-byte alphanumeric field; a short literal moved
 * into a longer alphanumeric field is left-justified and space-filled, so the key actually searched for
 * is {@code DEFAULT} followed by three spaces. The seed agrees: the first ten bytes of
 * {@code app/data/ASCII/discgrp.txt} hold exactly three distinct group values across its 51 rows, and
 * two of the three are space-padded to ten characters.</p>
 *
 * <p>Assumptions: ownership of that width is unambiguous and it does not sit here. This interface
 * neither pads a value nor removes padding from one, because it never sees a loose string: the identity
 * type {@code DisclosureGroup.DisclosureGroupId} checks each component for exact width in its
 * constructor and refuses any other width outright, so a seven-character group value is rejected at
 * construction and never reaches a query. Keying this finder on that identity rather than on three
 * separate string parameters is what routes every caller through the check instead of past it.</p>
 *
 * <p>Alternatives Considered: declaring the column {@code VARCHAR(10)} and reconciling the two spellings
 * by removing padding on both sides of the comparison. Rejected on two counts. The authoritative column
 * contract, this module's {@code src/main/resources/db/migration/V1__reference.sql} at line 311, states
 * {@code acct_group_id CHAR(10) NOT NULL}; and admitting a padded and an unpadded form of one logical
 * key would leave a class of lookups that fail only for the two padded group values. The failure mode is
 * what settles it: because a miss on this read falls back rather than raising, a probe that matched
 * nothing would surface as an interest figure quietly accrued at the fallback rate, not as an error at
 * the lookup that caused it.</p>
 *
 * <h2>The rate is an exact scaled decimal, and no arithmetic happens here</h2>
 *
 * <p>Assumptions: the rate is exact base-ten throughout -- {@code interest_rate NUMERIC(6,2) NOT NULL}
 * at {@code V1__reference.sql} line 337, and {@code java.math.BigDecimal} on the entity member. The
 * baseline field is {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9, a zoned
 * decimal carrying its sign as an overpunch in the trailing byte, which is itself an exact base-ten
 * encoding: the seed stores a rate of 15.00 as the six bytes <code>00150&#123;</code>, whose trailing
 * byte is the overpunch standing for a positive zero.</p>
 *
 * <p>Assumptions: that one value is shown with a plain code element and a character entity rather than
 * with an inline code tag, because the overpunch character is an opening brace. An inline code tag is
 * delimited by counting braces, so an unbalanced brace inside one consumes the tag's own terminator and
 * the rest of the block stops rendering. The entity form is what lets the byte be shown exactly as the
 * seed stores it.</p>
 *
 * <p>Assumptions: the rate is not a value that is merely displayed, which is why the encoding matters.
 * {@code app/cbl/CBACT04C.cbl} computes at lines 464 to 465
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, forming the product before the
 * quotient, and the result is written as a generated interest transaction that posts to a balance. A
 * rate routed through a binary floating-point type would perturb that product before the division ever
 * happened, and the error would settle into money a statement reports rather than showing up at the
 * conversion that introduced it. Neither of the two binary floating-point primitives nor either of their
 * wrapper types appears anywhere in this file or in anything it returns.</p>
 *
 * <p>Trade-offs: what enforces that exclusion and what does not is worth stating precisely, because a
 * green build is easy to mistake for a proof of correctness. The shared architecture test's money rule
 * was originally scoped to the shared money package alone and has since been widened: its subject is now
 * every production type under {@code com.carddemo}, this package included, so a binary floating-point
 * field, parameter or return type declared here -- including as a generic type argument -- fails the
 * build rather than only review. What that rule cannot do is read arithmetic. It cannot tell whether a
 * scale is 2, whether rounding is half-up, or whether the product above was formed before the quotient
 * as the cited computation requires. Those remain a review obligation, and they are one reason this
 * interface computes nothing.</p>
 *
 * <p>Refactoring Rationale: no convenience member that multiplies, divides or rounds the rate belongs on
 * this interface, and none is offered. A repository returns rows; the accrual that consumes this rate is
 * a batch concern and is implemented there, against the shared money type whose scale and rounding are
 * tested. Folding the computation in here would put a second, untested rounding contract beside the one
 * that already exists and would make the cited operation order a property of a query interface.</p>
 *
 * <p>Assumptions: a rate of zero is a meaningful value and not a missing one, so it is never mapped to
 * an absent result. {@code app/cbl/CBACT04C.cbl} guards the computation at line 214 with
 * {@code IF DIS-INT-RATE NOT = 0} before performing it at line 215, which makes zero the value that
 * suppresses interest generation for that balance. The seed carries such rows: several are written
 * {@code 0.00}. Reporting one as an empty result would send its caller down the fallback path and accrue
 * interest at another group's rate where the baseline accrues none.</p>
 *
 * <h2>The category component is character data, four wide</h2>
 *
 * <p>Alternatives Considered: typing the category component as an integer, which is the reading a
 * numeric picture invites and therefore a reasonable alternative someone will propose. It is rejected,
 * and the disagreement is named rather than hidden, because {@code app/cpy/CVTRA02Y.cpy} line 8 does
 * declare the field {@code DIS-TRAN-CAT-CD PIC 9(04)} -- so this is one place the copybook-is-normative
 * rule is not applied mechanically. Three character-typed sources decide it:
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 3 declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} and line 5 places it in the primary key;
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} line 43 generates the host variable as
 * {@code PIC X(4)}, character and not numeric; and {@code app/data/ASCII/discgrp.txt} stores the codes
 * zero-padded, its distinct values being {@code 0001} through {@code 0004}. An integer would hold
 * {@code 0001} as 1, and a key built from that value would compare against rows it cannot match. The
 * authoritative contract follows the character reading at {@code V1__reference.sql} line 319.</p>
 *
 * <p>Assumptions: the one {@code FILLER} of this record is dropped, and the drop is recorded here
 * because the migration's copybook rule requires it to be recorded per record.
 * {@code app/cpy/CVTRA02Y.cpy} line 10 declares {@code FILLER PIC X(28)}, padding the record to its
 * declared 50 bytes; 10 plus 2 plus 4 plus 6 plus 28 accounts for all of them, so nothing is discarded
 * except the padding itself. No member of this interface exposes it.</p>
 *
 * <h2>The fallback group, and why its rows are not optional</h2>
 *
 * <p>Assumptions: the baseline resolves a rate in two keyed reads rather than one, and the second read
 * is not a nicety. {@code app/cbl/CBACT04C.cbl} reads at line 416 after the key is assembled at lines
 * 210 to 212, and treats a status of {@code '00'} or {@code '23'} as acceptable at line 422. On a
 * genuine read error it reports {@code 'ERROR READING DISCLOSURE GROUP FILE'} at line 431 and performs
 * the abend at line 434. On the not-found status alone -- line 436, {@code IF DISCGRP-STATUS = '23'} --
 * it substitutes the fallback literal at line 437 and performs {@code 1200-A-GET-DEFAULT-INT-RATE} at
 * line 438. That paragraph opens at line 443, reads again at line 444, and accepts only {@code '00'} at
 * line 446; on anything else it reports {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} at line 455 and
 * performs the abend at line 458, whose body at line 628 moves 0 to the timing operand at line
 * 630, moves 999 to the abend code at line 631 and calls the environment's abend service at line
 * 632.</p>
 *
 * <p>Assumptions: the consequence for the schema is that the fallback rows are load-bearing. An
 * unreadable fallback group terminates the baseline program; it does not yield a soft default. The seed
 * migration {@code src/main/resources/db/migration/V2__seed_reference.sql} therefore loads all 51
 * rows of the source file, of which 17 carry the padded fallback group value, and its insert is
 * idempotent through a do-nothing conflict clause so a repeated load neither duplicates nor
 * disturbs them. Where the baseline abends at line 458 the migrated path raises a specific,
 * diagnosable condition instead, and that divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Refactoring Rationale: the two-step decision is deliberately NOT expressed as a single member here.
 * Choosing to read the fallback group when the requested group has no row is a business rule, and this
 * package is data access; the rule therefore lives one layer out, in
 * {@code com.carddemo.reference.service.DisclosureGroupService}, which calls the finder below twice --
 * once for the requested key and once with the group component replaced -- and raises when neither
 * resolves. Two further reasons make that the stronger split. The reply has to distinguish a direct hit
 * from a substitution, which a single member returning one row could not express, and so a group missing
 * from the seed would have no symptom at all. And only the group component is replaced on the second
 * read, exactly as line 437 replaces only that field: a member that widened the type or the category
 * would return the rate of a different product.</p>
 *
 * <h2>What this interface does not do</h2>
 *
 * <ul>
 *   <li><b>No walk over this table</b>, for the reason the charter gives at its lines 101 to 108: the
 *       cited program resolves one rate per balance row and never enumerates rates, so a walk here would
 *       be a query no baseline program performs. Nor is a window positioned by ordinal available
 *       anywhere in this package, because such a window skips and repeats rows when inserts land
 *       concurrently.</li>
 *   <li><b>No caller-supplied ordering</b>, which a single-row read by primary key has no use for.</li>
 *   <li><b>No modifying statement</b>, derived or bulk. Nothing in the migrated system replaces a row in
 *       this table; it is seeded by the migration that ships beside it, which is also why the entity
 *       carries no concurrency counter and why the authoritative contract declares none for it.</li>
 *   <li><b>No exception handler and no status mapping.</b> A failure surfaces through the
 *       framework's own translation and is rendered by
 *       {@code com.carddemo.common.error.GlobalExceptionHandler}, which this module inherits rather
 *       than duplicating, so the status and its wording stay identical across every service.</li>
 *   <li><b>No schema qualification on any query.</b> The schema is pinned by the JDBC search path that
 *       {@code com.carddemo.reference.config} configures, so naming it here would give one setting two
 *       owners that could disagree.</li>
 *   <li><b>No caching, no message broker and no read replica.</b> This read is a primary-key
 *       probe, and a second copy of the data would add a staleness window the baseline cannot
 *       exhibit.</li>
 *   <li><b>No retry and no resilience library</b>, which the shared architecture test enforces
 *       rather than merely intending.</li>
 *   <li><b>No code generation.</b> No annotation processor supplies any member here, because a generated
 *       member cannot carry the documentation the project's Explainability rule requires of it, and the
 *       Javadoc audit grants no annotation an exemption from needing one.</li>
 * </ul>
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {

    /**
     * Reads the one disclosure group carrying the whole three-part key, if the table holds it.
     *
     * <p>Assumptions: an empty result means the key is absent, and that is a routine outcome rather than
     * a failure -- it is the condition on which the caller substitutes the fallback group, mirroring the
     * not-found status tested at {@code app/cbl/CBACT04C.cbl} line 436. It is reported as an absent
     * result and never as an exception or a substituted row, because a repository that silently answered
     * with a neighbouring group's rate would make a gap in the seed indistinguishable from a genuine
     * rate.</p>
     *
     * <p>Assumptions: the identity is accepted as given and no component is padded or narrowed on
     * the way to the query. Each component was checked for exact width when the identity was
     * constructed, so a value that reached this call is already the width its column declares;
     * adjusting it here would either undo that check or apply it twice with two owners.</p>
     *
     * @param id the composite identity to read -- the account group at its declared ten characters
     *     including any trailing spaces, the two-character transaction type, and the four-digit
     *     transaction category with its leading zeros intact
     * @return the matching group carrying its rate as an exact scaled decimal, or an empty result
     *     when no row holds that key, which is what drives the caller's fallback rather than a
     *     refusal
     */
    Optional<DisclosureGroup> findByIdIs(DisclosureGroup.DisclosureGroupId id);
}
