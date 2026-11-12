plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}

// OCR for unknown cards in the reader: Google ML Kit text recognition (the model is in the app). Every build has it. The build flag `ocr`
// can switch it off for the production build: -Pocr=false (build.sh --prod --no-ocr), then the production app is small and the reader
// mode keeps its old behavior. The real reader is in src/ocr, the stand-in without a reader in src/noocr.
val ocrInProd = (project.findProperty("ocr") as String?) != "false"

// A bundle build is the one for the Play Store: the task name has "bundle" (build.sh --aab, or "Generate Signed Bundle" in Android Studio).
// Only a bundle gets the plain application id de.jobisoft.amster and the upload key. The APK builds get ".apk" in their id, so that
// they sit side by side with the Store app (they are signed with another key, Android would refuse one over the other).
val storeBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

// The upload key. build.sh --aab asks for the password and hands everything over in environment variables:
// AMSTER_KEYSTORE (the file, default upload.jks in the repository root, Git ignores it), AMSTER_KEYSTORE_PASSWORD,
// AMSTER_KEY_ALIAS (default upload) and AMSTER_KEY_PASSWORD (default: the keystore password).
val keystoreFile = System.getenv("AMSTER_KEYSTORE")?.let { File(it) } ?: rootProject.projectDir.resolve("../../upload.jks")
val keystorePassword: String? = System.getenv("AMSTER_KEYSTORE_PASSWORD")
if (storeBuild && (!keystoreFile.isFile || keystorePassword.isNullOrEmpty())) {
    throw GradleException(
        "The Store bundle needs the upload key: the file ${keystoreFile.path} and its password in AMSTER_KEYSTORE_PASSWORD. " +
            "Build it with ./build.sh --aab, it asks for the password."
    )
}

android {
    // The card sets are in the folder assets/card-data at the top. The folder assets is added to the assets of
    // the app, nothing is copied. The research data in assets/amazon-data is not part of the app.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.projectDir.resolve("../../assets"))
        }
        getByName("debug") {
            java.srcDir("src/ocr/java")
        }
        getByName("release") {
            java.srcDir(if (ocrInProd) "src/ocr/java" else "src/noocr/java")
        }
    }
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!amazon-data:!README.md"
    }

    namespace = "de.jobisoft.amster"
    compileSdk {
        version = release(36)
    }
    
    // Explicit build tools version for reproducible builds
    // AGP 8.13.2 typically uses build-tools 36.0.0
    // This ensures consistent build outputs across different environments
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "de.jobisoft.amster"
        minSdk = 24
        targetSdk = 36
        // build.sh passes a build time. Every build then has its own version name, and a
        // version code that only grows, so that Android accepts it as an update.
        // Without it (Android Studio) the app is a "dev" build.
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toInt() ?: 1
        versionName = "1.2.0-" + ((project.findProperty("buildStamp") as String?) ?: "dev")
        // only 64-bit ARM (every phone of the last years): the native library of ML Kit is 10 MB per processor type, the app is much
        // smaller without the others. Not for an emulator with x86 and not for an old 32-bit phone.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // Three builds, three application ids, so that they sit side by side on one phone:
    //   debug (assembleDebug)       de.jobisoft.amster.apk.debug  debug keystore of Android Studio (~/.android/debug.keystore)
    //   production APK (assembleRelease)  de.jobisoft.amster.apk  the same debug keystore
    //   Store bundle (bundleRelease)      de.jobisoft.amster      the upload key, see storeBuild above
    signingConfigs {
        if (storeBuild) {
            create("upload") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = System.getenv("AMSTER_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("AMSTER_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // the debug channel (DebugLog), it finds the log sink of
            // tools/debug_sink.py by itself
            applicationIdSuffix = ".apk.debug"
            versionNameSuffix = "-apk-debug"
            buildConfigField("boolean", "DEBUG_CHANNEL", "true")
            buildConfigField("boolean", "OCR_ENABLED", "true")
        }
        getByName("release") {
            // "production": small, no debug channel
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (storeBuild) {
                signingConfig = signingConfigs.getByName("upload")
                versionNameSuffix = "-store"
                resValue("string", "app_name", "AmSter")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                applicationIdSuffix = ".apk"
                versionNameSuffix = "-apk"
                resValue("string", "app_name", "AmSter (APK)")
            }
            buildConfigField("boolean", "DEBUG_CHANNEL", "false")
            buildConfigField("boolean", "OCR_ENABLED", ocrInProd.toString())
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
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
    
    // OCR for unknown cards (Google ML Kit text recognition): always in debug, in production unless -Pocr=false
    debugImplementation("com.google.mlkit:text-recognition:16.0.1")
    if (ocrInProd) releaseImplementation("com.google.mlkit:text-recognition:16.0.1")

    // Markdown parsing
    implementation(libs.commonmark)

    // Testing
    testImplementation(libs.junit)
}