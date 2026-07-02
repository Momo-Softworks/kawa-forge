# define-mixin DSL — v1 Specification

**Status: FROZEN for Phase 1.** The DSL is sugar over the carrier contract in
`mixin-payload-spec.md`; anything expressible there but not here can always be
written with explicit carrier annotations on a plain `define-simple-class`.

## 1. Import

```scheme
(import (kawaforge mixin))
```

The module ships inside the kawa-forge plugin and is placed on the Kawa import
path by `compileKawa` automatically.

## 2. Surface

```scheme
(define-mixin MixinMinecraft
  ;; ---- class clauses ----
  (target "net.minecraft.client.Minecraft")     ; 1+ strings; repeatable
  (priority 1001)                               ; any other (key value...) clause
                                                ; before the first member clause
                                                ; passes through to @Mixin

  ;; ---- inject handler ----
  (inject onStartGame
      ((ci :: org.spongepowered.asm.mixin.injection.callback.CallbackInfo))
    (method "startGame")
    (at "HEAD")
    (cancellable #t)
    ;; body starts at the first non-clause form:
    (display ">>> KAWA MIXIN WORKS <<<")
    (newline))

  ;; ---- shadow field ----
  (shadow-field theWorld net.minecraft.world.World)

  ;; ---- unique helper method ----
  (unique (helper ((x :: int)) :: int)
    (* x 2)))
```

## 3. Clause reference

### 3.1 Class clauses

- `(target STRING ...)` / `(targets STRING ...)` — accumulated, in order, into
  the `@Mixin` `targets` array. At least one target is required.
- Any other `(KEY VALUE ...)` clause appearing before the first member clause
  is passed through as a `@Mixin` member: `(priority 1001)`, `(remap #f)`, ...
  Values map to payload values by type: string → string, exact integer →
  integer, `#t`/`#f` → boolean.

### 3.2 `inject`

```
(inject NAME PARAMS injector-clause ... body ...)
PARAMS ::= ((param :: type) ...)     ; define-simple-class formals, types REQUIRED
```

- Return type is always `void`.
- Every parameter must carry an explicit `:: type` annotation (Kawa would
  otherwise default to `Object`, which Mixin rejects). The macro raises an
  expansion-time error naming the parameter if a type is missing.
- Injector clauses (recognized heads, everything else starts the body):
  `method`, `at`, `cancellable`, `require`, `expect`, `allow`, `remap`, `id`,
  `constraints`.
  - `(method STRING ...)` — required.
  - `(at ATSPEC)` — required.
  - remaining clauses pass through as `@Inject` members.
- `ATSPEC`:
  - shorthand: `(at "HEAD")` ≡ `(at (value "HEAD"))`
  - full form: `(at (value "INVOKE") (target "Lnet/...;method()V") (shift AFTER) (ordinal 0) ...)`
    - `(shift SYM)` expands to the enum constant
      `(enum org.spongepowered.asm.mixin.injection.At$Shift SYM)`
    - all other keys pass through as `@At` members.

### 3.3 `shadow-field`

```
(shadow-field NAME TYPE)
```

Expands to a field `(NAME :: TYPE)` carrying `@Shadow`. No initializer is
permitted (Mixin semantics).

### 3.4 `unique`

```
(unique (NAME PARAMS :: RET) body ...)
```

Expands to a method carrying `@Unique`. Parameter types required, as with
`inject`.

## 4. Expansion contract

`define-mixin` expands to exactly one `define-simple-class` with the same name,
no superclass/interfaces, plus carrier annotations
(`@com.momosoftworks.kawaforge.mixin.KawaMixinMeta` /
`...KawaMemberMeta`) whose payloads follow `mixin-payload-spec.md` and use the
**alias names** (`Mixin`, `Inject`, `At`, `Shadow`, `Unique`) resolved later by
the normalizer.

Worked example — the surface form in §2 expands to:

```scheme
(define-simple-class MixinMinecraft ()
  (@com.momosoftworks.kawaforge.mixin.KawaMixinMeta value:
    "(@ Mixin (targets \"net.minecraft.client.Minecraft\") (priority 1001))")
  (theWorld
    (@com.momosoftworks.kawaforge.mixin.KawaMemberMeta value: "(@ Shadow)")
    :: net.minecraft.world.World)  ; field annotations sit between name and type
  ((onStartGame (ci :: org.spongepowered.asm.mixin.injection.callback.CallbackInfo)) :: void
    (@com.momosoftworks.kawaforge.mixin.KawaMemberMeta value:
      "(@ Inject (method \"startGame\") (at (@ At (value \"HEAD\"))) (cancellable #t))")
    (display ">>> KAWA MIXIN WORKS <<<")
    (newline))
  ((helper (x :: int)) :: int
    (@com.momosoftworks.kawaforge.mixin.KawaMemberMeta value: "(@ Unique)")
    (* x 2)))
```

Second worked example — INVOKE injection point:

```scheme
(inject afterInit ((ci :: ...CallbackInfo))
  (method "startGame")
  (at (value "INVOKE")
      (target "Lnet/minecraft/client/Minecraft;func_71357_I()V")
      (shift AFTER))
  (remap #f)
  body...)
;; member payload:
;; (@ Inject (method "startGame")
;;    (at (@ At (value "INVOKE")
;;             (target "Lnet/minecraft/client/Minecraft;func_71357_I()V")
;;             (shift (enum org.spongepowered.asm.mixin.injection.At$Shift AFTER))))
;;    (remap #f))
```

## 5. Guarantees & non-goals

- The expansion is plain Kawa: it compiles, runs, and debugs identically to a
  hand-written `define-simple-class`. Payload strings are built at macro
  expansion time via `write` (constant by construction).
- v1 does NOT cover: `@Redirect`/`@ModifyArg`/`@ModifyVariable` (Phase 2),
  `@Accessor`/`@Invoker` (interface mixins), CallbackInfoReturnable-specific
  sugar (declare the CIR param explicitly), refmaps/SRG remapping.
- Error reporting: all expansion-time validation errors must name the mixin
  class, the member, and the offending clause.
