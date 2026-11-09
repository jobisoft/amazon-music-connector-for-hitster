
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}

android {
    // The card sets (CSV files and their names) are shared by the apps of this repository, the
    // folder card-sets/assets at the top is added to the assets of the app. They are not copied.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.projectDir.resolve("../../card-sets/assets"))
        }
    }

    namespace = "de.jobisoft.hitsterconnector"
    compileSdk {
        version = release(36)
    }
    
    // Explicit build tools version for reproducible builds
    // AGP 8.13.2 typically uses build-tools 36.0.0
    // This ensures consistent build outputs across different environments
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "de.jobisoft.hitsterconnector"
        minSdk = 24
        targetSdk = 36
        // build.sh passes a build time. Every build then has its own version name, and a
        // version code that only grows, so that Android accepts it as an update.
        // Without it (Android Studio) the app is a "dev" build.
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.0-" + ((project.findProperty("buildStamp") as String?) ?: "dev")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // Use legacy packaging to ensure 16 KB page size compatibility
            // This compresses native libraries in the APK to avoid alignment issues
            useLegacyPackaging = true
        }
        
        // Ensure deterministic ZIP ordering for reproducible builds
        // AGP 2.2.2+ automatically zeroes timestamps, but we ensure resources are deterministic
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }

    // Debug and release are signed with the same debug keystore of Android Studio
    // (~/.android/debug.keystore). The debug app has its own application id (suffix ".debug")
    // and name, so the debug and the production app are two apps that sit side by side on the phone.
    buildTypes {
        getByName("debug") {
            // the debug channel (DebugLog, "Send diagnostics"), it finds the log sink of
            // tools/debug_sink.py by itself
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "DEBUG_CHANNEL", "true")
        }
        getByName("release") {
            // "production": small, no debug channel
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-prod"
            buildConfigField("boolean", "DEBUG_CHANNEL", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        
        // Treat warnings as errors for Java compilation (can be enabled via gradle.properties: java.warningsAsErrors=true)
        val javaWarningsAsErrors = project.findProperty("java.warningsAsErrors")?.toString()?.toBoolean() ?: false
        if (javaWarningsAsErrors) {
            // Note: This is handled via tasks.withType<JavaCompile> below
        }
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            
            // CRITICAL: Treat all Kotlin warnings as errors
            // This ensures code quality and catches issues early.
            // DO NOT remove or disable this setting - see gradle.properties for details.
            allWarningsAsErrors.set(true)
            
            // Annotation target configuration for better compatibility
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    lint {
        // Ignore AndroidGradlePluginVersion warning - we intentionally stay on AGP 8.13.2
        // AGP 9.0.0 requires Gradle 9.x and may not be stable yet
        disable += "AndroidGradlePluginVersion"
        
        // CRITICAL: Treat all Lint warnings as errors
        // This ensures code quality and catches issues early.
        // DO NOT remove or disable this setting - see gradle.properties for details.
        // The setting is controlled via gradle.properties: android.lint.warningsAsErrors=true
        val lintWarningsAsErrors = project.findProperty("android.lint.warningsAsErrors")?.toString()?.toBoolean() ?: false
        if (lintWarningsAsErrors) {
            warningsAsErrors = true
        }
        
        // Check for warnings in release builds
        checkReleaseBuilds = true
        
        // Show all warnings
        abortOnError = false // Don't abort on lint errors, only on warnings-as-errors (controlled by warningsAsErrors above)
    }
}

// Task to copy LICENSES_THIRD_PARTY.md from root to assets directory before build
// Configured for reproducible builds: AGP automatically handles timestamp normalization
tasks.register<Copy>("copyLicenses") {
    from(rootProject.file("LICENSES_THIRD_PARTY.md"))
    into("src/main/assets")
    includeEmptyDirs = false
}

// Make sure the file is copied before processing resources
tasks.named("preBuild") {
    dependsOn("copyLicenses")
}

// CRITICAL: Configure Java compilation to treat warnings as errors
// This ensures code quality and catches issues early.
// DO NOT remove or disable this setting - see gradle.properties for details.
tasks.withType<JavaCompile>().configureEach {
    options.isDeprecation = true
    options.encoding = "UTF-8"
    
    // Treat Java compiler warnings as errors (controlled via gradle.properties: java.warningsAsErrors=true)
    val javaWarningsAsErrors = project.findProperty("java.warningsAsErrors")?.toString()?.toBoolean() ?: false
    if (javaWarningsAsErrors) {
        options.compilerArgs.add("-Werror")
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // DataStore (for configuration)
    implementation(libs.androidx.datastore.preferences)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Dependency Injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    
    // Networking
    implementation(libs.okhttp)
    
    // Camera & QR Code Scanning
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.android) {
        exclude(group = "com.google.mlkit", module = "barcode-scanning")
        exclude(group = "com.google.mlkit", module = "barcode-scanning-common")
    }
    
    // Markdown parsing
    implementation(libs.commonmark)
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}