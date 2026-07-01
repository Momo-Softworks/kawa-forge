# Kawa Mixin Carrier Payload — Grammar Specification (v0)

**Status: FROZEN for Phase 0.** Any change to this grammar requires bumping the
version in this document and updating the parser, normalizer, and all fixtures
in the same change.

## 1. Purpose

Kawa's annotation syntax cannot express array-typed JVM annotation members
(e.g. `@Mixin.targets() : String[]`, `@Inject.at() : At[]`), which SpongePowered
Mixin relies on heavily. Kawa *can* emit a scalar `String` annotation member.

This spec defines the contract between:

- **Producers** — Kawa source (hand-written in Phase 0, `define-mixin`
  macro-generated in Phase 1) that attaches *carrier annotations* whose single
  `String` payload encodes the desired real annotations, and
- **The consumer** — the `processKawaMixins` Gradle task, which parses the
  payload, normalizes it, emits the real JVM annotations into the compiled
  `.class` file with ASM, and strips the carriers.

The payload grammar is **generic**: it describes arbitrary JVM annotation
trees. Nothing in this layer is Mixin-specific except the alias table (§6.1).

## 2. Carrier annotations

Provided by the `kawa-mixin-annotations` artifact (added to the consumer's
`compileOnly` classpath by the plugin):

| Annotation | Target | Retention | Member |
|---|---|---|---|
| `com.momosoftworks.kawaforge.mixin.KawaMixinMeta` | `TYPE` | `CLASS` | `String value()` |
| `com.momosoftworks.kawaforge.mixin.KawaMemberMeta` | `METHOD`, `FIELD` | `CLASS` | `String value()` |

Both are removed from the output class by the post-processor. They never exist
at runtime.

Kawa usage example:

```scheme
(define-simple-class MixinMinecraft ()
  (@com.momosoftworks.kawaforge.mixin.KawaMixinMeta value:
    "(@ Mixin (targets \"net.minecraft.client.Minecraft\"))")

  ((onStartGame (ci :: org.spongepowered.asm.mixin.injection.callback.CallbackInfo)) :: void
   (@com.momosoftworks.kawaforge.mixin.KawaMemberMeta value:
     "(@ Inject (method \"startGame\") (at (@ At (value \"HEAD\"))))")
   (display ">>> KAWA MIXIN WORKS <<<")
   (newline)))
```

## 3. Lexical rules

The payload is a sequence of s-expressions over these tokens:

- **Parentheses**: `(` `)`.
- **Whitespace**: space, tab, CR, LF — separates tokens, otherwise ignored.
- **Comments**: `;` to end of line. Permitted (macro output will not produce
  them, but hand-written payloads may).
- **String**: double-quoted. Escape sequences: `\\`, `\"`, `\n`, `\r`, `\t`.
  No other escapes are defined in v0. Strings may not contain raw newlines.
- **Integer**: optional `-` followed by decimal digits. Range: 64-bit signed.
- **Float**: optional `-`, digits, containing a `.` and/or exponent
  (`e`/`E` followed by optional sign and digits). Examples: `1.0`, `-2.5e3`.
- **Boolean**: `#t`, `#f`.
- **Character**: `#\c` where `c` is a single printable non-space character
  (for `char`-typed members; rare).
- **Symbol**: matches `[A-Za-z_$@][A-Za-z0-9_$.]*`, plus the standalone token
  `@`. Symbols are case-sensitive. A symbol containing `.` is a
  fully-qualified *binary* class name (packages separated by `.`, nested
  classes by `$`, e.g. `org.spongepowered.asm.mixin.injection.At$Shift`).
  A symbol without `.` in type position is an **alias** (§6.1).

This is exactly the subset that Kawa's `write` produces for lists of symbols,
strings, exact integers, flonums, booleans, and characters — so the Phase 1
macro can serialize payloads with plain `write`.

## 4. Grammar

```
payload  ::= ann*                        ; one or more annotation forms
ann      ::= "(" "@" type member* ")"
type     ::= SYMBOL                      ; dotted = binary class name; undotted = alias
member   ::= "(" name value+ ")"
name     ::= SYMBOL                      ; annotation member name (Java identifier)
value    ::= STRING | INTEGER | FLOAT | BOOLEAN | CHARACTER
           | ann                          ; nested annotation
           | "(" "class" typename ")"     ; class literal
           | "(" "enum" typename SYMBOL ")" ; enum constant
           | "(" "array" value* ")"       ; explicit array (required for empty)
typename ::= SYMBOL                      ; binary class name, alias, primitive
                                         ; name (int, boolean, ...), or void
```

Notes:

- A `payload` on a carrier may declare **multiple annotations** (e.g. `@Mixin`
  and `@Pseudo` on one class).
- A `member` with **more than one value** is an implicit array:
  `(targets "a.B" "c.D")` ≡ `(targets (array "a.B" "c.D"))`.
- A `member` with **one value** whose declared type is an array is wrapped
  into a singleton array by the normalizer (§6.4) — Java's annotation
  shorthand, reimplemented.
- `(array ...)` is required for **empty** arrays and permitted everywhere.
- The reserved heads `@`, `class`, `enum`, `array` are unambiguous because
  they appear only in head position of their respective forms.

