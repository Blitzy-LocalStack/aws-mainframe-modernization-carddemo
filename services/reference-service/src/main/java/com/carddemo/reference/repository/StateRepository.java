package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_states}, the seeded state allow-list.
 *
 * <p>Purpose: the migrated form of a membership test over the 56 literals the baseline's condition name
 * carries, together with the bounded walks the published browse needs.</p>
 *
 * <p>Assumptions: the whole datum is the code, so there is nothing to filter on and no filtered walk is
 * offered. A read that misses is the migrated form of a state code failing validation, and the caller
 * that cares about it is another service's address check reached over this service's HTTP contract.</p>
 *
 * <p>⚠️ Assumptions: <b>the domain is 56 codes and must never be narrowed to 50.</b>
 * {@code app/cpy/CSLKPCDY.cpy} declares {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at L1012 and
 * {@code 88 VALID-US-STATE-CODE} at L1013, whose literal list runs to L1069 and holds exactly 56 values:
 * the fifty states, the District of Columbia, and the five territory codes {@code AS}, {@code GU},
 * {@code MP}, {@code PR} and {@code VI}. {@code V2__seed_reference.sql} loads exactly those 56 rows.
 * Trimming the list to "the fifty states" is the plausible-looking mistake this note exists to refuse:
 * it would reject an address the baseline accepts, which is a functional-parity break rather than a
 * tidy-up, and the refusal would surface in {@code account-service}'s address validation with nothing
 * pointing back at the seed that caused it. If a fixture or a test ever reports 50, the seed or this
 * domain has drifted; {@code StateRepositoryIT} asserts the 56 and names the six non-state codes
 * individually, because a count alone cannot see a substitution.</p>
 *
 * <p>Assumptions: the identity type is {@code String} because the primary key is {@code CHAR(2)} --
 * {@code V1__reference.sql} declares {@code state_cd CHAR(2) NOT NULL} as the whole of
 * {@code pk_us_states} -- and a fixed two-character code is compared exactly. Alternatives Considered:
 * modelling the domain as a Java {@code enum}, which is the obvious alternative for a closed set of 56
 * two-letter codes and is rejected: an enum freezes the domain in compiled code and diverges from the
 * seeded table the moment a migration changes it, whereas reference data here changes by migration and
 * by nothing else. That is also why this interface adds no insert or update affordance beyond what
 * {@link JpaRepository} inherits, and declares no "register a new state" method.</p>
 *
 * <p>Assumptions: the union-scoped membership predicate is the INHERITED {@code existsById}, named here
 * rather than re-declared as an {@code existsByStateCode} that would derive a second query for the same
 * question. The keyed finder below is declared because it returns the row a published item route serves,
 * which is a different answer from a verdict.</p>
 *
 * <p>Assumptions: the three walks below take their window BY KEY. Each is bounded by a
 * {@link org.springframework.data.domain.Limit} and positioned by a strict comparison against the
 * key column, so a caller resumes from a row rather than from an ordinal. No ordinal window, no page
 * number, no sort argument and no row total for windowing appears anywhere in this interface, and the
 * reason is behavioural rather than stylistic: a window taken by ordinal skips and repeats rows under
 * concurrent inserts, so two callers paging the same data can each miss a row that the other sees
 * twice. The shape used here is the normative one {@code TransactionTypeRepository} sets for this
 * package. Each walk states its ordering in its own name rather than leaving it to the engine, so the
 * boundaries a caller carries are boundaries in a defined order.</p>
 *
 * <p>Refactoring Rationale: this interface was authored as {@code UsStateRepository} and is now
 * {@code StateRepository}, which is the name the checkpoint contract for this package assigns to the
 * interface over {@code UsState}. It is a RENAME rather than a second interface: the package charter
 * withdrew a duplicate {@code StateRepository} that had existed ALONGSIDE the prefixed one and
 * duplicated its whole query surface, and that objection was to the duplication rather than to the
 * name, so one interface now carries the assigned name and no query is derived twice. The entity keeps
 * its {@code UsState} name and the table keeps {@code reference.us_states}; only the interface moved.</p>
 *
 * <p>Assumptions: no join or navigation to {@code UsStateZipPrefix} is declared here. That table's key
 * is a single four-character token compared as a unit and deliberately not split into a state
 * component, which {@code UsStateZipPrefixRepository} records; reaching across from here would reverse
 * that decision from the wrong side of it.</p>
 */
@Repository
public interface StateRepository extends JpaRepository<UsState, String> {

    /**
     * Reads the first page of state codes in code order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes
     * @return the bounded rows in ascending code order
     */
    List<UsState> findAllByOrderByStateCodeAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in code order.
     *
     * @param lastKey the code the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending code order
     */
    List<UsState> findByStateCodeGreaterThanOrderByStateCodeAsc(String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending code order.
     *
     * @param firstKey the code the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending code order; the caller reverses them
     */
    List<UsState> findByStateCodeLessThanOrderByStateCodeDesc(String firstKey, Limit limit);

    /**
     * Reads one state by its exact code.
     *
     * @param stateCode the two-character code to read
     * @return the row, or empty when the code is not in the seeded allow-list
     */
    Optional<UsState> findByStateCode(String stateCode);
}
