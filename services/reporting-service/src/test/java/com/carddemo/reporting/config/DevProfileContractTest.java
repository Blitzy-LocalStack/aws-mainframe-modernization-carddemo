package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.common.profile.ProfileConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Executes the {@code dev} profile of this service and holds every value it declares to the reasoning
 * recorded beside that value in {@code services/reporting-service/src/main/resources/application-dev.yml}.
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
@DisplayName("the reporting service development profile")
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
        "CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY",
        "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET",
        "CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN",
        "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
        "CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI");


  /**
   * The framework property a service would declare if it pinned its own region.
   *
   * <p>Assumptions: this service deliberately declares it NOWHERE, and the case below asserts that absence.
   * The constant exists so the spelling asserted here is the spelling a future document would have to use.
   *
   * <p>Refactoring Rationale: this is declared ahead of the seed map below and is read by BOTH -- the map
   * seeds this key and the absence case asserts this key -- because the two were previously the same string
   * written twice. A test that seeds one spelling and asserts the absence of another passes while proving
   * nothing, and nothing in the build compares two literals.
   */
  private static final String REGION_PROPERTY = "spring.cloud.aws.region.static";

  /**
   * The whole property family the region lives in, so a sibling key cannot slip past the exact-key check.
   *
   * <p>Assumptions: this is DERIVED from {@link #REGION_PROPERTY} rather than written out, so the two cannot
   * disagree about the family. The family matters because the case below claims the documents declare no
   * region at all, and the framework offers more than one way to declare one -- an instance-profile source
   * and a default-chain switch sit beside the static value under the same parent. Asserting only the static
   * key would leave that claim true of one spelling and untested for the others.
   */
  private static final String REGION_PROPERTY_FAMILY =
      REGION_PROPERTY.substring(0, REGION_PROPERTY.lastIndexOf('.') + 1);

  /**
   * The values configuration RESOLUTION itself needs, as distinct from those a bean reads later.
   *
   * <p>Assumptions: a static region is seeded here rather than listed among the platform-supplied variables
   * because of WHEN it is needed. This service's inherited import list names a parameter-store and a
   * secrets-manager location, and although each carries the {@code optional:} prefix, that prefix covers a
   * location that cannot be FOUND -- it does not cover a client that cannot be CONSTRUCTED. Without a region
   * the resolver falls through the whole default provider chain and fails before any document is read.
   *
   * <p>Assumptions: the key is the framework's own static-region property rather than the {@code AWS_REGION}
   * environment variable, and that is deliberate. This service's configuration declares no region property of
   * its own, so the resolver falls back to the SDK's provider chain -- which reads an operating-system
   * environment variable or a system property, neither of which a Spring property source can supply. Seeding
   * the framework property is what a deployment does when it pins a region, and it is the only form reachable
   * from here. {@link #theRemoteImportLocationsNeedARegionThisServiceDoesNotDeclare()} pins the coupling
   * either way: it reads the shipped documents to show this service declares no region of its own, and it
   * shows that seeding one is sufficient for resolution to complete.
   */
  private static final Map<String, String> RESOLUTION_TIME_VALUES =
      Map.of(REGION_PROPERTY, "us-east-1");

  /** The property whose list names the remote configuration locations a region is needed for. */
  private static final String CONFIG_IMPORT_PROPERTY = "spring.config.import";

  /** The remote parameter location the inherited import list names. */
  private static final String REMOTE_PARAMETER_LOCATION = "aws-parameterstore";

  /** The remote secret location the inherited import list names. */
  private static final String REMOTE_SECRET_LOCATION = "aws-secretsmanager";

  /** The prefix that makes a location survivable when it cannot be found. */
  private static final String OPTIONAL_LOCATION_PREFIX = "optional:";

  /** The configuration documents this service ships, both of which are read as text by the case below. */
  private static final List<String> SHIPPED_DOCUMENTS =
      List.of("/application.yml", "/application-dev.yml");
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
   * <p>Assumptions: two connections is the smallest ceiling of any context, and it is asserted as a figure
   * rather than a floor. This context owns no schema and reads through cross-schema views under a
   * select-only role, so its concurrency is one report request at a time plus the statement that streams it;
   * a wider pool would claim connections from a small development cluster's budget that nothing here would
   * ever use.
   */
  @Test
  @DisplayName("sizes the connection pool for the development load")
  void sizesTheConnectionPoolForTheDevelopmentLoad() {
    assertThat(dev.require("spring.datasource.hikari.maximum-pool-size")).isEqualTo("2");
    assertThat(dev.require("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
    assertThat(dev.sourceOf("spring.datasource.hikari.maximum-pool-size"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.minimum-idle"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * A report statement fetches in bounded batches rather than materialising its whole result.
   *
   * <p>Assumptions: the fetch size is asserted because it is what makes a report of arbitrary length run in
   * fixed memory. A statement that materialised its whole result would hold every row of a transaction
   * report in the heap at once, and the 133-column output this context produces is written a line at a time
   * precisely so it does not have to.
   */
  @Test
  @DisplayName("streams a report with a bounded fetch")
  void streamsAReportWithABoundedFetch() {
    assertThat(dev.require("spring.jpa.properties.hibernate.jdbc.fetch_size")).isEqualTo("100");
    assertThat(dev.sourceOf("spring.jpa.properties.hibernate.jdbc.fetch_size"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The aggregate health answer carries a status word only, with detail in an authorized group.
   *
   * <p>Refactoring Rationale: a role entry stood beside these keys and was withdrawn, because which
   * principals may read an endpoint is not a control this document can enforce -- the filter chain decides
   * it. What this document can decide is how much the endpoint DISCLOSES, which is what the two settings
   * below do.
   *
   * <p>Assumptions: the named group is asserted as well as the aggregate, because the detail was moved rather
   * than deleted. Asserting only the aggregate would pass for a profile that had removed the diagnostic
   * answer entirely.
   */
  @Test
  @DisplayName("keeps the aggregate health answer sanitized and moves detail into a group")
  void keepsTheAggregateHealthAnswerSanitized() {
    assertThat(dev.require("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(dev.require("management.endpoint.health.show-components")).isEqualTo("never");
    assertThat(dev.require("management.endpoint.health.group.diagnostics.show-details")).isEqualTo("when-authorized");
    assertThat(dev.require("management.endpoint.health.group.diagnostics.show-components")).isEqualTo("when-authorized");
    assertThat(dev.list("management.endpoint.health.group.diagnostics.include"))
        .contains("db", "diskSpace", "ping");
  }

  /**
   * The diagnostic categories this profile lowers, and the ones it deliberately does not.
   *
   * <p>Assumptions: root stays at INFO while this application package drops to DEBUG, so the output is this
   * context's own report and statement production rather than every framework package beneath it. The
   * statement-parameter pin is inherited and asserted, because this context reads account, card and
   * transaction rows through views and its bound values are those rows' keys.
   */
  @Test
  @DisplayName("lowers the diagnostic categories and leaves the identity-bearing ones pinned")
  void lowersTheDiagnosticCategoriesAndLeavesTheIdentityBearingOnesPinned() {
    final Map<String, String> levels = dev.loggingLevels();

    assertThat(levels).containsEntry("root", "INFO");
    assertThat(levels).containsEntry("com.carddemo", "DEBUG");
    assertThat(levels).containsEntry("org.hibernate.orm.jdbc.bind", "WARN");
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
        "logging.level.root",
        "logging.level.com.carddemo",
        "spring.datasource.hikari.maximum-pool-size",
        "spring.datasource.hikari.minimum-idle",
        "spring.jpa.properties.hibernate.jdbc.fetch_size",
        "management.endpoint.health.show-details",
        "management.endpoint.health.show-components",
        "management.endpoint.health.group.diagnostics.include",
        "management.endpoint.health.group.diagnostics.show-details",
        "management.endpoint.health.group.diagnostics.show-components");
  }

  /**
   * Names the keys this overlay is documented as introducing rather than overriding.
   *
   * @return the keys no inherited document declares
   */
  private static List<String> keysThisOverlayIntroduces() {
    /*
     * Assumptions: each member is a key no inherited document declares, so the overlay is the first place it
     * appears. The named diagnostics GROUP is created here by definition -- a group is only ever declared by
     * whoever declares its members -- and the aggregate component-visibility key is here because the base
     * document sets the detail visibility alone, so the overlay is what first states that the contributor
     * list is withheld as well. Each is listed rather than silently excluded from the comparison, so the
     * exception is as reviewable as the rule it is an exception to.
     */
    return List.of(
        "management.endpoint.health.group.diagnostics.include",
        "management.endpoint.health.group.diagnostics.show-details",
        "management.endpoint.health.group.diagnostics.show-components",
        "management.endpoint.health.show-components");
  }

  /**
   * The remote import locations this service inherits need a region it does not itself declare.
   *
   * <p>Purpose: record the coupling that is invisible in every other way. The import locations are marked
   * optional, which reads as "this service starts fine without a platform" -- and that is true only once a
   * region is available, because {@code optional:} covers a location that cannot be FOUND, not a client that
   * cannot be CONSTRUCTED. A deployment supplying every credential and endpoint but pinning no region fails
   * while reading configuration, with a message from the AWS provider chain naming no property of this
   * application.
   *
   * <p>⚠️ Refactoring Rationale: this previously asserted that {@code resolve(DEV)} THROWS. That assertion
   * was not the project's to make, because the precondition it depends on -- that no region is reachable --
   * cannot be established from inside the test. This service declares no region property, so the resolver
   * falls through to the AWS provider chain, and that chain reads {@code System.getenv()} directly rather
   * than any Spring property source. Any machine exporting {@code AWS_REGION} therefore resolves
   * successfully and the case fails; {@code /opt/carddemo-tools/svc-common.env} exports both
   * {@code AWS_REGION} and {@code AWS_DEFAULT_REGION}, so that is the documented runtime, not an edge case.
   * Measured: the whole 851-case suite is green with no region exported and fails this one case with
   * {@code AWS_REGION=us-east-1} alone. Note that no amount of Spring-level environment substitution can
   * fix it -- the read happens inside the SDK -- which is why the assertion itself had to change.
   *
   * <p>Alternatives Considered: skipping the case when a region is ambient. Rejected because a case that
   * silently skips is indistinguishable from one that silently passes, and this coupling is the reason a
   * region-less deployment fails in a way that names nothing of ours.
   *
   * <p>Assumptions: what is asserted instead is the CAUSE, in three parts that are each read from the
   * documents and are therefore immune to the surrounding machine: the remote locations exist and are the
   * ones a client must be built for; this service declares no region property of its own, which is exactly
   * why the SDK chain is consulted at all; and supplying a region is SUFFICIENT for resolution to complete.
   * Together those state the same coupling the throwing assertion was reaching for.
   */
  @Test
  @DisplayName("the inherited remote import locations need a region this service does not declare")
  void theRemoteImportLocationsNeedARegionThisServiceDoesNotDeclare() {
    List<String> remoteLocations = dev.list(CONFIG_IMPORT_PROPERTY).stream()
        .filter(location -> location.contains(REMOTE_PARAMETER_LOCATION)
            || location.contains(REMOTE_SECRET_LOCATION))
        .toList();
    assertThat(remoteLocations)
        .as("a region is only needed while remote locations must have clients built for them")
        .hasSize(2)
        .anyMatch(location -> location.contains(REMOTE_PARAMETER_LOCATION))
        .anyMatch(location -> location.contains(REMOTE_SECRET_LOCATION))
        // WHY : Assumptions: the optional prefix is asserted on the REMOTE locations only. The classpath
        //   entry beside them is not optional and must not be -- it carries the shared defaults every
        //   profile inherits -- so asserting the prefix across the whole list would assert the opposite
        //   of what the document intends.
        .allMatch(location -> location.startsWith(OPTIONAL_LOCATION_PREFIX));

    // WHY : Alternatives Considered: asserting this through the resolved configuration, either as an
    //   absent rawValue or as a sourceOf naming the harness seed. BOTH were rejected after being tried:
    //   this resolution seeds the region itself, so rawValue answers "us-east-1" regardless, and the seed
    //   source is added at HIGHEST precedence by design, so sourceOf names the seed even when a document
    //   declares the key too. The second reads like a real assertion and has no teeth at all -- verified
    //   by adding the property to application.yml and watching it still pass. The shipped documents are
    //   therefore read directly, which is the only form of this fact that can fail.
    // WHY : Refactoring Rationale: the direct read stays, and what changed is WHAT is read out of it. This
    //   was a text search for a bare `region:` line, which recognised exactly one of the five legal ways
    //   the key can be written: the nested block form. `spring.cloud.aws.region.static: us-east-1` written
    //   flat, `region: {static: us-east-1}` written inline, a quoted segment and an anchored alias all
    //   passed it, so the case could report that no region is declared against a document declaring one.
    //   Each document is now loaded by the framework's own YAML loader and its BOUND property names are
    //   inspected, which collapses every spelling to one name before the assertion sees it.
    for (String document : SHIPPED_DOCUMENTS) {
      final Set<String> declared = declaredPropertyNames(document, documentText(document));

      assertThat(declared)
          .as("%s must declare NO region block: this service leaves the region to the platform, which is"
              + " precisely why the AWS provider chain is consulted and why a region-less deployment"
              + " fails with a message naming nothing of ours", document)
          .doesNotContain(REGION_PROPERTY);
      assertThat(declared.stream().filter(name -> name.startsWith(REGION_PROPERTY_FAMILY)).toList())
          .as("%s must declare no key at all under %s: an instance-profile source or a default-chain"
              + " switch pins the region just as effectively as the static value, so the claim this case"
              + " makes is only true if the whole family is absent", document, REGION_PROPERTY_FAMILY)
          .isEmpty();
    }

    assertThatCode(() -> ProfileConfiguration.resolveWith(RESOLUTION_TIME_VALUES, DEV))
        .as("supplying the region is sufficient for resolution to complete")
        .doesNotThrowAnyException();
  }

  /**
   * The absence check above reads every legal spelling of the region key, and the text search did not.
   *
   * <p>Purpose: hold the check that guards an ABSENCE to its own sensitivity. An absence assertion passes
   * both when the key is genuinely undeclared and when the check cannot see the form it was written in, and
   * those two outcomes are indistinguishable from the report. The only way to tell them apart is to feed the
   * check documents that DO declare the key and require it to say so.
   *
   * <p>Refactoring Rationale: the withdrawn pattern is exercised here as well, on the flattened form, so the
   * blind spot is recorded as a failing expression rather than as a claim in a comment. It matched a bare
   * {@code region:} line only, which is one spelling out of five; the same defect returning would have to
   * survive this case as well as review.
   *
   * <p>Assumptions: five spellings are covered because each is legal YAML that the framework binds to one
   * name -- the flat dotted key, the fully nested block, the inline flow mapping, a quoted final segment and
   * an anchored value reached through an alias. A sixth form is asserted NOT to declare it: a sibling key
   * whose name merely starts with the same characters, which must not be read as the region key.
   *
   * <p>This case takes no parameter and returns no value.
   */
  @Test
  @DisplayName("the region-absence check reads every legal spelling of the key alike")
  void theRegionAbsenceCheckReadsEverySpellingAlike() {
    assertThat(declaredPropertyNames("flattened.yml", "spring.cloud.aws.region.static: us-east-1\n"))
        .as("a flat dotted key is the form a hand-written override most often takes")
        .contains(REGION_PROPERTY);
    assertThat(declaredPropertyNames(
            "nested.yml", "spring:\n  cloud:\n    aws:\n      region:\n        static: us-east-1\n"))
        .as("the fully nested block is the only form the withdrawn text search recognised")
        .contains(REGION_PROPERTY);
    assertThat(declaredPropertyNames(
            "inline.yml", "spring:\n  cloud:\n    aws:\n      region: {static: us-east-1}\n"))
        .as("an inline flow mapping keeps the key on the same line as its parent")
        .contains(REGION_PROPERTY);
    assertThat(declaredPropertyNames(
            "quoted.yml", "spring:\n  cloud:\n    aws:\n      region:\n        \"static\": us-east-1\n"))
        .as("a quoted segment binds to the same name as an unquoted one")
        .contains(REGION_PROPERTY);
    assertThat(declaredPropertyNames(
            "aliased.yml",
            "pinned: &pinned us-east-1\nspring:\n  cloud:\n    aws:\n      region:\n        static: *pinned\n"))
        .as("an alias resolves to its anchor's value, so the key is declared as surely as if inlined")
        .contains(REGION_PROPERTY);
    assertThat(declaredPropertyNames("sibling.yml", "spring.cloud.aws.region.statics: us-east-1\n"))
        .as("a name that merely starts with the key's characters is a different property")
        .doesNotContain(REGION_PROPERTY);

    // WHY : Refactoring Rationale: this is the withdrawn check, held to the input it could not see. It is
    //   asserted here rather than described in prose because a comment claiming a check was blind cannot
    //   fail if the check is ever reinstated, whereas this expression can.
    assertThat("spring.cloud.aws.region.static: us-east-1")
        .as("the withdrawn text search matched a bare block key only, so a flattened declaration was"
            + " invisible to it and the absence it reported was its own blindness")
        .doesNotContainPattern("(?m)^\\s*region:\\s*$");
  }

  /**
   * The listener stays encrypted under this profile, and its key material stays platform-supplied.
   *
   * <p>Refactoring Rationale: transport security is asserted HERE, in the development overlay's own contract,
   * because a development document is the likeliest place for it to be relaxed: turning the listener plain is
   * the shortest route to a working local browser session, and an overlay that did so would leave every gate
   * green while a task published a card-bearing API over clear text. Nothing else in the build resolved this
   * profile at all before this class, so the relaxation would have been invisible.
   *
   * <p>Assumptions: the winning source is asserted NOT to be the development document. The point is not that
   * these values are correct -- the base document sets them and other tests read it -- but that this overlay
   * leaves them alone. Asserting the value alone would pass just as happily against a dev document that
   * restated the same value today and drifted tomorrow.
   *
   * <p>Assumptions: the keystore password is asserted to be an UNRESOLVED placeholder rather than a value.
   * It carries no default anywhere, which is what makes a task started without real key material fail at
   * start-up instead of falling back to a well-known password committed in a document; the path and the alias
   * do carry defaults, and those defaults are asserted so a change to either is a deliberate edit here.
   */
  @Test
  @DisplayName("keeps the listener encrypted and its key material platform-supplied")
  void keepsTheListenerEncryptedAndItsKeyMaterialPlatformSupplied() {
    assertThat(dev.require("server.ssl.enabled")).isEqualTo("true");
    assertThat(dev.require("server.ssl.key-store-type")).isEqualTo("PKCS12");
    assertThat(dev.require("server.ssl.protocol")).isEqualTo("TLS");
    assertThat(dev.list("server.ssl.enabled-protocols")).containsExactly("TLSv1.2", "TLSv1.3");
    assertThat(dev.require("server.ssl.key-store")).isEqualTo("file:/tmp/carddemo-tls/listener.p12");
    assertThat(dev.require("server.ssl.key-alias")).isEqualTo("carddemo-listener");
    assertThat(dev.sourceOf("server.ssl.enabled"))
        .hasValueSatisfying(name -> assertThat(name).doesNotContain(DEV_DOCUMENT));
    assertThat(dev.sourceOf("server.ssl.enabled-protocols"))
        .hasValueSatisfying(name -> assertThat(name).doesNotContain(DEV_DOCUMENT));
    assertThat(dev.unresolvablePlaceholders())
        .containsEntry("server.ssl.key-store-password", "CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD");
  }

  /**
   * Reads one configuration document's text as the property names the framework would bind from it.
   *
   * <p>Assumptions: the framework's own YAML loader does the parsing, so the names this returns are the names
   * a running service would bind rather than names a bespoke walk happened to compose. A key's spelling in a
   * document is not its bound name -- flat, nested, inline, quoted and aliased forms all bind to one name --
   * so collapsing every spelling through the loader is what makes a single exact-key assertion sufficient.
   *
   * <p>Alternatives Considered: parsing with a plain YAML library and flattening the tree here. Rejected
   * because the flattening convention IS the contract under test: the loader decides how a list index, a
   * quoted segment and an empty mapping are named, and reimplementing that decision would make this method
   * agree with the framework only by coincidence.
   *
   * <p>Alternatives Considered: keeping the text search this replaced. Rejected on measurement -- it reads
   * one spelling out of five, and {@link #theRegionAbsenceCheckReadsEverySpellingAlike()} exercises the
   * other four against it.
   *
   * @param documentName name the parsed source is reported under, used only in diagnostics, never
   *     {@code null}
   * @param yamlText the document's full text, never {@code null}
   * @return every property name the document declares, in the loader's own order; empty when the document
   *     declares nothing, never {@code null}
   * @throws java.io.UncheckedIOException when the text cannot be loaded as YAML
   */
  private static Set<String> declaredPropertyNames(final String documentName, final String yamlText) {
    final ByteArrayResource resource =
        new ByteArrayResource(yamlText.getBytes(java.nio.charset.StandardCharsets.UTF_8), documentName);
    final Set<String> names = new java.util.LinkedHashSet<>();
    try {
      /*
       * Assumptions: the loader returns ONE property source per YAML document, so a file separated by
       * `---` is read as several and every one of them is walked. Taking only the first would silently
       * stop reading at the first separator, and a key declared in a later document would then be
       * reported as absent -- the same class of false absence this method was written to remove.
       */
      for (final org.springframework.core.env.PropertySource<?> source :
          new YamlPropertySourceLoader().load(documentName, resource)) {
        if (source instanceof EnumerablePropertySource<?> enumerable) {
          names.addAll(List.of(enumerable.getPropertyNames()));
        }
      }
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException("could not parse " + documentName + " as YAML", failure);
    }
    return names;
  }

  /**
   * Reads a shipped configuration document from the classpath as text.
   *
   * <p>Assumptions: the document is read as TEXT rather than parsed, because what the caller asserts is the
   * ABSENCE of a key -- and an absence cannot be observed through the resolved configuration, where a value
   * this class seeds itself is present regardless of what any document declares.
   *
   * @param resource absolute classpath location of the document, never {@code null}
   * @return the document's full text, never {@code null}
   * @throws IllegalStateException when the document is absent from the classpath
   * @throws java.io.UncheckedIOException when the document cannot be read
   */
  private static String documentText(final String resource) {
    try (java.io.InputStream stream = DevProfileContractTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("shipped configuration document is absent: " + resource);
      }
      return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException("could not read " + resource, failure);
    }
  }
}
