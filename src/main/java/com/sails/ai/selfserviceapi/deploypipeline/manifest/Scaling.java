package com.sails.ai.selfserviceapi.deploypipeline.manifest;

public record Scaling(int min, int max) {

    public static Scaling defaults() {
        return new Scaling(0, 3);
    }
}
