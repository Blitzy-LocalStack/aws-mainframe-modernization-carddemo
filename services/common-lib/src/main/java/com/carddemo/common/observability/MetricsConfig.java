package com.carddemo.common.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the three Micrometer common tags that label every meter a migrated CardDemo service
 * publishes.
 *
 * <h2>The contract: three tags, and nothing else</h2>
 *
 * <p>A common tag is a dimension the meter registry attaches to every meter it accepts, without the
 * emitting code naming it at the call site. This class contributes exactly three, the set is closed
 * at three, and each one is here because it answers a question an operator actually asks and enables
 * an action an operator can actually take:</p>
 *
 * <ul>
 *   <li>{@code service} -- partitions a series by the bounded context that owns it, so one series is
 *       attributable to exactly one of the eight migrated services. QUESTION: which deployable
 *       produced this measurement. ACTION: route the investigation to the one context whose code can
 *       change the number. Without it, two contexts publishing a same-named meter sum into one
 *       series and neither can be read back out of it.</li>
 *   <li>{@code environment} -- keeps the development and the production series apart inside a single
 *       metrics namespace. QUESTION: was this measured where it matters. ACTION: disregard a
 *       development spike without disregarding the meter. Without it a development series and a
 *       production series of the same name merge, and the merged value misattributes activity from
 *       one to the other in whichever direction reads worse.</li>
 *   <li>{@code version} -- attributes a change in a series to the deployed image it came from.
 *       QUESTION: which build did this. ACTION: decide a roll-back from the graph itself instead of
 *       correlating a step change by hand against a release record.</li>
 * </ul>
 *
 * <p>Those three are the whole of what this class owns. Every question it answers is a question about
 * how a series is LABELLED; every question about what a series MEASURES belongs to the bounded
 * context that emits it, which is why no meter is registered here and no meter name is declared
 * here.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Alternatives Considered: stamping the three tags at each call site, next to every counter and
 * timer, was evaluated and rejected. The failure it admits is not extra typing but an UNATTRIBUTABLE
 * SERIES: one omitted tag at one call site publishes a series that carries a different key set from
 * every other series in the process, so it cannot be joined to them, cannot be grouped with them and
 * cannot be alarmed on alongside them -- and nothing about it looks wrong until someone asks the
 * question it can no longer answer. A registry-level contribution applies the set once,
 * unconditionally, to every meter the registry accepts, which removes the possibility of the omission
 * rather than relying on reviewers to catch it.</p>
 *
 * <p>Alternatives Considered: the mechanism is a {@link MeterFilter} bean built by
 * {@link MeterFilter#commonTags(Iterable)} rather than the framework's own
 * {@code MeterRegistryCustomizer} interface, and the reason is measured rather than stylistic. Three
 * findings decided it. First, {@code MeterRegistryCustomizer} is not a Micrometer type at all: it
 * ships in {@code org.springframework.boot:spring-boot-micrometer-metrics}, which the shared kernel
 * deliberately does not declare, so naming it would add a dependency to a library whose whole point
 * is to hold the smallest surface every service can agree on. Second, the two are the SAME mechanism
 * and not two competing ones: a customiser written the conventional way calls
 * {@code registry.config().commonTags(tags)}, and that method's own body installs
 * {@code MeterFilter.commonTags(tags)} into the registry's filter chain, so the filter is what a
 * customiser would have produced anyway. Third, the framework applies both kinds of bean to every
 * registry it post-processes -- one pass over the customiser beans, one over the filter beans -- so
 * choosing the filter forfeits no reach. What is gained is that this class compiles against
 * {@code micrometer-core} alone, and a unit test can exercise it against a plain in-memory registry
 * with no application context at all.</p>
 *
 * <p>Assumptions: universality is a property of the filter and not of the ordering of bean creation.
 * A filter is consulted when a meter is REGISTERED, not when the filter is built, so a meter created
 * long after this bean carries the three tags exactly as one created immediately after it does.
 * Micrometer's tag collection is keyed by tag name and keeps one entry per key, so a service that
 * also declares these three keys through framework properties converges on one tag per key rather
 * than publishing either key twice.</p>
 *
 * <p>Assumptions: where a key collides, the MEASURED precedence is that the meter's OWN tag wins and
 * the common tag is discarded -- a meter registered as {@code counter("x", "service", "impostor")}
 * against a filter contributing {@code service=auth-service} publishes {@code service=impostor}, and
 * the same holds for {@code environment} and {@code version}. This direction is stated explicitly
 * because it is the opposite of what a reader may expect from a mechanism described as applying tags
 * "unconditionally" and "to every meter", and because it decides how a collision must be handled. It
 * is the useful direction: a call site that deliberately names one of these keys is expressing
 * something the registry-level default cannot know, and a filter that overrode it would make the
 * override impossible rather than merely unnecessary. The consequence for a caller is that the three
 * keys are RESERVED at every call site. A meter that names one of them is not adding a dimension, it
 * is silently replacing the label an operator uses to attribute the series, which produces a series
 * that reads as belonging to a context that did not emit it -- the precise unattributable-series
 * failure the decision record above rejects a per-call-site scheme to avoid. No meter in this
 * migration names any of the three, and none should.</p>
 *
 * <p>Assumptions: this class owns none of the three values it labels with, and the property contract
 * it depends on is stated on {@code SERVICE_PROPERTY}, {@code ENVIRONMENT_PROPERTY} and
 * {@code VERSION_PROPERTY} below. Nothing here embeds the name of a service, the name of an
 * environment or the identity of an image; each value arrives from the deployment that starts the
 * container, which is the property that keeps one built artefact deployable to every environment and
 * keeps a library that must never name a service module from naming one.</p>
 *
 * <p>Trade-offs: the set is held to three, and the compromise runs both ways. Three tags cannot
 * express everything an operator might want to slice by -- region, availability zone, task instance,
 * endpoint and caller are all absent, and an operator who needs one of them adds it to the one meter
 * that needs it rather than finding it already present everywhere. What is bought is that the cost
 * stays bounded: a common tag multiplies the series count of EVERY meter in the process, so the three
 * chosen are exactly those whose value sets are small, closed and known before any series is
 * published -- eight context names, a handful of environment names, one image identity per
 * deployment. A tag drawn from an open value set -- an account identifier, a card number, a
 * transaction identifier, a user identifier or a request correlation identifier -- would turn one
 * series into millions on every meter at once, which makes the metric both unreadable and expensive,
 * and is the one mistake in this area that cannot be undone once the series exist.</p>
 *
 * <p>Trade-offs: two categories of value are excluded outright rather than merely discouraged, and
 * both exclusions are structural consequences of the paragraph above. No primary account number, no
 * card verification value, no national identifier and no government-issued identifier may appear in a
 * tag name, a tag value, a meter name or a meter description; every one of them is masked, encrypted
 * or suppressed at the mapping layer of the migrated services, and telemetry must not be the path by
 * which one of them leaves the system. And no monetary amount is published as a meter value anywhere:
 * a meter value is recorded in a binary numeric type that cannot represent every scale-two decimal
 * exactly, whereas the money contract of this migration is exact scale-two decimal arithmetic from
 * the database column through to the wire. Money is therefore counted and timed -- how many postings,
 * how long a posting took -- and never measured.</p>
 *
 * <p>Assumptions: the exclusion above is a contract on the DEPLOYMENT, not a check this class
 * performs. Nothing here masks, redacts, validates or inspects the three values it is given: they are
 * normalised for blankness and otherwise emitted verbatim, so a {@code service} value of sixteen
 * digits is published as sixteen digits and a {@code version} value shaped like an access key is
 * published as that key. {@code com.carddemo.common.security.CardNumberMasker} is deliberately NOT
 * applied to them. This is recorded because the omission is invisible from the outside -- a reader who
 * sees a masker in the same library may reasonably assume telemetry is behind it -- and because the
 * consequence is not recoverable: a value that reaches a metrics backend has left the process, is
 * retained for the backend's retention window, appears in every dashboard and alert built on the
 * series, and cannot be recalled by fixing the property afterwards.</p>
 *
 * <p>Alternatives Considered: masking the three values here, or rejecting a value that looks like a
 * secret, was evaluated and rejected. All three are deployment IDENTITIES drawn from small closed sets
 * -- one of eight context names, one of a handful of environment names, one image identity -- so any
 * detector would be guessing at a value whose legitimate shape it cannot know, and the two failure
 * directions are both bad: masking a legitimate value silently destroys the attribution the tag exists
 * to provide, and failing startup over a false positive takes a service down for a cosmetic reason. A
 * value that is secret in one of these three properties is a misconfiguration of the deployment, and
 * the place to prevent it is where the value is set -- the task definition and the Terraform variables
 * that populate it, which carry sizing and identity values only and never a credential, with every
 * credential resolved from Secrets Manager instead. Trade-offs: this class therefore trusts its input,
 * and that trust is why the prohibition is written here in the contract a deployer reads rather than
 * left to be discovered.</p>
 *
 * <p>Alternatives Considered: a fourth and a fifth tag were available and were rejected. The
 * reference baseline carries a genuinely structured diagnostic record at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}, whose {@code ERR-LEVEL X(01)} at line 25
 * has four condition names at lines 26 to 29 -- {@code 'L'} for log, {@code 'I'} for info,
 * {@code 'W'} for warning and {@code 'C'} for critical -- and whose {@code ERR-SUBSYSTEM X(01)} at
 * line 30 has six at lines 31 to 36 -- {@code 'A'} for application, {@code 'C'} for CICS, {@code 'I'}
 * for IMS, {@code 'D'} for Db2, {@code 'M'} for message queueing and {@code 'F'} for file. Promoting
 * either domain to a common tag would have looked more faithful to that record and would have been
 * the wrong faithfulness. A common tag is attached unconditionally, so a severity tag would have to
 * carry some value on the many meters that have no severity at all, and a subsystem tag would
 * partition every series by a distinction the target runtime no longer draws. Both domains are
 * per-event properties of a structured log line, which is a different surface owned by
 * {@code com.carddemo.common.error} and described in section 3.3 of
 * {@code docs/architecture/observability.md}; section 3.7 of that same document describes THIS
 * surface and lists three tags. The boundary between the two sections is recorded here so that it is
 * not re-derived into a fourth or a fifth tag. For the same reason this class declares no severity
 * scale of its own: severity grades an event, and nothing here grades anything.</p>
 *
 * <p>Alternatives Considered: a {@code com.carddemo.common.config} package was the obvious
 * alternative home for a Spring configuration class named {@code MetricsConfig}, and it was rejected
 * structurally rather than stylistically. This library has no application-layer subpackage of any
 * kind -- no controller, service, repository, domain, transfer-object, mapper or configuration
 * package -- so filing this class by its framework stereotype would have introduced the first one, and
 * the question of which subpackage a new type belongs in would stop having one defensible answer.
 * Filing it by CONCERN keeps every subpackage name the name of a contract, so a reader looking for
 * the metric tag contract finds it under the concern it labels instead of having to know in advance
 * that the contract happens to be expressed as a configuration class. The cost is that a reader
 * hunting by stereotype has to learn the concern first.</p>
 *
 * <p>Alternatives Considered: Lombok would have removed the constructor below, and it is not used
 * anywhere in these services. Generated accessors and generated constructors cannot carry the Javadoc
 * the project Explainability rule requires, so a Lombok-built class either fails the documentation
 * gate or has to be suppressed out of it, and a suppression is how a gate stops being a gate. Plain
 * Java 21 with an explicit constructor gives the same brevity with members that can be documented.
 * </p>
 *
 * <p>Assumptions: this module is a library and not a deployable, which settles three absences that a
 * reader may come here looking for. No concrete registry implementation is a dependency of it, so
 * which backend receives these series, and in which wire format, is a per-service deployment decision
 * and not a compile-time one taken here for all eight at once. No health or management endpoint is
 * declared here either; the load balancer target group and the container health check read a path the
 * consuming service owns. And no cloud provider SDK is on this classpath at all, so nothing here can
 * reach a hosted metrics API directly. Trade-offs: each service therefore has to opt into an exporter
 * for itself and nothing arrives implicitly, which is accepted because the alternative binds an
 * export format into the shared kernel and adds an exporter to the classpath of any consumer that
 * scrapes nothing.</p>
 *
 * <h2>What this replaces</h2>
 *
 * <p>Refactoring Rationale: the reference baseline's entire observability surface is job output,
 * reached through three channels rather than one. Two are data definitions inside a step:
 * {@code app/jcl/POSTTRAN.jcl} declares {@code //SYSPRINT DD SYSOUT=*} at line 26 and
 * {@code //SYSOUT   DD SYSOUT=*} at line 27, and {@code app/jcl/INTCALC.jcl} declares the same pair
 * at lines 25 and 26 for the interest step, whose own line 22,
 * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}, additionally shows the business date arriving
 * as a job parameter rather than being read from a clock. The third channel is the job card itself:
 * {@code app/jcl/POSTTRAN.jcl} line 1 carries {@code MSGCLASS=0}, which routes the job log alongside
 * those two streams, and line 2 carries {@code NOTIFY=&SYSUID}, which is the ancestor of every alert
 * the target raises. The pairing is the standing convention of the batch tier and not a choice two
 * jobs happened to make: {@code SYSOUT} appears on one hundred and seventeen lines across
 * thirty-seven of the thirty-eight jobs in {@code app/jcl}, and {@code SYSPRINT} on eighty lines
 * across thirty-three of them. There is no metric emission of any kind in the baseline -- no counter,
 * no timer, no dimensional label -- so inspecting a run means inspecting a print file.</p>
 *
 * <p>Refactoring Rationale: what the target replaces is therefore NOT THE CONTENT of the job log but
 * its ADDRESSABILITY. A spooled job log is retrieved per job, by an operator who already knows which
 * job to go and look at, and all three channels above resolve to that one retrieval model. The target
 * needs the same content queryable across services and correlatable across a whole execution, because
 * a business operation that used to be a step inside one job is now a request crossing several
 * independently deployed services. That difference is exactly what makes an alarm on a RATE possible
 * where the baseline offered an eyeball on a listing: a print file cannot answer which service, which
 * environment and which deployed image for a series it never labelled, and a per-job retrieval cannot
 * reach across an execution it was never scoped to. Nothing about the baseline mechanism is treated
 * here as defective -- it addressed one job at a time because one job at a time was the unit of work,
 * and it was a complete and functioning operational model for the platform it ran on. It is
 * file-shaped where the target is stream-shaped, and the difference is one of MECHANISM.</p>
 *
 * <p>Refactoring Rationale: the {@code service} tag in particular is a promotion rather than an
 * invention, and saying so is the accurate framing.
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} declares {@code ERR-APPLICATION PIC
 * X(08)} at line 22, so the baseline already carried an application dimension on every logged event.
 * What changes is the scope and the guarantee: from a value a writer populates once per record, to a
 * label the registry applies unconditionally to every meter. The same record's
 * {@code ERR-EVENT-KEY X(20)} at line 40 is the baseline's own correlation identity, which is why the
 * migrated correlation filter formalises an existing concept as well; that filter belongs to
 * {@code com.carddemo.common.web} and not here, because a correlation identity travels with a request
 * whereas a common tag travels with a meter.</p>
 */
