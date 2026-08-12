package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;

/**
 * The body of a successful create, being the stored row plus the NAME of the managed-secret entry
 * holding the one-time credential its owner signs on with the first time.
 *
 * <p><b>Purpose.</b> Creating a user produces two things a caller needs and one of them is not the
 * caller's to be handed. The row is readable again at any time through
 * {@code GET /api/v1/auth/users/{userId}}; the temporary credential is readable exactly once, from the
 * managed-secret entry this body names, by a principal holding the read grant. This type is the only
 * place the row and that locator appear together, and the credential itself appears in neither.</p>
 *
 * <p>⚠️ Refactoring Rationale: the create operation used to answer with {@link UserResponse}, the same
 * shape the read and update operations answer with. That was not merely a shared type -- it was the
 * reason a created user could not sign on. The provisioning path created the pool account with delivery
 * suppressed and no credential, so the provider minted one internally and sent it nowhere; there was
 * nothing to return and no field to return it in, and the account stood in its force-change state with a
 * credential no one on earth knew. Every route was closed to that user and no operation existed to open
 * one.</p>
 *
 * <p>Assumptions: this record REPEATS the five row properties rather than nesting a {@link UserResponse}
 * under a property of its own. The reason is a contract one: a nested shape would move every existing
 * field of the 201 body one level down, breaking every consumer of the create response over a change
 * that only ADDS information. Repeating them keeps the body a strict superset of the read body, so a
 * client that ignores the new property reads exactly what it read before -- which is what the browser
 * client's own type does, declaring the created shape as an extension of the read shape.</p>
 *
 * <p>⚠️ Refactoring Rationale: NO credential is carried in this body, and an earlier revision of this
 * type carried one. It is withdrawn because a credential in a response body is copied into every proxy
 * log and browser history between the service and the operator, and this migration forbids a credential
 * reaching a place with no audit trail. The generated value is published instead to a per-user
 * managed-secret entry encrypted with the customer-managed key
 * ({@code com.carddemo.auth.service.CognitoUserProvisioningService}), and this body names that entry.
 * Alternatives Considered: answering with the read body alone and leaving the name to be recomputed
 * from the identifier. Rejected because that hands an operator the derivation rule rather than the
 * answer, while the entry name is not itself a secret.</p>
 *
 * <p>Trade-offs: three properties of the credential keep the handover safe from the entry onwards, and
 * each is stated where it is implemented rather than only here: it is single-use, because the account's
 * force-change state permits nothing but its own replacement; it is never persisted, because
 * {@code auth.users} declares no password column -- deliberately, undoing
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} L21 -- and no path writes it; and it is
 * never logged. Reading the entry needs the secret-store read action and the key's grant, which the
 * task role holds and a browser session does not, so onboarding ends with one privileged read in a
 * store that already has rotation and an audit trail.</p>
 *
 * <p>Assumptions: the property is REQUIRED rather than optional. An absent locator would mean a
 * created account nobody can reach, and a client that had to handle both shapes could not tell that
 * state from a client-side omission. It is refused at construction below for the same reason.</p>
 *
 * <p><strong>Return value.</strong> Each component documents its own accessor.</p>
 *
 * @param userId the row's identifier and primary key, blank padding already removed
 * @param firstName the given name as stored
 * @param lastName the family name as stored
 * @param userType {@code "A"} or {@code "U"}, the whole domain at {@code app/cpy/COCOM01Y.cpy} L27-L28
 * @param cognitoSub the subject the provider minted, which the row is bound to
 * @param credentialSecretName the name of the managed-secret entry holding the one-time credential the
 *     account was created with; the value is collected from that entry and presented once at first
 *     sign-on, and never travels in this body
 */
public record CreatedUserResponse(
        @NotNull @Size(max = UserResponse.USER_ID_WIDTH) String userId,
        @NotNull @Size(max = UserResponse.NAME_WIDTH) String firstName,
        @NotNull @Size(max = UserResponse.NAME_WIDTH) String lastName,
        @NotNull @Pattern(regexp = UserResponse.USER_TYPE_DOMAIN) String userType,
        @NotNull UUID cognitoSub,
        @NotNull String credentialSecretName) {

    /**
     * Refuses a body that omits the credential locator or the row it belongs to.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if the locator is blank, because a blank name reaches no entry
     */
    public CreatedUserResponse {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(userType, "userType");
        Objects.requireNonNull(cognitoSub, "cognitoSub");
        Objects.requireNonNull(credentialSecretName, "credentialSecretName");
        if (credentialSecretName.isBlank()) {
            throw new IllegalArgumentException(
                    "credentialSecretName must not be blank; a blank locator names no entry");
        }
    }

    /**
     * Composes the created body from a projected row and the locator of its account's credential.
     *
     * <p>Assumptions: a factory taking the read projection rather than a second mapper method, because
     * the five row values are already produced correctly in exactly one place -- padding stripped,
     * names read through -- and a second projection of the same entity would be a second chance to get
     * that stripping wrong. What this adds to the projection is one value the projection cannot know.</p>
     *
     * @param user the stored row as the read operation would return it; must not be {@code null}
     * @param credentialSecretName the name of the entry holding the one-time credential; must not be
     *     {@code null} or blank
     * @return the create body; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the locator is blank
     */
    public static CreatedUserResponse of(UserResponse user, String credentialSecretName) {
        Objects.requireNonNull(user, "user");
        return new CreatedUserResponse(user.userId(), user.firstName(), user.lastName(),
                user.userType(), user.cognitoSub(), credentialSecretName);
    }

    /**
     * Projects the row half of this body, for a caller that wants the shape a read would return.
     *
     * @return the five row properties as the read operation publishes them; never {@code null}
     */
    public UserResponse user() {
        return new UserResponse(this.userId, this.firstName, this.lastName, this.userType,
                this.cognitoSub);
    }

    /**
     * Renders the body for a diagnostic line, disclosing neither the credential locator nor the subject.
     *
     * <p>⚠️ Assumptions: this override is why this record declares one at all. A record's generated
     * {@code toString} renders every component, so any line that rendered them -- a debug log, a
     * framework's failure diagnostic, an assertion message -- would write both withheld values into the
     * log store for the whole retention period. The locator is withheld even though it is not itself a
     * secret, because it names the one entry an attacker reading logs would want to request and the
     * cost of withholding it is nothing. The subject is
     * withheld for the reason the provisioning service withholds it from its own lines: it is the value a
     * presented token is matched on, so a log store holding it holds the linkage between a person and
     * their token claims.</p>
     *
     * <p>Trade-offs: the identifier, the names and the type ARE rendered, because none of them is
     * withheld by {@code docs/architecture/observability.md}'s rendering contract and a rendering that
     * named nothing would be useless in the diagnostic it exists for. The identifier is already on every
     * other line about the request.</p>
     *
     * @return a single-line rendering naming the row and stating that the credential locator and the
     *     subject are withheld; never {@code null}
     */
    @Override
    public String toString() {
        return "CreatedUserResponse[userId=" + this.userId
                + ", firstName=" + this.firstName
                + ", lastName=" + this.lastName
                + ", userType=" + this.userType
                + ", cognitoSub=<withheld>, credentialSecretName=<withheld>]";
    }
}
