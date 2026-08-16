package com.carddemo.common.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Resolves one service's configuration exactly as Spring Boot resolves it at start-up, for a chosen
 * set of active profiles, so a test can assert on the result without starting an application.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Refactoring Rationale: before this harness, no test anywhere in the repository activated the
 * {@code dev} profile. Every service ships an {@code application-dev.yml} that overrides pool sizing,
 * listener concurrency, actuator exposure, logging levels and, in two services, the
 * {@code spring.config.import} list -- and none of it was executed by anything. A defect in one of
 * those documents therefore could not fail a build: a misspelled key binds to nothing silently, a
 * profile document that replaces an inherited collection instead of merging into it removes members
 * silently, and a placeholder naming an environment variable that no deployment supplies fails only
 * when a task starts.
 *
 * <h2>Why the real machinery rather than a YAML parse</h2>
 *
 * <p>Alternatives Considered: parsing the {@code application-dev.yml} document directly with SnakeYAML,
 * which is the pattern
 * {@code services/authorization-service/src/test/java/com/carddemo/authorization/config/ProfileConfigurationTopologyTest.java}
 * uses for its own purposes. Rejected for this job because a raw parse cannot answer the questions that
 * actually matter here. It sees one document rather than the ordered set of property sources boot
 * assembles, so it cannot tell whether a dev value OVERRIDES the base value or merely sits beside it;
 * it does not follow {@code spring.config.import}, so the two services whose dev overlay restates that
 * list would go unchecked precisely where restating it is load-bearing; and it does not resolve
 * placeholders, so an unresolvable one is invisible to it. {@link ConfigDataEnvironmentPostProcessor}
 * is the same component boot itself runs, so what this harness observes is what a task would observe.
 *
 * <p>Assumptions: the harness resolves from the CLASSPATH rather than from a file path. Each service's
 * own test run has that service's {@code src/main/resources} on its classpath, so
 * {@code application.yml} and its profile documents are found the way the packaged image finds them --
 * and a document that failed to be packaged is a failure this harness reports rather than one it works
 * around by reading the source tree.
 *
 * <h2>Trade-offs accepted</h2>
 *
 * <p>Trade-offs: resolution runs with no environment variables injected, which is deliberate. The
 * services intentionally leave every endpoint, credential and identifier as a placeholder with NO
 * default, so that a task started without them fails rather than reaching whichever database the host
 * happens to resolve. Running with nothing injected is therefore the only way to establish WHICH
 * placeholders are platform-supplied, and {@link #unresolvablePlaceholders()} turns that into an
 * assertable set: a typo in a placeholder name shows up as an unexpected member of it.
 */
public final class ProfileConfiguration {

  /**
   * Matches a single property placeholder and captures the name it references.
   *
   * <p>Assumptions: the name capture stops at a colon so a placeholder carrying a default is reported
   * under its NAME rather than under name-plus-default. A placeholder with a default always resolves,
   * so it never reaches the unresolvable set; the capture is written this way for the diagnostic
   * message, which reads better naming the variable than naming the whole expression.
   */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

  /** The base name boot resolves configuration documents under unless told otherwise. */
  private static final String DEFAULT_CONFIG_NAME = "application";

  /** The property the config-data machinery reads a non-default base name from. */
  private static final String CONFIG_NAME_KEY = "spring.config.name";

  /** Name of the synthetic source carrying seeded resolution-time values, used in diagnostics. */
  private static final String SEED_SOURCE = "carddemo-profile-harness-seed";

  /** The resolved environment, carrying every property source boot would have assembled. */
  private final ConfigurableEnvironment environment;

  /** Every property name contributed by an enumerable source, in source order. */
  private final Set<String> propertyNames;

  /**
   * Wraps a resolved environment.
   *
   * @param resolved the environment {@link ConfigDataEnvironmentPostProcessor} has already processed
   * @param names every property name the resolved sources contribute
   */
  private ProfileConfiguration(final ConfigurableEnvironment resolved, final Set<String> names) {
    this.environment = resolved;
    this.propertyNames = names;
  }

  /**
   * Resolves the calling module's configuration with the supplied profiles active.
   *
   * <p>Assumptions: a {@link StandardEnvironment} is used rather than a mock, because the post
   * processor writes its results into a real environment and reads the system property and environment
   * variable sources a standard environment already carries. Those two sources are left in place
   * deliberately: a value the surrounding build genuinely exports is part of what a task would see, and
   * removing them would make this harness report placeholders as unresolvable that the build does in
   * fact resolve.
   *
   * @param profiles profiles to activate, in the order boot should see them; at least one is required
   * @return the resolved configuration
   * @throws IllegalArgumentException when no profile is supplied
   */
  public static ProfileConfiguration resolve(final String... profiles) {
    return resolveNamed(DEFAULT_CONFIG_NAME, Map.of(), profiles);
  }

  /**
   * Resolves configuration with a small set of values supplied BEFORE resolution runs.
   *
   * <p>Refactoring Rationale: this overload exists because resolving with nothing injected turned out to be
   * impossible for three services, and the reason is worth stating rather than working around. Their import
   * lists name remote configuration locations, and although each is prefixed {@code optional:}, that prefix
   * covers a location that cannot be FOUND -- it does not cover a client that cannot be CONSTRUCTED. A
   * region placeholder left unresolved is therefore not a value a bean reads later; it is a value the
   * config-data machinery itself needs in order to finish, and without it resolution fails with a malformed
   * endpoint rather than with a message naming the missing variable.
   *
   * <p>Assumptions: values passed here are RESOLUTION-TIME requirements and are deliberately kept separate
   * from the platform-supplied set a service's contract test declares. The distinction is the point: a
   * variable a bean reads may be absent until the bean is created, while one the import machinery reads
   * must be present before anything else happens at all. A service whose configuration cannot resolve
   * without a given variable should say so, and passing it here is how its test says it.
   *
   * @param resolutionTimeValues values the config-data machinery itself needs, keyed by property name
   * @param profiles profiles to activate, in the order boot should see them; at least one is required
   * @return the resolved configuration
   * @throws IllegalArgumentException when no profile is supplied
   */
  public static ProfileConfiguration resolveWith(
      final Map<String, String> resolutionTimeValues, final String... profiles) {
    return resolveNamed(DEFAULT_CONFIG_NAME, resolutionTimeValues, profiles);
  }

  /**
   * Resolves configuration documents carrying a chosen base name, with the supplied profiles active.
   *
   * <p>Refactoring Rationale: this overload exists so the harness can be exercised against FIXTURE
   * documents rather than only against a service's real ones. The alternative was to place an
   * {@code application.yml} in this module's test resources, which was rejected because this module
   * already runs a Spring context test of its own and a document at the default name would be picked up
   * by it -- so a fixture written to prove the harness works would change the behaviour of an unrelated
   * test. A distinct base name keeps the fixture invisible to everything that does not ask for it.
   *
   * <p>Assumptions: the base name is placed on the environment BEFORE the post processor runs, because
   * the processor reads {@code spring.config.name} from the environment it is handed. Setting it
   * afterwards would have no effect and would fail silently, which is why it is set through an explicit
   * property source rather than a system property that could leak into a sibling test.
   *
   * @param configName base name of the configuration documents, without extension or profile suffix
   * @param profiles profiles to activate, in the order boot should see them; at least one is required
   * @return the resolved configuration
   * @throws IllegalArgumentException when no profile is supplied
   */
  public static ProfileConfiguration resolveNamed(final String configName, final String... profiles) {
    return resolveNamed(configName, Map.of(), profiles);
  }

  /**
   * Resolves configuration documents carrying a chosen base name, with supplied resolution-time values.
   *
   * @param configName base name of the configuration documents, without extension or profile suffix
   * @param resolutionTimeValues values the config-data machinery itself needs, keyed by property name
   * @param profiles profiles to activate, in the order boot should see them; at least one is required
   * @return the resolved configuration
   * @throws IllegalArgumentException when no profile is supplied
   */
  public static ProfileConfiguration resolveNamed(
      final String configName,
      final Map<String, String> resolutionTimeValues,
      final String... profiles) {
    Objects.requireNonNull(configName, "configName");
    Objects.requireNonNull(resolutionTimeValues, "resolutionTimeValues");
    Objects.requireNonNull(profiles, "profiles");
    if (profiles.length == 0) {
      throw new IllegalArgumentException("at least one profile must be named");
    }
    final StandardEnvironment resolved = new StandardEnvironment();
    final Map<String, Object> seeded = new LinkedHashMap<>(resolutionTimeValues);
    if (!DEFAULT_CONFIG_NAME.equals(configName)) {
      seeded.put(CONFIG_NAME_KEY, configName);
    }
    if (!seeded.isEmpty()) {
      /*
       * Assumptions: the seed source is added FIRST so it outranks every document, and it is added before
       * the post processor runs rather than after. The machinery reads these values while it is assembling
       * the import list, so a source added afterwards would be too late and would fail silently -- the
       * resolution would already have finished, or failed, without them.
       */
      resolved.getPropertySources().addFirst(new MapPropertySource(SEED_SOURCE, seeded));
    }
    ConfigDataEnvironmentPostProcessor.applyTo(
        resolved, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);

    return new ProfileConfiguration(resolved, namesOf(resolved));
  }

  /**
   * Collects every property name the resolved sources expose, preserving source precedence order.
   *
   * @param resolved the resolved environment
   * @return the property names
   */
  private static Set<String> namesOf(final ConfigurableEnvironment resolved) {
    final Set<String> names = new LinkedHashSet<>();
    for (final PropertySource<?> source : resolved.getPropertySources()) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        names.addAll(List.of(enumerable.getPropertyNames()));
      }
    }

    return names;
  }

  /**
   * Reports the profiles the resolved environment considers active.
   *
   * @return the active profile names
   */
  public List<String> activeProfiles() {
    return List.of(this.environment.getActiveProfiles());
  }

  /**
   * Reads one property, resolving placeholders the way a bean would see it.
   *
   * @param key the property name
   * @return the resolved value, or empty when the property is absent
   * @throws IllegalArgumentException when the value carries a placeholder nothing supplies
   */
  public Optional<String> value(final String key) {
    return Optional.ofNullable(this.environment.getProperty(key));
  }

  /**
   * Reads one property that must be present and resolvable.
   *
   * @param key the property name
   * @return the resolved value
   * @throws IllegalStateException when the property is absent
   */
  public String require(final String key) {
    return this.value(key)
        .orElseThrow(() -> new IllegalStateException("no value resolved for " + key));
  }

  /**
   * Reads one property WITHOUT resolving placeholders, so the raw declaration can be inspected.
   *
   * <p>Assumptions: the raw form is read from the property sources directly rather than through the
   * environment, because the environment resolves on read and would either substitute a value or throw.
   * A test that needs to prove a document DECLARES a placeholder -- rather than a working default --
   * needs the unresolved text.
   *
   * @param key the property name
   * @return the raw declared value, or empty when the property is absent
   */
  public Optional<String> rawValue(final String key) {
    for (final PropertySource<?> source : this.environment.getPropertySources()) {
      final Object candidate = source.getProperty(key);
      if (candidate != null) {
        return Optional.of(String.valueOf(candidate));
      }
    }

    return Optional.empty();
  }

  /**
   * Names the property source that supplies a property's effective value.
   *
   * <p>Assumptions: the FIRST source holding the key wins, which is boot's own precedence rule, so the
   * name returned is the document that actually decides the value. That is what makes an override
   * assertable: a dev document that sits BELOW the base document in precedence would leave the base
   * value in force, and the value alone cannot distinguish that from a successful override when the
   * two happen to agree.
   *
   * @param key the property name
   * @return the winning source's name, or empty when no source holds the key
   */
  public Optional<String> sourceOf(final String key) {
    for (final PropertySource<?> source : this.environment.getPropertySources()) {
      if (source.getProperty(key) != null) {
        return Optional.of(source.getName());
      }
    }

    return Optional.empty();
  }

  /**
   * Reports every property whose value carries a placeholder that nothing supplies.
   *
   * <p>Assumptions: each property is read through the environment and the resulting failure is caught
   * per property rather than aborting the sweep, so one unresolvable placeholder does not hide the
   * rest. The map is keyed by property name and carries the placeholder name, because a test asserting
   * on the platform-injected set needs the variable names and a test diagnosing a typo needs the
   * property that referenced it.
   *
   * @return property name to referenced placeholder name, ordered by property name
   */
  public Map<String, String> unresolvablePlaceholders() {
    final Map<String, String> unresolvable = new TreeMap<>();
    for (final String name : this.propertyNames) {
      try {
        this.environment.getProperty(name);
      } catch (final IllegalArgumentException unresolved) {
        unresolvable.put(name, placeholderNameIn(this.rawValue(name).orElse(""), unresolved));
      }
    }

    return unresolvable;
  }

  /**
   * Extracts the placeholder name a raw value references, falling back to the failure's own message.
   *
   * @param raw the raw declared value
   * @param failure the resolution failure, used when the raw value cannot be read
   * @return the placeholder name
   */
  private static String placeholderNameIn(final String raw, final IllegalArgumentException failure) {
    final Matcher matched = PLACEHOLDER.matcher(raw);

    return matched.find() ? matched.group(1) : String.valueOf(failure.getMessage());
  }

  /**
   * Reports every property name beginning with a prefix, with its resolved value.
   *
   * <p>Assumptions: a property whose value is unresolvable is reported with its RAW text rather than
   * omitted, so a caller listing a subtree sees the whole subtree. Omitting it would make a subtree
   * containing a platform-injected value look shorter than it is.
   *
   * @param prefix the property-name prefix, with or without a trailing dot
   * @return property name to value, ordered by property name
   */
  public Map<String, String> subtree(final String prefix) {
    final String normalised = prefix.endsWith(".") ? prefix : prefix + ".";
    final Map<String, String> matched = new TreeMap<>();
    for (final String name : this.propertyNames) {
      if (!name.startsWith(normalised) && !name.equals(prefix)) {
        continue;
      }
      matched.put(name, resolvedOrRaw(name));
    }

    return matched;
  }

  /**
   * Reads a property's resolved value, or its raw text when resolution fails.
   *
   * @param name the property name
   * @return the resolved value or the raw declaration
   */
  private String resolvedOrRaw(final String name) {
    try {
      return String.valueOf(this.environment.getProperty(name));
    } catch (final IllegalArgumentException unresolved) {
      return this.rawValue(name).orElse("");
    }
  }

  /**
   * Reads a property declared as a comma-separated or indexed list into its members.
   *
   * <p>Assumptions: BOTH shapes are handled, because a YAML document may write a list either way and
   * the two bind to different property names. A block sequence binds as {@code key[0]},
   * {@code key[1]} and so on, while an inline comma-separated scalar binds as {@code key}. A test
   * asserting on the members of a list must not have to know which shape the document happened to use.
   *
   * @param key the property name
   * @return the list members in declared order, empty when the property is absent
   */
  public List<String> list(final String key) {
    final List<String> members = new ArrayList<>();
    final Optional<String> scalar = this.value(key);
    if (scalar.isPresent() && !scalar.get().isBlank()) {
      for (final String member : scalar.get().split(",")) {
        members.add(member.trim());
      }

      return List.copyOf(members);
    }
    int index = 0;
    while (true) {
      final Optional<String> indexed = this.value(key + "[" + index + "]");
      if (indexed.isEmpty()) {
        break;
      }
      members.add(indexed.get().trim());
      index += 1;
    }

    return List.copyOf(members);
  }

  /**
   * Reports the logging levels the resolved configuration sets, keyed by logger category.
   *
   * @return logger category to level, ordered by category
   */
  public Map<String, String> loggingLevels() {
    final Map<String, String> levels = new LinkedHashMap<>();
    for (final Map.Entry<String, String> entry : this.subtree("logging.level").entrySet()) {
      levels.put(entry.getKey().substring("logging.level.".length()), entry.getValue());
    }

    return Map.copyOf(levels);
  }
}
