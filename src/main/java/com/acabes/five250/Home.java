package com.acabes.five250;

import java.io.File;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Resolves where five250 keeps its data (scenarios/, docs/) — the directory containing the
 * running jar, NOT the current working directory. This is what makes the package portable:
 * `java -jar /anywhere/five250.jar ...` always finds /anywhere/scenarios, regardless of which
 * directory you launched it from. Override with the FIVE250_HOME env var if you want the data
 * to live somewhere else entirely.
 */
public final class Home {

    public static final File DIR = resolve();

    private Home() {}

    private static File resolve() {
        String env = System.getenv("FIVE250_HOME");
        if (env != null && !env.isBlank()) {
            return new File(env);
        }
        try {
            File jarFile = new File(Home.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jarFile.isFile() && jarFile.getName().endsWith(".jar")) {
                return jarFile.getParentFile();
            }
        } catch (URISyntaxException | SecurityException | NullPointerException ignored) {
            // running from exploded classes (IDE/dev) — fall through to CWD
        }
        return new File(".");
    }

    public static File file(String relativePath) {
        return new File(DIR, relativePath);
    }

    // ---------- "was five250.jar rebuilt underneath a running daemon?" ----------
    //
    // The daemon stays up across CLI calls and loads classes LAZILY from its jar - GraalJS/
    // Truffle, for one, isn't touched until the first script or if/loop condition runs. On
    // Windows, Maven happily rewrites target/five250.jar in place while the daemon still has the
    // old one open (the JVM opens it with share-read/write), so after a rebuild every class the
    // daemon hasn't loaded yet comes from a zip whose entries no longer line up with what the
    // classloader indexed at startup: NoClassDefFoundError/ZipException on the next lazy load,
    // and - because Engine$ImplHolder's static initializer is what fails first for a script
    // run - a permanent "Could not initialize class org.graalvm.polyglot.Engine$ImplHolder" on
    // every run after that, with no hint that a rebuild is the reason. So the daemon stamps its
    // jar's size+mtime once at startup and refuses work (with THAT hint) once they've changed;
    // the CLI goes one step further and just restarts a stale daemon transparently.

    private static final File JAR = resolveJar();
    private static long jarSize = -1;
    private static long jarMtime = -1;

    private static File resolveJar() {
        try {
            File f = new File(Home.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() && f.getName().endsWith(".jar") ? f : null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null; // exploded classes (IDE/dev) - nothing to stamp
        }
    }

    /** Records the running jar's size and mtime; call once when the daemon starts. */
    public static synchronized void stampJar() {
        if (JAR == null) return;
        jarSize = JAR.length();
        jarMtime = JAR.lastModified();
    }

    /** The jar's mtime as stamped at daemon startup (epoch millis), or -1 if not running from a jar. */
    public static synchronized long stampedJarMtime() {
        return jarMtime;
    }

    /**
     * Null while the jar on disk is still the one this JVM started from; otherwise a
     * human-readable explanation that it changed (and what to do about it), suitable for
     * returning straight to a CLI/GUI caller as the error message.
     */
    public static synchronized String jarStaleReason() {
        if (JAR == null || jarMtime < 0) return null;
        long size = JAR.length();
        long mtime = JAR.lastModified();
        if (size == jarSize && mtime == jarMtime) return null;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        return "five250.jar was rebuilt at " + fmt.format(Instant.ofEpochMilli(mtime))
            + " while this daemon (started from the " + fmt.format(Instant.ofEpochMilli(jarMtime))
            + " build) was still running, so it can no longer load classes from it."
            + " Restart the daemon and re-run: 'five250 shutdown' (any following CLI command starts a fresh one),"
            + " or if you launched the GUI with 'java -jar five250.jar', close that and launch it again.";
    }
}
