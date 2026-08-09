/**
 * Tests holding the maintenance-job entry points to being invocable.
 *
 * <h2>What is asserted here, and why it is a separate concern</h2>
 *
 * <p>The load and the purge each have their own behavioural tests, and both were green while neither job
 * could be invoked by anything in production. That is the gap this package closes: a behavioural test calls
 * the service directly, so it cannot observe whether a caller exists. What is asserted here is the wiring
 * -- that every job name the runner publishes resolves to a bean, that every bean implementing the task
 * contract is published under a name, that only a job option diverts the process into a task, and that
 * every job's parameters are refused before a context starts when they are absent or malformed.</p>
 *
 * <p>Assumptions: the bean-name correspondence is asserted in BOTH directions, because each direction is a
 * different defect. A published name with no bean is a job an operator can select that then fails at
 * invocation. A bean with no published name is a job that compiles, is tested, and can never be run -- and
 * that is exactly the state both of these jobs were in.</p>
 *
 * <p>Assumptions: the correspondence is read from the task SOURCES rather than from a started application
 * context. A context needs a database, a queue and an identity provider, and a wiring test that needs three
 * services to prove that a name matches is a test that gets disabled the first time one is unavailable.</p>
 */
package com.carddemo.authorization.task;
