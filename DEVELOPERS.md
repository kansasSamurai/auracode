# Developer Documentation

## How to manually test

The test-fixtures/mybatis-sample/ directory exists specifically for this. Once the fat JAR is built:

## Step 1: Build

./mvnw clean package

## Step 2: Run the index command against the sample fixture

java -jar target/sourcelens.jar index \
--source test-fixtures/mybatis-sample/src \
--db ./db/test.db

```plain
java -jar target/sourcelens.jar index --source test-fixtures/mybatis-sample/src --db ./db/test.db
```

## Step 3: Inspect the SQLite output

sqlite3 ./db/test.db "SELECT * FROM method_node;"
sqlite3 ./db/test.db "SELECT m1.fqn, m2.fqn FROM call_edge e JOIN method_node m1 ON e.caller_id=m1.id JOIN method_node m2
ON e.callee_id=m2.id;"

What success looks like: the log should print something like Indexed N files → X nodes, Y edges persisted to ... and the
SQLite queries should return rows.
