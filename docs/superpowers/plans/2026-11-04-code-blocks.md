# Code blocks: the Java model down to the statement

**Goal:** every piece of Java is a `Block` in one tree: a type holds member blocks, a method holds statement blocks, a loop holds the blocks it runs on each iteration. Each block knows its lines, the variables it declares and where they are visible, and what it reads, writes and calls.

**Why:** today the model stops at the member; a method body is text. So edits inside a method are text splices, the call graph cannot tell what `ledger` is in `ledger.size()`, and the model cannot be told "this block may use `total` and `i`". With blocks, edits, outlines, graph and job prompts all work on one structure.

## The model

```
Block (interface)            kind, startLine, endLine, children(), declares(), visibleHere()
├─ JavaClass                 children: members          (existing, implements Block)
├─ JavaMethod / Constructor  children: statements       (existing, gets body())
├─ JavaField / Initializer   children: initializer expression's blocks (lambdas, anonymous classes)
└─ Statement                 new
   ├─ simple:   local declaration, assignment, call, return, throw, break, continue, yield, assert
   └─ compound: if, for, foreach, while, do, switch, try, synchronized, labeled, plain { }
                children grouped in named parts: if → condition | then | else
                                                  for → init | condition | update | body
                                                  try → resources | body | catch e | finally
```

Metadata per block:
- `declares`: `Variable(name, type, line, scope)`; `scope` = the lines where it is visible (from the declaration to the end of the enclosing block; a `for` header's variables to the end of the `for`; a catch parameter to the end of its catch; pattern variables of `instanceof` approximated to the enclosing statement).
- `visibleHere`: parameters + fields + locals in scope at the block's first line.
- `reads`, `writes`: variable names used, resolved to a declaration (local, parameter, field, outer field).
- `calls`: `Call(name, receiver, resolvedType)`: the receiver type comes from the declared type of the variable, `this`, a type name (static), `new X()`, or the return type of the previous call in a chain.
- Lambdas and anonymous classes are blocks nested in the expression that holds them.

Expressions are not blocks: a statement is the smallest piece; inside it only declarations, variable uses, calls, lambdas and anonymous classes are extracted.

## Status

Done: 1 (spike), 2 (model), 3 (scope), 5 (block tools and checks), 7 (sections from the structure), 8 (the job tells the model which variables it can use and refuses a double declaration or a lost variable). 4 and 6 (call graph, `javaOutline`, `javaGraph`) were built separately on token reading, with tests. Not done: plan changes that name a block, and the call graph on top of the statements.

## Decisions (my picks; say if you disagree)

1. **Parser:** javac's parser (`JavacTask.parse`, already used by the tests), not an extension of the hand-written reader. It handles all current syntax (records, switch patterns, text blocks), gives exact positions, and no attribution/classpath is needed. The hand reader stays for the declaration level. Bonus: javac's syntax errors replace the reader's leniency in `JavaStructure.unreadable`, so a file that is not valid Java is refused for real.
2. **Comments:** javac drops them, so a block's range starts at the first comment line directly above it (found with the existing tokenizer), as members do today.
3. **When:** bodies are built lazily on first use and kept per unit (invalidated by every change), so loading a project stays as fast as now.
4. **Addressing a block:** `Type.member` + a path of child indexes with the kind, e.g. `Ledger.add(int) > for[2] > if[1].then > [3]`, shown by the outline so the model copies it. Variable and call text can also select (`the assignment to total`).
5. **Types of variables** come from declarations as written (`var x = new T()` → `T`; other `var` stays unresolved). No attribution, so inherited fields and static imports are unknown: they are never reported as errors.

## Milestones

Each ends with a green build and its own tests.

1. **Spike: blocks for one method.** javac parse of a method body → statement tree with lines. Test: for every method of the project's own sources, children lie inside the parent, in order, without overlap, and the text of each block re-parses as a statement.
2. **Model and `Block` interface.** Records for statements and parts, `Variable`, `Call`; `JavaClass`/`JavaMethod`/`JavaConstructor`/`JavaInitializer`/`JavaField` implement `Block`; `body()` built lazily. `withoutLines()` extended.
3. **Scope analysis.** Declarations, scopes, reads/writes, `visibleHere`. Test fixture: extend `EveryConstruct.java` with every statement kind (nested loops, shadowing in lambdas, try-with-resources, switch patterns, labeled break) and assert declared/visible sets.
4. **Calls with receiver types.** Resolve receivers (variable, field, `this`, static, `new`, chains by return type). Replaces the by-name guess in `possibleCallers`; interface and abstract calls also link to overriding methods in project subtypes.
5. **Tools on blocks.** `javaOutline(type, depth)` (types → members → statements with lines, declared variables, loops and branches indented); `javaReadBlock`, `javaReplaceBlock`, `javaInsertBlock(before | after | into part)`, `javaDeleteBlock`. They reuse `JavaWorkspace.changeLines`. `JavaStructure` gets a block-level check: after a change, a variable that was declared in the replaced or deleted block must not be used after it ("`total` is used at line 41, but only the replaced block declared it"), and nothing is declared twice in one scope.
6. **`javaGraph(from, depth, direction)`.** Entry points (`main`, or a named member) → reachable types and methods as a compact tree limited by depth and size; "path from A to B"; unreachable members. Built on milestone 4.
7. **One parser for sections.** `outline`, `readSection`, `editSection` take their sections from the block tree instead of `JavaOutliner`, so there is a single source of lines.
8. **The coding job uses blocks.** `gather` adds the visible variables of the block a change targets; plan changes may name a block; the edit prompt for a block carries only that block and what it sees (smaller, faster requests); study notes can quote the block outline. Update `coding.json`, `docs/agent-graph.md`, README.

## Risks

- Positions of some javac trees (annotations, varargs, lambdas in field initializers) are off by a token: covered by the milestone 1 test over all project sources.
- Scope of pattern variables and flow typing is approximate; the check only warns for variables declared in the block that was removed, so false alarms stay rare.
- Cost on large files: lazy build and per-unit cache; measure on the biggest file (`CodingGraph.java`, ~1,900 lines) at milestone 2.
- API size: many new records. Mitigation: statements are one generic record (`kind`, `parts`) with accessors, not one class per statement kind.

## Not in scope

Type attribution (generics, overload resolution, classpath), expression-level blocks, refactorings (rename across usages) — a rename tool is a good follow-up once calls resolve.
