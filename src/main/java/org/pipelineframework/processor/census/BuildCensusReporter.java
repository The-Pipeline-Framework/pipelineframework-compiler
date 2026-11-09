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
    /** Creates the shared daemon with the standard clock and lazy transport. */
    public BuildCensusReporter() { this(HttpsCensusTransport::new, Clock.systemUTC()); }
    /** Creates a worker that starts only on submission and expires when idle. */
    public BuildCensusReporter(Supplier<CensusTransport> transport, Clock clock) {
        this.transport = transport; this.clock = clock;
        worker = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "tpf-build-census"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }
    /** Coalesces pending observations for the same anchor and settings, dropping work when the queue is full. */
    public void submit(BuildCensusHostContext host, BuildCensusSummary summary) {
        if (host.configuration().mode() == CensusConfiguration.Mode.OFF) return;
        var key = new Key(host.originalConfiguration().toAbsolutePath().normalize(), host.configuration());
        if (pending.put(key, new Candidate(host, summary)) != null) return;
        try { worker.execute(() -> consume(key)); }
        catch (RejectedExecutionException full) { pending.remove(key); }
    }
    /** Checks preview paths and handles state or transport failures off the compiler thread. */
    private void consume(Key key) {
        Candidate candidate = pending.remove(key);
        if (candidate == null) return;
        var state = new CensusStateStore(candidate.host.configuration().stateDirectory());
        try {
            if (candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
                var destination = candidate.host.previewDestination();
                if (destination.isEmpty()) {
                    System.err.println("TPF build census: preview skipped because the destination is not permitted.");
                    return;
                }
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
            } else if (candidate.host.configuration().mode() == CensusConfiguration.Mode.OUTBOX) {
                var directory = candidate.host.outboxDirectory();
                if (directory.isPresent()) {
                    new CensusStateStore(directory.orElseThrow()).observe(candidate.host, candidate.summary, clock, ignored -> false);
                }
            } else {
                state.observe(candidate.host, candidate.summary, clock, json -> transport.get().send(json));
            }
        } catch (Exception unavailable) { /* Optional census cannot alter build results. */ }
    }
    /** Describes a suppressed preview without exposing private context. */
    private String unavailablePreview(String reason) {
        return new com.google.gson.Gson().toJson(java.util.Map.of("event_available", false, "reason", reason));
    }
    /** Discards remaining work without waiting for network delivery. */
    @Override public void close() { worker.shutdownNow(); pending.clear(); }
}