@Configuration(proxyBeanMethods = false)
public final class MetricsConfig {

    /**
     * Tag name identifying the bounded context that owns a series, {@code service}.
     *
     * <p>Assumptions: the three tag names are declared as constants rather than written as literals
     * at the point of use because they are a cross-cutting contract, not a local detail. A dashboard
     * query, an alarm dimension and a service's own configuration all group by these exact strings,
     * so a test can assert the emitted key set against the same constant the emitter used and a
     * rename cannot leave the two disagreeing.</p>
     */
    public static final String SERVICE_TAG = "service";

    /**
     * Tag name separating the deployment environments of a series, {@code environment}.
     *
     * <p>See {@code SERVICE_TAG} for why the names are constants.</p>
     */
    public static final String ENVIRONMENT_TAG = "environment";

    /**
     * Tag name identifying the deployed image behind a series, {@code version}.
     *
     * <p>See {@code SERVICE_TAG} for why the names are constants.</p>
     */
    public static final String VERSION_TAG = "version";

    /**
     * Value substituted for any of the three tags the deployment does not supply,
     * {@code unspecified}.
     *
     * <p>Alternatives Considered: refusing to start was evaluated and rejected. An unresolvable
     * placeholder aborts a context with a named property, which is the right behaviour for a database
     * URL or a credential, where a default that resolves is a default that can be deployed by
     * accident. A metric label is not that: letting a telemetry dimension stop a service from serving
     * traffic trades a labelling problem for an availability one. Substituting a token that is
     * DELIBERATELY NOT THE NAME OF ANY REAL ENVIRONMENT keeps the failure loud in the data instead --
     * the series appears under {@code unspecified} and stands out as its own line on any graph --
     * rather than being folded silently into whichever environment it was closest to and skewing it.
     * </p>
     *
     * <p>Alternatives Considered: emitting a warning log line at startup was also evaluated and not
     * adopted, and the reason is the one this class exists for. A startup log line is retrieved by
     * someone who already suspects a problem, which is precisely the per-job retrieval model the
     * migration moves away from; a distinct {@code unspecified} series is queryable and can carry an
     * alarm, so the signal lives on the surface built to be asked questions.</p>
     */
    public static final String UNSPECIFIED = "unspecified";

