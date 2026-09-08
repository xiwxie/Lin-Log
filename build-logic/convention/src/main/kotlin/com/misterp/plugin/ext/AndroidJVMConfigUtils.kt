package com.misterp.plugin.ext

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * @Description: 当前file作用描述
 * @Author: pengshilin
 * @CreateDate: 2025/7/31 10:23
 */
fun Project.libApplyJvmConfig() {
    this.run { // 使用 target.run {} 或 with(target) {} 确保在 Project 上下文
        applyJvmConfig()
        // 2. 配置 Kotlin 编译选项
        // Kotlin 插件的 ID 可以是 "org.jetbrains.kotlin.android" (for Android projects)
        // or "org.jetbrains.kotlin.jvm" (for pure JVM/Kotlin projects)
        // 使用 extensions.configure 来获取并配置 KotlinJvmProjectExtension
        plugins.withId("org.jetbrains.kotlin.android") {
            extensions.configure(KotlinAndroidProjectExtension::class.java) { // 使用 KotlinJvmProjectExtension
                jvmToolchain {
                    languageVersion.set(JavaLanguageVersion.of(findVersionToString("appJVMtoolchain")))
                }
                compilerOptions {
                    languageVersion.set(KotlinVersion.KOTLIN_2_1)
                    apiVersion.set(KotlinVersion.KOTLIN_2_1)
                    jvmTarget.set(JvmTarget.valueOf(findVersionToString("appJVMTarget")))
                    suppressWarnings.set(true)
                    freeCompilerArgs.addAll(
                        listOf(
                            "-Xjvm-default=all",
                            "-Xskip-prerelease-check",
                            "-opt-in=kotlin.RequiresOptIn",
                            "-Xexplicit-api=strict",
                            "-nowarn"
                        )
                    )
                }
            }
        }
    }
}

fun Project.appApplyJvmConfig() {
    this.run {
        applyJvmConfig()
        plugins.withId("org.jetbrains.kotlin.android") {
            extensions.configure(KotlinAndroidProjectExtension::class.java) {
                jvmToolchain {
                    languageVersion.set(JavaLanguageVersion.of(findVersionToString("appJVMtoolchain")))
                }
                compilerOptions {
                    languageVersion.set(KotlinVersion.KOTLIN_2_1)
                    apiVersion.set(KotlinVersion.KOTLIN_2_1)
                    jvmTarget.set(JvmTarget.valueOf(findVersionToString("appJVMTarget")))
                    suppressWarnings.set(true)
                    freeCompilerArgs.addAll(
                        listOf(
                            "-Xjvm-default=all",
                            "-Xskip-prerelease-check",
                            "-opt-in=kotlin.RequiresOptIn",
                            "-nowarn"
                        )
                    )
                }
            }
        }
    }
}

private fun Project.applyJvmConfig() {
    this.run { // 使用 target.run {} 或 with(target) {} 确保在 Project 上下文
        // 配置 Java 插件 (如果项目应用了 java 插件)
        plugins.withId("java") { // 'java' 插件ID
            extensions.configure(JavaPluginExtension::class.java) {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(findVersionToString("appJVMtoolchain")))
                }
            }
        }
        plugins.withId("java-library") { // 'java-library' 插件ID
            extensions.configure(JavaPluginExtension::class.java) {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(findVersionToString("appJVMtoolchain")))
                }
            }
        }

        // 2. 配置 Kotlin 编译选项
        // Kotlin 插件的 ID 可以是 "org.jetbrains.kotlin.android" (for Android projects)
        // or "org.jetbrains.kotlin.jvm" (for pure JVM/Kotlin projects)
        // 使用 extensions.configure 来获取并配置 KotlinJvmProjectExtension

        plugins.withId("org.jetbrains.kotlin.jvm") { // 对于纯 Kotlin JVM 项目
            extensions.configure(KotlinAndroidProjectExtension::class.java) {
                jvmToolchain {
                    languageVersion.set(JavaLanguageVersion.of(findVersionToString("appJVMtoolchain")))
                }
                compilerOptions {
                    languageVersion.set(KotlinVersion.KOTLIN_2_1)
                    apiVersion.set(KotlinVersion.KOTLIN_2_1)
                    jvmTarget.set(JvmTarget.valueOf(findVersionToString("appJVMTarget")))
                    suppressWarnings.set(true)
                    freeCompilerArgs.addAll(
                        listOf(
                            "-Xjvm-default=all",
                            "-Xskip-prerelease-check",
                            "-opt-in=kotlin.RequiresOptIn",
                            "-Xexplicit-api=strict",
                            "-nowarn"
                        )
                    )
                    freeCompilerArgs.add("-Xuse-k2-jvm")
                }
            }
        }
    }
}