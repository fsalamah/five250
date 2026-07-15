package com.acabes.five250;

import java.io.File;
import java.net.URISyntaxException;

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
}
