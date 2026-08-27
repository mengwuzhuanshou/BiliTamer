# Third-party components in tools/engine/

This directory vendors the complete build toolchain so the repository builds
standalone, without an Android SDK.

| File | Origin | License |
| --- | --- | --- |
| builder.py, axml_writer.py, arsc_builder.py, SignApk.java | This project (MIT) | MIT |
| build-stub/ | This project (MIT) — compile-time stubs of the Android API surface used by the module; stubs are never packaged into the APK | MIT |
| dalvik-dx-11.0.0_r3.jar | AOSP dalvik/dx (used exactly as distributed by the Android Open Source Project) | Apache License 2.0 |
| apksig-7.4.1.jar | Android apksig library (tools/base/apksig, v7.4.1) | Apache License 2.0 |

The libxposed API used at compile time lives in tools/libxposed/ — see
PROVENANCE.md there (io.github.libxposed:api, Apache License 2.0).
