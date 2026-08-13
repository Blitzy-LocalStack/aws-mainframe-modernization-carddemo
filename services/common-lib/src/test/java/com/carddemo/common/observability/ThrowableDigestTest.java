package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a failure rendered for the operational record carries no message text from any link of
 * its cause chain.
 *
 * <h2>Purpose</h2>
 *
 * <p>The generic five-hundred handler used to hand the caught throwable to the logging facade, which
 * renders it by printing that exception's message and then every cause's message. Those sentences are
 * composed by drivers, parsers and validation libraries rather than by this repository, so any of them
 * can carry a primary account number, a national identifier or a whole request record -- and the generic
 * handler is by definition the one that fires for failures nobody anticipated. The justification given
 * for passing the throwable was that the appender configuration would redact it; no appender
 * configuration exists anywhere in this repository. This class asserts the replacement.</p>
 *
 * <p>Assumptions: every test below plants a recognisable sentinel INSIDE a message and asserts the
 * sentinel does not appear in the output. That is the direct form of the property, and it is preferred
 * over asserting the output's shape: a rendering could match an expected shape and still append the
 * message, whereas a rendering that omits the sentinel cannot have included the message that carried
 * it.</p>
 *
 * <p>Assumptions: the sentinels are shaped like the values that actually matter -- a sixteen-digit
 * account number, a national identifier and a fixed-width record image -- rather than being an arbitrary
 * token. Shaping them that way means a reader of a failure here sees immediately which class of
 * disclosure was reintroduced.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class ThrowableDigestTest {

    /**
     * A sentinel shaped like an unmasked primary account number, drawn from the reserved test range.
     *
     * <p>Assumptions: this is the card scheme's own published test number, reserved for exactly this
     * use and issued to nobody, which is why it is safe to write into a source file that is read and
     * searched. It is stated here rather than left to be recognised, because a sixteen-digit string
     * that satisfies the industry check digit is indistinguishable from a live account number to a
     * reader who does not already know the range.</p>
     */
    private static final String PAN_SENTINEL = "4111111111111111";

    /**
     * A sentinel shaped like a national identifier that no allocation rule could ever have issued.
     *
     * <p>Assumptions: the digits are chosen so the value cannot belong to a person rather than merely
     * being unlikely to. The issuing authority has never assigned an area number of 000, never a group
     * number of 00 and never a serial number of 0000, so this string fails three independent allocation
     * rules at once. It replaces 123-45-6789, which is shaped exactly like an ISSUABLE identifier and
     * therefore reads as a real one to anyone who meets it out of context -- an outcome this file exists
     * to prevent for values in a log line.</p>
     */
    private static final String SSN_SENTINEL = "000-00-0000";

    /**
     * A sentinel shaped like a fixed-width record image fragment.
     */
    private static final String RECORD_SENTINEL = "DOE       JOHN      0000012345";

    /**
     * Verifies the outermost throwable's own message never appears in the digest.
     *
     * <p>Assumptions: the outermost message is the one a facade prints first and the one a reader is
     * most likely to assume is safe, because it is often composed by the application. It is not always:
     * a framework wrapper composes the outermost message on a message-conversion failure, and it quotes
     * the offending payload there.</p>
     */
    @Test
    @DisplayName("the outermost throwable's message never appears in the digest")
    void theOutermostMessageNeverAppears() {
        String digest = ThrowableDigest.of(
                new IllegalStateException("card " + PAN_SENTINEL + " could not be posted"));

        assertThat(digest).doesNotContain(PAN_SENTINEL);
        assertThat(digest).contains(IllegalStateException.class.getName());
    }

    /**
     * Verifies no message from any link of a three-deep cause chain appears.
     *
     * <p>Assumptions: three links with three different sentinels, so a rendering that dropped only the
     * outermost message -- the single most plausible partial implementation -- fails on the second
     * sentinel rather than passing. The deepest link carries the record image, because in practice the
     * deepest cause is the driver or codec closest to the data and therefore the most likely to quote
     * it.</p>
     */
    @Test
    @DisplayName("no message from any link of a cause chain appears in the digest")
    void noMessageFromAnyLinkAppears() {
        Throwable deepest = new NumberFormatException("cannot parse '" + RECORD_SENTINEL + "'");
        Throwable middle = new IllegalArgumentException("customer " + SSN_SENTINEL + " rejected", deepest);
        Throwable outermost = new IllegalStateException("posting " + PAN_SENTINEL + " failed", middle);

        String digest = ThrowableDigest.of(outermost);

        assertThat(digest)
                .doesNotContain(PAN_SENTINEL)
                .doesNotContain(SSN_SENTINEL)
                .doesNotContain(RECORD_SENTINEL);
        assertThat(digest)
                .contains(IllegalStateException.class.getName())
                .contains(IllegalArgumentException.class.getName())
                .contains(NumberFormatException.class.getName());
        assertThat(digest).contains(ThrowableDigest.CAUSE_SEPARATOR);
    }

    /**
     * Verifies the digest names the frame the failure was raised at, so a reduction stays diagnostic.
     *
     * <p>Assumptions: this is the counterweight to every omission assertion above. A digest that
     * omitted the messages AND the frames would pass every other test in this class while being useless,
     * so the frame of this very test method is asserted present -- it is the frame the throwable below is
     * constructed at, which makes the expectation exact rather than approximate.</p>
     */
    @Test
    @DisplayName("the digest names the class, method and line the failure was raised at")
    void theDigestNamesTheOriginatingFrame() {
        String digest = ThrowableDigest.of(new IllegalStateException("no sentinel here"));

        assertThat(digest)
                .contains(ThrowableDigestTest.class.getName())
                .contains("theDigestNamesTheOriginatingFrame")
                .contains("ThrowableDigestTest.java");
    }

    /**
     * Verifies the digest is one whitespace-free token.
     *
     * <p>Assumptions: several log collectors split a structured line on whitespace, so a value containing
     * a space becomes two fields of which one has no key. The digest is interpolated into such a line as
     * a single {@code failure=} field, which is why the absence of whitespace is a contract and not a
     * formatting preference. The message planted below carries spaces, so a rendering that leaked it
     * would fail this assertion as well as the omission one.</p>
     */
    @Test
    @DisplayName("the digest is a single token with no whitespace")
    void theDigestIsASingleWhitespaceFreeToken() {
        String digest = ThrowableDigest.of(new IllegalStateException(
                "a message with several spaces in it", new IllegalArgumentException("and another")));

        assertThat(digest).doesNotContainAnyWhitespaces();
    }

    /**
     * Verifies a self-referential cause chain terminates rather than spinning.
     *
     * <p>Assumptions: a throwable whose cause is itself is constructible -- the platform's
     * initialisation guard refuses a second {@code initCause} but not one passed to the constructor of a
     * subclass that overrides the accessor -- and a naive walk over it never returns. This is asserted
     * because the failure mode is a hung request thread rather than an exception, which no other test in
     * this class would reveal.</p>
     */
    @Test
    @DisplayName("a self-referential cause chain terminates")
    void aSelfReferentialCauseChainTerminates() {
        Throwable looping = new RuntimeException("loop " + PAN_SENTINEL) {
            /** Serialisation identity for this anonymous subclass. */
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        String digest = ThrowableDigest.of(looping);

        assertThat(digest).doesNotContain(PAN_SENTINEL);
        assertThat(digest).doesNotContain(ThrowableDigest.CAUSE_SEPARATOR);
    }

    /**
     * Verifies a chain longer than the depth cap is truncated and says so.
     *
     * <p>Assumptions: the marker matters as much as the truncation. A digest cut silently at eight links
     * would let a reader believe the deepest link they can see is the root cause, which is the one
     * conclusion a truncated diagnostic must not invite.</p>
     */
    @Test
    @DisplayName("a chain deeper than the cap is truncated and marked")
    void aChainDeeperThanTheCapIsTruncatedAndMarked() {
        Throwable chain = new IllegalStateException("root " + SSN_SENTINEL);
        for (int depth = 0; depth < ThrowableDigest.MAX_CAUSE_DEPTH + 4; depth++) {
            chain = new IllegalStateException("link " + depth + " " + PAN_SENTINEL, chain);
        }

        String digest = ThrowableDigest.of(chain);

        assertThat(digest).endsWith(ThrowableDigest.TRUNCATION_MARKER);
        assertThat(digest).doesNotContain(PAN_SENTINEL).doesNotContain(SSN_SENTINEL);
        assertThat(digest.split(ThrowableDigest.CAUSE_SEPARATOR, -1))
                .describedAs("the walk must stop at the declared cap")
                .hasSizeLessThanOrEqualTo(ThrowableDigest.MAX_CAUSE_DEPTH + 1);
    }

    /**
     * Verifies a chain exactly at the cap is rendered whole and carries no truncation marker.
     *
     * <p>Assumptions: paired with the test above so the boundary is pinned on both sides. A cap applied
     * one link early would mark a complete chain as truncated, which is a different and quieter defect
     * than failing to mark an incomplete one.</p>
     */
    @Test
    @DisplayName("a chain exactly at the cap carries no truncation marker")
    void aChainExactlyAtTheCapCarriesNoMarker() {
        Throwable chain = new IllegalStateException("root");
        for (int depth = 1; depth < ThrowableDigest.MAX_CAUSE_DEPTH; depth++) {
            chain = new IllegalStateException("link " + depth, chain);
        }

        String digest = ThrowableDigest.of(chain);

        assertThat(digest).doesNotContain(ThrowableDigest.TRUNCATION_MARKER);
    }

    /**
     * Verifies a throwable with no stack trace renders a placeholder rather than nothing.
     *
     * <p>Assumptions: a suppressed-trace throwable is what a framework constructs on a hot path and what
     * a deserialised remote failure often is, so the case is ordinary rather than exotic. Rendering the
     * type followed by nothing would read as a formatting failure, which would send a reader looking in
     * the wrong place.</p>
     */
    @Test
    @DisplayName("a throwable carrying no stack trace renders a placeholder frame")
    void aThrowableWithNoStackTraceRendersAPlaceholder() {
        // WHY : Alternatives Considered: the protected four-argument constructor that suppresses trace
        //       capture, which is how a framework produces such a throwable. It is not declared on
        //       IllegalStateException, so reaching it needs an anonymous subclass here purely to call it.
        //       Emptying the trace after construction reaches the same state with no extra type, and the
        //       state is what this test is about rather than the route to it.
        Throwable traceless = new IllegalStateException(PAN_SENTINEL);
        traceless.setStackTrace(new StackTraceElement[0]);

        String digest = ThrowableDigest.of(traceless);

        assertThat(digest).doesNotContain(PAN_SENTINEL);
        assertThat(digest).contains(ThrowableDigest.NO_FRAME);
    }

    /**
     * Verifies a {@code null} failure renders the declared placeholder.
     *
     * <p>Assumptions: an empty string would be indistinguishable in a log line from a field the emitter
     * failed to populate, and the two want different responses from whoever reads the line.</p>
     */
    @Test
    @DisplayName("a null failure renders the declared placeholder")
    void aNullFailureRendersThePlaceholder() {
        assertThat(ThrowableDigest.of(null)).isEqualTo(ThrowableDigest.NONE);
    }

    /**
     * Verifies the head link renders more frames than a cause link.
     *
     * <p>Assumptions: the asymmetry is deliberate and is asserted so it is not "tidied" into symmetry.
     * For the outermost throwable the question is which code path failed, which takes a few frames to
     * establish; for a cause the question is only where it was raised.</p>
     */
    @Test
    @DisplayName("the outermost link renders more frames than a cause link")
    void theOutermostLinkRendersMoreFramesThanACause() {
        assertThat(ThrowableDigest.MAX_HEAD_FRAMES)
                .isGreaterThan(ThrowableDigest.MAX_CAUSE_FRAMES);

        String digest = ThrowableDigest.of(
                new IllegalStateException("outer", new IllegalArgumentException("inner")));
        String head = digest.substring(0, digest.indexOf(ThrowableDigest.CAUSE_SEPARATOR));
        String cause = digest.substring(
                digest.indexOf(ThrowableDigest.CAUSE_SEPARATOR) + ThrowableDigest.CAUSE_SEPARATOR.length());

        assertThat(head.split(ThrowableDigest.FRAME_SEPARATOR, -1).length)
                .isGreaterThan(cause.split(ThrowableDigest.FRAME_SEPARATOR, -1).length);
    }

    /**
     * Verifies the utility holder refuses instantiation, including reflectively.
     *
     * <p>Assumptions: the refusal is asserted through reflection rather than by observing that no
     * constructor is public, because the reason the constructor throws is that a mocked or subclassed
     * digest could be made to return the very message text this class exists to withhold. A private
     * constructor alone does not prevent that; one that raises does.</p>
     *
     * @throws NoSuchMethodException if the declared constructor is absent, which would mean the class
     *     gained an implicit public one
     */
    @Test
    @DisplayName("the utility holder refuses instantiation, including reflectively")
    void theUtilityHolderRefusesInstantiation() throws NoSuchMethodException {
        Constructor<ThrowableDigest> constructor = ThrowableDigest.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }
}
