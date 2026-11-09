package org.pipelineframework.processor.census;

import java.nio.file.Files;
import java.time.Clock;
import java.util.Objects;

/** Experimental local handoff. javac only stages; a build host owns draining and its transport. */
public final class BuildCensusOutbox {
    private BuildCensusOutbox() { }

    /** Offers a candidate to the host, or stages it on local disk in the standalone experiment. */
    public static void stage(BuildCensusObservation observation) {
        var host = observation.host();
        if (host.configuration().mode() != CensusConfiguration.Mode.OUTBOX) return;
        try {
            var socket = host.configuration().hostSocket();
            if (socket.isPresent()) {
                new LocalCensusHandoff(host.originalConfiguration(), observation.summary(), Runtime.version().feature())
                        .offer(socket.orElseThrow());
                return;
            }
            var directory = host.outboxDirectory();
            if (directory.isEmpty() || !Files.isDirectory(directory.orElseThrow().getParent())) return;
            new CensusStateStore(directory.orElseThrow()).observe(host, observation.summary(), Clock.systemUTC(), ignored -> false);
        } catch (Exception unavailable) {
            // Local storage failure cannot change the build result.
        }
    }

    /**
     * Drains one bounded batch using a transport explicitly supplied by the experimental host.
     * Call from a host-owned background task after javac exits. There is no implicit public transport.
     *
     * @return the number of acknowledgements applied to local state
     */
    public static int drain(CensusConfiguration configuration, CensusTransport transport) throws Exception {
        Objects.requireNonNull(transport);
        if (configuration.mode() != CensusConfiguration.Mode.OUTBOX) return 0;
        return new CensusStateStore(configuration.stateDirectory()).drain(Clock.systemUTC(), transport);
    }
}
