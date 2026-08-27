# libxposed API provenance

- Artifact: io.github.libxposed:api, version 102.0.0
- Source: https://github.com/libxposed/api (release 102.0.0; also published on Maven Central)
- License: Apache License 2.0
- Files kept here:
  - api.aar — the original release artifact
  - classes.jar — the classes.jar extracted from that AAR, used as the javac
    classpath entry by tools/build_module.py

The API jar is compile-time only; nothing from it is repackaged into the built
module APK (the host LSPosed implementation provides the classes at runtime).
