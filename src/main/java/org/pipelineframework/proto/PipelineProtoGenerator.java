/*
 * Copyright (c) 2023-2025 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.proto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.pipelineframework.config.pipeline.PipelineYamlConfigLocator;
import org.pipelineframework.config.template.*;

/**
 * Generates protobuf definitions from the pipeline template configuration.
 */
public class PipelineProtoGenerator {

    private static final String ORCHESTRATOR_PROTO = "orchestrator.proto";
    private static final String TYPES_PROTO = "pipeline-types.proto";
    private static final String IDL_BOOTSTRAP_PROPERTY = "pipeline.idl.bootstrap";
    private static final String REQUIRE_COMMITTED_IDL_STATE_PROPERTY = "pipeline.idl.require-committed-state";
    private final PipelineTypesProtoRenderer typesRenderer = new PipelineTypesProtoRenderer();
    private final PipelineStepNormalizer stepNormalizer = new PipelineStepNormalizer();
    private final PipelineStepProtoRenderer stepRenderer = new PipelineStepProtoRenderer();
    private final PipelineOrchestratorProtoRenderer orchestratorRenderer = new PipelineOrchestratorProtoRenderer();
    private final ExternalStepHostContractRenderer externalRenderer = new ExternalStepHostContractRenderer();
    private final PipelineIdlSnapshotWriter idlWriter = new PipelineIdlSnapshotWriter();

    /**
     * Creates a new PipelineProtoGenerator.
     */
    public PipelineProtoGenerator() {
    }

    /**
     * Command-line entry point for the generator.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        PipelineProtoGenerator generator = new PipelineProtoGenerator();
        generator.generate(arguments.moduleDir(), arguments.configPath(), arguments.outputDir(), arguments.typesProtoName());
    }

    /**
     * Generates protobuf definitions from the pipeline template configuration.
     *
     * This method locates and loads the pipeline configuration, creates the target
     * output directory if necessary, writes an IDL snapshot, and emits per-step
     * proto files plus the orchestrator and (when version &gt;= 2) a shared types
     * proto into the output directory.
     *
     * @param moduleDir  the module directory used to resolve the configuration and default output; may be null to use the current working directory
     * @param configPath an explicit path to the pipeline template config, or null to locate the config automatically starting from moduleDir
     * @param outputDir  the directory to write generated .proto files to, or null to use the default target/generated-sources/proto under moduleDir
     * @throws IllegalStateException if the configuration is invalid or missing required values (for example missing basePackage), if the config cannot be located, if output directories cannot be created, or if IDL compatibility checks fail
     */
    public void generate(Path moduleDir, Path configPath, Path outputDir) {
        generate(moduleDir, configPath, outputDir, TYPES_PROTO);
    }

