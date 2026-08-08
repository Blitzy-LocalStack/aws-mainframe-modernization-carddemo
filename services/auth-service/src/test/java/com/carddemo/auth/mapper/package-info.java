/**
 * Tests for the AUTH bounded context's anti-corruption layer, covering what the mapper is permitted to
 * change on a loaded row and what it must refuse.
 *
 * <h2>Purpose</h2>
 *
 * <p>The class in this package covers one property that is invisible from a return value and expensive to
 * get wrong: {@code com.carddemo.auth.mapper.UserMapper} may assign a row's two descriptive values and may
 * not assign its reference type. The type names an authority that the signed {@code cognito:groups} claim
 * actually confers, so a mapper that assigned it would commit a row describing an authority nobody granted
 * -- and, on the demotion direction, a row reporting that an administrator's access had been withdrawn when
 * it had not. Moving it belongs to {@code com.carddemo.auth.service.UserAuthorityService}, which moves the
 * provider membership first.</p>
 *
 * <p>Assumptions: the assertions are written against a real {@code com.carddemo.auth.domain.User} rather
 * than a substitute for it, because the defect these tests exclude is a column that CHANGED. A verified
 * call on a substituted entity would pass whether or not any value moved, which is precisely the failure
 * mode being guarded.</p>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point, and in
 * Java a package's entry point is its package declaration, which only {@code package-info.java} can carry.
 * Two Checkstyle modules enforce that independently: {@code JavadocPackage} requires this file to exist in
 * any directory holding an audited source file, and {@code MissingJavadocPackage} requires it to carry
 * Javadoc, so a bare package statement satisfies the first and fails the second. No parameter, return or
 * exception at-clause appears, because a package declaration accepts no argument, yields no value and
 * raises nothing. The written convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.auth.mapper;
