plugins {
    id("org.jetbrains.intellij.platform") version "2.9.0"
    kotlin("jvm") version "2.4.0"
}

group = "dev.bun"
version = "1.2.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

intellijPlatform {
    signing {
        privateKeyFile = layout.file(
            providers.environmentVariable("BUN_PLUGIN_PRIVATE_KEY_FILE").map { file(it) },
        )
        certificateChainFile = layout.file(
            providers.environmentVariable("BUN_PLUGIN_CERTIFICATE_CHAIN_FILE").map { file(it) },
        )
    }
    pluginVerification {
        ides {
            create("WS", "2026.3")
        }
    }
}

dependencies {
    intellijPlatform { intellijIdea("2026.3") }
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(27))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(27)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
