package com.momosoftworks.kawaforge.mixin.meta;

import java.util.*;

public final class PayloadReader {
    private PayloadReader() {}

    public static List<VAnnotation> parse(String payload) {
        if (payload == null) throw new PayloadException("Payload cannot be null", 0);
        
        Tokenizer tokenizer = new Tokenizer(payload);
        List<VAnnotation> annotations = new ArrayList<>();
        
        while (tokenizer.hasNext()) {
            annotations.add(parseAnnotation(tokenizer));
        }
        
        if (annotations.isEmpty()) {
            throw new PayloadException("Payload must contain at least one annotation form", 0);
        }
        
        return annotations;
    }

    private static VAnnotation parseAnnotation(Tokenizer tok) {
        tok.consume('(');
        
        String head = tok.next();
        if (head == null || !"@".equals(head)) {
            throw new PayloadException("Expected '@' symbol at start of annotation form, found: " + head, tok.currentOffset());
        }
        
        String typeName = tok.next();
        if (typeName == null || !isSymbol(typeName)) {
            throw new PayloadException("Expected type symbol after '@', found: " + typeName, tok.currentOffset());
        }
        
        LinkedHashMap<String, AnnValue> members = new LinkedHashMap<>();
        while (tok.peek() != null && !tok.peek().equals(")")) {
            tok.consume('(');
            String name = tok.next();
            if (name == null || !isSymbol(name)) {
                throw new PayloadException("Expected member name symbol, found: " + name, tok.currentOffset());
            }
            if (members.containsKey(name)) {
                throw new PayloadException("Duplicate member name: " + name, tok.currentOffset());
            }
            
            List<AnnValue> values = new ArrayList<>();
            while (tok.peek() != null && !tok.peek().equals(")")) {
                values.add(parseValue(tok));
            }
            tok.consume(')');
            
            if (values.isEmpty()) {
                throw new PayloadException("Member " + name + " must have at least one value", tok.currentOffset());
            }
            
            AnnValue finalVal = values.size() == 1 ? values.get(0) : new VArray(values);
            members.put(name, finalVal);
        }
        
        tok.consume(')');
        return new VAnnotation(typeName, members, null);
    }

    private static AnnValue parseValue(Tokenizer tok) {
        String peek = tok.peek();
        if (peek == null) throw new PayloadException("Unexpected end of input while parsing value", tok.currentOffset());
        
        if (peek.equals("(")) {
            tok.consume('(');
            String head = tok.next();
            if (head == null) throw new PayloadException("Unexpected end of input after '('", tok.currentOffset());
            
            if ("@".equals(head)) {
                // Nested annotation
                String typeName = tok.next();
                if (typeName == null || !isSymbol(typeName)) {
                    throw new PayloadException("Expected type symbol after '@', found: " + typeName, tok.currentOffset());
                }
                
                LinkedHashMap<String, AnnValue> members = new LinkedHashMap<>();
                while (tok.peek() != null && !tok.peek().equals(")")) {
                    tok.consume('(');
                    String name = tok.next();
                    if (name == null || !isSymbol(name)) {
                        throw new PayloadException("Expected member name symbol, found: " + name, tok.currentOffset());
                    }
                    if (members.containsKey(name)) {
                        throw new PayloadException("Duplicate member name: " + name, tok.currentOffset());
                    }
                    
                    List<AnnValue> values = new ArrayList<>();
                    while (tok.peek() != null && !tok.peek().equals(")")) {
                        values.add(parseValue(tok));
                    }
                    tok.consume(')');
                    
                    if (values.isEmpty()) {
                        throw new PayloadException("Member " + name + " must have at least one value", tok.currentOffset());
                    }
                    
                    AnnValue finalVal = values.size() == 1 ? values.get(0) : new VArray(values);
                    members.put(name, finalVal);
                }
                tok.consume(')');
                return new VAnnotation(typeName, members, null);
            } else if ("class".equals(head)) {
                String type = tok.next();
                if (type == null || !isSymbol(type)) throw new PayloadException("Expected class type symbol, found: " + type, tok.currentOffset());
                tok.consume(')');
                return new VClass(type);
            } else if ("enum".equals(head)) {
                String type = tok.next();
                if (type == null || !isSymbol(type)) throw new PayloadException("Expected enum type symbol, found: " + type, tok.currentOffset());
                String constant = tok.next();
                if (constant == null || !isSymbol(constant)) throw new PayloadException("Expected enum constant symbol, found: " + constant, tok.currentOffset());
                tok.consume(')');
                return new VEnum(type, constant);
            } else if ("array".equals(head)) {
                List<AnnValue> values = new ArrayList<>();
                while (tok.peek() != null && !tok.peek().equals(")")) {
                    values.add(parseValue(tok));
                }
                tok.consume(')');
                return new VArray(values);
            } else {
                throw new PayloadException("Unexpected form head: " + head, tok.currentOffset());
            }
        } else {
            String tokVal = tok.next();
            if (tokVal == null) throw new PayloadException("Unexpected end of input", tok.currentOffset());
            
            if (tokVal.startsWith("\"")) {
                return new VPrim(tokenizerUnquote(tokVal));
            } else if (tokVal.startsWith("#")) {
                return parseSpecial(tokVal);
            } else if (isDigitOrSign(tokVal.charAt(0))) {
                return parseNumber(tokVal);
            } else {
                throw new PayloadException("Unexpected symbol as value: " + tokVal, tok.currentOffset());
            }
        }
    }

