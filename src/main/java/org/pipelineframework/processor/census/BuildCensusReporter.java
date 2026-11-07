package org.pipelineframework.processor.census;

import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded, daemon-only build shell. No shutdown hook, flush, retry, or non-daemon executor. */
public final class BuildCensusReporter implements AutoCloseable {
    private record Candidate(BuildCensusHostContext host, BuildCensusSummary summary) { }
    private final ConcurrentMap<Path, Candidate> pending = new ConcurrentHashMap<>();
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
        Path key = host.originalConfiguration().toAbsolutePath().normalize();
        if (pending.computeIfPresent(key, (ignored, old) -> new Candidate(host, summary)) != null) return;
        if (pending.putIfAbsent(key, new Candidate(host, summary)) != null) return;
        try { worker.execute(() -> consume(key)); }
        catch (RejectedExecutionException full) { pending.remove(key); }
    }
    private void consume(Path key) {
        Candidate candidate = pending.remove(key);
        if (candidate == null) return;
        var state = new CensusStateStore(candidate.host.configuration().stateDirectory());
        try {
            if (candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW && !candidate.host.permitsPreviewOutput()) return;
            CensusTransport sender = candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW
                    ? json -> false : transport.get();
            var outcome = state.observe(candidate.host, candidate.summary, clock, sender);
            if (candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
                Path output = candidate.host.configuration().previewFile().orElseThrow();
                state.writePrivate(output, outcome.preview().orElseGet(() -> unavailablePreview(outcome.reason())));
            }
        } catch (Exception unavailable) {
            if (candidate.host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
                try {
                    if (!candidate.host.permitsPreviewOutput()) return;
                    state.writePrivate(candidate.host.configuration().previewFile().orElseThrow(), unavailablePreview("state_or_preview_unavailable"));
                } catch (Exception outputUnavailable) { /* Optional inspection cannot alter build results. */ }
            }
        }
    }
    private String unavailablePreview(String reason) {
        return new com.google.gson.Gson().toJson(java.util.Map.of("event_available", false, "reason", reason));
    }
    public void close() { worker.shutdownNow(); pending.clear(); }
}
