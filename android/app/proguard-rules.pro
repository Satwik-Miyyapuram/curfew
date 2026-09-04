# R8 rules for the Curfew app.
#
# The :policy library ships its own rules for the FFI and serialization surface, in
# policy/consumer-rules.pro. What is left here is what only the app itself has.
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

# SQLCipher loads its native library by name.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# Room's generated implementations are found reflectively by class name.
-keep class dev.curfew.app.data.CurfewDatabase_Impl { *; }
