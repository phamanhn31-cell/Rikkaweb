import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("me.rerere.rikkahub.standalone.MainKt")
}

val rikkawebJar = tasks.register<Jar>("rikkawebJar") {
    group = "distribution"
    description = "Build a self-contained runnable jar (no gradlew run needed)."

    archiveFileName.set("rikkaweb.jar")
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }

    // Avoid relying on Java `sourceSets` being present; include compiled Kotlin + processed resources.
    dependsOn(tasks.named("compileKotlin"))
    dependsOn(tasks.named("processResources"))
    from(layout.buildDirectory.dir("classes/kotlin/main"))
    from(layout.buildDirectory.dir("resources/main"))

    val runtimeClasspath = configurations.runtimeClasspath.get()
    dependsOn(runtimeClasspath)
    from({
        runtimeClasspath
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    // Avoid signature collisions when shading dependencies.
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
    )
}

tasks.named("build") {
    dependsOn(rikkawebJar)
}

dependencies {
    // ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation("org.mozilla:rhino:1.7.15")
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)

    // kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // logging (binder)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // persistence (read app backup sqlite)
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // http client for upstream AI providers
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MCP client
    implementation(libs.modelcontextprotocol.kotlin.sdk)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // tests
    testImplementation(libs.junit)
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
}
