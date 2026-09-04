# R8 rules for the Curfew app.
#
# Three things here are reachable only from outside the app's own code, so R8 cannot see that they
# are used. Each of them, if stripped, produces an app that installs and launches and silently
# enforces nothing — the worst failure this project has.

# Entry points named in the manifest rather than called from Kotlin.
-keep class dev.curfew.app.CurfewApplication { *; }
-keep class dev.curfew.app.enforce.CurfewAccessibilityService { *; }
-keep class dev.curfew.app.enforce.EnforcementService { *; }
-keep class dev.curfew.app.enforce.BootReceiver { *; }
-keep class dev.curfew.app.enforce.ScheduleAlarmReceiver { *; }
-keep class dev.curfew.app.block.BlockActivity { *; }
-keep class dev.curfew.app.ui.MainActivity { *; }

# The kotlinx.serialization shapes are the FFI wire format. A renamed field is a decode failure at
# the boundary, which means no policy at all.
-keepclassmembers class dev.curfew.policy.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.curfew.policy.**$$serializer { *; }
-keep class dev.curfew.policy.** { *; }

# UniFFI's generated bindings are called from Rust through JNA callbacks.
-keep class uniffi.curfew_ffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Structure { *; }

# SQLCipher loads its native library by name.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# Room's generated implementations are found reflectively by class name.
-keep class dev.curfew.app.data.CurfewDatabase_Impl { *; }
