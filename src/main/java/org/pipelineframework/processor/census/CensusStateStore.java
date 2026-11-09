package org.pipelineframework.processor.census;

import com.google.gson.Gson;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Private census state and outbox transactions. Lock acquisition never waits. */
public final class CensusStateStore {
    private static final int MAX_PENDING = 64;
    private static final int DRAIN_LIMIT = 8;
    private static final String NOTICE = "TPF build census: minimal build statistics enabled. Disable with TPF_CENSUS_ENABLED=false. Inspect: https://pipelineframework.org/develop/pipeline-compilation/generated-artifacts#build-census";
    private final Path directory;
    private final Gson gson = new Gson();
    public record Application(UUID identity, String attemptedDate, String acknowledgedDigest, String acknowledgedWeek) { }

    /** Stores only the approved event fields not already present in the enclosing state. */
    record Pending(String day, String compilerVersion, String contractsVersion, int javaMajor, BuildCensusSummary summary) {
        LocalDate date() { return LocalDate.parse(day); }

        CensusEvent event(UUID installation, UUID application) {
            return new CensusEvent(installation, application, date(), compilerVersion, contractsVersion, javaMajor, summary);
        }
    }

    private record Delivery(String key, Pending pending, CensusEvent event) { }
    public record State(UUID installation, String salt, boolean noticeShown, Map<String, Application> applications,
            Map<String, Pending> pending) {
        public State {
            applications = Map.copyOf(applications);
            pending = Map.copyOf(Objects.requireNonNullElse(pending, Map.of()));
        }
    }
    public record Outcome(String reason, Optional<String> preview, boolean firstUse) { }
    public CensusStateStore(Path directory) { this.directory = directory; }

    /** Locks retained state without waiting, then previews locally or persists an eligible attempt before delivery. */
    public Outcome observe(BuildCensusHostContext host, BuildCensusSummary summary, Clock clock, CensusTransport transport) throws Exception {
        if (host.configuration().mode() == CensusConfiguration.Mode.OFF) return new Outcome("disabled", Optional.empty(), false);
        return withState(state -> observeLocked(state, host, summary, clock, Runtime.version().feature(), transport))
                .orElseGet(() -> new Outcome("state_busy", Optional.empty(), false));
    }

    /** Stages a forked compiler's report using its JVM version. */
    boolean stageFromHost(BuildCensusHostContext host, BuildCensusSummary summary, Clock clock, int javaMajor) throws Exception {
        if (host.configuration().mode() != CensusConfiguration.Mode.OUTBOX) return false;
        return withState(state -> observeLocked(state, host, summary, clock, javaMajor, ignored -> false))
                .map(outcome -> outcome.reason().equals("queued")).orElse(false);
    }

    @FunctionalInterface
    private interface StateAction<T> {
        T apply(State state) throws Exception;
    }

    /** Acquires the installation lock without waiting. Callbacks must be kept short for the outbox. */
    private <T> Optional<T> withState(StateAction<T> action) throws Exception {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new java.io.IOException("Unsafe census state");
        secure(directory, true);
        Path lockPath = directory.resolve("state.lock");
        if (Files.isSymbolicLink(lockPath)) throw new java.io.IOException("Unsafe census lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            secure(lockPath, false);
            try (FileLock lock = channel.tryLock()) {
                return lock == null ? Optional.empty() : Optional.of(action.apply(read()));
            } catch (OverlappingFileLockException busy) { return Optional.empty(); }
        }
    }

