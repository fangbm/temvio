import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "dev.agenticscheduler.ui"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            @Suppress("DEPRECATION")
            implementation(compose.material3)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
