/**
 * The REST surface of the reference-data context, and the boundary the published contract describes.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type here is a controller. A controller binds and validates a request, calls one collaborator
 * and returns a transfer object. It holds no business rule, composes no message and reaches no datastore
 * except through a repository whose whole content is a membership test.</p>
 *
 * <p>Assumptions: all seven controllers are <b>landed</b> as compilation units beside this descriptor,
 * and between them they answer for every one of the nineteen operations
 * {@code src/main/resources/openapi/reference-api.yaml} declares. A contract-validation test holds the
 * two together in both directions, so an operation added to the document without a handler, or a handler
 * added without an operation, fails the build rather than review.</p>
 *
 * <h2>Why the address lookups have no service layer between them and their tables</h2>
 *
 * <p>Alternatives Considered: a lookup service wrapping the three seeded allow-list repositories was
 * evaluated and rejected, and the sibling {@code service} charter records the same decision. Those three
 * tables carry no rule beyond whether a code exists, so a service over them would forward a call and add
 * a file. The two transaction-reference tables are the opposite case -- version comparison, referential
 * refusal, three distinct write behaviours -- and every one of those goes through a service.</p>
 *
 * <h2>Authority</h2>
 *
 * <p>Assumptions: no controller here declares a method-level authority annotation, and their absence is
 * deliberate rather than an omission. {@code com.carddemo.reference.config.SecurityConfig} decides
 * authority by HTTP method for the whole module -- every write requires the administrative group
 * authority and every read requires one of the two group authorities -- and the contract publishes that
 * same model as its authority-model extension. A second declaration at the method would create two
 * places able to disagree about one question, with no rule for which wins.</p>
 *
 * <h2>Cursor material stops here</h2>
 *
 * <p>Assumptions: a controller holds the sealer and hands it to the service, rather than the service
 * holding it. That keeps the key that seals a position out of the layer that reads rows, so a service
 * cannot mint a position and a test of a service needs no key. It is the same arrangement the sibling
 * transaction context uses.</p>
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and in Java a package's entry point is its package declaration.
 * {@code config/checkstyle/checkstyle.xml} enforces that with {@code JavadocPackage} over the file set
 * and {@code MissingJavadocPackage} over the parsed tree, both bound to the {@code validate} phase with
 * violations failing the build. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.api;
