/**
 * The root package of the reference-data context, holding its single Spring Boot entry point.
 *
 * <h2>What lives here, and what deliberately does not</h2>
 *
 * <p>This package holds two compilation units and no more -- {@code ReferenceApplication}, and this
 * descriptor. Every other type of this context lives in a subpackage named for its layer:
 * {@code api}, {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper} and {@code config}. The root is kept bare so that the component scan the
 * entry point implies has exactly one thing to find at this level, and so that a reader looking for
 * behaviour is never uncertain whether the root is a layer as well as a root.</p>
 *
 * <p>Assumptions: the component scan is rooted HERE, at com.carddemo.reference, and is never widened. Every
 * shared kernel type lives under {@code com.carddemo.common}, which sits outside that root, so shared
 * components are not discovered by the scan at all -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the
 * shared module's own registration resource. This is the most easily misread thing about the layout: a
 * reader who assumes the scan reaches the shared kernel will look for a missing bean in the wrong tree.</p>
 *
 * <p>Alternatives Considered: widening the scan to {@code com.carddemo} so that shared components were
 * found automatically. Rejected outright -- it would also reach every OTHER bounded context's types,
 * turning eight independently deployable services into one that happens to be started eight ways, and
 * it is exactly the coupling the shared architecture test forbids.</p>
 *
 * <p>Assumptions: this descriptor exists because the documentation gate requires one in any directory
 * holding an audited compilation unit, and requires it to carry Javadoc rather than merely to exist --
 * {@code JavadocPackage} asserts the file and {@code MissingJavadocPackage} asserts its content, so an
 * empty descriptor satisfies the first and fails the second. A package declaration is also the module
 * entry point the project Explainability rule names. That rule's parameter, return and exception elements
 * describe callable code and have no counterpart on a package declaration, so they are omitted
 * deliberately rather than written out empty.</p>
 */
package com.carddemo.reference;
