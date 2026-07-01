package com.momosoftworks.kawaforge.mixin.meta;

import org.objectweb.asm.Type;
import java.util.*;

public final class Normalizer {
    private final AnnotationDefReader reader;

    public Normalizer(AnnotationDefReader reader) {
        this.reader = reader;
    }

    public VAnnotation normalize(VAnnotation raw) {
        String resolvedName = Aliases.resolve(raw.typeName);
        AnnotationDef def = reader.load(resolvedName);
        
        LinkedHashMap<String, AnnValue> normalizedMembers = new LinkedHashMap<>();
        
        for (Map.Entry<String, AnnValue> entry : raw.members.entrySet()) {
            String name = entry.getKey();
            AnnValue value = entry.getValue();
            
            if (!def.members.containsKey(name)) {
                throw new NormalizationException(
                    "Unknown member '" + name + "' for annotation " + resolvedName + 
                    ". Valid members: " + def.members.keySet()
                );
            }
            
            MemberDef memberDef = def.members.get(name);
            normalizedMembers.put(name, normalizeValue(name, memberDef, value));
        }
        
        return new VAnnotation(def.descriptor, normalizedMembers, def.runtimeVisible);
    }

    private AnnValue normalizeValue(String memberName, MemberDef memberDef, AnnValue value) {
        Type type = memberDef.type;
        boolean isArray = type.getSort() == Type.ARRAY;

        if (isArray) {
            AnnValue arrayValue = value;
            if (!(value instanceof VArray)) {
                arrayValue = new VArray(Collections.singletonList(value));
            }
            
            if (!(arrayValue instanceof VArray)) {
                throw new NormalizationException("Member '" + memberName + "' expected array, but got " + value);
            }
            
            VArray vArray = (VArray) arrayValue;
            List<AnnValue> normalizedElements = new ArrayList<>();
            Type elementType = type.getElementType();
            
            MemberDef elementDef = new MemberDef(memberName + "[]", elementType);
            for (AnnValue element : vArray.values) {
                normalizedElements.add(normalizeScalar(memberName, elementDef, element));
            }
            return new VArray(normalizedElements);
        } else {
            if (value instanceof VArray) {
                throw new NormalizationException("Member '" + memberName + "' expected scalar, but got array");
            }
            return normalizeScalar(memberName, memberDef, value);
        }
    }

    private AnnValue normalizeScalar(String memberName, MemberDef memberDef, AnnValue value) {
        Type type = memberDef.type;
        
        if (value instanceof VPrim) {
            VPrim vprim = (VPrim) value;
            Object val = vprim.value;
            
            if (type.getSort() == Type.BOOLEAN) {
                if (!(val instanceof Boolean)) {
                    throw new NormalizationException("Member '" + memberName + "' expected boolean, got " + val);
                }
                return vprim;
            } else if (type.getSort() == Type.CHAR) {
                if (!(val instanceof Character)) {
                    throw new NormalizationException("Member '" + memberName + "' expected char, got " + val);
                }
                return vprim;
            } else if (type.getSort() == Type.OBJECT && "java/lang/String".equals(type.getInternalName())) {
                if (!(val instanceof String)) {
                    throw new NormalizationException("Member '" + memberName + "' expected String, got " + val);
                }
                return vprim;
            } else if (type.getSort() == Type.INT || type.getSort() == Type.SHORT || 
                       type.getSort() == Type.BYTE || type.getSort() == Type.LONG) {
                if (!(val instanceof Long)) {
                    throw new NormalizationException("Member '" + memberName + "' expected numeric, got " + val);
                }
                long lval = (Long) val;
                
                if (type.getSort() == Type.BYTE && (lval < Byte.MIN_VALUE || lval > Byte.MAX_VALUE)) {
                    throw new NormalizationException("Member '" + memberName + "' value " + lval + " out of range for byte");
                }
                if (type.getSort() == Type.SHORT && (lval < Short.MIN_VALUE || lval > Short.MAX_VALUE)) {
                    throw new NormalizationException("Member '" + memberName + "' value " + lval + " out of range for short");
                }
                if (type.getSort() == Type.INT && (lval < Integer.MIN_VALUE || lval > Integer.MAX_VALUE)) {
                    throw new NormalizationException("Member '" + memberName + "' value " + lval + " out of range for int");
                }
                
                if (type.getSort() == Type.BYTE) return new VPrim((byte)lval);
                if (type.getSort() == Type.SHORT) return new VPrim((short)lval);
                if (type.getSort() == Type.INT) return new VPrim((int)lval);
                return vprim;
            } else if (type.getSort() == Type.FLOAT || type.getSort() == Type.DOUBLE) {
                if (!(val instanceof Double)) {
                    throw new NormalizationException("Member '" + memberName + "' expected float/double, got " + val);
                }
                double dval = (Double) val;
                if (type.getSort() == Type.FLOAT) {
                    return new VPrim((float)dval);
                }
                return vprim;
            }
            throw new NormalizationException("Member '" + memberName + "' type mismatch: declared " + type + ", given " + val);
        } else if (value instanceof VClass) {
            if (type.getSort() != Type.OBJECT || !"java/lang/Class".equals(type.getInternalName())) {
                throw new NormalizationException("Member '" + memberName + "' expected Class, got VClass");
            }
            return new VClass(Aliases.resolveTypeName(((VClass) value).typeName));
        } else if (value instanceof VEnum) {
            if (type.getSort() != Type.OBJECT) {
                throw new NormalizationException("Member '" + memberName + "' expected Enum, got VEnum");
            }
            String resolvedEnumName = Aliases.resolveTypeName(((VEnum) value).typeName);
            
            String memberBinaryName = type.getInternalName().replace('/', '.');
            if (!memberBinaryName.equals(((VEnum) value).typeName)) {
                 String rawType = ((VEnum) value).typeName;
                 String resolvedRaw = Aliases.resolve(rawType);
                 if (!memberBinaryName.equals(resolvedRaw)) {
                     throw new NormalizationException("Member '" + memberName + "' expected enum " + memberBinaryName + ", but got " + resolvedRaw);
                 }
            }
            
            return new VEnum(resolvedEnumName, ((VEnum) value).constant);
        } else if (value instanceof VAnnotation) {
            if (type.getSort() != Type.OBJECT) {
                throw new NormalizationException("Member '" + memberName + "' expected Annotation, got VAnnotation");
            }
            
            VAnnotation rawNested = (VAnnotation) value;
            VAnnotation normalizedNested = normalize(rawNested);
            
            if (!normalizedNested.typeName.equals(type.getDescriptor())) {
                throw new NormalizationException("Member '" + memberName + "' expected annotation " + type.getDescriptor() + ", but got " + normalizedNested.typeName);
            }
            
            return new VAnnotation(normalizedNested.typeName, normalizedNested.members, null);
        }
        
        throw new NormalizationException("Member '" + memberName + "' value is of unsupported type: " + value.getClass());
    }
}
