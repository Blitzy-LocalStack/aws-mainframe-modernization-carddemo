package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_states}, the seeded allow-list a state code is validated against.
 *
 * <p>Purpose: the migrated form of one COBOL condition name. {@code app/cpy/CSLKPCDY.cpy} line 1012
 * declares {@code US-STATE-CODE-TO-EDIT PIC X(2)} and line 1013 carries
 * {@code 88 VALID-US-STATE-CODE} over it with 56 literals, one to a line through line 1069, so the
 * only question the baseline asks of a state code is whether it appears in that list. The inherited
 * {@code existsById} answers exactly that and is the whole validation surface here, and the inherited
 * {@code findById} reads the row behind it. The one method declared below adds what neither inherited
 * operation offers: a deterministically ordered read of the complete list.</p>
 *
 * <p>Assumptions: <b>the domain is 56 codes and is never narrowed to the fifty states.</b> The
 * literals at lines 1014 to 1069 are the fifty states, the district code {@code DC}, and the five
 * territory codes {@code AS}, {@code GU}, {@code MP}, {@code PR} and {@code VI}. Dropping those six
 * would reject addresses the baseline accepts, which is a break in functional parity rather than a
 * simplification, and it is recorded here because "there are fifty states" is plausible enough that a
 * reader may supply it unprompted. The count is also the first invariant to check when a state lookup
 * misbehaves: this module's {@code V2__seed_reference.sql} inserts 56 rows and closes with
 * {@code ON CONFLICT (state_cd) DO NOTHING}, so re-applying it cannot duplicate a row, and a row count
 * of 50 anywhere means the seed or the domain has drifted rather than that a test is wrong.</p>
 *
 * <p>Assumptions: the identity is the two-character code itself, so the inherited keyed operations
 * take a {@code String}. The column is {@code CHAR(2)}, which disregards trailing blanks on either
 * side of a comparison, so a code that reached the caller from a fixed-width source as {@code 'AL '}
 * still matches its row. The authoritative column contract is this module's
 * {@code V1__reference.sql}, which is deferred to rather than restated.</p>
 *
 * <p>Alternatives Considered: <b>modelling the 56 codes as a Java {@code enum}</b> and testing
 * membership in memory. Rejected because it would compile a seeded domain into the application: on the
 * day a migration adds or removes a code the enum and the table disagree, and the disagreement is
 * silent in both directions -- a code the table now holds fails validation, and a code it no longer
 * holds still passes. It also buys no exactness, because equality between a {@code String} and a
 * {@code CHAR(2)} column is already exact. Declaring {@code existsByStateCode} or
 * {@code findByStateCode} was rejected for a narrower reason: {@code state_cd} is the identity, so
 * each would be a second name for a query an inherited operation already performs.</p>
 *
 * <p>Alternatives Considered: offering a bounded, key-positioned walk here, as the browse-shaped
 * interfaces in this package do. Rejected because no screen browses this data, and the paged contract
 * over this table is already declared beside this interface on {@code UsStateRepository}, whose three
 * bounded walks feed the published list endpoint; a second set here would be one query carrying two
 * declarations. This interface therefore takes no bound, no caller-supplied ordering, no
 * ordinal-positioned window and no total, which is what {@code package-info.java} in this package
 * rules for every type in it -- ordinal positioning is rejected there on correctness grounds, because a
 * window counted from the start of the table skips and repeats rows when another caller inserts or
 * deletes one in between. For the same reason no navigation to the state-and-postal-prefix table is
 * offered from here: that table's key is the four characters compared as a unit and deliberately never
 * split into a state component, as {@code UsStateZipPrefixRepository} records.</p>
 *
 * <p>Refactoring Rationale: the baseline keeps this allow-list in working storage, so the 56 literals
 * are compiled into every program that copies the book and changing the list means rebuilding each of
 * them. Reading it from a seeded table through this interface leaves the list with a single
 * definition, and changing it becomes a migration. The consequence for this interface is that the set
 * is closed at run time: no method is declared for registering a code, and the write operations
 * inherited from the framework interface are there so that a migration-equivalent load can run, not so
 * that a caller can extend the domain.</p>
 *
 * <p>Trade-offs: reading the whole table in one call is accepted here and would not be accepted
 * elsewhere. The arity is settled by a migration at 56 rows and no request path inserts into this
 * table, so the result set cannot grow, whereas a bounded walk would oblige every caller to carry a
 * position for a list that fits in one response. What no type can enforce is the write restriction: the
 * framework interface inherits save and delete operations, so keeping this reference data
 * migration-owned rests on review together with the published contract, which reserves every mutating
 * method for the administrator group and admits a read only to a token carrying one of the two
 * published groups.</p>
 */
@Repository
public interface StateRepository extends JpaRepository<UsState, String> {

    /**
     * Reads every seeded state code in ascending code order.
     *
     * <p>Assumptions: the ordering is declared rather than left to the database, because a query that
     * declares none may return rows in any order and two identical requests would then differ.
     * Ascending code order is used because the code is the only column and the primary key, so it is
     * the one total order this table can express and no tie-break column is needed. It deliberately
     * does not reproduce the copybook's own sequence, which opens {@code AL}, {@code AK}, {@code AZ},
     * {@code AR} -- state name order rather than code order. Nothing is lost by that: the baseline
     * only ever tests membership of the list and never presents it, so its sequence carries no
     * behaviour to preserve.</p>
     *
     * @return every seeded row, ordered ascending by {@code state_cd}; empty only where the seed
     *     migration has not been applied
     */
    List<UsState> findAllByOrderByStateCodeAsc();
}
