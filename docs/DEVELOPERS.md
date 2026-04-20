# Developer Documentation

## How to manually test Feature 1.1

The test-fixtures/mybatis-sample/ directory exists specifically for this. Once the fat JAR is built:

## Step 1: Build

`./mvnw clean package`

## Step 2: Run the index command against the sample fixture

java -jar target/auracode.jar index \
--source test-fixtures/mybatis-sample/src \
--db ./db/test.db

```plain
java -jar target/auracode.jar index --source test-fixtures/mybatis-sample/src --db ./db/test.db
```

## Step 3: Inspect the SQLite output

sqlite3 ./db/test.db "SELECT * FROM method_node;"
sqlite3 ./db/test.db "SELECT m1.fqn, m2.fqn FROM call_edge e JOIN method_node m1 ON e.caller_id=m1.id JOIN method_node m2
ON e.callee_id=m2.id;"

What success looks like: the log should print something like Indexed N files → X nodes, Y edges persisted to ... and the
SQLite queries should return rows.

## Command Reference

## Index the test fixture (skip if db already exists)

java -jar target/auracode.jar index --source test-fixtures/mybatis-sample/src --db db/mybatis.db

## Trace from UserController#getUser — happy path

java -jar target/auracode.jar trace --entry "com.example.controller.UserController#getUser(Long)" --db db/mybatis.db

## Remove old db as required

rm db/mybatis.db

## All stored method FQNs

sqlite3 db/mybatis.db "SELECT fqn FROM method_node ORDER BY fqn;"

## All stored edges (caller -> callee)  

sqlite3 db/mybatis.db "SELECT c.fqn AS caller, e.fqn AS callee FROM call_edge x JOIN method_node c ON c.id = x.caller_id JOIN method_node e ON e.id = x.callee_id ORDER BY c.fqn;"
