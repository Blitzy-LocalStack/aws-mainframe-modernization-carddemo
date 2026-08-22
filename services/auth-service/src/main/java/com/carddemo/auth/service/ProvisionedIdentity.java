package com.carddemo.auth.service;

import java.util.Objects;
import java.util.UUID;

/**
 * The three things provisioning a pool account produces: the subject the row is bound to, the one-time
 * credential its owner signs on with the first time, and the name of the entry that credential was
 * archived to.
 *
 * <p><b>Purpose.</b> All three are produced by the same provider call and are needed by three different
 * readers -- the subject by the row write, the credential by the administrator who has to hand it over
 * in the create response, the entry name by an operator recovering a handover that was lost -- so
 * returning one of them and dropping the others is what made a runtime-created user unusable. This
 * record carries all three out of
 * {@link CognitoUserProvisioningService#provision(String, String, String, String)} so that none can be
 * produced without the rest.</p>
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
 * <p>⚠️ Refactoring Rationale: this triple carries the credential's VALUE as well as its locator, and
 * an earlier revision carried the locator alone. That revision closed one half of the defect and left
 * the other half open, because the principal who needs the value is the administrator who performed
 * the create -- and that principal holds a browser session, which carries neither
 * {@code secretsmanager:GetSecretValue} nor a grant on the customer-managed key. The row existed, the
 * group was right, the entry was written, and the one person who had to hand a credential over could
 * not read it. Returning the value is what makes a runtime-created user reachable at all.</p>
 *
 * <p>Assumptions: BOTH are carried, and the second is not made redundant by the first. The value
 * travels exactly once, through the create response, and is unrecoverable afterwards -- nothing
 * persists it and no operation re-issues it -- so the entry is what an operator who lost the response
 * collects it from, and it is the audit trail the value itself has none of. The two answer different
 * questions and neither substitutes for the other.</p>
 *
 * <p>Alternatives Considered: keeping the locator alone and having the browser client read the entry.
 * Rejected because it would require granting a browser session the secret-store read action and a
 * grant on the key, which is a strictly larger disclosure than one value in one response: that grant
 * outlives the handover, covers every entry the policy admits, and is exercisable by anything holding
 * the session. Alternatives Considered: returning the value and NOT writing the entry. Rejected
 * because a response is seen once -- a closed tab, a refused render, a lost connection after the
 * commit -- and without the entry that account would be stranded with no operational recovery, which
 * is the defect this record exists to prevent.</p>
 *
 * <p>Trade-offs: the value reaches a response body, so it is exposed to whatever sits between this
 * service and the operator's browser. Three properties bound that exposure and each is enforced rather
 * than asserted: transport is TLS end to end, the create response is marked {@code no-store} so no
 * intermediary or browser may retain it, and the value buys exactly one sign-on because the account
 * stands in the provider's force-change state. What is given up is that a proxy configured to log
 * bodies would capture it; what is bought is that onboarding needs no privileged read at all.</p>
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
 * @param oneTimeCredential the generated value the pool account was created with, live for exactly one
 *     sign-on and unrecoverable from this service once the create response has been written; never
 *     {@code null}, never blank, never logged and never stored in this service's schema
 */
public record ProvisionedIdentity(
        UUID subject, String credentialSecretName, String oneTimeCredential) {

    /**
     * Refuses an instance that carries any component without the others.
     *
     * <p>Assumptions: all three components are required, because a caller receiving one of them and
     * {@code null} for another has been given a half-provisioned identity that reads as a whole one.
     * A missing subject cannot be written to a {@code NOT NULL} column, a missing locator names no
     * entry, and a missing credential leaves a user who cannot sign on -- which is precisely the
     * defect this record was introduced to make unrepresentable, so it is refused here rather than
     * discovered downstream.</p>
     *
     * <p>Assumptions: the credential is checked for blankness and for nothing else -- not its length,
     * not its character classes. Those are properties of the generator and are asserted where the
     * generator is configured; re-asserting them here would put the pool's password policy in two
     * places that could disagree, while a blank value is the one shape that is wrong under every
     * policy.</p>
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if the locator or the credential is blank
     */
    public ProvisionedIdentity {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credentialSecretName, "credentialSecretName");
        Objects.requireNonNull(oneTimeCredential, "oneTimeCredential");
        if (credentialSecretName.isBlank()) {
            throw new IllegalArgumentException(
                    "credentialSecretName must not be blank; a blank locator names no entry");
        }
        if (oneTimeCredential.isBlank()) {
            throw new IllegalArgumentException(
                    "oneTimeCredential must not be blank; a blank credential signs nobody on");
        }
    }

    /**
     * Renders the identity for a diagnostic line, disclosing NONE of its components.
     *
     * <p>⚠️ Assumptions: this override is the point of the class carrying one at all. A record's
     * generated {@code toString} renders every component, so a single {@code log.debug("provisioned
     * {}", identity)} -- or any framework that renders an argument on a failure path -- would write the
     * credential into the log store, where it would outlive the one-time handover by the whole log
     * retention period. The subject is withheld for the reason the provisioning service's own log lines
     * withhold it: it is the value a presented token is matched on, so a log store holding it holds the
     * linkage between a person and their token claims.</p>
     *
     * <p>Assumptions: the credential added to this record is withheld by the SAME mechanism rather than
     * by a new one, which is why this rendering names three withheld components and not two. A record
     * that gained a component and kept a two-component rendering would compile, read as complete, and
     * disclose nothing -- but the next component would have no such discipline to inherit.</p>
     *
     * <p>Trade-offs: what is given up is that this value is useless in a diagnostic. That is accepted:
     * the identifier the provisioning concerned is already on every line about the request, and it is
     * the only part of this that a diagnostic legitimately needs.</p>
     *
     * @return a fixed rendering naming the type and stating that every component is withheld; never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "ProvisionedIdentity[subject=<withheld>, credentialSecretName=<withheld>,"
                + " oneTimeCredential=<withheld>]";
    }
}
