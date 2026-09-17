package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

/** A cloudbuild.yaml file that isn't valid YAML, or isn't shaped like a Cloud Build config. Always caught, never thrown to a caller outside this package. */
public class CloudBuildParseException extends RuntimeException {

    public CloudBuildParseException(String message) {
        super(message);
    }

    public CloudBuildParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
