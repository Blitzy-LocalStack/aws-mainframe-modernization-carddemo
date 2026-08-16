/**
 * @file The masked-rendering contracts this tree enforces, declared once and imported everywhere.
 *
 * Purpose
 * -------
 * Two security-critical constants live here: the shape a masked primary account number must have
 * before any client will render it, and the placeholder a request target's value segments are reduced
 * to before a failure is reported. Both were previously declared per module — the card-number pattern
 * in five separate API clients and the segment placeholder in the transport — so one contract had six
 * owners between them.
 *
 * Refactoring Rationale: a review found the duplication and named the consequence rather than the
 * tidiness: security-critical constants with multiple owners CAN DRIFT, and a drifted mask pattern
 * fails in the permissive direction. If one client's copy were relaxed — eleven asterisks instead of
 * twelve, or a `test` allowed to match a substring — that client alone would render an unmasked or
 * partially masked number while its four siblings kept refusing it, and the difference would show up
 * as one screen leaking a value the others withheld. A single declaration makes that impossible
 * rather than unlikely.
 *
 * Alternatives Considered: leaving the five copies in place and adding a test that compared them for
 * equality. Rejected because it detects drift instead of preventing it, and it would pass for six
 * copies that were all wrong in the same way. It also leaves the reasoning distributed, so the next
 * author still has to decide which of six comments is authoritative.
 *
 * Alternatives Considered: putting these in `ui/src/api/client.ts` beside the transport, which already
 * owned one of them. Rejected because that module imports the runtime configuration, the server clock
 * and the shared types, so importing it back from the five clients that need only a regular expression
 * would pull the whole transport into modules that do not dispatch anything. This module imports
 * NOTHING, which is what lets every consumer take it without acquiring a dependency it did not ask for.
 *
 * Assumptions: nothing here is a formatting helper and no function in this module produces a mask. The
 * masking itself is performed by the SERVICES — each context's `mapper` package reduces a primary
 * account number to its last four digits before it is serialised — and these constants are how the
 * browser refuses a response that did not. A helper that masked a value client-side would invite a
 * caller to hold the unmasked one first, which is the exposure the service-side mask exists to remove.
 */

/**
 * The exact shape a masked primary account number must have: twelve asterisks then four digits.
 *
 * Assumptions: the pattern is ANCHORED at both ends, and both anchors carry weight. Without the
 * leading anchor a value that merely CONTAINED a masked number would satisfy it, and without the
 * trailing anchor a sixteen-digit number with a masked prefix would. Either form would let an
 * unmasked number reach a table, a log line or a bug report while a test that looked correct
 * reported a pass.
 *
 * Assumptions: it is expressed POSITIVELY — this is what a masked value looks like — rather than as a
 * refusal of sixteen consecutive digits. The negative form answers "no" to a nineteen-character value
 * of which sixteen are digits, and to every partial mask, so it admits the family of near-misses this
 * refuses. The contract's own `pattern` facet is the positive one, and this is that facet transcribed.
 *
 * Trade-offs: a service that rendered the mask differently — a different mask character, or eleven of
 * them — now fails the browse rather than painting a row. That is the intended exchange: the
 * contract's pattern is the agreement, and a rendering that does not satisfy it is either a service
 * fault or a disclosure, neither of which should be resolved by rendering it.
 *
 * Assumptions: the twelve-and-four split is the migrated form of the baseline's own field. The card
 * number is `PIC X(16)` at `app/cpy/CVACT02Y.cpy`, and the migration masks all but the last four
 * digits on every path except the administrative card-detail read — which returns the full number in
 * a response BODY and is addressed by an opaque selector, so no unmasked number ever reaches a
 * request target. That single exception is the reason this pattern is applied to the DISPLAY member
 * rather than to every card-number-shaped property.
 */
export const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/**
 * The placeholder a request target's value segments are replaced by before a failure names the target.
 *
 * Assumptions: a reported path is a TEMPLATE and never a dispatched target. A failure record carries
 * the path so an operator can tell which operation failed, and a path carrying its values would put an
 * account identifier, a customer identifier or a card selector into every log line a refusal produces
 * — which is the same disclosure the services avoid by taking those values in bodies rather than in
 * targets. Reducing each value segment to this constant keeps the operation identifiable and the
 * values out.
 *
 * Assumptions: the value is the brace form the published contracts use for their own path parameters,
 * so a reported template reads as the contract's own path rather than as a redacted string a reader
 * has to decode. The alternative of a row of asterisks was rejected for exactly that reason: it would
 * be indistinguishable from a masked VALUE, and a reader would not be able to tell a template from a
 * masked account number at a glance.
 */
export const MASKED_PATH_SEGMENT = '{id}';