    /**
     * Configuration property supplying the {@code service} tag value,
     * {@code spring.application.name}.
     *
     * <p>Assumptions: the framework's own application-name property is the source rather than a
     * property private to this class, and that is a contract with the consuming services rather than
     * a convenience. Each service already sets it to its own module name, one deployable therefore
     * has exactly one name across its build, its metrics and its log correlation output, and no
     * service has to state the same name twice in two spellings that can drift apart.</p>
     */
    public static final String SERVICE_PROPERTY = "spring.application.name";

    /**
     * Configuration property supplying the {@code environment} tag value,
     * {@code carddemo.environment}.
     *
     * <p>Assumptions: the deployment supplies this, and supplies it as an injected environment
     * variable rather than as a committed value. The container platform injects
     * {@code CARDDEMO_ENVIRONMENT} and the framework's relaxed binding maps that name onto this
     * property with no code here to do the mapping, which is the same route by which every other
     * externalised setting in these services arrives.</p>
     */
    public static final String ENVIRONMENT_PROPERTY = "carddemo.environment";

    /**
     * Configuration property supplying the {@code version} tag value, {@code carddemo.version}.
     *
     * <p>Assumptions: the value identifies the DEPLOYED IMAGE rather than a source revision, because
     * a deployment is what shifts a series and so a deployment is what has to be identifiable from
     * one. It arrives the same way its sibling above does: the platform injects
     * {@code CARDDEMO_VERSION} and relaxed binding maps it onto this property.</p>
     */
    public static final String VERSION_PROPERTY = "carddemo.version";