    private static String tokenizerUnquote(String s) {
        // s is like "\"hello\\nworld\""
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length() - 1) throw new PayloadException("Unterminated escape sequence", 0);
                char esc = s.charAt(++i);
                switch (esc) {
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: throw new PayloadException("Invalid escape sequence \\" + esc, 0);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static AnnValue parseSpecial(String s) {
        if (s.equals("#t")) return new VPrim(Boolean.TRUE);
        if (s.equals("#f")) return new VPrim(Boolean.FALSE);
        if (s.startsWith("#\\") && s.length() == 3) {
            return new VPrim(s.charAt(2));
        }
        throw new PayloadException("Invalid special token: " + s, 0);
    }

    private static AnnValue parseNumber(String s) {
        try {
            if (s.contains(".") || s.toLowerCase().contains("e")) {
                return new VPrim(Double.parseDouble(s));
            } else {
                return new VPrim(Long.parseLong(s));
            }
        } catch (NumberFormatException e) {
            throw new PayloadException("Invalid number format: " + s, 0);
        }
    }

    private static boolean isSymbol(String s) {
        return s != null && (s.equals("@") || s.matches("[A-Za-z_$@][A-Za-z0-9_$.]*"));
    }

    private static boolean isDigitOrSign(char c) {
        return Character.isDigit(c) || c == '-';
    }

    private static class Tokenizer {
        private final String input;
        private int pos = 0;

        Tokenizer(String input) { this.input = input; }

        boolean hasNext() {
            skipWhitespaceAndComments();
            return pos < input.length();
        }

        String peek() {
            skipWhitespaceAndComments();
            if (pos >= input.length()) return null;
            char c = input.charAt(pos);
            if (c == '(') return "(";
            if (c == ')') return ")";
            if (c == '"') return "\"";
            if (c == '#') return "#";
            if (isSymbolStart(c)) return "SYM";
            if (isDigitOrSign(c)) return "NUM";
            return "UNK";
        }
        
        int currentOffset() { return pos; }

        void consume(char expected) {
            skipWhitespaceAndComments();
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new PayloadException("Expected '" + expected + "'", pos);
            }
            pos++;
        }

        String next() {
            skipWhitespaceAndComments();
            if (pos >= input.length()) return null;
            
            char c = input.charAt(pos);
            if (c == '(') { pos++; return "("; }
            if (c == ')') { pos++; return ")"; }
            if (c == '"') return readString();
            if (c == '#') return readSpecial();
            if (isSymbolStart(c)) return readSymbol();
            if (isDigitOrSign(c)) return readNumber();
            
            throw new PayloadException("Unexpected character: " + c, pos);
        }

        private void skipWhitespaceAndComments() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == ';') {
                    while (pos < input.length() && input.charAt(pos) != '\n') pos++;
                } else {
                    break;
                }
            }
        }

        private String readString() {
            int start = pos;
            StringBuilder sb = new StringBuilder("\"");
            pos++; // skip opening quote
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '"') {
                    sb.append('"');
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    sb.append('\\');
                    pos++;
                    if (pos >= input.length()) throw new PayloadException("Unterminated escape sequence", pos);
                    char esc = input.charAt(pos);
                    sb.append(esc);
                    pos++;
                } else if (c == '\n' || c == '\r') {
                    throw new PayloadException("Raw newline in string", pos);
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new PayloadException("Unterminated string", start);
        }

        private String readSpecial() {
            int start = pos;
            pos++; // skip #
            if (pos >= input.length()) throw new PayloadException("Incomplete special token", pos);
            char c = input.charAt(pos);
            if (c == 't') { pos++; return "#t"; }
            if (c == 'f') { pos++; return "#f"; }
            if (c == '\\') {
                pos++;
                if (pos >= input.length()) throw new PayloadException("Incomplete character literal", pos);
                char ch = input.charAt(pos);
                if (Character.isWhitespace(ch)) throw new PayloadException("Character literal cannot be space", pos);
                pos++;
                return "#\\" + ch;
            }
            throw new PayloadException("Invalid special token", pos);
        }

        private String readSymbol() {
            int start = pos;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (isSymbolStart(c) || isSymbolPart(c)) {
                    pos++;
                } else {
                    break;
                }
            }
            return input.substring(start, pos);
        }

        private String readNumber() {
            int start = pos;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+') {
                    pos++;
                } else {
                    break;
                }
            }
            return input.substring(start, pos);
        }

        private boolean isSymbolStart(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '$' || c == '@';
        }
        private boolean isSymbolPart(char c) {
            return isSymbolStart(c) || Character.isDigit(c) || c == '.';
        }
        private boolean isDigitOrSign(char c) {
            return Character.isDigit(c) || c == '-';
        }
    }
}
