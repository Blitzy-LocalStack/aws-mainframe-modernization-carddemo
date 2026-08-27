/**
 * Tests for the startup contract: the environment check every service inherits and its refusal.
 *
 * <p>Purpose. Three properties of this package decide whether the check is worth having, and none of
 * them is visible from a signature. It must NAME every absent variable together with the property
 * that reads it, because the failure it replaces named nothing. It must stay silent about the two
 * shapes that look similar and are not deployment inputs -- a placeholder carrying a default, and a
 * lower-case dotted reference to another property in the same document -- because a check that
 * reports a correct configuration is a check that gets switched off. And it must actually be reached
 * by Spring Boot, which is a property of a registration resource rather than of any method here.
 *
 * <p>Assumptions: the detector is exercised against hand-built environments rather than by starting
 * eight services. A {@code StandardEnvironment} with named property sources reproduces every input
 * the check reads -- precedence order, raw unresolved text, the platform sources -- and lets a case
 * state one property of the algorithm at a time, which a whole-service start cannot.
 *
 * <p>Assumptions: one case starts a real {@code SpringApplication} even so. Discovery through
 * {@code META-INF/spring.factories}, the ordering that puts this check after configuration data is
 * loaded, and the framework's rendering of the failure are all properties of the framework's own
 * wiring, and a direct call to {@code postProcessEnvironment} asserts none of them. That case is what
 * makes the others' subject the thing a deployment actually runs.
 *
 * <p>Trade-offs: the enforcement guard means the check is INERT in this module's own test JVM unless
 * a case opts in, so every case that expects a refusal sets the opt-in property explicitly. The cost
 * is one line per case; the alternative -- a check that fires under a test harness -- would fail
 * roughly sixty correctly configured integration tests across the reactor, which take their database
 * from a connection-details bean rather than from a property.
 *
 * <p>Assumptions: the four rationale labels used here -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are written in the
 * plural, colon-terminated, without parentheses and with the ASCII hyphen-minus, which is the only
 * accepted spelling across the migration trees.
 */
package com.carddemo.common.config;
