/**
 * Tests for the reporting-service batch entry points, covering dispatch wiring, range derivation and
 * artifact keys.
 *
 * <p>Purpose: this package exists because the failure it guards against is invisible from either side.
 * The runner's own test proves a {@code --job=} token is accepted; the state-machine source proves the
 * same token is dispatched. Neither proves a bean answers to the name the runner then looks up, and the
 * module shipped in exactly that state -- three accepted, dispatched, validated names, none of which
 * resolved. A report submission was acknowledged and produced nothing, which is the worst of the
 * available outcomes because the caller was told otherwise.</p>
 *
 * <p>Assumptions: the accepted-name list is READ from the runner rather than restated here. A restated
 * list agrees with whatever it was copied from, so it cannot detect the one drift that matters -- a
 * fourth name added without a fourth bean, or a fourth bean added that no token reaches.</p>
 *
 * <p>Assumptions: a real context is refreshed rather than the component annotations being read
 * reflectively. Reading an annotation establishes that a name is DECLARED; refreshing establishes that
 * it is REGISTERED, and two beans declaring one name or a component outside the scan both satisfy the
 * first while failing the second.</p>
 *
 * <p>Assumptions: this package holds THREE test classes and the reason they are separate is the
 * subject rather than the size. {@code TaskDispatchWiringTest} asserts what the three dispatched names
 * resolve to and what each task then does; {@code CategoryBalanceArtifactPublisherTest} asserts the one
 * fixed object key the category-balance report lands on, which is observable only from the request the
 * storage client is handed; {@code GenerationKeysTest} asserts the generation-key convention and the
 * number a new write is allocated, which is a key grammar shared with
 * {@code infra/modules/s3-datasets} and with the retention function and is not a property of any one
 * task. Folding either into the wiring test would file a published contract under a wiring test.</p>
 *
 * <p>Assumptions: object keys are asserted from the request the storage client is HANDED, which is the
 * only place a key is observable from outside a task. A run producing no record closes its writer
 * without having started a multipart upload, so it publishes by whole-object put -- which is what lets
 * these cases read a key without driving a multi-mebibyte artifact to reach a completion call.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the project Explainability rule's
 * docstring specification describe callable code, and are omitted from this descriptor deliberately
 * rather than written out empty, because an at-clause carrying no description is itself a violation of
 * the completeness module that audits this build.</p>
 */
package com.carddemo.reporting.task;
