# Kawa Forge Gradle Plugin — Maven Repository

Published artifacts for `com.momosoftworks.kawa-forge`.

## Usage

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven {
            name = "Kawa Forge"
            url = uri("https://raw.githubusercontent.com/Momo-Softworks/kawa-forge/maven/")
        }
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("com.momosoftworks.kawa-forge") version "0.3.1"
}
```
