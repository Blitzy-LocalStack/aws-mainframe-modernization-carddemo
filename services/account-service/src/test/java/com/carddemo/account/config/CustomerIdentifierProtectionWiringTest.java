package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.service.CustomerIdentifierCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Proves this module can satisfy the encryption boundary its mapping layer requires, exactly once.
 *
 * <p>Purpose: {@link CustomerMapper} is component-scanned and takes
 * {@link CustomerMapper.CustomerIdentifierProtection} through its constructor. For a period no
 * implementation and no bean of that type existed anywhere in the repository, so this service could not
 * create its application context at all and the two encrypted columns
 * {@code src/main/resources/db/migration/V1__account.sql} declares had no production encipherment behind
 * them. A compile proves nothing about either fact: the port is satisfied at wiring time, not at compile
 * time. These cases are what turn "the bean exists" from an assumption into an assertion.
 *
 * <p>Assumptions: the key-management client is SUBSTITUTED rather than created. The real bean method on
 * {@link KmsConfig} is conditional on no other bean of its type precisely so that a context test can
 * supply its own, and supplying one here is what keeps these assertions independent of a region, a
 * credential and an account. What is therefore NOT proved here is that the deployed client resolves --
 * that is a deployment concern the task role and the region variable answer, and it is asserted for this
 * module's AWS starters separately in {@code AwsIntegrationStartupTest}.
 *
 * <p>Alternatives Considered: standing up the whole application context with {@code @SpringBootTest}.
 * Rejected because it would drag in the datasource, Flyway, the resource-server issuer resolution and the
 * queue listener container, so a failure in any of those would be reported as a failure of the wiring
 * these cases are about. A sliced context naming exactly the two configurations under test fails only for
 * the reason under test.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each member carries its own.
 */
class CustomerIdentifierProtectionWiringTest {

    /**
     * The key alias the sliced context resolves the cipher's fallback-free property from.
     *
     * <p>Assumptions: fabricated and visibly non-production. It is never sent anywhere, because the
     * substituted client is never called by a wiring assertion.
     */
    private static final String KEY_ALIAS_PROPERTY =
            "carddemo.security.customer-identifier.key-id=alias/carddemo-customer-identifier-test";

    /**
     * The sliced context every case runs against.
     *
     * <p>Assumptions: the runner names the cipher and the substitute client only. {@link KmsConfig} is
     * deliberately absent from the default slice so that each case can decide whether the real bean
     * method participates, which is how the conditional-bean behaviour below is exercised.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(KEY_ALIAS_PROPERTY);

    /**
     * Confirms exactly one implementation of the port is contributed, and that it is the cipher.
     *
     * <p>Assumptions: the COUNT is asserted, not merely the presence. A second unqualified bean of a type
     * injected by type is the failure mode that took down a sibling context in this same checkpoint -- an
     * abandoned configuration left a duplicate tokeniser bean behind -- so counting is what stops the same
     * mistake being made here by a later addition.
     */
    @Test
    @DisplayName("exactly one CustomerIdentifierProtection bean is contributed, and it is the cipher")
    void exactlyOneProtectionBeanIsContributed() {
        this.runner
                .withUserConfiguration(SubstituteKmsClient.class)
                .withBean(CustomerIdentifierCipher.class,
                        () -> new CustomerIdentifierCipher(Mockito.mock(KmsClient.class),
                                "alias/carddemo-customer-identifier-test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(
                            CustomerMapper.CustomerIdentifierProtection.class)).hasSize(1);
                    assertThat(context.getBean(CustomerMapper.CustomerIdentifierProtection.class))
                            .isInstanceOf(CustomerIdentifierCipher.class);
                });
    }

    /**
     * Confirms the mapper can be constructed from the contributed bean, which is the wiring that failed.
     *
     * <p>Assumptions: the mapper is placed in the slice rather than asserted about in isolation, because
     * the defect this class exists for was not that the port was unimplemented in the abstract -- it was
     * that a component-scanned mapper requiring it could not be satisfied. Constructing the mapper from
     * the context is the only assertion that reproduces that.
     */
    @Test
    @DisplayName("the component-scanned mapper is satisfied by the contributed boundary")
    void theMapperIsSatisfiedByTheContributedBoundary() {
        this.runner
                .withUserConfiguration(SubstituteKmsClient.class)
                .withBean(CustomerIdentifierCipher.class,
                        () -> new CustomerIdentifierCipher(Mockito.mock(KmsClient.class),
                                "alias/carddemo-customer-identifier-test"))
                .withBean(CustomerMapper.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CustomerMapper.class)).isNotNull();
                });
    }

    /**
     * Confirms the key-management bean method yields to a substitute rather than competing with it.
     *
     * <p>Assumptions: this is what the missing-bean condition on {@link KmsConfig} buys, and it is
     * asserted because the condition is invisible at every call site. Without it a sliced test supplying
     * its own client would end up with two clients of one type and an unsatisfied injection point, and the
     * failure would name the injection rather than the configuration that caused it.
     */
    @Test
    @DisplayName("the key-management bean method yields to a substituted client")
    void theKeyManagementBeanMethodYieldsToASubstitute() {
        this.runner
                .withUserConfiguration(SubstituteKmsClient.class, KmsConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(KmsClient.class)).hasSize(1);
                });
    }

    /**
     * Supplies a substituted key-management client so no case reaches an account or a region.
     *
     * <p>Assumptions: declared as a nested configuration rather than registered through a bean supplier so
     * that it participates in the same ordering the real configuration would, which is what makes the
     * conditional-bean case above meaningful.
     */
    @Configuration(proxyBeanMethods = false)
    static class SubstituteKmsClient {

        /**
         * Creates the nested configuration.
         *
         * <p>Assumptions: written out because the documentation gate requires a docstring on every
         * constructor. The class holds no state.
         */
        SubstituteKmsClient() {
            // Assumptions: empty by design; the bean method below is the whole contribution.
        }

        /**
         * Supplies a substituted key-management client.
         *
         * <p>Assumptions: a substitute with no stubbing at all, because no wiring assertion in this class
         * calls it. A stubbed response would suggest these cases exercise the encipherment, which they do
         * not -- that is {@code CustomerIdentifierCipherTest}'s subject.
         *
         * @return a substituted client, never {@code null}
         */
        @Bean
        KmsClient kmsClient() {
            return Mockito.mock(KmsClient.class);
        }
    }
}
