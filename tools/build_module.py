# -*- coding: utf-8 -*-
"""One-shot build entry: python tools/build_module.py

The engine is vendored at tools/engine/ (no Android SDK, no workspace deps).
Requires: a JDK (BILITAMER_JDK or JAVA_HOME or javac/java on PATH) and
tools/signing.local for the signing key (never committed).
"""
import os
import shutil
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# libxposed API jar on the javac classpath automatically
os.environ.setdefault("BILITAMER_EXTRA_CP",
                      os.path.join(PROJ, "tools", "libxposed", "classes.jar"))

sys.path.insert(0, os.path.join(PROJ, "tools", "engine"))
import builder  # noqa: E402

# javac never deletes output classes, so a removed/reordered anonymous inner class
# stays in build/app_classes and gets dexed into the shipped apk as dead code.
shutil.rmtree(os.path.join(PROJ, "build", "app_classes"), ignore_errors=True)

builder.build_project(PROJ)
