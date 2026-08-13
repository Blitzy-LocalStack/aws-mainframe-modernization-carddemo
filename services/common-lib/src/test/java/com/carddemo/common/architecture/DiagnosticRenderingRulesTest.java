package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every production record that carries a protected value to declaring its own diagnostic rendering.
 *
 * <h2>Why this gate exists</h2>
 *
 * <p>Refactoring Rationale: {@code docs/architecture/observability.md} states what a {@code toString()}
 * may render -- a prohibited value is omitted rather than abbreviated, a primary account number appears
 * only through the shared masker and only where a rendering has no other way to name its row, and what
 * remains is identity that discloses nothing. That prose had no executable counterpart, and the review
 * that prompted this class found the predictable result: across nine modules, records carrying account
 * identifiers, cardholder names, postal addresses, telephone numbers, monetary amounts, balances and
 * merchant free text relied on the rendering the compiler generates, which prints every component. Two
 * card shapes had additionally acquired a rendering that ran the account identifier through the CARD
 * masker, which preserves input width and keeps the last four characters, so each of them disclosed four
 * digits of an account on every line it produced -- while the tests over them, which searched for whole
 * values, passed.
 *
 * <p>Assumptions: a rule that is read cannot hold a hundred and eighty records. The rule is therefore
 * evaluated here, over the source of every module, so that a record ADDED later with a protected
 * component fails the build unless its author decides what its rendering discloses. It is a repository-wide
 * check that happens to be hosted in one module, not a per-module check.
 *
 * <h2>What this gate does and does not decide</h2>
 *
 * <p>Assumptions: this gate requires a DECISION to be recorded; it does not make the decision. It cannot,
 * because whether a fixed-point decimal is a cardholder's balance or a seeded reference rate is a question
 * about meaning rather than about type -- {@code com.carddemo.reference.dto.DisclosureGroupRateResponse}
 * renders its rate deliberately and says why, while {@code com.carddemo.batch.dto.BatchRunSummary}
 * withholds nothing and says why. What the gate can establish mechanically is that no record carrying such
 * a component is left with the rendering nobody wrote.
 *
 * <p>Trade-offs: the subject set is chosen by a NAME and TYPE vocabulary, which necessarily errs. It errs
 * towards demanding a renderer: a component named for a postal code in a reference lookup row is flagged
 * even though it identifies nobody, and the answer is a renderer whose documentation explains that -- see
 * {@code com.carddemo.reference.dto.UsStateZipPrefixResponse}. The opposite error, a protected component
 * whose name the vocabulary misses, is the residual gap and is why each module also carries its own
 * negative-disclosure tests over the shapes it owns.
 *
 * <h2>Why the source and not the classpath</h2>
 *
 * <p>Alternatives Considered: importing compiled classes, as {@code LayeringRulesTest} does, and using
 * reflection over record components. Rejected for this gate, and the reason is a property of how this
 * package is delivered rather than a preference. Only {@code LayeringRulesTest} is carried into the eight
 * services: the {@code architecture-rules} Surefire execution in {@code services/pom.xml} includes that one
 * file by literal name, so THIS class runs exactly ONCE, inside the shared kernel. A classpath-based rule
 * running there would import the kernel's own classes and nothing else, and would gate one module while
 * appearing to gate nine. Reading the source of every module makes one execution cover the whole reactor,
 * and it lets a failure name the FILE, which is what an author needs, rather than a binary name.
 *
 * <p>Measured: the reactor log confirms the delivery asymmetry rather than assuming it --
 * {@code LayeringRulesTest} appears against nine modules and this class against one. Adding this class to
 * that include list was considered and rejected: nine executions of a rule that already reads all nine
 * source trees would repeat identical work, and that include carries a documented anti-drift argument for
 * naming exactly one file.
 *
 * <p>Measured: both rules were confirmed to discriminate before this class was accepted. Removing the
 * rendering from {@code com.carddemo.authorization.dto.PendingAuthRowView} failed the first rule with
 * exactly one entry -- {@code PendingAuthRowView carries [amount, cardNum]}, naming the file -- and left
 * the second rule green; restoring the card masker over the account identifier in
 * {@code com.carddemo.card.dto.CardSummary} failed the second rule with exactly one entry --
 * {@code CardNumberMasker.mask(accountId)}, naming the file -- and left the first rule green, because that
 * record still declared a rendering. Each rule therefore fails for its own reason and neither stands in for
 * the other.
 *
 * <p>Assumptions: the source is sanitised before it is matched, by a state machine that blanks comment
 * bodies and string-literal contents while preserving every offset. Both are necessary and neither covers
 * the other: without the comment pass a record named in prose would be counted as a declaration, and
 * without the string pass a literal containing a slash pair -- a URL, a masked pattern -- would truncate
 * the file at that point and hide every declaration after it.
 */
