package org.pipelineframework.processor.census;

/** Build-only transport; acknowledgement means accepted or already durably recorded. */
@FunctionalInterface
public interface CensusTransport {
    boolean send(String json) throws Exception;
}
