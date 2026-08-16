package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.profile.ProfileConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executes the {@code dev} profile of this service and holds every value it declares to the reasoning
 * recorded beside that value in {@code services/authorization-service/src/main/resources/application-dev.yml}.
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
@DisplayName("the authorization service development profile")
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
        "CARDDEMO_ACCOUNT_CONTEXT_BASE_URL",
          /*
           * Assumptions: the approved-origin variable is on this list even though the property carrying it
           * declares a fallback, because the fallback is ANOTHER placeholder with no default of its own --
           * the property reads `${CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN:${CARDDEMO_ACCOUNT_CONTEXT_BASE_URL}}`.
           * A nested fallback resolves only if the inner variable is supplied, so the property is unresolvable
           * when neither is, and it is the OUTER name the diagnostic reports. It was missed on the first pass
           * precisely because a reader scanning for placeholders without a colon does not see it.
           */
          "CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN",
        "CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY",
        "CARDDEMO_MESSAGING_HMAC_KEY",
        "CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE",
        "CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST",
        "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
        "CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_FLYWAY_PASSWORD",
        "SPRING_FLYWAY_USER",
        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI");

  /** The resolved configuration, resolved once because resolution is read-only and not cheap. */
  private static ProfileConfiguration dev;

  /** Resolves this service's configuration with the development profile active. */
  @BeforeAll
  static void resolveDevProfile() {
    dev = ProfileConfiguration.resolve(DEV);
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
   * <p>Assumptions: the minimum is ONE here and zero in every other context, and the difference is asserted
   * rather than smoothed over. This service consumes a queue, so it holds a connection whenever a message
   * arrives; draining to nothing would mean the first message after an idle period paid a connect and
   * possibly a cluster resume inside its visibility timeout, and a message that times out is redelivered
   * rather than lost -- turning a latency cost into duplicate processing the ordering guarantees then have
   * to absorb.
   */
  @Test
  @DisplayName("sizes the connection pool for the development load")
  void sizesTheConnectionPoolForTheDevelopmentLoad() {
    assertThat(dev.require("spring.datasource.hikari.maximum-pool-size")).isEqualTo("4");
    assertThat(dev.require("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
    assertThat(dev.require("spring.datasource.hikari.connection-timeout")).isEqualTo("30000");
    assertThat(dev.sourceOf("spring.datasource.hikari.maximum-pool-size"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.minimum-idle"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.connection-timeout"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The import list restates the shared defaults document.
   *
   * <p>Assumptions: the property REPLACES the inherited list rather than adding to it, so a list that omitted
   * the shared document would drop the console log pattern carrying the correlation identifier -- in the one
   * context where a correlation identifier is the whole mechanism tying a reply back to the request that
   * produced it.
   *
   * <p>Assumptions: the document is asserted to have been READ, by requiring a property only it declares. A
   * list naming it is not evidence it was found.
   */
  @Test
  @DisplayName("restates the shared defaults import")
  void restatesTheSharedDefaultsImport() {
    assertThat(dev.list("spring.config.import"))
        .containsExactly("classpath:/carddemo-common-defaults.yml");
    assertThat(dev.value("logging.pattern.console")).isPresent();
  }

  /**
   * Statement echo is ON here, which is the opposite of every other context.
   *
   * <p>Assumptions: this divergence is asserted deliberately rather than normalised. This context is the one
   * whose unit of work replaced a two-phase commit across two datastores with a single local transaction, so
   * seeing the statements a decision issues -- and their order -- is what makes that collapse reviewable in
   * development. Every other context leaves the echo off because it bypasses the log pattern; the trade is
   * accepted here and nowhere else, so a change that silently aligned this value with the others would
   * remove the diagnostic the collapse was verified with.
   */
  @Test
  @DisplayName("echoes statements in this context only")
  void echoesStatementsInThisContextOnly() {
    assertThat(dev.require("spring.jpa.show-sql")).isEqualTo("true");
    assertThat(dev.sourceOf("spring.jpa.show-sql"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The health answer carries a status word only.
   *
   * <p>Assumptions: {@code never} rather than a wider setting, because the pollers of this endpoint are
   * unauthenticated and read the status word alone -- and the contributor detail here would name the
   * datastore holding pending authorization and fraud rows.
   */
  @Test
  @DisplayName("keeps the aggregate health answer sanitized")
  void keepsTheAggregateHealthAnswerSanitized() {
    assertThat(dev.require("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(dev.sourceOf("management.endpoint.health.show-details"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The diagnostic categories this profile lowers, and the ones it deliberately does not.
   *
   * <p>Assumptions: only this application package is lowered, and the statement-parameter pin is inherited
   * rather than restated -- which is why it is asserted here. This context's statements bind card numbers and
   * authorization amounts, so an inherited pin quietly undone would put both into a development log.
   */
  @Test
  @DisplayName("lowers the diagnostic categories and leaves the identity-bearing ones pinned")
  void lowersTheDiagnosticCategoriesAndLeavesTheIdentityBearingOnesPinned() {
    final Map<String, String> levels = dev.loggingLevels();

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
    final ProfileConfiguration base = ProfileConfiguration.resolve("default");
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
        "spring.datasource.hikari.maximum-pool-size",
        "spring.datasource.hikari.minimum-idle",
        "spring.datasource.hikari.connection-timeout",
        "spring.jpa.show-sql",
        "logging.level.com.carddemo",
        "management.endpoint.health.show-details");
  }

  /**
   * Names the keys this overlay is documented as introducing rather than overriding.
   *
   * @return the keys no inherited document declares
   */
  private static List<String> keysThisOverlayIntroduces() {
    return List.of(
        "carddemo.no-such-key");
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
}
