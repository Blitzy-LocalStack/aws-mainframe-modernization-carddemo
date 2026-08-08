package com.carddemo.account.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Wires the key-management client the two protected customer identifiers are enciphered with.
 *
 * <p><b>Purpose.</b> This context stores exactly two values as ciphertext -- the national identifier
 * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17 and the government-issued identifier
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18 -- and
 * {@code com.carddemo.account.service.CustomerIdentifierCipher} is the only class that reaches this
 * client. The bean exists so that class can take the client through its constructor and be exercised
 * against a substitute, which is what keeps the encipherment testable without a key or an account.</p>
 *
 * <p>Refactoring Rationale: {@code com.carddemo.account.mapper.CustomerMapper} declares the port
 * {@code CustomerIdentifierProtection} and requires it through its constructor, and for a period no
 * implementation and no bean existed anywhere. The consequence was not cosmetic: the mapper is
 * component-scanned, so context creation failed outright, and the encryption boundary the migrated
 * schema promises -- {@code ssn_encrypted BYTEA} and {@code govt_issued_id_encrypted BYTEA} in
 * {@code src/main/resources/db/migration/V1__account.sql} -- had no production implementation at all. A
 * column named for ciphertext does not by itself encrypt. This file and the cipher beside it are the
 * missing half.</p>
 *
 * <p>Assumptions: region and credentials resolve through the SDK's own default provider chains rather
 * than being set here. In a deployed task the region arrives as an environment variable and the
 * credentials as the task role, which is the mechanism least privilege depends on -- naming either here
 * would hard-code a region into the image or open a route for a credential to be configured, and the
 * whole point of a task role is that no credential is configurable.</p>
 *
 * <p>Assumptions: an endpoint override is available for a local emulator without a property of ours,
 * because the SDK reads a service-specific endpoint environment variable natively. Declaring our own
 * override property would add a second way to say the same thing, and the two would disagree the first
 * time only one was set.</p>
 *
 * <p>Alternatives Considered: obtaining the builder from the shared AWS starter's client configurer,
 * which this module already puts on the classpath for its queue consumer. Rejected because that
 * configurer exists to make several clients agree about which account and endpoint they address, and
 * the key-management client has no sibling to agree with here: it is used by one class for one key. The
 * default provider chain reaches the same account the starter would, so the configurer would add an
 * indirection without changing the result.</p>
 *
 * <p>Alternatives Considered: constructing the client inside the cipher class. Rejected because it
 * would make the cipher untestable without an account: a class that builds its own infrastructure
 * client cannot be handed a substitute, and every case asserting what the envelope discloses would then
 * need a real key.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Configuration(proxyBeanMethods = false)
public class KmsConfig {

    /**
     * Creates the configuration.
     *
     * <p>Assumptions: written out rather than left implicit because the documentation gate requires a
     * docstring on every constructor. The class holds no state.</p>
     */
    public KmsConfig() {
        // Assumptions: empty by design. Everything this class contributes is the bean method below.
    }

    /**
     * Supplies the synchronous key-management client the customer-identifier cipher uses.
     *
     * <p>Trade-offs: the SYNCHRONOUS client. The cipher obtains a data key and then enciphers with it
     * immediately, so it has to have the key in hand before it can continue and would block on a future
     * at once if the client were asynchronous. Blocking explicitly is the same wait without the
     * ambiguity about which thread the continuation runs on.</p>
     *
     * <p>Assumptions: conditional on no other bean of this type, so a test or a future context can
     * supply its own -- a substitute, or a client pointed at an emulator -- without this class being
     * modified or excluded. The same condition is what lets the sliced web tests in this module stand up
     * a context without reaching a key-management service.</p>
     *
     * @return the key-management client, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public KmsClient kmsClient() {
        return KmsClient.create();
    }
}