class DiagnosticRenderingRulesTest {

    /** A file that exists only at the repository root, used to recognise it while walking upwards. */
    private static final String ROOT_MARKER = "services/pom.xml";

    /** The directory under the repository root holding the nine Maven modules. */
    private static final String SERVICES_DIRECTORY = "services";

    /**
     * The component-name fragments that mark a value as protected, matched case-insensitively.
     *
     * <p>Assumptions: each fragment corresponds to a category
     * {@code docs/architecture/observability.md} L1093 to L1112 names -- an account or customer
     * identifier, a card number, a card-verification value, a national or government identifier, a limit,
     * a balance, an amount, a cardholder name, a postal address, a telephone number, a credit score -- or
     * to a value whose disclosure that section's own reasoning covers, which is why a derived card
     * fingerprint is here: it is a confirmable token over a sixteen-digit input, and the shared kernel's
     * analysis of unkeyed digests is that such a token discloses the value it was meant to withhold.</p>
     *
     * <p>Trade-offs: fragments rather than exact names, because the same value is spelled several ways
     * across the reference layouts -- {@code cardNum}, {@code cardNumber}, {@code displayCardNumber} -- and
     * an exact-name list would have to be extended for each spelling while a fragment list would not. The
     * cost is the false positives recorded on the class above.</p>
     */
    private static final List<String> PROTECTED_NAME_FRAGMENTS = List.of(
            "accountid", "acctid", "customerid", "custid", "cardnum", "pan", "cvv", "ssn",
            "nationalid", "governmentid", "balance", "limit", "amount", "amt", "address", "phone",
            "zip", "city", "creditscore", "fingerprint", "password", "secret",
            "firstname", "middlename", "lastname", "customername", "merchantname", "holdername",
            "embossedname");

    /**
     * The component types that mark a value as protected or as unbounded, matched as whole words.
     *
     * <p>Assumptions: the two fixed-point decimal types are here because this migration holds every exact
     * decimal in one of them, so the type is evidence that a value MIGHT be money -- not that it is, which
     * is the judgement each renderer records. The four collection shapes are here for a different reason:
     * a rendering whose length is a function of the data rather than of the type is unbounded whatever it
     * contains, and the review found a page envelope, a hundred-field error list and a thousand-element
     * maintenance batch all rendered element by element.</p>
     */
    private static final List<String> PROTECTED_OR_UNBOUNDED_TYPES = List.of(
            "Money", "BigDecimal", "List", "Set", "Map", "Collection", "PageResponse");

    /**
     * The lowest number of record declarations a healthy walk finds.
     *
     * <p>Assumptions: this is an anti-vacuity floor and not a census. A rule evaluated over an empty
     * subject set passes every assertion it makes, and the log of such a run is indistinguishable from the
     * log of a run that checked every module -- so a walker broken by a moved directory, a changed root
     * marker or a sanitiser that blanked whole files would report success. The floor is set well below the
     * figure measured when this gate was written, so adding or removing a record never requires an edit
     * here, while a walk that suddenly sees a fraction of the tree fails.</p>
     */
    private static final int MINIMUM_RECORDS_SCANNED = 140;

    /**
     * The lowest number of production compilation units a healthy walk finds.
     *
     * <p>Assumptions: this guards the file walk specifically, which the record floor does not. A
     * sanitiser that blanked every file would leave the file count intact and the record count at zero, and
     * a walk that resolved the wrong directory would leave both at zero; two floors distinguish the two
     * failures instead of leaving one to be inferred.</p>
     */
    private static final int MINIMUM_FILES_SCANNED = 400;

    /**
     * The misuse this gate refuses by name: the card masker applied to a non-card identifier.
     *
     * <p>Assumptions: the identifier fragments are matched inside the argument expression rather than the
     * whole argument being enumerated, so {@code accountId}, {@code this.accountId} and
     * {@code candidateAccountId} are all caught. The pattern deliberately does NOT forbid the masker on a
     * card number, which is the one abbreviation the observability rule sanctions; what it forbids is
     * reusing that function on a value the same rule requires to be omitted.</p>
     */
    private static final Pattern CARD_MASKER_ON_NON_CARD_IDENTIFIER = Pattern.compile(
            "CardNumberMasker\\s*\\.\\s*mask\\s*\\(\\s*[A-Za-z0-9_.]*"
                    + "(?:[Aa]ccountId|[Cc]ustomerId|[Aa]cctId|[Cc]ustId)[A-Za-z0-9_.]*\\s*\\)");

