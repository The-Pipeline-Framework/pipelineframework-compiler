package org.pipelineframework.processor.census;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/** Experimental POSIX build launcher endpoint. It stays alive across forked javac exits. */
public final class LocalCensusBuildHost implements AutoCloseable {
    private static final int BACKLOG = 64;
    private final Path directory;
    private final Path socket;
    private final Path stateDirectory;
    private final ServerSocketChannel listener;
    private volatile boolean finishing;
    private volatile Selector waiting;
    private Thread worker;

    /** Opens a local listener in an owner-only directory with a short socket path. */
    public LocalCensusBuildHost(Path stateDirectory) throws IOException {
        this.stateDirectory = stateDirectory.toAbsolutePath().normalize();
        directory = Files.createTempDirectory(LocalCensusHandoff.SOCKET_ROOT, LocalCensusHandoff.DIRECTORY_PREFIX,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        socket = directory.resolve(LocalCensusHandoff.SOCKET_NAME);
        try {
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException | RuntimeException unavailable) {
            Files.deleteIfExists(directory);
            throw unavailable;
        }
        try {
            listener.configureBlocking(false);
            listener.bind(UnixDomainSocketAddress.of(socket), BACKLOG);
        } catch (IOException | RuntimeException unavailable) {
            close();
            throw unavailable;
        }
    }

    /** Selects the local handoff for a child process without overriding its opt-out. */
    public void configure(ProcessBuilder build) {
        var environment = build.environment();
        environment.put("TPF_CENSUS_MODE", "outbox");
        environment.put("TPF_CENSUS_HOST_SOCKET", socket.toString());
        environment.put("TPF_CENSUS_STATE_DIR", stateDirectory.toString());
    }

    /** Options a build integration can pass to javac without changing the Maven process environment. */
    public List<String> compilerArguments() {
        return List.of("-Atpf.census.mode=outbox", "-Atpf.census.hostSocket=" + socket);
    }

    /** Stages frames during compilation so Maven need not do the durable writes at exit. */
    void startCollecting(Runnable startup, IntConsumer settled) {
        worker = Thread.ofPlatform().daemon().name("tpf-census-host").start(() -> {
            try (var selector = Selector.open()) {
                waiting = selector;
                listener.register(selector, SelectionKey.OP_ACCEPT);
                startup.run();
                int staged = 0;
                while (!finishing) {
                    selector.select();
                    staged += collect();
                    selector.selectedKeys().clear();
                }
                staged += collect();
                settled.accept(staged);
            } catch (IOException unavailable) {
                // The optional receiver cannot fail the build.
            } finally {
                waiting = null;
            }
        });
    }

    /** Waits only for final local staging, with a strict time budget. */
    boolean finishCollecting() throws InterruptedException {
        finishing = true;
        var selector = waiting;
        if (selector != null) selector.wakeup();
        return worker.join(Duration.ofMillis(125));
    }

    /**
     * Takes ownership of complete handoffs after the child exits. This only writes
     * local state. The host can schedule collector delivery separately.
     *
     * @return the number of newly staged observations
     */
    public int collect() throws IOException {
        int staged = 0;
        for (int i = 0; i < BACKLOG; i++) {
            var channel = listener.accept();
            if (channel == null) break;
            try (channel) {
                channel.configureBlocking(false);
                var frame = ByteBuffer.allocate(LocalCensusHandoff.MAX_FRAME);
                long deadline = System.nanoTime() + 20_000_000;
                while (frame.hasRemaining()) {
                    int read = channel.read(frame);
                    if (read < 0 || frame.position() >= Integer.BYTES
                            && frame.getInt(0) == frame.position() - Integer.BYTES) break;
                    if (System.nanoTime() >= deadline) break;
                    if (read == 0) LockSupport.parkNanos(250_000);
                }
                if (stage(LocalCensusHandoff.read(frame.array(), frame.position()))) staged++;
            } catch (Exception unavailable) {
                // Drop malformed frames or unavailable local state, then continue with the next connection.
            }
        }
        return staged;
    }

    /** Delivers persisted candidates using a transport explicitly supplied by the host. */
    public int drain(CensusTransport transport) throws Exception {
        return BuildCensusOutbox.drain(configuration(), transport);
    }

    private boolean stage(LocalCensusHandoff candidate) throws Exception {
        var host = new BuildCensusHostContext(candidate.anchor(), configuration());
        var state = host.outboxDirectory();
        if (state.isEmpty() || !Files.isDirectory(state.orElseThrow().getParent())) return false;
        return new CensusStateStore(state.orElseThrow())
                .stageFromHost(host, candidate.summary(), Clock.systemUTC(), candidate.javaMajor());
    }

    private CensusConfiguration configuration() {
        return new CensusConfiguration(CensusConfiguration.Mode.OUTBOX, stateDirectory, Optional.empty());
    }

    /** Releases the local listener and removes its socket path. */
    @Override public void close() throws IOException {
        listener.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(directory);
    }
}
