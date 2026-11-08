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

Project-owned source is GPL-3.0-only. Dependencies retain the terms below.
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
aria2 patches is included. The historical ZIP in docs/history/prototype/ retains its GPL license.

The APK includes the GPL-3.0 license, the Android linking permission, and
third-party notices. View them in Settings under “许可证与第三方声明”.
The Google SDK terms apply to the Google components listed above.
"""
if args.module == 'inspector':
    footer = footer.replace('Test-only dependencies (not shipped in the APK):',
                            "The main application's test-only dependencies (not shipped in this APK):")
    footer = footer.split('The APK includes the GPL-3.0 license')[0] + """The inspector APK includes the GPL-3.0 license and third-party notices.
This module has no Google authorization or download implementation.
"""
if args.module == 'app':
    footer += "\nPublic GTS Root R1–R4 certificates: Mozilla NSS root collection subset (MPL-2.0).\nSee docs/TRUST_ROOTS.md and app/src/main/resources/gts-roots.LICENSE; no CA private keys are included.\n"
    bundled.append((root / "app/src/main/resources/gts-roots.LICENSE").read_text())
inventory = header + "\n".join(rows) + "\n" + footer
notice_path = root / ('THIRD_PARTY_NOTICES.md' if args.module == 'app' else 'tools/legacy-inspector/THIRD_PARTY_NOTICES.md')
notice_path.write_text(inventory)
module_dir = root / ('tools/legacy-inspector' if args.module == 'inspector' else 'app')
asset = module_dir / "src/main/assets/third-party-notices.txt"
asset.parent.mkdir(parents=True, exist_ok=True)
license_texts = [(root / "LICENSE").read_text()]
if args.module == 'app':
    license_texts.append((root / "LICENSES/Google-Play-Services-exception.txt").read_text())
license_texts.append((root / "LICENSES/Apache-2.0.txt").read_text())
asset.write_text(inventory + "\n\n" + (root / "NOTICE").read_text()
                 + "\n\n" + "\n\n".join(license_texts)
                 + "\n\n" + "\n".join(bundled).rstrip() + "\n")
print(f"Recorded {len(coordinates)} runtime graph nodes and {len(bundled)} distinct bundled notices.")
