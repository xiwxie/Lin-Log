import com.android.build.gradle.LibraryExtension
import com.misterp.plugin.ext.buildCompileOptions
import com.misterp.plugin.ext.buildLibTypesTemp
import com.misterp.plugin.ext.findVersionToInt
import com.misterp.plugin.ext.libApplyJvmConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * @Description: lib库通用模版
 * @Author: pengshilin
 * @CreateDate: 2025/6/13 09:45
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            apply(plugin = "com.android.library")
            apply(plugin = "org.jetbrains.kotlin.android")
            this.libApplyJvmConfig()
            extensions.configure<LibraryExtension> {
                compileSdk = findVersionToInt("appCompileSdk")
                defaultConfig.apply {
                    targetSdk = findVersionToInt("appTargetSdk")
                    minSdk = findVersionToInt("appMinSdk")
                }
                buildCompileOptions(project)
                buildLibTypesTemp()
            }

        }
    }
}
