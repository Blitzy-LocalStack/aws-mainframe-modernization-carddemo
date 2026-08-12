package com.carddemo.auth.service;

import java.util.Objects;
import java.util.UUID;

/**
 * The two things provisioning a pool account produces: the subject the row is bound to, and the
 * one-time credential its owner signs on with the first time.
 *
 * <p><b>Purpose.</b> The subject and the credential are produced by the same provider call and are
 * needed by two different callers -- the subject by the row write, the credential by the administrator
 * who has to hand it over -- so returning one of them and dropping the other is what made a
 * runtime-created user unusable. This record carries both out of
 * {@link CognitoUserProvisioningService#provision(String, String, String, String)} so that neither can
 * be produced without the other.</p>
 *
 * <p>⚠️ Refactoring Rationale: that method used to return a bare {@code UUID}. The credential was not
 * merely undelivered -- it was never CREATED: the account was made with delivery suppressed and no
 * temporary password, so the provider minted one internally and sent it nowhere, and nothing in the
 * reactor could ever learn it. The pool declares no email or phone attribute, so no reset message can
 * be delivered either, and the administrative reset that establishes the SEED users runs in Terraform
 * at apply time and cannot be reached for an account created afterwards. A user created through
 * {@code POST /api/v1/auth/users} therefore existed, held the right group, had a row bound to its
 * subject -- and could never sign on. Every route was closed to it and no operation existed to open
 * one.</p>
 *
 * <p>Refactoring Rationale: this pair carries the credential's LOCATOR, never the credential. Two
 * things had to be true at once. A user created at runtime has to be able to sign on -- the pool
 * declares no email or phone attribute, so no reset message can be delivered, and the administrative
 * reset that establishes the SEED identities runs in Terraform at apply time and cannot be reached for
 * an account created afterwards -- and a live credential must not enter a response body, where it
 * would be copied into every proxy log and browser history between the service and the operator. The
 * credential is therefore generated inside the provisioning boundary and published to a per-user
 * managed-secret entry encrypted with the customer-managed key, and what travels back is the derived
 * NAME of that entry.</p>
 *
 * <p>Alternatives Considered: returning the generated value itself, on the argument that it is
 * single-use because the account stands in its force-change state. Rejected: single-use bounds the
 * damage, it does not prevent the disclosure, and the AAP forbids a credential reaching a place with
 * no audit trail. Alternatives Considered: returning nothing at all and leaving the name to be
 * recomputed. Rejected: the derivation is a digest of the identifier, so an operator would have to be
 * told the rule rather than the answer, and a locator is not a secret.</p>
 *
 * <p>Trade-offs: the operator performing the create holds a browser session, while reading the entry
 * needs {@code secretsmanager:GetSecretValue} and the key's grant -- which the task role holds and a
 * browser session does not. Onboarding therefore ends with a privileged read, and that is the accepted
 * cost: it is one audited step in a store that already has rotation and an audit trail, against a
 * credential in a log that has neither.</p>
 *
 * <p>Assumptions: the credential is NEVER persisted by this service. {@code auth.users} declares no
 * password column -- deliberately, because {@code app/cpy/CSUSR01Y.cpy} L21 stored
 * {@code SEC-USR-PWD PIC X(08)} in the clear and undoing that is the point of moving identity to a
 * managed provider -- and no code path writes the value anywhere except the managed entry.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own accessor.</p>
 *
 * @param subject the subject the provider minted, which is the value {@code auth.users.cognito_sub}
 *     stores and the only link between a presented token and the row; never {@code null}
 * @param credentialSecretName the name of the managed-secret entry holding the account's first
 *     credential, derived from the identifier so it is recomputable; never {@code null}, never blank,
 *     and never the credential itself
 */
public record ProvisionedIdentity(UUID subject, String credentialSecretName) {

    /**
     * Refuses an instance that carries either half without the other.
     *
     * <p>Assumptions: both components are required, because a caller receiving one of them and
     * {@code null} for the other has been given a half-provisioned identity that reads as a whole one.
     * A missing subject cannot be written to a {@code NOT NULL} column and a missing credential leaves
     * a user who cannot sign on -- which is precisely the defect this record was introduced to make
     * unrepresentable, so it is refused here rather than discovered downstream.</p>
     *
     * @throws NullPointerException if either component is {@code null}
     * @throws IllegalArgumentException if the credential is blank
     */
    public ProvisionedIdentity {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credentialSecretName, "credentialSecretName");
        if (credentialSecretName.isBlank()) {
            throw new IllegalArgumentException(
                    "credentialSecretName must not be blank; a blank locator names no entry");
        }
    }

    /**
     * Renders the identity for a diagnostic line, disclosing NEITHER component.
     *
     * <p>⚠️ Assumptions: this override is the point of the class carrying one at all. A record's
     * generated {@code toString} renders every component, so a single {@code log.debug("provisioned
     * {}", identity)} -- or any framework that renders an argument on a failure path -- would write the
     * credential into the log store, where it would outlive the one-time handover by the whole log
     * retention period. The subject is withheld for the reason the provisioning service's own log lines
     * withhold it: it is the value a presented token is matched on, so a log store holding it holds the
     * linkage between a person and their token claims.</p>
     *
     * <p>Trade-offs: what is given up is that this value is useless in a diagnostic. That is accepted:
     * the identifier the provisioning concerned is already on every line about the request, and it is
     * the only part of this that a diagnostic legitimately needs.</p>
     *
     * @return a fixed rendering naming the type and stating that both components are withheld; never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "ProvisionedIdentity[subject=<withheld>, credentialSecretName=<withheld>]";
    }
}
