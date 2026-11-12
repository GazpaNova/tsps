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
 * Run it from an IDE, or from Gradle via:
 * <pre>
 *   bash runelite-gateway/gradlew -p tools/voidburn-plugin test \
 *     --tests com.voidburn.plugin.VoidburnPluginLauncher
 * </pre>
 * (The launcher is a main method, not a JUnit test, so prefer launching from
 * an IDE "Run" action; a direct `java` invocation with the test classpath
 * also works.)
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
