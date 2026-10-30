# Consumer ProGuard rules for the Dynamic Island engine library.
#
# These rules are packaged INTO the AAR and applied automatically to any app
# that consumes the library.

# Framework Services
-keep class com.dynamicisland.engine.service.DynamicIslandService { *; }
-keep class com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener { *; }

# Public Engine Singletons & Companion Objects
-keep class com.dynamicisland.engine.service.DynamicIslandService$Companion { *; }
-keep class com.dynamicisland.engine.settings.IslandSettings { *; }
-keep class com.dynamicisland.engine.settings.IslandSettings$Companion { *; }

# Suppress hidden framework reflection warnings

# Preserve multi-threaded volatile memory semantics
-keepclassmembers class * {
    @kotlin.jvm.Volatile <fields>;
}

# Coroutines Internal Dispatchers
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
