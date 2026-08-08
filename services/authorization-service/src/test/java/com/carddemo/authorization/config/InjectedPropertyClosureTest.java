package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.security.OpaqueIdentifier;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts that every property this service injects into Java is a property its own configuration declares,
 * and that exactly one keyed messaging tokeniser bean is declared anywhere in the configuration package.
 *
 * <h2>Purpose</h2>
 *
 * <p>Refactoring Rationale: a component-scanned configuration class resolved
 * {@code ${carddemo.security.mask-hmac-key}} after that property had been withdrawn from
 * {@code application.yml}, and it declared a second, unqualified tokeniser bean beside the qualified one
 * every consumer actually injects. The consequence was total: an undefaulted {@code @Value} naming a
 * property no property source declares is an unresolvable placeholder, so every context refresh failed and
 * the service could not start at all. The sibling {@code EnvironmentClosureTest} could not see it, because
 * that test compares YAML placeholders against the infrastructure parameter maps and this placeholder was
 * in Java. This class closes that side of the same loop.</p>
 *
 * <p>Assumptions: the authority for "declared" is this module's own {@code application.yml}. A property
 * injected in Java has to be reachable from the configuration the service ships, because the profile
 * overlays add values for keys the base file already names and the environment supplies the values those
 * keys interpolate -- so a key absent from the base file is absent everywhere, which is exactly the
 * condition that stopped start-up.</p>
 *
 * <p>Alternatives Considered: asserting only that the withdrawn key has no consumer, by name. Rejected
 * because it would close one instance and leave the class of defect open: the next property withdrawn from
 * the YAML would fail the same way and this test would still pass. Checking closure over every injected
 * property makes the assertion about the invariant rather than about the incident.</p>
 *
 * <p>Trade-offs: the scan reads compiled annotations through reflection rather than parsing source, so a
 * placeholder assembled at run time from concatenated strings would not be seen. That is accepted because
 * no such placeholder exists in this service and one would be a defect on its own terms -- an injected
 * property name that cannot be read from the class file cannot be reviewed either.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class InjectedPropertyClosureTest {

    /** The package whose compiled classes are scanned for injected properties. */
    private static final String SERVICE_PACKAGE = "com.carddemo.authorization";

    /** The configuration package that must declare exactly one keyed messaging tokeniser. */
    private static final String CONFIG_PACKAGE = "com.carddemo.authorization.config";

    /** The base configuration file that is the authority for which properties exist. */
    private static final String BASE_CONFIGURATION = "/application.yml";

    /** The property withdrawn by the finding, named so the closure failure reads unambiguously. */
    private static final String WITHDRAWN_KEY = "carddemo.security.mask-hmac-key";

    /**
     * Matches one placeholder and captures its property name, stopping before any default part.
     *
     * <p>Assumptions: the name group excludes both the closing brace and the colon that introduces a
     * default, so {@code ${a.b:fallback}} yields {@code a.b} and is then judged by whether a default was
     * present. Capturing through the colon would produce a name no property source could ever hold and
     * every defaulted placeholder would fail.</p>
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(:[^}]*)?}");

    /**
     * One injected property, as read off a compiled annotation.
     *
     * @param key the property name the placeholder names
     * @param defaulted whether the placeholder carried a default, which makes an absent property harmless
     * @param site the declaring class and member, named so a failure points at the injection rather than
     *     at this test
     */
    private record InjectedProperty(String key, boolean defaulted, String site) {
    }

    /**
     * Reads every {@code @Value} placeholder the service's compiled classes carry.
     *
     * <p>Assumptions: fields, methods, constructors AND their parameters are all inspected, because a
     * property can be injected at any of the four and the defect this class answers was on a method
     * parameter. Inspecting only fields -- the form most examples show -- would have missed it entirely.</p>
     *
     * @return every injected property found, in encounter order; never {@code null}
     */
    private static List<InjectedProperty> injectedProperties() {
        JavaClasses imported = new ClassFileImporter().importPackages(SERVICE_PACKAGE);
        List<InjectedProperty> found = new ArrayList<>();
        for (JavaClass javaClass : imported) {
            Class<?> reflected = javaClass.reflect();
            for (Field field : reflected.getDeclaredFields()) {
                collect(field.getAnnotations(), reflected, field.getName(), found);
            }
            for (Method method : reflected.getDeclaredMethods()) {
                collect(method.getAnnotations(), reflected, method.getName(), found);
                collectParameters(method.getParameterAnnotations(), reflected, method.getName(), found);
            }
            for (Constructor<?> constructor : reflected.getDeclaredConstructors()) {
                collect(constructor.getAnnotations(), reflected, "<init>", found);
                collectParameters(constructor.getParameterAnnotations(), reflected, "<init>", found);
            }
        }
        return found;
    }

    /**
     * Adds every placeholder carried by one member's annotations.
     *
     * @param annotations the annotations declared on the member
     * @param owner the declaring class, named in the failure site
     * @param member the member name, named in the failure site
     * @param found the accumulating list, appended to in place
     */
    private static void collect(Annotation[] annotations, Class<?> owner, String member,
            List<InjectedProperty> found) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Value value) {
                Matcher matcher = PLACEHOLDER.matcher(value.value());
                while (matcher.find()) {
                    found.add(new InjectedProperty(matcher.group(1), matcher.group(2) != null,
                            owner.getSimpleName() + "#" + member));
                }
            }
        }
    }

    /**
     * Adds every placeholder carried by one code unit's parameter annotations.
     *
     * @param parameterAnnotations one annotation array per parameter, in declaration order
     * @param owner the declaring class, named in the failure site
     * @param member the member name, named in the failure site
     * @param found the accumulating list, appended to in place
     */
    private static void collectParameters(Annotation[][] parameterAnnotations, Class<?> owner,
            String member, List<InjectedProperty> found) {
        for (Annotation[] onOneParameter : parameterAnnotations) {
            collect(onOneParameter, owner, member, found);
        }
    }

    /**
     * Flattens this service's base configuration into the set of dotted property names it declares.
     *
     * @return every declared property name; never {@code null}
     * @throws IOException if the packaged configuration cannot be read, which would mean the module was
     *     built without its own resources and no assertion here would be meaningful
     */
    private static Set<String> declaredProperties() throws IOException {
        try (InputStream configuration =
                InjectedPropertyClosureTest.class.getResourceAsStream(BASE_CONFIGURATION)) {
            assertThat(configuration).as("the packaged %s must be readable", BASE_CONFIGURATION)
                    .isNotNull();
            Map<String, Object> tree = new Yaml().load(
                    new String(configuration.readAllBytes(), StandardCharsets.UTF_8));
            Set<String> names = new LinkedHashSet<>();
            flatten("", tree, names);
            return names;
        }
    }

    /**
     * Walks one configuration subtree, recording the dotted name of every leaf and of every node.
     *
     * <p>Assumptions: intermediate nodes are recorded as well as leaves, because a property may be injected
     * at a node that carries structured content -- a list, or a map bound as a whole -- and treating only
     * scalar leaves as declared would report such an injection as unresolvable when it resolves fine.</p>
     *
     * @param prefix the dotted path accumulated so far, empty at the root
     * @param node the subtree to walk, which is a map, a list or a scalar
     * @param names the accumulating name set, appended to in place
     */
    private static void flatten(String prefix, Object node, Set<String> names) {
        if (!prefix.isEmpty()) {
            names.add(prefix);
        }
        if (node instanceof Map<?, ?> children) {
            for (Map.Entry<?, ?> child : children.entrySet()) {
                String name = prefix.isEmpty()
                        ? String.valueOf(child.getKey())
                        : prefix + "." + child.getKey();
                flatten(name, child.getValue(), names);
            }
        }
    }

    /**
     * Verifies the scan finds the injections it is meant to police.
     *
     * <p>Assumptions: this guard exists because every closure assertion below passes trivially on an empty
     * scan. A change that broke the reflection -- a renamed package, a build that produced no classes --
     * would otherwise turn this class green while it verified nothing at all.</p>
     */
    @Test
    @DisplayName("the scan finds the injected properties it is meant to police")
    void theScanFindsTheInjectedProperties() {
        assertThat(injectedProperties()).extracting(InjectedProperty::key)
                .contains("carddemo.messaging.hmac-key");
    }

    /**
     * Verifies every undefaulted injected property is one this service's configuration declares.
     *
     * <p>Assumptions: a defaulted placeholder is excluded, because a default is precisely the statement
     * that the property need not exist. The failure message names the injection site, since the remedy is
     * either to declare the property or to remove the injection and only the site says which.</p>
     *
     * @throws IOException if the packaged configuration cannot be read
     */
    @Test
    @DisplayName("every property injected in Java is one the packaged configuration declares")
    void everyInjectedPropertyIsDeclared() throws IOException {
        Set<String> declared = declaredProperties();

        Map<String, String> undeclared = new TreeMap<>();
        for (InjectedProperty injected : injectedProperties()) {
            if (!injected.defaulted() && !declared.contains(injected.key())) {
                undeclared.put(injected.key(), injected.site());
            }
        }

        assertThat(undeclared)
                .as("an undefaulted @Value naming a property application.yml does not declare is an "
                        + "unresolvable placeholder, so the context cannot refresh and the service cannot "
                        + "start; each entry maps the property to the injection site that names it")
                .isEmpty();
    }

    /**
     * Verifies the withdrawn masking key is injected nowhere, by name.
     *
     * <p>Assumptions: this is asserted in addition to the closure above rather than instead of it. The
     * closure would catch the key's return only while it stayed absent from the YAML; naming it here also
     * refuses the other repair someone might reach for, which is to re-declare the property. That repair
     * would restart the service and reintroduce the reason it was withdrawn -- the key belongs to a
     * migration workload that reads cardholder extracts, so keying production queue metadata with it would
     * let that workload compute any card's group identity.</p>
     */
    @Test
    @DisplayName("the withdrawn masking key is injected nowhere in this service")
    void theWithdrawnMaskingKeyIsInjectedNowhere() {
        assertThat(injectedProperties()).extracting(InjectedProperty::key)
                .as("%s is withdrawn; queue identities are keyed from carddemo.messaging.hmac-key",
                        WITHDRAWN_KEY)
                .doesNotContain(WITHDRAWN_KEY);
    }

    /**
     * Verifies exactly one bean method in the configuration package supplies a keyed tokeniser.
     *
     * <p>Assumptions: the assertion is over declared bean METHODS rather than over beans in a running
     * context, because the defect was a second configuration class that a context could not even be built
     * with. Counting methods sees the duplicate whether or not the context it would have produced can
     * start, and it names the class that declares the extra one.</p>
     */
    @Test
    @DisplayName("exactly one bean method in the configuration package supplies a keyed tokeniser")
    void exactlyOneBeanMethodSuppliesAKeyedTokeniser() {
        JavaClasses configuration = new ClassFileImporter().importPackages(CONFIG_PACKAGE);

        List<String> suppliers = new ArrayList<>();
        for (JavaClass javaClass : configuration) {
            for (Method method : javaClass.reflect().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)
                        && OpaqueIdentifier.class.equals(method.getReturnType())) {
                    suppliers.add(javaClass.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(suppliers)
                .as("a second tokeniser bean is injected by nobody and keyed differently, so replies "
                        + "published through it would fall into a different ordering group for the same "
                        + "card")
                .containsExactly("MessagingIdentityConfig#messagingOpaqueIdentifier");
    }

    /**
     * Verifies a context built on the tokeniser configuration holds exactly one, under its qualified name.
     *
     * <p>Assumptions: the runner carries no auto-configuration, so the only beans present are the ones the
     * configuration under test declares. That is what makes a count of one meaningful: with
     * auto-configuration active the count would depend on what else the classpath happened to contribute.</p>
     */
    @Test
    @DisplayName("a context on the tokeniser configuration holds exactly one, under its qualified name")
    void aContextHoldsExactlyOneTokeniserUnderItsQualifiedName() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(MessagingIdentityConfig.class)
                .withPropertyValues("carddemo.messaging.hmac-key=" + "0123456789abcdef".repeat(2))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OpaqueIdentifier.class))
                            .containsOnlyKeys(MessagingIdentityConfig.MESSAGING_TOKENISER);
                });
    }

    /**
     * Verifies the tokeniser configuration refuses to start with no key rather than defaulting one.
     *
     * <p>Assumptions: failing start-up is the required behaviour and not merely the observed one. A default
     * would key every environment's queue identities from a value computable out of the source tree, which
     * is the same disclosure the tokeniser exists to prevent, and it would do so silently.</p>
     */
    @Test
    @DisplayName("the tokeniser configuration refuses to start with no key rather than defaulting one")
    void theTokeniserConfigurationRefusesToStartWithNoKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(MessagingIdentityConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }
}