    private Outcome observeLocked(State state, BuildCensusHostContext host, BuildCensusSummary summary, Clock clock,
            int javaMajor, CensusTransport transport) throws Exception {
        String key = digestPath(state.salt(), host.originalConfiguration().toRealPath().toString());
        if (!state.applications().containsKey(key) && state.applications().size() >= 1024)
            return new Outcome("state_capacity", Optional.empty(), false);
        Application app = state.applications().getOrDefault(key, new Application(UUID.randomUUID(), "", "", ""));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String week = today.get(WeekFields.ISO.weekBasedYear()) + "-" + today.get(WeekFields.ISO.weekOfWeekBasedYear());
        CensusEvent event = new CensusEvent(state.installation(), app.identity(), today,
                CensusEvent.packagedVersion(CensusEvent.class, "/META-INF/tpf/compiler-version.properties"),
                CensusEvent.packagedVersion(org.pipelineframework.annotation.PipelineStep.class, "/META-INF/tpf/contracts-version.properties"),
                javaMajor, summary);
        String reportDigest = digest(event.reportValues());
        String reason = app.attemptedDate().equals(today.toString()) ? "already_attempted_today"
                : app.acknowledgedDigest().equals(reportDigest) && app.acknowledgedWeek().equals(week) ? "unchanged_this_week" : "eligible";
        if (host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
            Map<String, Object> preview = new TreeMap<>(); preview.put("event", event.values()); preview.put("reason", reason);
            preview.put("shape_digest_local_only", digest(gson.toJson(summary.projection())));
            write(new State(state.installation(), state.salt(), state.noticeShown(), with(state.applications(), key, app), state.pending()));
            return new Outcome(reason, Optional.of(gson.toJson(preview)), false);
        }
        if (host.configuration().mode() == CensusConfiguration.Mode.OUTBOX) {
            return stagePending(state, key, app, event, reason);
        }
        if (!reason.equals("eligible")) return new Outcome(reason, Optional.empty(), false);
        Application attempted = new Application(app.identity(), today.toString(), app.acknowledgedDigest(), app.acknowledgedWeek());
        boolean firstUse = !state.noticeShown();
        if (firstUse) System.err.println(NOTICE);
        state = new State(state.installation(), state.salt(), true, with(state.applications(), key, attempted), state.pending());
        write(state);
        // A forced file plus an atomic rename is not enough after a power loss.
        // Force the directory entries before sending; unsupported hosts fail closed.
        for (Path parent = directory.toRealPath(); parent != null; parent = parent.getParent()) {
            try (FileChannel channelToSync = FileChannel.open(parent, StandardOpenOption.READ)) {
                channelToSync.force(true);
            }
        }
        if (transport.send(event.json())) {
            write(new State(state.installation(), state.salt(), true,
                    with(state.applications(), key, new Application(app.identity(), today.toString(), reportDigest, week)), state.pending()));
        }
        return new Outcome("attempted", Optional.empty(), firstUse);
    }

    /** Writes the identity and pending event in one local transaction, with no transport call. */
    private Outcome stagePending(State state, String key, Application app, CensusEvent event, String reason) throws Exception {
        var pending = activePending(state, event.observedAt());
        if (reason.equals("eligible")) {
            var existing = pending.get(key);
            if (existing != null && existing.date().equals(event.observedAt())) reason = "already_queued_today";
            else if (existing == null && pending.size() == MAX_PENDING) reason = "outbox_capacity";
        }
        if (reason.equals("eligible")) {
            pending.put(key, new Pending(event.observedAt().toString(), event.compilerVersion(), event.contractsVersion(),
                    event.javaMajor(), event.summary()));
            boolean firstUse = !state.noticeShown();
            if (firstUse) System.err.println(NOTICE);
            write(new State(state.installation(), state.salt(), true, with(state.applications(), key, app), pending));
            syncOutboxDirectory();
            return new Outcome("queued", Optional.empty(), firstUse);
        }
        if (!pending.equals(state.pending())) {
            write(new State(state.installation(), state.salt(), state.noticeShown(), state.applications(), pending));
        }
        return new Outcome(reason, Optional.empty(), false);
    }

    /** Takes a bounded snapshot under lock and lets the host attempt delivery outside it. */
    int drain(Clock clock, CensusTransport transport) throws Exception {
        if (!Files.isRegularFile(directory.resolve("state.json"), LinkOption.NOFOLLOW_LINKS)) return 0;
        var deliveries = withState(state -> pendingDeliveries(state, LocalDate.now(clock.withZone(ZoneOffset.UTC))));
        int acknowledged = 0;
        for (Delivery delivery : deliveries.orElseGet(List::of)) {
            if (!active(delivery.pending(), LocalDate.now(clock.withZone(ZoneOffset.UTC)))) continue;
            boolean accepted;
            try { accepted = transport.send(delivery.event().json()); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception unavailable) {
                break; // A later host can retry, without a burst of requests during an outage.
            }
            if (!accepted) break;
            if (withState(state -> acknowledge(state, delivery)).orElse(false)) acknowledged++;
        }
        return acknowledged;
    }

    /** Used by the host before starting a sender. Does not touch an unused installation. */
    boolean hasPending(Clock clock) throws Exception {
        if (!Files.isRegularFile(directory.resolve("state.json"), LinkOption.NOFOLLOW_LINKS)) return false;
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return withState(state -> !pendingDeliveries(state, today).isEmpty()).orElse(false);
    }

    /** Removes expired entries and returns a stable batch of still-admissible observations. */
    private List<Delivery> pendingDeliveries(State state, LocalDate today) throws Exception {
        var pending = activePending(state, today);
        if (!pending.equals(state.pending())) {
            write(new State(state.installation(), state.salt(), state.noticeShown(), state.applications(), pending));
        }
        return pending.entrySet().stream().limit(DRAIN_LIMIT)
                .map(entry -> new Delivery(entry.getKey(), entry.getValue(),
                        entry.getValue().event(state.installation(), state.applications().get(entry.getKey()).identity())))
                .toList();
    }

