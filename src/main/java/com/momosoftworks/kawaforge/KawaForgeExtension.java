package com.momosoftworks.kawaforge;

import org.gradle.api.Action;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Configuration for the Kawa Forge Gradle plugin. */
public class KawaForgeExtension {
    private File sourceDir = new File("src/main/scheme");
    private KawaRuntimeConfig runtime = new KawaRuntimeConfig();
    private KawaReplConfig repl = new KawaReplConfig();
    private List<String> compilerArgs = new ArrayList<>();
    private String verbosity = "normal";

    public File getSourceDir() { return sourceDir; }
    public void setSourceDir(File sourceDir) { this.sourceDir = sourceDir; }

    public KawaRuntimeConfig getRuntime() { return runtime; }
    public void setRuntime(KawaRuntimeConfig runtime) { this.runtime = runtime; }

    public void runtime(Action<? super KawaRuntimeConfig> action) {
        action.execute(runtime);
    }

    public KawaReplConfig getRepl() { return repl; }
    public void setRepl(KawaReplConfig repl) { this.repl = repl; }

    public void repl(Action<? super KawaReplConfig> action) {
        action.execute(repl);
    }

    public List<String> getCompilerArgs() { return compilerArgs; }
    public void setCompilerArgs(List<String> compilerArgs) { this.compilerArgs = compilerArgs; }

    public String getVerbosity() { return verbosity; }
    public void setVerbosity(String verbosity) { this.verbosity = verbosity; }

    // --- Runtime config -------------------------------------------------

    public static class KawaRuntimeConfig {
        private File local;
        private String maven;
        private boolean guix;

        public File getLocal() { return local; }
        public void setLocal(File local) { this.local = local; }

        public String getMaven() { return maven; }
        public void setMaven(String maven) { this.maven = maven; }

        public boolean getGuix() { return guix; }
        public void setGuix(boolean guix) { this.guix = guix; }
    }

    // --- REPL config ----------------------------------------------------

    public static class KawaReplConfig {
        private int port = 4242;
        private int standalonePort = 4243;

        /** TCP port for the in-game REPL server.  0 = disable. */
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        /** TCP port for the ./gradlew kawaRepl standalone process. */
        public int getStandalonePort() { return standalonePort; }
        public void setStandalonePort(int standalonePort) { this.standalonePort = standalonePort; }
    }
}
