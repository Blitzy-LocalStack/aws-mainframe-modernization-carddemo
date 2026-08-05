package com.carddemo.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts the group claim of a validated JSON Web Token into Spring Security authorities, and so
 * replaces the one-character user type the CardDemo baseline carried between screen turns.
 *
 * <p><b>Purpose.</b> A resource server validates a bearer token before any application code runs,
 * so by the time this class is reached authentication has already happened and only authorization
 * is left to settle. This class settles it from one input and one only: the token's
 * {@code cognito:groups} claim. It reads no request body, no header, no path segment and no value
 * the caller supplied for the purpose; it performs no input or output of any kind; and it keeps
 * nothing between calls. The eight service modules gate their administrative routes on the
 * authorities it returns, and none of them repeats this reading.</p>
 *
 * <p>The mapping is two group names wide, and it is closed at two:</p>
 *
 * <pre>
 * baseline user type   provider group    constant declared here
 * 'A'                  carddemo-admin    ADMIN_AUTHORITY
 * 'U'                  carddemo-user     USER_AUTHORITY
 * </pre>
 *
 * <p>Assumptions: the infrastructure module creates those exact two group names as fixed values,
 * independent of its configurable resource-name prefix. That distinction is load-bearing:
 * {@code name_prefix} may rename pools and secrets for another deployment, but it cannot rename the
 * values carried in {@code cognito:groups}. Keeping the claim vocabulary fixed at its producer
 * prevents a valid Terraform naming override from turning every recognized user into a subject with
 * no authority here.</p>
 *
 * <p>Assumptions: two independent reference artefacts attest to that two-value domain, and their
 * agreement is what makes it safe to close. The in-flight form is line 26 of
 * {@code app/cpy/COCOM01Y.cpy} -- a one-character user type inside the 160-byte communication area
 * declared at line 19 of that copybook and shared by all eighteen online programs -- whose two
 * condition names are given at lines 27 and 28 as {@code 'A'} and {@code 'U'}. The stored form is
 * line 22 of {@code app/cpy/CSUSR01Y.cpy}, the same one-character field on the 80-byte security
 * record declared at line 17 there. The two are joined at line 227 of
 * {@code app/cbl/COSGN00C.cbl}, which moves the stored value into the communication area, and that
 * line is the only place in the whole baseline where the value is set at all. A domain witnessed
 * twice and populated from exactly one place cannot acquire a third value without a change to the
 * baseline, and the baseline does not change.</p>
 *
 * <p>Refactoring Rationale: what this replaces is not a role lookup but a byte the caller handed
 * back. The baseline is pseudo-conversational, so its task ends at every screen turn: lines 65 to
 * 67 of {@code app/cbl/COSGN00C.cbl} declare the inbound communication area as a character table
 * whose extent depends on a monitor-supplied length, line 80 detects a first entry by that length
 * being zero, and lines 98 to 102 end the turn by returning to the monitor with the area echoed
 * back, naming it at line 100. The user type is a byte inside that echoed area, which means the
 * party whose privilege is being decided is in possession of the value that decides it. Moving the
 * decision onto a claim of a signed token is therefore not a change of transport: a claim the
 * caller altered would fail signature validation before this class ever saw it, so the caller can
 * no longer assert its own privilege at all. That is what was wrong with the old approach, and
 * removing it is why the conversion lives in the shared kernel rather than being re-derived inside
 * each service.</p>
 *
 * <p>Assumptions: the branch these authorities feed is the sign-on program's own. Line 230 of
 * {@code app/cbl/COSGN00C.cbl} tests the administrator condition; lines 231 to 234 transfer control
 * to the administrative menu, naming that program at line 232; and lines 236 to 239 transfer to
 * the ordinary menu, naming that program at line 237. In the migrated system that transfer becomes
 * a route change made on the browser side, and the branch is taken on the authority this class
 * returns instead of on a value the caller sent.</p>
 *
 * <p>Refactoring Rationale: one contract of the baseline security record is declined rather than
 * carried across, and the omission is deliberate. Line 21 of {@code app/cpy/CSUSR01Y.cpy} declares
 * an eight-character plaintext password field, and line 223 of {@code app/cbl/COSGN00C.cbl}
 * compares it directly against what was typed, inside the flow that reads that record at lines 211
 * to 219. The field is not carried forward at all -- not to a column, not to a transfer object, not
 * to a codec field, not to a constant, not to a parameter and not to an example. The baseline
 * stores and compares a plaintext credential; the migrated system delegates the comparison to a
 * managed identity provider and retains only a subject reference; and the divergence is recorded in
 * the migration's traceability matrix. Transformation rule T9 both licenses and bounds that:
 * structure may change while behaviour may not, and no behavioural change ships unless it is an
 * explicitly documented divergence with a stated reason. The stated reason is that carrying the
 * comparison across would carry the exposure with it, and a migration whose purpose is to leave the
 * mainframe runtime behind has no reason to reproduce the one store it replaces outright. The
 * baseline itself is untouched and stays byte-identical, because it is the behavioural oracle this
 * migration is verified against, and an oracle edited to agree with the code it validates proves
 * nothing.</p>
 *
 * <p>Trade-offs: the consequence for this class is a boundary rather than a preference. Nothing
 * here holds a credential, compares one, or names one, and nothing here may take on that
 * responsibility later. The cost is that a reader looking for the sign-on comparison will not find
 * it in this code base at all and has to know it moved outside it. The gain is that the conversion
 * is pure -- a claim in, authorities out -- with no credential anywhere on its path and therefore
 * nothing for it to disclose.</p>
 *
 * <p>Assumptions: the two user-type values are character literals, and the copybook that declares
 * them writes its literals two different ways in adjacent declarations. At lines 27 and 28 the user
 * type's condition names take quoted values, {@code 'A'} and {@code 'U'}. At lines 30 and 31 the
 * condition names of the very next field -- the one-digit re-entry discriminator declared at line
 * 29 -- take bare unquoted values, {@code 0} and {@code 1}. Both pairs sit inside the same copybook
 * and the same enclosing group. A reader transcribing by pattern rather than by eye carries the
 * wrong form across, and it goes wrong quietly in either direction, because a quoted digit is not
 * the number it resembles and an unquoted letter is a name rather than a value. This class reads
 * the values at lines 27 and 28 as the characters they are. The digits at lines 30 and 31 are not
 * its concern at all: a stateless handler has no first-entry-versus-re-entry distinction to draw,
 * so that discriminator has no target in the migrated system, which is also why the authorities
 * below are derived afresh from every token instead of being remembered between requests.</p>
 *
 * <p>Assumptions: no client library for the managed identity provider sits on this module's
 * classpath, and none may be added to it. This class is handed a token that was decoded and
 * signature-checked upstream, so it opens no connection, resolves no signing key and consults no
 * directory. That is what lets it be tested from a token assembled in memory, and it is also why an
 * unrecognised group name cannot be checked against anything here -- there is nothing to ask.</p>
 *
 * <p>Alternatives Considered: the authority is the provider's group name verbatim, with no prefix
 * added. The framework's role convention is the reasonable alternative: prefixing each authority
 * would let a route be written with the role predicate, which prepends the same prefix on the
 * caller's behalf. It is rejected because the consequence lands on the caller either way and the
 * verbatim form is the one that makes it visible. With the group name used as it stands, a route is
 * authorised with {@code hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)}, and the role predicate
 * must not be used for it -- that predicate would look for a prefixed authority this class never
 * produces, match nothing, and refuse every administrative request with a forbidden response rather
 * than failing at start-up where the mistake would be noticed. Under the prefixing alternative the
 * same trap runs the other way round, and it additionally costs one more translation between the
 * name an operator reads in the provider's group list and the name a developer reads in a route
 * table. One string, spelled one way, in both places.</p>
 *
 * <p>Alternatives Considered: the framework already ships a claim-configurable authorities
 * converter, and reusing it was evaluated. It reads a scope claim by default, applies a scope
 * prefix by default, and splits a textual claim on a delimiter, so adopting it would mean setting
 * its claim name, clearing its prefix and accepting its delimiter semantics at every point it is
 * wired -- eight service modules, each free to get one of those three settings wrong -- and it
 * would still admit every group name it found, which is the behaviour rejected under the
 * unrecognised-group heading below. A named class carries the mapping once, and is then the single
 * thing a reader opens to learn what the authority vocabulary is.</p>
 *
 * <p>Trade-offs: a group name outside the recognised pair is ignored rather than passed through as
 * an authority. Checking membership first is the executable form of the closure argued above: the
 * vocabulary is then defined by this class and discoverable by reading it, rather than by whatever
 * group list a provider happens to hold at the time. The accepted cost is that introducing a third
 * role becomes a change to this class and a release of the modules that consume it, instead of a
 * group added through an administrative console. That cost is accepted because the two-value domain
 * is closed by the baseline itself, so a third group name turning up is far likelier to be a rename
 * or a typing slip than a requirement -- and under the pass-through alternative a renamed group
 * would yield an authority that no route mentions, which fails in exactly the same way a dropped
 * group does while giving up the guarantee that every authority returned is one this code base
 * declares.</p>
 *
 * <p>Assumptions: an absent group claim and an empty one are both ordinary runtime states rather
 * than errors, and both yield an empty authority collection. A token is legitimately issued to a
 * subject that belongs to no group, and a caller holding no authorities is simply a caller that no
 * protected route admits; the right outcome is a forbidden response decided by the route, not an
 * exception raised here that a filter chain would then have to translate into one. The collection
 * returned is never {@code null} either: the framework's authentication converter passes whatever
 * this returns straight into an authentication token, so a null would surface as a failure several
 * frames away from the claim that caused it.</p>
 *
 * <p>Trade-offs: the returned collection is an unmodifiable view over a set this class builds and
 * never lets escape, so a caller can neither add to nor remove from the authority set after
 * conversion. The alternative was an unconditionally immutable copy from the platform's set
 * factory, which is shorter to write but leaves iteration order unspecified; the view keeps claim
 * order with duplicates collapsed, which holds a rendered authority list stable from run to run and
 * so keeps a test that asserts one from depending on an order the platform does not promise.</p>
 *
 * <p>Alternatives Considered: no configuration class accompanies this one, and the shared kernel
 * admits no configuration package at all. A shared {@code SecurityConfig} was evaluated and
 * rejected: each of the eight service modules owns its own filter chain and decides for itself
 * which of its routes demand the administrative authority, so a shared one would have to anticipate
 * eight route tables it cannot see. The cost is that eight modules each carry a few lines of wiring
 * that one class could have carried once; the gain is that a route table stays beside the routes it
 * governs, and that this class needs to know nothing about how any service is routed. A consuming
 * module opts in by constructing this converter with the two group names read from configuration
 * and handing it to the framework's authentication converter. Requiring those names in the
 * constructor makes authorization drift fail while the service starts instead of turning every
 * request into a forbidden response after deployment.</p>
 *
 * <p>Alternatives Considered: no annotation processor generates any member of this class. Generated
 * accessors and constructors cannot carry the documentation this tree requires, so a generated
 * member either fails the documentation gate or has to be excused from it, and no exclusion reaches
 * this source tree. Java's own record types with explicit constructors offer the same brevity with
 * members that can be documented, and this class needs neither.</p>
 *
 * <p>Alternatives Considered: rationale inside this file is written as a single comment line that
 * opens with one of the four canonical labels and carries the reason alone. Pairing it with a
 * second line stating what the statement below does was evaluated and rejected: such a line
 * restates the statement, which is the first practice the explainability rule forbids, and purpose
 * belongs in the Javadoc where the language already puts it. The paired form is reserved for fenced
 * command blocks in prose, where no docstring construct exists to hold the purpose.</p>
 *
 * <p>Assumptions: no executable parity comparison backs this class. The reference suite's
 * end-to-end layer covers batch flows, and its own guide records at lines 83 to 85 of
 * {@code tests/README.md} that the online programs cannot be driven end to end without a
 * transaction monitor, which is absent from the build agent. The one baseline program that sets the
 * user type is an online program, so there is nothing to compare a converted authority against and
 * no such backing is claimed for it. This class is verified by its own unit tests under this
 * module's test tree, which is a different tree from the reference suite at the repository root and
 * substitutes for none of it.</p>
 *
 * <p>This class keeps no mutable state, so a single instance is safe to share across threads and is
 * meant to be shared. It is declared final because it is the one owner of the mapping above, and a
 * subclass overriding the conversion would move that ownership somewhere a reader of this file
 * cannot see.</p>
 *
 * <p>Trade-offs: this file is written in ASCII with no byte-order mark, and an em dash is written
 * as two ordinary hyphens. Reproducing the typographic punctuation of the migration's prose would
 * read closer to it, but this repository holds a visually identical non-breaking hyphen in another
 * file, and a justification label spelled with that code point is one a search for the label does
 * not find. Restricting the file to ASCII puts that failure out of reach and keeps the bytes stable
 * under any default charset, at the cost of plainer punctuation.</p>
 */
