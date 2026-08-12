package com.carddemo.auth.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Supplies the two bounded AWS clients this context provisions managed identities through.
 *
 * <h2>Why a wiring type rather than construction inside the service</h2>
 *
 * <p>Alternatives Considered: building the clients inside
 * {@code com.carddemo.auth.service.CognitoUserProvisioningService} itself, which would remove this
 * file. Rejected because it would make that class untestable without reaching a network: a class that
 * builds its own client cannot be handed a stand-in, so every assertion about which provider calls it
 * issues would have to become an assertion about an HTTP exchange. Injecting the clients leaves the
 * service's provider interaction assertable in the ordinary test gate, which is where the mapping from
 * a reference user type to a pool group is checked.</p>
 *
 * <p>Assumptions: the region, the credentials and the endpoint are all resolved by the SDK's own
 * default chains rather than by properties declared here, and that is deliberate. On the deployed task
 * the region arrives from the execution environment and the credentials from the task role, so a
 * property for either would be a second source that could disagree with the one the SDK actually uses.
 * Declaring them would also make a local run appear configured while still resolving credentials from
 * somewhere else entirely.</p>
 *
 * <h2>Why both clients carry an explicit deadline</h2>
 *
 * <p>Refactoring Rationale: both clients were built by {@code create()} with no override configuration
 * at all, and the SDK's default for both the whole-call and the per-attempt deadline is NO deadline.
 * That absence is not symmetrical with the rest of this context. The sign-on exchange is bounded, and
 * every administrative call reached from a request thread was not: a provider that accepted a
 * connection and then stopped answering would hold that thread, and its database connection where one
 * was open, until the socket itself gave up. The concrete shape of the failure is a create-user request
 * that never returns while the pool account it asked for has already been made, so the caller retries
 * and meets the pool's duplicate-username condition. Both deadlines are therefore set here, from
 * validated properties, rather than left to a default that means "wait forever".</p>
 *
 * <p>Assumptions: the per-attempt deadline must be smaller than the whole-call deadline, and the pair
 * is validated rather than trusted. The SDK spends the attempt budget once per try and the whole-call
 * budget across every try including the retries it makes itself, so an attempt budget that equalled or
 * exceeded the whole-call budget would leave the retry policy no room at all -- the first slow attempt
 * would consume the entire call. Inverting them is a configuration mistake that produces a service
 * which retries nothing while appearing to have a retry policy, so it stops startup with a message
 * naming both properties instead.</p>
 *
 * <p>Trade-offs: one budget pair is shared by both clients rather than a pair per client. The two are
 * called from the same paths within one request or one reconciliation task -- the pool account is
 * created and the credential is published in immediate succession -- so a caller's total exposure is
 * the sum either way, and two pairs would be four properties whose only correct relationship is that
 * they are equal. A per-client pair belongs here the moment one of them is reached from a path the
 * other is not.</p>
 *
 * <h2>Trade-offs: this package's charter names what is present</h2>
 *
 * <p>This is a separate file from {@code SecurityConfig} rather than a bean method on it because the
 * two hold opposite ends of the identity relationship and must not share a task-role footprint in a
 * reader's mind: {@code SecurityConfig} decides whether a presented token is accepted and needs no
 * provider permission at all, while these clients are used with administrative user-management
 * permissions on the pool and write permission under one managed-secret name prefix. Keeping them
 * apart is what lets a reader see that the request-authentication path holds none of those
 * permissions.</p>
 */
@Configuration
// WHY : Refactoring Rationale: scheduling is enabled HERE, on the configuration that owns the provider
//       client, because the only scheduled job this service runs exists solely to converge that provider:
//       IdentitySyncService's reconciliation pass applies the changes auth.users owes the user pool after a
//       process death left them pending. Alternatives Considered: a configuration class of its own, which
//       would have been one more file whose whole content is one annotation, and the application class,
//       where a reader looking for what is scheduled would find no clue as to why. The authorization
//       context makes the same choice, enabling scheduling on the configuration that owns its queue client.
@EnableScheduling
public class CognitoIdentityConfig {

    /**
     * The property naming how long a whole administrative call may take, retries included.
     *
     * <p>Assumptions: the key is declared as a constant because it is named in the failure this class
     * raises as well as read by the annotation that injects it, and a failure that named a different
     * string from the one that was read would send an operator to the wrong line of a document.</p>
     */
    static final String API_CALL_TIMEOUT_KEY = "carddemo.auth.cognito.api-call-timeout-ms";

    /** The property naming how long one attempt of an administrative call may take. */
    static final String API_CALL_ATTEMPT_TIMEOUT_KEY =
            "carddemo.auth.cognito.api-call-attempt-timeout-ms";

