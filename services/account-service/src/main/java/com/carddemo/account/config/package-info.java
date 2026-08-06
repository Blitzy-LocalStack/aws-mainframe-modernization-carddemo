/**
 * The configuration layer of the account, customer and card-cross-reference context.
 *
 * <h2>What this package owns</h2>
 *
 * <p>Two concerns: {@code SecurityConfig}, which decides which tokens are accepted and which authority each route requires, and the datasource wiring that binds this context to the {@code account} schema. Both are configuration in the strict sense -- they establish how the context is wired and hold no business rule of their own.</p>
 *
 * <p>Assumptions: no route of this context is administrator-only, and {@code SecurityConfig} records why: the baseline reaches account view and account update from the MAIN menu, which both user types reach, so restricting either to an administrator would remove a capability an ordinary user has today. Transformation rule T9 permits no behavioural change without a documented divergence, and tightening a control is still a change.</p>
 *
 * <h2>What this package deliberately does not own</h2>
 *
 * <p>Assumptions: there is no exception-handler class, no correlation-filter registration, no meter
 * customiser and no object-mapper module here. All four are shared kernel components and all four reach
 * this context through {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework
 * loads from the shared module's registration resource rather than from a scan. Declaring any of them
 * again here would give one concern two owners and make which of them answers a given request depend on
 * bean ordering rather than on anything written down.</p>
 *
 * <p>Alternatives Considered: importing those shared components explicitly from the entry point, which
 * an earlier design of this repository did. Rejected because a registration a service has to remember is
 * a registration a service can omit, and the symptom of omitting it is silent -- log lines with no
 * correlation identity, meters with no service dimension, amounts on the wire as bare JSON numbers, or a
 * framework-shaped error body escaping from one service while the others answer in the migrated shape.
 * The shared module's own auto-configuration charter records the same decision from the other side.</p>
 *
 * <p>Assumptions: this descriptor exists because the documentation gate requires one in any directory
 * holding an audited compilation unit, and requires it to carry Javadoc rather than merely to exist. A
 * package declaration is also the module entry point the project Explainability rule names; that rule's
 * parameter, return and exception elements describe callable code and have no counterpart on a package
 * declaration, so they are omitted deliberately rather than written out empty.</p>
 */
package com.carddemo.account.config;
