plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.transmiti2.transmiti2"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.transmiti2.transmiti2"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Fase 3.0: el minSdk efectivo es el mayor entre el de Flutter y 23.
        // 23 es el piso de Android Keystore AES (KeyGenParameterSpec) y de
        // Tink AEAD con AndroidKeysetManager. Si Flutter ya trae 24 o más,
        // ese valor manda y no se baja nada.
        minSdk = maxOf(flutter.minSdkVersion, 23)
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Fase 3.0 — almacenamiento seguro de claves de transmisión:
    // DataStore (Preferences, sin cifrar el archivo completo: cada valor
    // se cifra individualmente con Tink AEAD y su propio AAD) + Tink.
    // No se usa datastore-tink: su AeadSerializer tiene un AAD fijo por
    // archivo y no permite un AAD distinto por destino.
    // Tink 1.24.0: totalmente compatible desde API 24; AEAD y
    // AndroidKeysetManager funcionan desde API 23. JWT y StreamingAead
    // (que sí exigen API 24) no se usan en este proyecto.
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    // Media3 1.11.1 (estable 2026-11-11, minSdk 21): media3-muxer para
    // MediaMuxerCompat — ganó el spike frente al clásico en muerte
    // real del proceso (decisión 3.1.2, informe spike-resultados §18)
    // — y media3-exoplayer para la reproducción nativa y la
    // verificación de segmentos (dirección aprobada).
    implementation("androidx.media3:media3-muxer:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    testImplementation("junit:junit:4.13.2")
}

flutter {
    source = "../.."
}

// Imprime y verifica las versiones SDK efectivas. La usa el CI
// (./gradlew :app:printTransmiti2SdkVersions) y falla si el minSdk
// configurado no es exactamente max(flutter.minSdkVersion, 23).
tasks.register("printTransmiti2SdkVersions") {
    doLast {
        val flutterMinSdk = flutter.minSdkVersion
        val effectiveMinSdk = android.defaultConfig.minSdk ?: -1
        val targetSdk = android.defaultConfig.targetSdk ?: -1
        val compileSdk = android.compileSdk
        println("TRANSMITI2 SDK: flutter.minSdkVersion=$flutterMinSdk")
        println("TRANSMITI2 SDK: effective minSdk=$effectiveMinSdk")
        println("TRANSMITI2 SDK: targetSdk=$targetSdk")
        println("TRANSMITI2 SDK: compileSdk=$compileSdk")
        val expectedMinSdk = maxOf(flutterMinSdk, 23)
        check(effectiveMinSdk == expectedMinSdk) {
            "minSdk efectivo ($effectiveMinSdk) no es " +
                "max(flutter.minSdkVersion=$flutterMinSdk, 23) " +
                "= $expectedMinSdk"
        }
        check(effectiveMinSdk >= 23) {
            "minSdk efectivo ($effectiveMinSdk) es menor que 23"
        }
    }
}
