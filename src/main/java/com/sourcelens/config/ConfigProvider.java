package com.sourcelens.config;

/**
 * Provides default values for CLI options loaded from a configuration file.
 *
 * <p>Implementations are registered with Picocli via
 * {@link picocli.CommandLine#setDefaultValueProvider(picocli.CommandLine.IDefaultValueProvider)}.
 * A {@code null} return means "no configured value — fall through to Picocli's own default".
 */
public interface ConfigProvider {

    /**
     * Returns the configured default for the given option key, or {@code null} if no value
     * is configured.
     *
     * @param commandName the Picocli subcommand name (e.g. {@code "index"}, {@code "trace"})
     * @param optionKey   the option's longest name with {@code --} stripped (e.g. {@code "db"}, {@code "depth"})
     * @return the string value from the config file, or {@code null}
     */
    String getValue(String commandName, String optionKey);
}
