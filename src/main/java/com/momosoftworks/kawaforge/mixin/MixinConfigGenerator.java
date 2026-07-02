package com.momosoftworks.kawaforge.mixin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

public final class MixinConfigGenerator {

    public static class MixinProcessingException extends RuntimeException {
        public MixinProcessingException(String message) {
            super(message);
        }
    }

    /** Scans a classes dir for classes bearing Lorg/spongepowered/asm/mixin/Mixin; (either visibility). @return binary class names, sorted. */
    public static List<String> findMixinClasses(Path classesDir) throws IOException {
        if (!Files.exists(classesDir)) {
            return Collections.emptyList();
        }

        List<String> mixins = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(classesDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                  .forEach(p -> {
                      try {
                          if (isMixinClass(p)) {
                              String className = getClassName(classesDir, p);
                              if (className != null) {
                                  mixins.add(className);
                              }
                          }
                      } catch (IOException e) {
                          throw new RuntimeException("Failed to read class " + p, e);
                      }
                  });
        }
        Collections.sort(mixins);
        return mixins;
    }

    private static boolean isMixinClass(Path classFile) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            ClassReader reader = new ClassReader(bytes);
            final boolean[] isMixin = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                public void visitAnnotation(int descriptor, boolean visible) {
                    if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                        isMixin[0] = true;
                    }
                }
            }, 0);
            return isMixin[0];
        } catch (Exception e) {
            return false;
        }
    }

    private static String getClassName(Path root, Path file) {
        Path relative = root.relativize(file);
        String pathStr = relative.toString().replace(FileSystems.getDefault().getSeparator(), ".");
        if (pathStr.endsWith(".class")) {
            pathStr = pathStr.substring(0, pathStr.length() - 6);
        }
        return pathStr;
    }

    /** Renders the config JSON. All mixin class names must live under mixinPackage (direct or nested); otherwise throw MixinProcessingException naming the offenders. Relative names keep nested subpackage prefixes (e.g. "client.MixinFoo"). */
    public static String render(String mixinPackage, List<String> mixinClassNames, boolean required, String minVersion, String compatibilityLevel) {
        List<String> relativeNames = new ArrayList<>();
        for (String fullClassName : mixinClassNames) {
            if (!fullClassName.startsWith(mixinPackage + ".")) {
                throw new MixinProcessingException("Class " + fullClassName + " is not in the specified mixin package " + mixinPackage);
            }
            String relative = fullClassName.substring(mixinPackage.length() + 1);
            relativeNames.add(relative);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"required\": ").append(required).append(",\n");
        json.append("  \"minVersion\": \"").append(minVersion).append("\",\n");
        json.append("  \"package\": \"").append(mixinPackage).append("\",\n");
        json.append("  \"compatibilityLevel\": \"").append(compatibilityLevel).append("\",\n");
        json.append("  \"mixins\": [");
        for (int i = 0; i < relativeNames.size(); i++) {
            json.append("\n    \"").append(relativeNames.get(i)).append("\"");
            if (i < relativeNames.size() - 1) {
                json.append(",");
            }
        }
        if (!relativeNames.isEmpty()) {
            json.append("\n  ]");
        } else {
            json.append("]");
        }
        json.append("\n}");
        return json.toString();
    }
}
