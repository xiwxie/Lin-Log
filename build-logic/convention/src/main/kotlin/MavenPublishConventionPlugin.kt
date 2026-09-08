import com.android.build.gradle.LibraryExtension
import com.misterp.plugin.ext.PublishExtension
import com.misterp.plugin.ext.findLocalProperty
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

class MavenPublishConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            // 1. 应用官方插件
            pluginManager.apply("maven-publish")
            // 情况 A：如果当前模块是 Android Library (例如 minirouter-api)
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> {
                    publishing {
                        singleVariant("release") {
                            withSourcesJar()
                        }
                    }
                }
            }

            // 情况 B：如果当前模块是纯 Kotlin JVM 库 (例如 minirouter-compiler)
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                // 为 JVM 模块配置源码打包
                extensions.configure<JavaPluginExtension> {
                    withSourcesJar()
                }
            }
            // 2. 创建供子模块使用的扩展配置 publishInfo { ... }
            val extension = extensions.create<PublishExtension>("publishInfo")

            // 3. 配置发布任务
            // Android 必须在 afterEvaluate 之后才能获取到 components["release"]
            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications {
                        create<MavenPublication>("maven") {
                            // 自动选择：如果有 Android release 就用 Android，否则尝试用 Java
                            val releaseComponent = components.findByName("release") ?: components.findByName("java")
                            releaseComponent?.let { from(it) }
                            // 使用扩展中的值，如果没填则使用默认值
                            groupId = extension.groupId
                            artifactId = extension.artifactId ?: project.name
                            version = extension.version
                            println("LinRouter-Publish: Publishing ${groupId}:${artifactId}:${version}")
                            pom {
                                name.set(extension.artifactId ?: project.name)
                                description.set(extension.description)
                            }
                        }
                    }

                    // 预设仓库地址，子模块不可见，实现隐藏复杂度
                    repositories {
                        maven {
                            // 替换公司 Nexus 的仓库地址
                            url = uri(project.findLocalProperty("NEXUS_URL") ?: "")
                            val nexusUser = project.findLocalProperty("NEXUS_USERNAME") ?: ""
                            val nexusPassword = project.findLocalProperty("NEXUS_PASSWORD") ?: ""
                            credentials {
                                // 建议放在 local.properties 或环境变量中，不要硬编码
                                username = nexusUser
                                password = nexusPassword
                            }
                            // 如果 Nexus 是 http 而非 https，需要允许不安全协议
                            isAllowInsecureProtocol = true
                        }
                    }
                }
            }
        }
    }
}