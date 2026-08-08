package com.carddemo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.service.CognitoIdentityService;
import com.carddemo.auth.service.UserService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Asserts each refusal renders the sentence its own operation's contract declares.
 *
 * <p>Purpose: three unauthenticated operations of the sign-on adapter refuse at 401, and a handler is
 * selected by exception TYPE rather than by the operation that raised it. The defect this class exists to
 * prevent is the challenge and renewal exchanges rendering the CREDENTIAL sentence -- telling a caller its
 * password was wrong when it submitted none, and inviting it to reset a credential that works.
 *
 * <p>Assumptions: the adopted service raises two distinct types for that reason, and the distinction is
 * what these cases pin. A refused identifier-and-password pair reaches the handler as a plain
 * {@code BadCredentialsException} and renders the credential sentence; a refused session or refresh token
 * reaches it as {@code CognitoIdentityService.SessionRefusedException} -- a subtype, so the framework
 * selects the more specific handler -- and renders the sign-on-again sentence. Both sentences are asserted,
 * and so is their being different, because sharing one is the cheaper implementation and the one that
 * carries the defect.
 *
 * <p>Refactoring Rationale: these cases were first written against a single handler that selected its
 * sentence from a {@code Reason} enum carried on one exception type. The type-based split is what the
 * service actually raises, and it holds the same property with one fewer moving part: the enum could be
 * given a fourth member without any handler being taught to render it, whereas a new exception type cannot
 * reach a handler at all without one being declared for it.
 *
 * <p>Assumptions: the handlers are invoked directly rather than through a request, because what is under
 * test is the mapping from an outcome to a status and a sentence. Reachability is the filter chain's concern
 * and is asserted by the security tests beside this class.
 *
 * <p>Assumptions: the three identifier cases this class once carried -- an over-wide value, a blank value
 * and a lower-case value -- are NOT here. They asserted that the adapter itself refused and folded; the
 * adopted adapter declares {@code @NotBlank} and {@code @Size(max = 8)} on the path variable, which the
 * framework enforces before the method body runs and which a direct call therefore cannot exercise, and it
 * delegates the fold to the service. Both properties are asserted where they live, by
 * {@code com.carddemo.auth.service.UserServiceTest}'s blank-identifier and folding cases, and the declared
 * constraints are asserted below so the contract's stated width cannot be dropped unnoticed.
 */
class RefusalRenderingTest {

    /** A fixed instant so a body can be compared without asserting around a clock. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    /** The sign-on adapter under test. */
    private AuthController authController;

    /** The roster adapter under test. */
    private UserController userController;

    /** The roster service, mocked so a refusal can be raised without a store. */
    private UserService userService;

    /** The request the handlers read a path from. */
    private HttpServletRequest request;

    /**
     * Builds both adapters over mocked collaborators and a fixed clock.
     */
    @BeforeEach
    void setUp() {
        this.authController =
                new AuthController(mock(CognitoIdentityService.class), CLOCK);
        this.userService = mock(UserService.class);
        this.userController =
                new UserController(this.userService, CLOCK);
        this.request = mock(HttpServletRequest.class);
        when(this.request.getRequestURI()).thenReturn("/api/v1/auth/signon");
    }

