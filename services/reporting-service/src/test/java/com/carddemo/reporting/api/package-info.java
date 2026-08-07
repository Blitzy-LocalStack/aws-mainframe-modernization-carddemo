/**
 * Tests that hold this context's published contract to its own request handlers, to the document its
 * configuration serves, and to the two deployed artifacts that route to it.
 *
 * <p>Assumptions: this package tests an AGREEMENT BETWEEN ARTIFACTS rather than the behaviour of a
 * handler, so its assertions compare two descriptions of one surface instead of exercising a request.
 * A path template, an operation identifier, a required authority and a response header all cross the
 * wire as strings, so a contract declaring one route and a controller serving another both compile,
 * both pass their own unit tests, and disagree only at run time in front of a client. The surface is
 * described in five places that no compiler and no linter compares -- the committed OpenAPI document,
 * the mapping annotations of {@code ReportController} and {@code StatementController}, the
 * information block the {@code OpenApiConfig} bean serves, the load balancer's forwarding patterns in
 * {@code infra/envs/&#123;dev,prod&#125;/main.tf}, and the public HTTP API's route keys in
 * {@code infra/modules/api-gateway-http/variables.tf} -- and three of those are text files in two
 * other languages. The classes here are that comparison.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found three files in this module
 * asserting in prose that the contract document, the request handlers and the browser client written
 * against them all existed, when none of the three did. One of those assertions contradicted itself
 * inside a single Javadoc block, naming the file as the contract of record two sentences before
 * recording it as absent from the tree. Prose cannot fail a build, so the contradiction survived
 * every green run; moving each checkable claim into an assertion is what removes that possibility
 * rather than merely correcting today's wording.</p>
 *
 * <p>Assumptions: the contract is read from the CLASSPATH rather than from a source path, so the
 * assertions run against the artifact the service actually publishes. Reading the file under
 * {@code src/main/resources} through the file system would pass while the packaged resource was
 * stale or absent, which is the one failure a contract test most needs to catch.</p>
 *
 * <p>Assumptions: the controllers are inspected by REFLECTION over their mapping annotations rather
 * than by standing up a web context. What is being asserted is the declared route table, which the
 * annotations hold in full, and reflection reaches it without a server, a data source, a token issuer
 * or a container. The runtime counterpart, a mock request per route, belongs to the sibling
 * controller tests in this package, which own the web slice.</p>
 *
 * <p>Trade-offs: what these tests cannot decide is FIELD-LEVEL agreement between a handler and the
 * document -- whether a property a controller serializes carries the name, type and width the
 * contract declares for it. Nothing here compares the two, because one side is Java and the other
 * YAML and nothing in this build reads both as schemas; the document is the specification a handler
 * is written against, and this package guarantees that the specification exists, parses, is
 * internally consistent, is reachable through both routing hops, names the same operations the
 * controllers annotate, and says what this module's own configuration says. Stating that boundary
 * here is what keeps a reader from mistaking a green run for full contract conformance.</p>
 *
 * <p>Assumptions: of the four docstring elements user-specified Rule 1 enumerates, only Purpose
 * applies to a package declaration. It accepts no parameter, yields no value and raises nothing, so
 * the Parameters, Return values and Exceptions elements are inapplicable rather than omitted, and no
 * at-clause is invented to stand in for one of them. Two Checkstyle checks act on this file and they
 * are not redundant: {@code JavadocPackage} requires the file to be present in a package holding
 * Java sources, and {@code MissingJavadocPackage} requires it to carry Javadoc; both run against
 * test sources because the plugin execution in {@code services/pom.xml} includes the test source
 * directory. The written convention this discharges is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 * </p>

 */
package com.carddemo.reporting.api;