    /** Matches the head of a record declaration, up to and including its opening parenthesis. */
    private static final Pattern RECORD_HEAD = Pattern.compile("\\brecord\\s+(\\w+)\\s*\\(");

    /**
     * One record declaration found in the source tree, with the components that require a decision.
     *
     * @param file the compilation unit the declaration was found in, for a failure to name
     * @param name the record's simple name
     * @param flaggedComponents the components whose name or type marks them protected or unbounded
     * @param declaresRendering whether the record's own body declares {@code toString()}
     */
    private record RecordDeclaration(Path file, String name, List<String> flaggedComponents,
            boolean declaresRendering) {

        /**
         * Renders this declaration as a failure line naming the file, the record and what it carries.
         *
         * <p>Assumptions: the components are named. A failure saying only that a record needs a renderer
         * leaves its author to re-derive which component provoked it, and the vocabulary that flagged it is
         * in this test rather than in the file being fixed.</p>
         *
         * @return a single line identifying the declaration and its flagged components; never {@code null}
         */
        @Override
        public String toString() {
            return file + ": record " + name + " carries " + flaggedComponents;
        }
    }

    /**
     * Asserts that every production record carrying a protected or unbounded component renders itself.
     *
     * <p>Assumptions: the assertion is stated as an empty-collection assertion over the offenders rather
     * than as one assertion per record, so a single run names every record still needing a decision instead
     * of stopping at the first.</p>
     */
    @Test
    @DisplayName("every record carrying a protected component declares its own rendering")
    void everyRecordCarryingAProtectedComponentDeclaresItsOwnRendering() {
        List<RecordDeclaration> declarations = scanRecordDeclarations();

        List<String> offenders = declarations.stream()
                .filter(declaration -> !declaration.flaggedComponents().isEmpty())
                .filter(declaration -> !declaration.declaresRendering())
                .map(RecordDeclaration::toString)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("a record's generated rendering prints every component, so a record carrying a"
                        + " protected or unbounded one must declare a rendering that states what it"
                        + " discloses and why -- see docs/architecture/observability.md")
                .isEmpty();
    }

    /**
     * Asserts that no production source applies the card masker to an account or customer identifier.
     *
     * <p>Refactoring Rationale: this case exists because two card response shapes did exactly that, and
     * because no test could see it. Every assertion over those renderings searched for the WHOLE
     * identifier, which a mask that keeps the last four characters does not contain, so the defect was
     * invisible to a suite of passing tests. A search of the source for the misuse itself is decidable and
     * cannot be satisfied vacuously.</p>
     *
     * <p>Assumptions: this is a source-text rule rather than a dependency rule, because what is wrong is
     * not that these files reference the masker -- several legitimately mask a card number -- but which
     * ARGUMENT is passed to it, and an argument is not part of any dependency graph.</p>
     */
    @Test
    @DisplayName("no rendering abbreviates an account or customer identifier through the card masker")
    void noRenderingAbbreviatesAnIdentifierThroughTheCardMasker() {
        List<String> offenders = new ArrayList<>();

        for (Path file : productionSources()) {
            Matcher matcher = CARD_MASKER_ON_NON_CARD_IDENTIFIER.matcher(sanitise(read(file)));
            while (matcher.find()) {
                offenders.add(file + ": " + matcher.group());
            }
        }

        assertThat(offenders)
                .as("the shared masker preserves input width and keeps the last four characters, so"
                        + " applying it to an eleven-digit identifier discloses four digits of it; the"
                        + " observability rule requires such a value to be omitted, and confines that"
                        + " function to a primary account number")
                .isEmpty();
    }

