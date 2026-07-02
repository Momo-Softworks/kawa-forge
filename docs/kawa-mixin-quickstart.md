# Kawa Mixins — Quickstart

Write SpongePowered Mixins for Minecraft Forge 1.7.10 in Kawa Scheme. The
plugin compiles your Scheme, injects the real Mixin annotations into the
bytecode, and generates the `mixins.<name>.json` config — no Java shims.

## 1. Consumer mod project

No cloning required — the plugin is published on the repo's `maven` branch.

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven {
            name = 'Kawa Forge'
            url = 'https://raw.githubusercontent.com/Momo-Softworks/kawa-forge/maven/'
        }
        gradlePluginPortal()
        // ... your existing plugin repos (RFG etc.)
    }
}
```

`build.gradle` — alongside your existing RetroFuturaGradle setup:

```groovy
plugins {
    id 'com.momosoftworks.kawa-forge' version '0.3.0'
}

// No extra repositories needed: the plugin auto-adds its own maven repo for
// kawa-mixin-annotations and the Kawa runtime.

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

## 2. The mixin, in Scheme

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

## 3. Build & verify the bytecode

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

## 4. Runtime bootstrap (1.7.10-specific — use what your pack already does)

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

## 5. What success looks like

Launch the dev client. Very early in the log (before the main menu):

```text
>>> KAWA MIXIN WORKS <<<
```

## Hacking on the plugin itself

Clone the repo, `./gradlew publishToMavenLocal` (JDK 17+; on Guix:
`guix shell -m manifest.scm`), and put `mavenLocal()` first in the consumer's
`pluginManagement` repositories.

## Known limitations (v0.3.0)

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
