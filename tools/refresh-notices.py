#!/usr/bin/env python3
"""Inventory resolved runtime dependencies and preserve shipped component notices.

Run after resolving dependencies: python3 tools/refresh-notices.py
Requires the project's JDK/SDK/Gradle environment. No credentials are read.
"""
import io
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--module',choices=('app','inspector'),default='app')
args = parser.parse_args()
report = subprocess.run(
    [str(root / "gradlew"), f":{args.module}:dependencies", "--configuration", "debugRuntimeClasspath",
     "--no-daemon", "--max-workers=2", "--console=plain"],
    cwd=root, check=True, text=True, stdout=subprocess.PIPE,
).stdout
coordinates = set()
for line in report.splitlines():
    match = re.search(r"(?:[+\\]--- )([\w.-]+):([\w.-]+):([^\s]+)(?: -> ([^\s]+))?", line)
    if match:
        coordinates.add((match[1], match[2], match[4] or match[3]))
if not coordinates:
    raise SystemExit("No runtime dependencies resolved; refusing to replace notices")
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
rows, bundled, seen = [], [], set()
for group, artifact, version in sorted(coordinates):
    directory = cache / group / artifact / version
    pom = next(directory.glob("*/*.pom"), None)
    if pom is None:
        raise SystemExit(f"Missing license metadata for {group}:{artifact}:{version}")
    parsed = ET.parse(pom).getroot()
    licenses = [x.findtext("m:name", default="", namespaces=ns)
                for x in parsed.findall("m:licenses/m:license", ns)]
    if group == "com.google.android.gms" and any("Development Kit" in x for x in licenses):
        license_name = "[Google SDK terms](https://developer.android.com/studio/terms.html)"
    elif any("Apache" in x for x in licenses) or (group, artifact, version) == ("com.google.guava", "listenablefuture", "1.0"):
        # listenablefuture inherits the Apache-2.0 license from guava-parent.
        license_name = "Apache-2.0"
    else:
        raise SystemExit(f"Review dependency license before proceeding: {group}:{artifact}:{version}: {licenses}")
    module = f"{group}:{artifact}:{version}"
    rows.append(f"| `{module}` | {license_name} |")
    for archive in directory.glob("*/*"):
        if archive.suffix not in (".jar", ".aar"):
            continue
        pending = [(archive.name, archive.read_bytes())]
        for label, data in pending:
            with zipfile.ZipFile(io.BytesIO(data)) as package:
                for name in package.namelist():
                    if name.endswith(".jar") and archive.suffix == ".aar":
                        pending.append((label + "/" + name, package.read(name)))
                    if name.endswith("/") or not any(key in name.rsplit("/", 1)[-1].upper()
                                                      for key in ("LICENSE", "NOTICE", "COPYRIGHT")):
                        continue
                    content = package.read(name)
                    if content in seen:
                        continue
                    try:
                        text = content.decode("utf-8")
                    except UnicodeError:
                        continue
                    seen.add(content)
                    bundled.append(f"===== {module} / {label} / {name} =====\n{text}\n")
header = """# Third-party notices

The application's own source is Apache-2.0. Dependencies retain the terms below;
the project license does not relicense Google SDK components or imply endorsement.
Google authorization requires Google Play services on the device.

This inventory is generated from Gradle's resolved `debugRuntimeClasspath` and
cached Maven POM license metadata. It includes platform/BOM metadata nodes.
Refresh after dependency changes with `python3 tools/refresh-notices.py` in the
configured JDK/SDK shell; review new licenses before distributing.

| Resolved module | License |
| --- | --- |
"""
if args.module == 'inspector':
    header = header.replace('Google authorization requires Google Play services on the device.',
                            'This offline inspector does not include the Google authorization SDK or request Google authorization.')
footer = """
Gradle 8.9 and its generated Wrapper are Apache-2.0. Android SDK/build tools are
development prerequisites governed by their SDK terms, not application-owned code.

Test-only dependencies (not shipped in the APK): JUnit 4.13.2 (EPL-1.0),
Hamcrest Core 1.3 (BSD-3-Clause), and Vaadin's Android JSON port
0.0.20131108.vaadin1 (Apache-2.0), plus OkHttp MockWebServer and okhttp-tls
4.12.0 (Apache-2.0). No implementation from mizorewww/gdirect or its
aria2 patches is included. The historical ZIP in archive/ retains its GPL license.

Bundled component license/copyright/notice texts and the Apache-2.0 license are
preserved in `app/src/main/assets/third-party-notices.txt`, accessible from the
Drive screen's “许可证与第三方声明” menu. The Google SDK terms linked above apply
to the Google components in the inventory.
"""
if args.module == 'inspector':
    footer = footer.replace('Test-only dependencies (not shipped in the APK):',
                            "The main application's test-only dependencies (not shipped in this APK):")
    footer = footer.split('Bundled component license/copyright/notice texts')[0] + """Bundled component license/copyright/notice texts and the Apache-2.0 license are
preserved in `inspector/src/main/assets/third-party-notices.txt` inside the APK.
This module has no Google authorization or download implementation.
"""
inventory = header + "\n".join(rows) + "\n" + footer
notice_path = root / ('THIRD_PARTY_NOTICES.md' if args.module == 'app' else 'inspector/THIRD_PARTY_NOTICES.md')
notice_path.write_text(inventory)
asset = root / args.module / "src/main/assets/third-party-notices.txt"
asset.parent.mkdir(parents=True, exist_ok=True)
asset.write_text(inventory + "\n\n" + (root / "NOTICE").read_text()
                 + "\n\n" + (root / "LICENSE").read_text() + "\n\n" + "\n".join(bundled).rstrip() + "\n")
print(f"Recorded {len(coordinates)} runtime graph nodes and {len(bundled)} distinct bundled notices.")
