import java.util.Properties

plugins {
    id("com.android.application")
}

// No dependencies on purpose: framework Views only, to keep RAM and APK size minimal.
android {
    namespace = "dev.lannister"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lannister"
        minSdk = 25 // Fire OS 6 (Fire TV Stick 4K) is Android 7.1 = API 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, without ** marks.
        val notes = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
            .substringAfter("\n## $versionName\n", "").substringBefore("\n## ")
            .lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").replace("**", "") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $versionName\"" }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
    }

    buildFeatures {
        resValues = true
    }

    // minSdk 25 makes lint flag the Android 8+ features that are switched off at runtime on a Fire Stick;
    // do not let that stop the build.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
        // A fixed, public sideload key (signing/lannister.jks) so every build, including GitHub Actions builds,
        // installs over the previous one. It is NOT a secret: use your own keystore.properties to publish.
        create("sideload") {
            storeFile = rootProject.file("signing/lannister.jks")
            storePassword = "lannister"
            keyAlias = "lannister"
            keyPassword = "lannister"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("sideload")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
