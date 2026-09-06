package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when a repo's poc.yaml is not well-formed YAML, or not shaped like a manifest at all. */
public class ManifestParseException extends ApiException {

    public ManifestParseException(String reason) {
        super(HttpStatus.BAD_REQUEST, "MANIFEST_PARSE_ERROR", "Could not parse poc.yaml: " + reason);
    }
}
