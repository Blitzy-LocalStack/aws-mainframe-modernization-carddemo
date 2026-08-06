/**
 * Tests for the reporting module's process entry point and its orchestrated-task argument contract.
 *
 * <p>Assumptions: this package exists because Checkstyle enforces {@code JavadocPackage} at Checker level
 * over the test sources as well as the main sources, so a test package with no {@code package-info.java}
 * fails the build at the {@code validate} phase rather than at review.</p>
 *
 * <p>Assumptions: every test here runs WITHOUT an application context. The argument contract is validated
 * at the process boundary before any context is built, which is exactly what makes it exercisable on a
 * machine with no database, no parameter store and no credentials -- and asserting it here is what keeps
 * that property from being lost.</p>
 */
package com.carddemo.reporting;