public final class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /**
     * The name of the token claim this class reads, and the only input it takes.
     *
     * <p>Assumptions: the managed identity provider publishes a subject's group memberships under
     * this claim name, and the colon inside the name is part of the name rather than a separator to
     * be split on. It is declared once here so that no consuming module spells it out again, which
     * is the same discipline the reference baseline applies to a record layout when it resolves the
     * layout through one copybook include path instead of restating it in each program.</p>
     */
    public static final String GROUPS_CLAIM = "cognito:groups";

    /**
     * The authority an administrator holds, which is also the provider group name verbatim.
     *
     * <p>Assumptions: this is the target of the baseline user type {@code 'A'}, whose condition name
     * is declared at line 27 of {@code app/cpy/COCOM01Y.cpy} and whose stored counterpart is the
     * field at line 22 of {@code app/cpy/CSUSR01Y.cpy}. A route that demands it is authorised with
     * the authority predicate and this constant, never with the role predicate; the reason that
     * distinction matters is recorded on this class.</p>
     *
     * <p><strong>Assumptions: this literal is a FIXED CROSS-LANGUAGE CONTRACT and is not derived from
     * any deployment-time value.</strong> Three consumers match it byte for byte -- this class, which
     * turns it into a Spring Security authority; {@code ui/src/hooks/useAuth.ts}, which reads it out
     * of the token; and the SPA's administrative routes, which test for it -- and the identity
     * provider must therefore mint a group of exactly this name. That is why
     * {@code infra/modules/cognito} declares its group names as this same literal rather than
     * composing them from its {@code name_prefix} input. Refactoring Rationale: the composed form was
     * the earlier shape and it failed in the worst available way. Group names are scoped to a user
     * pool, so composing them bought no collision safety, while any root that set a non-default
     * prefix produced tokens carrying a group name no consumer recognises -- and because an
     * unrecognised group contributes no authority rather than raising, every user in that environment
     * would authenticate successfully and then be refused by every route, with nothing in any log
     * naming the group as the cause. A fixed literal makes that failure unreachable.</p>
     */
    public static final String ADMIN_AUTHORITY = "carddemo-admin";

    /**
     * The authority an ordinary user holds, which is also the provider group name verbatim.
     *
     * <p>Assumptions: this is the target of the baseline user type {@code 'U'}, whose condition name
     * is declared at line 28 of {@code app/cpy/COCOM01Y.cpy}. It is the value the sign-on program's
     * else branch at lines 236 to 239 leads to, so a subject holding it reaches the ordinary menu
     * rather than the administrative one.</p>
     */
    public static final String USER_AUTHORITY = "carddemo-user";

    /**
     * The closed set of group names this class recognises.
     *
     * <p>Assumptions: membership of this set is the executable form of the two-value domain that the
     * two reference artefacts witness between them. A name outside it contributes no authority, for
     * the reason recorded on this class, so this declaration is the complete authority vocabulary
     * the migrated system can produce from a group claim.</p>
     */
    private static final Set<String> RECOGNISED_AUTHORITIES = Set.of(ADMIN_AUTHORITY, USER_AUTHORITY);

    /**
     * Creates a converter after verifying the configured identity-provider group contract.
     *
     * <p>Assumptions: the calling service reads both values from the configuration written by the
     * infrastructure root from the Cognito module's group-name outputs. The names are checked but
     * not stored because the constants in this class remain the executable authority vocabulary;
     * configuration is evidence that the deployed pool agrees with that vocabulary, not a way to
     * redefine it.</p>
     *
     * <p>Alternatives Considered: a no-argument constructor was rejected because it lets a service
     * start without ever comparing its compiled authority names with the groups the deployed pool
     * actually created. Mutable properties were also rejected: a group rename is a migration
     * contract change, not a deployment preference, and making it settable would permit two
     * services to authorize the same token differently.</p>
     *
     * @param configuredAdminGroupName the administrator group name read from runtime
     *     configuration; must equal {@link #ADMIN_AUTHORITY}
     * @param configuredUserGroupName the ordinary-user group name read from runtime
     *     configuration; must equal {@link #USER_AUTHORITY}
     * @throws NullPointerException if either configured name is {@code null}
     * @throws IllegalStateException if either configured name differs from the compiled contract
     */
    public JwtRoleConverter(String configuredAdminGroupName, String configuredUserGroupName) {
        requireConfiguredGroupName(ADMIN_AUTHORITY, configuredAdminGroupName);
        requireConfiguredGroupName(USER_AUTHORITY, configuredUserGroupName);
    }

    /**
     * Converts the group claim of one validated token into the authorities its subject holds.
     *
     * <p>The claim named by {@link #GROUPS_CLAIM} is read from the token, each name it carries is
     * checked against {@link #RECOGNISED_AUTHORITIES}, and each recognised name becomes one
     * authority whose value is that group name verbatim. Names outside the recognised pair
     * contribute nothing and repeated names collapse to a single authority.</p>
     *
     * <p>Assumptions: no value read from the token is logged, placed in an exception message or
     * returned in any form other than a recognised group name, so this conversion cannot disclose
     * token content. There is also no parameter through which a caller could assert a user type: the
     * echoed byte at line 26 of {@code app/cpy/COCOM01Y.cpy}, moved into place at line 227 of
     * {@code app/cbl/COSGN00C.cbl}, has no counterpart in this signature, which is the whole point
     * of the class.</p>
     *
     * @param token the validated token whose group claim is read; must not be {@code null}, and is
     *     expected to have been decoded and signature-checked upstream, because this method performs
     *     no validation of its own
     * @return the authorities the subject holds, as an unmodifiable collection in claim order with
     *     duplicates collapsed; empty rather than {@code null} when the claim is absent, when it is
     *     empty, and when it names no recognised group
     * @throws NullPointerException if {@code token} is {@code null}, which is a defect at the call
     *     site rather than a runtime state worth tolerating: an absent claim is expressed by an empty
     *     result, whereas an absent token means no authentication took place and there is nothing to
     *     convert
     */
    @Override
    public Collection<GrantedAuthority> convert(Jwt token) {
        // Assumptions: an explicit check names the parameter in the failure, so a caller that wired
        // the converter into a chain where no token is present is sent to their own line rather than
        // to a dereference inside this class.
        Objects.requireNonNull(token, "token must not be null");

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String groupName : readGroupNames(token)) {
            // Trade-offs: membership is tested before an authority is built, so the vocabulary
            // returned is the one this class declares rather than the one a provider's group list
            // happens to hold. The pass-through alternative and the cost of this closure are
            // recorded on the class.
            if (RECOGNISED_AUTHORITIES.contains(groupName)) {
                authorities.add(new SimpleGrantedAuthority(groupName));
            }
        }

        // Trade-offs: an unmodifiable view over a set that never escapes this method is returned
        // instead of a copy from the platform's set factory. Both deny mutation; only this one keeps
        // claim order, which is what stops a caller that renders the authorities from depending on an
        // iteration order the factory does not promise.
        return Collections.unmodifiableSet(authorities);
    }

    /**
     * Reads the group claim from a token and reduces it to the group names it carries.
     *
     * <p>Two encoded shapes are accepted. A JSON array arrives as a collection, which is the shape
     * the managed identity provider emits for a subject in one or more groups. A JSON string arrives
     * as a single textual value, which is the shape one group takes when it is encoded as a scalar;
     * it is accepted because discarding it would turn a legitimate membership into an empty
     * authority set and a forbidden response that reads like a defect in the mapping. Any other
     * shape, and an absent claim, yield no names.</p>
     *
     * <p>Assumptions: the claim is read from the token's own claim map rather than through the
     * convenience accessor that converts a claim to a list of strings. That accessor raises when a
     * claim is present in a shape its conversion service cannot turn into a list, and it names the
     * offending type while doing so. Reading the raw value and deciding here which shapes are
     * acceptable makes the never-raises property of this conversion a property of this class, which
     * a unit test can assert directly, instead of an inherited consequence of which converters
     * happen to be registered elsewhere.</p>
     *
     * <p>Assumptions: a token cannot be constructed without claims, so the claim map itself is
     * present and only the individual claim can be missing. Entries that are not textual are skipped
     * rather than coerced, because a group name is text by definition: a numeric or structured entry
     * is a malformed claim rather than a name, and rendering it would put a value taken from the
     * token into an authority this class never declared.</p>
     *
     * @param token the validated token to read from; must not be {@code null}, which its only caller
     *     has already established before delegating here
     * @return the group names the claim carries, in claim order and without interpretation; empty
     *     rather than {@code null} when the claim is absent, when it is empty, and when it is encoded
     *     in a shape this method does not accept
     */
    private static List<String> readGroupNames(Jwt token) {
        Object rawClaim = token.getClaims().get(GROUPS_CLAIM);
        if (rawClaim == null) {
            // Assumptions: a subject in no group is an ordinary state, so the absent claim resolves
            // to no names and the caller turns that into an empty authority set.
            return List.of();
        }

        if (rawClaim instanceof String singleGroup) {
            return List.of(singleGroup);
        }

        if (!(rawClaim instanceof Collection<?> claimEntries)) {
            // Trade-offs: an unexpected encoding yields no names instead of an exception. The
            // alternative would let a malformed claim break a request that the route would refuse
            // anyway, and it would do so from inside a filter chain where the empty result is
            // already the documented outcome for a subject with no recognised group.
            return List.of();
        }

        List<String> groupNames = new ArrayList<>(claimEntries.size());
        for (Object entry : claimEntries) {
            if (entry instanceof String groupName) {
                groupNames.add(groupName);
            }
        }
        return groupNames;
    }

    /**
     * Fails service construction when one configured group name differs from the compiled contract.
     *
     * <p>Trade-offs: the exception reports only which side of the contract failed and never echoes
     * the configured value. Group names are not credentials, but omitting the value keeps startup
     * diagnostics shape-only and prevents a future caller from turning this helper into a generic
     * configuration-value disclosure path.</p>
     *
     * @param expectedGroupName the invariant authority name compiled into this class
     * @param configuredGroupName the group name supplied by runtime configuration
     * @throws NullPointerException if {@code configuredGroupName} is {@code null}
     * @throws IllegalStateException if {@code configuredGroupName} differs from
     *     {@code expectedGroupName}
     */
    private static void requireConfiguredGroupName(
            String expectedGroupName, String configuredGroupName) {
        Objects.requireNonNull(configuredGroupName, "configured group name must not be null");
        if (!expectedGroupName.equals(configuredGroupName)) {
            throw new IllegalStateException(
                    "configured Cognito group name does not match the compiled authority contract");
        }
    }
}
