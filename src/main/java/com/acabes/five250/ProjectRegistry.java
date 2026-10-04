package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of known projects (name -> root directory) plus which one is active right now. Each
 * project's root holds its own suites/scripts/results/extracted/docs/samples - the same layout
 * Home.DIR held everything in before projects existed (a project root IS what Home.DIR used to
 * be, just no longer pinned to the jar's own directory). Persisted to "projects.json" next to
 * the running jar (Home.DIR) - deliberately app-level, not project-level, since it has to exist
 * before any project is opened.
 *
 * suites/<flow>/<file>.csv (+ .vars.csv/.data.csv/.bat/.sh) holds the data - what a suite does.
 * scripts/<flow>/<file>.js (+ generated .d.ts) holds the code - a JS orchestrator, when a suite
 * has one (see JsSuiteRunner). Kept as two separate trees rather than co-located, same shape,
 * different top-level folder - deliberate data/code separation.
 *
 * One active project for the whole daemon at a time (not per-connection, not per-request) -
 * switching projects in the GUI changes it for every API caller, CLI included. `run-suite
 * --project <name>` (see Cli.runSuite) pins a specific project for a single invocation without
 * touching or depending on whichever project the GUI currently has open - important for CI,
 * which shouldn't silently follow along if someone switches projects in the browser mid-run.
 *
 * Migration: a fresh install of this feature adopts any scenarios/ (or already-migrated suites/)
 * sitting directly at Home.DIR as a project named "default" the first time the registry is
 * loaded, and migrateIfNeeded() renames scenarios/ -> suites/ and pulls any .js/.d.ts out into
 * scripts/ in place - existing users need zero manual steps.
 */
public final class ProjectRegistry {

    private static final File REGISTRY_FILE = Home.file("projects.json");
    private static final File PROJECTS_ROOT = Home.file("projects");
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static List<Project> projects;
    private static String activeName;

    private ProjectRegistry() {}

    public static final class Project {
        public final String name;
        public final File root;
        public String lastOpened;

        private Project(String name, File root, String lastOpened) {
            this.name = name;
            this.root = root;
            this.lastOpened = lastOpened;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", root.getAbsolutePath());
            m.put("lastOpened", lastOpened);
            return m;
        }
    }

    // ---------- public API ----------

    public static synchronized List<Project> list() {
        ensureLoaded();
        return new ArrayList<>(projects);
    }

    /** Throws if no project exists yet - callers (HttpApi) should catch and surface an empty
     * "create or open a project" state instead of a 500. */
    public static synchronized Project current() {
        ensureLoaded();
        for (Project p : projects) if (p.name.equals(activeName)) return p;
        if (!projects.isEmpty()) {
            activeName = projects.get(0).name;
            persist();
            return projects.get(0);
        }
        throw new IllegalStateException("No project open yet - create one first");
    }

    public static synchronized Project byName(String name) {
        ensureLoaded();
        for (Project p : projects) if (p.name.equals(name)) return p;
        throw new IllegalArgumentException("No such project: " + name);
    }

    public static synchronized Project create(String name) {
        ensureLoaded();
        String clean = safeName(name);
        for (Project p : projects) {
            if (p.name.equalsIgnoreCase(clean)) throw new IllegalArgumentException("Project already exists: " + clean);
        }
        File root = new File(PROJECTS_ROOT, clean);
        mkLayout(root);
        Project p = new Project(clean, root, now());
        projects.add(p);
        activeName = clean;
        persist();
        return p;
    }

    public static synchronized Project open(String name) {
        ensureLoaded();
        Project p = byName(name);
        p.lastOpened = now();
        activeName = name;
        mkLayout(p.root); // defensive - in case a subfolder was removed by hand since last open
        persist();
        return p;
    }

    public static File suitesDir()    { return new File(current().root, "suites"); }
    public static File scriptsDir()   { return new File(current().root, "scripts"); }
    public static File resultsDir()   { return new File(current().root, "results"); }
    public static File extractedDir() { return new File(current().root, "extracted"); }
    public static File screenshotsDir() { return new File(current().root, "screenshots"); }
    public static File failuresDir()  { return new File(current().root, "docs/samples/failures"); }
    public static File replaysDir()   { return new File(current().root, "docs/samples/replays"); }

    // ---------- loading / migration / persistence ----------

    private static void ensureLoaded() {
        if (projects != null) return;
        projects = new ArrayList<>();
        if (REGISTRY_FILE.exists()) {
            try {
                String raw = Files.readString(REGISTRY_FILE.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> root = Json.parseObject(raw);
                activeName = (String) root.get("active");
                Object list = root.get("projects");
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        Object lastOpened = m.get("lastOpened");
                        projects.add(new Project(
                            String.valueOf(m.get("name")),
                            new File(String.valueOf(m.get("path"))),
                            lastOpened == null ? null : String.valueOf(lastOpened)
                        ));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("projects.json is unreadable: " + e.getMessage(), e);
            }
            // Every already-registered project gets migrated onto the current layout right away,
            // not just whichever one a user next happens to open()/create() - otherwise a project
            // that's already active when this ships would keep pointing at its old scenarios/
            // folder until someone happened to reopen it.
            for (Project p : projects) migrateIfNeeded(p.root);
            return;
        }

        // First boot with this feature: adopt any scenario data already sitting directly at
        // Home.DIR as a project named "default" instead of requiring existing users to migrate
        // by hand - mkLayout() below (via migrateIfNeeded) moves scenarios/ -> suites/ and pulls
        // any .js/.d.ts out into scripts/ the same way it would for any other existing project.
        if (Home.file("scenarios").isDirectory() || Home.file("suites").isDirectory()) {
            mkLayout(Home.DIR);
            Project p = new Project("default", Home.DIR, now());
            projects.add(p);
            activeName = "default";
            persist();
        }
        // else: genuinely first run, nothing to adopt - registry stays empty until the GUI
        // creates a project, at which point create()/persist() writes it out.
    }

    private static void mkLayout(File root) {
        migrateIfNeeded(root);
        new File(root, "suites").mkdirs();
        new File(root, "scripts").mkdirs();
        new File(root, "results").mkdirs();
        new File(root, "extracted").mkdirs();
        new File(root, "docs/samples/failures").mkdirs();
        new File(root, "docs/samples/replays").mkdirs();
    }

    /** Moves an existing project onto the current on-disk layout, in place, before anything else
     * touches it. Handles migrating from either prior layout:
     *   1. Original: "scenarios/" (old top-level name) with a suite's ".js"/".d.ts" sitting next
     *      to its own csv, under scenarios/<flow>/.
     *   2. Intermediate (a since-superseded mid-point of this same migration): "suites/" already
     *      renamed, but scripts still live per-flow under scripts/<flow>/.
     * Either way, every ".js" ends up flat at scripts/<name>.js - scripts are fully decoupled from
     * suites now (see JsSuiteRunner's importSuite()), so there's no flow to namespace them under
     * any more. Old per-suite ".d.ts" files are dropped, not migrated - the new Script editor's
     * declarations are fixed/generated on the fly (HttpApi.SCRIPT_DECLARATIONS), never written to
     * disk, so a stale one would just be dead weight. Safe to call on every open()/create(): every
     * step is a no-op once already migrated. */
    private static void migrateIfNeeded(File root) {
        File oldScenarios = new File(root, "scenarios");
        File suites = new File(root, "suites");
        if (oldScenarios.isDirectory() && !suites.exists()) {
            oldScenarios.renameTo(suites);
        }

        File scripts = new File(root, "scripts");
        flattenScriptsFrom(suites, scripts);   // case 1: scripts still sitting next to their csv
        flattenScriptsFrom(scripts, scripts);  // case 2: scripts already under scripts/<flow>/, not yet flat
    }

    /** Moves every "*.js" found in any immediate subdirectory of sourceRoot into destScripts
     * directly (flat - no subfolder), deleting any "*.d.ts" found alongside instead of moving it.
     * On a rare same-name collision between two different flows, the losing file is suffixed with
     * its original flow name rather than silently overwritten. */
    private static void flattenScriptsFrom(File sourceRoot, File destScripts) {
        if (!sourceRoot.isDirectory()) return;
        File[] subDirs = sourceRoot.listFiles(File::isDirectory);
        if (subDirs == null) return;
        for (File subDir : subDirs) {
            File[] found = subDir.listFiles((d, n) -> n.endsWith(".js") || n.endsWith(".d.ts"));
            if (found == null || found.length == 0) continue;
            destScripts.mkdirs();
            for (File f : found) {
                if (f.getName().endsWith(".d.ts")) {
                    f.delete();
                    continue;
                }
                File dest = new File(destScripts, f.getName());
                if (dest.exists()) dest = new File(destScripts, subDir.getName() + "-" + f.getName());
                f.renameTo(dest);
            }
            subDir.delete(); // no-op if anything else still lives there
        }
    }

    private static void persist() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("active", activeName);
        List<Object> list = new ArrayList<>();
        for (Project p : projects) list.add(p.toMap());
        root.put("projects", list);
        try {
            Files.writeString(REGISTRY_FILE.toPath(), Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to save projects.json: " + e.getMessage(), e);
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String cleaned = name.trim().replaceAll("[\\\\/]", "_").replace("..", "_");
        if (cleaned.isBlank()) throw new IllegalArgumentException("invalid name");
        return cleaned;
    }
}
