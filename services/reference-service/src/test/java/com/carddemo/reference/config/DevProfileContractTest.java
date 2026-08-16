package com.carddemo.reference.config;

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
 * recorded beside that value in {@code services/reference-service/src/main/resources/application-dev.yml}.
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
@DisplayName("the reference service development profile")
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
        "AWS_REGION",
        "CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE",
        "CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE",
        "CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE",
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
   * <p>Assumptions: the lifetime ceiling is asserted alongside the idle timeout because the two bound
   * different things and only both together make the pool drain. An idle connection is retired after the
   * idle timeout, but a connection the listener keeps busy is not idle -- the lifetime ceiling is what
   * eventually retires that one, so omitting it would leave a queue consumer holding capacity awake
   * indefinitely while every idle-based bound reported the pool as behaving correctly.
   */
  @Test
  @DisplayName("sizes the connection pool for the development load")
  void sizesTheConnectionPoolForTheDevelopmentLoad() {
    assertThat(dev.require("spring.datasource.hikari.maximum-pool-size")).isEqualTo("4");
    assertThat(dev.require("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
    assertThat(dev.require("spring.datasource.hikari.connection-timeout")).isEqualTo("30000");
    assertThat(dev.require("spring.datasource.hikari.idle-timeout")).isEqualTo("60000");
    assertThat(dev.require("spring.datasource.hikari.max-lifetime")).isEqualTo("240000");
    assertThat(dev.sourceOf("spring.datasource.hikari.maximum-pool-size"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.minimum-idle"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.connection-timeout"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.idle-timeout"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
    assertThat(dev.sourceOf("spring.datasource.hikari.max-lifetime"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /*
   * WHY : Refactoring Rationale: a case asserting `spring.cloud.aws.sqs.listener.max-concurrent-messages`
   *       and `max-messages-per-poll` at two stood here, bounding the date-conversion consumer to
   *       development volume. It is withdrawn with the consumer itself: this module binds no queue
   *       listener at all -- `ReferenceServiceStructureTest` asserts that none remains, and the base
   *       document records the whole family of queue-related absences -- so both keys are absent from
   *       every overlay and a `require` on either could only fail.
   * WHY : Assumptions: the sizing property is not merely moved here, it moves WITH the consumer. The
   *       shared inquiry request queue admits exactly one consumer and that consumer now lives in
   *       account-service, so its own dev overlay is where the two bounds are declared and its own
   *       profile contract is where they are asserted. Leaving an assertion here would test a document
   *       this module does not own.
   */

  /**
   * Health detail is available to an authenticated caller and to nobody else.
   *
   * <p>Assumptions: {@code when-authorized} rather than full disclosure, because the pollers of this endpoint
   * are unauthenticated and read the status word alone. What the detail would add for them is nothing; what
   * it would add for anything else able to reach the task is the datasource and migration state of the
   * reference schema.
   */
  @Test
  @DisplayName("moves health detail behind an authenticated caller")
  void movesHealthDetailBehindAnAuthenticatedCaller() {
    assertThat(dev.require("management.endpoint.health.show-details")).isEqualTo("when-authorized");
    assertThat(dev.require("management.endpoint.health.show-components")).isEqualTo("when-authorized");
    assertThat(dev.sourceOf("management.endpoint.health.show-details"))
        .hasValueSatisfying(name -> assertThat(name).contains(DEV_DOCUMENT));
  }

  /**
   * The diagnostic categories this profile lowers, and the ones it deliberately does not.
   *
   * <p>Assumptions: DEBUG is set on this application package and on no other, so the output stays the lines
   * this context's own diagnosis needs. The statement-parameter pin is inherited rather than restated here,
   * which is why it is asserted: an inherited pin is exactly the kind of intent a later edit undoes without
   * noticing, and this context's statements carry the seeded reference codes an address validation reads.
   */
  @Test
  @DisplayName("lowers the diagnostic categories and leaves the identity-bearing ones pinned")
  void lowersTheDiagnosticCategoriesAndLeavesTheIdentityBearingOnesPinned() {
    final Map<String, String> levels = dev.loggingLevels();

    assertThat(levels).containsEntry("com.carddemo", "DEBUG");
    assertThat(levels).containsEntry("org.hibernate.SQL", "DEBUG");
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
        "spring.datasource.hikari.idle-timeout",
        "spring.datasource.hikari.max-lifetime",
        "management.endpoint.health.show-details",
        "management.endpoint.health.show-components",
        "logging.level.com.carddemo",
        "logging.level.org.hibernate.SQL");
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
