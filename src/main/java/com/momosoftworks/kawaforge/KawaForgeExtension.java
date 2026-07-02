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
    private MixinConfigConfig mixin = new MixinConfigConfig();
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

    public MixinConfigConfig getMixin() { return mixin; }
    public void setMixin(MixinConfigConfig mixin) { this.mixin = mixin; }

    public void mixin(Action<? super MixinConfigConfig> action) {
        action.execute(mixin);
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

    // --- Mixin config ---------------------------------------------------

    public static class MixinConfigConfig {
        private boolean enabled = true;
        private String configName = "";
        private String mixinPackage = "";
        private boolean required = true;
        private String minVersion = "0.7.11";
        private String compatibilityLevel = "JAVA_8";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getConfigName() { return configName; }
        public void setConfigName(String configName) { this.configName = configName; }

        public String getMixinPackage() { return mixinPackage; }
        public void setMixinPackage(String mixinPackage) { this.mixinPackage = mixinPackage; }

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public String getMinVersion() { return minVersion; }
        public void setMinVersion(String minVersion) { this.minVersion = minVersion; }

        public String getCompatibilityLevel() { return compatibilityLevel; }
        public void setCompatibilityLevel(String compatibilityLevel) { this.compatibilityLevel = compatibilityLevel; }
    }
}