    /**
     * Asserts that the walk and the sanitiser both saw the tree, so neither rule above can pass vacuously.
     *
     * <p>Assumptions: two floors rather than one, for the reason each constant records: a walk that
     * resolves the wrong directory and a sanitiser that blanks every file are different failures, and one
     * combined floor would report them identically.</p>
     */
    @Test
    @DisplayName("the source walk and the sanitiser both have subjects")
    void theSourceWalkHasSubjects() {
        List<Path> files = productionSources();
        List<RecordDeclaration> declarations = scanRecordDeclarations();

        assertThat(files)
                .as("no production compilation unit was found, so both rules above examined nothing")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_FILES_SCANNED);
        assertThat(declarations)
                .as("no record declaration was recognised in a tree of %d files, so the sanitiser or the"
                        + " declaration pattern is broken rather than the tree being empty", files.size())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_RECORDS_SCANNED);
        assertThat(declarations)
                .as("the census must contain records that DO carry a protected component, or the first"
                        + " rule is trivially satisfied by a subject set of none")
                .anyMatch(declaration -> !declaration.flaggedComponents().isEmpty());
    }

    /**
     * Finds every record declaration in the production source of every module.
     *
     * @return one entry per record declaration, nested declarations included; never {@code null}
     */
    private static List<RecordDeclaration> scanRecordDeclarations() {
        List<RecordDeclaration> declarations = new ArrayList<>();
        for (Path file : productionSources()) {
            String source = sanitise(read(file));
            Matcher head = RECORD_HEAD.matcher(source);
            while (head.find()) {
                int openParen = head.end() - 1;
                int closeParen = matchingDelimiter(source, openParen, '(', ')');
                int bodyOpen = source.indexOf('{', closeParen);
                if (bodyOpen < 0) {
                    continue;
                }
                declarations.add(new RecordDeclaration(file, head.group(1),
                        flaggedComponents(source.substring(openParen + 1, closeParen)),
                        declaresRendering(source, bodyOpen)));
            }
        }
        return declarations;
    }

    /**
     * Selects the components of one record header whose name or type requires a rendering decision.
     *
     * <p>Assumptions: the header is split on TOP-LEVEL commas only, because a component's type may itself
     * contain commas -- a map of string to long is one component, not two -- and a naive split would read
     * the second half of such a type as a component whose name is a type name.</p>
     *
     * @param header the text between the record's parentheses, already sanitised
     * @return the flagged component names, in declaration order; never {@code null}
     */
    private static List<String> flaggedComponents(String header) {
        List<String> flagged = new ArrayList<>();
        for (String component : splitTopLevel(header)) {
            String collapsed = component.replaceAll("@\\w+(\\s*\\([^()]*\\))?", " ")
                    .replaceAll("\\s+", " ").trim();
            int lastSpace = collapsed.lastIndexOf(' ');
            if (lastSpace < 0) {
                continue;
            }
            String name = collapsed.substring(lastSpace + 1);
            String type = collapsed.substring(0, lastSpace);
            if (isProtectedName(name) || isProtectedOrUnboundedType(type)) {
                flagged.add(name);
            }
        }
        return flagged;
    }

    /**
     * Reports whether a component name contains one of the protected fragments.
     *
     * @param name the component name as declared
     * @return {@code true} when the name marks the component protected
     */
    private static boolean isProtectedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return PROTECTED_NAME_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    /**
     * Reports whether a component type is a fixed-point decimal, a collection or a page envelope.
     *
     * <p>Assumptions: the type is matched as a whole WORD and an array suffix is matched separately, so a
     * type merely containing one of the words -- a class named {@code MoneyModule}, say -- is not flagged
     * while {@code Money}, {@code List&lt;X&gt;} and {@code String[]} all are.</p>
     *
     * @param type the component type as declared, annotations already removed
     * @return {@code true} when the type marks the component protected or its rendering unbounded
     */
    private static boolean isProtectedOrUnboundedType(String type) {
        if (type.contains("[]")) {
            return true;
        }
        return PROTECTED_OR_UNBOUNDED_TYPES.stream()
                .anyMatch(candidate -> Pattern.compile("\\b" + candidate + "\\b").matcher(type).find());
    }

    /**
     * Reports whether the record whose body opens at {@code bodyOpen} declares {@code toString()} itself.
     *
     * <p>Assumptions: only a declaration at the body's OWN nesting level counts. A record may nest another
     * record, and the nested one's rendering must not satisfy the rule for its enclosing type -- which is
     * exactly the case for the two seam clients, whose outer types nest several wire records.</p>
     *
     * @param source the sanitised compilation unit
     * @param bodyOpen the index of the brace opening the record body
     * @return {@code true} when the record's own body declares a rendering
     */
    private static boolean declaresRendering(String source, int bodyOpen) {
        int depth = 0;
        for (int index = bodyOpen + 1; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (depth == 0 && source.startsWith("public String toString()", index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a record header on the commas that separate components, ignoring nested ones.
     *
     * @param header the text between the record's parentheses
     * @return the component declarations, in order; never {@code null}
     */
    private static List<String> splitTopLevel(String header) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (character == '<' || character == '(' || character == '[') {
                depth++;
            } else if (character == '>' || character == ')' || character == ']') {
                depth--;
            } else if (character == ',' && depth == 0) {
                parts.add(header.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(header.substring(start));
        return parts.stream().map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    /**
     * Finds the delimiter closing the one that opens at {@code openIndex}.
     *
     * @param source the sanitised compilation unit
     * @param openIndex the index of the opening delimiter
     * @param open the opening delimiter character
     * @param close the closing delimiter character
     * @return the index of the matching closing delimiter
     * @throws IllegalStateException when the delimiters are unbalanced, which in sanitised source means the
     *     sanitiser is wrong rather than the source being unparseable
     */
    private static int matchingDelimiter(String source, int openIndex, char open, char close) {
        int depth = 0;
        for (int index = openIndex; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == open) {
                depth++;
            } else if (character == close) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new IllegalStateException("unbalanced " + open + close + " from offset " + openIndex);
    }

    /**
     * Blanks comment bodies and string-literal contents while preserving every offset.
     *
     * <p>Assumptions: characters are replaced rather than removed, so an offset in the returned text is the
     * same offset in the file. That is what lets a match be reported against the file it came from without
     * a second pass to translate positions.</p>
     *
     * @param source the compilation unit as read
     * @return the same text with comments and literal contents replaced by spaces; never {@code null}
     */
    private static String sanitise(String source) {
        char[] characters = source.toCharArray();
        int index = 0;
        while (index < characters.length) {
            char character = characters[index];
            if (character == '/' && index + 1 < characters.length && characters[index + 1] == '/') {
                while (index < characters.length && characters[index] != '\n') {
                    characters[index++] = ' ';
                }
            } else if (character == '/' && index + 1 < characters.length
                    && characters[index + 1] == '*') {
                characters[index++] = ' ';
                characters[index++] = ' ';
                while (index < characters.length
                        && !(characters[index] == '*' && index + 1 < characters.length
                                && characters[index + 1] == '/')) {
                    if (characters[index] != '\n') {
                        characters[index] = ' ';
                    }
                    index++;
                }
                if (index < characters.length) {
                    characters[index++] = ' ';
                    characters[index++] = ' ';
                }
            } else if (character == '"' || character == '\'') {
                index = blankLiteral(characters, index, character);
            } else {
                index++;
            }
        }
        return new String(characters);
    }

    /**
     * Blanks one string or character literal, beginning at its opening quote.
     *
     * <p>Assumptions: an escape sequence consumes two characters, so a literal ending in an escaped quote
     * does not swallow the rest of the file. A text block is handled as three literals in sequence, which
     * blanks its content for the same reason without needing a separate state.</p>
     *
     * @param characters the buffer being sanitised in place
     * @param openIndex the index of the opening quote
     * @param quote the quote character that opened the literal
     * @return the index just past the literal's closing quote
     */
    private static int blankLiteral(char[] characters, int openIndex, char quote) {
        int index = openIndex + 1;
        while (index < characters.length) {
            char character = characters[index];
            if (character == '\\') {
                characters[index] = ' ';
                if (index + 1 < characters.length) {
                    characters[index + 1] = ' ';
                }
                index += 2;
                continue;
            }
            if (character == quote) {
                return index + 1;
            }
            if (character != '\n') {
                characters[index] = ' ';
            }
            index++;
        }
        return index;
    }

    /**
     * Lists every production compilation unit of every module, excluding package documentation.
     *
     * <p>Assumptions: {@code package-info.java} files are excluded because they declare no type, so they
     * can hold no record and their prose naming one would be a false positive the sanitiser cannot remove
     * -- a package charter that named a record in a Javadoc tag is exactly such prose.</p>
     *
     * @return the production sources, in a stable order; never {@code null}
     * @throws UncheckedIOException when the services directory or a module tree cannot be walked, which is
     *     a broken checkout rather than a rule violation and must not be reported as one
     */
    private static List<Path> productionSources() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(services)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(main)) {
                    walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .filter(path -> !"package-info.java".equals(
                                    path.getFileName().toString()))
                            .sorted()
                            .forEach(sources::add);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("could not walk " + services, failure);
        }
        return sources;
    }

    /**
     * Locates the repository root by walking upwards until the aggregator POM is visible.
     *
     * @return the repository root directory; never {@code null}
     * @throws IllegalStateException when no ancestor of the working directory contains the anchor, which
     *     means the test is running from outside a checkout and no assertion here could be meaningful
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of " + Path.of("").toAbsolutePath() + " contains " + ROOT_MARKER
                        + ", so the repository root could not be located");
    }

    /**
     * Reads a compilation unit as UTF-8.
     *
     * @param file the file to read
     * @return the file's contents; never {@code null}
     * @throws UncheckedIOException when the file cannot be read, which is a broken checkout rather than a
     *     rule violation and must not be reported as one
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + file, failure);
        }
    }
}