    /** Clears only the exact pending event accepted by the transport. */
    private boolean acknowledge(State state, Delivery delivery) throws Exception {
        if (!state.installation().equals(delivery.event().installationId())
                || !delivery.pending().equals(state.pending().get(delivery.key()))) return false;
        var event = delivery.event();
        var day = event.observedAt();
        String week = day.get(WeekFields.ISO.weekBasedYear()) + "-" + day.get(WeekFields.ISO.weekOfWeekBasedYear());
        var application = new Application(event.applicationInstanceId(), day.toString(), digest(event.reportValues()), week);
        var pending = new TreeMap<>(state.pending());
        pending.remove(delivery.key());
        write(new State(state.installation(), state.salt(), state.noticeShown(),
                with(state.applications(), delivery.key(), application), pending));
        return true;
    }

    private Map<String, Pending> activePending(State state, LocalDate today) {
        var pending = new TreeMap<>(state.pending());
        pending.values().removeIf(value -> !active(value, today));
        return pending;
    }

    private boolean active(Pending pending, LocalDate today) {
        return !pending.date().isBefore(today.minusDays(1)) && !pending.date().isAfter(today);
    }

    private void syncOutboxDirectory() throws Exception {
        // The atomic state file survives JVM exit. Sync its directory entry on supporting hosts.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        for (Path path : List.of(directory, directory.getParent())) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    /** Loads and validates bounded retained state, generating an identity only when no state file exists. */
    private State read() throws Exception {
        Path file = directory.resolve("state.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] salt = new byte[32]; new SecureRandom().nextBytes(salt);
            return new State(UUID.randomUUID(), HexFormat.of().formatHex(salt), false, Map.of(), Map.of());
        }
        if (Files.isSymbolicLink(file) || Files.size(file) > 1024 * 1024) throw new java.io.IOException("Invalid census state");
        State state = gson.fromJson(Files.readString(file), State.class);
        Objects.requireNonNull(state);
        if (!CensusEvent.isRandomIdentity(state.installation())) throw new java.io.IOException("Invalid census installation identity");
        if (state.salt() == null || !state.salt().matches("[a-f0-9]{64}") || state.applications().size() > 1024)
            throw new java.io.IOException("Invalid census state");
        for (var entry : state.applications().entrySet()) {
            Application app = Objects.requireNonNull(entry.getValue());
            if (!CensusEvent.isRandomIdentity(app.identity())) throw new java.io.IOException("Invalid census application identity");
            if (!entry.getKey().matches("[a-f0-9]{64}") || app.attemptedDate() == null || app.acknowledgedDigest() == null || app.acknowledgedWeek() == null)
                throw new java.io.IOException("Invalid census state");
            if (!app.attemptedDate().isEmpty()) LocalDate.parse(app.attemptedDate());
            if (!app.acknowledgedDigest().isEmpty() && !app.acknowledgedDigest().matches("[a-f0-9]{64}"))
                throw new java.io.IOException("Invalid census digest");
        }
        if (state.pending().size() > MAX_PENDING || !state.applications().keySet().containsAll(state.pending().keySet()))
            throw new java.io.IOException("Invalid census outbox");
        for (var entry : state.pending().entrySet()) {
            entry.getValue().event(state.installation(), state.applications().get(entry.getKey()).identity());
        }
        secure(file, false);
        return state;
    }
    private Map<String, Application> with(Map<String, Application> source, String key, Application value) {
        Map<String, Application> next = new TreeMap<>(source); next.put(key, value); return next;
    }
    private void write(State state) throws Exception {
        writePrivate(directory.resolve("state.json"), gson.toJson(state), true);
    }
    /** Publishes a complete private preview without replacing an existing destination. */
    void writePreview(Path output, String contents) throws Exception {
        writePrivate(output, contents, false);
    }
    /** Forces a private temporary file before atomically publishing it as state or a create-only preview. */
    private void writePrivate(Path output, String contents, boolean replace) throws Exception {
        if (Files.isSymbolicLink(output)) throw new java.io.IOException("Unsafe census output");
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), "census-", ".tmp");
        try {
            secure(temporary, false);
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (replace) {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Atomic create-only publication of the completed private file.
                Files.createLink(output, temporary);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Restricts state files to their owner and fails when the filesystem cannot enforce that boundary. */
    private void secure(Path path, boolean folder) throws Exception {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(folder ? "rwx------" : "rw-------"));
        else if (Files.getFileAttributeView(path, AclFileAttributeView.class) != null) {
            var owner = Files.getOwner(path);
            Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else throw new java.io.IOException("Protected state permissions unavailable");
    }
    /** Derives a local lookup key from the configuration path without storing the path itself. */
    private String digestPath(String salt, String path) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(HexFormat.of().parseHex(salt), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
    }
    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
