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

/** Local state transaction. Lock acquisition never waits; call only on the census worker or explicit inspector. */
public final class CensusStateStore {
    private final Path directory;
    private final Gson gson = new Gson();
    public record Application(UUID identity, String attemptedDate, String acknowledgedDigest, String acknowledgedWeek) { }
    public record State(UUID installation, String salt, boolean noticeShown, Map<String, Application> applications) {
        public State { applications = Map.copyOf(applications); }
    }
    public record Outcome(String reason, Optional<String> preview, boolean firstUse) { }
    public CensusStateStore(Path directory) { this.directory = directory; }

    public Outcome observe(BuildCensusHostContext host, BuildCensusSummary summary, Clock clock, CensusTransport transport) throws Exception {
        if (host.configuration().mode() == CensusConfiguration.Mode.OFF) return new Outcome("disabled", Optional.empty(), false);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new java.io.IOException("Unsafe census state");
        secure(directory, true);
        Path lockPath = directory.resolve("state.lock");
        if (Files.isSymbolicLink(lockPath)) throw new java.io.IOException("Unsafe census lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            secure(lockPath, false);
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) return new Outcome("state_busy", Optional.empty(), false);
                State state = read();
                String key = digestPath(state.salt(), host.originalConfiguration().toRealPath().toString());
                if (!state.applications().containsKey(key) && state.applications().size() >= 1024)
                    return new Outcome("state_capacity", Optional.empty(), false);
                Application app = state.applications().getOrDefault(key, new Application(UUID.randomUUID(), "", "", ""));
                LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                String week = today.get(WeekFields.ISO.weekBasedYear()) + "-" + today.get(WeekFields.ISO.weekOfWeekBasedYear());
                CensusEvent event = new CensusEvent(state.installation(), app.identity(), today,
                        CensusEvent.packagedVersion(CensusEvent.class, "/META-INF/tpf/compiler-version.properties"),
                        CensusEvent.packagedVersion(org.pipelineframework.annotation.PipelineStep.class, "/META-INF/tpf/contracts-version.properties"),
                        Runtime.version().feature(), summary);
                String reportDigest = digest(event.reportValues());
                String reason = app.attemptedDate().equals(today.toString()) ? "already_attempted_today"
                        : app.acknowledgedDigest().equals(reportDigest) && app.acknowledgedWeek().equals(week) ? "unchanged_this_week" : "eligible";
                if (host.configuration().mode() == CensusConfiguration.Mode.PREVIEW) {
                    Map<String, Object> preview = new TreeMap<>(); preview.put("event", event.values()); preview.put("reason", reason);
                    preview.put("shape_digest_local_only", digest(gson.toJson(summary.projection())));
                    write(new State(state.installation(), state.salt(), state.noticeShown(), with(state.applications(), key, app)));
                    return new Outcome(reason, Optional.of(gson.toJson(preview)), false);
                }
                if (!reason.equals("eligible")) return new Outcome(reason, Optional.empty(), false);
                Application attempted = new Application(app.identity(), today.toString(), app.acknowledgedDigest(), app.acknowledgedWeek());
                boolean firstUse = !state.noticeShown();
                state = new State(state.installation(), state.salt(), true, with(state.applications(), key, attempted));
                write(state); // durable attempt marker precedes delivery, even if delivery throws.
                if (firstUse) System.err.println("TPF build census: minimal build statistics enabled. Disable with TPF_TELEMETRY_DISABLED=true. Inspect: https://pipelineframework.org/develop/pipeline-compilation/generated-artifacts#build-census");
                if (transport.send(event.json())) {
                    write(new State(state.installation(), state.salt(), true,
                            with(state.applications(), key, new Application(app.identity(), today.toString(), reportDigest, week))));
                }
                return new Outcome("attempted", Optional.empty(), firstUse);
            } catch (OverlappingFileLockException busy) { return new Outcome("state_busy", Optional.empty(), false); }
        }
    }
    private State read() throws Exception {
        Path file = directory.resolve("state.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] salt = new byte[32]; new SecureRandom().nextBytes(salt);
            return new State(UUID.randomUUID(), HexFormat.of().formatHex(salt), false, Map.of());
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
        secure(file, false);
        return state;
    }
    private Map<String, Application> with(Map<String, Application> source, String key, Application value) {
        Map<String, Application> next = new TreeMap<>(source); next.put(key, value); return next;
    }
    private void write(State state) throws Exception {
        writePrivate(directory.resolve("state.json"), gson.toJson(state));
    }
    void writePrivate(Path output, String contents) throws Exception {
        if (Files.isSymbolicLink(output)) throw new java.io.IOException("Unsafe census output");
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), "census-", ".tmp");
        try {
            secure(temporary, false);
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void secure(Path path, boolean folder) throws Exception {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(folder ? "rwx------" : "rw-------"));
        else if (Files.getFileAttributeView(path, AclFileAttributeView.class) != null) {
            var owner = Files.getOwner(path);
            Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else throw new java.io.IOException("Protected state permissions unavailable");
    }
    private String digestPath(String salt, String path) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(HexFormat.of().parseHex(salt), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
    }
    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
