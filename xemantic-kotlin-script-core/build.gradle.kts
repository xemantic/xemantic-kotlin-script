@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    explicitApi()

    abiValidation {}

    // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        jvmTarget = JvmTarget.fromTarget(javaTarget)
        freeCompilerArgs.addAll(
            "-Xjdk-release=$javaTarget",
            "-Xcontext-sensitive-resolution"
        )
        extraWarnings = true
        progressiveMode = true
    }

    sourceSets.test {
        // the test types outside the com.xemantic.kotlin.script package,
        // in a source root of their own, not to break the package prefix inference of the IDE
        kotlin.srcDir("src/test/packages")
    }

}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // the plugin making the snippets suspending, passed to the compiler of a session with -Xplugin
    implementation(project(":xemantic-kotlin-script-compiler-plugin"))
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // the K2 REPL compiler, keeping the state of the session between the snippets
    implementation(libs.kotlin.scripting.compiler.embeddable)
    implementation(libs.kotlin.compiler.embeddable)
    // the @file:DependsOn and @file:Repository annotations of the snippets, resolved from Maven repositories
    implementation(libs.kotlin.scripting.dependencies)
    implementation(libs.kotlin.scripting.dependencies.maven)
    // the Maven ordering of the versions, to resolve the latest one of a dependency declared without version
    implementation(libs.maven.resolver.util)
    // the versions of a dependency declared without version, looked up like kotlin-scripting-dependencies-maven resolves it
    implementation(libs.maven.resolver.impl)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.file)
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.provider)
    implementation(libs.maven.settings)
    // the compiler brings kotlin-reflect 1.6.10, which mutates final fields, deprecated since JDK 26
    implementation(libs.kotlin.reflect)
    // the plugin compiling the @Serializable classes of the snippets, applied to every session
    implementation(libs.kotlin.serialization.compiler.plugin.embeddable)
    implementation(libs.xemantic.kotlin.core)
    implementation(libs.kotlin.logging)
    // kotlin-logging 8 no longer brings SLF4J transitively
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.auto.head.response)
    testImplementation(libs.ktor.client.cio)
    // the runtime of the @Serializable classes declared by the snippets
    testImplementation(libs.kotlinx.serialization.json)
    // only the tests bind an SLF4J backend, a library leaves this choice to the application,
    // and collect the logged events with it, see LogCollector
    testImplementation(libs.logback.classic)
}

// the versions of Maven Resolver and Maven must be the ones brought by kotlin-scripting-dependencies-maven,
// otherwise the API and the implementation of Maven Resolver of 2 major versions are mixed on the classpath
val expectedVersions = mapOf(
    "org.apache.maven.resolver" to ("mavenResolver" to libs.versions.mavenResolver.get()),
    "org.apache.maven" to ("maven" to libs.versions.maven.get())
)

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(libs.versions.asm.get())
        }
        expectedVersions[requested.group]?.let { (key, expected) ->
            check(requested.version == expected) {
                "$requested is requested, while gradle/libs.versions.toml declares $key = \"$expected\" for ${requested.group}, " +
                    "see the comment on $key there"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_23)) {
        // the fast JAR file system of the compiler calls sun.misc.Unsafe, the option is unknown before JDK 23
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
}

powerAssert {
    functions = listOf(
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have"
    )
}

// https://kotlinlang.org/docs/dokka-migration.html#adjust-configuration-options
dokka {
    pluginsConfiguration.html {
        footerMessage = rootProject.extensions.getByType<com.xemantic.gradle.conventions.XemanticConfiguration>().copyright
    }
}
