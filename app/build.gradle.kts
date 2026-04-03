import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val generatedResDir = layout.buildDirectory.dir("generated/res/main")
val generateCaBundle = tasks.register<Copy>("generateCaBundle") {
    val configuredBundle = providers.environmentVariable("CACERT_PEM")
    val caBundlePath = configuredBundle.orNull
        ?: error("CACERT_PEM must be set by the Nix build environment")

    from(caBundlePath)
    into(generatedResDir.map { it.dir("raw") })
    rename { "nixpkgs_cacert.pem" }
}

val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")

android {
    namespace = "com.example.smsforwarder"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val storeFilePath = signingStoreFile.orNull
                ?: error("SIGNING_STORE_FILE must be set by the build environment")
            storeFile = file(storeFilePath)
            storePassword = signingStorePassword.orNull
                ?: error("SIGNING_STORE_PASSWORD must be set by the build environment")
            keyAlias = signingKeyAlias.orNull
                ?: error("SIGNING_KEY_ALIAS must be set by the build environment")
            keyPassword = signingKeyPassword.orNull
                ?: error("SIGNING_KEY_PASSWORD must be set by the build environment")
        }
    }

    defaultConfig {
        applicationId = "com.example.smsforwarder"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val robolectricDepsFile = System.getenv("ROBOLECTRIC_DEPS_PROPERTIES")
            if (!robolectricDepsFile.isNullOrBlank()) {
                it.systemProperty("robolectric-deps.properties", robolectricDepsFile)
            }
        }
    }

    sourceSets.named("main") {
        res.srcDir(generatedResDir)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateCaBundle)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
