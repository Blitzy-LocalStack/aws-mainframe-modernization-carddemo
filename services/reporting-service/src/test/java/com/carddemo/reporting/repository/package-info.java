/**
 * Integration tests for the read-only data-access layer of the reporting bounded context.
 *
 * <h2>Purpose</h2>
 *
 * <p>This directory holds the tests that need a real database engine to say anything at all, and
 * there are two of them because there are two such properties.
 *
 * <p>The first is whether the queries PARSE. The five repository roles beside them declare queries --
 * derived from method names and, for the report join, written out -- and a query that names a property
 * the metamodel does not carry, or joins two entities on a path that does not exist, is a defect no
 * compiler reports and no unit test reaches. It surfaces when the repository proxy is created, which
 * needs an entity manager factory, which needs a data source. {@code ReportingQueryBootstrapIT} makes
 * that claim and deliberately creates no relation, because parsing happens against the metamodel.
 *
 * <p>The second is whether the statement heading walk's keyset predicate reproduces its own
 * {@code ORDER BY}. {@code StatementHeadingChunkIT} makes that claim, and unlike the first it needs
 * ROWS: a predicate that compares one component of a two-component ordering parses perfectly, so the
 * first class passes on it, and a mocked repository answers whatever it is arranged to answer, so the
 * service's unit tests pass on it too. It supplies three relations as plain tables through a
 * Testcontainers init script under {@code db/testharness}, and that script records at length why a
 * stand-in is used rather than the real views. Refactoring Rationale: the second class was added
 * after exactly that defect shipped -- cards were skipped and repeated by a chunk walk with no gate
 * anywhere able to see it -- so the directory's purpose is stated as two properties rather than one.
 *
 * <p>Assumptions: the tests here are named with the integration suffix, so the surefire execution
 * that runs the module's unit tests does not select them and the failsafe execution does. That split
 * is the module's own convention rather than this directory's, and it matters here because starting a
 * container is measured in seconds while the unit tests of this module are measured in milliseconds.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) requires a docstring on every
 * module entry point, a Java package declaration is one, and a {@code package-info.java} Javadoc
 * block is the only construct able to carry it. Two Checkstyle checks act on it and neither is
 * redundant: {@code JavadocPackage} requires the file to be present in a package holding Java
 * sources, and {@code MissingJavadocPackage} requires it to carry Javadoc. Of the four content
 * elements the rule enumerates, only Purpose applies -- a package declaration accepts no parameter,
 * yields no value and raises nothing, so the other three are inapplicable rather than omitted, and no
 * at-clause is invented to stand in for one of them.
 */
package com.carddemo.reporting.repository;
