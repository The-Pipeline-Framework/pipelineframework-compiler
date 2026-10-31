package org.pipelineframework.processor.phase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.config.template.PipelineTemplateConfigLoader;
import org.pipelineframework.processor.Jsr269SourceInventory;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.composition.PipelineCompositionContractProjector;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.parser.StepDefinitionParser;
import org.pipelineframework.processor.routing.PipelineBranchRoutingPlanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineCompositionLinkingPhaseTest {
    @TempDir
    Path tempDir;

    @Test
    void pinsImplicitRootOutputInTheDefinitionFingerprintWithoutRequiringNestedInvocation() throws Exception {
        for (PipelineTransport transport : java.util.List.of(PipelineTransport.LOCAL, PipelineTransport.REST)) {
            var fingerprints = new HashSet<String>();
            for (String output : java.util.List.of("Outcome", "AlternateOutcome")) {
                Path file = tempDir.resolve(transport + "-" + output + ".yaml");
                Files.writeString(file, """
                    version: 3
                    appName: DefinitionCompletion
                    basePackage: com.example
                    transport: %s
                    contract: { input: Request, output: %s }
                    types:
                      Request: { fields: [[id, string]] }
                      Accepted: { fields: [[id, string]] }
                      Outcome: { variants: { accepted: Accepted } }
                      AlternateOutcome: { variants: { accepted: Accepted } }
                    steps:
                      - name: Approve
                        service: com.example.Approve
                        cardinality: ONE_TO_ONE
                        input: Request
                        output: Accepted
                    """.formatted(transport.name(), output));
                var config = new PipelineTemplateConfigLoader().load(file);
                var parsed = new StepDefinitionParser().parseDefinitionCatalog(file);
                var ctx = new PipelineCompilationContext(null, Jsr269SourceInventory.empty());
                ctx.setTransportMode(transport);
                ctx.setPipelineTemplateConfig(config);
                ctx.setStepDefinitions(parsed.rootSteps());
                ctx.setParsedPipelineDefinitionCatalog(parsed);
                ctx.setBranchingPlan(new PipelineBranchRoutingPlanner().plan(ctx).orElseThrow());
                new PipelineCompositionLinkingPhase().link(ctx);
                var composition = new PipelineCompositionContractProjector().project(
                    ctx.getResolvedPipelineDefinitionGraph().orElseThrow());
                var root = composition.definitions().getFirst();
                assertEquals(output, root.outputContractId());
                assertEquals(1, root.nodes().size());
                fingerprints.add(root.definitionFingerprint());
            }
            assertEquals(2, fingerprints.size(), "Changing only the output boundary must change the contract");
            assertTrue(fingerprints.stream().noneMatch(String::isBlank));
        }
    }
}