## 5. Examples

Class-level payload:

```
(@ Mixin (targets "net.minecraft.client.Minecraft") (priority 1001))
```

Method-level payload:

```
(@ Inject
   (method "startGame")
   (at (@ At (value "HEAD")))
   (cancellable #t)
   (require 1))
```

Enum + class-literal + explicit array:

```
(@ Inject
   (method "func_71384_a")
   (at (@ At (value "INVOKE")
            (target "Lnet/minecraft/client/Minecraft;func_71357_I()V")
            (shift (enum org.spongepowered.asm.mixin.injection.At$Shift AFTER))))
   (remap #f))

(@ Mixin (value (class net.minecraft.client.Minecraft)) (targets (array)))
```

## 6. Normalization

The post-processor normalizes the raw tree against the **actual annotation
definitions** resolved from the consumer project's compile classpath (read
with ASM; never hardcoded). Steps, in order:

### 6.1 Alias resolution

Undotted type symbols resolve through this table (v0). Dotted names are used
verbatim. Unknown undotted names are an error.

| Alias | Binary name |
|---|---|
| `Mixin` | `org.spongepowered.asm.mixin.Mixin` |
| `Pseudo` | `org.spongepowered.asm.mixin.Pseudo` |
| `Shadow` | `org.spongepowered.asm.mixin.Shadow` |
| `Unique` | `org.spongepowered.asm.mixin.Unique` |
| `Final` | `org.spongepowered.asm.mixin.Final` |
| `Mutable` | `org.spongepowered.asm.mixin.Mutable` |
| `Overwrite` | `org.spongepowered.asm.mixin.Overwrite` |
| `Debug` | `org.spongepowered.asm.mixin.Debug` |
| `Dynamic` | `org.spongepowered.asm.mixin.Dynamic` |
| `Intrinsic` | `org.spongepowered.asm.mixin.Intrinsic` |
| `Inject` | `org.spongepowered.asm.mixin.injection.Inject` |
| `At` | `org.spongepowered.asm.mixin.injection.At` |
| `Slice` | `org.spongepowered.asm.mixin.injection.Slice` |
| `Redirect` | `org.spongepowered.asm.mixin.injection.Redirect` |
| `ModifyArg` | `org.spongepowered.asm.mixin.injection.ModifyArg` |
| `ModifyArgs` | `org.spongepowered.asm.mixin.injection.ModifyArgs` |
| `ModifyVariable` | `org.spongepowered.asm.mixin.injection.ModifyVariable` |
| `ModifyConstant` | `org.spongepowered.asm.mixin.injection.ModifyConstant` |
| `Constant` | `org.spongepowered.asm.mixin.injection.Constant` |
| `Surrogate` | `org.spongepowered.asm.mixin.injection.Surrogate` |
| `Coerce` | `org.spongepowered.asm.mixin.injection.Coerce` |
| `Group` | `org.spongepowered.asm.mixin.injection.Group` |
| `Accessor` | `org.spongepowered.asm.mixin.gen.Accessor` |
| `Invoker` | `org.spongepowered.asm.mixin.gen.Invoker` |

### 6.2 Definition loading

Each annotation type must be resolvable on the consumer compile classpath.
Its member names, member types, defaults, and retention are read with ASM.
Unresolvable type → build error naming the classpath searched.

### 6.3 Member checking

- Member name must exist on the annotation definition; otherwise error listing
  the valid member names.
- Value/type compatibility:
  - `STRING` → `String`
  - `INTEGER` → `byte`/`short`/`int`/`long` (range-checked)
  - `FLOAT` → `float`/`double`
  - `BOOLEAN` → `boolean`
  - `CHARACTER` → `char`
  - `(class T)` → `Class<...>`
  - `(enum T C)` → enum of exactly type `T` (`T` must match the declared type)
  - `ann` → annotation member of exactly that annotation type
- Mismatch → error stating member, declared type, and given value.

### 6.4 Array handling

If the declared member type is an array and the provided value is not an
`(array ...)` form or implicit multi-value array, wrap it into a singleton
array. Element types are then checked per §6.3. Arrays are **always emitted
via `AnnotationVisitor.visitArray`**, including singletons.

### 6.5 Visibility

Emit as `RuntimeVisible` if the annotation's retention is `RUNTIME`, and as
`RuntimeInvisible` if `CLASS`. Retention `SOURCE` → error. (Note: `@Mixin`
itself is `CLASS` retention; most injector annotations are `RUNTIME`. This is
why visibility must come from the definition, never assumed.)

### 6.6 Carrier stripping

`KawaMixinMeta` / `KawaMemberMeta` are removed from the output class file.
A class containing carriers with unparseable payloads fails the build; it is
never silently passed through.

## 7. Error reporting requirements

Every parse/normalization error must report: the class file, the carrier
location (class vs. member name + descriptor), the character offset within the
payload where applicable, and a one-line remediation hint. The payload is
short; include it verbatim in the error.

## 8. Out of scope for v0

- Refmap generation / SRG remapping (Phase 2).
- Mixin-semantic validation (handler descriptor shape, `@Shadow` matching) —
  Layer 2, separate from this grammar.
- `mixins.json` config generation (Phase 1).
