/**
 * Tests that hold this context's published contract to the artifacts that realise it.
 *
 * <p>Assumptions: two classes execute here. {@code ReferenceWireContractTest} is named for the
 * WIRE rather than for the API because this module already holds {@code ReferenceApiContractTest} in its
 * {@code config} package, and {@code DomainRefusalDisclosureTest} holds the two {@code @JsonCreator}
 * readers of this package to a refusal that names its domain without reproducing what a caller
 * supplied. Two classes of one simple name inside one module are told apart only by the
 * package they ran in, so a reader given a failure, a report line or a reference to "the contract test"
 * cannot tell which produced it; the shared layering gate records the same reasoning at length for its
 * own name. The two are not duplicates in substance: the {@code config} class holds the error, message
 * and value-domain assertions, and this one holds the version column, the per-operation authority, the
 * date mask, the per-action batch outcome and the cursor examples.</p>
 *
 * <p>Assumptions: this package asserts AGREEMENT between authored artifacts rather than behaviour,
 * and it does so because nothing else compares them. Refactoring Rationale: this charter previously
 * said the module held "no entity, no repository and no controller", with only an application class,
 * a security configuration and package charters. That was true when written and is not true now --
 * the main tree holds six entities, nine repository interfaces, nine annotated controllers, six
 * services, six mappers and twenty-one transfer objects -- so the stated reason for the package was
 * describing a module that no longer exists. The reason it survives the correction unchanged is that
 * behaviour is now covered where behaviour lives: {@code service} holds the write-behaviour and
 * listener assertions, {@code mapper} and {@code domain} hold theirs. What is left over is the
 * agreement between the published document, the Flyway migration and the filter chain, which spans
 * three artifacts no single one of those packages owns.
 *
 * <p>Refactoring Rationale: the assertions in this package were authored after a review found the
 * document contradicting the migration and the filter chain in five separate places at once -- a
 * required version member no table carried, a read authority that did not match the chain, an
 * enumeration refusing a value the document promised to answer, an atomic batch the reference
 * program does not perform, and ten paging examples failing the document's own pattern. Every one of
 * those was reachable by reading two authored files side by side, which is exactly what a test can
 * do and a reviewer reliably cannot.
 *
 * <p>Refactoring Rationale: the authority item in that list has since been resolved in the opposite
 * direction from the one first taken. The reads were changed to declare only {@code authenticated},
 * on the reasoning that {@code carddemo-user} could not be enforced without refusing an
 * administrator-only token. The chain was later hardened -- its catch-all became {@code denyAll()}
 * and its read rule became {@code SecurityConfig.businessAccess()}, which admits EITHER group -- and
 * that removed the premise, leaving the document publishing a requirement WEAKER than the chain
 * applies and one no rule enforced. The reads now declare {@code carddemo-user}, the value the four
 * sibling contracts already use for the same rule, and this package asserts it against
 * {@code SecurityConfig.BUSINESS_AUTHORITIES} and {@code JwtRoleConverter} rather than against a
 * literal, so the two cannot drift apart again while the assertion keeps passing.
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
