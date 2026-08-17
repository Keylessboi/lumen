# StAX (javax.xml.stream) — Smack's XML parser needs these on Android
# which doesn't ship the Java EE StAX API.
-dontwarn javax.xml.stream.**
-keep class javax.xml.stream.** { *; }

# Smack XMPP library — keep the XML pull parser factory reflection path
-keep class org.jivesoftware.smack.xml.stax.** { *; }
-dontwarn org.jivesoftware.smack.xml.stax.**

# MiniDns resolver — ServiceLoader discovery
-keep class org.minidns.** { *; }
-dontwarn org.minidns.**

# Bouncy Castle — multi-release JAR, used for Argon2id + X25519
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
