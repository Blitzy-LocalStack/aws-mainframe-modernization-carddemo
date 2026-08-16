/**
 * Executable consumer for the fixed-width fixtures under {@code src/test/resources/fixtures}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package holds one class, {@code BatchFixtureContractTest}, and it exists because the
 * per-scenario job runs cannot cover the corpus as a whole. Sixteen scenario directories across four
 * domains hold 62 record files; section 1.5 of {@code src/test/resources/fixtures/README.md} measures
 * that 53 are opened as job input and nine are supporting images no job run reads. A fixture nobody
 * loads proves nothing, and a fixture a job loads still proves nothing about the fields that job does
 * not assert: its record length, its field offsets, the one-cent and one-day boundary pairs it
 * encodes, the relationships that make a reject scenario reject, and the emptiness that drives a
 * create path are all claims, and a claim no test reads cannot fail. This class turns each of them
 * into an assertion, and its digest check turns the remaining bytes into one too, so no value in the
 * corpus can move by one cent, one day or one byte with every test staying green.</p>
 *
 * <p>Assumptions: the class does <em>not</em> re-run the batch jobs over these bytes. All four
 * domains are driven end to end elsewhere -- the posting corpus by {@code PostTransactionsJobTest}
 * and {@code PostTransactionsJobParityIT}, the interest corpus by {@code CalculateInterestJobTest},
 * three preflight scenarios by {@code PreflightDailyTransactionsJobTest} and one export file by
 * {@code ExportJobTest} -- so duplicating those runs here would assert the jobs twice while adding
 * nothing about the nine supporting images or about any unasserted field. What this class asserts
 * instead is the corpus itself: its census, its geometry, its governance rules, the discriminating
 * values its scenario READMEs state, and its byte identity against the manifest of section 11.5.</p>
 *
 * <p>Refactoring Rationale: the two paragraphs above previously stated 51 record files, five of them
 * driven, and that "the interest job reads the reference tree under {@code tests/}". Every one of
 * those figures and that claim was stale -- the interest job's {@code FIXTURE_INTEREST_ROOT} is this
 * module's own {@code fixtures/interest/}, and both posting classes seed from {@code fixtures/posting/}.
 * They are corrected here as well as in the fixture contract because this file is what a reader opens
 * to find out why the package exists, and a stale reason invites the conclusion that the package is
 * redundant now that the jobs read the tree. It is not: the nine supporting images and every
 * unasserted field still have no other consumer.</p>
 *
 * <p>Assumptions: the tests live in their own package rather than beside the job tests, because
 * their subject is the <em>resource directory</em> rather than any one production type. The sibling
 * services reached the same arrangement independently -- {@code com.carddemo.transaction.fixtures}
 * and {@code com.carddemo.reporting.fixtures} -- so the location is a house convention rather than a
 * local choice. Alternatives Considered: folding the assertions into the job test classes, which
 * would avoid a package. Rejected because the census assertion is closed-set, naming every scenario
 * that may exist, and a closed set stated in several classes is a set that drifts.</p>
 *
 * <p>Assumptions: nothing in this package is a production type, so it declares no public API, holds
 * no state and is never referenced from {@code src/main}. It is compiled only by the test
 * compilation unit.</p>
 */
package com.carddemo.batch.fixtures;
