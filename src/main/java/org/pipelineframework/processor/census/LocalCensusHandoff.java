package org.pipelineframework.processor.census;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Private, bounded IPC between a forked compiler and its explicitly configured local build host. */
record LocalCensusHandoff(Path anchor, Set<Path> packagedRoots, BuildCensusSummary summary, CensusArtifactVersions versions) {
    static final int MAX_FRAME = 4096;
    static final Path SOCKET_ROOT = Path.of("/tmp");
    static final String DIRECTORY_PREFIX = "tpf-census-";
    static final String SOCKET_NAME = "host.sock";
    private static final int VERSION = 2;
    private static final int MAX_ROOTS = 32;
    private static final List<String> SIZES = List.of("pipelines", "steps", "types", "blocks");
    private static final List<String> FEATURES = List.of("nested_invocation", "one_to_many", "many_to_one", "query", "command", "await");

    /** Copies at most one frame into the local kernel buffer. A busy or absent host drops the candidate. */
    void offer(Path socket) throws IOException {
        Path parent = socket.getParent();
        if (parent == null || !SOCKET_ROOT.equals(parent.getParent())
                || !parent.getFileName().toString().startsWith(DIRECTORY_PREFIX)
                || !socket.getFileName().toString().equals(SOCKET_NAME)) return;
        var attributes = Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.isWritable(parent)) return;
        String path = anchor.toAbsolutePath().normalize().toString();
        if (path.length() > MAX_FRAME || packagedRoots.size() > MAX_ROOTS) return;
        var payload = new ByteArrayOutputStream();
        try (var frame = new DataOutputStream(payload)) {
            frame.writeByte(VERSION);
            frame.writeUTF(path);
            frame.writeByte(packagedRoots.size());
            for (Path root : packagedRoots) {
                String absolute = root.toAbsolutePath().normalize().toString();
                if (absolute.length() > MAX_FRAME) return;
                frame.writeUTF(absolute);
                if (payload.size() > MAX_FRAME) return;
            }
            frame.writeUTF(versions.compiler());
            frame.writeUTF(versions.contracts());
            frame.writeShort(versions.javaMajor());
            for (String key : SIZES) frame.writeByte(summary.sizes().get(key).ordinal());
            for (String key : FEATURES) frame.writeByte(summary.featureCounts().get(key).ordinal());
            frame.writeByte(summary.transport().ordinal());
            frame.writeByte(summary.platform().ordinal());
            frame.writeByte(summary.runtimeLayout().ordinal());
        }
        if (payload.size() + Integer.BYTES > MAX_FRAME) return;
        var buffer = ByteBuffer.allocate(Integer.BYTES + payload.size());
        buffer.putInt(payload.size()).put(payload.toByteArray()).flip();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (channel.connect(UnixDomainSocketAddress.of(socket))) channel.write(buffer);
        }
    }

    /** Reads a complete local frame and rejects malformed values. */
    static LocalCensusHandoff read(byte[] buffer, int size) throws IOException {
        try (var frame = new DataInputStream(new ByteArrayInputStream(buffer, 0, size))) {
            if (frame.readInt() != size - Integer.BYTES || frame.readUnsignedByte() != VERSION)
                throw new IOException("Invalid local census frame");
            Path anchor = Path.of(frame.readUTF());
            int rootCount = frame.readUnsignedByte();
            if (rootCount > MAX_ROOTS) throw new IOException("Too many census output roots");
            var roots = new HashSet<Path>();
            for (int i = 0; i < rootCount; i++) {
                Path root = Path.of(frame.readUTF());
                if (!root.isAbsolute()) throw new IOException("Invalid census output root");
                roots.add(root);
            }
            String compiler = CensusEvent.normalizeVersion(frame.readUTF());
            String contracts = CensusEvent.normalizeVersion(frame.readUTF());
            int javaMajor = frame.readUnsignedShort();
            var summary = new BuildCensusSummary(readBands(frame, SIZES), readBands(frame, FEATURES),
                    readEnum(frame, BuildCensusSummary.Transport.values()),
                    readEnum(frame, BuildCensusSummary.Platform.values()),
                    readEnum(frame, BuildCensusSummary.Layout.values()));
            if (frame.available() != 0 || !anchor.isAbsolute() || javaMajor < 1 || javaMajor > 999)
                throw new IOException("Invalid local census frame");
            return new LocalCensusHandoff(anchor, Set.copyOf(roots), summary, new CensusArtifactVersions(compiler, contracts, javaMajor));
        }
    }

    /** Reads bands in the fixed wire order. */
    private static Map<String, CountBand> readBands(DataInputStream frame, List<String> keys) throws IOException {
        var bands = new LinkedHashMap<String, CountBand>();
        for (String key : keys) bands.put(key, readEnum(frame, CountBand.values()));
        return bands;
    }

    /** Rejects enum ordinals outside the protocol vocabulary. */
    private static <E extends Enum<E>> E readEnum(DataInputStream frame, E[] values) throws IOException {
        int index = frame.readUnsignedByte();
        if (index >= values.length) throw new IOException("Invalid local census enum");
        return values[index];
    }
}
