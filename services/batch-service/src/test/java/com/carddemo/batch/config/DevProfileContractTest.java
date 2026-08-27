package com.carddemo.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.common.profile.ProfileConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executes the {@code dev} profile of this service and holds every value it declares to the reasoning
 * recorded beside that value in {@code services/batch-service/src/main/resources/application-dev.yml}.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: before this class, no test in the repository activated {@code dev} at all,
 * so the whole overlay was inert as far as the build was concerned. Three classes of defect could
 * therefore reach a deployment untouched. A misspelled property key binds to nothing and reports
 * nothing, so a value written under a near-miss name leaves the inherited one silently in force. A
 * profile document REPLACES an inherited collection rather than merging into it, so a list that omitted
 * a member the base document supplied would remove it with no diagnostic. And a placeholder naming an
 * environment variable no deployment supplies fails when a task starts, which is after every gate has
 * passed.
 *
 * <h2>Why the winning source is asserted, not only the value</h2>
 *
 * <p>Assumptions: each override asserts the NAME of the document that supplied the value as well as the
 * value itself. A value comparison alone cannot distinguish a successful override from a dev document
 * that never took effect, because the two are indistinguishable whenever the dev value and the
 * inherited value agree -- and for most keys agreeing is the normal case. Naming the winning document
 * makes the override itself the thing under test.
 *
 * <h2>Why resolution runs with nothing injected</h2>
 *
 * <p>Trade-offs: the resolution supplies no environment variables, so every endpoint, credential and
 * identifier stays an unresolved placeholder. That is deliberate: these documents carry no working
 * default for any of them, so a task started without them fails rather than reaching whichever endpoint
 * the host happens to resolve. Running with nothing injected is therefore the only way to observe which
 * placeholders are platform-supplied, and holding that set to a documented list catches a placeholder
 * whose name was mistyped -- a defect that otherwise surfaces as a start-up failure in the one
 * environment nobody tested.
 */
@DisplayName("the batch service development profile")
final class DevProfileContractTest {

  /** The profile under test. */
  private static final String DEV = "dev";

  /** The document that must win every override below, matched by name fragment. */
  private static final String DEV_DOCUMENT = "application-dev.yml";

  /**
   * The environment variables this service's configuration deliberately leaves to the platform.
   *
   * <p>Assumptions: this list is a TRANSCRIPTION of the placeholders the configuration documents declare
   * without a default, and it is asserted as an upper bound rather than as a target. A placeholder whose
   * name is mistyped is not in this list, so it fails the assertion naming the property that referenced
   * it; a placeholder deliberately added must be added here in the same change, which is what makes the
   * platform contract reviewable in one place.
   */
  private static final Set<String> PLATFORM_SUPPLIED =
      Set.of(
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_FLYWAY_PASSWORD",
        "SPRING_FLYWAY_USER");


  /**
   * The values configuration RESOLUTION itself needs, as distinct from those a bean reads later.
   *
   * <p>Assumptions: the region is here rather than in {@link #PLATFORM_SUPPLIED} because of WHEN it is
   * needed. This service's inherited import list names remote configuration locations, and although each
   * carries the {@code optional:} prefix, that prefix covers a location that cannot be FOUND -- it does not
   * cover a client that cannot be CONSTRUCTED. With the region unresolved the machinery builds a malformed
   * endpoint and resolution fails before any document is read, reporting an invalid URI rather than naming
   * the missing variable. {@link #theRegionTheImportLocationNeedsIsRequiredAndHasNoDefault()} pins that coupling so a reader does not
   * have to rediscover it.
   */
  private static final Map<String, String> RESOLUTION_TIME_VALUES = Map.of("AWS_REGION", "us-east-1");

  /**
   * The framework property the configuration client reads its region from.
   *
   * <p>Assumptions: the constant is declared once and used by the case below rather than written inline,
   * because the same spelling appears in {@code application.yml} and a mismatch between the two would make
   * the case assert nothing while still passing -- {@code rawValue} answers empty for a key that does not
   * exist, and the case would then be pinning the absence of a property nobody declared.
   */
  private static final String REGION_PROPERTY = "spring.cloud.aws.region.static";

  /** The environment variable that property's placeholder names. */
  private static final String REGION_VARIABLE = "AWS_REGION";

