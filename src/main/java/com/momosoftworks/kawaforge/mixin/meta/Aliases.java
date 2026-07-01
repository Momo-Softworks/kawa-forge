package com.momosoftworks.kawaforge.mixin.meta;

import java.util.*;

public final class Aliases {
    private static final Map<String, String> ALIAS_TABLE = new HashMap<>();

    static {
        ALIAS_TABLE.put("Mixin", "org.spongepowered.asm.mixin.Mixin");
        ALIAS_TABLE.put("Pseudo", "org.spongepowered.asm.mixin.Pseudo");
        ALIAS_TABLE.put("Shadow", "org.spongepowered.asm.mixin.Shadow");
        ALIAS_TABLE.put("Unique", "org.spongepowered.asm.mixin.Unique");
        ALIAS_TABLE.put("Final", "org.spongepowered.asm.mixin.Final");
        ALIAS_TABLE.put("Mutable", "org.spongepowered.asm.mixin.Mutable");
        ALIAS_TABLE.put("Overwrite", "org.spongepowered.asm.mixin.Overwrite");
        ALIAS_TABLE.put("Debug", "org.spongepowered.asm.mixin.Debug");
        ALIAS_TABLE.put("Dynamic", "org.spongepowered.asm.mixin.Dynamic");
        ALIAS_TABLE.put("Intrinsic", "org.spongepowered.asm.mixin.Intrinsic");
        ALIAS_TABLE.put("Inject", "org.spongepowered.asm.mixin.injection.Inject");
        ALIAS_TABLE.put("At", "org.spongepowered.asm.mixin.injection.At");
        ALIAS_TABLE.put("Slice", "org.spongepowered.asm.mixin.injection.Slice");
        ALIAS_TABLE.put("Redirect", "org.spongepowered.asm.mixin.injection.Redirect");
        ALIAS_TABLE.put("ModifyArg", "org.spongepowered.asm.mixin.injection.ModifyArg");
        ALIAS_TABLE.put("ModifyArgs", "org.spongepowered.asm.mixin.injection.ModifyArgs");
        ALIAS_TABLE.put("ModifyVariable", "org.spongepowered.asm.mixin.injection.ModifyVariable");
        ALIAS_TABLE.put("ModifyConstant", "org.spongepowered.asm.mixin.injection.ModifyConstant");
        ALIAS_TABLE.put("Constant", "org.spongepowered.asm.mixin.injection.Constant");
        ALIAS_TABLE.put("Surrogate", "org.spongepowered.asm.mixin.injection.Surrogate");
        ALIAS_TABLE.put("Coerce", "org.spongepowered.asm.mixin.injection.Coerce");
        ALIAS_TABLE.put("Group", "org.spongepowered.asm.mixin.injection.Group");
        ALIAS_TABLE.put("Accessor", "org.spongepowered.asm.mixin.gen.Accessor");
        ALIAS_TABLE.put("Invoker", "org.spongepowered.asm.mixin.gen.Invoker");
    }

    public static String resolve(String typeSymbol) {
        if (typeSymbol != null && typeSymbol.contains(".")) {
            return typeSymbol;
        }
        String resolved = ALIAS_TABLE.get(typeSymbol);
        if (resolved == null) {
            throw new NormalizationException("Unknown annotation alias: " + typeSymbol);
        }
        return resolved;
    }

    public static String resolveTypeName(String typeSymbol) {
        if (typeSymbol == null) return null;

        switch (typeSymbol) {
            case "boolean": return "Z";
            case "byte": return "B";
            case "short": return "S";
            case "int": return "I";
            case "long": return "J";
            case "float": return "F";
            case "double": return "D";
            case "char": return "C";
            case "void": return "V";
        }

        if (typeSymbol.contains(".")) {
            return "L" + typeSymbol.replace('.', '/') + ";";
        }

        String alias = ALIAS_TABLE.get(typeSymbol);
        if (alias != null) {
            return "L" + alias.replace('.', '/') + ";";
        }

        throw new NormalizationException("Unknown type symbol: " + typeSymbol);
    }
}
