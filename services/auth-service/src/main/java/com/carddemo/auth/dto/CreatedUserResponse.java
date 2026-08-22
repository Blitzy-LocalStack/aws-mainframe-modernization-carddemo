package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;

/**
 * The body of a successful create, being the stored row, the one-time credential its owner signs on
 * with the first time, and the NAME of the managed-secret entry that credential was archived to.
 *
 * <p><b>Purpose.</b> Creating a user produces three things a caller needs and they have three
 * different lifetimes. The row is readable again at any time through
 * {@code GET /api/v1/auth/users/{userId}}; the credential is readable in THIS body and nowhere else,
 * because nothing persists it in this service and no operation re-issues it; the entry name is what an
 * operator who lost this body recovers the credential from, with the store's own audit trail behind
 * it. This type is the only place all three appear together.</p>
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
 * <p>⚠️ Refactoring Rationale: the credential IS carried in this body, and an intermediate revision of
 * this type carried only the locator. That revision was authored to keep a credential out of a response
 * body, and it closed one exposure by creating a worse defect: reading the entry requires
 * {@code secretsmanager:GetSecretValue} and a grant on the customer-managed key, which the task role
 * holds and the administrator's browser session does not, so the one principal obliged to hand the
 * credential over was the one principal who could not read it. Every account created through
 * {@code POST /api/v1/auth/users} was therefore unusable, which is the same defect the locator was
 * introduced to fix, one layer further out.</p>
 *
 * <p>Alternatives Considered: granting the browser client the secret-store read action so it could
 * collect the value itself. Rejected as a strictly larger disclosure than one value in one response --
 * that grant outlives the handover, spans every entry the policy admits, and is exercisable by anything
 * holding the session. Alternatives Considered: carrying the credential and dropping the locator.
 * Rejected because a response is delivered once, so a closed tab or a dropped connection after the
 * commit would strand the account with no operational recovery and no audit trail.</p>
 *
 * <p>Trade-offs: the credential reaches a response body, and four properties bound that exposure --
 * each implemented where it belongs rather than only asserted here. It is single-use, because the
 * account's force-change state permits nothing but its own replacement. It is never persisted, because
 * {@code auth.users} declares no password column -- deliberately, undoing
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} L21 -- and no path writes it. It is
 * never logged, and {@link #toString()} below is what makes that true of a rendering nobody wrote
 * deliberately. And the response carrying it is marked {@code Cache-Control: no-store} by
 * {@code com.carddemo.auth.api.UserController}, so no intermediary or browser may retain it. What
 * remains is that a proxy configured to log bodies would capture it; what is bought is that onboarding
 * needs no privileged read at all.</p>
 *
 * <p>Assumptions: both properties are REQUIRED rather than optional. An absent credential would mean a
 * created account whose owner cannot sign on, an absent locator would mean one nobody can recover, and
 * a client that had to handle either shape could not tell that state from a client-side omission. Both
 * are refused at construction below for the same reason.</p>
 *
 * <p><strong>Return value.</strong> Each component documents its own accessor.</p>
 *
 * @param userId the row's identifier and primary key, blank padding already removed
 * @param firstName the given name as stored
 * @param lastName the family name as stored
 * @param userType {@code "A"} or {@code "U"}, the whole domain at {@code app/cpy/COCOM01Y.cpy} L27-L28
 * @param cognitoSub the subject the provider minted, which the row is bound to
 * @param credentialSecretName the name of the managed-secret entry the one-time credential was archived
 *     to, which an operator who lost this response collects it from
 * @param oneTimeCredential the credential the pool account was created with, live for exactly one
 *     sign-on and carried in this body only -- no column stores it, no operation re-issues it, and no
 *     diagnostic rendering discloses it
 */
public record CreatedUserResponse(
        @NotNull @Size(max = UserResponse.USER_ID_WIDTH) String userId,
        @NotNull @Size(max = UserResponse.NAME_WIDTH) String firstName,
        @NotNull @Size(max = UserResponse.NAME_WIDTH) String lastName,
        @NotNull @Pattern(regexp = UserResponse.USER_TYPE_DOMAIN) String userType,
        @NotNull UUID cognitoSub,
        @NotNull String credentialSecretName,
        @NotNull String oneTimeCredential) {

    /**
     * Refuses a body that omits the credential, its locator, or the row they belong to.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if the locator is blank, because a blank name reaches no entry,
     *     or if the credential is blank, because a blank credential signs nobody on
     */
    public CreatedUserResponse {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(userType, "userType");
        Objects.requireNonNull(cognitoSub, "cognitoSub");
        Objects.requireNonNull(credentialSecretName, "credentialSecretName");
        Objects.requireNonNull(oneTimeCredential, "oneTimeCredential");
        if (credentialSecretName.isBlank()) {
            throw new IllegalArgumentException(
                    "credentialSecretName must not be blank; a blank locator names no entry");
        }
        // WHY : Assumptions: the credential is checked for blankness only, and not for length or
        //       character classes. Those are the generator's properties and are asserted where the
        //       generator is configured; restating them here would put the pool's password policy in
        //       two places that could disagree, while a blank value is wrong under every policy and is
        //       the one shape that would silently produce an unusable account.
        if (oneTimeCredential.isBlank()) {
            throw new IllegalArgumentException(
                    "oneTimeCredential must not be blank; a blank credential signs nobody on");
        }
    }

    /**
     * Composes the created body from a projected row, its account's credential, and that credential's
     * locator.
     *
     * <p>Assumptions: a factory taking the read projection rather than a second mapper method, because
     * the five row values are already produced correctly in exactly one place -- padding stripped,
     * names read through -- and a second projection of the same entity would be a second chance to get
     * that stripping wrong. What this adds to the projection is two values the projection cannot know.</p>
     *
     * @param user the stored row as the read operation would return it; must not be {@code null}
     * @param credentialSecretName the name of the entry the one-time credential was archived to; must
     *     not be {@code null} or blank
     * @param oneTimeCredential the credential the account was created with, as the provisioning result
     *     returned it; must not be {@code null} or blank
     * @return the create body; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the locator or the credential is blank
     */
    public static CreatedUserResponse of(UserResponse user, String credentialSecretName,
            String oneTimeCredential) {
        Objects.requireNonNull(user, "user");
        return new CreatedUserResponse(user.userId(), user.firstName(), user.lastName(),
                user.userType(), user.cognitoSub(), credentialSecretName, oneTimeCredential);
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
     * Renders the body for a diagnostic line, disclosing neither the credential, nor its locator, nor
     * the subject.
     *
     * <p>⚠️ Assumptions: this override is why this record declares one at all. A record's generated
     * {@code toString} renders every component, so any line that rendered them -- a debug log, a
     * framework's failure diagnostic, an assertion message -- would write all three withheld values into
     * the log store for the whole retention period. The credential is the one that matters most: it is
     * live for the width of one handover, and a log store would hold it for the whole retention period,
     * which is the exposure the {@code no-store} response header and the absence of a password column are
     * both there to prevent. The locator is withheld even though it is not itself a
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
     * @return a single-line rendering naming the row and stating that the credential, its locator and the
     *     subject are withheld; never {@code null}
     */
    @Override
    public String toString() {
        return "CreatedUserResponse[userId=" + this.userId
                + ", firstName=" + this.firstName
                + ", lastName=" + this.lastName
                + ", userType=" + this.userType
                + ", cognitoSub=<withheld>, credentialSecretName=<withheld>,"
                + " oneTimeCredential=<withheld>]";
    }
}
