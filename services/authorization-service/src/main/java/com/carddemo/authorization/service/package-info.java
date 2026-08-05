/**
 * The business rules and the messaging path of the pending-authorization context.
 *
 * <p>Three types live here, and together they are the migrated form of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}:</p>
 *
 * <ul>
 *   <li>{@link com.carddemo.authorization.service.AuthorizationDecisionService} -- paragraph
 *       {@code 6000-MAKE-DECISION} at lines 657 to 734 of that program, transcribed with every response
 *       code and reason literal carried across character for character.</li>
 *   <li>{@link com.carddemo.authorization.service.AuthorizationRequestListener} -- the program's
 *       queue-read at line 389, its per-message syncpoint at line 335, and its database writes at lines
 *       786 onward.</li>
 *   <li>{@link com.carddemo.authorization.service.OutboxPublisher} -- the program's reply put at line
 *       753, relocated behind a transactional outbox.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the relocation is the one deliberate behavioural improvement in this package
 * and the reason the outbox exists. The baseline commits its decision and then publishes its reply as two
 * separate units of work, so a failure between them leaves an authorization the data says was decided and
 * a requester that never hears back. The listener writes the reply into the database INSIDE the deciding
 * transaction and the publisher sends it afterwards, so the row and the decision commit together or
 * neither does, and publication can be retried without re-deciding anything. The divergence is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: four properties of the baseline consumer ARE preserved, and each is preserved as
 * configuration rather than as code, so an operator can change it without a rebuild. The unit of work is
 * one message, matching the per-message syncpoint. The receive wait is five seconds, matching the
 * baseline's own get-with-wait. The in-flight batch is bounded, which is the continuous equivalent of the
 * baseline's five-hundred-message processing limit. And the reply destination comes from the REQUEST,
 * exactly as the message descriptor's reply-to queue field did, so one consumer serves however many
 * requesters have their own reply queues.</p>
 *
 * <p>Assumptions: the request queue is a FIFO queue whose message group is the card number and whose
 * deduplication identifier is the acquirer's transaction identifier. Grouping by card gives per-card
 * ordering while leaving different cards to be delivered in parallel, which is what lets the listener take
 * a pessimistic row lock on one account's summary without serialising the whole service. Deduplicating by
 * transaction identifier makes suppression independent of the payload, so a re-sent request whose bytes
 * differ is still recognised as the same request.</p>
 *
 * <p>Assumptions: one genuine semantic gap is resolved here rather than ignored. The baseline sets a
 * five-second expiry on its reply message and the target queue service has no per-message time to live, so
 * the expiry travels as a message attribute instead: the listener DROPS and logs a request whose expiry has
 * already passed, and the publisher retires a reply whose expiry passed before it could be sent. Deciding
 * a stale request would reserve funds against an authorization whose requester has stopped waiting, and
 * sending a stale reply would consume the deduplication identifier so that a legitimate retry would then be
 * suppressed as a duplicate of an answer nobody read. The resolution is recorded in
 * {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <p>Assumptions: nothing in this package logs a value that came off the wire. A failure message from a
 * queue client can embed the request it was building, and that request carries a primary account number, so
 * the publisher records an exception's CLASS NAME rather than its message and the listener logs an
 * unparseable attribute's LENGTH rather than its content. Log storage is the one destination the masking
 * applied at the API edge does not reach, which is why the discipline is stated at package level.</p>
 */
package com.carddemo.authorization.service;
