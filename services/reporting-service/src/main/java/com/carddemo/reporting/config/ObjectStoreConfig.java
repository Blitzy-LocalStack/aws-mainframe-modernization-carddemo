package com.carddemo.reporting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Supplies the object-store client the artifact writers publish through and the request edge reads back.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: this bounded context declares an output bucket and two key prefixes in its configuration and
 * had no client bean to reach them with, so nothing in the module could publish an artifact. The
 * orchestrated {@code GenerateStatements} and {@code GenerateReports} states run this image expecting two
 * stored objects and a report file respectively, and the absence of this bean is one of the two reasons
 * neither could produce one.</p>
 *
 * <h2>The permissions this one bean commits the task role to</h2>
 *
 * <p>⚠️ Refactoring Rationale: this class is documented as the client "the artifact writers publish
 * through", which was true when only writers held it and is no longer the whole truth -- a review found
 * the deployed task role granting writes alone while delivered code READS. Three distinct read calls are
 * made through this bean, all of them from {@code service/ArtifactStore}: {@code HeadObject} to establish
 * whether a run has published an artifact, a whole-object {@code GetObject} to stream one to a caller, and
 * a ranged {@code GetObject} to read single index entries. Every one of them is authorized by
 * {@code s3:GetObject}, since the store publishes no separate action for a metadata read, so a role
 * granted writes alone answers the statement and report status operations with an access failure rather
 * than with an absence. The grant this bean therefore needs is {@code s3:GetObject} on the reporting
 * object-key prefixes of the dataset bucket, alongside the {@code s3:PutObject} and
 * {@code s3:AbortMultipartUpload} the writers need on the same prefixes and the {@code s3:ListBucket}
 * bounded by those prefixes that {@code task/GenerationKeys} needs to number a generation.</p>
 *
 * <p>Assumptions: recording the required grant HERE, on the bean, is what keeps it discoverable from the
 * code that depends on it. The policy itself lives in the environment roots under {@code infra/envs}, and
 * a permission absent there fails at run time on whichever request first needs it -- not at build time and
 * not at plan time -- so the only defence is that the two documents name the same set. Alternatives
 * Considered: leaving the enumeration to the infrastructure code alone, which is where it is enforced;
 * rejected because a reader adding a fourth call through this bean has no reason to look there, and the
 * review found exactly that failure in the opposite direction -- a policy comment asserting that no state
 * reads an object while three read paths were already shipping.</p>
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
     * Supplies the object-store client, used for both publication and read-back.
     *
     * @return the client, resolving its region and credentials from the task environment and carrying
     *     both the write and the read capability the class documentation enumerates; never {@code null}
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder().build();
    }
}
