package com.carddemo.account.config;

import com.carddemo.account.mapper.CustomerMapper.CustomerIdentifierProtection;
import com.carddemo.account.service.CustomerIdentifierCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Supplies the implementation of the protected-identifier port the customer mapper declares.
 *
 * <p><b>Purpose.</b> {@code CustomerMapper} is a {@code @Component} whose constructor requires a
 * {@link CustomerIdentifierProtection}, and until this class existed no implementation of that interface
 * existed anywhere in the reactor. The consequence was not a missing feature but a context that could not
 * start at all: any refresh that scanned {@code com.carddemo.account.mapper} failed with
 * {@code NoSuchBeanDefinitionException} for the nested port type. The module's own build did not catch it,
 * because its start-up test drives an {@code ApplicationContextRunner} over the AWS autoconfigurations
 * only and never scans the mapper package, so the missing bean was invisible to every green build.</p>
 *
 * <p>Assumptions: this lives in the CONFIGURATION layer, which is where the port's own documentation says
 * an implementation belongs -- the interface is declared in the mapper package so that the mapping layer
 * states what it needs of a key provider without naming one, and an infrastructure type is admissible
 * here and not there. The mapper therefore still has no compile-time dependency on the key-management
 * client, and its unit tests still satisfy the port with a substitute and hold every width, composition
 * and masking decision assertable with no key material in reach.</p>
 *
 * <p>⚠️ Refactoring Rationale: this class no longer ENCIPHERS anything. It held a private nested
 * {@code KmsEnvelopeIdentifierProtection} that framed
 * {@code [2-byte key length][wrapped key][IV][ciphertext]}, with no marker and no version byte, while
 * {@code com.carddemo.account.service.CustomerIdentifierCipher} -- a {@code @Component}, and therefore
 * the implementation that supersedes this one under {@code @ConditionalOnMissingBean} in any context
 * that scans the service package -- frames
 * {@code [CDCI][version][2-byte key length][wrapped key][IV][ciphertext]}. One {@code BYTEA} column
 * consequently had two writers producing two formats differing in their first five bytes, selected by
 * which beans a context happened to register. Nothing detected it because this context publishes no
 * decipher path, so the bytes are never read back here; it would have surfaced at the first re-key or
 * audit read, against rows already written, with no way to tell from a row which writer produced it. The
 * bean method below now delegates to that one cipher, so there is exactly ONE writer of this envelope in
 * the reactor and its declared constants are what
 * {@code data-migration/src/carddemo_migration/loaders/protected_columns.py} reproduces.</p>
 *
 * <p>Assumptions: what remains here is WIRING. The cryptographic decisions -- envelope encryption under
 * a fresh data key per identifier, Galois/Counter Mode, a twelve-byte vector, a hundred-and-twenty-eight
 * bit tag, an encryption context naming the purpose and the column but never the customer, and the byte
 * framing itself -- are all recorded on {@code CustomerIdentifierCipher}, which is where they are
 * implemented. Restating any of them here would create a second statement of a contract that has one
 * implementation, which is the shape of the defect above.</p>
 *
 * <p>Trade-offs: there is still no deciphering member anywhere on this path, and its absence is
 * deliberate rather than an omission. {@code Customer} publishes no accessor for either clear identifier
 * and every response renders the fixed redaction marker, so nothing in this context has a value to
 * decipher FOR; a decipher method would be an unused route from ciphertext back to a national
 * identifier. The card context does publish one because a verification value has a verification use.
 * When a re-key or an export needs one, it is added with the test that proves the framing round-trips --
 * which is the same standard the card context met, and it is now a single framing to round-trip rather
 * than two.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CustomerIdentifierProtectionConfig {

    /*
     * WHY : ⚠️ Refactoring Rationale: seven constants stood here -- the transformation, the key
     *       algorithm, the tag length, the vector length and the three encryption-context strings --
     *       and every one of them was read only by the private nested implementation this class no
     *       longer holds. They are deleted rather than kept, because a constant that no code in the
     *       file reads is a SECOND STATEMENT of a contract with one implementation: the next author
     *       to change the vector width on CustomerIdentifierCipher would leave these behind saying
     *       twelve, and a reader comparing the two files would have no way to tell which one the
     *       bytes came from. That divergence-by-duplication is precisely the defect that produced
     *       two writers of this envelope in the first place.
     * WHY : Alternatives Considered: keeping them as documentation of the framing this bean supplies.
     *       Rejected -- the class javadoc already names CustomerIdentifierCipher as the place the
     *       cryptographic decisions are recorded, and a cross-tree assertion in
     *       data-migration/tests/test_protected_columns.py now reads those constants from the cipher
     *       itself, so the one consumer that needed them to be declared somewhere reads them from
     *       the file that uses them.
     */

    /**
     * Creates the configuration.
     *
     * <p>Assumptions: written out rather than left implicit because the documentation gate requires a
     * docstring on every constructor. The class holds no state.</p>
     */
    public CustomerIdentifierProtectionConfig() {
        // Assumptions: empty by design. Everything this class contributes is the two bean methods below.
    }

    /**
     * Supplies the synchronous key-management client the identifier protection uses.
     *
     * <p>Assumptions: region and credentials resolve through the SDK's own default provider chains rather
     * than being set here. In a deployed task the region arrives as an environment variable and the
     * credentials as the task role, which is the mechanism least-privilege depends on; naming either here
     * would hard-code a region into the image or open a route for a credential to be configured, and the
     * whole point of a task role is that no credential is configurable.</p>
     *
     * <p>Trade-offs: the SYNCHRONOUS client, because the protection obtains a data key and then enciphers
     * with it immediately, so it must hold the key before it can continue and would block on a future at
     * once were the client asynchronous. Blocking explicitly is the same wait without ambiguity about
     * which thread the continuation runs on.</p>
     *
     * <p>Assumptions: conditional on no other bean of this type, so a test or a future context can supply
     * a stub or a client pointed at an emulator without this class being modified or excluded. This is
     * what lets the module's own wiring test prove the port is satisfiable with no account reachable.</p>
     *
     * @return the key-management client, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public KmsClient accountKmsClient() {
        return KmsClient.create();
    }

    /**
     * Supplies the protected-identifier boundary the customer mapper requires.
     *
     * <p>Assumptions: conditional on no other bean of this type, for the same reason the client is. A test
     * that wants to assert a composition rather than an encipherment substitutes its own and never
     * reaches a key.</p>
     *
     * @param kms the key-management client to obtain data keys from; must not be {@code null}
     * @param keyId the customer-managed key the data keys are wrapped under; must not be {@code null}
     * @return the protection boundary, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public CustomerIdentifierProtection customerIdentifierProtection(KmsClient kms,
            @Value("${carddemo.security.customer-identifier.key-id}") String keyId) {
        // WHY : ⚠️ Refactoring Rationale: this method used to construct a PRIVATE nested
        //       KmsEnvelopeIdentifierProtection whose framing was
        //       [2-byte key length][wrapped key][IV][ciphertext] -- with no marker and no version
        //       byte -- while the component-scanned CustomerIdentifierCipher that supersedes it under
        //       @ConditionalOnMissingBean frames [CDCI][version][2-byte key length][wrapped key][IV]
        //       [ciphertext]. One column therefore had TWO writers producing TWO formats that differ
        //       in their first five bytes, chosen by which beans a context happened to register. The
        //       divergence was invisible because this context publishes no decipher path, so nothing
        //       reads the bytes back today; it would have surfaced at the first re-key or audit read,
        //       against rows already written, with no way to tell from a row which writer produced it
        //       beyond guessing at its prefix.
        // WHY : Assumptions: delegating removes the second format rather than aligning it. Copying the
        //       marker and the version into a second private implementation would have made the two
        //       agree today and left two places to change on the next format revision, which is the
        //       arrangement that produced this defect. There is now exactly ONE writer of this
        //       envelope in the reactor, and its constants are the ones the Python loader reproduces.
        // WHY : Alternatives Considered: deleting this bean method outright and relying on the
        //       component scan. Rejected because this method is what makes the port satisfiable in a
        //       context that scans the mapper package without scanning the service package -- which is
        //       the slice several of this module's own tests use -- so removing it would reintroduce
        //       the unsatisfiable-port failure this class was created to fix.
        return new CustomerIdentifierCipher(kms, keyId);
    }

}
