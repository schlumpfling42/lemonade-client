# Task 9: Docs - report

Status: DONE. No commits (not a git repo). `.\gradlew.bat build` -> BUILD SUCCESSFUL (no new tests; docs/comments only).

## Files changed
- docs/agent-graph.md: Mermaid `coding` subgraph (all nodes, routes, pause), node table rows for `coding` and each `coding/*` node, `route()` row gains `coding`, new "Inside a coding job" section (plan check, NOT_IN_JOB, web content rule, limits, plan errors), state keys, pauses/progress, trace names, check step lists CodingGraphDefinition.
- README.md: text diagram, new "Coding jobs" section, `CODING_COMMAND_TIMEOUT_SECONDS` in config table, Layout row.
- src/main/java/net/rka/ai/agent/AgentGraph.java: class Javadoc diagram (comment only).
- src/main/java/net/rka/ai/agent/CodingGraph.java: two "Not implemented yet" items (Stop button, visual checking of designs); comment only.
- .env: commented `# CODING_COMMAND_TIMEOUT_SECONDS=600` with a comment line; no other line changed.

## Checklist: generated outline -> docs/agent-graph.md (Mermaid edge labels use the route names)
AgentGraphDefinition:
- START -> memory: mermaid `START --> memory`
- memory -> agent: mermaid
- tools -> agent, trusted -> agent, approval -> agent: mermaid (`tools --> agent`, `trusted_execute --> agent` etc.)
- coding -> agent: mermaid `coding_report --> agent`
- agent route coding: `agent -- "coding..." --> coding_environment`
- route approval / trusted / tools / done: unchanged edges in mermaid
- subgraphs approval, trusted, coding: mermaid `subgraph` blocks + table rows

CodingGraphDefinition:
- START -> environment: `agent -- coding --> coding_environment` (subgraph entry), table `coding/environment`
- environment -> investigate, investigateTools -> investigate, plan -> review, report -> END (returns to agent): mermaid
- investigate: tools, plan: mermaid labels + table
- review: approval, retry, failed: mermaid + table
- planApproval: approve, verify, revise, deny: mermaid + table
- implement: tools, next, verify, unfinished: mermaid + table
- implementTools: implement, revise: mermaid + table
- verify: passed, fix, failing: mermaid + table

Mermaid not rendered here (no tool); syntax follows the existing diagram's patterns.
