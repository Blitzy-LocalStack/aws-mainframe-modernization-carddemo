package com.carddemo.common.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the shared profile harness reports what Spring Boot would actually resolve, so the eight
 * per-service dev-profile contract tests rest on a measured tool rather than an assumed one.
 *
 * <h2>Why the harness itself is tested</h2>
 *
 * <p>Assumptions: this class exists because a harness that silently reported nothing would make every
 * dev-profile contract test pass. Each case below therefore pins one behaviour a consumer depends on,
 * and each is written so that a harness returning empty results fails it: precedence is asserted through
 * the WINNING SOURCE NAME as well as the value, the unresolvable set is asserted to contain a specific
 * member and to exclude another, and both list shapes are asserted to yield members.
 *
 * <p>Assumptions: the fixture documents are {@code carddemo-profile-harness.yml} and its
 * {@code devcheck} profile document beside this class's resources, not a service's real configuration.
 * A real service's values belong to that service and would change under this class for reasons that
 * have nothing to do with the harness.
 */
@DisplayName("the shared dev-profile resolution harness")
final class ProfileConfigurationTest {

  /** Base name of the fixture documents, which is deliberately not {@code application}. */
  private static final String FIXTURE = "carddemo-profile-harness";

  /** The fixture profile, deliberately not {@code dev} so it cannot interact with the real one. */
  private static final String PROFILE = "devcheck";

  /**
   * Resolves the fixture documents with the fixture profile active.
   *
   * @return the resolved configuration
   */
  private static ProfileConfiguration fixture() {
    return ProfileConfiguration.resolveNamed(FIXTURE, PROFILE);
  }

  /** Reports the profile the resolution was asked for as active. */
  @Test
  @DisplayName("reports the requested profile as active")
  void reportsTheRequestedProfileAsActive() {
    assertThat(fixture().activeProfiles()).containsExactly(PROFILE);
  }

  /**
   * A profile document's value wins, and the harness names the document that decided it.
   *
   * <p>Assumptions: the SOURCE NAME is asserted as well as the value, and that is the half a value
   * comparison cannot supply. A dev document that sat below the base document in precedence would leave
   * the base value in force, which is indistinguishable from a successful override whenever the two
   * documents happen to agree -- and agreeing is the normal case for most keys.
   */
  @Test
  @DisplayName("prefers the profile document and names the winning source")
  void prefersTheProfileDocumentAndNamesTheWinningSource() {
    final ProfileConfiguration resolved = fixture();

    assertThat(resolved.require("carddemo.harness.overridden")).isEqualTo("profile-value");
    assertThat(resolved.sourceOf("carddemo.harness.overridden"))
        .hasValueSatisfying(name -> assertThat(name).contains(PROFILE));
  }

  /** A value only the base document declares survives the profile activation. */
  @Test
  @DisplayName("keeps a value the profile document does not restate")
  void keepsAValueTheProfileDocumentDoesNotRestate() {
    assertThat(fixture().require("carddemo.harness.inherited")).isEqualTo("inherited-value");
  }

  /**
   * A placeholder with no default is reported as unresolvable, under the variable it names.
   *
   * <p>Assumptions: the map is keyed by PROPERTY and carries the VARIABLE, and both halves are asserted.
   * A consumer asserting that a service's unresolvable set is exactly its platform-injected set needs
   * the variable names; a consumer diagnosing a typo needs the property that referenced it.
   */
  @Test
  @DisplayName("reports a placeholder with no default as unresolvable")
  void reportsAPlaceholderWithNoDefaultAsUnresolvable() {
    final Map<String, String> unresolvable = fixture().unresolvablePlaceholders();

    assertThat(unresolvable)
        .containsEntry("carddemo.harness.platform-supplied", "CARDDEMO_HARNESS_ABSENT_VARIABLE");
  }

  /**
   * A placeholder carrying a default resolves and is absent from the unresolvable set.
   *
   * <p>Assumptions: this is the control for the case above. Without it, a harness that reported EVERY
   * placeholder as unresolvable would satisfy that case, and a service's contract test would then be
   * unable to tell a platform-injected value from a defaulted one.
   */
  @Test
  @DisplayName("resolves a placeholder carrying a default")
  void resolvesAPlaceholderCarryingADefault() {
    final ProfileConfiguration resolved = fixture();

    assertThat(resolved.require("carddemo.harness.defaulted")).isEqualTo("fallback-value");
    assertThat(resolved.unresolvablePlaceholders()).doesNotContainKey("carddemo.harness.defaulted");
  }

