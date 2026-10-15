"""Validate a release ZIP, its checksums, and every detached GPG signature."""

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def verify(bundle: Path, gpg: str, homedir: str,
           coordinates: str) -> None:
    group, artifact_id, version = coordinates.split(":")
    if group != "io.github.rwpp-team" or artifact_id not in ("rwml-core", "game-lib"):
        raise ValueError("Unexpected publication coordinates")
    prefix = group.replace(".", "/") + f"/{artifact_id}/{version}/"
    base = artifact_id + "-" + version
    suffixes = [".pom", ".jar", "-sources.jar", "-javadoc.jar"]
    if artifact_id == "rwml-core":
        suffixes.append(".module")
    artifacts = [base + suffix for suffix in suffixes]
    with ZipFile(bundle) as archive, tempfile.TemporaryDirectory() as directory:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        allowed = {prefix + name + suffix for name in artifacts
                   for suffix in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512")}
        unexpected = {name for name in names if not name.endswith("/")} - allowed
        if unexpected:
            raise ValueError(f"Unexpected artifacts: {sorted(unexpected)}")
        for name in artifacts:
            payload = archive.read(prefix + name)
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum_path = prefix + name + "." + algorithm
                if checksum_path not in names and algorithm in ("sha256", "sha512"):
                    continue
                expected = archive.read(checksum_path).decode("ascii").strip().lower()
                if hashlib.new(algorithm, payload).hexdigest() != expected:
                    raise ValueError(f"Invalid {algorithm} checksum: {name}")
            artifact = Path(directory, name)
            signature = Path(directory, name + ".asc")
            artifact.write_bytes(payload)
            signature.write_bytes(archive.read(prefix + name + ".asc"))
            result = subprocess.run([gpg, "--homedir", homedir, "--batch", "--verify",
                                     str(signature), str(artifact)], capture_output=True, text=True)
            if result.returncode:
                raise ValueError(f"Invalid signature for {name}: {result.stderr}")
        pom = ET.fromstring(archive.read(prefix + base + ".pom"))
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        required = {
            "groupId": group, "artifactId": artifact_id, "version": version,
            "licenses/license/name": ("CC0 1.0 Universal" if artifact_id == "game-lib"
                                       else "RWPP-Team Shield License 1.0.0"),
            "developers/developer/email": "1225327866@qq.com",
        }
        for path, value in required.items():
            if pom.findtext("/".join("m:" + part for part in path.split("/")), namespaces=ns) != value:
                raise ValueError(f"Incorrect POM metadata: {path}")
        if pom.findall("m:dependencies/m:dependency", ns):
            raise ValueError("Publication must not introduce runtime dependencies")
        if artifact_id == "game-lib":
            required_classes = (
                "com/corrodinggames/rts/gameFramework/GameEngine",
                "com/corrodinggames/rts/game/RWGameEngine",
                "com/corrodinggames/rts/java/MainLauncher",
            )
            with ZipFile(io.BytesIO(archive.read(prefix + base + ".jar"))) as binary:
                for name in required_classes:
                    if name + ".class" not in binary.namelist():
                        raise ValueError(f"Missing mapped class: {name}")
            with ZipFile(io.BytesIO(archive.read(prefix + base + "-sources.jar"))) as sources:
                for name in required_classes:
                    if name + ".java" not in sources.namelist():
                        raise ValueError(f"Missing mapped source: {name}")
                if not sources.namelist() or any(not name.endswith(".java") for name in sources.namelist()):
                    raise ValueError("Source archive must contain decompiled Java sources")
    print(f"Central bundle OK: {coordinates}, POM, sources, Javadoc, checksums, and {len(artifacts)} valid signatures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--gpg", required=True)
    parser.add_argument("--homedir", required=True)
    parser.add_argument("--coordinates", required=True)
    args = parser.parse_args()
    verify(args.bundle, args.gpg, args.homedir, args.coordinates)