    /**
     * Generates protobuf definitions from the pipeline template configuration using an explicit shared types proto name.
     *
     * This overload behaves like {@link #generate(Path, Path, Path)} but allows callers to choose
     * the filename used for the shared version-2 message-types proto. When {@code typesProtoName}
     * is {@code null} or blank, the generator falls back to {@code pipeline-types.proto}.
     *
     * @param moduleDir the module directory used to resolve the configuration and default output; may be null to use the current working directory
     * @param configPath an explicit path to the pipeline template config, or null to locate the config automatically starting from moduleDir
     * @param outputDir the directory to write generated .proto files to, or null to use the default target/generated-sources/proto under moduleDir
     * @param typesProtoName the filename to use for the shared v2 types proto; defaults to {@code pipeline-types.proto} when null or blank
     * @throws IllegalStateException if the configuration is invalid or missing required values, if the config cannot be located, if output directories cannot be created, or if IDL compatibility checks fail
     */
    public void generate(Path moduleDir, Path configPath, Path outputDir, String typesProtoName) {
        Path resolvedModuleDir = moduleDir == null ? Path.of("") : moduleDir;
        Path resolvedConfig = resolveConfigPath(resolvedModuleDir, configPath).toAbsolutePath().normalize();
        Path resolvedOutput = outputDir != null
            ? outputDir
            : resolvedModuleDir.resolve("target").resolve("generated-sources").resolve("proto");
        String resolvedTypesProtoName = validateTypesProtoName(typesProtoName);

        PipelineTemplateConfigLoader loader = new PipelineTemplateConfigLoader();
        PipelineTemplateConfig config = loader.load(resolvedConfig);
        Path idlStatePath = idlWriter.resolveIdlStatePath(resolvedConfig);
        PipelineIdlSnapshot committedState = Files.exists(idlStatePath) ? idlWriter.readBaselineSnapshot(idlStatePath.toString()) : null;
        boolean lockMissing = committedState == null;
        boolean hasSemanticTypes = config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3
            ? !config.typeModel().definitions().isEmpty()
            : !config.messages().isEmpty() || !config.unions().isEmpty();
        if (lockMissing && hasSemanticTypes && Boolean.getBoolean(REQUIRE_COMMITTED_IDL_STATE_PROPERTY)) {
            throw new IllegalStateException("Pipeline template is missing committed IDL state: " + idlStatePath);
        }
        boolean bootstrapIdl = Boolean.getBoolean(IDL_BOOTSTRAP_PROPERTY);
        boolean initializeIdl = bootstrapIdl || (config.dialect() != org.pipelineframework.config.template.PipelineTemplateDialect.V3
            && lockMissing && hasSemanticTypes);
        PipelineIdlStateResolver.Resolved idl = new PipelineIdlStateResolver().resolve(config, committedState, initializeIdl);
        config = idl.config();
        if (config.basePackage() == null || config.basePackage().isBlank()) {
            throw new IllegalStateException("pipeline-config.yaml is missing basePackage");
        }
        try {
            Files.createDirectories(resolvedOutput);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create proto output directory: " + resolvedOutput, e);
        }

        idlWriter.writeIdlSnapshot(resolvedModuleDir, config, idl.state(), committedState, idlStatePath, initializeIdl);
        if (lockMissing && initializeIdl) {
            System.getLogger(PipelineProtoGenerator.class.getName()).log(System.Logger.Level.WARNING,
                "Created missing IDL lock file {0}; commit it to preserve compatibility", idlStatePath);
        }
        List<PipelineTemplateStep> steps = config.steps();
        if (steps == null || steps.isEmpty()) {
            externalRenderer.write(config.basePackage(), resolvedOutput, List.of(), resolvedTypesProtoName);
            if (config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3) {
                writeProto(resolvedOutput.resolve(resolvedTypesProtoName),
                    typesRenderer.renderV3(config.basePackage(), config.typeModel(), idl.state()));
            }
            return;
        }

        boolean sharedTypes = config.dialect() != org.pipelineframework.config.template.PipelineTemplateDialect.V1;
        List<ResolvedStep> resolvedSteps = stepNormalizer.normalizeSteps(steps, sharedTypes,
            config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3);
        if (config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3) {
            resolvedSteps = stepNormalizer.resolveV3AliasContracts(resolvedSteps, config.typeModel());
        }
        List<AspectDefinition> aspectDefinitions = stepNormalizer.toAspectDefinitions(config.aspects());

        if (sharedTypes) {
            Path typesProtoPath = resolvedOutput.resolve(resolvedTypesProtoName);
            String typesProto = config.dialect() == org.pipelineframework.config.template.PipelineTemplateDialect.V3
                ? typesRenderer.renderV3(config.basePackage(), config.typeModel(), idl.state())
                : typesRenderer.renderV2(config.basePackage(), config.messages(), config.unions());
            writeProto(typesProtoPath, typesProto);
        }

        for (int i = 0; i < resolvedSteps.size(); i++) {
            ResolvedStep step = resolvedSteps.get(i);
            ResolvedStep previous = i > 0 ? resolvedSteps.get(i - 1) : null;
            String content = stepRenderer.renderStepProto(
                config.basePackage(),
                step,
                previous,
                i == 0,
                aspectDefinitions,
                sharedTypes,
                resolvedTypesProtoName);
            Path protoPath = resolvedOutput.resolve(step.serviceName() + ".proto");
            writeProto(protoPath, content);
        }
        externalRenderer.write(config.basePackage(), resolvedOutput, resolvedSteps, resolvedTypesProtoName);

        String transport = config.transport();
        if (transport == null || transport.isBlank() || "GRPC".equalsIgnoreCase(transport)) {
            String content = orchestratorRenderer.renderOrchestratorProto(config.basePackage(), resolvedSteps, sharedTypes, resolvedTypesProtoName);
            Path protoPath = resolvedOutput.resolve(ORCHESTRATOR_PROTO);
            writeProto(protoPath, content);
        }
    }

