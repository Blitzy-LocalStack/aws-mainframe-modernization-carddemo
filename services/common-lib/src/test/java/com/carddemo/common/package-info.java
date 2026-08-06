/**
 * Verifies that the shared kernel's components are actually reached by a consumer's application
 * context.
 *
 * <h2>What this package asserts, and why it is not a unit concern</h2>
 *
 * <p>Every other package in this test tree asserts what one class computes. This one asserts something
 * no unit test can see: whether the class is instantiated at all. The shared kernel publishes four
 * cross-cutting components -- the correlation filter, the common-tag meter contribution, the money
 * codec module and the single error advice -- and none of them is reached unless the registration
 * resource under {@code META-INF/spring}, the class-level conditions on the two nested servlet
 * configurations and the consumer's own classpath all agree. Each way that agreement can fail is
 * silent: a service starts, serves requests, and emits log lines with no correlation identity, meters
 * with no service dimension, amounts on the wire as bare JSON numbers, or failures rendered in the
 * framework's own shape rather than the migrated one. The assertions here start real contexts and read
 * the assembled bean graph, which is the only level at which that class of defect is visible.</p>
 *
 * <p>Assumptions: the absences are asserted as deliberately as the presences. A non-web context must
 * receive the clock and the money module and must NOT receive the filter or the advice, and it must
 * start rather than fail while omitting them -- which is what proves the nested conditions are decided
 * from class-file metadata before a servlet return type is resolved. That distinction is the whole
 * reason the two nested configurations exist, and the batch context is the consumer that depends on
 * it.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>This package holds two {@code .java} files and no others -- this descriptor and
 * {@code CardDemoCommonAutoConfigurationIT}.</p>
 *
 * <p>Assumptions: that class is named {@code ...IT} rather than {@code ...Test} because the reactor
 * splits the two by name: Surefire runs {@code *Test} in the test phase and Failsafe runs {@code *IT}
 * at the integration-test phase, asserting the result in {@code verify}. The name is correct on content
 * -- it starts a context and asserts on a graph rather than on one unit -- and it is also what gives the
 * Failsafe binding something to run. Nothing external is contacted: no container, no database and no
 * network endpoint, so the class is fast and its result depends on nothing outside the reactor.</p>
 *
 * <p>Assumptions: this descriptor exists because the Java documentation gate audits test sources on the
 * same terms as main sources -- {@code services/pom.xml} sets {@code includeTestSourceDirectory} on the
 * Checkstyle execution bound to Maven's {@code validate} phase. {@code JavadocPackage} requires a
 * descriptor to exist in any directory holding an audited source and {@code MissingJavadocPackage}
 * requires it to carry Javadoc, so an empty file would satisfy the first and fail the second. The
 * Explainability rule's parameter, return and exception elements describe callable code and have no
 * counterpart on a package declaration, so they are omitted deliberately rather than written empty; an
 * at-clause with no description would itself be reported by {@code NonEmptyAtclauseDescription}.</p>
 */
package com.carddemo.common;
