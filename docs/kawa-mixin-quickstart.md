# Kawa Mixins — Quickstart

Write SpongePowered Mixins for Minecraft Forge 1.7.10 in Kawa Scheme. The
plugin compiles your Scheme, injects the real Mixin annotations into the
bytecode, and generates the `mixins.<name>.json` config — no Java shims.

## 1. One-time setup (until the plugin is published to a repo)

```sh
git clone git@github.com:Momo-Softworks/kawa-forge.git
cd kawa-forge
./gradlew publishToMavenLocal
```

Requires any JDK 17+ on PATH (on Guix: `guix shell -m manifest.scm` inside the
clone). This publishes `com.momosoftworks.kawa-forge` **0.2.0** and
`com.momosoftworks:kawa-mixin-annotations:0.2.0` to `~/.m2`.

## 2. Consumer mod project

`settings.gradle` — let Gradle find the plugin in mavenLocal:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        // ... your existing plugin repos (RFG etc.)
    }
}
```

`build.gradle` — alongside your existing RetroFuturaGradle setup:

```groovy
plugins {
    id 'com.momosoftworks.kawa-forge' version '0.2.0'
}

repositories {
    mavenLocal() // resolves kawa-mixin-annotations
}

kawa {
    mixin {
        // Package root for your Kawa mixin classes (required once you have mixins):
        mixinPackage = 'com.example.mymod.mixin'
        // Optional overrides (defaults shown):
        // configName = 'mixins.<project-name>.json'
        // required = true
        // minVersion = '0.7.11'
        // compatibilityLevel = 'JAVA_8'
    }
}

dependencies {
    // Your usual 1.7.10 mixin library so CallbackInfo & friends are on the
    // compile classpath — e.g. UniMixins (GTNH) or whatever the pack already
    // uses. The plugin reads the REAL annotation definitions from this
    // dependency, so it stays correct for whichever Mixin version you ship.
}
```

## 3. The mixin, in Scheme

`src/main/scheme/com/example/mymod/mixin/mixins.scm`:

```scheme
;; IMPORTANT: the module name must NOT equal any mixin class name. If they
;; match, Kawa merges its module machinery (static initializer, Runnable)
;; into the mixin class, which Sponge Mixin will not accept. With a distinct
;; module name the mixin class compiles clean (constructor only) and the
;; module machinery lands in a separate, inert class.
(module-name com.example.mymod.mixin.mixins)
(import (kawaforge mixin))

(define-mixin MixinMinecraft
  (target "net.minecraft.client.Minecraft")

  (inject onStartGame
      ((ci :: org.spongepowered.asm.mixin.injection.callback.CallbackInfo))
    (method "startGame")
    (at "HEAD")
    (display ">>> KAWA MIXIN WORKS <<<")
    (newline)))
```

Notes:
- `(import (kawaforge mixin))` — the DSL ships with the plugin; nothing to add.
- Handler parameters **must** be typed (`:: Type`); the macro errors otherwise.
- More surface (INVOKE points, `shift`, `cancellable`, `shadow-field`,
  `unique`, `@Mixin` passthroughs like `priority`): see
  [`mixin-dsl-spec.md`](mixin-dsl-spec.md). Anything the DSL doesn't cover yet
  can be hand-written with carrier annotations per
  [`mixin-payload-spec.md`](mixin-payload-spec.md).

## 4. Build & verify the bytecode

```sh
./gradlew build
```

Pipeline: `compileKawa` → `processKawaMixins` (annotations materialized) →
`generateKawaMixinConfig` (config json) → your jar.

Sanity checks:

```sh
javap -v -cp build/classes/kawaMixin/main com.example.mymod.mixin.MixinMinecraft | grep -A4 Annotations
```

Expected shape:

```text
RuntimeInvisibleAnnotations:
  org.spongepowered.asm.mixin.Mixin(targets=["net.minecraft.client.Minecraft"])
...
public void onStartGame(...CallbackInfo);
  RuntimeVisibleAnnotations:
    org.spongepowered.asm.mixin.injection.Inject(
      method=["startGame"], at=[@At(value="HEAD")])
```

And the generated config in the jar:

```sh
unzip -p build/libs/<yourmod>.jar mixins.<yourmod>.json
```

```json
{
  "required": true,
  "minVersion": "0.7.11",
  "package": "com.example.mymod.mixin",
  "compatibilityLevel": "JAVA_8",
  "mixins": ["MixinMinecraft"]
}
```

## 5. Runtime bootstrap (1.7.10-specific — use what your pack already does)

The compile-time side above is fully automated and tested. Getting Mixin to
*load* the config at runtime is the same as for any Java mixin mod on 1.7.10
and depends on your environment:

- **UniMixins-based packs (GTNH-style)**: ship UniMixins as usual and register
  the config the way your other mixin mods do.
- **Plain LaunchWrapper**: the classic route is jar manifest attributes —
  `TweakClass: org.spongepowered.asm.launch.MixinTweaker` and
  `MixinConfigs: mixins.<yourmod>.json` — with the mixin library available at
  launch. In Gradle:

  ```groovy
  jar {
      manifest.attributes(
          'TweakClass': 'org.spongepowered.asm.launch.MixinTweaker',
          'MixinConfigs': "mixins.${project.name}.json"
      )
  }
  ```

This half is exactly what the smoke test should confirm — if the config loads
but Mixin rejects a class, that's a bug in this plugin: please capture the log.

## 6. What success looks like

Launch the dev client. Very early in the log (before the main menu):

```text
>>> KAWA MIXIN WORKS <<<
```

## Known limitations (v0.2.0)

- Dev-environment (MCP names) only — **no refmap/SRG remapping yet**, so
  production/obfuscated environments won't remap targets. Phase 2.
- DSL covers `@Mixin`, `@Inject`+`@At`, `@Shadow` (fields), `@Unique`.
  `@Redirect`/`@ModifyArg`/`@Accessor`/`@Invoker` etc. are planned; the
  underlying engine already supports arbitrary annotations via hand-written
  carriers.
- The mixin class should not have initialized instance fields (standard Mixin
  restriction; the DSL doesn't emit any).

## Reporting problems

Please include: the `.scm` source, the `javap -v` output of the class from
`build/classes/kawaMixin/main`, the generated `mixins.*.json`, and the launch
log. Build-time errors already name the class/member/payload — paste them
verbatim.