  /** The property whose list names the remote configuration location the region is needed for. */
  private static final String CONFIG_IMPORT_PROPERTY = "spring.config.import";

  /** The remote parameter location the import list names. */
  private static final String REMOTE_PARAMETER_LOCATION = "aws-parameterstore";

  /** The prefix that makes a location survivable when it cannot be found. */
  private static final String OPTIONAL_LOCATION_PREFIX = "optional:";

  /** The resolved configuration, resolved once because resolution is read-only and not cheap. */
  private static ProfileConfiguration dev;

  /** Resolves this service's configuration with the development profile active. */
  @BeforeAll
  static void resolveDevProfile() {
    dev = ProfileConfiguration.resolveWith(RESOLUTION_TIME_VALUES, DEV);
  }

  /** The profile the resolution asked for is the profile that ends up active. */
  @Test
  @DisplayName("activates the development profile")
  void activatesTheDevelopmentProfile() {
    assertThat(dev.activeProfiles()).containsExactly(DEV);
  }

  /**
   * The connection pool is sized for this context's development load.
   *
   * <p>Assumptions: the widened wait is what lets a development task survive a cluster resume, and it is
   * asserted together with the zero minimum because one causes the need for the other. A cluster at a zero
   * minimum pauses while idle and must resume before accepting a connection, so the first connection of a
   * nightly chain pays that resume -- and a batch step that failed to acquire it would fail the whole chain
   * for a reason indistinguishable from a real database fault.
   */
  @Test
  @DisplayName("sizes the connection pool for the development load")
  void sizesTheConnectionPoolForTheDevelopmentLoad() {
    assertThat(dev.require("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
    assertThat(dev.require("spring.datasource.hikari.connection-timeout")).isEqualTo("45000");
    assertThat(dev.sourceOf("spring.datasource.hikari.minimum-idle"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.connection-timeout"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The connection start-up packet carries the two statement bounds a long step needs.
   *
   * <p>Assumptions: these bounds travel in the connection's START-UP PACKET rather than as a session
   * statement, and that distinction is load-bearing rather than stylistic. A session statement issued after
   * connect runs inside a transaction the pool does not commit when auto-commit is disabled, so it is rolled
   * back and the setting silently never applies -- the same defect this project already found and fixed on a
   * schema search path. A packet option is applied by the server before any transaction exists.
   *
   * <p>Assumptions: BOTH bounds are asserted by name. One caps how long a statement waits for a lock and the
   * other how long a transaction may sit idle holding one; a batch chain that lost the first would block
   * behind an online writer indefinitely, and one that lost the second could hold a lock across a whole
   * window.
   */
  @Test
  @DisplayName("carries the start-up packet bounds a batch step needs")
  void carriesTheStartupPacketBoundsForBatchStatements() {
    final String options = dev.require("spring.datasource.hikari.data-source-properties.options");

    assertThat(options).contains("lock_timeout=10000");
    assertThat(options).contains("idle_in_transaction_session_timeout=120000");
    assertThat(dev.sourceOf("spring.datasource.hikari.data-source-properties.options"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The import list restates the shared defaults document as well as naming the remote location.
   *
   * <p>Assumptions: this is the case the whole harness was needed for. The property REPLACES the inherited
   * list rather than adding to it, so omitting the classpath entry would drop the shared console log pattern
   * in the development profile only -- and the correlation identifier would vanish from exactly the logs a
   * developer is reading, with every other gate still green.
   *
   * <p>Assumptions: the shared document is asserted to have been READ as well as listed, by requiring a
   * property only it declares. A list naming the document is not evidence the document was found.
   *
   * <p>Assumptions: the remote location keeps its {@code optional:} prefix, so a build or a task with no
   * platform behind it skips it rather than failing. A prefix dropped from it would turn every start-up
   * outside AWS into a resolution failure.
   */
  @Test
  @DisplayName("restates the shared defaults import alongside the remote one")
  void restatesTheSharedDefaultsImportAlongsideTheRemoteOne() {
    final List<String> imports = dev.list("spring.config.import");

    assertThat(imports).isNotEmpty();
    assertThat(imports.getFirst()).isEqualTo("classpath:/carddemo-common-defaults.yml");
    assertThat(imports).anySatisfy(DevProfileContractTest::assertOptionalParameterStore);
    assertThat(dev.value("logging.pattern.console")).isPresent();
  }

  /**
   * Neither statement text nor bound values are logged, at any level.
   *
   * <p>Assumptions: this context turns the two statement categories OFF rather than pinning them at a level,
   * and that is the opposite of what every online context does. A batch step issues one statement per record
   * over a whole file, so a category that logged them would produce a line per record -- burying the step
   * boundaries and return codes an operator reads, and doing so in the log of the chain that posts money.
   *
   * <p>Assumptions: the fetch and batch sizes are asserted here too, because they are what makes the step
   * issue few statements rather than many, and a change to either alters the volume this decision was taken
   * against.
   */
  @Test
  @DisplayName("keeps statement echo and statement logging off entirely")
  void keepsStatementEchoAndStatementLoggingOffEntirely() {
    assertThat(dev.require("logging.level.org.hibernate.SQL")).isEqualTo("OFF");
    assertThat(dev.require("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("OFF");
    assertThat(dev.require("spring.jpa.show-sql")).isEqualTo("false");
    assertThat(dev.require("spring.jpa.properties.hibernate.jdbc.fetch_size")).isEqualTo("25");
    assertThat(dev.require("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("10");
  }

  /**
   * The diagnostic categories this profile lowers, and the ones it deliberately does not.
   *
   * <p>Assumptions: the batch framework's own category is lowered alongside this application's, because a
   * step's chunk boundaries and its job-repository writes are the framework's to report -- and they are
   * precisely what a developer diagnosing a restart needs to see, since the durable step ledger is what
   * makes a resumed step a no-op.
   */
  @Test
  @DisplayName("lowers the diagnostic categories and leaves the identity-bearing ones pinned")
  void lowersTheDiagnosticCategoriesAndLeavesTheIdentityBearingOnesPinned() {
    final Map<String, String> levels = dev.loggingLevels();

    assertThat(levels).containsEntry("com.carddemo", "DEBUG");
    assertThat(levels).containsEntry("org.springframework.batch", "DEBUG");
    assertThat(levels).containsEntry("org.flywaydb", "DEBUG");
  }

  /**
   * Asserts one import entry is the optional parameter-store location this overlay names.
   *
   * @param entry one member of the resolved import list
   */
  private static void assertOptionalParameterStore(final String entry) {
    assertThat(entry).startsWith("optional:aws-parameterstore:");
    assertThat(entry).contains("/batch/");
  }

  /**
   * The overlay varies values only and introduces no key the inherited documents do not declare.
   *
   * <p>Assumptions: this is the overlay's own headline constraint, and it is asserted mechanically rather
   * than trusted. The consequence is the point of the constraint: a defect reproduced in dev is
   * reproducible in prod, because the only difference between the environments is the value of a handful
   * of keys. A key the dev document INTRODUCED would appear in the dev resolution and be absent from a
   * resolution taken without it.
   *
   * <p>Trade-offs: keys the overlay is documented as introducing deliberately are excluded from the
   * comparison by {@link #keysThisOverlayIntroduces()} rather than by weakening the check, so the
   * exception is stated as a list a reviewer can read instead of as an absent assertion.
   */
  @Test
  @DisplayName("introduces no undocumented key")
  void introducesNoUndocumentedKey() {
    final ProfileConfiguration base =
        ProfileConfiguration.resolveWith(RESOLUTION_TIME_VALUES, "default");
    for (final String key : devDeclaredKeys()) {
      if (keysThisOverlayIntroduces().contains(key)) {
        continue;
      }
      assertThat(base.sourceOf(key))
          .as("an inherited document must already declare %s", key)
          .isPresent();
    }
  }

  /**
   * Only the documented environment variables are left for the platform to supply.
   *
   * <p>Assumptions: the assertion is a SUBSET check against the documented list rather than an equality
   * check. A variable the surrounding build happens to export resolves and drops out of the observed set,
   * which would break an equality check for a reason that is not a defect; a variable whose name was
   * mistyped is not in the documented list, so it fails this check naming the property that referenced
   * it, which is the defect worth catching.
   *
   * <p>Assumptions: the set is also asserted NON-EMPTY. These documents supply no working default for any
   * endpoint or credential, so an empty set would mean either that a default had been introduced -- a
   * wrong endpoint that resolves presents as a working service, which is the harder fault to notice -- or
   * that the harness resolved nothing at all.
   */
  @Test
  @DisplayName("declares only the documented platform-supplied placeholders")
  void declaresOnlyTheDocumentedPlatformSuppliedPlaceholders() {
    final Map<String, String> unresolvable = dev.unresolvablePlaceholders();

    assertThat(unresolvable).isNotEmpty();
    /*
     * Assumptions: the containment is asserted in this direction -- documented list CONTAINS every observed
     * variable -- rather than per element. A per-element assertion aggregates into one failure that names no
     * element, which was measured while this class was being written: the report carried an empty message.
     * This form lists exactly the variables that were observed and are not documented, which is the
     * information a reader needs to tell a mistyped placeholder from a newly added one.
     */
    assertThat(PLATFORM_SUPPLIED)
        .as("every unresolved placeholder must be a documented platform-supplied variable")
        .containsAll(unresolvable.values());
  }

  /**
   * Names every key this service's development overlay sets.
   *
   * <p>Assumptions: the list is a transcription of the overlay rather than a discovery from it, and that
   * is deliberate. A test that derived the list from the document could not detect a key REMOVED from the
   * document, because the derived list would shrink with it; a transcription fails when the document
   * stops declaring something it is supposed to declare.
   *
   * @return the keys the overlay sets
   */
  private static List<String> devDeclaredKeys() {
    return List.of(
        "spring.datasource.hikari.connection-timeout",
        "spring.datasource.hikari.minimum-idle",
        "spring.datasource.hikari.data-source-properties.options",
        "spring.jpa.show-sql",
        "spring.jpa.properties.hibernate.jdbc.fetch_size",
        "spring.jpa.properties.hibernate.jdbc.batch_size",
        "logging.level.com.carddemo",
        "logging.level.org.springframework.batch",
        "logging.level.org.flywaydb",
        "logging.level.org.hibernate.SQL",
        "logging.level.org.hibernate.orm.jdbc.bind");
  }

  /**
   * Names the keys this overlay is documented as introducing rather than overriding.
   *
   * @return the keys no inherited document declares
   */
  private static List<String> keysThisOverlayIntroduces() {
    /*
     * Assumptions: the connection timeout is on this list because the overlay's own note says so in as many
     * words -- it records that this is the one key the base document does not declare, and that the widened
     * budget is what lets a development task survive a cluster resume. It is listed here rather than
     * silently excluded so that the exception is as reviewable as the rule.
     */
    return List.of(
        "spring.datasource.hikari.data-source-properties.options",
        "spring.datasource.hikari.connection-timeout");
  }

  /**
   * The region the import location is built from is required, carries no default, and is what makes
   * resolution possible.
   *
   * <p>Assumptions: the coupling this pins is invisible in every other way. The import location is marked
   * optional, which reads as "this service starts fine without a platform", and that is true only once a
   * region is present -- the {@code optional:} prefix covers a location that cannot be FOUND, not a client
   * that cannot be CONSTRUCTED. A deployment that supplied every credential but omitted the region would
   * fail with a malformed-endpoint message naming no variable at all.
   *
   * <p>⚠️ Refactoring Rationale: this used to assert that {@code ProfileConfiguration.resolve(DEV)} THROWS,
   * and that assertion was not deterministic -- it depended on a variable neither this project nor this test
   * sets. With {@code AWS_ENDPOINT_URL} present in the process environment the configuration client is
   * constructible without a resolved region, so resolution completes and the case failed; the documented
   * local environment exports exactly that variable, so a build of this module failed on a correct tree. The
   * failure was also in the one direction that misleads: it reported a defect where there was none, while a
   * genuine regression -- someone giving the region placeholder a default -- would have been reported the
   * same way and read as the same known noise. That variable is read by the AWS SDK from the operating-system
   * environment, so no property source and no test fixture can mask it from inside this JVM.
   *
   * <p>⚠️ Assumptions: what replaces it asserts the CAUSE and the MECHANISM rather than the consequence, and
   * both are read from the configuration documents, so neither can be changed by the surrounding
   * environment. First, the region property's RAW value is the bare placeholder with NO default part -- that
   * is precisely the property a later edit could weaken, and a default here is what would let a region-less
   * job run and then address the wrong partition. Second, the import list genuinely names a remote location,
   * because a defaultless region only matters while something is built from it. Third, supplying the region
   * is SUFFICIENT for resolution to complete, which is the consequence half stated in the one direction that
   * is deterministic in every environment.
   *
   * <p>Assumptions: ONE remote location is expected here where the request-serving contexts expect two. This
   * module imports the parameter store and NOT the secret store, which is its own deliberate choice recorded
   * beside the import list: a batch job reads endpoints and sizing, and the one credential it needs arrives
   * through its datasource rather than through a configuration import.
   *
   * <p>Assumptions: the identical correction was applied to {@code account-service}, which carried the same
   * assertion for the same reason. The sibling in {@code reporting-service} deliberately still asserts the
   * throw and is NOT harmonised with these two: that service declares no region property at all, so its
   * resolver falls through to the SDK's own provider chain, which an endpoint override does not satisfy.
   */
  @Test
  @DisplayName("requires the region the import location needs, with no default and no fallback")
  void theRegionTheImportLocationNeedsIsRequiredAndHasNoDefault() {
    assertThat(dev.rawValue(REGION_PROPERTY))
        .as("the region property must be declared, since the import location is built from it")
        .isPresent();
    assertThat(dev.rawValue(REGION_PROPERTY).orElseThrow())
        .as("the region placeholder must carry NO default; a default would let a region-less job run"
            + " and then address a partition nobody chose")
        .isEqualTo("${" + REGION_VARIABLE + "}")
        .doesNotContain(":");

    List<String> remoteLocations = dev.list(CONFIG_IMPORT_PROPERTY).stream()
        .filter(location -> location.contains(REMOTE_PARAMETER_LOCATION))
        .toList();
    assertThat(remoteLocations)
        .as("a defaultless region only matters while a remote location is built from it")
        .hasSize(1)
        // WHY : Assumptions: the optional prefix is asserted on the REMOTE location only, and the classpath
        //   default beside it is deliberately excluded. That entry is not optional and must not be: it
        //   carries the shared defaults every profile inherits, so a job that could start without it would
        //   start with those defaults absent. Asserting the prefix across the whole list would therefore
        //   assert the opposite of what the document intends.
        .allMatch(location -> location.startsWith(OPTIONAL_LOCATION_PREFIX));

    assertThatCode(() -> ProfileConfiguration.resolveWith(RESOLUTION_TIME_VALUES, DEV))
        .as("supplying the region is sufficient for resolution to complete")
        .doesNotThrowAnyException();
  }

  /**
   * The probe listener stays on loopback under this profile, and this profile adds no listener of its own.
   *
   * <p>Refactoring Rationale: this is the batch analogue of the transport-security case the seven request
   * -serving contexts carry, and it asserts a different mechanism because this module protects that listener
   * differently. It declares no {@code server.ssl} at all: every client of that listener sits inside the
   * task's own network namespace and reaches {@code 127.0.0.1:8080/actuator/health} from there -- the image
   * declares no HEALTHCHECK, so the client is an operator or a task-definition command -- so the bind address
   * -- not a certificate -- is what keeps the endpoint unreachable from anywhere else. The reasoning is
   * recorded in full beside the key in {@code application.yml}.
   *
   * <p>Assumptions: the absence of a TLS section is asserted deliberately rather than left unstated, so that
   * a reader of this class is not left to wonder whether the seven-service case was forgotten here. What
   * would be a defect in this module is a development overlay that WIDENED the bind -- which is a plausible
   * local convenience, since a loopback listener cannot be reached from a developer's browser -- and that is
   * the assertion the winning-source check makes.
   */
  @Test
  @DisplayName("keeps the probe listener on loopback and adds no listener of its own")
  void keepsTheProbeListenerOnLoopbackAndAddsNoListenerOfItsOwn() {
    assertThat(dev.require("server.address")).isEqualTo("127.0.0.1");
    assertThat(dev.require("server.port")).isEqualTo("8080");
    assertThat(dev.sourceOf("server.address"))
        .hasValueSatisfying(name -> assertThat(name).doesNotContain(DEV_DOCUMENT));
    assertThat(dev.value("server.ssl.enabled")).isEmpty();
    assertThat(dev.value("server.ssl.key-store")).isEmpty();
  }
}
