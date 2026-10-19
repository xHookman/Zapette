import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "fr.zapette.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.zapette.tv"
        minSdk = 24
        targetSdk = 35
        // The CI gives both (see .github/workflows/build.yml). The code is the build
        // number: it only goes up, as Android requires for an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("ZAPETTE_VERSION") ?: "dev"
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: "xHookman/Zapette"}\"")

        ndk {
            // Python (Chaquopy) is native. One APK for every TV: 64-bit and 32-bit ARM
            // (many TVs run a 32-bit Android on a 64-bit chip, which confuses users)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Releases are signed with the private Zapette key, given by the CI through
    // environment variables (GitHub secrets, never stored in the repository).
    // Android only installs an update signed with the same key as the installed app.
    // Without these variables (a local build), the release uses the debug key.
    val keystore = System.getenv("ZAPETTE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("zapette") {
            storeFile = keystore
            storePassword = System.getenv("ZAPETTE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ZAPETTE_KEY_ALIAS")
            keyPassword = System.getenv("ZAPETTE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8: Compose and Media3 run much faster once optimized (TV boxes are slow)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("zapette") ?: signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        // The app is sideloaded on TVs: never block a release build on lint
        checkReleaseBuilds = false
        abortOnError = false
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Same libraries Kodi provides to Catch-up TV & More (script.module.*)
            install("requests==2.31.0")   // urlquick is not compatible with requests >= 2.32.4
            install("six")
            install("pytz")
            install("tzdata")
            install("tzlocal")
            install("future")
            install("pyqrcode")
            install("htmlement")
            install("yt-dlp")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // Installs the baseline profile (ahead-of-time compiled UI code) on sideloaded installs
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

/*
 * ./gradlew :app:updateAddons
 * Downloads the latest Catch-up TV & More and codequick from GitHub and
 * repackages them (add-on files at the root of the zip) into the assets.
 */
tasks.register("updateAddons") {
    group = "zapette"
    description = "Refresh the bundled Kodi add-ons from GitHub"
    doLast {
        val sources = mapOf(
            "plugin.video.catchuptvandmore" to
                "https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore/archive/refs/heads/master.zip",
            "script.module.codequick" to
                "https://github.com/willforde/script.module.codequick/archive/refs/heads/master.zip",
        )
        val skip = listOf(".git", "resources/screenshots/", "tools/", ".github/", "tests/", "docs/")
        sources.forEach { (id, url) ->
            logger.lifecycle("Downloading $id")
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zos ->
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        // "<repo>-master/" (+ "script.module.codequick/" for codequick)
                        var name = e.name.substringAfter('/')
                        if (id == "script.module.codequick") {
                            name = if (name.startsWith("script.module.codequick/")) name.substringAfter('/') else ""
                        }
                        if (name.isNotEmpty() && !e.isDirectory && skip.none { name.startsWith(it) }) {
                            zos.putNextEntry(ZipEntry(name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                        e = zis.nextEntry
                    }
                }
            }
            file("src/main/assets/addons/$id.zip").writeBytes(out.toByteArray())
        }
    }
}
