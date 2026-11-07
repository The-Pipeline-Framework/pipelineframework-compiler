package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CensusMetadataTest {
    @TempDir Path temporary;
    @Test void versionsBelongToOwnerArtifactNotAnEarlierApplicationResource() throws Exception {
        String name = CountBand.class.getName().replace('.', '/') + ".class";
        byte[] owner;
        try (var input = CountBand.class.getResourceAsStream("CountBand.class")) { owner = input.readAllBytes(); }
        Path attacker = temporary.resolve("application.jar"), component = temporary.resolve("component.jar");
        for (String metadata : new String[] {"compiler-version.properties", "contracts-version.properties"}) {
            String resource = "META-INF/tpf/" + metadata;
            try (var jar = new JarOutputStream(Files.newOutputStream(attacker))) {
                jar.putNextEntry(new JarEntry(resource)); jar.write("version=1234.56.78\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
            }
            try (var jar = new JarOutputStream(Files.newOutputStream(component))) {
                jar.putNextEntry(new JarEntry(name)); jar.write(owner); jar.closeEntry();
                jar.putNextEntry(new JarEntry(resource)); jar.write("version=26.11.2-SNAPSHOT\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
            }
            try (var loader = new URLClassLoader(new java.net.URL[] {attacker.toUri().toURL(), component.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> packaged = loader.loadClass(CountBand.class.getName());
                assertEquals("26.11.2-dev", CensusEvent.packagedVersion(packaged, "/" + resource));
            }
            // Older owner without metadata must remain unknown, even with a shadow resource.
            try (var jar = new JarOutputStream(Files.newOutputStream(component))) {
                jar.putNextEntry(new JarEntry(name)); jar.write(owner); jar.closeEntry();
            }
            try (var loader = new URLClassLoader(new java.net.URL[] {attacker.toUri().toURL(), component.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                assertEquals("unknown", CensusEvent.packagedVersion(loader.loadClass(CountBand.class.getName()), "/" + resource));
            }
        }
    }
    @Test void inspectionNeverWritesUnderResourcesOrKnownHostOutputRoots() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path source = Files.writeString(project.resolve("pipeline.yaml"), "private");
        for (Path directory : new Path[] {project.resolve("src/main/resources"), project.resolve("target/classes"), temporary.resolve("custom-artifacts")}) {
            Files.createDirectories(directory);
            var configuration = new CensusConfiguration(CensusConfiguration.Mode.PREVIEW, temporary.resolve("state"), java.util.Optional.of(directory.resolve("preview.json")));
            var host = new BuildCensusHostContext(source, configuration, java.util.Set.of(temporary.resolve("custom-artifacts")));
            assertFalse(host.permitsPreviewOutput());
            assertFalse(Files.exists(directory.resolve("preview.json")));
        }
        Path resources = project.resolve("src/main/resources");
        Path alias = temporary.resolve("private-alias"); Files.createSymbolicLink(alias, resources);
        assertFalse(new BuildCensusHostContext(source, new CensusConfiguration(CensusConfiguration.Mode.PREVIEW,
                temporary.resolve("state"), java.util.Optional.of(alias.resolve("preview.json")))).permitsPreviewOutput());
        assertFalse(Files.exists(temporary.resolve("state")));
    }
}
