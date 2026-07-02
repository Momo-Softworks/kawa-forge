package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the literal-pooling discovery from the Patina field test:
 *
 * <p>Scheme literals in handler bodies (e.g. {@code (display "...")}) compile
 * to GETSTATIC references to pooled {@code Lit*} fields of gnu.* types on the
 * module class, whose {@code <clinit>} Sponge Mixin will not run from merged
 * code. The processor must WARN about those. String literals flowing into
 * String-typed Java APIs — or wrapped in the DSL's {@code (jstr ...)} — emit
 * plain {@code ldc} constants and must NOT warn.
 */
class KawaLiteralPoolingTest {

    private static Path dslClasses;
    private static Path annotationClasses;
    private static Path fixtureClasses;

    @TempDir
    Path tmp;

    @BeforeAll
    static void compileDsl(@TempDir Path dslTmp) throws Exception {
        KawaTestHarness.assumeAvailable();
        String dslSource = new String(
            Files.readAllBytes(Paths.get("src/main/resources/kawaforge/mixin.scm")),
            StandardCharsets.UTF_8);
        dslClasses = KawaTestHarness.compile(dslTmp, "mixin.scm", dslSource, Arrays.asList());
        annotationClasses = codeSource(KawaMixinMeta.class);
        fixtureClasses = codeSource(FixtureMixin.class);
    }

    @Test
    void pooledLiteralInHandlerTriggersWarning() throws Exception {
        List<String> warnings = processMixin("pooled",
            "(define-mixin com.example.mixins.PooledLit\n"
            + "  (target \"a.B\")\n"
            + "  (inject h ((ci :: java.lang.Object))\n"
            + "    (method \"m\") (at \"HEAD\")\n"
            + "    (display \">>> HELLO <<<\")))\n",
            "com/example/mixins/PooledLit.class");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("pooled literal")),
            "expected a pooled-literal warning, got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("jstr")),
            "warning must point at the jstr helper");
    }

    @Test
    void stringTypedApiAndJstrAreClean() throws Exception {
        List<String> warnings = processMixin("clean",
            "(define-mixin com.example.mixins.CleanLit\n"
            + "  (target \"a.B\")\n"
            + "  (inject h ((ci :: java.lang.Object))\n"
            + "    (method \"m\") (at \"HEAD\")\n"
            + "    (java.lang.System:out:println \">>> DIRECT <<<\")\n"
            + "    (java.lang.System:out:println (jstr \">>> WRAPPED <<<\"))))\n",
            "com/example/mixins/CleanLit.class");
        assertTrue(warnings.isEmpty(), "expected no warnings, got: " + warnings);
    }

    /** Compiles a consumer using the DSL, processes the named class, returns warnings. */
    private List<String> processMixin(String workDirName, String mixinForms, String classFile)
            throws Exception {
        String source = "(module-name com.example.Module" + workDirName + ")\n"
            + "(import (kawaforge mixin))\n"
            + mixinForms;
        Path work = Files.createDirectories(tmp.resolve(workDirName));
        Path out = KawaTestHarness.compile(work, workDirName + ".scm", source,
            Arrays.asList(annotationClasses, dslClasses));

        // define-mixin payloads use the Mixin/Inject/At aliases, which the
        // normalizer resolves against org.spongepowered classes — provide
        // synthesized stubs with matching shapes (no Sponge dependency).
        Path stubs = SpongeStubs.ensure(tmp.resolve("sponge-stubs"));
        MixinClassProcessor processor =
            new MixinClassProcessor(Arrays.asList(stubs, fixtureClasses));
        byte[] processed = processor.process(Files.readAllBytes(out.resolve(classFile)));
        assertNotNull(processed, "class should carry mixin carriers");
        return processor.warnings();
    }

    private static Path codeSource(Class<?> cls) throws Exception {
        return Paths.get(cls.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
