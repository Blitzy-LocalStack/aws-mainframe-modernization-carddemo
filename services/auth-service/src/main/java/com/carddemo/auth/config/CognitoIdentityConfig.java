package com.carddemo.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Supplies the administrative identity-provider client this context provisions pool accounts through.
 *
 * <h2>Why a wiring type rather than construction inside the service</h2>
 *
 * <p>Alternatives Considered: building the client inside
 * {@code com.carddemo.auth.service.CognitoUserProvisioningService} itself, which would remove this
 * file. Rejected because it would make that class untestable without reaching a network: a class that
 * builds its own client cannot be handed a stand-in, so every assertion about which provider calls it
 * issues would have to become an assertion about an HTTP exchange. Injecting the client leaves the
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
 * <h2>Trade-offs: this is the second landed type in this package, and the charter says so</h2>
 *
 * <p>The package charter names four configuration types, of which this and {@code SecurityConfig} are
 * on disk. It is a separate file from {@code SecurityConfig} rather than a bean method on it because
 * the two hold opposite ends of the identity relationship and must not share a task-role footprint in
 * a reader's mind: {@code SecurityConfig} decides whether a presented token is accepted and needs no
 * provider permission at all, while this client is used with administrative user-management
 * permissions on the pool. Keeping them apart is what lets a reader see that the request-authentication
 * path holds none of those permissions.</p>
 */
@Configuration
public class CognitoIdentityConfig {

    /**
     * Creates the administrative provider client, unless one is already contributed.
     *
     * <p>Assumptions: the missing-bean condition exists so a test or a future integration slice can
     * supply its own client without excluding this class, which is the same discipline the shared
     * kernel's auto-configuration uses for every component it contributes. Without the condition an
     * overriding bean would collide rather than take precedence.</p>
     *
     * <p>Assumptions: the client is built through {@code create()} rather than through a builder with
     * arguments, because every setting this context needs is one the default chains already resolve --
     * see the class note above. A builder with no arguments added would be the same object reached by
     * more code.</p>
     *
     * @return the administrative identity-provider client; never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(CognitoIdentityProviderClient.class)
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.create();
    }
}
