package com.momosoftworks.kawaforge;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** AOT-compiles Kawa Scheme sources to JVM class files, incrementally. */
public class KawaCompileTask extends DefaultTask {

    // Kawa Scheme scan script — embedded rather than shipped separately.
    private static final String SCAN_SCRIPT =
        ";; Scan Kawa module files: module-name, import, and :: type references.\n" +
        "(define file-list-path (cadr (command-line)))\n" +
        "(define (collect-type-refs form)\n" +
        "  (let ((results '()))\n" +
        "    (let walk ((f form))\n" +
        "      (cond\n" +
        "       ((pair? f)\n" +
        "        (when (and (eq? (car f) '::) (pair? (cdr f)))\n" +
        "          (let ((type-name (cadr f)))\n" +
        "            (when (symbol? type-name)\n" +
        "              (let ((name-str (symbol->string type-name)))\n" +
        "                (when (string-contains name-str \".\")\n" +
        "                  (set! results (cons name-str results)))))))\n" +
        "        (walk (car f))\n" +
        "        (walk (cdr f)))))\n" +
        "    results))\n" +
        "(define (dedup lst)\n" +
        "  (let loop ((lst lst) (seen '()) (acc '()))\n" +
        "    (cond ((null? lst) (reverse acc))\n" +
        "          ((member (car lst) seen string=?) (loop (cdr lst) seen acc))\n" +
        "          (else (loop (cdr lst) (cons (car lst) seen) (cons (car lst) acc))))))\n" +
        "(define (scan-file path)\n" +
        "  (let* ((type-refs '())\n" +
        "         (p (open-input-file path)))\n" +
        "    (let form-loop ()\n" +
        "      (let ((form (read p)))\n" +
        "        (unless (eof-object? form)\n" +
        "          (when (pair? form)\n" +
        "            (case (car form)\n" +
        "              ((module-name)\n" +
        "               (format #t \"MODULE ~a: ~a~%\" path (cadr form)))\n" +
        "              ((import)\n" +
        "               (let ((spec (cadr form)))\n" +
        "                 (cond\n" +
        "                  ((symbol? spec)\n" +
        "                   (format #t \"IMPORT ~a: ~a~%\" path spec))\n" +
        "                  ((pair? spec)\n" +
        "                   (format #t \"IMPORT ~a: ~a\" path (car spec))\n" +
        "                   (for-each (lambda (m) (format #t \".~a\" m)) (cdr spec))\n" +
        "                   (format #t \"~%\")))))\n" +
        "              (else\n" +
        "               (set! type-refs (append (collect-type-refs form) type-refs)))))\n" +
        "          (form-loop))))\n" +
        "    (close-input-port p)\n" +
        "    (for-each (lambda (ref)\n" +
        "                (format #t \"TYPEREF ~a: ~a~%\" path ref))\n" +
        "              (dedup type-refs))))\n" +
        "(let ((p (open-input-file file-list-path)))\n" +
        "  (let loop ()\n" +
        "    (let ((line (read-line p)))\n" +
        "      (unless (eof-object? line)\n" +
        "        (when (> (string-length line) 0)\n" +
        "          (scan-file line))\n" +
        "        (loop))))\n" +
        "  (close-input-port p))\n";

