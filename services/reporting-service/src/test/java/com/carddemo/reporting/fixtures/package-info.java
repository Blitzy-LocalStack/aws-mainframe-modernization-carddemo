/**
 * Executable consumers for the fixed-width fixtures under {@code src/test/resources/fixtures}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package holds one class, {@code ReportingFixtureContractTest}, and it exists because the
 * four fixture files it consumes previously had no consumer at all. A fixture nobody loads proves
 * nothing: its record length, its field offsets, its key domain and the synthetic origin of its
 * values are all claims, and a claim no test reads cannot fail. The class here turns each of those
 * claims into an assertion, and it also drives the two mappers of the sibling
 * {@code com.carddemo.reporting.mapper} package with fixture rows, so the fixtures are consumed as
 * data and not merely as bytes.</p>
 *
 * <p>Assumptions: the tests live in their own package rather than beside the mapper tests, because
 * their subject is the <em>resource directory</em> rather than any one production type. Placing them
 * in the mapper package would attach a directory-wide contract to a class-shaped test suite, and a
 * reader looking for the fixture inventory would have no obvious place to look. Alternatives
 * Considered: folding the assertions into the two mapper test classes, which would avoid a package.
 * Rejected because the inventory assertion is closed-set -- it names every file that may exist --
 * and a closed set stated twice in two classes is a set that drifts.</p>
 *
 * <p>Assumptions: nothing in this package is a production type, so it declares no public API, holds
 * no state and is never referenced from {@code src/main}. It is compiled only by the test
 * compilation unit.</p>
 */
package com.carddemo.reporting.fixtures;
