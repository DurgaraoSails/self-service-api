package com.sails.ai.selfserviceapi.deploypipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the platform guarantees a deployed POC about the container it runs in — the runtime half of
 * the manifest contract, kept separate from {@link PipelineProperties} because these describe the
 * deployed service rather than how the deploy is carried out. A POC author may rely on every value
 * here; none of them can be set from a poc.yaml (see {@code manifest.reserved-env-names}).
 */
@ConfigurationProperties(prefix = "poc-runtime")
public record PocRuntimeProperties(

        /**
         * The port the ingress container binds, and the value Cloud Run sets as its {@code PORT}.
         * Platform-owned: a multi-container service gets no default ingress port from Cloud Run at
         * all, and only one container may expose one, so this cannot be left to a manifest to
         * decide per-container. A manifest that names its own ingress port still wins — see
         * {@code CloudRunDeployCommandBuilder.ingressPort} — this is the value used when it doesn't.
         */
        Integer ingressPort,

        /**
         * This self-service-api deployment's own externally-reachable URL, injected as
         * {@code PLATFORM_API_URL} into every container so a POC's backend can fetch
         * {@code /.well-known/jwks.json} and verify the launch token it was handed.
         *
         * <p>Must be reachable from Cloud Run. The localhost default is a development placeholder
         * that cannot work for a deployed POC — worse, inside a multi-container instance it
         * resolves to a sibling container rather than failing outright, so the POC gets some other
         * container's response where it expected a JWKS document. The deploy warns rather than
         * refuses, since a POC that never verifies a token is unaffected.
         */
        String platformApiUrl,

        /**
         * The single origin allowed to frame a POC, injected as {@code PORTAL_ORIGIN}. It is both
         * the {@code postMessage} targetOrigin a POC replies to and the value it puts in its own
         * {@code Content-Security-Policy: frame-ancestors}, so the JavaScript origin check and the
         * browser-enforced embedding restriction cannot disagree. Platform-supplied precisely so a
         * POC never derives it from {@code document.referrer} or {@code location.ancestorOrigins},
         * both of which an embedder controls. Defaults to the portal URL this API already
         * configures for email links.
         */
        String portalOrigin
) {

    private static final int DEFAULT_INGRESS_PORT = 8080;
    private static final String DEFAULT_PLATFORM_API_URL = "http://localhost:8080";

    public Integer ingressPort() {
        return ingressPort == null ? DEFAULT_INGRESS_PORT : ingressPort;
    }

    public String platformApiUrl() {
        return platformApiUrl == null || platformApiUrl.isBlank() ? DEFAULT_PLATFORM_API_URL : platformApiUrl;
    }

    /** Blank rather than a guessed default: a wrong origin here silently breaks the portal handshake. */
    public String portalOrigin() {
        return portalOrigin == null ? "" : portalOrigin;
    }

    public boolean hasPortalOrigin() {
        return !portalOrigin().isBlank();
    }

    /** A loopback address means every deployed container resolves PLATFORM_API_URL to itself. */
    public boolean platformApiUrlIsUnreachableFromCloudRun() {
        String url = platformApiUrl();
        return url.contains("localhost") || url.contains("127.0.0.1");
    }
}