    /**
     * Placeholder resolving {@code SERVICE_PROPERTY}, falling back to {@code UNSPECIFIED}.
     *
     * <p>Assumptions: an annotation argument has to be a compile-time constant, so the placeholder is
     * assembled from the property constant and the fallback constant rather than written out again as
     * a literal. The property name then appears exactly once in this class, which is what stops the
     * documented contract above and the string actually resolved below from drifting apart.</p>
     */
    private static final String SERVICE_PLACEHOLDER = "${" + SERVICE_PROPERTY + ":" + UNSPECIFIED + "}";

    /**
     * Placeholder resolving {@code ENVIRONMENT_PROPERTY}, falling back to {@code UNSPECIFIED}.
     *
     * <p>See {@code SERVICE_PLACEHOLDER} for why the placeholder is assembled rather than written
     * out.</p>
     */
    private static final String ENVIRONMENT_PLACEHOLDER =
            "${" + ENVIRONMENT_PROPERTY + ":" + UNSPECIFIED + "}";

    /**
     * Placeholder resolving {@code VERSION_PROPERTY}, falling back to {@code UNSPECIFIED}.
     *
     * <p>See {@code SERVICE_PLACEHOLDER} for why the placeholder is assembled rather than written
     * out.</p>
     */
    private static final String VERSION_PLACEHOLDER = "${" + VERSION_PROPERTY + ":" + UNSPECIFIED + "}";

