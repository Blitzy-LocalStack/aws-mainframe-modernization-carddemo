package com.carddemo.common.web;

import java.time.Clock;
import java.util.Objects;
import org.apache.catalina.Container;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;

/**
 * Installs {@link RejectedRequestErrorReportValve} as the servlet container's only error reporter, so
 * that a refusal issued before any application code runs is answered in this fleet's JSON envelope.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The valve it installs cannot install itself, and it cannot be published as an ordinary bean
 * either: the slot it has to occupy belongs to the embedded container's host, which is assembled by
 * the framework's web server factory rather than by the application context. A factory customizer is
 * the published way to reach that assembly, and reaching the host in particular has to be done from a
 * context customizer, because the host is only addressable as the parent of the web application the
 * factory is preparing.</p>
 *
 * <h2>Assumptions: why the slot has to be taken rather than shared</h2>
 *
 * <p>Assumptions: the host arrives already holding a stock error reporter. The framework adds one
 * whenever a deployment asks for stack traces to be withheld, which this fleet's shared defaults do,
 * and a host additionally guarantees itself one on start-up by instantiating whatever class it has
 * been told its reporter should be. Both of those produce the page this valve exists to replace, so
 * installation is three steps and not one: every stock reporter on the host is removed, one instance
 * of the replacement is added, and the host is told that the replacement is the class its reporter
 * should be.</p>
 *
 * <p>Alternatives Considered: adding the replacement beside the stock reporter and relying on
 * position. A pipeline invokes its valves outward on the way back, so whichever reporter sits closest
 * to the application reports first and claims the response, and the replacement does sit closest
 * today because this customizer is applied last. Rejected because that is an ordering coincidence
 * rather than a property: a customizer given a different precedence, or a framework release that
 * moved its own customizer, would silently put the stock reporter back in front and return the fleet
 * to serving a page. Removing the competing reporter states the outcome instead of inferring it.</p>
 *
 * <p>Alternatives Considered: removing the stock reporter WITHOUT telling the host which class its
 * reporter should be. Rejected, and it would have been worse than doing nothing: a host that cannot
 * find a reporter of its configured class builds a fresh one on start-up, and a freshly built stock
 * reporter emits both the diagnostic report and the server identification that this fleet
 * suppresses. Naming the replacement to the host is what stops that instance from ever being
 * built.</p>
 *
 * <p>Assumptions: the replacement declares a no-argument constructor for the same reason. That is
 * the constructor a host uses when it builds its configured reporter class itself, so the class named
 * to the host has to be constructible that way even though this customizer never builds it that
 * way.</p>
 *
 * <h2>Trade-offs: applied last, deliberately</h2>
 *
 * <p>Trade-offs: this customizer declares the lowest precedence, so it is applied after every
 * framework customizer and after any a service adds. The cost is that a service wanting to influence
 * the host's error reporting cannot do so by ordering a customizer after this one. The gain is that
 * the removal step sees the final set of reporters rather than a partial one, which is the whole
 * reason the removal is deterministic. A service that genuinely needs different behaviour replaces
 * the bean instead -- the kernel publishes this customizer only when no bean of this type is already
 * declared.</p>
 *
 * <h2>Assumptions: what happens on a runtime that is not this one</h2>
 *
 * <p>Assumptions: nothing here runs outside an embedded Tomcat servlet runtime. The bean is published
 * behind conditions on both the servlet application type and the presence of the container's own
 * classes, and the customizer is typed to the Tomcat factory so the framework applies it to no other
 * factory. A service with no web server -- the batch context -- never resolves any of these
 * types.</p>
 *
 * <p>Assumptions: a host that is not the standard implementation still receives the replacement and
 * has its stock reporters removed; only the class-naming step is skipped, because that setting exists
 * on the standard implementation alone. The self-guarantee that step defends against is also the
 * standard implementation's, so skipping it where it does not exist withdraws nothing.</p>
 */
public class RejectedRequestErrorReportValveCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    /**
     * The record this customizer writes its installation outcome to.
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(RejectedRequestErrorReportValveCustomizer.class);

    /**
     * The reading passed to every valve this customizer installs.
     */
    private final Clock clock;

    /**
     * Builds a customizer that installs valves reading the supplied clock.
     *
     * @param clock the reading the installed valve's envelope timestamps are taken from, resolved
     *     from the application context so one request is never stamped from two clocks; must not be
     *     {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public RejectedRequestErrorReportValveCustomizer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Arranges for the replacement reporter to be installed on the host of every web application the
     * supplied factory prepares.
     *
     * <p>Assumptions: the work is deferred into a context customizer rather than performed here. The
     * host does not exist yet when a factory is customized, and it is only reachable as the parent of
     * a prepared web application, so the earliest point at which the pipeline can be edited is when
     * that application is being configured.</p>
     *
     * @param factory the container factory to arrange installation on; must not be {@code null}
     * @throws NullPointerException if {@code factory} is {@code null}
     */
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        Objects.requireNonNull(factory, "factory must not be null");
        factory.addContextCustomizers(context -> install(context.getParent()));
    }

    /**
     * Reports this customizer's precedence, the lowest there is.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}, so that this customizer is applied after every other
     *     one and its removal step therefore sees the final set of reporters
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Replaces whatever error reporting a host holds with the JSON-answering reporter.
     *
     * <p>Assumptions: the outcome is recorded at informational level rather than left silent. This is
     * the one step that decides whether a container-level refusal is answered in the fleet's envelope
     * or as a page, and an operator reading a service's start-up has no other way to tell which.</p>
     *
     * @param host the container the web application belongs to, which is the container whose pipeline
     *     answers a request that was never mapped to an application; may be {@code null} on a
     *     container assembled without one, in which case nothing is installed
     */
    private void install(Container host) {
        if (host == null) {
            LOG.warn("event=web.container.reporter.uninstalled reason=absent-host");
            return;
        }

        Pipeline pipeline = host.getPipeline();
        if (pipeline == null) {
            LOG.warn("event=web.container.reporter.uninstalled reason=absent-pipeline");
            return;
        }

        // WHY : Assumptions: the array this iterates is a snapshot the pipeline builds on request,
        //       not a live view of it, so removing a valve while walking it is safe. Copying the
        //       array first was considered and is unnecessary for that reason.
        boolean alreadyInstalled = false;
        int removed = 0;
        for (Valve valve : pipeline.getValves()) {
            if (valve instanceof RejectedRequestErrorReportValve) {
                alreadyInstalled = true;
            } else if (valve instanceof ErrorReportValve) {
                // WHY : Assumptions: only a reporter is removed, and only one that is not the
                //       replacement. Every other valve on this pipeline -- access logging, remote
                //       address handling, whatever a service added -- is left exactly where it is,
                //       because none of them answers a refusal and removing one would change
                //       behaviour this class has no business changing.
                pipeline.removeValve(valve);
                removed++;
            }
        }

        if (!alreadyInstalled) {
            pipeline.addValve(new RejectedRequestErrorReportValve(this.clock));
        }

        if (host instanceof StandardHost standardHost) {
            standardHost.setErrorReportValveClass(
                    RejectedRequestErrorReportValve.class.getName());
        }

        LOG.info("event=web.container.reporter.installed host={} stockReportersRemoved={}",
                host.getName(), removed);
    }
}
