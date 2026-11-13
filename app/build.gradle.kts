import java.util.Properties

// アプリモジュールのビルド設定
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * リリース署名の読み込み。
 *
 * 鍵とパスワードはリポジトリに置かない。次のどちらかから読む。
 * 1. 環境変数: FPV_KEYSTORE_PATH / FPV_KEYSTORE_PASSWORD / FPV_KEY_ALIAS / FPV_KEY_PASSWORD
 * 2. リポジトリ外のプロパティファイル: %USERPROFILE%\.android-signing\pictobi-release.properties
 *
 * どちらも無い場合、release ビルドは未署名APKになる (端末にはインストール不可)。
 * 署名鍵の作成・保管方針は docs/signing-policy.md を参照する。
 */
val keystorePropertiesFile = File(System.getProperty("user.home"), ".android-signing/pictobi-release.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("storeFile", "FPV_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "FPV_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "FPV_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "FPV_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

android {
    namespace = "com.tonbiattack.fastestpictureviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tonbiattack.fastestpictureviewer"
        // RELATIVE_PATH / ボリューム別クエリを使うため Android 10 (API 29) 以上を対象とする
        minSdk = 29
        targetSdk = 36
        // リリース時は -Pfpv.versionCode=2 -Pfpv.versionName=0.2.0 のように上書きできる
        versionCode = (findProperty("fpv.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("fpv.versionName") as String?) ?: "0.1.0"
        // 実機での Compose UI テスト (ピンチ操作など端末入力が必要な検証) 用
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 署名情報が揃っているときだけリリース署名を付ける
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose を使用
    buildFeatures {
        compose = true
    }

    // Robolectric がリソースを使えるようにする (DAO の単体テスト用)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (BOM でバージョンを一括管理)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Room (永続メタデータキャッシュ)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // 画像読み込み
    implementation(libs.coil.compose)

    // 単体テスト (Robolectric は Room DAO の検証に使用する)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.runtime)

    // 実機 (OPPO Reno13 A) で端末入力を使った UI 検証を行うための instrumented test
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
