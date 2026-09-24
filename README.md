# Braid companion for Android

Turns an Android phone into a relay for the Braid desktop download manager:
an HTTP forward proxy on port 8710 that sends each lane's traffic over one
chosen network (mobile data, Wi-Fi), so the desktop gains a download path.

- `relay/`: the proxy, plain JVM Kotlin with no Android dependencies, tested on the desktop JVM.
- `app/`: the Android app that runs it.

The wire contract is defined by the desktop (`crates/dl-net/src/control.rs` in
the braid repository); where this code and that disagree, the desktop wins.

```
./gradlew :relay:test          # proxy tests
./gradlew :app:assembleDebug   # APK
```
