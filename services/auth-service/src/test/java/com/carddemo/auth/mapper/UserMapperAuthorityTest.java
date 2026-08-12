package com.carddemo.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that applying an update mutates a row's two descriptive values and can never move the reference
 * type that names its authority.
 *
 * <h2>Purpose</h2>
 *
 * <p>The review finding this class answers is that a user-type change reached the database and reached no
 * Cognito group. Authority in this migration is derived from the signed {@code cognito:groups} claim by
 * {@code com.carddemo.common.security.JwtRoleConverter}, so {@code auth.users.user_type} names an authority
 * without conferring one, and a column moved on its own records a grant nobody made.</p>
 *
 * <p>Refactoring Rationale: this class first held that property by asserting the mapper REFUSED a submitted
 * type change. The refusal is withdrawn, because {@code userType} is a REQUIRED property of
 * {@code UpdateUserRequest} and {@code PUT /api/v1/auth/users/{userId}} is the only route the contract
 * publishes for changing it -- so refusing it in the mapper turned a published capability into a permanent
 * server error. What the refusal was protecting is now asserted directly instead: the column moves, and the
 * only two methods in the service that may move it are the two that move the provider membership with it.</p>
 *
 * <p>Assumptions: the load-bearing assertion is therefore the bytecode rule at the end of this class rather
 * than any single behavioural case. The behavioural cases prove the column and the two names land; the rule
 * proves no THIRD site can assign the column, which is the class of defect the finding named rather than the
 * one instance of it that was found.</p>
 *
 * <p>Trade-offs: nothing here substitutes the identity provider, because nothing here reaches it. The
 * provider-facing halves are covered by {@code com.carddemo.auth.service.IdentitySyncServiceTest} and by
 * {@code com.carddemo.auth.service.UserServiceTest}, whose changed-update case asserts the previously stored
 * type and the newly requested one are BOTH handed to the provisioner -- which is what makes the group move.
 * Keeping the three separate is what lets this class fail for exactly one reason.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class UserMapperAuthorityTest {

    /** The identifier under test, at the eight characters {@code SEC-USR-ID PIC X(08)} declares. */
    private static final String USER_ID = "TESTUSR1";

    /** The subject the row is bound to, which no mapping in this class may change. */
    private static final UUID SUBJECT = UUID.fromString("99999999-8888-4777-8666-555555555555");

    /** The reference type for an administrator, per {@code app/cpy/COCOM01Y.cpy} L27. */
    private static final String ADMIN = "A";

    /** The reference type for an ordinary user, per {@code app/cpy/COCOM01Y.cpy} L28. */
    private static final String ORDINARY = "U";

    /** The subject under test, which holds no state and so needs no per-test construction. */
    private final UserMapper mapper = new UserMapper();

    /**
     * Builds a row holding the given reference type and the original two names.
     *
     * @param userType the reference type the row is to hold
     * @return a row under test; never {@code null}
     */
    private static User userHolding(String userType) {
        return new User(USER_ID, "Grace", "Hopper", userType, SUBJECT);
    }

    /**
     * Verifies both names are applied when the submitted type matches the row's.
     *
     * <p>Assumptions: the subject and the identifier are asserted unchanged as well. Neither is on the request
     * and neither may be reachable through it, since the subject is the only link between a presented token and
     * a row and the identifier is what locates the row at all.</p>
     */
    @Test
    @DisplayName("both names are applied when the submitted type matches the row's")
    void bothNamesAreAppliedWhenTheTypeMatches() {
        User user = userHolding(ORDINARY);

        this.mapper.applyUpdate(new UpdateUserRequest("Ada", "Lovelace", ORDINARY), user);

        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
        assertThat(user.getUserType()).isEqualTo(ORDINARY);
        assertThat(user.getUserId()).isEqualTo(USER_ID);
        assertThat(user.getCognitoSub()).isEqualTo(SUBJECT);
    }

    /**
     * Verifies a promotion is applied to the column, which the published operation requires.
     *
     * <p>Assumptions: the assertion is that the column MOVED, not that it moved safely -- safety is the
     * caller's, and it is asserted where the caller is tested. Held here, the case pins the half of the
     * contract a reader of {@code auth-api.yaml} depends on: a request naming {@code "A"} for a row holding
     * {@code "U"} changes the row.</p>
     */
    @Test
    @DisplayName("a promotion is applied to the reference-type column")
    void aPromotionIsApplied() {
        User user = userHolding(ORDINARY);

        this.mapper.applyUpdate(new UpdateUserRequest("Ada", "Lovelace", ADMIN), user);

        assertThat(user.getUserType()).isEqualTo(ADMIN);
        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
    }

    /**
     * Verifies a demotion is applied, which is the direction whose false success mattered most.
     *
     * <p>Assumptions: this direction is asserted separately from the promotion because the consequences of
     * getting it wrong differ. A promotion that took no effect leaves a user who cannot do something an
     * operator believes they can; a demotion that took no effect leaves an administrator holding access an
     * operator believes was withdrawn. Only the second is a live privilege, so the case that it takes effect
     * is stated in its own right rather than assumed to follow from the promotion above.</p>
     */
    @Test
    @DisplayName("a demotion is applied, so a withdrawal an operator requested is a withdrawal recorded")
    void aDemotionIsApplied() {
        User user = userHolding(ADMIN);

        this.mapper.applyUpdate(new UpdateUserRequest("Ada", "Lovelace", ORDINARY), user);

        assertThat(user.getUserType()).isEqualTo(ORDINARY);
    }

    /**
     * Verifies neither the identifier nor the subject is reachable through an update.
     *
     * <p>Assumptions: these two are asserted on the CHANGING path rather than only on the matching one,
     * because a mapper that rebuilt the row instead of mutating it would be most likely to lose them exactly
     * here. The subject is the only link between a presented token and a row, and the identifier is what
     * locates the row at all, so either being writable through this body would be a far worse defect than the
     * one this class was opened for.</p>
     */
    @Test
    @DisplayName("neither the identifier nor the subject is reachable through an update")
    void neitherTheIdentifierNorTheSubjectIsReachable() {
        User user = userHolding(ADMIN);

        this.mapper.applyUpdate(new UpdateUserRequest("Ada", "Lovelace", ORDINARY), user);

        assertThat(user.getUserId()).isEqualTo(USER_ID);
        assertThat(user.getCognitoSub()).isEqualTo(SUBJECT);
    }

    /**
     * Verifies only the provider-coordinated mapper assigns the reference-type column.
     *
     * <p>Assumptions: the rule is stated as an allow-list of ONE rather than as a prohibition, and that one
     * is the only site at which a membership move is guaranteed to accompany the assignment.
     * {@code UserMapper} is reached only from {@code UserService#update}, which records the intended
     * membership move in the durable task ledger inside the same transaction that writes the row -- so a
     * rollback discards the intention with the row, and a commit leaves an intention the applier converges.
     * Any second site would have neither property.</p>
     *
     * <p>Refactoring Rationale: the allow-list held TWO names, the second being
     * {@code UserAuthorityService}, and that service has been withdrawn: it had no production caller, and
     * the compensation it registered ran after completion in the same process, so a process death between
     * the provider call and the commit left the two stores disagreeing with nothing recording it. The
     * ledger survives that death, which is why the list is now one name and not two.</p>
     *
     * <p>Alternatives Considered: relying on the behavioural assertions above alone. They cover every path a
     * caller can take today, but they would all still pass if a future edit assigned the column from a new
     * class -- a listener, a batch fixup, a second mapper. Asserting over compiled bytecode across the whole
     * service removes the class of defect rather than the instances currently reachable, which is what the
     * finding asks to be held.</p>
     *
     * <p>Alternatives Considered: reading the sources as text and asserting the setter's name appears only
     * twice. Rejected because it would pass on a call written through a variable of a supertype, would fail on
     * the name appearing in a comment or a message, and would depend on a source path resolving the same way
     * under every build. Bytecode carries the resolved call target, so it answers the question asked.</p>
     */
    @Test
    @DisplayName("only the mapper assigns the reference-type column")
    void onlyTheProviderCoordinatedMapperAssignsTheReferenceType() {
        JavaClasses service = new ClassFileImporter().importPackages("com.carddemo.auth");

        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .doNotHaveFullyQualifiedName("com.carddemo.auth.mapper.UserMapper")
                .should()
                .callMethod(User.class, "setUserType", String.class)
                .because("moving auth.users.user_type without moving the cognito:groups membership behind "
                        + "it records an authority nobody granted; the only site that moves both is "
                        + "com.carddemo.auth.mapper.UserMapper, whose caller records the membership move "
                        + "in the durable task ledger inside the same transaction as the row change");

        rule.check(service);
    }
}
