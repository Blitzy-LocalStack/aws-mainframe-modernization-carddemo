/**
 * The online-write window: the one decision every mutating path consults while the nightly batch
 * chain owns the data, and the machinery that applies it.
 *
 * <h2>What this package owns</h2>
 *
 * <ul>
 *   <li><b>{@code OnlineWriteGate}</b> -- reads the flag that says whether the environment is
 *       accepting mutating work, caches the answer for a few seconds, and REFUSES when it cannot
 *       establish that the window is open.</li>
 *   <li><b>{@code OnlineWritesDisabledException}</b> -- the refusal, rendered as a single status and
 *       a single sentence wherever it is raised.</li>
 *   <li><b>{@code OnlineWriteGateInterceptor}</b> -- applies the gate to every mutating request
 *       without any handler having to call it.</li>
 *   <li><b>{@code OnlineWriteGateExempt}</b> -- the marker by which a read shaped as a write
 *       declares itself, carrying its justification at the handler.</li>
 * </ul>
 *
 * <h2>Lineage: what the reference actually did</h2>
 *
 * <p>The baseline quiesced the online region with an operator command, not with application logic.
 * {@code app/jcl/CLOSEFIL.jcl} runs a single step at line 22 whose input stream, lines 26 to 30,
 * issues five region commands -- one each for {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT},
 * {@code CXACAIX} and {@code USRSEC} -- that CLOSE those files to the region.
 * {@code app/jcl/OPENFIL.jcl} is its exact mirror: the same step shape at line 22 and the same five
 * files reopened at lines 26 to 30. Between the two jobs the posting chain owned the data, and an
 * online transaction that attempted a write received a file-status error and took its abend path.
 *
 * <p>Assumptions: exactly five files, and that number is worth stating because it is smaller than
 * the ten clusters the region defines. The bracket covered the masters the posting chain writes and
 * the security file, and it did not cover, for example, the card master. This package does not
 * reproduce that asymmetry: the flag is one value for a whole environment, and the gate refuses
 * every mutating request rather than only those touching a particular table. Alternatives
 * Considered: gating per table, to mirror the five. Rejected because the mapping from a request to
 * the tables its handler will write is not available before the handler runs -- it is exactly what
 * the handler decides -- so a per-table gate would have to be a call inside each service method,
 * which is the completeness problem this package exists to avoid. Trade-offs: the target therefore
 * refuses slightly more than the reference did during the window. That is accepted because the
 * additional refusals are writes to reference and card data during a posting run, which no operator
 * procedure requires, and because the alternative is a control that a new handler can silently
 * escape.
 *
 * <p>The target's replacement for the two jobs is a pair of state-machine states around the batch
 * chain, which set and clear one Parameter Store flag. Those states, the flag and the task-role grant
 * that lets a service read it all exist in {@code infra/envs/dev} and {@code infra/envs/prod}. This
 * package is the half that makes them mean something: without it the flag was written, injected into
 * every online task definition, and read by nothing.
 *
 * <h2>The three decisions this package is built on</h2>
 *
 * <p>Assumptions: THE GATE FAILS CLOSED. If the flag cannot be read -- the parameter is missing, the
 * task role lacks the permission, the call times out -- the gate refuses the write. Alternatives
 * Considered: admitting writes when the window's state is unknown, on the ground that a dependency
 * failure should not take an application down. Rejected because it inverts the risk: an outage of
 * the parameter service or a mistaken policy change would silently reopen the window in the middle
 * of a posting run, which is the single condition the control exists to prevent. A refused write is
 * recoverable by retrying; a write interleaved with posting is not, because the posting chain has
 * already read the balance it is about to rewrite.
 *
 * <p>Assumptions: the flag is READ, not injected. The task definition carries the parameter's name
 * and never its value. Alternatives Considered: injecting the value as an environment variable,
 * which needs no client and no permission. Rejected because the value would be frozen at task start,
 * so a task that started inside the window would keep refusing writes after it closed, and one that
 * started before a quiesce would keep accepting them straight through it. A gate that cannot observe
 * a change is not a gate. Trade-offs: reading per request costs a call, so a short cache sits in
 * front of it -- long enough that a busy service does not spend a round trip per write, short enough
 * that a quiesce takes effect well inside the state transition that follows it.
 *
 * <p>Refactoring Rationale: the gate is applied by an interceptor rather than by a servlet filter,
 * and the difference is behavioural. An exception thrown in a filter never reaches the shared
 * controller advice -- {@code com.carddemo.common.web.CorrelationIdFilter} records that constraint
 * and has to render its own refusal body because of it -- whereas an interceptor's pre-handle runs
 * inside the dispatcher, so a refusal raised there is rendered by the shared advice with the same
 * problem shape, correlation identifier and timestamp as every other refusal the application
 * produces. Alternatives Considered: a filter that renders the body itself. Rejected because it would
 * be a second place for that shape to be defined, and the two would drift.
 *
 * <h2>Why the exemptions are an annotation and not a list</h2>
 *
 * <p>Several operations in this migration are {@code POST} despite being reads: the internal account,
 * customer and cross-reference lookups carry their identifier in a request body specifically so that
 * it is not composed into the load balancer's access record. Those must stay available during the
 * window, because a quiesce closes writes and leaves reads working. Alternatives Considered: holding
 * the exempt addresses as a set inside the gate. Rejected on drift -- the path and the exemption
 * would live in different files, so renaming a route would move it silently from exempt to gated, and
 * the symptom would be an internal read refused during the one window nobody is watching.
 * {@code OnlineWriteGateExempt} cannot drift from the handler it is attached to, and its
 * justification is required rather than optional, so an exemption cannot be added without an argument
 * a reviewer can read beside the operation.
 *
 * <h2>Why the queue consumers are deliberately NOT gated</h2>
 *
 * <p>Assumptions: the gate is applied to HTTP requests only. No message listener in this migration
 * calls it, and that is a parity decision with two independent reasons rather than an omission.
 *
 * <p>First, none of the four consumers writes data the reference bracket protected. The account
 * inquiry listener is declared read-only, and both date listeners compute a reply and persist
 * nothing. The authorization consumer does write, but it writes the {@code authorization} schema --
 * the target of the IMS segments and the Db2 fraud table -- and the posting chain writes none of it.
 * The five files {@code app/jcl/CLOSEFIL.jcl} closed at lines 26 to 30 are the transaction master,
 * the cross-reference, the account master, the cross-reference alternate index and the security file;
 * the authorization store is not among them, and the reference program that consumes those messages
 * is not a CICS file user at all.
 *
 * <p>Second, refusing a queued message is not a refusal, it is a deferral with a deadline. An
 * unconsumed message becomes visible again after its timeout and is redelivered; at the fifth receive
 * it lands in the dead-letter queue. A window of any length would therefore convert legitimate
 * authorization traffic into a backlog an operator has to redrive by hand, and it would do so for a
 * store the window was never protecting. Alternatives Considered: stopping the listener containers
 * for the duration instead, which avoids the dead-letter consequence. Rejected because it would ADD a
 * quiesce the reference does not have -- the reference's consumer ran throughout -- and because
 * stopping and restarting a container is a different operation from setting a flag, with its own
 * failure mode if the resume does not happen.
 *
 * <h2>Which workloads hold this gate, and which deliberately do not</h2>
 *
 * <p>Assumptions: the gate is active only where the parameter-name property is set, and the
 * infrastructure sets it for the seven request-serving workloads and for no others. The two
 * exclusions are not symmetric and should not be read as one rule. The batch workload is excluded
 * because it is the workload the quiesce PROTECTS: gating it against its own window would stop the
 * chain the window exists to give exclusive access to. The extract-transform-load package is
 * excluded because it runs as a staging step before the chain rather than as a service, and it loads
 * whole datasets rather than applying requests.
 *
 * <h2>This package's share of the shared kernel</h2>
 *
 * <pre>
 * this package: control 4 production + 1 charter = 5 compilation units
 * </pre>
 *
 * <p>The kernel-wide figures those five belong to, re-derived by
 * {@code com.carddemo.common.architecture.SharedKernelInventoryTest} from the directory on every
 * build:
 *
 * <pre>
 * root 1 + money 2 + codec 7 + error 7 + web 6 + security 9 + observability 4 + time 1 + validation 2 + messaging 5 + control 4 = 48
 * root 2 + money 3 + codec 8 + error 8 + web 7 + security 10 + observability 5 + time 2 + validation 3 + messaging 6 + control 5 = 59
 * </pre>
 *
 * <p>Assumptions: this subpackage is not named by the migration plan's section 0.4.1.2, and the root
 * charter records it as a deliberate addition with one argument per class rather than leaving the
 * excess to be reconciled later. It is a subpackage of its own rather than four classes inside
 * {@code web} because what it owns is a DECISION about the environment -- read a flag, fail closed,
 * cache briefly -- and only the application of that decision is a request concern. Filing the whole of
 * it under {@code web} would name the enforcement point as the subject and the decision as an
 * implementation detail of it, which is the wrong way round: the interceptor is replaceable and the
 * fail-closed semantics are not.
 *
 * <p>Assumptions: the four rationale labels used above -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are written in the
 * plural, colon-terminated, without parentheses and with the ASCII hyphen-minus, which is the only
 * accepted spelling across every language and file of the migration trees.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full statement of the convention.
 */
package com.carddemo.common.control;
