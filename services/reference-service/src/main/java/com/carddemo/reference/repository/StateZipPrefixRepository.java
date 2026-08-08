package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsStateZipPrefix;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_state_zip_prefixes}, the seeded state-and-postal-prefix allow-list.
 *
 * <p>Purpose: the migrated form of the baseline's membership test over one concatenated four-character
 * value, together with the bounded ordered walks and the keyed read the published contract declares.
 * Where the baseline compares a candidate against a condition name holding 240 literals, this
 * repository answers whether a row carrying that value exists.</p>
 *
 * <p>Assumptions: the membership test itself declares no method here, because it is the inherited
 * {@code existsById}, taking the assembled four-character value as its identity argument. It is named
 * here rather than restated as a derived existence finder: a derived one would answer precisely what
 * the inherited one already answers, leaving two members spelling one question and two places for that
 * question to drift apart. The keyed finder below is declared for a different reason -- the contract's
 * read operation replies with a representation rather than with a bare status, so its caller needs the
 * row and not a flag.</p>
 *
 * <p>Assumptions: the key is the whole of the four characters, assembled as a two-character state code
 * followed by the two leading digits of a postal code, in that order, with no separator and in upper
 * case. Assembling it is the caller's obligation, and getting it wrong is silent rather than loud: a
 * probe whose significant characters differ from a seeded combination -- a lower-case state code, a
 * separator, a shortened value, a fifth significant character -- is one the set does not carry, so it
 * reads as absent rather than raising. Which characters count as significant is decided by the declared
 * column type and not here, and the decision is named because it is easy to get backwards: the column
 * is CHAR, so a comparison is padded to the declared width and a probe carrying nothing but blanks past
 * the fourth character still matches its unpadded form, while nothing is trimmed or folded on either
 * side beyond that padding. The baseline assembles the value the same way and asks nothing else of it,
 * stringing the two halves {@code INTO US-STATE-AND-FIRST-ZIP2} at {@code app/cbl/COACTUPC.cbl} L2540
 * and then asking {@code IF VALID-US-STATE-ZIP-CD2-COMBO} of the assembled value at L2542.</p>
 *
 * <p>Alternatives Considered: a two-column key holding the state code beside the postal prefix, and a
 * foreign key from this table to {@code reference.us_states}. Both were genuinely available and both
 * are rejected, because {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at {@code app/cpy/CSLKPCDY.cpy} L1073
 * tests the concatenation declared one line above it as
 * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4).} at L1072. Its 240 literals, running from {@code AA34} to
 * {@code WY83}, are therefore a set of admitted pairs and not one set of states combined freely with one
 * set of prefixes: splitting the value would admit pairs the baseline refuses, unless the two columns
 * were always compared together again -- which is the same single equality carrying more parts to keep
 * aligned. A second and independent reason is sharper still: the postal half is two characters and not a
 * number, and a split that typed it numerically would lose every combination whose postal half opens
 * with the digit zero, because such a value would be stored and compared as one digit. This module's
 * {@code src/main/resources/db/migration/V1__reference.sql} settles it
 * identically, declaring one column {@code state_zip_cd CHAR(4) NOT NULL} as the whole primary key and
 * declaring no foreign key on this table, so no association to the state lookup is navigable from
 * here.</p>
 *
 * <p>Assumptions: the remaining three digits of a postal code have no column, no property and no
 * parameter anywhere here, and that omission is recorded rather than left to be noticed. The group
 * opened at {@code app/cpy/CSLKPCDY.cpy} L1071 spans seven characters, of which the four-character
 * field at L1072 contributes four; the three-character subordinate declared at L1314, after the last of
 * the 240 literals at L1313, carries the balance. Those three digits belong to the value being
 * validated rather than to the reference data that validates it, so they are dropped exactly as a
 * padding field is dropped, and the drop is stated here so a reader can tell it from an oversight.</p>
 *
 * <p>Refactoring Rationale: the three walks below replace a cursor that the baseline opens, fetches
 * through and closes inside one screen turn. Each is instead a single stateless query positioned by a
 * key it is handed, so no cursor and no conversation state survives a request on the server and two
 * instances of this service are interchangeable. A position is a key naming one row rather than a
 * distance into the set, which is what keeps a row from being missed or returned again when another
 * caller inserts or deletes elsewhere in the table part-way through a walk. The full ruling, the
 * evidence behind it and the mechanisms admissible for bounding a walk are settled once in this
 * package's descriptor and are cited from here rather than restated, so that ruling keeps one owner.</p>
 *
 * <p>Trade-offs: the single-column key gives up a direct equality path for "which postal prefixes does
 * one state admit", which the rejected two-column key would have offered. The cost is accepted because
 * the baseline asks only the membership question at {@code app/cbl/COACTUPC.cbl} L2542, and because a
 * state's two-character code leads the key, so the prefixes it admits still form one contiguous run of
 * the ordered walks below.</p>
 *
 * <p>Assumptions: every member here is required by a named consumer and none is speculative. This
 * module's published contract, {@code src/main/resources/openapi/reference-api.yaml}, declares
 * {@code listUsStateZipPrefixes} over {@code /api/v1/reference/us-state-zip-prefixes} with a cursor and
 * a direction, which is what requires three walks rather than one, and {@code getUsStateZipPrefix} over
 * the same collection addressed by the four-character value, which is what requires the keyed finder.
 * Nothing beyond those two operations and the inherited membership test is declared.</p>
 *
 * <p>Assumptions: the stereotype below is redundant to the persistence framework, which registers a
 * bean for any interface extending the base repository whether it is annotated or not. It is written
 * anyway, and only so that the package reads uniformly: the sibling interfaces beside this one carry
 * it, and its absence on this one alone would read as a deliberate difference that does not exist.</p>
 *
 * <p>Assumptions: this compilation unit holds no executable line at all -- every member is an abstract
 * declaration -- so the rationale the Explainability rule asks for in an adjacent comment has no
 * statement to sit beside, and it is carried in this block and in each member's own block instead,
 * which is the only placement available. On exceptions: no member here declares or throws one, so no
 * member carries an exception at-clause, and the absence is stated rather than left silent so that a
 * reader can tell a declared inapplicability from an omission. That rule's Validation Gate is
 * conjunctive, so every ruling
 * above carries both a description and its reason, and each of the four labels is written in one
 * spelling throughout, because a second spelling of one label would read as a second category. The
 * written convention these blocks follow is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path
 * and never restated. Where migrated behaviour departs from the baseline deliberately, as the trailing
 * boundary that every walk here publishes departs, the departure is ruled in this package's descriptor
 * and registered in {@code docs/architecture/cobol-to-service-traceability.md}; neither document is
 * reproduced here.</p>
 */
