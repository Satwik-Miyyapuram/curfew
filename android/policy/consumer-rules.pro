# R8 rules that travel with the :policy library.
#
# These cover :policy's own surface, so they belong here rather than in any one consumer: an app
# that depends on this module gets them automatically, and a second consumer cannot forget them.
# Stripping any of these produces a build that installs, launches, and enforces nothing.

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

# JNA's desktop AWT helpers reference java.awt, which does not exist on Android. Nothing on this
# path can be reached from an Android process, so the references are simply absent rather than
# broken, and R8 only needs to be told not to treat that as an error.
-dontwarn java.awt.**