    /**
     * The three common tags in the order they are declared on this class, resolved and normalised.
     *
     * <p>Trade-offs: the tags are built once, here, and held immutably rather than being rebuilt each
     * time the bean method is called. The compromise accepted is that a configuration change is not
     * observed without a restart, which is correct for these three: a service does not change its
     * name, its environment or its deployed image while running, and a series whose labels changed
     * underneath it would be two series reported as one.</p>
     */
    private final List<Tag> commonTags;

    /**
     * Resolves the three tag values from configuration and normalises each one.
     *
     * <p>Every value is normalised through the same rule, so no absent, blank or padded value can
     * reach a tag. That totality is the point: an empty tag value is accepted by the registry and
     * produces exactly the unattributable series this class exists to prevent, and it would produce
     * it silently.</p>
     *
     * @param service the resolved value of {@code SERVICE_PROPERTY}, a {@link String} naming the
     *     bounded context that owns the meters of this process; blank or absent yields
     *     {@code UNSPECIFIED}
     * @param environment the resolved value of {@code ENVIRONMENT_PROPERTY}, a {@link String} naming
     *     the deployment environment; blank or absent yields {@code UNSPECIFIED}
     * @param version the resolved value of {@code VERSION_PROPERTY}, a {@link String} identifying the
     *     deployed image; blank or absent yields {@code UNSPECIFIED}
     */
    public MetricsConfig(
            @Value(SERVICE_PLACEHOLDER) String service,
            @Value(ENVIRONMENT_PLACEHOLDER) String environment,
            @Value(VERSION_PLACEHOLDER) String version) {
        // WHY : Assumptions: the values are injected as constructor arguments rather than read from
        //       an environment object held as a field, because a constructor argument is what lets a
        //       test drive this class with three plain strings and no application context at all.
        //       The same shape is what keeps the class total: normalisation happens on the one path
        //       every value takes.
        this.commonTags = List.of(
                Tag.of(SERVICE_TAG, orUnspecified(service)),
                Tag.of(ENVIRONMENT_TAG, orUnspecified(environment)),
                Tag.of(VERSION_TAG, orUnspecified(version)));
    }