    /**
     * Creates the administrative provider client, unless one is already contributed.
     *
     * <p>Assumptions: the missing-bean condition exists so a test or a future integration slice can
     * supply its own client without excluding this class, which is the same discipline the shared
     * kernel's auto-configuration uses for every component it contributes. Without the condition an
     * overriding bean would collide rather than take precedence.</p>
     *
     * <p>Assumptions: the builder is reached rather than {@code create()} because the override
     * configuration is the whole reason this method is not one line. Every other setting is one the
     * default chains already resolve -- see the class note above -- so nothing else is set here.</p>
     *
     * @param apiCallTimeoutMillis how long a whole call may take including the SDK's own retries, in
     *     milliseconds; must be positive
     * @param apiCallAttemptTimeoutMillis how long one attempt may take, in milliseconds; must be
     *     positive and smaller than {@code apiCallTimeoutMillis}
     * @return the administrative identity-provider client; never {@code null}
     * @throws IllegalStateException if either budget is not positive, or if the attempt budget is not
     *     smaller than the whole-call budget
     */
    @Bean
    @ConditionalOnMissingBean(CognitoIdentityProviderClient.class)
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(
            @Value("${" + API_CALL_TIMEOUT_KEY + ":10000}") long apiCallTimeoutMillis,
            @Value("${" + API_CALL_ATTEMPT_TIMEOUT_KEY + ":4000}") long apiCallAttemptTimeoutMillis) {

        return CognitoIdentityProviderClient.builder()
                .overrideConfiguration(
                        callBudget(apiCallTimeoutMillis, apiCallAttemptTimeoutMillis))
                .build();
    }

    /**
     * Creates the managed-secret client the initial-credential handover is published through, unless
     * one is already contributed.
     *
     * <p>Assumptions: it carries the SAME budget pair as the provider client above, for the reason the
     * class note records: the two are called in immediate succession on one provisioning path, so a
     * caller's exposure is their sum and separate budgets would be two numbers whose only correct
     * relationship is equality.</p>
     *
     * @param apiCallTimeoutMillis how long a whole call may take including the SDK's own retries, in
     *     milliseconds; must be positive
     * @param apiCallAttemptTimeoutMillis how long one attempt may take, in milliseconds; must be
     *     positive and smaller than {@code apiCallTimeoutMillis}
     * @return the managed-secret client; never {@code null}
     * @throws IllegalStateException if either budget is not positive, or if the attempt budget is not
     *     smaller than the whole-call budget
     */
    @Bean
    @ConditionalOnMissingBean(SecretsManagerClient.class)
    public SecretsManagerClient authSecretsManagerClient(
            @Value("${" + API_CALL_TIMEOUT_KEY + ":10000}") long apiCallTimeoutMillis,
            @Value("${" + API_CALL_ATTEMPT_TIMEOUT_KEY + ":4000}") long apiCallAttemptTimeoutMillis) {

        return SecretsManagerClient.builder()
                .overrideConfiguration(
                        callBudget(apiCallTimeoutMillis, apiCallAttemptTimeoutMillis))
                .build();
    }

    /**
     * Builds the validated override configuration both clients above are given.
     *
     * <p>Assumptions: validation happens HERE rather than on each bean method, so the two clients
     * cannot be bounded on different terms and a reader has one place to check what the pair means.</p>
     *
     * @param apiCallTimeoutMillis how long a whole call may take, in milliseconds
     * @param apiCallAttemptTimeoutMillis how long one attempt may take, in milliseconds
     * @return the override configuration carrying both deadlines; never {@code null}
     * @throws IllegalStateException if either budget is not positive, or if the attempt budget is not
     *     smaller than the whole-call budget
     */
    static ClientOverrideConfiguration callBudget(long apiCallTimeoutMillis,
            long apiCallAttemptTimeoutMillis) {

        requirePositive(apiCallTimeoutMillis, API_CALL_TIMEOUT_KEY,
                "a whole-call budget of zero or less either refuses every call outright or, read as"
                        + " absent, restores the unbounded default this setting exists to remove");
        requirePositive(apiCallAttemptTimeoutMillis, API_CALL_ATTEMPT_TIMEOUT_KEY,
                "an attempt budget of zero or less cannot bound a single attempt, so a provider that"
                        + " accepts a connection and stops answering holds the calling thread");

        if (apiCallAttemptTimeoutMillis >= apiCallTimeoutMillis) {
            throw new IllegalStateException(API_CALL_ATTEMPT_TIMEOUT_KEY + " ("
                    + apiCallAttemptTimeoutMillis + "ms) must be smaller than " + API_CALL_TIMEOUT_KEY
                    + " (" + apiCallTimeoutMillis + "ms): the whole-call budget is spent across every"
                    + " attempt the retry policy makes, so an attempt allowed to consume all of it"
                    + " leaves the policy no room and retries nothing");
        }

        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis))
                .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMillis))
                .build();
    }

    /**
     * Refuses a budget that is not positive, naming the property and what a non-positive value costs.
     *
     * @param budgetMillis the configured budget in milliseconds
     * @param propertyName the property the budget came from, named in the failure; must not be
     *     {@code null}
     * @param consequence what a non-positive value costs, quoted so the failure explains itself; must
     *     not be {@code null}
     * @throws IllegalStateException if the budget is zero or negative
     */
    private static void requirePositive(long budgetMillis, String propertyName, String consequence) {
        if (budgetMillis <= 0L) {
            throw new IllegalStateException(propertyName + " must be positive but was " + budgetMillis
                    + "ms: " + consequence);
        }
    }
}