  /** The raw declaration of a platform-supplied value is readable without resolution. */
  @Test
  @DisplayName("reads a raw declaration without resolving it")
  void readsARawDeclarationWithoutResolvingIt() {
    assertThat(fixture().rawValue("carddemo.harness.platform-supplied"))
        .hasValue("${CARDDEMO_HARNESS_ABSENT_VARIABLE}");
  }

  /**
   * Both list shapes yield their members in declared order.
   *
   * <p>Assumptions: the inline and block forms are asserted together, because a YAML document may use
   * either and the two bind to DIFFERENT property names -- a scalar for the inline form, indexed
   * properties for the block form. A harness handling only one would silently return an empty list for
   * the other, and a test asserting on a list's members would then pass for a list it never read.
   */
  @Test
  @DisplayName("reads both the inline and block list shapes")
  void readsBothTheInlineAndBlockListShapes() {
    final ProfileConfiguration resolved = fixture();

    assertThat(resolved.list("carddemo.harness.inline-list"))
        .containsExactly("alpha", "beta", "gamma");
    assertThat(resolved.list("carddemo.harness.block-list"))
        .containsExactly("first", "second", "third");
  }

  /** An absent list is empty rather than a failure. */
  @Test
  @DisplayName("reports an absent list as empty")
  void reportsAnAbsentListAsEmpty() {
    assertThat(fixture().list("carddemo.harness.no-such-list")).isEmpty();
  }

  /**
   * A subtree carries every member, including one whose value cannot be resolved.
   *
   * <p>Assumptions: the platform-supplied member is asserted PRESENT in the subtree. Omitting an
   * unresolvable member would make a subtree containing a platform-injected value look shorter than it
   * is, and a consumer sweeping a subtree to prove a document declares what it should would then miss
   * exactly the keys the platform fills in.
   */
  @Test
  @DisplayName("lists a subtree including its unresolvable members")
  void listsASubtreeIncludingItsUnresolvableMembers() {
    final Map<String, String> subtree = fixture().subtree("carddemo.harness");

    assertThat(subtree)
        .containsKeys(
            "carddemo.harness.overridden",
            "carddemo.harness.inherited",
            "carddemo.harness.platform-supplied",
            "carddemo.harness.defaulted");
    assertThat(subtree.get("carddemo.harness.platform-supplied"))
        .isEqualTo("${CARDDEMO_HARNESS_ABSENT_VARIABLE}");
  }

  /**
   * Logging levels from the base and profile documents both appear.
   *
   * <p>Assumptions: BOTH categories are asserted, because the two documents contribute to one map and a
   * profile document that replaced the map rather than adding to it would drop the base category -- the
   * same replace-versus-merge behaviour that makes an actuator exposure list load-bearing.
   */
  @Test
  @DisplayName("merges logging levels from both documents")
  void mergesLoggingLevelsFromBothDocuments() {
    final Map<String, String> levels = fixture().loggingLevels();

    assertThat(levels).containsEntry("com.carddemo.harness.base", "INFO");
    assertThat(levels).containsEntry("com.carddemo.harness.profile", "DEBUG");
  }

  /** An absent property is reported as empty rather than as a failure. */
  @Test
  @DisplayName("reports an absent property as empty")
  void reportsAnAbsentPropertyAsEmpty() {
    final ProfileConfiguration resolved = fixture();

    assertThat(resolved.value("carddemo.harness.no-such-key")).isEmpty();
    assertThat(resolved.sourceOf("carddemo.harness.no-such-key")).isEmpty();
  }

  /** Requiring an absent property fails with the key named. */
  @Test
  @DisplayName("fails naming the key when a required property is absent")
  void failsNamingTheKeyWhenARequiredPropertyIsAbsent() {
    final ProfileConfiguration resolved = fixture();

    assertThatThrownBy(() -> resolved.require("carddemo.harness.no-such-key"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("carddemo.harness.no-such-key");
  }

  /**
   * Resolving with no profile is refused.
   *
   * <p>Assumptions: this is refused rather than defaulted, because a harness that quietly resolved the
   * base documents when handed no profile would let a consumer's contract test assert on the base values
   * while believing it had activated {@code dev} -- the exact failure the harness exists to prevent.
   */
  @Test
  @DisplayName("refuses a resolution naming no profile")
  void refusesAResolutionNamingNoProfile() {
    assertThatThrownBy(() -> ProfileConfiguration.resolve(new String[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one profile");
  }

  /** A resolution under the default base name still honours an explicitly named profile. */
  @Test
  @DisplayName("honours the requested profile under the default base name")
  void honoursTheRequestedProfileUnderTheDefaultBaseName() {
    final List<String> active = ProfileConfiguration.resolve(PROFILE).activeProfiles();

    assertThat(active).containsExactly(PROFILE);
  }
}