    /**
     * Contributes the three common tags to every meter registry in the application context.
     *
     * <p>The returned filter is consulted when a meter is REGISTERED rather than when the filter is
     * built, so it labels meters created at any point in the life of the process, including meters
     * created by a library that starts after this bean.</p>
     *
     * @return a {@link MeterFilter} that adds the {@code service}, {@code environment} and
     *     {@code version} tags to the identity of every meter the registry accepts, leaving each
     *     meter's own name and its own tags otherwise untouched
     */
    @Bean
    public MeterFilter commonTagsMeterFilter() {
        // WHY : Alternatives Considered: building the filter by hand, by implementing map and
        //       rewriting the meter identity, was evaluated and rejected. The library's own factory
        //       already merges the added tags with whatever tags the meter declared for itself and
        //       keeps one entry per tag name, so a hand-written version would either reproduce that
        //       merge or, more likely, replace a meter's own tags with these three and quietly
        //       discard the dimension the emitting code cared about.
        return MeterFilter.commonTags(this.commonTags);
    }

    /**
     * Substitutes {@code UNSPECIFIED} for a tag value the deployment did not usefully supply.
     *
     * @param value the configured tag value to normalise, a {@link String} that may be {@code null},
     *     empty, entirely whitespace or padded with it
     * @return the value stripped of surrounding whitespace when it carries any other character, and
     *     {@code UNSPECIFIED} when it is {@code null}, empty or whitespace only
     */
    private static String orUnspecified(String value) {
        // WHY : Assumptions: three distinct absences reach this method and all three have to collapse
        //       to the same visible token. A property left out entirely never arrives, because the
        //       placeholder's fallback has already supplied the token; a property set to an empty
        //       string DOES arrive, and the registry would accept it as a tag value; and a variable
        //       injected by a task definition can arrive carrying surrounding whitespace. The last
        //       one matters more than it looks: two values differing only in trailing whitespace are
        //       two different tag values, so one deployment would publish a second series that reads
        //       as identical to the first on any graph that trims what it prints.
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }
        return value.strip();
    }
}
