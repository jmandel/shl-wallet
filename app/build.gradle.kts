plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.fhir.shcwallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.fhir.shcwallet"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

// Task to compile the Rust WASM module
tasks.register("compileMatcherWasm", Exec::class) {
    workingDir = rootProject.file("matcher_rs")
    // Ensure Cargo is in the PATH or specify the full path to cargo
    commandLine = listOf("cargo", "build", "--target", "wasm32-wasi", "--release")
    // Optional: Add error handling or check for cargo presence
    doFirst {
        logger.lifecycle("Compiling matcher_rs WASM module...")
    }
    doLast {
        logger.lifecycle("Finished compiling matcher_rs WASM module.")
    }
}

// Task to copy the WASM file to assets before build
tasks.register("copyMatcherWasm", Copy::class) {
    dependsOn(tasks.named("compileMatcherWasm")) // Depend on the compilation task
    from(rootProject.file("matcher_rs/target/wasm32-wasi/release/matcher_rs.wasm"))
    into("src/main/assets")
    include("matcher_rs.wasm")
    rename { "matcher_rs.wasm" }
    // Make sure the assets directory exists
    doFirst {
        File("src/main/assets").mkdirs()
    }
}

// Make preBuild depend on our copy task
tasks.named("preBuild") {
    dependsOn("copyMatcherWasm")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.google.guava)
    implementation(libs.nimbus.jose.jwt)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.credentials)
//    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.registry.provider)
    implementation(libs.androidx.registry.provider.play.services)
    implementation(libs.androidx.registry.digitalcredentials.mdoc)
    implementation(libs.androidx.registry.digitalcredentials.preview)
    implementation(libs.play.services.identity.credentials)
    implementation(libs.androidx.biometric.ktx)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
}