# Release ProGuard/R8 rules for the Dynamic Island host app.
#
# Referenced by the `release` buildType in app/build.gradle.kts. Inert today
# when R8 shrinking is enabled.
#
# The engine library ships its own keep rules in consumer-rules.pro; those are
# applied to this app automatically when it consumes the library, so do not
# duplicate them here.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep public class com.dynamicisland.engine.service.DynamicIslandAccessibilityService { *; }
-keep public class com.dynamicisland.engine.service.DynamicIslandService { *; }
-keep class com.dynamicisland.engine.core.model.** { *; }
