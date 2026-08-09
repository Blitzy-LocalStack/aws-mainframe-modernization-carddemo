package com.carddemo.common.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * Decides whether the environment is currently accepting mutating work, by reading the one
 * Parameter Store entry the nightly batch chain toggles around its write window.
 *
 * <p>This is the application half of the control the target architecture describes as the
 * {@code QuiesceOnlineWrites} and {@code ResumeOnlineWrites} states of the daily state machine,
 * which replace the operator SDSF commands in {@code app/jcl/CLOSEFIL.jcl} and
 * {@code app/jcl/OPENFIL.jcl}. Those two jobs closed and reopened the VSAM files so that the posting
 * chain owned the data exclusively; the flag this class reads is what the state machine sets in
 * their place, and this class is what makes the flag mean something to a running service.</p>
 *
 * <p>Refactoring Rationale: before this class existed the flag was created, toggled by two Lambda
 * functions and injected into every online task definition — and READ BY NOTHING. Every service
 * continued to accept writes throughout the window, so "quiesce" named a control that did not
 * exist. Fixing only the infrastructure would not have closed that: the parameter's name reaching a
 * container is not a gate, and neither is a task role that may read it.</p>
 *
 * <p>Assumptions: THE GATE FAILS CLOSED, and that is the property worth stating first. If the flag
 * cannot be read — the parameter is missing, the task role lacks permission, or the call times out —
 * the gate REFUSES the write. The alternative, admitting writes when the state of the window is
 * unknown, would make an outage of Parameter Store or a mistaken IAM change silently reopen the
 * window mid-batch, which is the exact condition the control exists to prevent. A refusal is
 * recoverable by retry; a write applied during posting is not.</p>
 *
 * <p>Assumptions: the value is read per request rather than injected once at task start, which is
 * why the task definition carries the parameter's NAME instead of its value. A value injected at
 * start would freeze the answer for the life of the task, so a task that started before the window
 * opened would keep refusing writes until it was replaced.</p>
 *
 * <p>Trade-offs: a short time-to-live cache sits in front of the call, so a busy service does not
 * make one Parameter Store request per mutating request. The cost is that a quiesce takes effect up
 * to one cache period late. That is accepted because the batch window is bracketed by explicit
 * states rather than being instantaneous, and because the alternatives are worse in both
 * directions: no cache spends a network round trip and a throttling quota on every write, while a
 * long cache widens the interval in which a write can still land after the window has closed. The
 * period is deliberately measured in seconds, not minutes.</p>
 */
public class OnlineWriteGate {

    /**
     * Value the flag carries while writes are permitted. Compared case-insensitively against the
     * trimmed parameter value.
     *
     * <p>Assumptions: the comparison is positive — writes are enabled only when the flag reads
     * exactly this — rather than negative against a "false" literal. A negative test would treat
     * every unexpected value, including an empty string or a typo, as permission to write, which is
     * the fail-open shape this class exists to avoid.</p>
     */
    static final String WRITES_ENABLED_VALUE = "true";

    private static final Logger LOG = LoggerFactory.getLogger(OnlineWriteGate.class);

    private final SsmClient ssm;
    private final String parameterName;
    private final Duration cachePeriod;
    private final Clock clock;
    private final AtomicReference<CachedDecision> cached = new AtomicReference<>(null);

