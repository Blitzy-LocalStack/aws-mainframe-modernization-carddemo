/**
 * Tests that hold this context's published contract to the artifacts that realise it.
 *
 * <p>Assumptions: one class executes here, {@code ReferenceWireContractTest}, and it is named for the
 * WIRE rather than for the API because this module already holds {@code ReferenceApiContractTest} in its
 * {@code config} package. Two classes of one simple name inside one module are told apart only by the
 * package they ran in, so a reader given a failure, a report line or a reference to "the contract test"
 * cannot tell which produced it; the shared layering gate records the same reasoning at length for its
 * own name. The two are not duplicates in substance: the {@code config} class holds the error, message
 * and value-domain assertions, and this one holds the version column, the per-operation authority, the
 * date mask, the per-action batch outcome and the cursor examples.</p>
 *
 * <p>Assumptions: this package exists because {@code reference-service} publishes a contract far
 * larger than the code it currently ships. There is no entity, no repository and no controller in
 * this module -- its main tree holds an application class, a security configuration and package
 * charters -- so the document, the Flyway migration and the filter chain are the only authored
 * artifacts, and the only assertions worth making here are the ones that hold those three to each
 * other. A test that mocked a service layer would be asserting against nothing that exists.
 *
 * <p>Refactoring Rationale: the assertions in this package were authored after a review found the
 * document contradicting the migration and the filter chain in five separate places at once -- a
 * required version member no table carried, a read authority the chain does not enforce, an
 * enumeration refusing a value the document promised to answer, an atomic batch the reference
 * program does not perform, and ten paging examples failing the document's own pattern. Every one of
 * those was reachable by reading two authored files side by side, which is exactly what a test can
 * do and a reviewer reliably cannot.
 *
 * <p>Trade-offs: the package is named for transfer objects although it declares none, and the name
 * is kept for one reason: it is where the sibling contexts put the same assertions, so an engineer
 * looking for a contract test finds it in the same place in all nine modules. Naming it for the
 * document instead would be more literal here and less predictable everywhere.
 *
 * <p>A package charter accepts no parameter, yields no value and raises nothing, so this block
 * carries no parameter, return or exception at-clause.
 */
package com.carddemo.reference.dto;