@Repository
public interface StateZipPrefixRepository extends JpaRepository<UsStateZipPrefix, String> {

    /**
     * Reads the first page of admitted combinations in key order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes, so that a
     *     further-page flag can be established from the surplus row rather than from a count
     * @return the bounded rows in ascending key order, empty when the table holds no row
     */
    List<UsStateZipPrefix> findAllByOrderByStateZipCdAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in key order.
     *
     * @param lastKey the four-character value the previous page ended at; rows equal to it are
     *     excluded, so a page cannot repeat a row the caller has already received
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending key order, empty when the position names the last row
     */
    List<UsStateZipPrefix> findByStateZipCdGreaterThanOrderByStateZipCdAsc(
            String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending key order.
     *
     * <p>Assumptions: descending order is what makes this a page rather than a scan. The rows wanted
     * are the ones nearest the position, and the same bound applied to an ascending walk would return
     * the rows furthest from it instead. The caller restores ascending order before publishing, so the
     * surplus row of a descending walk carries the lowest key and arrives first once reversed.</p>
     *
     * @param firstKey the four-character value the current page begins at; rows equal to it are
     *     excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending key order, empty when the position names the first row
     */
    List<UsStateZipPrefix> findByStateZipCdLessThanOrderByStateZipCdDesc(
            String firstKey, Limit limit);

    /**
     * Reads one admitted combination by its exact four-character value.
     *
     * @param stateZipCd the two-character state code followed by the two leading postal digits,
     *     already assembled as a single value of four characters
     * @return the row, or empty when the pair is not among the seeded combinations, which is the
     *     condition the published contract answers as a not-found refusal
     */
    Optional<UsStateZipPrefix> findByStateZipCd(String stateZipCd);
}
