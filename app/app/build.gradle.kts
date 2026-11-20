import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Push needs four values of the owner's Firebase project. They are read at build time from
 * the owner's private settings file (never from the repository) and compiled into the app as
 * build-config fields; the file itself is not copied anywhere. Without the file the build
 * works as before and the app simply has no push.
 */
fun firebaseValues(): Map<String, String> {
    val file = File(System.getProperty("user.home"), ".config/lifeos/firebase/google-services.json")
    val text = providers.fileContents(layout.file(provider { file })).asText.orNull ?: return emptyMap()
    return try {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
        val project = root["project_info"] as Map<String, Any?>
        val client = (root["client"] as List<Map<String, Any?>>).first {
            ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "dev.lifeos.app"
        }
        mapOf(
            "FIREBASE_PROJECT_ID" to project["project_id"].toString(),
            "FIREBASE_SENDER_ID" to project["project_number"].toString(),
            "FIREBASE_APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString(),
            "FIREBASE_API_KEY" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString(),
        )
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * Release signing: the key and its password live only in the owner's private settings folder
 * (`~/.config/lifeos/release/keystore.properties` next to the key file); nothing is in the
 * repository. Without that file a release build is produced unsigned.
 */
fun releaseSigning(): Properties? {
    val file = File(System.getProperty("user.home"), ".config/lifeos/release/keystore.properties")
    if (!file.isFile) return null
    return Properties().apply { file.inputStream().use { load(it) } }
        .takeIf { p -> listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !p.getProperty(it).isNullOrEmpty() } }
}

/** Grows with every commit, so a newer build always installs over an older one. */
fun commitCount(): Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "dev.lifeos.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.lifeos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount()
        versionName = "0.1.$versionCode"

        val firebase = firebaseValues()
        for (name in listOf("FIREBASE_PROJECT_ID", "FIREBASE_SENDER_ID", "FIREBASE_APP_ID", "FIREBASE_API_KEY")) {
            buildConfigField("String", name, "\"${firebase[name].orEmpty().replace("\"", "")}\"")
        }

        ndk {
            // libsodium ships for these; the JNA archive also carries ABIs no phone uses.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    val signing = releaseSigning()
    if (signing != null) {
        signingConfigs {
            create("release") {
                storeFile = File(System.getProperty("user.home"), ".config/lifeos/release/" + signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Only the two languages the app is translated into.
        localeFilters += listOf("en", "tr")
    }

    lint {
        // Warnings are reported but do not fail the build; errors do.
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // End-to-end layer: libsodium through lazysodium. The app loads the native library
    // from the JNA Android archive; JVM unit tests use the desktop binding instead.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("${libs.jna.get()}@aar")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
    testImplementation(libs.kotlinx.coroutines.test)
}
