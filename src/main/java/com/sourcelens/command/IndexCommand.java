package com.sourcelens.command;

import com.sourcelens.Assert;
import com.sourcelens.indexer.SourceIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
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

    @Option(names = {"--clean"},
            description = "Delete the existing database before indexing (prompts for confirmation).")
    private boolean clean;

    @Option(names = {"-y", "--yes"},
            description = "Skip the --clean confirmation prompt (for scripting and CI).")
    private boolean yes;

    @Override
    public void run() {
        Assert.isDirectory(sourcePath, "--source must be an existing directory: " + sourcePath);

        if (clean) {
            if (!yes && !confirm()) {
                log.info("Clean aborted — database unchanged.");
                return;
            }
            try {
                boolean deleted = Files.deleteIfExists(dbPath);
                if (deleted) {
                    log.info("Removed existing database: {}", dbPath.toAbsolutePath());
                } else {
                    log.info("No existing database found at {} — proceeding with fresh index.", dbPath.toAbsolutePath());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete database: " + dbPath.toAbsolutePath(), e);
            }
        }

        log.info("Indexing {} → {}", sourcePath.toAbsolutePath(), dbPath.toAbsolutePath());
        new SourceIndexer(dbPath).index(sourcePath);
    }

    /**
     * Prompts the user for confirmation on stdout/stdin via {@link System#console()}.
     * Returns {@code false} when running in a non-interactive context (e.g. CI, piped
     * input) where no console is attached — callers must check {@code --yes} first.
     */
    private boolean confirm() {
        java.io.Console console = System.console();
        if (console == null) {
            log.warn("No interactive console — use --yes to bypass confirmation in non-interactive environments.");
            return false;
        }
        String answer = console.readLine(
                "Delete %s and re-index from scratch? [y/N] ", dbPath.toAbsolutePath());
        return answer != null && answer.trim().equalsIgnoreCase("y");
    }
}
