#!/usr/bin/env python3
"""Merges a library's AndroidManifest.xml (from an .aar) into the app's manifest.

Gradle normally does this; this build has no Gradle. Usage:
    merge_manifest.py app/AndroidManifest.xml lib/AndroidManifest.xml com.example.app > merged.xml
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
NAME = "{%s}name" % ANDROID


def key(el):
    return (el.tag, el.get(NAME))


def main(app_path, lib_path, package):
    app = ET.parse(app_path)
    root = app.getroot()
    app_application = root.find("application")
    with open(lib_path, encoding="utf-8") as f:
        lib_text = f.read().replace("${applicationId}", package)
    lib = ET.fromstring(lib_text)

    existing_top = {key(e) for e in root}
    for el in lib:
        if el.tag in ("uses-sdk", "application"):
            continue
        if el.tag != "queries" and key(el) in existing_top:
            continue
        root.insert(list(root).index(app_application), el)
        existing_top.add(key(el))

    lib_application = lib.find("application")
    if lib_application is not None:
        existing_app = {key(e) for e in app_application}
        for el in lib_application:
            if key(el) not in existing_app:
                app_application.append(el)
                existing_app.add(key(el))

    # This app works without ARCore (3DoF modes), so mark it optional.
    if not any(e.tag == "meta-data" and e.get(NAME) == "com.google.ar.core" for e in app_application):
        meta = ET.SubElement(app_application, "meta-data")
        meta.set(NAME, "com.google.ar.core")
        meta.set("{%s}value" % ANDROID, "optional")

    sys.stdout.write(ET.tostring(root, encoding="unicode"))


if __name__ == "__main__":
    main(*sys.argv[1:4])
