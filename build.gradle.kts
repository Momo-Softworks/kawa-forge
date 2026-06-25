plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.momosoftworks"
version = "0.1.0"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("kawaForge") {
            id = "com.momosoftworks.kawa-forge"
            implementationClass = "com.momosoftworks.kawaforge.KawaForgePlugin"
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
