#!/usr/bin/env python3
"""Reject generated POM dependencies that Clojars will not publish."""
import sys
import xml.etree.ElementTree as ET

pom = ET.parse(sys.argv[1] if len(sys.argv) > 1 else "pom.xml")
floating = []
for dependency in pom.findall(".//{*}dependency"):
    version = dependency.findtext("{*}version")
    if version in {"LATEST", "RELEASE"}:
        floating.append("{}:{}:{}".format(
            dependency.findtext("{*}groupId"),
            dependency.findtext("{*}artifactId"), version))
if floating:
    raise SystemExit("Clojars rejects floating dependency versions: " + ", ".join(floating))
print("Generated release POM has no LATEST/RELEASE dependencies.")
