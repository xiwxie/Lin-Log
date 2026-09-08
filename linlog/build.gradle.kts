plugins {
    alias(libs.plugins.nowinandroid.android.library)
    alias(libs.plugins.nowinandroid.maven.publish)
}

publishInfo {
    groupId = "com.github.xiwxie"
    artifactId = "linlog"
    version = "1.0.0"
    description = "LinLog - 工业级全异步、零侵入、多领域物理隔离的 Android 日志基础设施"
}

android {
    namespace = "com.lin.log"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
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
}

dependencies {
    compileOnly(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
