package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The classification-scoped membership predicate over {@code reference.us_phone_area_codes}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface answers one question: is a three-character code a member of a NAMED
 * classification list. That is the question the baseline's account-maintenance path asks, and it is
 * narrower than membership of the allow-list as a whole. {@code app/cbl/COACTUPC.cbl} line 2298
 * tests {@code IF VALID-GENERAL-PURP-CODE}, which is the condition name declared at
 * {@code app/cpy/CSLKPCDY.cpy} line 521 over 410 literals. It does not test
 * {@code VALID-PHONE-AREA-CODE}, the union declared at line 30 over 490 literals, and it does not
 * test {@code VALID-EASY-RECOG-AREA-CODE}, declared at line 931 over 80. All three predicates read
 * the same three-character field, {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at line 24, and the
 * two sublists partition the union exactly: 410 plus 80 is 490. A predicate that tested the union
 * would therefore accept all 80 codes the baseline declines, so the classification belongs to the
 * question rather than to the shape of the answer.</p>
 *
 * <p>The union-scoped predicate is deliberately NOT declared here. It is {@code existsById},
 * inherited from {@link JpaRepository}, whose identity is the three-character code itself, and it
 * reports membership of all 490 seeded rows. Naming it in this documentation rather than
 * re-declaring it as {@code existsByAreaCode} keeps one query with one derivation: two spellings of
 * the same predicate would leave a reader working out which one the persistence provider had used
 * and whether the two agreed.</p>
 *
 * <h2>Why a second interface over one table</h2>
 *
 * <p>{@link UsPhoneAreaCodeRepository} retrieves rows: the bounded ordered walks the published
 * browse route serves, and a keyed finder that returns a row together with its classification. This
 * interface retrieves no row. The split is by question and not by convenience, and the two are not
 * interchangeable even though a caller could reach the same verdict either way -- a finder hands
 * back a row and leaves the classification comparison to whoever called it, whereas the predicate
 * below performs that comparison inside the query and returns only the verdict.</p>
 *
 * <p>Refactoring Rationale: the schema holds ONE table with a classification column rather than two
 * tables, one per sublist, and that is what makes a classification-scoped predicate necessary in the
 * first place. The three condition names at {@code app/cpy/CSLKPCDY.cpy} lines 30, 521 and 931 are
 * three predicates over a single declared field at line 24, so the code and its classification are
 * one fact. Splitting them across two tables would allow a code to appear in both or in neither, and
 * neither state has any counterpart in the copybook -- whereas one table with a
 * {@code code_class CHAR(1)} column constrained to the two letters the entity names makes both
 * states unrepresentable. The consequence is that a class-scoped question has to be expressed as a
 * predicate over a column, which is exactly what this interface declares.</p>
 *
 * <p>Refactoring Rationale: this interface was withdrawn from this package on the recorded ground
 * that {@code findByAreaCode} on the sibling interface "answers strictly more", and that ground
 * conflated returning more DATA with answering the same QUESTION. Returning the row and its
 * classification does let a caller reach the same verdict, but it relocates the comparison against
 * {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} out of the layer that owns the column and into
 * whichever caller happens to ask. The interface is reinstated for that reason and declares ONE
 * method rather than the six walks and the keyed finder it previously duplicated, so the earlier
 * objection -- two derivations of one query surface with no basis for choosing between them -- does
 * not apply to what is declared here.</p>
 *
 * <h2>What this interface does not do</h2>
 *
 * <p>Assumptions: no method here returns a collection, so no ordering is declared. Ordering matters
 * only where more than one row is returned, and a predicate returns a verdict; the browse contracts
 * and the ordering rulings that govern them belong to this package's charter and to
 * {@link UsPhoneAreaCodeRepository}, which is where every walk over this table is declared. Neither
 * a window nor a position parameter appears below for the same reason.</p>
 *
 * <p>Alternatives Considered: a single unscoped existence method that chose the general-purpose
 * classification internally, so a caller could ask "is this code acceptable" without naming a list.
 * Rejected. Which of the three copybook lists is being tested is a parity-relevant decision -- the
 * account-maintenance path tests one, and a lookup elsewhere could legitimately test another -- and
 * a method that resolved it silently would put that decision inside the data layer where no caller
 * could see it. The method below takes the classification as a parameter so the choice is made and
 * visible at the call site.</p>
 *
 * <p>Alternatives Considered: mapping the classification to a Java enumeration and taking that as
 * the parameter type, which would make an unrecognised value unrepresentable. Rejected here because
 * {@link UsPhoneAreaCode} stores the classification as the single character the schema stores and
 * publishes the two admissible values as constants; a repository parameter typed as an enumeration
 * would bind the stored letter to a constant name, which is the alternative that entity's own
 * documentation already records and declines. The parameter is therefore the same {@code String} the
 * column holds, and the two constants are the only values a caller should pass.</p>
 *
 * <p>Trade-offs: a class-scoped predicate obliges every caller to name the classification, which is
 * one more argument at every call site than an unscoped check would need. That cost is accepted
 * because the alternative hides a parity-relevant decision in the data layer: a caller reading an
 * unscoped call could not tell whether 410 codes or 490 were being accepted, and the difference is
 * 80 codes the baseline refuses.</p>
 *
 * <p>Trade-offs: within this module no class injects this interface today. The published item route
 * serves a row with its classification, and the account context's address validation classifies that
 * row on its own side after reading the route over HTTP -- so the sibling interface, not this one, is
 * what that path reaches. This interface is verified directly instead, by
 * {@code PhoneAreaCodeRepositoryIT}, which asserts against the seeded schema that the predicate holds
 * for a general-purpose code and does NOT hold for an easily-recognisable one. That is deliberately
 * stated rather than left implicit: an adapter test on the far side of an HTTP hop can assert what
 * that context does with a classification it was given, and only a test at this layer can assert
 * that the SQL predicate itself partitions the allow-list the way the baseline's condition names
 * do.</p>
 *
 * <p>Assumptions: the identity type is {@code String} and never an integer, because
 * {@code app/cpy/CSLKPCDY.cpy} line 24 declares the field {@code PIC XXX} and every literal on all
 * three lists is quoted and compared as characters. {@code V1__reference.sql} carries that through as
 * {@code CHAR(3)}, so a code such as {@code "012"} keeps its leading zero and an integer identity
 * would lose it.</p>
 *
 * <p>Assumptions: the method below carries a full Javadoc block naming its parameters and its return
 * value even though its body is derived rather than written. The project Explainability rule exempts
 * only trivial accessors at line 23, and its Validation Gate at line 43 states the docstring and the
 * decision-rationale requirements conjunctively, so a derived query method is documented in full
 * here rather than treated as self-describing.</p>
 */
@Repository
public interface PhoneAreaCodeRepository extends JpaRepository<UsPhoneAreaCode, String> {

    /**
     * Reports whether a code belongs to the named classification list, and to that list only.
     *
     * <p>Assumptions: both columns are fixed-width character columns -- {@code area_cd CHAR(3)} and
     * {@code code_class CHAR(1)} -- and PostgreSQL's {@code character(n)} comparison DISREGARDS
     * trailing blanks. A value whose digits differ from every seeded code therefore matches nothing
     * whether it is shorter or longer, but a value differing from a seeded code only by trailing
     * blanks DOES match: {@code "201 "} and {@code "201"} are one value at this column's type. That is
     * measured against the engine by {@code PhoneAreaCodeRepositoryIT} rather than assumed, and it is
     * recorded here because the consequence belongs to the caller: this predicate must not be relied
     * on to reject an untrimmed value, and a caller to which the distinction matters trims its own
     * input. It is not a parity divergence -- {@code WS-US-PHONE-AREA-CODE-TO-EDIT} is exactly three
     * bytes wide, so the baseline has no longer-value case to diverge from.</p>
     *
     * <p>Assumptions: the classification argument is one of the two values
     * {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} and
     * {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE}. Any other value returns {@code false}
     * for every code, because {@code V1__reference.sql} constrains the stored column to those two,
     * and the constants exist so that no call site spells the letter inline.</p>
     *
     * @param areaCode the three-character code to test, as {@code app/cpy/CSLKPCDY.cpy} line 24
     *     declares it; trailing blanks are not significant, per the note above
     * @param codeClass the classification list to test membership of, one of the two constants
     *     {@link UsPhoneAreaCode} publishes
     * @return {@code true} when a row exists carrying both that code and that classification;
     *     {@code false} when the code is absent from the 490-row allow-list altogether and equally
     *     when it is present under the other classification
     */
    boolean existsByAreaCodeAndCodeClass(String areaCode, String codeClass);
}
