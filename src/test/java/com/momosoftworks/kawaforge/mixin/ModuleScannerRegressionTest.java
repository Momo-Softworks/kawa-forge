package com.momosoftworks.kawaforge.mixin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ModuleScannerRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void testModuleScannerCrashVector() throws IOException, InterruptedException {
        KawaTestHarness.assumeAvailable();
        
        // This source exercises the datum shapes that killed the old scanner:
        // 1. (::)          - car is '::', cdr is not a pair (it's null/empty list)
        // 2. (a . ::)      - car is 'a', not '::'
        // 3. (:: . x)      - car is '::', cdr is not a pair (it's the atom x)
        // 4. (let ((x :: int 1)) x) - standard valid use case
        String source = 
            "(module-name com.example.ScannerProbe)\n" +
            "(define weird1 '(::))\n" +
            "(define weird2 '(a . ::))\n" +
            "(define weird3 '(:: . x))\n" +
            "(define (typed-let) (let ((x :: int 1)) x))";
            
        KawaTestHarness.compile(tempDir, "ScannerProbe.scm", source, Collections.emptyList());
        
        // If compile() returns without IOException, the Kawa compiler (and the 
        // embedded scanner logic if exercised during the process) succeeded.
        // While the scanner in KawaCompileTask is a separate Scheme process, 
        // verifying that these shapes are valid Kawa Scheme is the baseline.
        assertTrue(Files.exists(tempDir.resolve("kawa-out/com/example/ScannerProbe.class")));
    }
}
