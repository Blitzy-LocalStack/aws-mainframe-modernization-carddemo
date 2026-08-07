/**
 * Tests for the business rules and the identity-provider calls of the authentication bounded context.
 *
 * <p>Assumptions: this charter exists because Checkstyle's {@code JavadocPackage} rule audits a DIRECTORY
 * rather than a compilation unit, and this module's documentation gate includes test sources. A package that
 * holds a test class and no charter fails the build, so the charter has to govern the directory as a whole.</p>
 *
 * <p>What belongs here: assertions about the types in the corresponding main-source package, and nothing
 * else. A test that needed a type from a different layer to make its point belongs beside that layer, because
 * a test placed for convenience rather than for subject is a test a later reader cannot find from the code it
 * covers.</p>
 *
 * <p>Trade-offs: the tests here substitute the identity provider rather than contacting one. That is not a
 * convenience -- the calls under test are administrative writes against a user pool, so an integration
 * variant would either need a live pool or an emulator that reproduces the exact attribute schema, and
 * neither is available to a unit build. What a substituted provider buys is the assertion that matters most
 * for this subject: that an out-of-domain user type reaches NO provider call at all, which can only be shown
 * by observing that the collaborator was never touched.</p>
 *
 * <p>Alternatives Considered: asserting provisioning through the create-user endpoint with a mocked provider
 * behind it, so one test covered the controller and the provider call together. Rejected because the two
 * failure modes then become indistinguishable: a request refused by bean validation and a user type refused
 * by the group lookup both surface as one status, and the property under test here is precisely that the
 * second refusal happens before any account exists.</p>
 */
package com.carddemo.auth.service;
