# PebbleKit 2 talks to the Pebble app through Bundles and an AIDL binder.
-keep class io.rebble.pebblekit2.** { *; }
# The Pebble app binds to this service by its intent filter.
-keep class app.emberio.pebble.WatchListenerService { *; }