    /**
     * Supplies each refused value with the sentence the operation that raises it renders.
     *
     * @return one case per refused value: the credential pair, and the two token exchanges that share the
     *     sign-on-again sentence; never {@code null}
     */
    private static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("a refused identifier and password pair",
                        new BadCredentialsException("diagnostic for the log"),
                        AuthController.MESSAGE_CREDENTIAL_REFUSED),
                Arguments.of("a refused sign-on challenge session",
                        sessionRefusal(),
                        CognitoIdentityService.MESSAGE_SESSION_REFUSED),
                Arguments.of("a refused refresh token",
                        sessionRefusal(),
                        CognitoIdentityService.MESSAGE_SESSION_REFUSED));
    }

    /**
     * Asserts each refused value renders the sentence its own operation declares, at 401.
     *
     * @param description the refused value the case is about, used only to name the case
     * @param refusal the exception the service raises for it
     * @param expected the sentence the operation that raises it renders
     */
    @ParameterizedTest(name = "{0} renders \"{2}\"")
    @MethodSource("refusals")
    @DisplayName("each refused value renders the sentence its own operation declares")
    void eachRefusedValueRendersItsOwnSentence(String description, BadCredentialsException refusal,
            String expected) {

        ResponseEntity<ApiError> answered = answerFor(refusal);

        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(answered.getBody()).isNotNull();
        assertThat(answered.getBody().message()).isEqualTo(expected);
    }

    /**
     * Asserts a refused token exchange never renders the credential sentence.
     *
     * <p>Assumptions: this is the finding, stated on its own rather than left to follow from the cases
     * above. A caller that submitted no password must not be told its password was wrong, because the
     * remedy it would infer -- reset the credential -- is the wrong one and touches a value that works.
     * Neither sentence discloses WHY the value was refused, so telling the two apart reveals only which
     * value the caller itself submitted.
     */
    @Test
    @DisplayName("a refused token exchange never renders the credential sentence")
    void aRefusedTokenExchangeNeverRendersTheCredentialSentence() {
        assertThat(CognitoIdentityService.MESSAGE_SESSION_REFUSED)
                .isNotEqualTo(AuthController.MESSAGE_CREDENTIAL_REFUSED);

        ResponseEntity<ApiError> answered = answerFor(sessionRefusal());

        assertThat(answered.getBody()).isNotNull();
        assertThat(answered.getBody().message())
                .isNotEqualTo(AuthController.MESSAGE_CREDENTIAL_REFUSED)
                .isEqualTo(CognitoIdentityService.MESSAGE_SESSION_REFUSED);
    }

    /**
     * Asserts the declared width and non-blank constraints stay on the identifier path variable.
     *
     * <p>Assumptions: the constraints are read off the method rather than exercised through a call, because
     * the framework applies them before the body runs and a direct call cannot reach them. What can be lost
     * is the DECLARATION, and losing it would silently admit a nine-character identifier that the column
     * cannot store and that {@code auth-api.yaml} declares invalid, so the declaration is what is pinned.
     *
     * @throws ReflectiveOperationException if the handler method cannot be resolved, which a rename would
     *     cause and which should fail this test rather than skip it
     */
    @Test
    @DisplayName("the identifier path variable keeps its declared non-blank and eight-character bounds")
    void theIdentifierPathVariableKeepsItsDeclaredBounds() throws ReflectiveOperationException {
        var parameter = UserController.class.getDeclaredMethod("getUser", String.class)
                .getParameters()[0];

        assertThat(parameter.getAnnotation(jakarta.validation.constraints.NotBlank.class))
                .as("the contract declares minLength 1 for userId")
                .isNotNull();
        assertThat(parameter.getAnnotation(jakarta.validation.constraints.Size.class))
                .as("the contract declares maxLength 8 for userId")
                .isNotNull()
                .extracting(jakarta.validation.constraints.Size::max)
                .isEqualTo(8);
    }

    /**
     * Builds the refusal the service raises for a session or refresh-token exchange.
     *
     * <p>Assumptions: reflection is used because the refusal's constructor is package-private so that only
     * its owning service may raise it, and this class sits in the adapter package. Relaxing that visibility
     * for a test would remove it for production too.
     *
     * @return the refusal to hand to a handler; never {@code null}
     * @throws IllegalStateException if the constructor cannot be resolved, which a rename would cause and
     *     which fails the cases using it rather than letting them pass against a substitute
     */
    private static CognitoIdentityService.SessionRefusedException sessionRefusal() {
        try {
            var constructor = CognitoIdentityService.SessionRefusedException.class
                    .getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            return constructor.newInstance("diagnostic for the log");
        } catch (ReflectiveOperationException unresolvable) {
            throw new IllegalStateException(
                    "the session refusal's constructor could not be resolved", unresolvable);
        }
    }

    /**
     * Routes a refusal to the handler the framework would select for its type.
     *
     * <p>Assumptions: the selection is made here by type rather than by asking Spring, because the property
     * under test is the sentence each handler renders and not the framework's own most-specific-handler
     * rule. That rule is what makes the routing below correct, and it is the framework's to guarantee.
     *
     * @param refusal the refusal to render; must not be {@code null}
     * @return the answered problem body; never {@code null}
     */
    private ResponseEntity<ApiError> answerFor(BadCredentialsException refusal) {
        return refusal instanceof CognitoIdentityService.SessionRefusedException session
                ? this.authController.onRefusedSession(session, this.request)
                : this.authController.onRefusedCredential(refusal, this.request);
    }

    /**
     * Asserts a refusal naming no reason falls back to the most restrictive sentence.
     *
     * <p>Assumptions: the fallback is the credential sentence because it is the least informative of the
     * three, so an unforeseen refusal discloses least rather than most.
     */
    @Test
    @DisplayName("a refusal naming no reason falls back to the credential sentence")
    void aRefusalNamingNoReasonFallsBackToTheCredentialSentence() {

        ResponseEntity<ApiError> answered = this.authController.onRefusedCredential(
                new BadCredentialsException("raised by something that names no reason"), this.request);

        assertThat(answered.getBody()).isNotNull();
        assertThat(answered.getBody().message()).isEqualTo("Wrong Password. Try again ...");
    }

    /**
     * Asserts no refusal body carries the exception's own diagnostic.
     *
     * <p>Assumptions: the diagnostic exists for the log and the contract forbids it reaching a caller, so a
     * body carrying it would be a disclosure the document does not describe. The assertion is made against
     * a deliberately recognisable diagnostic so that a match could not be coincidental.
     */
    @Test
    @DisplayName("no refusal body carries the diagnostic the exception was raised with")
    void noRefusalBodyCarriesTheExceptionsDiagnostic() {

        String diagnostic = "provider-fault-NotAuthorizedException-detail";

        ResponseEntity<ApiError> answered = this.authController.onRefusedCredential(
                new BadCredentialsException(diagnostic), this.request);

        assertThat(answered.getBody()).isNotNull();
        assertThat(answered.getBody().message()).doesNotContain(diagnostic);
    }

    /**
     * Asserts a taken identifier renders the reference's already-exists sentence at 409.
     *
     * <p>Assumptions: the sentence is asserted byte for byte, singular verb and absent space before the
     * ellipsis included, because the reference writes it that way and transformation rule T8 carries
     * user-visible strings across verbatim. Correcting the grammar would break this assertion, which is the
     * intent.
     *
     * @throws ReflectiveOperationException if the refusal's package-private constructor cannot be
     *     resolved, which a rename would cause and which should fail this test rather than skip it
     */
    @Test
    @DisplayName("a taken identifier renders the reference's already-exists sentence verbatim at 409")
    void aTakenIdentifierRendersTheReferenceSentence() throws ReflectiveOperationException {

        when(this.request.getRequestURI()).thenReturn("/api/v1/auth/users");

        ResponseEntity<ApiError> answered = this.userController.onDuplicateUser(
                duplicateUser(), this.request);

        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answered.getBody()).isNotNull();
        assertThat(answered.getBody().message()).isEqualTo("User ID already exist...");
        assertThat(answered.getBody().fieldErrors())
                .as("nothing about the identifier needs correcting; it is well formed and simply taken")
                .isEmpty();
    }

    /**
     * Asserts a deletion with no confirmation deletes nothing.
     *
     * <p>Assumptions: that the service is never reached is the assertion that matters. A confirmation
     * checked after the delete would report the fault while the row was already gone.
     */
    @Test
    @DisplayName("a deletion with no confirmation is refused and the service is never reached")
    void aDeletionWithoutConfirmationDeletesNothing() {

        assertThatThrownBy(() -> this.userController.deleteUser("ADMIN001", null))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Confirm the deletion to delete this user ...");

        verifyNoInteractions(this.userService);
    }

    /**
     * Asserts a deletion confirmed false is refused exactly as an absent confirmation is.
     */
    @Test
    @DisplayName("a deletion confirmed false is refused exactly as an absent confirmation is")
    void aDeletionConfirmedFalseIsRefused() {

        assertThatThrownBy(() -> this.userController.deleteUser("ADMIN001", Boolean.FALSE))
                .isInstanceOf(ClientInputException.class);

        verifyNoInteractions(this.userService);
    }




    /**
     * Builds the conflict the user adapter renders, without widening the refusal's own visibility.
     *
     * <p>Assumptions: {@link UserService.DuplicateUserException} keeps a package-private constructor so
     * only its owning service can raise it, and this class sits in the adapter package. Reflection is
     * used rather than relaxing that visibility, because the visibility is the property under test
     * elsewhere and widening it for a test would remove it for production too.</p>
     *
     * @return the refusal to hand to the adapter; never {@code null}
     * @throws ReflectiveOperationException if the constructor cannot be resolved, which a rename would
     *     cause and which should surface here rather than as a silent skip
     */
    private static UserService.DuplicateUserException duplicateUser() throws ReflectiveOperationException {
        var constructor = UserService.DuplicateUserException.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UserService.MESSAGE_USER_ID_EXISTS);
    }
}
