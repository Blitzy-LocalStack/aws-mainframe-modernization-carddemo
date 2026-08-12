package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the extract-load, unload and purge diagnostics name no account or customer identifier.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: seven log templates and one throwable message in {@code LoadService} and
 * {@code PurgeJob} rendered the account identifier of the row they were describing. The logging contract in
 * {@code docs/architecture/observability.md} directs that such a value is OMITTED rather than abbreviated
 * or tokenised, so each was rewritten to locate the fault by the record's position in the extract, or by a
 * count, instead. Nothing prevented the value from coming back: no test reads a log line, and the two
 * classes are exercised through their database effects rather than their output. This test reads the two
 * sources and asserts the property directly, which is the only place the omission can be made to fail
 * visibly rather than silently regress.</p>
 *
 * <p>Assumptions: a disclosing member is one whose expression names an account or customer identifier in
 * any spelling this project uses -- {@code accountId}, {@code lastAccountId}, {@code getAccountId},
 * {@code customerId}, {@code getCustomerId} -- so the pattern matches the two nouns rather than an
 * enumerated list of accessors that a new call site could sidestep by inventing an eighth spelling.</p>
 *
 * <p>Assumptions: the authorization date and time complements ARE admitted, because the contract's own list
 * of what a diagnostic may still carry names a date, and the pair is what says WHICH authorization beneath
 * a summary was the duplicate. Excluding them would have forced the two duplicate-detection lines to carry
 * nothing at all.</p>
 *
 * <p>Alternatives Considered: capturing the logger with an appender and asserting the formatted output.
 * Rejected because it proves the property only for the code paths a test happens to drive -- the refusal
 * path here needs a detail extract with an unresolvable parent, the progress path needs enough windows to
 * cross the log frequency -- so a template on an undriven path would stay unasserted, which is exactly the
 * state that let the identifiers stand. Reading the source asserts every template unconditionally.</p>
 *
 * <p>⚠️ Refactoring Rationale: {@code UnloadService} is the THIRD class this asserts over, and its
 * absence from the first two is why review found two disclosing templates in it after the same finding had
 * been closed for its two siblings. One template reported the walk's resume key, which is an account
 * identifier, and the other reported the customer identifier of a row it had passed over -- and carried a
 * paragraph ARGUING for it, on the ground that the account identifier was the value that was absent so the
 * customer identifier was the only handle left. A test naming its subjects one at a time is a test that
 * cannot see the class nobody added, so this covers the whole set of sources that walk the two tables and
 * report on rows: the loader, the unloader and the purge. Assumptions: the set is enumerated rather than
 * globbed because a glob over the service package would also reach the request listener and the outbox,
 * whose admissible complements are a card number's masked form and a message identity rather than a record
 * ordinal, and a single rule over both groups could only hold by admitting what this one forbids.</p>
 *
 * <p>Alternatives Considered: an ArchUnit rule in {@code common-lib} applied to every service. Rejected
 * for now because the admissible complements differ by context -- an authorization is keyed by date and
 * time, a transaction by its identifier -- so a single repository-wide rule would need a per-class
 * exception list to express what this file states in one place for the two classes the finding named.</p>
 *
 * <p>Trade-offs: this test reads a file by a path rather than through the classpath, which its sibling
 * contract tests avoid. It is unavoidable here for the same reason as in
 * {@code com.carddemo.authorization.config.EnvironmentClosureTest}: the subject is source text, and a
 * compiled class no longer distinguishes a template that named an identifier from one that never did. The
 * path is resolved from the working directory upward so it holds whether the build runs from this module or
 * from the aggregator.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class AuthorizationDiagnosticDisclosureTest {

    /**
     * Matches an expression that names an account or customer identifier.
     *
     * <p>Assumptions: matching the two nouns with an optional separator covers every spelling in use --
     * camel case, a getter, a qualified field and the snake case a column name would bring -- and does so
     * without enumerating accessors. The alternative, a list of exact identifiers, stops holding the moment
     * a new call site names the value differently, which is the failure this test exists to prevent.</p>
     */
    private static final Pattern DISCLOSING_MEMBER =
            Pattern.compile("(?i)(account|customer)[ _]?id");

    /** Matches the opening of a logger invocation at any level. */
    private static final Pattern LOG_CALL = Pattern.compile("LOG\\.\\w+\\(");

    /** The main source that loads the two pending-authorization extracts. */
    private static final String LOAD_SERVICE = "LoadService.java";

    /** The main source that purges expired pending authorizations. */
    private static final String PURGE_JOB = "PurgeJob.java";

    /** The main source that writes both pending-authorization extracts back out. */
    private static final String UNLOAD_SERVICE = "UnloadService.java";

    /**
     * Every main source whose diagnostics this class governs.
     *
     * <p>Assumptions: declared once and read by both of the two set-wide cases below, so a fourth source
     * added here is covered by both rather than by whichever case its author remembered. That is the
     * failure this constant exists to prevent: the omission assertion and the still-logs assertion were
     * two independently written literals, and the class that went uncovered went uncovered in both.</p>
     */
    private static final List<String> GOVERNED_SOURCES =
            List.of(LOAD_SERVICE, PURGE_JOB, UNLOAD_SERVICE);

    /**
     * No log template in either class renders an account or customer identifier.
     *
     * @throws AssertionError if any logger invocation names one of the two identifiers
     */
    @Test
    @DisplayName("no load, unload or purge log template names an account or customer identifier")
    void noLogTemplateNamesAnAccountOrCustomer() {
        for (String source : GOVERNED_SOURCES) {
            List<String> disclosing = new ArrayList<>();
            for (String call : loggerInvocations(source)) {
                if (DISCLOSING_MEMBER.matcher(call).find()) {
                    disclosing.add(call);
                }
            }
            assertThat(disclosing)
                    .as("logger invocations in %s that name an account or customer identifier", source)
                    .isEmpty();
        }
    }

    /**
     * Both classes still log, so an emptied file cannot pass the omission assertion by having no output.
     *
     * <p>Assumptions: the count is asserted as a lower bound rather than an exact figure, because a new
     * diagnostic is a legitimate addition whereas the disappearance of all of them is not. Without this
     * the first assertion would hold vacuously for a class whose logging had been deleted wholesale.</p>
     *
     * @throws AssertionError if either class has fewer logger invocations than it is known to have
     */
    @Test
    @DisplayName("both classes still emit the diagnostics whose contents are asserted")
    void bothClassesStillEmitDiagnostics() {
        assertThat(loggerInvocations(LOAD_SERVICE))
                .as("logger invocations in %s", LOAD_SERVICE)
                .hasSizeGreaterThanOrEqualTo(4);
        assertThat(loggerInvocations(PURGE_JOB))
                .as("logger invocations in %s", PURGE_JOB)
                .hasSizeGreaterThanOrEqualTo(6);
        assertThat(loggerInvocations(UNLOAD_SERVICE))
                .as("logger invocations in %s", UNLOAD_SERVICE)
                .hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * Every governed source is a file, so a renamed subject cannot make the set-wide cases vacuous.
     *
     * <p>Assumptions: {@link #loggerInvocations(String)} raises on an unreadable path, so a renamed source
     * already fails the two cases above rather than passing them empty. This case is the direct statement
     * of the same property, because the failure it produces names the missing file instead of naming an
     * empty collection of logger invocations -- and the diagnosis of the two is a minute apart.</p>
     *
     * @throws AssertionError if any governed source is absent
     */
    @Test
    @DisplayName("every source this class governs is present")
    void everyGovernedSourceIsPresent() {
        for (String source : GOVERNED_SOURCES) {
            assertThat(strippedSource(source))
                    .as("the source text of %s", source)
                    .isNotBlank();
        }
        assertThat(GOVERNED_SOURCES)
                .as("the sources whose diagnostics this class governs")
                .containsExactly(LOAD_SERVICE, PURGE_JOB, UNLOAD_SERVICE);
    }

    /**
     * The unload diagnostics report run-local counts where they once reported two identifiers.
     *
     * <p>Assumptions: this is asserted as a positive requirement and not only as the absence above,
     * because withdrawing an identifier from a diagnostic that then says nothing useful trades one defect
     * for another. The skip line has to locate the occurrence WITHIN the run, which its ordinal does, and
     * the terminating line has to say how far the run had got, which its three counts do.</p>
     *
     * @throws AssertionError if either unload diagnostic loses its run-local replacement
     */
    @Test
    @DisplayName("the unload diagnostics report a run-local ordinal and run-local counts")
    void theUnloadDiagnosticsReportRunLocalValues() {
        List<String> skips = new ArrayList<>();
        List<String> endings = new ArrayList<>();
        for (String call : loggerInvocations(UNLOAD_SERVICE)) {
            if (call.contains("root-skipped-unexportable")) {
                skips.add(call);
            }
            if (call.contains("walk-ended-without-resume-key")) {
                endings.add(call);
            }
        }
        assertThat(skips).as("unload skip diagnostics").hasSize(1);
        assertThat(skips.get(0)).contains("skippedOrdinal={}");
        assertThat(endings).as("unload walk-termination diagnostics").hasSize(1);
        assertThat(endings.get(0))
                .contains("rootsWritten={}")
                .contains("childrenWritten={}")
                .contains("rootsSkipped={}");
    }

    /**
     * Every load diagnostic that reports a skipped or refused record still locates that record.
     *
     * <p>Assumptions: withdrawing the identifier is only half of the remedy -- a diagnostic that names
     * nothing at all is unusable to the operator holding the extract, so the record's ordinal position took
     * the identifier's place and this asserts it is still there. The two halves together are what make the
     * rewrite reviewable: one forbids the value, the other forbids an empty message.</p>
     *
     * <p>⚠️ Refactoring Rationale: the expected count moved from three to four when the extract load began
     * RECORDING a refused record rather than letting the refusal propagate unlogged. It is asserted as an
     * exact figure and not a lower bound, deliberately, because the loop below only checks the templates it
     * finds: a fifth record-scope template added without an ordinal would fail, but one added and then
     * silently REMOVED would leave the remaining four passing, and an exact figure is what makes that
     * removal visible. Raising this number is the correct response to a new located diagnostic; lowering it
     * is a review question.</p>
     *
     * <p>⚠️ Assumptions: the diagnostics are PARTITIONED by scope rather than tested by one rule, and the
     * file-scope arm asserts the ordinal is ABSENT. When an extract's total length is not a multiple of its
     * stride every record in it may be well formed, so an ordinal on that line would send a reader to
     * inspect bytes that are correct -- inventing one is the defect, not the remedy. Writing this as a
     * single rule over every line mentioning a refusal would have forced the file-scope line either to
     * carry a fabricated ordinal or to be renamed around the assertion, and both would have made the rule
     * weaker than it looks.</p>
     *
     * @throws AssertionError if a record-scope diagnostic carries no record ordinal, or if a file-scope
     *     diagnostic invents one
     */
    @Test
    @DisplayName("record-scope load diagnostics name the ordinal and file-scope diagnostics do not")
    void eachSkipOrRefusalDiagnosticNamesTheRecordOrdinal() {
        List<String> located = new ArrayList<>();
        List<String> fileScope = new ArrayList<>();
        for (String call : loggerInvocations(LOAD_SERVICE)) {
            if (call.contains("extract-refused")) {
                fileScope.add(call);
                continue;
            }
            if (call.contains("skipped") || call.contains("refused")) {
                assertThat(call)
                        .as("a skip or refusal diagnostic that does not locate its record")
                        .contains("recordOrdinal={}");
                located.add(call);
            }
        }
        assertThat(located)
                .as("record-scope skip and refusal diagnostics in %s", LOAD_SERVICE)
                .hasSize(4);
        assertThat(fileScope)
                .as("file-scope refusal diagnostics in %s", LOAD_SERVICE)
                .hasSize(1);
        assertThat(fileScope.get(0))
                .as("no record is at fault in a whole-file refusal, so none may be named")
                .doesNotContain("recordOrdinal")
                .contains("fault={}")
                .contains("detail={}");
    }

    /**
     * The refused-record diagnostic names both the fault's type chain and its redacted condition.
     *
     * <p>⚠️ Purpose: an ordinal alone locates the record but says nothing about what was wrong with it,
     * which was the second half of the same complaint -- an operator holding a rejected extract had a
     * position and a type name and no statement of the condition. This asserts the line still carries both
     * complements: the type chain, which says where the refusal was raised, and the condition, which says
     * what the record violated.</p>
     *
     * <p>⚠️ Assumptions: the condition is asserted to be rendered through the DIGIT-REDACTING summary and
     * not the plain one, and that distinction is the whole safety of the line. A record refusal is composed
     * by a mapper reading that record's own bytes, so its message can quote the eleven-digit account
     * identifier the record carries -- a value this context's observability contract names as inadmissible
     * in a durable diagnostic, and the value every other case in this class exists to keep out. The plain
     * renderer masks a sixteen-digit card number and nothing narrower, so it would have admitted exactly
     * that identifier. Asserting the renderer by name is what stops a later edit from reaching for the
     * shorter method and reintroducing the disclosure with every source-text assertion above still green.
     * </p>
     *
     * <p>⚠️ Assumptions: this also asserts that NO governed source renders a failure through the plain
     * renderer, rather than only checking the one line that exists today. The rule being protected is a
     * property of the whole group -- these three classes walk the two tables and report on rows -- so a
     * second reporting site added to any of them must reach for the redacting form too, and a case scoped
     * to a single template could not say so.</p>
     *
     * @throws AssertionError if the refusal diagnostic loses either complement, or if any governed source
     *     renders a failure through the plain summary renderer
     */
    @Test
    @DisplayName("the refused-record diagnostic names the fault chain and its redacted condition")
    void theRefusedRecordDiagnosticNamesTheFaultAndItsRedactedCondition() {
        List<String> refusals = new ArrayList<>();
        for (String call : loggerInvocations(LOAD_SERVICE)) {
            if (call.contains("authorization.load.record-refused")) {
                refusals.add(call);
            }
        }
        assertThat(refusals).as("refused-record diagnostics in %s", LOAD_SERVICE).hasSize(1);
        assertThat(refusals.get(0))
                .contains("fault={}")
                .contains("detail={}")
                .contains("ThrowableDigest.of(")
                .contains("FailureSummary.redactedOf(");

        for (String source : GOVERNED_SOURCES) {
            assertThat(strippedSource(source))
                    .as("%s must render a failure through the redacting summary, never the plain one",
                            source)
                    .doesNotContain("FailureSummary.of(");
        }
    }

    /**
     * The purge progress diagnostic reports counts where it once reported the account it had reached.
     *
     * @throws AssertionError if the progress diagnostic carries neither count
     */
    @Test
    @DisplayName("the purge progress diagnostic reports counts rather than a position")
    void thePurgeProgressDiagnosticReportsCounts() {
        List<String> progress = new ArrayList<>();
        for (String call : loggerInvocations(PURGE_JOB)) {
            if (call.contains("purge progress")) {
                progress.add(call);
            }
        }
        assertThat(progress).as("purge progress diagnostics").hasSize(1);
        assertThat(progress.get(0))
                .contains("summariesRead={}")
                .contains("summariesDeleted={}");
    }

    /**
     * The unresolvable-parent refusal message names no account, and still locates the offending record.
     *
     * <p>Assumptions: the exception's {@code accountId} field and its accessor are deliberately NOT
     * asserted against, because a caller reading a typed value in memory discloses nothing -- it is the
     * message, which a log or a bug report carries indefinitely, that the contract governs.</p>
     *
     * @throws AssertionError if the refusal message names an account or omits the record ordinal
     */
    @Test
    @DisplayName("the unresolvable-parent refusal message locates the record without naming the account")
    void theUnresolvableParentRefusalNamesNoAccount() {
        String source = strippedSource(LOAD_SERVICE);
        int declaration = source.indexOf("class UnresolvedParentException");
        assertThat(declaration)
                .as("the declaration of UnresolvedParentException in %s", LOAD_SERVICE)
                .isNotNegative();
        String body = source.substring(declaration);
        int superCall = body.indexOf("super(");
        assertThat(superCall)
                .as("the message expression of UnresolvedParentException")
                .isNotNegative();
        String message = balancedFrom(body, superCall + "super".length());
        assertThat(DISCLOSING_MEMBER.matcher(message).find())
                .as("the refusal message expression %s names an identifier", message)
                .isFalse();
        assertThat(message).contains("record ");
    }

    /**
     * Extracts every logger invocation from a main source, with comments and Javadoc removed.
     *
     * @param simpleName the file name of the main source to read; must not be {@code null}
     * @return every {@code LOG.<level>(...)} invocation in declaration order, never {@code null}
     * @throws UncheckedIOException if the source cannot be read
     */
    private static List<String> loggerInvocations(String simpleName) {
        String source = strippedSource(simpleName);
        List<String> invocations = new ArrayList<>();
        Matcher matcher = LOG_CALL.matcher(source);
        while (matcher.find()) {
            int openIndex = matcher.end() - 1;
            invocations.add(source.substring(matcher.start(), openIndex)
                    + balancedFrom(source, openIndex));
        }
        return invocations;
    }

    /**
     * Returns the parenthesised group that starts at the given position, braces balanced.
     *
     * <p>Assumptions: balancing rather than matching to the first closing parenthesis is required because
     * every template here passes arguments that themselves contain calls, so a first-match scan would cut
     * the invocation short and hide whichever argument followed -- which in three cases was precisely the
     * argument under assertion.</p>
     *
     * @param source the text to scan; must not be {@code null}
     * @param openIndex the index of the opening parenthesis
     * @return the group including both parentheses, never {@code null}
     * @throws IllegalStateException if the parentheses do not balance before the end of the text
     */
    private static String balancedFrom(String source, int openIndex) {
        int depth = 0;
        for (int index = openIndex; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(openIndex, index + 1);
                }
            }
        }
        throw new IllegalStateException("unbalanced parentheses from index " + openIndex);
    }

    /**
     * Reads a main source of this module with its comments and Javadoc removed.
     *
     * <p>Assumptions: only whole-line comments and block comments are removed, and no attempt is made to
     * parse a comment marker occurring inside a string literal. That is deliberate: a template containing
     * a URL would be corrupted by a naive stripper, whereas a line whose first non-blank characters open a
     * comment cannot be part of a literal. The removal is needed because this class's own explanatory prose
     * names the withdrawn members, and a scan over the raw text would match itself.</p>
     *
     * @param simpleName the file name of the main source to read; must not be {@code null}
     * @return the source text without comments, never {@code null}
     * @throws UncheckedIOException if the source cannot be read
     */
    private static String strippedSource(String simpleName) {
        Path path = repositoryRoot()
                .resolve("services/authorization-service/src/main/java/com/carddemo/authorization/service")
                .resolve(simpleName);
        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("unable to read " + path, failure);
        }
        StringBuilder stripped = new StringBuilder(raw.length());
        boolean inBlock = false;
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (inBlock) {
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlock = true;
                }
                continue;
            }
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        return stripped.toString();
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * <p>Assumptions: the root is the first ancestor holding both {@code services} and {@code docs}, which
     * identifies it whether the build starts in this module or in the aggregator, and does so without
     * assuming a fixed depth.</p>
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds both directories
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("services"))
                    && Files.isDirectory(candidate.resolve("docs"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory holds both services/ and docs/");
    }
}
