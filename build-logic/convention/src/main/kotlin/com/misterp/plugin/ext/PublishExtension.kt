package com.misterp.plugin.ext

// build-logic/src/main/kotlin/PublishExtension.kt
open class PublishExtension {
    var artifactId: String? = null
    var groupId: String = "com.yourcompany.android" // 默认值
    var version: String = "1.0.0"                   // 默认值
    var description: String = ""
}