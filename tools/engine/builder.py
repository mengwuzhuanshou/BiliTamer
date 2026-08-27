# -*- coding: utf-8 -*-
r"""BiliTamer standalone module build engine (no Android SDK required).

Pipeline: javac(--release 8, compile stubs) -> dalvik-dx -> hand-written AXML
manifest -> zip (STORED arsc, first-entry 4-byte aligned) -> apksig v1+v2+v3
signing. Keystore is read from tools/signing.local (never committed).

Environment overrides:
  BILITAMER_JDK         JDK home (else JAVA_HOME, else javac/java on PATH)
  BILITAMER_DX_JAR      dx jar (default: tools/engine/dalvik-dx-11.0.0_r3.jar)
  BILITAMER_APKSIG_JAR  apksig jar (default: tools/engine/apksig-7.4.1.jar)
  BILITAMER_EXTRA_CP    extra javac classpath entries (libxposed API is wired
                        automatically by tools/build_module.py)
  BILITAMER_KEYSTORE / BILITAMER_KS_PASS / BILITAMER_KS_ALIAS

Project layout expected by build_project(proj):
  proj/module_conf.py                    MODULE dict (package/versions/entry...)
  proj/app/src/main/java/**/*.java       module sources
  proj/app/src/main/assets/xposed_init   classic-API entry file (optional)
  proj/tools/engine/                     this engine (builder.py, axml_writer.py,
                                         arsc_builder.py, SignApk.java, build-stub/,
                                         dalvik-dx jar, apksig jar)
  proj/tools/libxposed/classes.jar       libxposed API jar (compile classpath)
  proj/tools/signing.local               KS_PATH= / KS_PASS= / KS_ALIAS=
"""
import os
import subprocess
import sys
import zipfile

MB = os.path.dirname(os.path.abspath(__file__))


def _find_jdk():
    jdk = os.environ.get("BILITAMER_JDK") or os.environ.get("JAVA_HOME")
    if jdk:
        b = os.path.join(jdk, "bin")
        exe = ".exe" if os.name == "nt" else ""
        if os.path.exists(os.path.join(b, "javac" + exe)):
            return os.path.join(b, "javac" + exe), os.path.join(b, "java" + exe)
    return "javac", "java"


JAVAC, JAVA = _find_jdk()
DX_JAR = os.environ.get("BILITAMER_DX_JAR",
                        os.path.join(MB, "dalvik-dx-11.0.0_r3.jar"))
APKSIG_JAR = os.environ.get("BILITAMER_APKSIG_JAR",
                            os.path.join(MB, "apksig-7.4.1.jar"))


