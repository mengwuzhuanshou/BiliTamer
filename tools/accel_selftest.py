# -*- coding: utf-8 -*-
"""Desktop self-test for the accel stack, two stages:

  stage 1  accel core            — pure-Java accel/*.java + tools/acceltest
                                   against a fake Range-capable CDN.
  stage 2  accel hook layer      — accel/*.java + hooks/{HookApi,AccelHooks}.java
                                   + tools/accelhooks (fake host classes, fake
                                   libxposed stub) driving the real AccelHooks
                                   through a real AccelProxy on loopback.

Nothing here touches a device, the Android SDK, or the network. Run:
    python tools/accel_selftest.py
"""
import os
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_MAIN = os.path.join(PROJ, "app", "src", "main", "java")
ACCEL = os.path.join(JAVA_MAIN, "com", "tamer", "bili", "accel")
HOOKS = os.path.join(JAVA_MAIN, "com", "tamer", "bili", "hooks")
TEST_SRC = os.path.join(PROJ, "tools", "acceltest")
HOOKTEST_SRC = os.path.join(PROJ, "tools", "accelhooks")


def find_javac():
    for name in ("javac.exe", "javac"):
        env = os.environ.get("BILITAMER_JDK") or os.environ.get("JAVA_HOME")
        if env:
            cand = os.path.join(env, "bin", name)
            if os.path.exists(cand):
                return cand
    return None


def java_files(root):
    out = []
    for cur, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(cur, f))
    return sorted(out)


def run_stage(javac, java, out, sources, main_class):
    """Compile `sources` into `out` and run `main_class`; return exit code."""
    if os.path.isdir(out):
        for root, _dirs, files in os.walk(out):
            for f in files:
                os.remove(os.path.join(root, f))
    else:
        os.makedirs(out)

    cmd = [javac, "-nowarn", "-encoding", "UTF-8", "-d", out] + sources
    print("[javac] %s ... %d files" % (os.path.basename(out), len(sources)))
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        log = os.path.join(out, "javac.log")
        with open(log, "wb") as fh:
            fh.write(proc.stdout)
            fh.write(proc.stderr)
        print("javac FAILED, log: %s" % log)
        return 1

    run = subprocess.run(
        [java, "-Dfile.encoding=UTF-8", "-cp", out, main_class],
        capture_output=True,
    )
    # Windows 控制台是 GBK，中文断言名会糊成一团：原样落到日志文件里再看。
    log = os.path.join(out, "selftest.log")
    with open(log, "wb") as fh:
        fh.write(run.stdout)
        if run.returncode != 0:
            fh.write(run.stderr)
    print("log: %s" % log)
    print("[%s] exit=%d" % (main_class, run.returncode))
    return run.returncode


def main():
    javac = find_javac()
    if javac is None:
        print("no JDK: set BILITAMER_JDK or JAVA_HOME")
        return 2
    java = os.path.join(os.path.dirname(javac), "java.exe" if os.name == "nt" else "java")
    if not os.path.exists(java):
        java = "java"

    accel_sources = sorted(
        os.path.join(ACCEL, name) for name in os.listdir(ACCEL) if name.endswith(".java")
    )

    rc1 = run_stage(
        javac, java,
        os.path.join(PROJ, "build", "accel-selftest"),
        accel_sources + [os.path.join(TEST_SRC, "com", "tamer", "bili", "accel", "AccelSelfTest.java")],
        "com.tamer.bili.accel.AccelSelfTest",
    )
    if rc1 != 0:
        return rc1

    rc2 = run_stage(
        javac, java,
        os.path.join(PROJ, "build", "accel-hooks-test"),
        accel_sources
        + [
            os.path.join(HOOKS, "HookApi.java"),
            os.path.join(HOOKS, "AccelHooks.java"),
        ]
        + java_files(HOOKTEST_SRC),
        "com.tamer.bili.hookstest.AccelHooksSelfTest",
    )
    return rc2


if __name__ == "__main__":
    sys.exit(main())
