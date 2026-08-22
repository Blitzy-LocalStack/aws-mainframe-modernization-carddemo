/**
 * Executable consumers for the fixed-width fixtures under {@code src/test/resources/fixtures}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package holds one class, {@code ReportingFixtureContractTest}, and it exists because the
 * fixture files it consumes previously had no consumer at all. It binds every fixture named by its
 * own {@code EXPECTED_RESOURCES} list and registers each one in its {@code everyFixture} argument
 * source against the descriptor, record length and row count that fixture claims; those two members
 * are the roster, and this charter deliberately does not restate it. A fixture nobody loads proves
 * nothing: its record length, its field offsets, its key domain and the synthetic origin of its
 * values are all claims, and a claim no test reads cannot fail. The class here turns each of those
 * claims into an assertion, and it also drives the two mappers of the sibling
 * {@code com.carddemo.reporting.mapper} package with fixture rows, so the fixtures are consumed as
 * data and not merely as bytes.</p>
 *
 * <p>Refactoring Rationale: this paragraph named a roster of SEVEN files and listed them. That was
 * accurate when it was written and is not now -- {@code tranfile.txt}, {@code trnxfile.txt} and
 * {@code xreffile.txt} landed afterwards -- so the charter under-reported the bound set by three
 * while the inventory assertion in this package remained CLOSED-SET, which is the reading that does
 * real harm: someone reconciling the directory against this text would have taken three bound
 * fixtures for arrivals nobody had accounted for. The roster is now referenced rather than copied,
 * because a list restated in a second place has to be re-tallied by hand every time one lands, and
 * this is the second time that re-tally was missed. Alternatives Considered: restating the roster at
 * ten. Rejected for the reason the drift itself demonstrates -- the count and the names are already
 * asserted in one place, and a duplicate that a build cannot check is a duplicate that goes
 * stale.</p>
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
