package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.messaging.Message;

/**
 * Asserts that the queue starter's own failure log line stays switched off, and that the reason it can be
 * switched off by name still holds.
 *
 * <p>Purpose: the shared defaults set one framework logger to {@code off}, and that single line is a
 * privacy control rather than a noise-reduction preference. The starter's message sink logs
 * {@code error("Error processing message {}.", id, throwable)}, and a trailing throwable argument makes
 * the logging facade render the whole cause chain INCLUDING every exception message — text this
 * repository does not write and cannot vet, arriving from a JDBC driver quoting a statement, a codec
 * quoting bytes, or a validation library naming a rejected value. On these queues those values are
 * primary account numbers and whole request records. This class is what makes that suppression a checked
 * control instead of a comment.</p>
 *
 * <p>Refactoring Rationale: the suppression pins ONE logger by its fully qualified name, and pinning by
 * name is exactly the kind of coupling that rots silently — a starter upgrade that renamed or relocated
 * the class would leave the configuration key matching nothing, the level would go back to inherited, and
 * the exposure would return with nothing failing. That is the failure this class converts into a build
 * failure. The alternative, switching off the whole {@code io.awspring.cloud.sqs.listener.sink} package,
 * was rejected because it would also silence the sink's legitimate lifecycle records.</p>
 *
 * <p>Alternatives Considered: asserting the suppression by starting a service and reading its standard
 * output. Rejected because logging is initialised by an application listener before any context exists,
 * so such a test would assert the order of two framework listeners and would depend on whichever appender
 * the surrounding build had already installed. This class separates the two halves instead, following
 * {@code StructuredLoggingDefaultsTest}: the shipped file is resolved through the same config-data import
 * every service uses, and the logging behaviour is driven directly against a named logger.</p>
 *
 * <p>Assumptions: no container, network endpoint or external service is used, so this class belongs under
 * Surefire as a unit test rather than under Failsafe.</p>
 */
class MessageSinkSuppressionTest {

    /**
     * The framework logger the shared defaults switch off, named in full.
     *
     * <p>Assumptions: this is spelled out as a string rather than derived from a class literal, and that
     * is deliberate. A class literal would be rewritten automatically by any rename refactoring and the
     * test would keep passing while the configuration key no longer matched anything. The string is the
     * same text the configuration file carries, so the two can only agree or the test fails.</p>
     */
    private static final String SINK_LOGGER_NAME =
            "io.awspring.cloud.sqs.listener.sink.AbstractMessageProcessingPipelineSink";

    /** The configuration key the shared defaults set for that logger. */
    private static final String LEVEL_PROPERTY = "logging.level." + SINK_LOGGER_NAME;

    /** The level the shared defaults set it to. */
    private static final String EXPECTED_LEVEL = "off";

    /** The shipped defaults file, imported exactly as each service's {@code application.yml} imports it. */
    private static final String SHARED_DEFAULTS = "classpath:/carddemo-common-defaults.yml";

    /** The message template the sink logs, reproduced so the exposure can be driven exactly. */
    private static final String SINK_TEMPLATE = "Error processing message {}.";

    /**
     * Stands in for the sensitive text a driver, codec or validator puts in an exception message.
     *
     * <p>Assumptions: a card-number-shaped literal is used because that is the value actually at risk on
     * these queues, and because it makes the negative control below unambiguous: if this string can be
     * found in a captured event, the exposure is real rather than theoretical.</p>
     */
    private static final String SENSITIVE_TEXT = "could not parse card 4111111111111111 in request";

    /**
     * The name of the sink's logger field, read reflectively to prove the key targets the real logger.
     */
    private static final String LOGGER_FIELD = "logger";