    // Kawa Scheme REPL module — AOT-compiled alongside the mod's sources.
    // Provides (start-repl! [#!optional port]) for geiser-kawa integration.
    static final String REPL_MODULE =
        "(module-name kawaforge.repl)\n" +
        "(import (kawa base))\n" +
        "\n" +
        "(define repl-started? #f)\n" +
        "\n" +
        "(define (start-repl! #!optional (port 4242))\n" +
        "  (unless repl-started?\n" +
        "    (set! repl-started? #t)\n" +
        "    (guard\n" +
        "     (exn\n" +
        "      (#t\n" +
        "       (format (current-error-port)\n" +
        "               \"Kawa REPL: port ~a in use (another mod has it)~%\" port)))\n" +
        "     (let* ((repl (kawa.standard.Scheme:getInstance \"scheme\"))\n" +
        "            (server :: java.net.ServerSocket\n" +
        "                    (java.net.ServerSocket:new port 50\n" +
        "                      (java.net.InetAddress:getLoopbackAddress)))\n" +
        "            (accept-loop\n" +
        "             (lambda ()\n" +
        "               (let loop ()\n" +
        "                 (let* ((client (server:accept))\n" +
        "                        (session (kawa.TelnetRepl:new repl client))\n" +
        "                        (thread :: java.lang.Thread\n" +
        "                                (java.lang.Thread:new\n" +
        "                                 (gnu.mapping.RunnableClosure:new\n" +
        "                                  session:apply0)\n" +
        "                                 \"kawa-repl-session\")))\n" +
        "                   (thread:setDaemon #t)\n" +
        "                   (thread:start)\n" +
        "                   (loop))))))\n" +
        "       (format #t \"Kawa REPL listening on 127.0.0.1:~a~%\" port)\n" +
        "       (java.lang.Thread:new\n" +
        "        (gnu.mapping.RunnableClosure:new accept-loop)\n" +
        "        \"kawa-repl-accept\"):start\n" +
        "       server))))\n";

    // Kawa Scheme script that scans a jar for Kawa modules.
    // Uses ModuleInfo.find — Kawa's own module discovery API.
    static final String JAR_SCAN_SCRIPT =
        ";; Scan a jar for Kawa modules using ModuleInfo.find.\n" +
        "(import (kawa base))\n" +
        "(define jar-path (cadr (command-line)))\n" +
        "(let* ((jar :: java.util.jar.JarFile\n" +
        "            (java.util.jar.JarFile:new (as java.lang.String jar-path)))\n" +
        "       (entries (jar:entries)))\n" +
        "  (let loop ()\n" +
        "    (if (entries:hasMoreElements)\n" +
        "        (let* ((entry :: java.util.jar.JarEntry (entries:nextElement))\n" +
        "               (name :: java.lang.String (entry:getName)))\n" +
        "          (when (and (name:endsWith \".class\")\n" +
        "                     (not (name:contains \"$\"))\n" +
        "                     (not (name:startsWith \"gnu/\"))\n" +
        "                     (not (name:startsWith \"kawa/\")))\n" +
        "            (let* ((no-ext :: java.lang.String\n" +
        "                           (name:substring 0 (- (name:length) 6)))\n" +
        "                   (class-dot :: java.lang.String\n" +
        "                              (no-ext:replace #\\/ #\\.)))\n" +
        "              (guard (exn (#t #f))\n" +
        "                (let ((ctype (gnu.bytecode.ClassType:make class-dot)))\n" +
        "                  (when (not (eq? (gnu.expr.ModuleInfo:find ctype) #!null))\n" +
        "                    (format #t \"JARMODULE ~a: ~a~%\" jar-path class-dot))))))\n" +
        "          (loop))\n" +
        "        (jar:close))))\n";

    private File schemeSourceDir;

    @InputDirectory
    public File getSchemeSourceDir() { return schemeSourceDir; }
    public void setSchemeSourceDir(File dir) { this.schemeSourceDir = dir; }

    private final DirectoryProperty schemeOutputDir = getProject().getObjects().directoryProperty();

    @OutputDirectory
    public DirectoryProperty getSchemeOutputDir() { return schemeOutputDir; }

    private SourceSet mainSourceSet;

    @Internal
    public SourceSet getMainSourceSet() { return mainSourceSet; }
    public void setMainSourceSet(SourceSet ss) { this.mainSourceSet = ss; }

    private List<String> compilerArgs = Collections.emptyList();

    @Input
    public List<String> getCompilerArgs() { return compilerArgs; }
    public void setCompilerArgs(List<String> args) { this.compilerArgs = args; }

    private String verbosity = "normal";

    @Input
    public String getVerbosity() { return verbosity; }
    public void setVerbosity(String v) { this.verbosity = v; }

