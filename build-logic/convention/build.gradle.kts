/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    alias(libs.plugins.android.lint)
}

group = "com.google.samples.apps.nowinandroid.buildlogic"

// Configure the build-logic plugins to target JDK 17
// This matches the JDK used to build the project, and is not related to what is running on device.

java {
    sourceCompatibility = JavaVersion.valueOf(libs.versions.appJavaVersion.get())
    targetCompatibility = JavaVersion.valueOf(libs.versions.appJavaVersion.get())
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.valueOf(libs.versions.appJVMTarget.get())
    }
}


dependencies {
    compileOnly(libs.android.tools.gradle)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.symbol.processing.gradle.plugin)
}
tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "nowinandroid.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "nowinandroid.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }

        register("AppUploadBuildFilePlugin"){
            id = "nowinandroid.appUploadBuildFilePlugin"
            implementationClass = "AppUploadBuildFilePlugin"
        }

        register("mavenPublish") {
            id = "my.maven.publish"
            implementationClass = "MavenPublishConventionPlugin"
        }
    }
}