    /**
     * A context runner that imports the shipped defaults exactly as a service does.
     *
     * <p>Assumptions: the three deployment values the shipped file interpolates elsewhere are not needed
     * here, because the logging level entry contains no placeholder. Supplying them anyway would suggest
     * this assertion depended on them.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=" + SHARED_DEFAULTS);

    /**
     * The class whose logger is suppressed still exists under exactly the name the configuration uses.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the suppressed sink class still exists under the exact name the configuration pins")
    void theSuppressedSinkClassExistsUnderThePinnedName() {
        assertThatNoException()
                .as("the shared defaults switch off %s by name; if the starter renamed or relocated it,"
                        + " the key would match nothing, the level would revert to inherited, and the"
                        + " full-throwable line would return with nothing failing", SINK_LOGGER_NAME)
                .isThrownBy(() -> Class.forName(SINK_LOGGER_NAME));
    }

    /**
     * The suppressed name is the name the sink's own logger carries, so the key reaches that logger.
     *
     * <p>Refactoring Rationale: asserting only that the class exists would be a weaker guard than it
     * looks. A logger obtained with an explicit name, or from a different class, would leave the class
     * present and the configuration key inert. Reading the field and comparing its logger's name closes
     * that gap, and it is the only way to establish the relationship the configuration depends on.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ReflectiveOperationException if the sink class or its logger field cannot be read, which is
     *     itself the failure this test exists to report
     */
    @Test
    @DisplayName("the sink's own logger is named exactly what the configuration switches off")
    void theSinkLoggerIsNamedExactlyWhatIsSuppressed() throws ReflectiveOperationException {
        Class<?> sink = Class.forName(SINK_LOGGER_NAME);
        Field loggerField = sink.getDeclaredField(LOGGER_FIELD);
        loggerField.setAccessible(true);

        Object logger = loggerField.get(null);

        assertThat(logger)
                .as("the suppression assumes the sink logs through an slf4j logger; a different logging"
                        + " facade would need a different mechanism entirely")
                .isInstanceOf(org.slf4j.Logger.class);
        assertThat(((org.slf4j.Logger) logger).getName())
                .as("the configuration key %s only reaches this logger if the logger carries that exact"
                        + " name, which it does when the field is initialised from the class literal",
                        LEVEL_PROPERTY)
                .isEqualTo(SINK_LOGGER_NAME);
    }

    /**
     * The methods that produce the exposing line are still declared on the sink.
     *
     * <p>Assumptions: both overloads are asserted rather than one. The container calls the single-message
     * form or the batch form depending on how it is configured to acknowledge, so a check on one would
     * leave the other's disappearance — or survival — unreported. If either is ever removed upstream,
     * this test failing is the signal to re-read whether the suppression is still needed at all, which is
     * a better outcome than carrying a configuration line that no longer does anything.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ClassNotFoundException if the sink class cannot be loaded, which the first test in this
     *     class reports on its own terms
     */
    @Test
    @DisplayName("both sink logError overloads still exist, so the suppression still has a target")
    void bothSinkLogErrorOverloadsStillExist() throws ClassNotFoundException {
        Class<?> sink = Class.forName(SINK_LOGGER_NAME);

        assertThatCode(() -> sink.getDeclaredMethod("logError", Throwable.class, Message.class))
                .as("the single-message form of the exposing call")
                .doesNotThrowAnyException();
        assertThatCode(() -> sink.getDeclaredMethod("logError", Throwable.class, Collection.class))
                .as("the batch form of the exposing call")
                .doesNotThrowAnyException();
    }

