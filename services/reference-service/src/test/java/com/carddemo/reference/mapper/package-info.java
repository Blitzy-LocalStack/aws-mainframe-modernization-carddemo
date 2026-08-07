/**
 * Tests for the hand-written anti-corruption layer of the reference bounded context.
 *
 * <p>Assumptions: this charter exists because Checkstyle's {@code JavadocPackage} rule audits a DIRECTORY
 * rather than a compilation unit, and this module's documentation gate includes test sources. A package that
 * holds a test class and no charter fails the build, so the charter has to govern the directory as a whole.</p>
 *
 * <p>What belongs here: assertions about the types in the corresponding main-source package, and nothing else.
 * A test that needed a type from a different layer to make its point belongs beside that layer, because a test
 * placed for convenience rather than for subject is a test a later reader cannot find from the code it
 * covers.</p>
 *
 * <p>Trade-offs: these tests substitute their collaborators rather than loading a Spring context or starting a
 * listener container. That buys assertions that name the exact value or the exact byte that was wrong, at the
 * cost of proving nothing about bean wiring -- which is asserted separately, by the configuration tests in the
 * {@code config} package of this module.</p>
 */
package com.carddemo.reference.mapper;
