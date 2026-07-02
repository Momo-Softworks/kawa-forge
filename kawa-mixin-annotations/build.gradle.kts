plugins {
    `java-library`
    `maven-publish`
}

group = "com.momosoftworks"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
        providers.gradleProperty("githubMavenDir").orNull?.let { dir ->
            maven {
                name = "githubMaven"
                url = uri(dir)
            }
        }
    }
}
