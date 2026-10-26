# Task 1 report: @Subgraphs

## Implemented
- `graph-annotations/.../Subgraphs.java`: `@Subgraphs(prefix = "" (default), returnTo, router)` on a method.
- `GraphModel`: new `SubgraphsDef(prefix, returnTo, router method, method)` and a nullable `dynamicSubgraphs` component.
- `GraphProcessor`: registers the annotation; one role per method; at most one `@Subgraphs` per graph; signature check
  (`Map<String, StateGraph<S>>` or `Map<String, CompiledGraph<S>>`, no params, S = parent state);
  `readSubgraphs` resolves `returnTo` (a node or END) and `router` (name of a `@Router` method). Errors name the bad value
  with a "did you mean" hint, e.g. `@Subgraphs on skills(): router 'routr' is not a @Router method ...`.
- `GraphSourceWriter`: with `@Subgraphs`, `stateGraph` keeps the builder chain in `result`, calls the method once
  (`runtimeSubgraphs`), adds `addNode(prefix + key, value)` and `addEdge(prefix + key, returnTo)` per entry, builds the named
  router's route map as a `LinkedHashMap` plus `prefix + key -> prefix + key`, then adds routers as statements.
  Graphs without `@Subgraphs` generate exactly the old code. Outline lines: `skill-* = subgraphs skills()` and
  `agent -> route() {..., skill-*: skill-*}`.

## Tests (GraphProcessorTest)
- `subgraphsAddNodesEdgesAndRoutes`: two runtime subgraphs; routes skill-a, skill-b, return to `agent`; asserts outline lines and run result.
- `subgraphsNeedAnExistingRouterAndTarget`: unknown `router` and unknown `returnTo` both give a compile error naming the value.
- `.\gradlew.bat :graph-processor:test`: 15/15 pass. `.\gradlew.bat build`: BUILD SUCCESSFUL.

## TDD evidence
Not followed strictly: I wrote the implementation before the tests, so there is no recorded RED run. (Before the
annotation existed the tests could only fail by not compiling.) During GREEN, one test failed first
("Maximum number of iterations (25) reached"): a test bug, not a processor bug. `Channels.appender` drops values it
already holds, so a repeated `"agent"` step was lost and the router looped. The test now numbers the agent steps.

## Files changed
- graph-annotations/src/main/java/net/rka/ai/graph/annotations/Subgraphs.java (new)
- graph-processor/src/main/java/net/rka/ai/graph/processor/{GraphProcessor,GraphModel,GraphSourceWriter}.java
- graph-processor/src/test/java/net/rka/ai/graph/processor/GraphProcessorTest.java

## Notes
- No commits (not a git repo). `@Subgraphs` is not used in the app; docs/README untouched as instructed.
- A key colliding with a static node name is not detectable at compile time; LangGraph4j fails at graph build.
- `GraphProcessor` class Javadoc "Flow" does not mention `@Subgraphs` (minor).
