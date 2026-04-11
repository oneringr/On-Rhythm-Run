import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

fun resolveLocalFile(path: String?): File? {
    if (path.isNullOrBlank()) {
        return null
    }
    val file = File(path)
    return if (file.isAbsolute) file else rootProject.file(path)
}

val rootLocalProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.reader(Charsets.UTF_8).use(::load)
    }
}

val externalSigningProperties = Properties().apply {
    val signingConfigFilePath = firstNonBlank(
        providers.gradleProperty("watch.signing.configFile").orNull,
        System.getenv("WATCH_SIGNING_CONFIG_FILE"),
        rootLocalProperties.getProperty("watch.signing.configFile")
    )
    if (!signingConfigFilePath.isNullOrBlank()) {
        val signingConfigFile = resolveLocalFile(signingConfigFilePath)
        if (signingConfigFile?.exists() == true) {
            signingConfigFile.reader(Charsets.UTF_8).use(::load)
        }
    }
}

val releaseSigningStoreFile = firstNonBlank(
    providers.gradleProperty("watch.signing.storeFile").orNull,
    System.getenv("WATCH_SIGNING_STORE_FILE"),
    rootLocalProperties.getProperty("watch.signing.storeFile"),
    externalSigningProperties.getProperty("storeFile")
)?.let(::resolveLocalFile)

val releaseSigningStorePassword = firstNonBlank(
    providers.gradleProperty("watch.signing.storePassword").orNull,
    System.getenv("WATCH_SIGNING_STORE_PASSWORD"),
    rootLocalProperties.getProperty("watch.signing.storePassword"),
    externalSigningProperties.getProperty("storePassword")
)

val releaseSigningKeyAlias = firstNonBlank(
    providers.gradleProperty("watch.signing.keyAlias").orNull,
    System.getenv("WATCH_SIGNING_KEY_ALIAS"),
    rootLocalProperties.getProperty("watch.signing.keyAlias"),
    externalSigningProperties.getProperty("keyAlias")
)

val releaseSigningKeyPassword = firstNonBlank(
    providers.gradleProperty("watch.signing.keyPassword").orNull,
    System.getenv("WATCH_SIGNING_KEY_PASSWORD"),
    rootLocalProperties.getProperty("watch.signing.keyPassword"),
    externalSigningProperties.getProperty("keyPassword")
)

val hasReleaseSigningConfig = releaseSigningStoreFile?.exists() == true &&
    !releaseSigningStorePassword.isNullOrBlank() &&
    !releaseSigningKeyAlias.isNullOrBlank() &&
    !releaseSigningKeyPassword.isNullOrBlank()

android {
    namespace = "com.runner.smartplayer.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.runner.smartplayer.watch"
        minSdk = 27
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseSigningStoreFile
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    buildFeatures {
        viewBinding = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20240303")
}
