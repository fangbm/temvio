import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:domain"))
    implementation(project(":shared:application"))
    implementation(project(":shared:database"))
    implementation(project(":shared:agent"))
    implementation(libs.ktor.client.cio)
    implementation(compose.desktop.currentOs)
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.ktor.client.mock)
}

sourceSets.test { kotlin.srcDir(rootProject.file("test-support/d10-01")) }
sourceSets.main { kotlin.srcDir(rootProject.file("apps/presentation/src/main/kotlin")) }
sourceSets.test { kotlin.srcDir(rootProject.file("test-support/d10-02")) }

compose.desktop {
    application {
        mainClass = "dev.agenticscheduler.desktop.MainKt"
    }
}

sourceSets.test { kotlin.srcDir(rootProject.file("test-support/d10-03")) }
sourceSets.test { kotlin.srcDir(rootProject.file("test-support/d10-04")) }
