plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.momosoftworks"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(project(":kawa-mixin-annotations"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
        // Publish to the GitHub-hosted maven branch worktree:
        //   ./gradlew publishAllPublicationsToGithubMavenRepository -PgithubMavenDir=<worktree>
        providers.gradleProperty("githubMavenDir").orNull?.let { dir ->
            maven {
                name = "githubMaven"
                url = uri(dir)
            }
        }
    }
}
