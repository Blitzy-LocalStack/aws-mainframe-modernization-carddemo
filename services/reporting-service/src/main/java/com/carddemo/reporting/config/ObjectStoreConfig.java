package com.carddemo.reporting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Supplies the object-store client the artifact writers publish through.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: this bounded context declares an output bucket and two key prefixes in its configuration and
 * had no client bean to reach them with, so nothing in the module could publish an artifact. The
 * orchestrated {@code GenerateStatements} and {@code GenerateReports} states run this image expecting two
 * stored objects and a report file respectively, and the absence of this bean is one of the two reasons
 * neither could produce one.</p>
 *
 * <p>Assumptions: NO region, credential provider or endpoint is set here. The library's default chains
 * resolve all three from the task's environment -- the region from the container's own region variable and
 * the credentials from the task role's metadata endpoint -- which is what keeps a deployment's identity in
 * the deployment rather than in source. Naming a region here would additionally make the image
 * region-specific, so the same artifact could not be promoted between environments.</p>
 *
 * <p>Alternatives Considered: setting a per-call timeout, as this module's orchestration client does. Not
 * done, and the difference is the shape of the call rather than an inconsistency. An orchestration call is
 * a small control-plane request whose latency is bounded by the service; an artifact upload part is a five
 * mebibyte transfer whose duration is a function of the object's size and the network, so a fixed ceiling
 * would abort a legitimate upload on a slow link and the value to choose is not derivable from anything
 * this repository states. The library's own transfer defaults are left exactly as they are, and the
 * bounding that matters -- the part size -- lives on the writer.</p>
 *
 * <p>Assumptions: the client is a singleton. It is thread-safe by contract, holds a connection pool, and
 * costs credential resolution and endpoint discovery to build, so one per context is correct and one per
 * artifact would pay that cost per file.</p>
 *
 * <p>Assumptions: {@code proxyBeanMethods = false} because no bean method here calls another, so the CGLIB
 * subclass a proxying configuration creates would add start-up cost and no behaviour.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ObjectStoreConfig {

    /**
     * Supplies the object-store client.
     *
     * @return the client, never {@code null}
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder().build();
    }
}
