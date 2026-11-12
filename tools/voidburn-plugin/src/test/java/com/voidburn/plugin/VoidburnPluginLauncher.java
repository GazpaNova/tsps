package com.voidburn.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Developer-only entry point: launches RuneLite with the Voidburn plugin
 * registered as a built-in external plugin. This is the stock RuneLite 1.13.3
 * side-loading pattern — {@link ExternalPluginManager#loadBuiltin} adds the
 * plugin class to RuneLite's plugin set BEFORE the client's own plugin scan
 * runs, so the plugin is discovered and enabled through normal RuneLite
 * machinery without needing a Plugin Hub submission.
 *
 * Lives in src/test so it is excluded from the production plugin JAR —
 * players use the Plugin Hub distribution (future), not a bundled launcher.
 *
 * Run it from an IDE: pick "Run" on the main method. This is not a JUnit
 * test, so Gradle's `--tests` filter doesn't apply; launching via `java`
 * against the module's test classpath works too but is awkward to spell out
 * portably. Add a JavaExec task if you need a one-liner.
 *
 * Arguments are forwarded unchanged to {@link RuneLite#main}; use
 * {@code --developer-mode} to enable developer tooling.
 */
public final class VoidburnPluginLauncher {
    private VoidburnPluginLauncher() {}

    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(VoidburnPlugin.class);
        RuneLite.main(args);
    }
}