    /**
     * Creates the gate over one Parameter Store entry.
     *
     * @param ssm client used to read the flag; must not be {@code null}
     * @param parameterName absolute name of the Parameter Store entry carrying the flag, as supplied
     *     to the task through {@code CARDDEMO_ONLINE_WRITES_PARAMETER}; must not be {@code null} or
     *     blank, because a blank name would make every read fail and therefore refuse every write
     * @param cachePeriod how long a decision may be reused before the flag is read again; must not
     *     be {@code null} and must be positive, since a zero or negative period would express "cache
     *     forever" or "cache in the past" rather than "do not cache"
     * @param clock the clock the cache measures its period against; must not be {@code null}. It is a
     *     parameter rather than a read of the system clock so that the cache's expiry is assertable
     *     without a test having to sleep -- a sleeping test would either be slow or flaky, and the
     *     property under test is precisely that a decision STOPS being reused, which no amount of
     *     waiting proves as directly as advancing a controlled reading past the boundary
     * @throws NullPointerException if {@code ssm}, {@code parameterName}, {@code cachePeriod} or
     *     {@code clock} is {@code null}
     * @throws IllegalArgumentException if {@code parameterName} is blank or {@code cachePeriod} is
     *     not positive
     */
    public OnlineWriteGate(
            SsmClient ssm, String parameterName, Duration cachePeriod, Clock clock) {
        this.ssm = Objects.requireNonNull(ssm, "ssm must not be null");
        this.parameterName = Objects.requireNonNull(parameterName, "parameterName must not be null");
        this.cachePeriod = Objects.requireNonNull(cachePeriod, "cachePeriod must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (this.parameterName.isBlank()) {
            throw new IllegalArgumentException(
                    "parameterName must not be blank: a blank name cannot be read, and because this"
                            + " gate fails closed that would refuse every mutating request");
        }
        if (this.cachePeriod.isZero() || this.cachePeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "cachePeriod must be positive, but was " + this.cachePeriod);
        }
    }

    /**
     * Refuses the calling operation unless the online-write window is currently open.
     *
     * <p>Assumptions: this is the single entry point through which the decision is enforced, and
     * {@link OnlineWriteGateInterceptor} is its only caller today -- it applies the gate to every
     * mutating request of every write-gated service. It is a public method rather than logic private
     * to that interceptor because the enforcement point and the decision are separable concerns: the
     * decision, including its fail-closed behaviour, belongs to this class whatever applies it.</p>
     *
     * <p>Assumptions: the queue consumers deliberately do NOT call this, and the reason is a parity
     * one rather than an omission. The reference bracket closed five VSAM files
     * ({@code app/jcl/CLOSEFIL.jcl} lines 26 to 30) and none of them holds the data a consumer in this
     * migration writes: the account and date inquiries write nothing at all, and the authorization
     * consumer writes the authorization schema, which the posting chain does not touch. Refusing a
     * queued message would also be worse than useless -- an unconsumed message is redelivered and, at
     * the fifth receive, lands in the dead-letter queue, so a closed window would convert legitimate
     * traffic into a backlog an operator has to redrive by hand. The package charter records this
     * decision in full.</p>
     *
     * @throws OnlineWritesDisabledException if the flag says writes are closed, or if the flag cannot
     *     be read at all — the two are deliberately the same outcome, because an unknown window is
     *     not evidence of an open one
     */
    public void requireWritesEnabled() {
        if (!writesEnabled()) {
            throw new OnlineWritesDisabledException(
                    "Online writes are currently closed for the batch window. The request was not"
                            + " applied; retry once the window reopens.");
        }
    }

    /**
     * Reports whether the online-write window is currently open.
     *
     * <p>Assumptions: a read failure returns {@code false} rather than propagating, so that every
     * caller of this method inherits the fail-closed behaviour without having to remember it. The
     * failure is logged at warning level with the parameter name, because an operator diagnosing
     * refused writes needs to distinguish a deliberate quiesce from a broken read, and those two
     * look identical to a client.</p>
     *
     * @return {@code true} when the flag was read and carries the enabled value; {@code false} when
     *     it carries anything else, and {@code false} when it could not be read
     */
    public boolean writesEnabled() {
        CachedDecision current = this.cached.get();
        Instant now = this.clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.enabled();
        }

        boolean enabled;
        try {
            GetParameterResponse response = this.ssm.getParameter(
                    GetParameterRequest.builder().name(this.parameterName).build());
            String value = response.parameter() == null ? null : response.parameter().value();
            enabled = value != null && WRITES_ENABLED_VALUE.equalsIgnoreCase(value.trim());
            if (!enabled) {
                LOG.info(
                        "Online writes are closed: parameter {} does not carry the enabled value",
                        this.parameterName);
            }
        } catch (RuntimeException failure) {
            // WHY : Assumptions: RuntimeException rather than the SDK's own exception hierarchy,
            //       because the failures that matter here are not all SdkException -- a missing
            //       parameter, an access denial, a timeout and a credential-resolution failure
            //       arrive as different types, and every one of them means the same thing to this
            //       gate: the window's state is unknown. Catching the common supertype keeps the
            //       fail-closed decision in one branch instead of one branch per SDK failure mode.
            //       Trade-offs: this also catches a programming error such as a null field, which
            //       would then present as a refused write rather than as a stack trace. Accepted
            //       because the log line below carries the throwable, so the defect is still
            //       visible, and because refusing is the safe direction to be wrong in.
            LOG.warn(
                    "Online-write flag {} could not be read; refusing mutating work because this"
                            + " gate fails closed",
                    this.parameterName,
                    failure);
            enabled = false;
        }

        this.cached.set(new CachedDecision(enabled, now.plus(this.cachePeriod)));
        return enabled;
    }

    /**
     * One cached decision and the instant at which it stops being usable.
     *
     * @param enabled whether writes were permitted when the flag was last read
     * @param expiresAt the instant from which the decision must be read again
     */
    private record CachedDecision(boolean enabled, Instant expiresAt) {}
}