    private String validateTypesProtoName(String typesProtoName) {
        String candidate = (typesProtoName == null || typesProtoName.isBlank()) ? TYPES_PROTO : typesProtoName.trim();
        if (".".equals(candidate) || "..".equals(candidate)
            || candidate.contains("/") || candidate.contains("\\")
            || Path.of(candidate).isAbsolute()) {
            throw new IllegalArgumentException("--types-proto-name must be a file name, not a path");
        }
        return candidate;
    }

    /**
     * Resolve the pipeline template configuration path by returning the provided path or locating a config under the module directory.
     *
     * @param moduleDir the root directory to search for a pipeline template config when {@code configPath} is null
     * @param configPath an explicit path to the pipeline template config; if non-null this value is returned
     * @return the resolved path to the pipeline template configuration
     * @throws IllegalStateException if {@code configPath} is null and no pipeline template config can be located under {@code moduleDir}
     */
    private Path resolveConfigPath(Path moduleDir, Path configPath) {
        if (configPath != null) {
            return configPath;
        }
        PipelineYamlConfigLocator locator = new PipelineYamlConfigLocator();
        Optional<Path> located = locator.locate(moduleDir);
        if (located.isEmpty()) {
            throw new IllegalStateException("No pipeline template config found from " + moduleDir.toAbsolutePath());
        }
        return located.get();
    }

    private void writeProto(Path outputPath, String content) {
        try {
            Files.writeString(outputPath, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write proto file: " + outputPath, e);
        }
    }

    private record Arguments(Path moduleDir, Path configPath, Path outputDir, String typesProtoName) {
        /**
         * Parse command-line options and produce an Arguments instance with the resolved paths.
         *
         * Recognized options:
         * - --module-dir <path> or --module-dir=<path>
         * - --config <path> or --config=<path>
         * - --output-dir <path> or --output-dir=<path>
         * - --types-proto-name <file> or --types-proto-name=<file>
         * - --help or -h (prints usage and exits)
         *
         * @param args the raw CLI arguments (may be null)
         * @return an Arguments object with moduleDir, configPath, and outputDir set from the parsed options (any unset value will be null)
         */
        static Arguments parse(String[] args) {
            Path moduleDir = null;
            Path configPath = null;
            Path outputDir = null;
            String typesProtoName = TYPES_PROTO;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if (arg == null || arg.isBlank()) {
                        continue;
                    }
                    if (arg.startsWith("--module-dir=")) {
                        moduleDir = Path.of(arg.substring("--module-dir=".length()));
                    } else if ("--module-dir".equals(arg) && i + 1 < args.length) {
                        moduleDir = Path.of(args[++i]);
                    } else if (arg.startsWith("--config=")) {
                        configPath = Path.of(arg.substring("--config=".length()));
                    } else if ("--config".equals(arg) && i + 1 < args.length) {
                        configPath = Path.of(args[++i]);
                    } else if (arg.startsWith("--output-dir=")) {
                        outputDir = Path.of(arg.substring("--output-dir=".length()));
                    } else if ("--output-dir".equals(arg) && i + 1 < args.length) {
                        outputDir = Path.of(args[++i]);
                    } else if (arg.startsWith("--types-proto-name=")) {
                        typesProtoName = arg.substring("--types-proto-name=".length());
                    } else if ("--types-proto-name".equals(arg) && i + 1 < args.length) {
                        typesProtoName = args[++i];
                    } else if ("--help".equals(arg) || "-h".equals(arg)) {
                        printUsage();
                        System.exit(0);
                    }
                }
            }
            return new Arguments(moduleDir, configPath, outputDir, typesProtoName);
        }

        /**
         * Display the command-line usage instructions for PipelineProtoGenerator.
         *
         * Writes a single-line usage message to standard output describing the accepted options: --module-dir, --config, and --output-dir.
         */
        static void printUsage() {
            System.out.println(
                "Usage: PipelineProtoGenerator [--module-dir DIR] [--config PATH] [--output-dir DIR] [--types-proto-name FILE]");
        }
    }
}
