package com.carddemo.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that a protected-value update intent cannot be altered after it has been stated.
 *
 * <p><b>Purpose.</b> {@link Customer.ProtectedValueUpdate} carries the one component of an update that is
 * genuinely mutable: the ciphertext of a protected identifier, held as a {@code byte[]}. An array retained
 * by reference makes the intent's declared value changeable after the declaration, so the value that
 * eventually reaches {@code account.customers} can differ from the value the caller stated -- and neither
 * the intent nor the entity reports the substitution, because both see a well-formed array either way.
 * This class holds the copy-in and copy-out property that closes it.</p>
 *
 * <p>Assumptions: the assertions read the intent's own accessor rather than the stored column, because the
 * entity deliberately publishes no getter for either protected column -- the values are write-only from
 * outside the package and are read only by the persistence provider. The accessor is therefore the last
 * observable point on the path from a caller's array to the column, and it is the point both copies have to
 * hold at.</p>
 *
 * <p>Assumptions: this lives in the entity's own package rather than beside the service tests that build
 * these intents, for one mechanical reason: the accessor is package-private, so no assertion outside this
 * package can observe what an intent is carrying at all.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception documentation.</p>
 */
@DisplayName("A protected-value update intent is fixed when it is created")
class ProtectedValueIsolationTest {

    /** The ciphertext a caller declares, short because its content is irrelevant to the property. */
    private static final byte[] DECLARED = {1, 2, 3, 4};

    /**
     * Confirms mutating the caller's array after stating the intent does not change what the intent carries.
     *
     * <p>Assumptions: this is the direction a caller reaches by reusing a working buffer across two
     * identifiers, which is ordinary code rather than an adversarial act -- the national and the
     * government-issued identifier are enciphered one after the other on the same update.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a later mutation of the caller's array does not change the declared value")
    void aLaterMutationOfTheCallersArrayDoesNotChangeTheIntent() {
        byte[] supplied = DECLARED.clone();
        Customer.ProtectedValueUpdate intent = Customer.ProtectedValueUpdate.replaceWith(supplied);

        supplied[0] = 99;

        assertThat(intent.ciphertext())
                .as("the intent must carry the value the caller declared, not the value its array holds"
                        + " afterwards")
                .containsExactly(DECLARED);
    }

    /**
     * Confirms mutating the array the accessor returns does not change what a later read reports.
     *
     * <p>Assumptions: this direction is asserted separately because one copy does not establish the
     * property. Copying at construction while returning the held array leaves any holder of the returned
     * value able to alter what the entity reads on the write path, which is the same defect from the other
     * side.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a mutation of the value handed out does not change what a later read reports")
    void aMutationOfTheValueHandedOutDoesNotChangeTheIntent() {
        Customer.ProtectedValueUpdate intent = Customer.ProtectedValueUpdate.replaceWith(DECLARED.clone());

        byte[] handedOut = intent.ciphertext();
        handedOut[0] = 55;

        assertThat(intent.ciphertext())
                .as("a value handed out must be a copy, or the accessor is a second way to mutate the"
                        + " intent")
                .containsExactly(DECLARED);
    }

    /**
     * Confirms the two stateless intents carry no ciphertext, so the copying rule has nothing to apply to.
     *
     * <p>Assumptions: asserted so that the copy added to the constructor is shown not to have turned a
     * {@code null} into an empty array -- which would make {@code replaces()} report true for an intent
     * that replaces nothing, and would write an empty value over a stored identifier.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the preserve and clear intents carry no ciphertext at all")
    void theStatelessIntentsCarryNoCiphertext() {
        assertThat(Customer.ProtectedValueUpdate.preserve().ciphertext()).isNull();
        assertThat(Customer.ProtectedValueUpdate.preserve().replaces()).isFalse();
        assertThat(Customer.ProtectedValueUpdate.clear().ciphertext()).isNull();
        assertThat(Customer.ProtectedValueUpdate.clear().replaces()).isFalse();
        assertThat(Customer.ProtectedValueUpdate.clear().clears()).isTrue();
    }

    /**
     * Confirms an empty array is still refused, which the copy must not have made indistinguishable.
     *
     * <p>Assumptions: the refusal exists so that storing nothing and protecting nothing stay distinct
     * outcomes, and it is asserted beside the copying cases because a copy of an empty array is also empty
     * -- so a constructor that copied BEFORE validating would have preserved the refusal by accident rather
     * than by order. The factory validates first, and this case is what says so.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an empty ciphertext is refused rather than copied")
    void anEmptyCiphertextIsRefused() {
        assertThatThrownBy(() -> Customer.ProtectedValueUpdate.replaceWith(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clear()");
    }
}
