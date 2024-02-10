plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = Ext.appId
    compileSdk = 34

    defaultConfig {
        applicationId = Ext.appId
        minSdk = 26
        targetSdk = 34

        versionCode = Ext.appVersionCode
        versionName = Ext.appVersionName

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.Experimental",
        )
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // implementation fileTree(dir: "libs", include: ["*.jar"])

    val kotlinxCoroutinesVersion = "1.7.3"

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media:media:1.7.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

detekt {
    // 並列処理
    parallel = true

    // デフォルト設定の上に自分の設定ファイルを適用する
    buildUponDefaultConfig = true

    // Detektの関する設定ファイル
    config.from(files("$rootDir/config/detekt/detekt.yml"))

    basePath = rootDir.absolutePath
}
