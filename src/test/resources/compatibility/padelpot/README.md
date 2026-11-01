# PadelPot authored-consumer compatibility fixture

`pipeline.yaml` is copied from `The-Pipeline-Framework/padelpot`,
`app/src/main/resources/pipeline.yaml`, migrated from the issue's original
`dec967cbdc16056804f3b4f9bbc26b88aacfaeca` baseline. It retains the complete authored
flow and canonical types from that migration proof. The application's current-main migration
also retains the later ChatAgent handoff and its executable compatibility suite.

The compiler regression loads the canonical types and validates every authored consumer.
The application owns the executable fixture suite, including generated adapters, two correlated
human reviews in one root, private persisted history, concurrency, and terminal results.

Review and result publication are real native Commands backed by the TPF persistence shell.
Review publication carries post-operation Await. The root declares `recur: [TurnState]`;
there are no identity review/return services or nominal loop wrappers.
