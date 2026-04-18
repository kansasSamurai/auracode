package com.sourcelens.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads default CLI option values from a project-local configuration file and
 * bridges them into Picocli via {@link CommandLine.IDefaultValueProvider}.
 *
 * <p>File search order (both relative to the working directory):
 * <ol>
 *   <li>{@code .sourcelens} — hidden dot-file at the project root</li>
 *   <li>{@code src/etc/sourcelens/config.properties} — Maven-conventional config location</li>
 * </ol>
 * The first file found wins; the other is ignored.  If neither file exists the
 * provider is silently inert and all lookups return {@code null}.
 *
 * <p>Both files use standard Java {@code .properties} format.
 *
 * <p>Key resolution order for a given option:
 * <ol>
 *   <li>{@code <commandName>.<optionKey>} — e.g. {@code index.db} (command-scoped)</li>
 *   <li>{@code <optionKey>} alone — e.g. {@code db} (shared across commands)</li>
 *   <li>{@code null} — no configured value; Picocli falls back to its built-in default</li>
 * </ol>
 *
 * <p>CLI flags always take precedence over values supplied by this provider —
 * that is Picocli's standard behaviour and requires no special handling here.
 *
 * <p>Setting a value for a {@code required = true} option (e.g. {@code index.source}) in
 * the config file satisfies the requirement and makes the flag optional on the command line.
 *
 * // TODO: [DEBT-012] consider adding ~/.sourcelens (user-home) as a third, lowest-priority
 * //   candidate for global user defaults (below project-local files)
 */
public class DefaultConfigProvider implements ConfigProvider, CommandLine.IDefaultValueProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigProvider.class);

    private static final String PRIMARY_FILENAME   = ".sourcelens";
    private static final String SECONDARY_FILENAME = "src/etc/sourcelens/config.properties";

    private final Properties props;

    /** Production constructor — resolves config files relative to {@code user.dir}. */
    public DefaultConfigProvider() {
        this(Path.of(System.getProperty("user.dir")));
    }

    /**
     * Constructs a provider that resolves config files relative to the supplied
     * {@code baseDir} instead of {@code user.dir}.  Use this in tests to inject
     * a temporary directory so the production working directory is never read.
     */
    public DefaultConfigProvider(Path baseDir) {
        this.props = loadProperties(baseDir);
    }

    // -------------------------------------------------------------------------
    // ConfigProvider
    // -------------------------------------------------------------------------

    @Override
    public String getValue(String commandName, String optionKey) {
        // 1. Try command-scoped key first (e.g. "index.db")
        String scoped = commandName + "." + optionKey;
        if (props.containsKey(scoped)) {
            return props.getProperty(scoped);
        }
        // 2. Fall back to bare key (e.g. "db" — shared across commands)
        return props.getProperty(optionKey, null);
    }

    // -------------------------------------------------------------------------
    // IDefaultValueProvider (Picocli bridge)
    // -------------------------------------------------------------------------

    @Override
    public String defaultValue(CommandLine.Model.ArgSpec argSpec) {
        if (!(argSpec instanceof CommandLine.Model.OptionSpec optionSpec)) {
            return null; // positional parameters are not configured via file
        }
        String commandName = argSpec.command().name();
        // Strip leading "--" or "-" to get the bare key (e.g. "--db" → "db")
        String optionKey = optionSpec.longestName().replaceFirst("^--?", "");
        return getValue(commandName, optionKey);
    }

    // -------------------------------------------------------------------------
    // File loading
    // -------------------------------------------------------------------------

    private static Properties loadProperties(Path baseDir) {
        Path primary   = baseDir.resolve(PRIMARY_FILENAME);
        Path secondary = baseDir.resolve(SECONDARY_FILENAME);

        Path candidate = Files.exists(primary)   ? primary
                       : Files.exists(secondary) ? secondary
                       : null;

        Properties p = new Properties();
        if (candidate == null) {
            log.debug("No config file found — provider is inert.");
            return p;
        }

        try (InputStream is = Files.newInputStream(candidate)) {
            p.load(is);
            log.info("Loaded config from {}", candidate.toAbsolutePath());
        } catch (IOException e) {
            // Degrade gracefully: the tool is still fully usable without config-file defaults.
            log.warn("Config file found at {} but could not be read: {}", candidate, e.getMessage());
        }
        return p;
    }
}
