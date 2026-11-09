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

/** Private, bounded IPC between a forked compiler and its explicitly configured local build host. */
record LocalCensusHandoff(Path anchor, BuildCensusSummary summary, int javaMajor) {
    static final int MAX_FRAME = 4096;
    static final Path SOCKET_ROOT = Path.of("/tmp");
    static final String DIRECTORY_PREFIX = "tpf-census-";
    static final String SOCKET_NAME = "host.sock";
    private static final int VERSION = 1;
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
        if (path.length() > (MAX_FRAME - 32) / 3) return;
        var payload = new ByteArrayOutputStream();
        try (var frame = new DataOutputStream(payload)) {
            frame.writeByte(VERSION);
            frame.writeUTF(path);
            frame.writeShort(javaMajor);
            for (String key : SIZES) frame.writeByte(summary.sizes().get(key).ordinal());
            for (String key : FEATURES) frame.writeByte(summary.featureCounts().get(key).ordinal());
            frame.writeByte(summary.transport().ordinal());
            frame.writeByte(summary.platform().ordinal());
            frame.writeByte(summary.runtimeLayout().ordinal());
        }
        var buffer = ByteBuffer.allocate(Integer.BYTES + payload.size());
        buffer.putInt(payload.size()).put(payload.toByteArray()).flip();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (channel.connect(UnixDomainSocketAddress.of(socket))) channel.write(buffer);
        }
    }

    /** Reads a whole frame after javac exits. A short or malformed frame is discarded. */
    static LocalCensusHandoff read(byte[] buffer, int size) throws IOException {
        try (var frame = new DataInputStream(new ByteArrayInputStream(buffer, 0, size))) {
            if (frame.readInt() != size - Integer.BYTES || frame.readUnsignedByte() != VERSION)
                throw new IOException("Invalid local census frame");
            Path anchor = Path.of(frame.readUTF());
            int javaMajor = frame.readUnsignedShort();
            var summary = new BuildCensusSummary(readBands(frame, SIZES), readBands(frame, FEATURES),
                    readEnum(frame, BuildCensusSummary.Transport.values()),
                    readEnum(frame, BuildCensusSummary.Platform.values()),
                    readEnum(frame, BuildCensusSummary.Layout.values()));
            if (frame.available() != 0 || !anchor.isAbsolute() || javaMajor < 1 || javaMajor > 999)
                throw new IOException("Invalid local census frame");
            return new LocalCensusHandoff(anchor, summary, javaMajor);
        }
    }

    private static Map<String, CountBand> readBands(DataInputStream frame, List<String> keys) throws IOException {
        var bands = new LinkedHashMap<String, CountBand>();
        for (String key : keys) bands.put(key, readEnum(frame, CountBand.values()));
        return bands;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream frame, E[] values) throws IOException {
        int index = frame.readUnsignedByte();
        if (index >= values.length) throw new IOException("Invalid local census enum");
        return values[index];
    }
}
