/**
 * Integration tests for the read-only data-access layer of the reporting bounded context.
 *
 * <h2>Purpose</h2>
 *
 * <p>This directory holds the tests that need a real database engine to say anything at all. The
 * five repository roles beside them declare queries -- derived from method names and, for the report
 * join, written out -- and a query that names a property the metamodel does not carry, or joins two
 * entities on a path that does not exist, is a defect no compiler reports and no unit test reaches.
 * It surfaces when the repository proxy is created, which needs an entity manager factory, which
 * needs a data source. That is the whole reason a test in this directory starts an engine.
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
