/**
 * Rules for the message METADATA the migrated queue flows carry, and for what a failed delivery records.
 *
 * <p><b>Purpose.</b> The baseline conducts three decoupled flows over a message queue, and each of them
 * carries values in the message descriptor rather than in the payload: a correlation identity, a reply
 * destination, a format indicator and an expiry. The payload contracts themselves live in
 * {@code com.carddemo.common.codec}, which owns the delimited request and reply records byte for byte.
 * This package owns the rules for the metadata AROUND those payloads, and it exists because those rules
 * are shared by more than one consumer while belonging to none of them.</p>
 *
 * <p>Refactoring Rationale: the heading read "transport-neutral rules for the message metadata", and it is
 * reworded because one of the four types here is neither metadata nor transport-neutral. The failure
 * handler is in this package for the same reason the metadata rules are -- three consuming contexts must
 * agree on it and none of them owns it -- but a heading that described only the other three would leave a
 * reader who found it here concluding it was misfiled.</p>
 *
 * <p>Refactoring Rationale: this package was created because the metadata rules had been borrowed from
 * the SERVLET transport, whose own rule set lives in {@code com.carddemo.common.web}. A correlation
 * identity minted for a response header and a correlation identity echoed from a queue descriptor are
 * different values with different producers and different bounds, and applying one predicate to both
 * meant the queue consumers silently discarded identities their requesters were waiting on. Stating the
 * messaging rule in its own package makes the distinction visible in an import line rather than leaving
 * it to be rediscovered.</p>
 *
 * <p>Assumptions: nothing here touches a queue client or a database, and THREE of the four types touch no
 * message either. {@code MessageExpiry}, {@code MessagingCorrelationId} and {@code QueueClientBudget} are
 * pure rules over values, so they are unit-testable without a broker, a container or a message. That
 * applies to {@code QueueClientBudget} as much as to the other two: it states the relationship between a
 * client's time bounds and a queue's visibility period as three durations, and leaves APPLYING those
 * values to whichever client library each service configures.</p>
 *
 * <p>Refactoring Rationale: the fourth type, {@code RethrowingDigestErrorHandler}, is the stated exception
 * and this paragraph names it rather than letting the sentence above quietly stop being true. It takes a
 * {@code org.springframework.messaging.Message} and implements the queue starter's own error-handler
 * interface, because it is the ONE rule in this package that has to be handed to the framework rather than
 * called by this repository's own code: it decides what a failed delivery puts in a log record and what it
 * rethrows, and the framework is what invokes it. It still holds no queue client, reads no payload and
 * touches no database, so the layering gate that forbids infrastructure types inside shared-kernel domain
 * packages is unaffected -- this module has no {@code domain} package at all.</p>
 *
 * <p>Alternatives Considered: giving that fourth type a nested {@code messaging.listener} subpackage, so
 * this charter could keep the shorter claim. Rejected because the shared kernel's root charter declares a
 * closed inventory of a root and ten FLAT subpackages, and {@code SharedKernelInventoryTest} re-derives
 * that table one level deep and treats a nested package as drift to expose rather than absorb. Naming one
 * exception here costs a paragraph; a twelfth package would have cost the flat closed set the module is
 * designed around, and would have meant widening a gate against its own documented intent.</p>
 *
 * <p>Alternatives Considered: adding the messaging rule as a second predicate on the servlet filter that
 * already owns the correlation contract. Rejected because it would leave a class named for one transport
 * publishing the rule of another, so a reader looking for the queue rule would have to know to look in
 * the web package -- and the next consumer to need it would be as likely to reuse the wrong predicate as
 * the last one was.</p>
 */
package com.carddemo.common.messaging;
