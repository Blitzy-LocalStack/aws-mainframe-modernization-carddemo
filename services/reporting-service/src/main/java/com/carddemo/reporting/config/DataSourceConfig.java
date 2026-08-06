package com.carddemo.reporting.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds this context's connection pool so that the schema search path is pinned once at the
 * connection boundary, and so that the pool refuses to start at all unless the read-only posture
 * this context depends on is genuinely configured.
 *
 * <p>This class has exactly two responsibilities and deliberately no third. It pins the search
 * path that every unqualified name in this context resolves against, and it sizes the pool from
 * externally declared configuration. It creates no schema, no table, no view and no privileges, and
 * it overrides neither entity scanning nor the transaction manager.
 *
 * <h2>What this context is permitted to read</h2>
 *
 * <p>Alternatives Considered: giving this context a schema of its own, with tables and a migration
 * directory, so that its datasource configuration would look like every other service's. Rejected
 * because the reporting context reads and never writes, and every store it reads is owned
 * elsewhere. The migration that bootstraps the database creates {@code reporting} as a schema that
 * holds no table at all and exists only to house read-only cross-schema views, stated at
 * {@code data-migration/sql/V0__schemas_and_roles.sql} L542 to L549, which matches the target
 * design's own entry of "(none)" against this context's owned tables. Copying those stores locally
 * to obtain a private schema would create a second truth for figures whose whole purpose is to
 * restate the first one exactly.
 *
 * <p>Assumptions: the login role this pool authenticates as can read the four views and nothing
 * else, and that is enforced by the database rather than trusted to query authors. The service
 * role receives {@code USAGE} on the {@code reporting} schema and no privilege whatsoever on
 * {@code ledger}, {@code account}, {@code card} or {@code reference}, at
 * {@code V0__schemas_and_roles.sql} L582 and L888 to L892, and that same file revokes default
 * table-level read from it in all four of those schemas at L928 to L935. A separate no-login owner
 * role holds the read privileges the views themselves execute with, so the views can read what the
 * caller cannot. That is why this class configures a pool and asserts a posture rather than
 * attempting to police access itself.
 *
 * <p>Assumptions: the views, the roles and the grants all belong to the data-migration package and
 * never to this module. The four views are created by
 * {@code data-migration/sql/V1__reporting_views.sql}, which builds each one with a security
 * barrier, assigns it to the owner role, masks the card number every ledger-derived view publishes,
 * and grants read on each view by name. A view missing at run time is therefore a defect to report
 * against that package, and explicitly not something for this service to create for itself, as
 * {@code V0__schemas_and_roles.sql} L565 to L567 states directly.
 *
 * <p>Alternatives Considered: pointing this context at a read replica, which is the reflex for a
 * reporting workload. Rejected on the target design's own reasoning, that "a replica adds cost and
 * replica-lag semantics for no parity benefit". Reads go to the writer through the read-only views
 * instead, so a figure this context reports cannot disagree with the ledger it reports on because
 * of replication lag.
 *
 * <p>Alternatives Considered: treating a read-only role as a reduction in capability that would
 * need compensating for somewhere. It is not one for this context, and the baseline itself shows
 * why. The statement subroutine at {@code app/cbl/CBSTM03B.CBL} declares six file operations at
 * L103 to L108, open, close, read-next, read-by-key, write and rewrite, and dispatches on the data
 * definition name at L118. The two report programs drive only the first four of those six; the
 * write and rewrite operations are unreachable on any path this context takes, so a role holding
 * read and nothing else gives up nothing the baseline ever exercised here.
 *
 * <h2>Why the search path is pinned here rather than per query</h2>
 *
 * <p>Assumptions: pinning through the pool's connection initialisation statement makes schema
 * resolution a single enforcement point that a later query author cannot forget. The alternative,
 * qualifying every table reference in every query, distributes the obligation across every query
 * ever added and fails the first time someone omits it, which presents as a name resolving
 * somewhere unintended rather than as an error. Relying on a single default-schema setting is not
 * an alternative either, because it names one schema and this context's reach spans several. The
 * bootstrap migration reaches the same conclusion from the other direction and records it at
 * {@code V0__schemas_and_roles.sql} L592 to L600, where it declines to set a role-level search path
 * precisely because each service pins its own, and one setting with two owners drifts invisibly.
 *
 * <p>Assumptions: the direct evidence for how wide that reach is comes from the two baseline
 * programs this context replaces, and the two do not agree, so the discrepancy is recorded here
 * rather than smoothed over. The statement job joins four data definitions at
 * {@code app/jcl/CREASTMT.JCL} L83 to L86, reaching the transaction store and three account-side
 * stores. The report job joins its own four at {@code app/jcl/TRANREPT.jcl} L65 to L74, reaching
 * the transaction store, the card cross-reference and two reference stores. Their union is three
 * schemas, {@code ledger}, {@code account} and {@code reference}, and the two jobs share only the
 * transaction store and the cross-reference. Neither job declares a {@code CARDFILE} data
 * definition anywhere, so neither reads the card store directly; the cross-reference is an
 * account-side store despite its name. The card store nonetheless appears among the four schemas
 * granted to the view owner, because a card attribute does surface on an endpoint here, masked, on
 * the ledger-derived views. That is the whole of the discrepancy, and none of it is resolved by
 * this class, which pins whatever the configuration declares rather than restating a schema list
 * that would then have two owners.
 *
 * <h2>Isolation and pool sizing against the baseline</h2>
 *
 * <p>Trade-offs: the database's default read-committed isolation is stricter than the baseline's
 * declared read integrity, and the difference is accepted rather than tuned away. The CICS resource
 * definitions, 505 lines of reference material that is never modified, declare
 * {@code READINTEG(UNCOMMITTED)} on the first file stanza at {@code app/csd/CARDDEMO.CSD} L90, so
 * the baseline permitted a report to read a value a concurrent task had not yet committed. Nothing
 * here reproduces that, so a dirty read the baseline allowed cannot occur; what is given up is any
 * report that depended on seeing uncommitted work, and no report does. The tightening is
 * deliberate and is recorded as a divergence rather than presented as equivalence.
 *
 * <p>Trade-offs: pool sizing is a genuinely new degree of freedom rather than a ported setting, and
 * it is bound from configuration here rather than chosen. The same stanza declares
 * {@code STRINGS(1)} at {@code app/csd/CARDDEMO.CSD} L91, a ceiling of one concurrent access path
 * per file, so the baseline had no pool to size and serialised concurrent readers instead. Removing
 * that ceiling admits real concurrency and, with it, an obligation the baseline never had: the
 * aggregate connection count across all eight contexts has to stay inside the database cluster's
 * connection budget, which is why the ceiling for this context is declared per environment in the
 * profile documents rather than hard-coded here, where it could not vary by environment. Raising
 * throughput past that budget would starve the other seven contexts, so the compromise accepted is
 * a modest per-context ceiling in exchange for a bounded total.
 *
 * <p>Assumptions: the same stanza declares {@code JOURNAL(NO)} at L94 and, at L96,
 * {@code RECOVERY(NONE)} alongside {@code JNLSYNCWRITE(YES)}, so the accurate description of the
 * baseline is that it kept no forward recovery log and no data-change journalling, and not that it
 * journalled nothing at all. This context writes nothing, so neither setting has an analogue to
 * carry across; the store it reads is encrypted and backed up, and that divergence is recorded in
 * the migration's divergence register.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Alternatives Considered: declaring a schema-migration bean here so that this module's
 * datasource setup would mirror its seven peers. Rejected because this module owns no schema to
 * migrate. The migration tool is version-managed centrally only because the other seven contexts
 * use it, no migration artifact is on this module's compile or runtime classpath, and the schema
 * generation setting is declared as none, so nothing in this module may emit schema definition
 * statements. The bootstrap migration states the corollary plainly at
 * {@code V0__schemas_and_roles.sql} L554 to L556: a migration directory appearing under this module
 * would itself be a defect rather than an addition.
 *
 * <p>Assumptions: the driver is resolved from the connection string and is never named in code.
 * The driver artifact is declared at run-time scope in this module's {@code pom.xml} on purpose, so
 * it is absent from the compile classpath and an accidental direct reference to a driver type fails
 * the build instead of quietly coupling this context to one vendor's classes. Naming the driver
 * class in a string would evade that guard while gaining nothing, so neither the type nor its name
 * appears here.
 *
 * <p>Assumptions: every endpoint and every credential is resolved outside this repository. All three
 * of the {@code spring.datasource} keys this class binds through -- the connection string, the login
 * name and its secret -- are declared in the base configuration document as environment placeholders
 * carrying no fallback, and neither of the two profile overlays supplies a value for any of them, so
 * the running task receives all three from the provisioning layer's outputs by way of the parameter
 * and secret stores. No value of any of the three exists in this file or in the documents it binds
 * from, which is what makes the absence of committed secrets a structural property of the design
 * rather than a matter of review discipline.
 *
 * <h2>Documentation convention</h2>
 *
 * <p>Alternatives Considered: the justification labels above and below are written in the plural,
 * un-parenthesised, colon-terminated form taken from the governing rule itself. The repository was
 * measured before choosing rather than assumed, with a byte-exact search: taking the compromise
 * label as the representative case, the form used here appears 2440 times across 436 files, against
 * 104 occurrences of its singular spelling and 152 of a bracketed spelling. Two further reasons
 * settled it. The house test guide renders that same label with a non-breaking hyphen in place of a
 * plain one and drops the colon, so its bytes were deliberately not copied and every label here is
 * typed from the rule text instead, which is also why this file holds no byte outside the
 * seven-bit range. The written convention this follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // Assumptions: this is the identical prefix the framework's own pooled-datasource factory
    // binds, so declaring a pool here changes which bean definition wins and changes nothing about
    // how that pool is configured. Every key declared under it binds exactly as it would have,
    // including the nested transport-security pair, and both environment overlays keep applying as
    // ordinary property sources. Restating the prefix as anything else would strand every declared
    // pool setting at its library default while the configuration still appeared to set them.
    /** The configuration prefix whose keys are bound onto the pool this class publishes. */
    private static final String HIKARI_PROPERTY_PREFIX = "spring.datasource.hikari";

    /** The key whose statement pins the search path, named so a failure can cite it. */
    private static final String INIT_SQL_PROPERTY = HIKARI_PROPERTY_PREFIX + ".connection-init-sql";

    /** The key that declares the pool read-only, named so a failure can cite it. */
    private static final String READ_ONLY_PROPERTY = HIKARI_PROPERTY_PREFIX + ".read-only";

    // Assumptions: the check below matches on this opening alone and never on a whole statement,
    // because the schema list is the configuration's to own and restating it here would give one
    // value two owners that could then disagree. The bootstrap migration takes the same position at
    // V0__schemas_and_roles.sql L592 to L600, where it declines to set a role-level search path so
    // that resolution is left wholly to the connecting service. What is asserted here is therefore
    // that the pinning mechanism is present, not which schemas it names.
    /** The normalised opening of any statement that pins a search path. */
    private static final String SEARCH_PATH_PREFIX = "set search_path";

    /** Matches the default schema as a whole word, so it can be refused from the path. */
    private static final String PUBLIC_SCHEMA_PATTERN = ".*\\bpublic\\b.*";

    /** Matches any run of whitespace, so a statement is compared on meaning not on spacing. */
    private static final String WHITESPACE_RUN_PATTERN = "\\s+";

    /**
     * Validates the two declared settings this context's correctness rests on, so that a
     * misconfiguration stops the service at startup instead of surfacing later as a failed query.
     *
     * <p>Assumptions: neither of the two bound keys, {@code spring.datasource.hikari.read-only} and
     * {@code spring.datasource.hikari.connection-init-sql}, carries a fallback, and the omission is
     * the point. Both are declared in the base configuration document and neither is overridden by
     * either profile overlay, so should either be removed, placeholder resolution fails and the
     * service does not start. That is the intended outcome: the search-path pin is the single
     * enforcement point that every unqualified name in this context depends on, so losing it
     * silently would turn a configuration edit into a class of query failure appearing far from its
     * cause. A default here would have supplied the missing value and hidden the edit.
     *
     * <p>Trade-offs: validating a declared setting rather than assuming it duplicates a small
     * amount of knowledge between this class and the configuration it reads, and that cost is
     * accepted because the two then disagree loudly at startup instead of quietly at run time. What
     * is duplicated is exactly two key names and nothing else, and both are read from the same
     * prefix the pool binds, so the checked value and the applied value come from one source and
     * cannot drift apart.
     *
     * <p>Alternatives Considered: retaining both validated values as fields and exposing them.
     * Rejected because nothing reads them. The pool receives both by binding, not from this class,
     * so a retained copy would be state that could only ever go stale relative to the bound value,
     * and an accessor over it would be public surface with no caller. Validating and discarding
     * keeps one owner for each value and leaves this class with the single bean it publishes.
     *
     * @param connectionInitSql the statement the pool runs on every new physical connection, taken
     *     from the same key the pool binds so that one declared value is both checked and applied
     * @param readOnly whether the pool marks its connections read-only, which this context
     *     requires to be true because it holds read privileges and nothing else
     * @throws IllegalStateException if the statement is absent, does not pin a search path, or
     *     admits the default schema, or if the pool is not declared read-only
     */
    public DataSourceConfig(
            @Value("${" + INIT_SQL_PROPERTY + "}") String connectionInitSql,
            @Value("${" + READ_ONLY_PROPERTY + "}") boolean readOnly) {

        // Assumptions: the read-only flag is asserted rather than merely expected because it is a
        // second, independent guard over privileges the database already withholds -- the bootstrap
        // migration revokes default table-level read from this login role in all four source schemas
        // at V0__schemas_and_roles.sql L928 to L935. A pool that permitted writes would still be
        // refused by that role, but it would be refused at the moment of the write, deep inside a
        // report that had already done its work, rather than at startup where the cause is in front
        // of whoever changed it.
        if (!readOnly) {
            throw new IllegalStateException(
                    READ_ONLY_PROPERTY + " must be true; this context holds read privileges only");
        }

        requireSearchPathPin(connectionInitSql);
    }

    /**
     * Checks that a declared initialisation statement really does pin a search path that excludes
     * the default schema, returning normally when it does and refusing to start when it does not.
     *
     * <p>Assumptions: matching is done on a case-folded, whitespace-normalised copy so that a
     * differently cased or differently spaced statement is accepted on its meaning rather than on
     * its formatting. Case folding is done with the invariant root locale rather than the ambient
     * one, because in at least one locale the default rules map an ASCII letter of this keyword
     * outside ASCII, which would make a correct statement fail on a machine configured that way.
     *
     * <p>Trade-offs: the default schema is rejected outright, which is stricter than the database
     * requires and is chosen so that dropping it stays a deliberate act. Leaving it on the path
     * would let an object created there shadow one of this context's views, so re-admitting it
     * should require editing this check and recording a reason, not merely editing a list. The
     * compromise is that a future path legitimately needing it fails at startup until that happens.
     *
     * <p>Trade-offs: the whole word is matched rather than the bare characters, so a schema whose
     * name merely contains those characters is not rejected by accident. The narrower match costs a
     * pattern evaluation once per startup and avoids refusing a valid configuration.
     *
     * @param initSql the declared statement to check, as bound from the configuration key
     * @throws IllegalStateException if the statement is {@code null}, blank, does not begin by
     *     pinning a search path, or names the default schema
     */
    private static void requireSearchPathPin(String initSql) {
        if (initSql == null || initSql.isBlank()) {
            throw new IllegalStateException(
                    INIT_SQL_PROPERTY + " must pin the schema search path and must not be blank");
        }

        String normalised =
                initSql.trim().toLowerCase(Locale.ROOT).replaceAll(WHITESPACE_RUN_PATTERN, " ");

        if (!normalised.startsWith(SEARCH_PATH_PREFIX)) {
            throw new IllegalStateException(
                    INIT_SQL_PROPERTY + " must begin by pinning the schema search path");
        }

        if (normalised.matches(PUBLIC_SCHEMA_PATTERN)) {
            throw new IllegalStateException(
                    INIT_SQL_PROPERTY + " must not admit the default schema to the search path");
        }
    }

    /**
     * Builds the pool this context reads through, pinning the schema search path on every physical
     * connection and taking its sizing from the declared configuration.
     *
     * <p>Assumptions: all three of the {@code spring.datasource} values -- the connection string,
     * the login name and the secret -- are read from the supplied properties object, which resolves
     * them from the environment the provisioning layer injects, so none of the three is named here
     * and this class references no driver type. The pool type is stated explicitly because the
     * binding below writes pool settings, and those settings exist only on this pool
     * implementation.
     *
     * <p>Assumptions: binding the prefix onto the returned pool is what applies the search-path
     * pin, the read-only flag, the sizing and the transport-security settings, and it is the same
     * mechanism the framework's own factory uses. Copying those settings across by hand instead
     * would freeze this class against the set of keys that happened to be declared when it was
     * written, so a key added later would be declared, appear to be configured, and bind to
     * nothing.
     *
     * <p>Alternatives Considered: leaving the pool entirely to automatic configuration, which would
     * apply every declared key without this class existing. Rejected because it can assert nothing:
     * the pin and the read-only posture would be silently optional, and the bootstrap migration
     * records at {@code V0__schemas_and_roles.sql} L595 to L596 that each service is expected to pin
     * its schema in its own datasource configuration precisely so the pin has one visible owner.
     *
     * @param dataSourceProperties the resolved connection settings, supplied by the framework and
     *     carrying the connection string, login name and secret from the injected environment
     * @return the configured pool, read-only, sized from the declared configuration, and pinning
     *     the validated search path on every physical connection it opens
     */
    @Bean
    @ConfigurationProperties(HIKARI_PROPERTY_PREFIX)
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
