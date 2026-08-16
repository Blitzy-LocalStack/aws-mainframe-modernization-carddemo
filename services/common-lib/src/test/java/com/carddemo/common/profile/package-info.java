/**
 * Publishes the shared harness each service's dev-profile contract test resolves its configuration
 * through, so the {@code dev} profile is executed by the build rather than only by a deployment.
 *
 * <h2>What this package contains</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter is a MEASUREMENT of
 * the directory beside it, not a target. The package holds this descriptor,
 * {@code ProfileConfiguration} and {@code ProfileConfigurationTest}, and the charter is maintained
 * against those files in the same change that adds or withdraws one. A count stated here that a reader
 * cannot confirm by listing the directory is a defect in this file rather than a plan for the
 * directory. The marker line below is re-measured on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * which counts the {@code .java} files beside this descriptor and holds the figures to them:</p>
 *
 * <pre>
 * this directory: 3 java files = 2 classes + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code ProfileConfiguration}</li>
 *   <li>{@code ProfileConfigurationTest}</li>
 * </ul>
 *
 * <h2>Why a harness lives in the shared kernel's test tree</h2>
 *
 * <p>Refactoring Rationale: the eight services each ship an {@code application-dev.yml}, and until this
 * package existed no test anywhere activated {@code dev}. The consequence was not a coverage gap in the
 * ordinary sense but a class of defect the build could not see at all: a misspelled property key binds
 * to nothing and reports nothing; a profile document that REPLACES an inherited collection rather than
 * merging into it silently removes the members it did not restate; and a placeholder naming an
 * environment variable no deployment supplies fails when a task starts, which is after every gate has
 * passed.
 *
 * <p>Assumptions: the harness is published here rather than copied into each service because a copy per
 * service is eight places for the resolution logic to drift, and each service's contract test would
 * then be asserting against its own copy of the thing under test. It reaches the services through the
 * {@code tests}-classified artifact this module already publishes and every service already declares at
 * test scope; the packaging filter in {@code services/common-lib/pom.xml} names this package explicitly,
 * and a class added here that is not in that filter compiles locally and is absent from the artifact.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Assumptions: this package holds NO assertion about any individual service's dev values. Those
 * belong beside the service that declares them -- the pool ceiling that is right for one bounded context
 * is wrong for another, and a central table of eight services' expected values would be a second place
 * for each to drift from. What is shared is the resolution, not the expectation.
 *
 * <p>Assumptions: the harness is not a test and is not scanned as one. {@code services/pom.xml}
 * restricts the scanned test classes of this module's artifact to the layering rule alone, so the
 * harness is on each service's test classpath without being executed there; the case that exercises the
 * harness itself is {@code ProfileConfigurationTest}, which runs here.
 */
package com.carddemo.common.profile;
