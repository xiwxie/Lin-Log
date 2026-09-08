plugins {
    alias(libs.plugins.nowinandroid.android.application)
}

android {
    namespace = "com.lin.log.sample"
    defaultConfig {
        applicationId = "com.lin.log.sample"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(project(":linlog"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
