# Release ProGuard/R8 rules for the Dynamic Island engine library.
#
# Referenced by the `release` buildType in build.gradle.kts. Inert today
# because `isMinifyEnabled = false`; the file exists because the reference to
# it was already there and a release build with minification enabled would
# otherwise fail on the missing file.
#
# See consumer-rules.pro for the keep rules this library will need once
# minification is switched on.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
