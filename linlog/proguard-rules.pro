-keep class com.lin.log.LinLog { *; }
-keep class com.lin.log.LinLogger { *; }
-keep class com.lin.log.LinLogConfiguration** { *; }
-keep class com.lin.log.LogLevel { *; }
-keep class com.lin.log.LinLogConstants { *; }
-keep class com.lin.log.internal.** { *; }
-keep class com.lin.log.printer.** { *; }
-keep class com.lin.log.uploader.** { *; }
-keep class com.lin.log.cleaner.** { *; }
-keep class com.lin.log.formatter.** { *; }

# 保留行号与源文件名映射
-keepattributes SourceFile,LineNumberTable
