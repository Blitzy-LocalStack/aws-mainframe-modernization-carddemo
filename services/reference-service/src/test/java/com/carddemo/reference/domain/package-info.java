/**
 * Tests over the reference domain entities, and in particular over the two composite keys.
 *
 * <p>Purpose: the entities in {@code com.carddemo.reference.domain} are mappings, so most of their
 * content is asserted by the migration test that starts a throwaway database and lets the provider
 * compare each mapping against the Flyway output. What that comparison cannot reach is the behaviour
 * the two composite-key types carry in their constructors, because a provider populating a key
 * reflectively never calls them. Those guards are what this package exercises.</p>
 *
 * <p>Assumptions: no test here opens a database or a Spring context. Each case constructs a key or an
 * entity directly, which is why they belong in the unit phase rather than beside the integration
 * tests, and why they run on every build rather than only where a container is available.</p>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java a package's entry point is its package declaration. Checkstyle enforces this with
 * {@code JavadocPackage} over the file set and {@code MissingJavadocPackage} over the parsed tree,
 * and it audits test sources as well as main sources, so this descriptor is required rather than
 * decorative. The written convention it follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.domain;
