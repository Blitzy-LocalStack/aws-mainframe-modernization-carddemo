/**
 * Transport-neutral rules for the message METADATA the migrated queue flows carry.
 *
 * <p><b>Purpose.</b> The baseline conducts three decoupled flows over a message queue, and each of them
 * carries values in the message descriptor rather than in the payload: a correlation identity, a reply
 * destination, a format indicator and an expiry. The payload contracts themselves live in
 * {@code com.carddemo.common.codec}, which owns the delimited request and reply records byte for byte.
 * This package owns the rules for the metadata AROUND those payloads, and it exists because those rules
 * are shared by more than one consumer while belonging to none of them.</p>
 *
 * <p>Refactoring Rationale: this package was created because the metadata rules had been borrowed from
 * the SERVLET transport, whose own rule set lives in {@code com.carddemo.common.web}. A correlation
 * identity minted for a response header and a correlation identity echoed from a queue descriptor are
 * different values with different producers and different bounds, and applying one predicate to both
 * meant the queue consumers silently discarded identities their requesters were waiting on. Stating the
 * messaging rule in its own package makes the distinction visible in an import line rather than leaving
 * it to be rediscovered.</p>
 *
 * <p>Assumptions: nothing here touches a queue client, a payload or a database. The types are pure rules
 * over values, so they are unit-testable without a broker, a container or a message, and the layering
 * gate that forbids infrastructure types inside shared-kernel packages holds here by construction.</p>
 *
 * <p>Alternatives Considered: adding the messaging rule as a second predicate on the servlet filter that
 * already owns the correlation contract. Rejected because it would leave a class named for one transport
 * publishing the rule of another, so a reader looking for the queue rule would have to know to look in
 * the web package -- and the next consumer to need it would be as likely to reuse the wrong predicate as
 * the last one was.</p>
 */
package com.carddemo.common.messaging;
