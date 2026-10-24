# Release (R8) keep rules. Debug builds do not minify, so a missing rule
# here only shows up in the release APK — on the first connection.

# Smack: providers, initializers and the XML parser factory are looked up
# by class name (smack-config.xml, *.providers, META-INF/services).
-keep class org.jivesoftware.smack.** { *; }
-keep class org.jivesoftware.smackx.** { *; }
-keep class org.jxmpp.** { *; }
-dontwarn org.jivesoftware.smack.**
-dontwarn org.jivesoftware.smackx.**
-dontwarn org.jxmpp.**
# Android has no StAX; Smack's xpp3 backend uses the platform XmlPullParser.
-dontwarn javax.xml.stream.**
-dontwarn org.xmlpull.**
-keep class org.xmlpull.** { *; }

# MiniDNS — ServiceLoader discovery and reflection.
-keep class org.minidns.** { *; }
-dontwarn org.minidns.**

# Bouncy Castle — Argon2id (export), X25519, HKDF (E2EE).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Desktop-only JDK classes Smack references but never uses on Android.
-dontwarn javax.naming.**
-dontwarn javax.security.sasl.**
-dontwarn java.beans.**
