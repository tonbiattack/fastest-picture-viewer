import java.util.Properties

/**
 * versionName から versionCode を求める。
 *
 * `major*10000 + minor*100 + patch` で単調増加させる。
 * `.github/workflows/release.yml` の計算式と揃えること（片方だけ変えると
 * タグ由来の versionCode と既定値がずれ、Obtainium が更新を検知できなくなる）。
 *
 * 例: 0.2.0 → 200、0.2.1 → 201、1.0.0 → 10000
 */
fun versionCodeOf(versionName: String): Int {
    val parts = versionName.split(".")
    require(parts.size == 3) { "versionName は major.minor.patch 形式にしてください: $versionName" }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: error("versionName に数値でない部分があります: $versionName")
    }
    return major * 10000 + minor * 100 + patch
}

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
        // ストア登録後は変更できないため、既定値は固定する。
        // R8 有効ビルドの動作検証など、既存インストールと衝突させたくない場合だけ
        // -Pfpv.applicationIdSuffix=.r8check のように接尾辞を付けて別アプリとして入れる
        val appIdSuffix = (findProperty("fpv.applicationIdSuffix") as String?) ?: ""
        applicationId = "com.tonbiattack.fastestpictureviewer$appIdSuffix"
        // RELATIVE_PATH / ボリューム別クエリを使うため Android 10 (API 29) 以上を対象とする
        minSdk = 29
        targetSdk = 36
        // バージョンは「コードを変更するたびに上げる」(AGENTS.md)。
        // Obtainium がリリースのタグと APK の versionCode を見て更新を判定するため、
        // 既定値も実際に配布している版に合わせておく。
        //
        // versionCode は major*10000 + minor*100 + patch で単調増加させる
        // （.github/workflows/release.yml と同じ計算式）。
        // リリース時は -Pfpv.versionCode=201 -Pfpv.versionName=0.2.1 のように上書きできる。
        val defaultVersionName = "0.4.0"
        versionName = (findProperty("fpv.versionName") as String?) ?: defaultVersionName
        versionCode = (findProperty("fpv.versionCode") as String?)?.toIntOrNull()
            ?: versionCodeOf(defaultVersionName)
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
        debug {
            // 計測は debug で既定有効。性能比較はこのビルドで行う (Issue #99)
            buildConfigField(
                "boolean",
                "PERF_TRACE",
                ((findProperty("fpv.perfTrace") as String?)?.toBoolean() ?: true).toString()
            )
            // R8 有効ビルドの動作検証用 (Issue #121)。
            // リリース署名と debug 署名は互換がないため、実機で上書き検証するときは
            // -Pfpv.minifyDebug=true で debug 署名のまま R8 を掛ける
            if ((findProperty("fpv.minifyDebug") as String?)?.toBoolean() == true) {
                isMinifyEnabled = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // リリースは既定で無効。性能比較用に明示指定したときだけ有効にする
            buildConfigField(
                "boolean",
                "PERF_TRACE",
                ((findProperty("fpv.perfTrace") as String?)?.toBoolean() ?: false).toString()
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
        // バックアップにアプリのバージョン名を記録するため (Issue #74)
        buildConfig = true
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
