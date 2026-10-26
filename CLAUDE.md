# Project notes for Claude

Java agent: LangChain4j + LangGraph4j, JavaFX chat window and CLI. See `README.md` for setup and layout.

## Keep the graph diagram current

`docs/agent-graph.md` is the diagram of the agent graph and its subgraphs. Whenever a change
touches the graph — adding, removing or renaming a `@Node`, `@Edge`, `@Router` route,
`@Subgraph` or `@Subgraphs`, changing what a router decides, or changing which tool calls count as safe,
trusted or needing approval (`ToolRegistry`, `TrustedCommands`, `Settings`, `@RequiresApproval`,
`@WebContent`, `@WritesFiles`),
or the graph state keys — update
in the same change:

1. The Mermaid diagram and node table in `docs/agent-graph.md`.
2. The text diagram at the top of `README.md` and in the `AgentGraph` class Javadoc.

Check it against the outlines the graph processor generates: run `./gradlew compileJava`
(`.\gradlew.bat compileJava` on Windows) and read the Javadoc of
`build/generated/sources/annotationProcessor/java/main/net/rka/ai/agent/*Definition.java`
(`AgentGraph`, `ApprovalGraph`, `TrustedGraph`, `SkillBuilderGraph`).
Every edge and route listed there must appear in the diagram under the same name.

## Keep the skills docs current

Skills are JSON subgraphs (`net.rka.ai.agent.skills`, built-ins in
`src/main/resources/net/rka/ai/agent/skills/*.json`, the user's in `~/chat/skills`); the coding job is
the built-in skill `coding`. The generated outlines show them only as `skill-*`, so they need their own
check. Whenever a change touches skills — a node kind, its fields or outcomes (`GenericKinds`,
`CodingKinds`), the validator's rules or limits (`SkillValidator`), the loader's built-ins, overrides or
error handling (`SkillLoader`, and the build fallbacks in `AgentGraph.buildable` / `Agent.compile`), the generic `start` / `toolStep` / `toolApproval` / `stopped` nodes or
the pauses (`SkillGraphBuilder`), the skill builder, the skill state keys, or a built-in skill file
such as `coding.json` — update in the same change:

1. `docs/agent-graph.md`: the skill subgraph, the `skill-coding-*` nodes and the node tables, the
   rules for calls inside skills, the state keys and the "Skills" section.
2. The "Skills" section of `README.md` (file format, node kinds, safety rules, limits, builder,
   overriding `coding`). If you change its example file, check that it still validates (a throwaway
   test that parses it with `SkillFile.parse` and `SkillValidator.validate`; delete it afterwards).

Check the diagram against the built-in skill: every node, `next` and `on` entry of `coding.json`
must appear in the `skill-coding` subgraph of `docs/agent-graph.md` under the same names, and the
generic skill subgraph must match `SkillGraphBuilder`. A skill's own generated `~/chat/skills/<name>.md`
shows a user skill's diagram, which is a good way to check how a skill file is drawn.

## Build and test

- `./gradlew build` compiles everything and runs the graph-processor tests.
- `./gradlew runCli --args="question"` asks one question; risky commands prompt `Allow? [y/N]`
  on stdin (pipe `y` lines in to approve when testing non-interactively).
- Traces of every run are in `traces/traces-YYYY-MM-DD.jsonl` (one span per line), useful for
  seeing which graph nodes ran.
