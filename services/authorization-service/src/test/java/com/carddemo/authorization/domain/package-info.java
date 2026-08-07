/**
 * Tests for the authorization-service persistence entities: the invariants they enforce themselves,
 * and what their diagnostic renderings disclose.
 *
 * <p>Purpose: two properties of these types are expressed in code rather than delegated to the schema,
 * and neither the compiler nor a migration can hold them. The first is the set of invariants
 * {@code com.carddemo.authorization.domain} declares -- the four-value match-status domain, the
 * two-digit bound on the point-of-sale entry mode, and the two-state fraud transition with its paired
 * report date. A check constraint would enforce those too late: it fires at flush time and names a
 * column, whereas the entity refuses at the call site that chose the value. The second is disclosure.
 * This context holds the module's only composite key built from an account identifier, and the only
 * entities whose entire content is packed monetary and merchant data, so what a rendering of either
 * carries is a disclosure decision rather than a formatting preference.</p>
 *
 * <p>Assumptions: the rendering cases need a test at all because {@code toString} is invoked
 * implicitly. A rendering is reached without any call site choosing it -- a provider reports a failed
 * flush or a failed conversion by rendering the entity or its key, and a key is rendered again whenever
 * it is used as a persistence-context entry key or a map key. Nothing in the build fails when an
 * override is deleted, nor when a member is added back into one, and the resulting disclosure appears
 * only in a log file no other test reads. An assertion on the rendered string is the only mechanism
 * that turns that silent regression into a build failure.</p>
 *
 * <p>Assumptions: these tests construct entities DIRECTLY rather than driving them through the request
 * consumer or a repository, and they need neither a Spring context nor a database. The invariants
 * belong to the type and hold for every instance, including the ones an extract loader and a fixture
 * build, so a test that could only reach them through one caller would leave every other construction
 * path unasserted -- and keeping them free of a provider is what makes it affordable to assert a whole
 * parameterised domain per case rather than a representative value.</p>
 *
 * <p>Alternatives Considered: filing the domain cases in the fixtures package beside the segment-image
 * tests, which also construct entities. Rejected because those tests assert what a BYTE IMAGE decodes
 * to and are anchored to a committed resource, whereas these assert what the TYPE refuses and are
 * anchored to a copybook condition name and a migration constraint. Mixing the two would have put a
 * fixture's contract and an entity's contract under one heading, and a reader looking for either would
 * have to read both.</p>
 *
 * <p>Alternatives Considered: placing the rendering coverage in the existing
 * {@code SummaryRenderingTest} beside the served-response rendering it already pins. Rejected because
 * that class lives in {@code com.carddemo.authorization.dto} and this key's no-argument constructor is
 * package-private to {@code com.carddemo.authorization.domain}, so a case reaching the unpopulated
 * instance -- which is exactly the instance a failed-flush report renders -- could not be written from
 * there.</p>
 *
 * <p>Assumptions: three classes execute here and they divide by what they hold rather than by the type
 * they touch. {@code PendingAuthDetailDomainTest} pins the domains and bounds the detail entity refuses;
 * {@code MatchStatusOriginationTest} pins the narrower question of which of those in-domain statuses an
 * INSERT may originate -- pending on an approval, declined on a decline, and neither of the two states a
 * later transition reaches; and {@code KeyRenderingTest} pins what a rendering of the composite key
 * discloses. The middle one cannot be folded into the first: what a column may HOLD is asserted by the
 * migration's own check constraint, whereas what this type may CREATE is a construction invariant, and a
 * test that only exercised the column would accept either of two in-domain values equally.</p>
 *
 * <p>Alternatives Considered: folding the origination assertions into the mapper suite, which already
 * constructs detail rows as fixtures. Rejected because a fixture builder exists to serve assertions about
 * something else, so an invariant asserted only through one is asserted only for the argument
 * combinations that builder happens to use -- and the defect those cases exist to prevent was exactly a
 * value no fixture ever varied.</p>
 *
 * <p>Assumptions: every citation in this package is a path and a line number under
 * {@code app/app-authorization-ims-db2-mq}, which is read as the specification for these types and is
 * never modified.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the project Explainability rule's
 * docstring specification describe callable code, and are omitted from this descriptor deliberately
 * rather than written out empty, because an at-clause carrying no description is itself a violation of
 * the completeness module that audits this build.</p>
 */
package com.carddemo.authorization.domain;
