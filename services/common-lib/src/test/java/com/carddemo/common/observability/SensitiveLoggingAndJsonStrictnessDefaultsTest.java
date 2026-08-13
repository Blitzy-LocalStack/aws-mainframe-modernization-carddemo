package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts the shared kernel's defaults close the two disclosure channels and the three parser
 * leniencies that every service previously answered for itself.
 *
 * <p>Purpose: the controls this class covers are the kind that exist or do not exist per service, and
 * before these defaults they existed in one service and not in the other seven. Two of them are
 * disclosure controls -- the persistence loggers that print a failing ROW, and the ones that print a
 * bound PARAMETER -- and three are input controls: an undeclared member, a duplicated member and a
 * non-textual scalar reaching a declared character field. None of the five is visible from a response
 * body, so none of them fails a wire test when it is missing; a service simply logs a cardholder or
 * accepts a document its own contract refuses.
 *
 * <p>Assumptions: the shipped defaults file is RESOLVED through the same config-data import every
 * service's {@code application.yml} uses, rather than having its values restated here. That is the
 * property under test -- that a service importing the file inherits the floors -- and restating the
 * values would assert only that this test agrees with itself. The pattern follows
 * {@link StructuredLoggingDefaultsTest}, which verifies the structured-logging half of the same file
 * the same way.
 *
 * <p>Alternatives Considered: starting each service and reading its effective logger levels. Rejected
 * as a cross-module test a module cannot run: a Maven module's test classpath does not carry a sibling
 * module's resources, so such a test could only live in one service and would then cover one service.
 *
 * <p>⚠️ Refactoring Rationale: that paragraph used to close with "each service asserts its own import of
 * it", and that was true of three services out of eight. The five that did not assert it inherited the
 * floors perfectly well and had nothing that would notice if they stopped -- which is the whole failure mode
 * these defaults exist to close, reproduced one level up. {@link #everyServiceProfileImportsTheDefaults()}
 * now asserts the import for ALL of them at once, reading each profile from the FILESYSTEM rather than the
 * classpath, which is the same route the repository's other cross-module gates take and the reason the
 * rejection above does not apply to it. Three services additionally assert their own import locally, which
 * is left alone: a local case names the service in its own failure output.
 *
 * <p>Assumptions: no container, network endpoint or external service is used, so this class belongs
 * under Surefire as a unit test rather than under Failsafe.
 */
class SensitiveLoggingAndJsonStrictnessDefaultsTest {

    /** The shipped defaults file, imported exactly as each service's {@code application.yml} imports it. */
    private static final String SHARED_DEFAULTS = "classpath:/carddemo-common-defaults.yml";

    /** The reactor aggregator, used as the marker that locates the repository root from any module. */
    private static final String ROOT_MARKER = "services/pom.xml";

    /** The directory holding one subdirectory per Maven module. */
    private static final String SERVICES_DIRECTORY = "services";

    /** The profile every service ships, relative to its module directory. */
    private static final String PROFILE_RESOURCE = "src/main/resources/application.yml";

    /**
     * The number of service profiles the reactor holds.
     *
     * <p>Assumptions: this is a FLOOR and not an equality, so a ninth service added later is covered by the
     * case rather than failing it for existing. What it exists to catch is a walk that resolved the wrong
     * directory, which would find none.</p>
     */
    private static final int EXPECTED_SERVICE_PROFILES = 8;

    /** The persistence logger that renders the driver's message, which for a violation is the failing row. */
    private static final String ORM_ERROR_LOGGER = "logging.level.org.hibernate.orm.jdbc.error";

    /** The persistence logger that renders every bound statement parameter. */
    private static final String ORM_BIND_LOGGER = "logging.level.org.hibernate.orm.jdbc.bind";

    /** The persistence logger that renders every statement issued. */
    private static final String SQL_LOGGER = "logging.level.org.hibernate.SQL";

    /** The reader setting that refuses a member the contract does not declare. */
    private static final String UNKNOWN_MEMBER_PROPERTY =
            "spring.jackson.deserialization.fail-on-unknown-properties";

    /** The parser setting that refuses a member named twice in one object. */
    private static final String DUPLICATE_MEMBER_PROPERTY =
            "spring.jackson.read.strict-duplicate-detection";

    /** The serialiser setting that keeps a required-and-nullable member present with a null value. */
    private static final String NULL_INCLUSION_PROPERTY = "spring.jackson.default-property-inclusion";

    /** The only value the seven contracts admit for {@link #NULL_INCLUSION_PROPERTY}. */
    private static final String NULL_INCLUSION_VALUE = "always";

    /** Profile documents to examine per module, in the order a deployment resolves them. */
    private static final String PROFILE_PREFIX = "application";

    /** The suffix identifying the development profile, the one document permitted to raise statements. */
    private static final String DEV_PROFILE_SUFFIX = "-dev.yml";

    /**
     * Log levels ordered from most to least restrictive, so a pin can be compared with an override.
     *
     * <p>Assumptions: the list is the framework's own level set including {@code OFF}, and position in it
     * IS the comparison -- a level at a lower index withholds at least as much as one at a higher index.
     * Comparing the strings themselves would make {@code DEBUG} look more restrictive than {@code WARN}
     * because D sorts before W, which is the mistake this ordering exists to prevent.</p>
     */
    private static final List<String> LEVELS_MOST_TO_LEAST_RESTRICTIVE =
            List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /**
     * A context runner importing the shipped defaults, with the deployment labels the file interpolates.
     *
     * <p>Assumptions: the two {@code CARDDEMO_} values are supplied because the shipped file
     * interpolates them into the structured-logging block, so a run without them fails to resolve the
     * document at all and the assertion would report a placeholder failure rather than the property
     * under test.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.config.import=" + SHARED_DEFAULTS,
                    "spring.application.name=common-lib-test",
                    "CARDDEMO_ENVIRONMENT=test",
                    "CARDDEMO_VERSION=0.0.0-test");

    /**
     * The three persistence loggers that can render a row or a parameter are pinned above their
     * value-bearing levels.
     *
     * <p>⚠️ Assumptions: the error category is pinned at {@code ERROR} rather than {@code OFF} because
     * its value-bearing output is emitted at {@code WARN}: {@code ERROR} is the lowest level that
     * suppresses the row while leaving anything Hibernate raises at error level still visible. The two
     * statement categories are pinned at {@code WARN} because they write at {@code DEBUG} and
     * {@code TRACE}, so {@code WARN} is above both.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the shared defaults pin the three persistence loggers above their disclosing levels")
    void sharedDefaultsPinThePersistenceLoggers() {
        this.runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty(ORM_ERROR_LOGGER))
                    .as("the driver's message for a constraint violation IS the failing row")
                    .isEqualTo("ERROR");
            assertThat(environment.getProperty(ORM_BIND_LOGGER))
                    .as("bound parameters on these paths are card numbers and amounts")
                    .isEqualTo("WARN");
            assertThat(environment.getProperty(SQL_LOGGER))
                    .as("the statement text names the columns a bound parameter fills")
                    .isEqualTo("WARN");
        });
    }

    /**
     * A service can still raise one of the three floors in its own document, which is what makes them
     * defaults rather than a lock.
     *
     * <p>Assumptions: this asserts the PRECEDENCE rather than a value. A config-data import is lower
     * precedence than the importing document, so an operator diagnosing a specific failure can raise
     * one category for one service without editing the shared file -- and if that precedence were ever
     * inverted, the floors would become unoverridable and this case would say so.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("an importing document still outranks the shared floor")
    void animportingDocumentOutranksTheSharedFloor() {
        this.runner.withPropertyValues(ORM_ERROR_LOGGER + "=DEBUG")
                .run(context -> assertThat(context.getEnvironment().getProperty(ORM_ERROR_LOGGER))
                        .isEqualTo("DEBUG"));
    }

    /**
     * The shared defaults refuse an undeclared member and a duplicated member for every service.
     *
     * <p>⚠️ Assumptions: both keys are asserted from the resolved environment rather than by parsing a
     * document, because what a service inherits is the resolved property. The two conditions they close
     * are different: an undeclared member is silently DISCARDED by the reader's default, so a
     * misspelled paging direction produced a well-formed page in the opposite direction; a duplicated
     * member is silently resolved LAST-WINS, so a body naming one selector twice was answered for the
     * second value while the caller read back the first.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the shared defaults refuse undeclared and duplicated members")
    void sharedDefaultsRefuseUndeclaredAndDuplicatedMembers() {
        this.runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty(UNKNOWN_MEMBER_PROPERTY, Boolean.class))
                    .as("every published request body declares additionalProperties: false")
                    .isTrue();
            assertThat(environment.getProperty(DUPLICATE_MEMBER_PROPERTY, Boolean.class))
                    .as("a duplicated member is a parser differential, not a formatting preference")
                    .isTrue();
        });
    }

    /**
     * The shared auto-configuration publishes the coercion customiser, and it refuses a number for a
     * declared character member while still admitting a money string.
     *
     * <p>⚠️ Purpose: this is the asymmetry the customiser exists for, and asserting only half of it
     * would leave the wrong half enforced. A number reaching a textual member must be refused, because
     * the conversion renders as exactly the digits a pattern constraint expects and the acceptance is
     * therefore undetectable. A string reaching a {@code BigDecimal} must be admitted, because money
     * crosses every boundary in this migration as a JSON string under transformation rule T3 -- so the
     * blanket setting that would close the first would also break every amount.
     *
     * <p>Assumptions: the customiser is applied to a mapper built here rather than asserted by identity,
     * because what matters is the reader's behaviour and not which builder method was called.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the shared customiser refuses a number for a text member and admits a money string")
    void sharedCustomiserRefusesNumbersForTextAndAdmitsMoneyStrings() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CardDemoCommonAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(JsonMapperBuilderCustomizer.class);
                    JsonMapper.Builder builder = JsonMapper.builder();
                    context.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
                    JsonMapper mapper = builder.build();

                    assertThatThrownBy(() ->
                            mapper.readValue("{\"accountId\":10000000101}", TextMember.class))
                            .as("a number renders as the digits a pattern expects, so acceptance would"
                                    + " be undetectable")
                            .isInstanceOf(MismatchedInputException.class);

                    assertThat(mapper.readValue("{\"amount\":\"1234.56\"}", MoneyMember.class).amount())
                            .as("money crosses every boundary as a string under rule T3")
                            .isEqualByComparingTo(new BigDecimal("1234.56"));
                });
    }

    /**
     * The duplicate-member refusal is in force on a mapper configured from the shared properties.
     *
     * <p>Assumptions: the property is applied to a mapper directly rather than through a running web
     * layer, because the setting under test is a PARSER feature and a slice test would additionally
     * depend on the request converter's own configuration. The sibling case above proves the property is
     * published; this one proves what the published value does.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("strict duplicate detection refuses a member named twice")
    void strictDuplicateDetectionRefusesARepeatedMember() {
        JsonMapper mapper = JsonMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();

        assertThatThrownBy(() -> mapper.readValue(
                "{\"accountId\":\"10000000101\",\"accountId\":\"20000000001\"}", TextMember.class))
                .as("last-wins answers for a value the caller cannot see it sent")
                .isInstanceOf(StreamReadException.class);
    }

    /**
     * A body with one declared character member, standing for the selectors every contract publishes.
     *
     * @param accountId the eleven-character account scope as every contract declares it, a string
     */
    private record TextMember(String accountId) {
    }

    /**
     * A body with one money member, standing for the amounts that must keep arriving as strings.
     *
     * @param amount the exact fixed-point amount, transported as a JSON string under rule T3
     */
    private record MoneyMember(BigDecimal amount) {
    }

    /**
     * Every service profile imports the shared defaults, so no service can quietly opt out of the floors.
     *
     * <p>Purpose: the settings this class asserts only reach a service that imports this file, and the import
     * is one line in a document nothing compiles. A service whose profile dropped it would keep passing every
     * test it owns, publish contracts that declare {@code additionalProperties: false}, and silently accept
     * undeclared members at run time -- and the logger floors that keep a cardholder out of a log line would
     * go with it. This case makes that omission a build failure.
     *
     * <p>Assumptions: each profile is PARSED and its {@code spring.config.import} read, rather than searched
     * for the file name as text. Four of the eight profiles mention this file in a comment, so a textual
     * search would report every one of them as importing it whether or not the import survived -- which is
     * precisely the kind of assertion that passes while the thing it names is gone.
     *
     * <p>Assumptions: the import may be declared as a single value or as a list, and both are accepted,
     * because the eight profiles use both forms and neither is more correct. What is asserted is that the
     * shared document is among the imports, not how the import is spelled.
     *
     * <p>Assumptions: a floor is asserted on the number of profiles found, and it exists because the walk
     * resolves a path rather than a classpath. A walk that resolved the wrong directory would find no
     * profile and would otherwise pass with nothing examined, which is the failure mode a floor converts
     * into a message.
     *
     * <p>Measured: deleting the import line from one service's profile fails this case and names that
     * profile's path; commenting the same line out fails it identically, which is what shows the parse and
     * not a text search decides.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every service profile imports the shared defaults")
    void everyServiceProfileImportsTheDefaults() {
        List<Path> profiles = serviceProfiles();

        assertThat(profiles)
                .as("the walk must resolve the service tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_SERVICE_PROFILES);

        List<String> notImporting = new ArrayList<>();
        for (Path profile : profiles) {
            if (!declaredImports(profile).contains(SHARED_DEFAULTS)) {
                notImporting.add(profile.toString());
            }
        }

        assertThat(notImporting)
                .as("a profile that stops importing the shared document silently loosens that service")
                .isEmpty();
    }

    /**
     * No service profile loosens a floor or a refusal the shared document pins.
     *
     * <p>Purpose: importing the shared document is necessary and not sufficient. A config-data import is
     * LOWER precedence than the document that imports it -- {@link #animportingDocumentOutranksTheSharedFloor()}
     * asserts exactly that -- so a service can inherit every floor and then raise one back in its own
     * profile, and nothing it owns would notice. This case reads all three documents of all eight services
     * and reports any that does.
     *
     * <p>⚠️ Assumptions: the development document is permitted to raise {@code org.hibernate.SQL} and the
     * base and production documents are not. Measured across the reactor, five development profiles set it
     * to {@code DEBUG} deliberately, and at {@code DEBUG} that category writes the STATEMENT with parameter
     * placeholders rather than the parameters themselves -- the values arrive on
     * {@code org.hibernate.orm.jdbc.bind} at {@code TRACE}, which this case refuses to loosen anywhere. A
     * development deployment runs against a non-production dataset, so the statement text is a legitimate
     * development aid there and is refused in the two documents that reach live cardholder data.
     *
     * <p>⚠️ Assumptions: the error and bind categories are refused a loosening in EVERY document including
     * development, because their output is the cardholder value itself rather than the shape of a query. An
     * operator who needs a literal failing row raises the category for one package on one run, which is the
     * scoped and auditable act the shared document's own rationale describes; a profile that ships it raised
     * makes every violation in that environment write a row into a log store.
     *
     * <p>Assumptions: each document is flattened to dotted keys before it is read, so a logger pinned as
     * one dotted key and a logger pinned as nested mappings are both found. Reading a fixed key path would
     * see only the spelling the current documents happen to use.
     *
     * <p>Measured: setting {@code org.hibernate.orm.jdbc.bind} to {@code TRACE} in one development profile
     * fails this case with {@code application-dev.yml sets logging.level.org.hibernate.orm.jdbc.bind to
     * TRACE, below the shared floor of WARN}, naming the one document. Setting {@code org.hibernate.SQL}
     * to {@code DEBUG} in a BASE document fails it the same way, while the five development documents
     * already carrying that exact value pass in the same run -- which is what shows the two kinds of
     * document are judged differently rather than the level simply being refused everywhere.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("no service profile loosens a pinned disclosure floor or parser refusal")
    void noServiceProfileLoosensAPinnedFloor() {
        List<Path> profiles = allServiceProfiles();

        assertThat(profiles)
                .as("the walk must resolve the service tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_SERVICE_PROFILES);

        List<String> loosened = new ArrayList<>();
        for (Path profile : profiles) {
            Map<String, String> declared = flattened(profile);
            collectLoosenedFloor(profile, declared, ORM_ERROR_LOGGER, "ERROR", loosened);
            collectLoosenedFloor(profile, declared, ORM_BIND_LOGGER, "WARN", loosened);
            if (!profile.getFileName().toString().endsWith(DEV_PROFILE_SUFFIX)) {
                collectLoosenedFloor(profile, declared, SQL_LOGGER, "WARN", loosened);
            }
            collectWithdrawnRefusal(profile, declared, UNKNOWN_MEMBER_PROPERTY, loosened);
            collectWithdrawnRefusal(profile, declared, DUPLICATE_MEMBER_PROPERTY, loosened);

            String inclusion = declared.get(NULL_INCLUSION_PROPERTY);
            if (inclusion != null && !NULL_INCLUSION_VALUE.equals(inclusion)) {
                loosened.add(profile + " sets " + NULL_INCLUSION_PROPERTY + " to " + inclusion
                        + ", and every contract publishes required-and-nullable members that depend on "
                        + NULL_INCLUSION_VALUE);
            }
        }

        assertThat(loosened)
                .as("a profile may not raise a category that prints a cardholder, nor withdraw a refusal")
                .isEmpty();
    }

    /**
     * Collects every service profile in the reactor.
     *
     * @return one path per {@code application.yml} under a service module's main resources; never
     *     {@code null}
     * @throws IllegalStateException if the service directory cannot be listed, which means the walk
     *     resolved a path that is not the reactor
     */
    private static List<Path> serviceProfiles() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        List<Path> profiles = new ArrayList<>();
        try (Stream<Path> modules = Files.list(services)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path profile = module.resolve(PROFILE_RESOURCE);
                if (Files.isRegularFile(profile)) {
                    profiles.add(profile);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not list " + services, failure);
        }
        return profiles;
    }

    /**
     * Reads the config-data imports one profile declares.
     *
     * <p>Assumptions: an absent import section yields an empty list rather than raising, so a profile that
     * declares none is reported by the assertion that reads it instead of by an exception naming a key.</p>
     *
     * @param profile the profile to read; must not be {@code null}
     * @return the imports it declares, in declaration order; never {@code null}
     * @throws IllegalStateException if the profile cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private static List<String> declaredImports(Path profile) {
        try (InputStream stream = Files.newInputStream(profile)) {
            Object parsed = new Yaml().load(stream);
            Object node = parsed;
            for (String key : List.of("spring", "config", "import")) {
                if (!(node instanceof Map<?, ?> mapping)) {
                    return List.of();
                }
                node = ((Map<String, Object>) mapping).get(key);
            }
            if (node instanceof List<?> declared) {
                return declared.stream().map(String::valueOf).toList();
            }
            return node == null ? List.of() : List.of(String.valueOf(node));
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + profile, failure);
        }
    }

    /**
     * Collects every profile document of every service module.
     *
     * @return one path per {@code application*.yml} under a service module's main resources, module by
     *     module and document by document in name order; never {@code null}
     * @throws IllegalStateException if a directory cannot be listed, which means the walk resolved a path
     *     that is not the reactor
     */
    private static List<Path> allServiceProfiles() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        List<Path> profiles = new ArrayList<>();
        try (Stream<Path> modules = Files.list(services)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> documents = Files.list(resources)) {
                    documents.filter(Files::isRegularFile)
                            .filter(document -> document.getFileName().toString()
                                    .startsWith(PROFILE_PREFIX))
                            .filter(document -> document.getFileName().toString().endsWith(".yml"))
                            .sorted()
                            .forEach(profiles::add);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not list under " + services, failure);
        }
        return profiles;
    }

    /**
     * Reads one profile as a map of dotted property keys to rendered values.
     *
     * @param profile the profile to read; must not be {@code null}
     * @return every scalar the document declares, keyed by its dotted property name; never {@code null}
     * @throws IllegalStateException if the profile cannot be read
     */
    private static Map<String, String> flattened(Path profile) {
        try (InputStream stream = Files.newInputStream(profile)) {
            Map<String, String> flat = new java.util.LinkedHashMap<>();
            flattenInto(new Yaml().load(stream), "", flat);
            return flat;
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + profile, failure);
        }
    }

    /**
     * Flattens one parsed node into dotted keys.
     *
     * <p>Assumptions: a mapping key that already contains dots is appended as it stands rather than split,
     * so {@code org.hibernate.SQL} written as one key and written as three nested ones both flatten to the
     * same dotted name -- which is what lets the caller look a logger up by its property name.</p>
     *
     * @param node the parsed node, which may be {@code null} for an empty document
     * @param prefix the dotted prefix accumulated so far, empty at the root
     * @param into the map to populate; must not be {@code null}
     */
    private static void flattenInto(Object node, String prefix, Map<String, String> into) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                String key = prefix.isEmpty()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flattenInto(entry.getValue(), key, into);
            }
            return;
        }
        if (node != null && !prefix.isEmpty()) {
            into.put(prefix, String.valueOf(node));
        }
    }

    /**
     * Records a logger whose declared level withholds less than the shared floor.
     *
     * @param profile the profile being read; named in the message so the failure points at one document
     * @param declared the flattened profile
     * @param logger the dotted property naming the logger
     * @param floor the least restrictive level the shared document permits for it
     * @param into the list of findings to append to
     */
    private static void collectLoosenedFloor(Path profile, Map<String, String> declared, String logger,
            String floor, List<String> into) {
        String level = declared.get(logger);
        if (level == null) {
            return;
        }
        int declaredRank = LEVELS_MOST_TO_LEAST_RESTRICTIVE.indexOf(level.toUpperCase(java.util.Locale.ROOT));
        int floorRank = LEVELS_MOST_TO_LEAST_RESTRICTIVE.indexOf(floor);
        if (declaredRank < 0) {
            into.add(profile + " sets " + logger + " to " + level + ", which is not a log level");
            return;
        }
        if (declaredRank > floorRank) {
            into.add(profile + " sets " + logger + " to " + level + ", below the shared floor of " + floor);
        }
    }

    /**
     * Records a parser refusal a profile withdraws.
     *
     * @param profile the profile being read; named in the message so the failure points at one document
     * @param declared the flattened profile
     * @param property the dotted property naming the refusal
     * @param into the list of findings to append to
     */
    private static void collectWithdrawnRefusal(Path profile, Map<String, String> declared, String property,
            List<String> into) {
        String value = declared.get(property);
        if (value != null && !Boolean.parseBoolean(value)) {
            into.add(profile + " withdraws " + property + ", which every contract's "
                    + "additionalProperties: false depends on");
        }
    }

    /**
     * Locates the repository root by walking up to the reactor's own aggregator.
     *
     * @return the directory holding the reactor aggregator; never {@code null}
     * @throws IllegalStateException if no ancestor holds it, which means the test is running from a
     *     directory outside the checkout
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("no ancestor of " + Path.of("").toAbsolutePath()
                + " contains " + ROOT_MARKER + ", so the reactor root could not be located");
    }
}
