/*
 * Copyright 2023 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.misterp.plugin.ext

import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryBuildType
import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import java.util.Properties

val Project.libs
    get(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * 查找Libs配置下的key-转换成Int
 */
fun Project.findVersionToInt(key : String) : Int{
    return findVersionToString(key).toInt()
}

/**
 * 查找Libs配置下的key-转换成String
 */
fun Project.findVersionToString(key : String) : String{
    return libs.findVersion(key)
        .get()
        .toString()
}

fun Project.findLocalProperty(key: String): String? {
    // 1. 尝试从 local.properties 读取
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        val localProps = Properties().apply {
            localPropsFile.inputStream().use { load(it) }
        }
        val value = localProps.getProperty(key)
        if (!value.isNullOrBlank()) return value
    }

    // 2. 尝试从 gradle.properties 或 -P 参数读取
    val gradleProp = providers.gradleProperty(key).orNull
    if (!gradleProp.isNullOrBlank()) return gradleProp

    // 3. 尝试从环境变量读取 (可选)
    return System.getenv(key)
}

/**
 * app包通用构建
 */
fun ApplicationExtension.buildCompileOptions(project: Project){
    compileOptions{
        sourceCompatibility = JavaVersion.valueOf(project.findVersionToString("appJavaVersion"))
        targetCompatibility = JavaVersion.valueOf(project.findVersionToString("appJavaVersion"))
        encoding = "UTF-8"
    }
}

/**
 * buildTypes 构建模版
 */
fun ApplicationExtension.buildTypesTemp() {
    buildTypes {
        fun ApplicationBuildType.applyProduct() {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // AGP 8.0+：zipalign 强制开启，移除了手动控制的选项
            // isZipAlignEnabled = true
//                signingConfig = signingConfigs.getByName("config")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // 测试环境包
        getByName("debug") {
            // 设置是否要自动上传
            isDebuggable = true
            // 禁用其他耗时操作（但zipalign不受影响）
            isCrunchPngs = false
            isMinifyEnabled = false
            isShrinkResources = false
            //signingConfig = signingConfigs.getByName("config")
        }
        release {
            applyProduct()
        }
    }
}

/**
 * lib包通用构建
 */
fun LibraryExtension.buildCompileOptions(project : Project){
    compileOptions{
        sourceCompatibility = JavaVersion.valueOf(project.findVersionToString("appJavaVersion"))
        targetCompatibility = JavaVersion.valueOf(project.findVersionToString("appJavaVersion"))
        encoding = "UTF-8"
    }
}
/**
 * lib-buildTypes 构建模版
 */
fun LibraryExtension.buildLibTypesTemp() {
    buildTypes {
        fun LibraryBuildType.applyProduct() {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // 测试环境包
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            applyProduct()
        }
    }
}