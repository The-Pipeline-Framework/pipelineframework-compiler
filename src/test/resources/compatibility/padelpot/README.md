# PadelPot authored-consumer compatibility fixture

`pipeline.yaml` is copied from `The-Pipeline-Framework/padelpot`,
`app/src/main/resources/pipeline.yaml`, migrated from the issue's original
`dec967cbdc16056804f3b4f9bbc26b88aacfaeca` baseline. It retains the complete authored
flow and canonical types from that migration proof. The application's current-main migration
also retains the later ChatAgent handoff and its executable compatibility suite.

The compiler regression loads the canonical types and validates every authored consumer.
The application owns the executable fixture suite, including generated adapters, two correlated
human reviews in one root, private persisted history, concurrency, and terminal results.

The policy operation selects its Review outcome for Await. Persistence aspects observe Review
and Result where meaningful operations produce them. The completed output union contains the
existing TurnState, AnsweredReview, and Result leaves. The root declares `recur: [TurnState]`;
there are no application persistence Commands, identity services, or new concrete phase types.
