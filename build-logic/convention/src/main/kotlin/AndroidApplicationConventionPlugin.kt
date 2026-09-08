import com.android.build.api.dsl.ApplicationExtension
import com.misterp.plugin.ext.appApplyJvmConfig
import com.misterp.plugin.ext.buildCompileOptions
import com.misterp.plugin.ext.buildTypesTemp
import com.misterp.plugin.ext.findVersionToInt
import com.misterp.plugin.ext.findVersionToString
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * @Description: Application-Module 通用模版
 * @Author: pengshilin
 * @CreateDate: 2025/7/16 15:14
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "org.jetbrains.kotlin.android")
            this.appApplyJvmConfig()
            extensions.configure<ApplicationExtension> {
                compileSdk = findVersionToInt("appCompileSdk")
                defaultConfig.apply {
                    targetSdk = findVersionToInt("appTargetSdk")
                    minSdk = findVersionToInt("appMinSdk")
                    versionCode = findVersionToInt("appVersionCode")
                    versionName = findVersionToString("appVersionName")
                    multiDexEnabled = true
                    vectorDrawables.useSupportLibrary = true
                    ndk {
                        // 设置支持的SO库架构
                        abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
                    }
                    packaging {
                        resources {
                            excludes +=
                                listOf(
                                    "META-INF/library_release.kotlin_module",
                                )
                        }
                    }
                }
                sourceSets {
                    getByName("main") {
                        java.srcDirs("src/main/java")
                    }
                }
                buildCompileOptions(project)

                bundle {
                    language {
                        // 是否开启语言分包，当为true在这里可以添加inclue ‘ch-ZH’,配置预设语言
                        enableSplit = false
                    }
                    // 分辨率分包
                    density {
                        enableSplit = true
                    }
                    // cpu内核分包
                    abi {
                        enableSplit = true
                        // include "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
                    }
                }

                packaging {
                    jniLibs {
                        useLegacyPackaging = true
                    }
                }

                //productFlavorsTmp()
                //signConfigTmp(target)
                buildTypesTemp()
            }
        }
    }

}