package com.momosoftworks.kawaforge.mixin;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Locates a real Kawa compiler jar and AOT-compiles Scheme fixtures for tests.
 *
 * <p>Resolution order: {@code KAWA_TEST_JAR} env var, then {@code guix build kawa}
 * (cheap when the store already has it). Tests must call {@link #assumeAvailable()}
 * first so environments without Kawa SKIP instead of failing.
 */
public final class KawaTestHarness {

    private static volatile Path cachedJar;
    private static volatile boolean resolved;

    private KawaTestHarness() {
    }

    public static synchronized Path kawaJar() {
        if (!resolved) {
            resolved = true;
            cachedJar = locate();
        }
        return cachedJar;
    }

    public static void assumeAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(kawaJar() != null,
            "Kawa jar not available (set KAWA_TEST_JAR or make `guix build kawa` work); skipping");
    }

    private static Path locate() {
        String env = System.getenv("KAWA_TEST_JAR");
        if (env != null && !env.isEmpty()) {
            Path p = Paths.get(env);
            return Files.isRegularFile(p) ? p : null;
        }
        try {
            Process proc = new ProcessBuilder("guix", "build", "kawa").start();
            String last = null;
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    last = line;
                }
            }
            if (!proc.waitFor(180, TimeUnit.SECONDS) || proc.exitValue() != 0 || last == null) {
                return null;
            }
            Path jar = Paths.get(last, "share", "kawa", "lib", "kawa.jar");
            return Files.isRegularFile(jar) ? jar : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * AOT-compiles one .scm source with {@code kawa.repl -d <out> -C <file>}.
     * The given classpath entries are appended after kawa.jar.
     *
     * @return the output directory containing the emitted .class files
     * @throws IOException on non-zero exit, with the full compiler output in the message
     */
    public static Path compile(Path workDir, String fileName, String source, List<Path> classpath)
            throws IOException, InterruptedException {
        Path scm = workDir.resolve(fileName);
        Files.write(scm, source.getBytes(StandardCharsets.UTF_8));
        Path outDir = workDir.resolve("kawa-out");
        Files.createDirectories(outDir);

        StringBuilder cp = new StringBuilder(kawaJar().toString());
        for (Path p : classpath) {
            cp.append(File.pathSeparatorChar).append(p);
        }

        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add("kawa.repl");
        cmd.add("-d");
        cmd.add(outDir.toString());
        cmd.add("-C");
        cmd.add(scm.toString());

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(readAll(proc.getInputStream()), StandardCharsets.UTF_8);
        if (!proc.waitFor(180, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("kawa compile timed out:\n" + output);
        }
        if (proc.exitValue() != 0) {
            throw new IOException("kawa compile failed (exit " + proc.exitValue() + "):\n" + output);
        }
        return outDir;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