def resolve_keystore(proj):
    """Keystore resolution: env BILITAMER_* > project tools/signing.local."""
    ks = os.environ.get("BILITAMER_KEYSTORE")
    ks_pass = os.environ.get("BILITAMER_KS_PASS")
    ks_alias = os.environ.get("BILITAMER_KS_ALIAS")
    if ks is None:
        local = os.path.join(proj, "tools", "signing.local")
        if os.path.exists(local):
            vals = {}
            with open(local, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    vals[k.strip()] = v.strip()
            ks = vals.get("KS_PATH")
            ks_pass = vals.get("KS_PASS")
            ks_alias = vals.get("KS_ALIAS")
    if ks is None:
        raise SystemExit(
            "no keystore: create tools/signing.local with KS_PATH/KS_PASS/KS_ALIAS "
            "(generate one via: keytool -genkeypair -v -keystore my.jks -alias mymod "
            "-keyalg RSA -keysize 2048 -validity 10000), or export "
            "BILITAMER_KEYSTORE/BILITAMER_KS_PASS/BILITAMER_KS_ALIAS")
    if ks_pass is None:
        ks_pass = ""
    if ks_alias is None:
        ks_alias = ""
    return ks, ks_pass, ks_alias


sys.path.insert(0, MB)
import axml_writer  # noqa: E402
import arsc_builder  # noqa: E402


def load_conf(proj):
    path = os.path.join(proj, "module_conf.py")
    if not os.path.exists(path):
        raise SystemExit("missing module_conf.py in " + proj)
    ns = {}
    with open(path, "rb") as f:
        exec(compile(f.read(), path, "exec"), ns)
    c = ns.get("MODULE")
    if not isinstance(c, dict):
        raise SystemExit("module_conf.py must define MODULE dict")
    return c


def run(cmd, desc):
    print("==>", desc)
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        print(p.stdout[-4000:])
        print(p.stderr[-4000:])
        raise SystemExit("FAILED: " + desc)
    if p.stdout.strip():
        print(p.stdout.strip()[-1500:])
    if p.stderr.strip():
        print(p.stderr.strip()[-1500:])


def java_sources(base):
    out = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for fn in filenames:
            if fn.endswith(".java"):
                out.append(os.path.join(dirpath, fn))
    return out


def main(proj):
    cfg = load_conf(proj)
    pkg = cfg["package"]
    vname = cfg["version_name"]
    vcode = cfg["version_code"]
    label = cfg.get("app_label", pkg)
    desc = cfg.get("xposed_description", pkg)
    scope = cfg.get("xposed_scope", "")
    dist_name = cfg.get("dist_name", pkg + "-v%s.apk")
    icon_rel = cfg.get("icon_png")
    ICON_PNG = os.path.join(proj, icon_rel) if icon_rel else None
    ICON_RES_PATH = "res/drawable/ic_launcher.png"
    # libxposed mode: META-INF/xposed/java_init.list + module.prop + scope.list,
    # no classic xposedmodule metadata (LSPosed 2.x loads it reliably).
    libxposed = bool(cfg.get("libxposed", False))
    xposed_entry = cfg.get("xposed_entry", pkg + ".MainHook") if libxposed else None

    BUILD = os.path.join(proj, "build")
    DIST = os.path.join(proj, "dist")
    STUB_SRC = os.path.join(MB, "build-stub")
    APP_SRC = os.path.join(proj, "app", "src", "main", "java")
    ASSETS = os.path.join(proj, "app", "src", "main", "assets")

    UTF16_FLAG = "--utf16" in sys.argv
    ORDER_STD = "--order-std" in sys.argv

    os.makedirs(os.path.join(BUILD, "stub_classes"), exist_ok=True)
    os.makedirs(os.path.join(BUILD, "app_classes"), exist_ok=True)
    os.makedirs(DIST, exist_ok=True)

    run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn", "-d",
         os.path.join(BUILD, "stub_classes")]
        + java_sources(STUB_SRC), "compile shared stubs")

    extra_cp = os.environ.get("BILITAMER_EXTRA_CP")
    cp = os.path.join(BUILD, "stub_classes")
    if extra_cp:
        cp += os.pathsep + extra_cp
    run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
         "-cp", cp, "-d",
         os.path.join(BUILD, "app_classes")]
        + java_sources(APP_SRC), "compile module")

    run([JAVA, "-cp", DX_JAR, "com.android.dx.command.Main",
         "--dex", "--min-sdk-version=26",
         "--output", os.path.join(BUILD, "classes.dex"),
         os.path.join(BUILD, "app_classes")], "dex")

    manifest = axml_writer.build_module_manifest(
        package=pkg,
        version_code=vcode,
        version_name=vname,
        min_sdk=26,
        target_sdk=34,
        app_label=label,
        activities=[{
            "name": pkg + ".ui.SettingsActivity",
            "label": label,
            "exported": True,
            "launcher": True,
            "module_settings": True,
        }],
        meta_datas=([] if libxposed else [
            ("xposedmodule", ("bool", True)),
            ("xposeddescription", desc),
            ("xposedminversion", ("int", 93)),
            ("xposedscope", scope),
        ]),
        allow_backup=False,
        icon_ref=("0x7f020000" if ICON_PNG and os.path.exists(ICON_PNG) else None),
        utf16=UTF16_FLAG,
        order_std=True,
    )
    man_path = os.path.join(BUILD, "AndroidManifest.xml")
    with open(man_path, "wb") as f:
        f.write(manifest)
    print("manifest bytes:", len(manifest))

    has_icon = ICON_PNG is not None and os.path.exists(ICON_PNG)
    arsc = arsc_builder.build_minimal_arsc(pkg, label, icon_path=ICON_RES_PATH)
    arsc_path = os.path.join(BUILD, "resources.arsc")
    with open(arsc_path, "wb") as f:
        f.write(arsc)
    print("resources.arsc bytes:", len(arsc), "icon:", has_icon)

    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(arsc_path, "resources.arsc", compress_type=zipfile.ZIP_STORED)
        z.write(man_path, "AndroidManifest.xml")
        z.write(os.path.join(BUILD, "classes.dex"), "classes.dex")
        if libxposed:
            meta_dir = os.path.join(BUILD, "META-INF", "xposed")
            os.makedirs(meta_dir, exist_ok=True)
            with open(os.path.join(meta_dir, "java_init.list"), "w", encoding="utf-8") as f:
                f.write(xposed_entry + "\n")
            with open(os.path.join(meta_dir, "module.prop"), "w", encoding="utf-8") as f:
                f.write("minApiVersion=101\ntargetApiVersion=102\nstaticScope=true\n")
            if scope:
                with open(os.path.join(meta_dir, "scope.list"), "w", encoding="utf-8") as f:
                    f.write(scope + "\n")
            z.write(os.path.join(meta_dir, "java_init.list"), "META-INF/xposed/java_init.list")
            z.write(os.path.join(meta_dir, "module.prop"), "META-INF/xposed/module.prop")
            if scope:
                z.write(os.path.join(meta_dir, "scope.list"), "META-INF/xposed/scope.list")
        else:
            xinit = os.path.join(ASSETS, "xposed_init")
            z.write(xinit, "assets/xposed_init", compress_type=zipfile.ZIP_STORED)
        if has_icon:
            z.write(ICON_PNG, ICON_RES_PATH)
    print("unsigned apk:", os.path.getsize(unsigned), "bytes")

    with zipfile.ZipFile(unsigned) as z:
        info = z.getinfo("resources.arsc")
        name_len = len("resources.arsc".encode())
        extra_len = len(info.extra)
        data_off = info.header_offset + 30 + name_len + extra_len
        print("arsc compress_type=%d data_offset=%d aligned=%s"
              % (info.compress_type, data_off, data_off % 4 == 0))
        assert info.compress_type == 0 and data_off % 4 == 0, "arsc alignment check failed"

    sign_classes = os.path.join(BUILD, "sign_classes")
    os.makedirs(sign_classes, exist_ok=True)
    run([JAVAC, "--release", "8", "-nowarn", "-cp", APKSIG_JAR, "-d",
         sign_classes, os.path.join(MB, "SignApk.java")],
        "compile SignApk")

    suffix = "_utf16" if UTF16_FLAG else ""
    signed = os.path.join(DIST, dist_name % (vname + suffix))
    cp = APKSIG_JAR + os.pathsep + sign_classes
    ks, ks_pass, ks_alias = resolve_keystore(proj)
    print("signing keystore:", ks, "alias:", ks_alias)
    run([JAVA, "-cp", cp, "SignApk", unsigned, signed, ks, ks_pass,
         ks_alias, ks_pass, "true"], "sign apk")

    print()
    print("SIGNED MODULE APK:", signed)
    print("size:", os.path.getsize(signed), "bytes")


def build_project(proj):
    main(os.path.abspath(proj))


if __name__ == "__main__":
    main(os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.getcwd()))
