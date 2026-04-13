package com.sourcelens.command;

import com.sourcelens.indexer.SourceIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Command(
    name = "index",
    mixinStandardHelpOptions = true,
    description = "Walk a Java source tree, parse every .java file, and persist method-call edges to a SQLite database."
)
public class IndexCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IndexCommand.class);

    @Option(names = {"-s", "--source"}, required = true, description = "Root directory of Java source to index.")
    private Path sourcePath;

    @Option(names = {"-d", "--db"}, defaultValue = ".sourcelens.db", description = "SQLite output file (default: .sourcelens.db).")
    private Path dbPath;

    @Override
    public void run() {
        // TODO: [DEBT-004] replace plain if-check with Assert utility in hardening pass
        if (!sourcePath.toFile().isDirectory()) {
            log.error("--source must be an existing directory: {}", sourcePath);
            throw new IllegalArgumentException("Source path is not a directory: " + sourcePath);
        }

        log.info("Indexing {} → {}", sourcePath.toAbsolutePath(), dbPath.toAbsolutePath());
        new SourceIndexer(dbPath).index(sourcePath);
    }
}
