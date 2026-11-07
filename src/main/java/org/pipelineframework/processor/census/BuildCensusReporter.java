package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded, daemon-only build shell. No shutdown hook, flush, retry, or non-daemon executor. */
public final class BuildCensusReporter implements AutoCloseable {
    private record Candidate(BuildCensusHostContext host, BuildCensusSummary summary) { }
    // Different preview destinations must not replace each other in a shared build host.
    private record Key(Path anchor, CensusConfiguration configuration) { }
    private final ConcurrentMap<Key, Candidate> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker;
    private final Supplier<CensusTransport> transport;
    private final Clock clock;
    public BuildCensusReporter() { this(HttpsCensusTransport::new, Clock.systemUTC()); }
    public BuildCensusReporter(Supplier<CensusTransport> transport, Clock clock) {
        this.transport = transport; this.clock = clock;
        worker = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "tpf-build-census"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }
    public void submit(BuildCensusHostContext host, BuildCensusSummary summary) {
        if (host.configuration().mode() == CensusConfiguration.Mode.OFF) return;
        var key = new Key(host.originalConfiguration().toAbsolutePath().normalize(), host.configuration());
        if (pending.put(key, new Candidate(host, summary)) != null) return;
        try { worker.execute(() -> consume(key)); }
        catch (RejectedExecutionException full) { pending.remove(key); }
    }
    private void consume(Key key) {
        Candidate candidate = pending.remove(key);
        if (candidate == null) return;
        var state = new CensusStateStore(candidate.host.configuration().stateDirectory());
        try {
            if (candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
                var destination = candidate.host.previewDestination();
                if (destination.isEmpty()) return;
                String contents;
                try {
                    var outcome = state.observe(candidate.host, candidate.summary, clock, json -> false);
                    contents = outcome.preview().orElseGet(() -> unavailablePreview(outcome.reason()));
                } catch (Exception unavailable) {
                    contents = unavailablePreview("state_or_preview_unavailable");
                }
                // Recheck the boundary, then publish through the validated canonical path,
                // never through the caller's potentially retargeted directory alias.
                if (destination.equals(candidate.host.previewDestination())) {
                    state.writePreview(destination.orElseThrow(), contents);
                }
            } else {
                state.observe(candidate.host, candidate.summary, clock, json -> transport.get().send(json));
            }
        } catch (Exception unavailable) { /* Optional census cannot alter build results. */ }
    }
    private String unavailablePreview(String reason) {
        return new com.google.gson.Gson().toJson(java.util.Map.of("event_available", false, "reason", reason));
    }
    public void close() { worker.shutdownNow(); pending.clear(); }
}
