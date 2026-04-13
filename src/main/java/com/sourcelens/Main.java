package com.sourcelens;

import com.sourcelens.command.IndexCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "sourcelens",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Static call-graph analysis and Mermaid sequence diagram generation for Java projects.",
    subcommands = { IndexCommand.class, CommandLine.HelpCommand.class }
)
public class Main implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Override
    public void run() {
        // No subcommand given — print usage to guide the user.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
