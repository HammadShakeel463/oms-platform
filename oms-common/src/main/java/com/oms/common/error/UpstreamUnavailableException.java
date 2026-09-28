package com.oms.common.error;

/** A synchronous dependency (REST call, Redis, Kafka producer) could not be reached. */
public class UpstreamUnavailableException extends OmsException {

    private final String dependency;

    public UpstreamUnavailableException(String dependency, String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_UNAVAILABLE, message, cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
