import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.cameleonnbss.originostoolkit"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cameleonnbss.originostoolkit"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
        resourceConfigurations += listOf("en", "fr", "zh")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The tweak catalog lives at the repository root so the CLI and the app can
    // never drift apart: there is exactly one copy of every definition.
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            assets.srcDirs("src/main/assets", rootProject.file("catalog"))
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // No AIDL on purpose: the Shizuku user service speaks a hand-written
        // Binder protocol (core/shell/ShellProtocol.kt), which avoids the native
        // aidl binary and its non-ASCII-path failures on Windows.
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "OldTargetApi", "DataExtractionRules")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

// JVM unit tests read the very same JSON the device build ships in its assets.
tasks.withType<Test>().configureEach {
    systemProperty("originos.catalog.dir", rootProject.file("catalog").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