    /**
     * The shipped defaults resolve that logger to {@code off} through a real config-data import.
     *
     * <p>Assumptions: the property is read from the resolved environment rather than by parsing the file
     * as text, so the assertion covers the file being importable and the key being spelled in a form the
     * framework's relaxed binding accepts — a dotted logger name nested under {@code logging.level} is
     * exactly the shape that a hand-written parser would accept and the framework might not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the shared defaults resolve the sink logger to off")
    void theSharedDefaultsResolveTheSinkLoggerToOff() {
        this.runner.run(context -> assertThat(context.getEnvironment().getProperty(LEVEL_PROPERTY))
                .as("the one privacy control that stops the starter rendering exception message text")
                .isEqualTo(EXPECTED_LEVEL));
    }

    /**
     * The level token in the file is one the framework's own level enumeration accepts.
     *
     * <p>Refactoring Rationale: a misspelled level is not rejected loudly. The framework would fail to
     * bind it and the logger would keep its inherited level, so the line would look configured and
     * suppress nothing. Converting the token through the same enumeration the framework binds it with
     * turns that silent case into a failure here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the level token in the shared defaults is one the framework accepts as OFF")
    void theLevelTokenIsAcceptedByTheFramework() {
        assertThat(LogLevel.valueOf(EXPECTED_LEVEL.toUpperCase(Locale.ROOT)))
                .as("a token the framework cannot bind would leave the logger inheriting its level while"
                        + " the configuration line looked correct")
                .isEqualTo(LogLevel.OFF);
    }

    /**
     * At that level the exposing call emits nothing, and at any lower level it emits the message text.
     *
     * <p>Purpose: this is the behavioural half. The two halves above establish that the configuration
     * names the right logger with a token the framework accepts; this one establishes what that level
     * does to the call the sink actually makes, and — through its negative control — that the exposure
     * being suppressed is real.</p>
     *
     * <p>Refactoring Rationale: the negative control is part of the same test rather than a separate one
     * on purpose. A suppression assertion that passes because the appender was never wired, or because
     * the call shape does not render the throwable after all, is worse than no assertion: it reports a
     * control that is not there. Driving the identical call at a level that permits it, and finding the
     * card-number-shaped text in the captured event, is what rules both of those out.</p>
     *
     * <p>Assumptions: the level is applied directly through the logging implementation rather than by
     * initialising the framework's logging system. What the framework does with a bound level is its own
     * behaviour; what this class owns is the claim that at {@code OFF} this particular call publishes
     * nothing, and that is asserted against the level the shipped file names rather than a level chosen
     * here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("at off the sink call publishes nothing, and at error it publishes the message text")
    void theSuppressedLevelWithholdsTheMessageTextThatErrorWouldPublish() {
        ch.qos.logback.classic.Logger sinkLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SINK_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        sinkLogger.addAppender(captured);
        // WHY : Assumptions: the prior level may legitimately be null, which is not "no level" but
        //   "inherit from the parent", and restoring null is what puts the logger back into that state.
        //   Substituting a concrete default here would leave the logger pinned where it had previously
        //   been inheriting -- a different configuration that happens to look the same in this class.
        Level previousLevel = sinkLogger.getLevel();
        try {
            sinkLogger.setLevel(Level.toLevel(EXPECTED_LEVEL));
            sinkLogger.error(SINK_TEMPLATE, "b7b3a1e4", new IllegalStateException(SENSITIVE_TEXT));

            assertThat(captured.list)
                    .as("at the level the shared defaults set, the starter's failure line must not reach"
                            + " an appender at all")
                    .isEmpty();

            // WHY : Refactoring Rationale: this is the negative control, and it is what makes the
            //   assertion above mean something. It drives the IDENTICAL call at a level that permits it
            //   and finds the exception's own message in the captured event, which proves three things at
            //   once: the appender is really attached, this call shape really does carry the throwable,
            //   and the throwable's message text really is reachable from a log record. Without it, an
            //   empty list would be equally consistent with a control that does nothing.
            sinkLogger.setLevel(Level.ERROR);
            sinkLogger.error(SINK_TEMPLATE, "b7b3a1e4", new IllegalStateException(SENSITIVE_TEXT));

            assertThat(captured.list)
                    .as("the exposure this suppression exists for has to be demonstrable, or the"
                            + " suppression is being asserted against nothing")
                    .hasSize(1);
            assertThat(captured.list.get(0).getThrowableProxy())
                    .as("the trailing throwable argument is what carries the message text into the record")
                    .isNotNull();
            assertThat(captured.list.get(0).getThrowableProxy().getMessage())
                    .as("this is the text a driver, codec or validator would have written, and on these"
                            + " queues it is where a primary account number arrives")
                    .contains(SENSITIVE_TEXT);
        } finally {
            sinkLogger.detachAppender(captured);
            captured.stop();
            sinkLogger.setLevel(previousLevel);
        }
    }
}