    // ------------------------------------------------------------------
    // Scan dependency jars for Kawa modules using ModuleInfo.find.
    // ------------------------------------------------------------------
    private void scanDependencyJars(String javaBin,
                                    org.gradle.api.file.FileCollection classpath,
                                    Set<String> precompiled) {
        try {
            // Collect jar files from the classpath (exclude kawa.jar itself).
            List<File> jars = new ArrayList<>();
            for (File f : classpath.getFiles()) {
                String name = f.getName();
                if (name.endsWith(".jar") && !name.contains("kawa")) {
                    jars.add(f);
                }
            }
            if (jars.isEmpty()) return;

            // Write the jar scan script to a temp file.
            File scanFile = File.createTempFile("kawa-jar-scan", ".scm");
            scanFile.deleteOnExit();
            Files.write(scanFile.toPath(), JAR_SCAN_SCRIPT.getBytes());

            for (File jar : jars) {
                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-cp");
                cmd.add(classpath.getAsPath());
                cmd.add("kawa.repl");
                cmd.add("-f");
                cmd.add(scanFile.getAbsolutePath());
                cmd.add(jar.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("JARMODULE ")) {
                        // JARMODULE <jarPath>: <moduleClassName>
                        int colon = line.indexOf(':');
                        String moduleName = line.substring(colon + 1).trim();
                        precompiled.add(moduleName);
                    }
                }
                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    getLogger().warn("kawa-forge: jar scan failed for " + jar.getName());
                }
            }
        } catch (Exception e) {
            getLogger().warn("kawa-forge: dependency jar scan error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Incremental compilation entry point
    // ------------------------------------------------------------------

    @TaskAction
    public void compile() {
        File outDir = schemeOutputDir.get().getAsFile();
        outDir.mkdirs();

        List<File> scmFiles = new ArrayList<>();
        collectScmFiles(schemeSourceDir, scmFiles);

        // Include the (kawaforge repl) module so mods can (import kawaforge.repl).
        File replFile = writeReplModule(outDir);
        scmFiles.add(replFile);

        // Include the (kawaforge mixin) DSL module so mods can (import (kawaforge mixin)).
        // It MUST be compiled in its own process before consumers to avoid lazy-compile macro issues.
        File mixinDslFile = writeMixinDslModule(outDir);
        scmFiles.add(mixinDslFile);

        if (scmFiles.isEmpty()) return;

        String javaBin = System.getProperty("java.home") + "/bin/java";
        org.gradle.api.file.FileCollection baseCp = mainSourceSet.getCompileClasspath();

        // ---- Scan for current dependency graph (always ~260ms, negligible) ----
        Map<File, String> fileToModule = new LinkedHashMap<>();
        Map<File, Set<String>> fileDeps = new LinkedHashMap<>();
        if ("verbose".equals(verbosity)) getLogger().lifecycle("  Scanning Kawa modules...");
        scanModules(javaBin, baseCp, scmFiles, fileToModule, fileDeps);

        // ---- Scan dependency jars for Kawa modules (multi-module support) ----
        Set<String> precompiledModules = new LinkedHashSet<>();
        scanDependencyJars(javaBin, baseCp, precompiledModules);

        Set<String> allModuleNames = new LinkedHashSet<>(fileToModule.values());
        for (Map.Entry<File, Set<String>> e : fileDeps.entrySet()) {
            String self = fileToModule.get(e.getKey());
            e.getValue().retainAll(allModuleNames);
            if (self != null) e.getValue().remove(self);
        }

        // ---- Determine which files need recompilation ----
        DepCache prevCache = loadCache(outDir);

        Set<File> dirtyFiles = computeDirtyFiles(scmFiles, fileToModule, fileDeps,
            prevCache, outDir);

        if (prevCache == null) {
            // First build — compile everything.
            dirtyFiles = new LinkedHashSet<>(scmFiles);
        }

        int skipped = scmFiles.size() - dirtyFiles.size();
        if (skipped > 0) {
            getLogger().lifecycle("  Kawa: " + dirtyFiles.size() + " file(s) to compile, "
                + skipped + " up-to-date");
        }

        // ---- Topological sort (all files, so ordering is correct) ----
        List<File> sorted = topologicalSort(scmFiles, fileToModule, fileDeps,
            precompiledModules);

        // ---- Compile only dirty files, in dependency order ----
        org.gradle.api.file.FileCollection cp = baseCp.plus(getProject().files(outDir));
        for (File f : sorted) {
            if (!dirtyFiles.contains(f)) continue; // skip clean

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-cp");
            cmd.add(cp.getAsPath());
            cmd.add("kawa.repl");
            cmd.add("--module-static-run");
            cmd.add("-d");
            cmd.add(outDir.getAbsolutePath());
            cmd.add("-C");
            cmd.add(f.getAbsolutePath());
            cmd.addAll(compilerArgs);

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    throw new GradleException(
                        "Kawa compilation failed for " + f.getName() + ":\n" + output);
                }
                switch (verbosity) {
                    case "verbose": getLogger().lifecycle(output.toString()); break;
                    case "quiet": /* nothing */ break;
                    default: getLogger().lifecycle("  Kawa compiled: " + f.getName());
                }
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException("Kawa compilation error for " + f.getName(), e);
            }
            cp = baseCp.plus(getProject().files(outDir));
        }

        // ---- Save current state for next build ----
        saveCache(outDir, fileToModule, fileDeps);
    }

    // ------------------------------------------------------------------
    // Compute the set of files that need recompilation.
    // Uses file timestamps + dependency-diffing against the cached state.
    // ------------------------------------------------------------------
    private Set<File> computeDirtyFiles(List<File> scmFiles,
                                         Map<File, String> fileToModule,
                                         Map<File, Set<String>> fileDeps,
                                         DepCache prevCache,
                                         File outDir) {
        Set<File> dirty = new LinkedHashSet<>();

        if (prevCache == null) return dirty;

        // 1. New files (not in cache) or files whose timestamp changed.
        for (File f : scmFiles) {
            Long prevTime = prevCache.fileTimes.get(f);
            if (prevTime == null || prevTime != f.lastModified()) {
                dirty.add(f);
            }
        }

        // 2. Files whose dependencies changed since last build.
        for (File f : scmFiles) {
            Set<String> curDeps = fileDeps.getOrDefault(f, Collections.emptySet());
            Set<String> prevDeps = prevCache.fileDeps.getOrDefault(f, Collections.emptySet());
            if (!curDeps.equals(prevDeps)) {
                dirty.add(f);
            }
        }

        // 3. Files that were removed — clean their stale .class files.
        for (File f : prevCache.fileTimes.keySet()) {
            if (!fileToModule.containsKey(f)) {
                String moduleName = prevCache.fileToModule.get(f);
                if (moduleName != null) {
                    String classPath = moduleName.replace('.', '/') + ".class";
                    File classFile = new File(outDir, classPath);
                    if (classFile.exists()) {
                        classFile.delete();
                        getLogger().lifecycle("  Kawa removed: " + f.getName());
                    }
                }
            }
        }

        // 4. Transitive closure: everything that depends on a dirty file.
        Set<File> closure = new LinkedHashSet<>(dirty);
        boolean changed;
        do {
            changed = false;
            for (File f : scmFiles) {
                if (closure.contains(f)) continue;
                Set<String> deps = fileDeps.getOrDefault(f, Collections.emptySet());
                for (String dep : deps) {
                    for (Map.Entry<File, String> e : fileToModule.entrySet()) {
                        if (dep.equals(e.getValue()) && closure.contains(e.getKey())) {
                            closure.add(f);
                            changed = true;
                            break;
                        }
                    }
                }
            }
        } while (changed);

        return closure;
    }

    // ------------------------------------------------------------------
    // Topological sort of all files by dependency order.
    // ------------------------------------------------------------------
    private List<File> topologicalSort(List<File> scmFiles,
                                        Map<File, String> fileToModule,
                                        Map<File, Set<String>> fileDeps,
                                        Set<String> precompiled) {
        Set<String> compiled = new LinkedHashSet<>(precompiled);
        List<File> sorted = new ArrayList<>();
        List<File> remaining = new ArrayList<>(scmFiles);

        while (!remaining.isEmpty()) {
            List<File> ready = new ArrayList<>();
            for (File f : remaining) {
                Set<String> deps = fileDeps.getOrDefault(f, Collections.emptySet());
                if (compiled.containsAll(deps)) ready.add(f);
            }
            if (ready.isEmpty()) {
                Set<String> unresolved = new LinkedHashSet<>();
                for (File f : remaining) {
                    Set<String> deps = fileDeps.getOrDefault(f, Collections.emptySet());
                    for (String d : deps) if (!compiled.contains(d)) unresolved.add(d);
                }
                List<String> names = new ArrayList<>();
                for (File f : remaining) names.add(f.getName());
                throw new GradleException(
                    "Circular or unsatisfied Kawa module dependencies.\n" +
                    "  Unresolved: " + unresolved + "\n" +
                    "  Files remaining: " + names);
            }
            ready.sort(Comparator.comparing(File::getName));
            sorted.addAll(ready);
            for (File f : ready) {
                String mn = fileToModule.get(f);
                if (mn != null) compiled.add(mn);
            }
            remaining.removeAll(ready);
        }
        return sorted;
    }

    // ------------------------------------------------------------------
    // Dependency cache (stored as a text file in the output directory).
    // ------------------------------------------------------------------

    private static class DepCache {
        final Map<File, String> fileToModule;
        final Map<File, Set<String>> fileDeps;
        final Map<File, Long> fileTimes;

        DepCache(Map<File, String> ftm, Map<File, Set<String>> fd, Map<File, Long> ft) {
            this.fileToModule = ftm;
            this.fileDeps = fd;
            this.fileTimes = ft;
        }
    }

    private DepCache loadCache(File outDir) {
        File cacheFile = new File(getProject().getBuildDir(), "kawa/.kawa-deps");
        if (!cacheFile.exists()) return null;

        try {
            Map<File, String> ftm = new LinkedHashMap<>();
            Map<File, Set<String>> fd = new LinkedHashMap<>();
            Map<File, Long> ft = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(cacheFile.toPath());
            for (String line : lines) {
                if (line.startsWith("MODULE:")) {
                    String rest = line.substring(7);
                    int eq = rest.indexOf('=');
                    if (eq > 0) {
                        ftm.put(new File(rest.substring(0, eq)), rest.substring(eq + 1));
                    }
                } else if (line.startsWith("DEPS:")) {
                    String rest = line.substring(5);
                    int eq = rest.indexOf('=');
                    if (eq > 0) {
                        File f = new File(rest.substring(0, eq));
                        String depsStr = rest.substring(eq + 1);
                        Set<String> deps = depsStr.isEmpty()
                            ? Collections.emptySet()
                            : new LinkedHashSet<>(Arrays.asList(depsStr.split(",")));
                        fd.put(f, deps);
                    }
                } else if (line.startsWith("TIME:")) {
                    String rest = line.substring(5);
                    int eq = rest.indexOf('=');
                    if (eq > 0) {
                        ft.put(new File(rest.substring(0, eq)), Long.parseLong(rest.substring(eq + 1)));
                    }
                }
            }
            return new DepCache(ftm, fd, ft);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private void saveCache(File outDir, Map<File, String> fileToModule,
                           Map<File, Set<String>> fileDeps) {
        File cacheFile = new File(getProject().getBuildDir(), "kawa/.kawa-deps");
        cacheFile.getParentFile().mkdirs();
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<File, String> e : fileToModule.entrySet()) {
                lines.add("MODULE:" + e.getKey().getAbsolutePath() + "=" + e.getValue());
            }
            for (Map.Entry<File, Set<String>> e : fileDeps.entrySet()) {
                lines.add("DEPS:" + e.getKey().getAbsolutePath() + "="
                    + String.join(",", e.getValue()));
            }
            for (Map.Entry<File, String> e : fileToModule.entrySet()) {
                lines.add("TIME:" + e.getKey().getAbsolutePath() + "=" + e.getKey().lastModified());
            }
            Files.write(cacheFile.toPath(), lines);
        } catch (IOException e) {
            getLogger().warn("kawa-forge: could not write dependency cache", e);
        }
    }

    // ------------------------------------------------------------------
    // Kawa scan subprocess
    // ------------------------------------------------------------------

    private void scanModules(String javaBin,
                             org.gradle.api.file.FileCollection classpath,
                             List<File> scmFiles,
                             Map<File, String> fileToModule,
                             Map<File, Set<String>> fileDeps) {
        try {
            File scanFile = File.createTempFile("kawa-scan", ".scm");
            scanFile.deleteOnExit();
            Files.write(scanFile.toPath(), SCAN_SCRIPT.getBytes());

            File listFile = File.createTempFile("kawa-files", ".txt");
            listFile.deleteOnExit();
            StringBuilder listContent = new StringBuilder();
            for (File f : scmFiles) {
                listContent.append(f.getAbsolutePath()).append('\n');
            }
            Files.write(listFile.toPath(), listContent.toString().getBytes());

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-cp");
            cmd.add(classpath.getAsPath());
            cmd.add("kawa.repl");
            cmd.add("-f");
            cmd.add(scanFile.getAbsolutePath());
            cmd.add(listFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MODULE ")) {
                    int colon = line.indexOf(':');
                    String path = line.substring(7, colon).trim();
                    String module = line.substring(colon + 1).trim();
                    fileToModule.put(new File(path), module);
                } else if (line.startsWith("IMPORT ")) {
                    int colon = line.indexOf(':');
                    String path = line.substring(7, colon).trim();
                    String dep = line.substring(colon + 1).trim();
                    File key = new File(path);
                    fileDeps.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(dep);
                } else if (line.startsWith("TYPEREF ")) {
                    int colon = line.indexOf(':');
                    String path = line.substring(8, colon).trim();
                    String ref = line.substring(colon + 1).trim();
                    File key = new File(path);
                    fileDeps.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(ref);
                }
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new GradleException("Kawa module scan failed with exit code " + exitCode);
            }
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("Kawa module scan error", e);
        }
    }

    // ------------------------------------------------------------------
    // Write the (kawaforge repl) module to a temp file so it gets
    // AOT-compiled alongside the mod's own sources.  The module ends up
    // in the jar as kawaforge/repl.class — mods import it with
    // (import kawaforge.repl) and call (start-repl!).
    // ------------------------------------------------------------------
    private File writeReplModule(File outDir) {
        try {
            // Write to build/tmp/ so the .scm source stays out of the jar.
            File dir = new File(getProject().getBuildDir(), "tmp/kawa-repl");
            dir.mkdirs();
            File replFile = new File(dir, "repl.scm");
            Files.write(replFile.toPath(), REPL_MODULE.getBytes());
            return replFile;
        } catch (IOException e) {
            throw new GradleException("Failed to write repl module", e);
        }
    }

    private File writeMixinDslModule(File outDir) {
        try {
            // Mirror repl module: write to build/tmp/ to keep source out of the jar.
            File dir = new File(getProject().getBuildDir(), "tmp/kawa-mixin-dsl");
            dir.mkdirs();
            File dslFile = new File(dir, "mixin-dsl.scm");
            
            // Read the DSL source from the plugin's resources.
            try (InputStream is = getClass().getResourceAsStream("/kawaforge/mixin.scm")) {
                if (is == null) throw new IOException("Could not find /kawaforge/mixin.scm in classpath");
                Files.copy(is, dslFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return dslFile;
        } catch (IOException e) {
            throw new GradleException("Failed to write mixin DSL module", e);
        }
    }

    private static void collectScmFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectScmFiles(f, out);
            else if (f.getName().endsWith(".scm")
                     && !f.getName().startsWith(".#")   // Emacs lock files
                     && !f.getName().startsWith("#")    // Emacs auto-save
                     && !f.getName().endsWith("~"))     // backup files
                out.add(f);
        }
    }
}
