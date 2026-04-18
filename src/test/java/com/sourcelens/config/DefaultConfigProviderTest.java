package com.sourcelens.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultConfigProviderTest {

    // -------------------------------------------------------------------------
    // Primary file (.sourcelens) — basic key resolution
    // -------------------------------------------------------------------------

    @Test
    void scopedKey_returnsValue(@TempDir Path dir) throws IOException {
        writePrimary(dir, "index.db=mydb.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("mydb.db", p.getValue("index", "db"));
    }

    @Test
    void bareKey_fallback(@TempDir Path dir) throws IOException {
        writePrimary(dir, "db=shared.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("shared.db", p.getValue("trace", "db"));
    }

    @Test
    void scopedKey_overridesBareKey(@TempDir Path dir) throws IOException {
        writePrimary(dir, "db=bare.db\ntrace.db=scoped.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("scoped.db", p.getValue("trace", "db"));
        // bare key still applies to other commands
        assertEquals("bare.db", p.getValue("index", "db"));
    }

    @Test
    void booleanValue_returnedAsString(@TempDir Path dir) throws IOException {
        writePrimary(dir, "trace.callers=true\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("true", p.getValue("trace", "callers"));
    }

    @Test
    void missingKey_returnsNull(@TempDir Path dir) throws IOException {
        writePrimary(dir, "db=some.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertNull(p.getValue("index", "source"),
                "Unknown key should return null");
    }

    // -------------------------------------------------------------------------
    // File search order
    // -------------------------------------------------------------------------

    @Test
    void primaryFile_takesPresedenceOverSecondary(@TempDir Path dir) throws IOException {
        writePrimary(dir, "db=primary.db\n");
        writeSecondary(dir, "db=secondary.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("primary.db", p.getValue("index", "db"));
    }

    @Test
    void secondaryFile_usedWhenPrimaryAbsent(@TempDir Path dir) throws IOException {
        writeSecondary(dir, "db=secondary.db\n");
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertEquals("secondary.db", p.getValue("index", "db"));
    }

    // -------------------------------------------------------------------------
    // No config file present
    // -------------------------------------------------------------------------

    @Test
    void noFile_allLookupsReturnNull(@TempDir Path dir) {
        DefaultConfigProvider p = new DefaultConfigProvider(dir);
        assertNull(p.getValue("index", "db"));
        assertNull(p.getValue("trace", "depth"));
        assertNull(p.getValue("render", "output"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void writePrimary(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve(".sourcelens"), content);
    }

    private static void writeSecondary(Path dir, String content) throws IOException {
        Path secondary = dir.resolve("src/etc/sourcelens");
        Files.createDirectories(secondary);
        Files.writeString(secondary.resolve("config.properties"), content);
    }
}
