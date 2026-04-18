# Developer Notes

## Remove old db as required

`rm db/mybatis.db`

## Index the test fixture (skip if db already exists)

`java -jar target/sourcelens.jar index --source test-fixtures/mybatis-sample/src --db db/mybatis.db`

## Trace from UserController#getUser — happy path

`java -jar target/sourcelens.jar trace --entry "com.example.controller.UserController#getUser(Long)" --db db/mybatis.db`

## Inverse trace from the deepest node

`java -jar target/sourcelens.jar trace --entry "com.example.mapper.UserMapper#selectById(Long)" --db db/mybatis.db --callers`

## All stored method FQNs

`sqlite3 db/mybatis.db "SELECT fqn FROM method_node ORDER BY fqn;"`

## All stored edges (caller -> callee)  

`sqlite3 db/mybatis.db "SELECT c.fqn AS caller, e.fqn AS callee FROM call_edge x JOIN method_node c ON c.id = x.caller_id JOIN method_node e ON e.id = x.callee_id ORDER BY c.fqn;"`

## Query for the UserSorter FQNs

`sqlite3 db/mybatis.db "SELECT fqn FROM method_node WHERE fqn LIKE '%UserSorter%' ORDER BY fqn;"`

Expected output (nested and anonymous class FQNs are now correct):
com.example.util.UserSorter$ByUsername#compare(User, User)
com.example.util.UserSorter$anonymous:NN#compare(User, User)

Must NOT appear (old wrong attribution):
com.example.util.UserSorter#compare(User, User)

Confirm the existing UserController → UserServiceImpl → UserMapper trace still works after
re-indexing (regression check).
-------------------------------
Verification

./mvnw clean package

# Full pipeline — pipe trace into render

`java -jar target/sourcelens.jar trace --entry "com.example.controller.UserController#getUser(Long)" --db db/mybatis.db | java -jar target/sourcelens.jar render`

## Expected stdout

```plain
# ```mermaid
# sequenceDiagram
#     participant UserController
#     participant UserServiceImpl
#     participant UserMapper
#     UserController->>UserServiceImpl: findById(Long)
#     UserServiceImpl->>UserMapper: selectById(Long)
# ```
```

## Via intermediate file

`java -jar target/sourcelens.jar trace --entry "com.example.controller.UserController#getUser(Long)" --db db/mybatis.db --output trace.txt`

`java -jar target/sourcelens.jar render --input trace.txt --output diagram.md`

`cat /tmp/diagram.md`

## Error case — input file does not exist
java -jar target/sourcelens.jar render --input /nonexistent.txt
# Expected: Assert.fileExists fires with clear error message

--------------------------

Step 2 — Add query methods to CallGraphDb

File: src/main/java/com/sourcelens/db/CallGraphDb.java

getCallerFqns(String calleeFqn) — direct reverse of getCalleeFqns:
SELECT n1.fqn
FROM   method_node n1
JOIN   call_edge   e  ON e.caller_id = n1.id
JOIN   method_node n2 ON n2.id = e.callee_id
WHERE  n2.fqn = ?

findInterfaceCallerFqns(String fqn) — prototype heuristic, mirrors
findConcreteCalleeFqns. When no direct callers are found (because callers stored
calls against an interface FQN), find callers of any node sharing the same
#method(params) suffix:
SELECT DISTINCT n1.fqn
FROM   method_node n1
JOIN   call_edge   e  ON e.caller_id = n1.id
JOIN   method_node n2 ON n2.id = e.callee_id
WHERE  n2.fqn LIKE '%' || ?   -- ? = '#findById(Long)'
AND  n2.fqn != ?            -- exclude the original FQN
Tag with // TODO: [DEBT-010].

------------------------------------

# Build
./mvnw clean package

# Re-index the fixture (delete stale DB first if needed)
java -jar target/sourcelens.jar index \
  --source test-fixtures/mybatis-sample/src \
  --db db/mybatis.db

# Inverse trace from the deepest node
java -jar target/sourcelens.jar trace \
  --entry "com.example.mapper.UserMapper#selectById(Long)" \
  --db db/mybatis.db \
  --callers

# Expected stdout (order may vary):
# com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)
# com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)

## Pipe directly into render to see the upstream diagram

java -jar target/sourcelens.jar trace \
  --entry "com.example.mapper.UserMapper#selectById(Long)" \
  --db db/mybatis.db \
  --callers | \
  java -jar target/sourcelens.jar render

## Run tests

./mvnw test

### Expected

> Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

## Verification

./mvnw clean package

## Index fixture

java -jar target/sourcelens.jar index \
--source test-fixtures/mybatis-sample/src \
--db db/mybatis.db

## Split inverse trace — expect 3 sections

java -jar target/sourcelens.jar trace \
--entry "com.example.mapper.UserMapper#selectById(Long)" \
--db db/mybatis.db \
--callers --split

`java -jar target/sourcelens.jar trace --entry "com.example.mapper.UserMapper#selectById(Long)" --db db/mybatis.db --callers --split`

## Expected stdout (3 sections, order may vary)

```plain
=== com.example.controller.UserController#getUser(Long) ===
com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)
com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)
=== com.example.controller.UserController#createUser(String, String) ===
com.example.controller.UserController#createUser(String, String) ->
com.example.service.UserServiceImpl#createUser(String, String)
com.example.service.UserServiceImpl#createUser(String, String) -> com.example.mapper.UserMapper#selectById(Long)
=== com.example.controller.UserController#updateEmail(Long, String) ===
...
```

## Pipe to render for 3 Mermaid diagrams

`java -jar target/sourcelens.jar trace \
--entry "com.example.mapper.UserMapper#selectById(Long)" \
--db db/mybatis.db \
--callers --split | \
java -jar target/sourcelens.jar render`

## Run tests (expect 8 passing)

`./mvnw test`

## Feature 2.6 — Method Return Arrows

CallGraphDb (schema + API)

- method_node gains return_type TEXT; init() runs a guarded ALTER TABLE for pre-existing DBs
- upsertNode(fqn, returnType) — uses a proper ON CONFLICT DO UPDATE that never overwrites a real return type with null, so
insertion order doesn't matter
- getReturnType(fqn) — new query for trace emission
- persistEdges — reads optional edge[2] as the caller's return type

SourceIndexer — visitor changes from VoidVisitorAdapter<String> to VoidVisitorAdapter<String[]>, carrying [callerFqn,
returnType] as context; edges become 3-element arrays

TraceCommand — new returnTypeSuffix(db, calleeFqn) helper appends  : ReturnType to every emitted edge line across all
three traversal paths (traverse, traverseCallers, emitChain); emitChain gains a db parameter

### RenderCommand

- New Edge record replaces raw String[2] arrays throughout
- parseSections parses optional  : ReturnType suffix (backward compatible — old trace files work unchanged)
- writeDiagram is now two-pass: forward -->> arrows in edge order, then dashed -->> return arrows in reverse; void/null
suppressed

PipelineIntegrationTest — return arrow assertions added to both the full pipeline test and the split inverse trace test

Docs — docs/features/feature-2.6-method-returns.md created; ROADMAP.md updated with 2.6 entry [x]
