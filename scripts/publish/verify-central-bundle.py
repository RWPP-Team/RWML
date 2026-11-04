"""Validate a release ZIP, its checksums, and every detached GPG signature."""

import argparse
import hashlib
import io
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile


GAME_LIB_MAPPINGS = {
    "com/corrodinggames/rts/gameFramework/GameEngine": {
        "fields": {"simulationTick": "I", "disableAutomaticGameThread": "Z"},
        "methods": {"clearAllGameObjects": "()V"},
        "old_fields": {"gameTimeSeconds": "I", "PENDING_bb": "Z"},
        "old_methods": {"renderGame": "()V"},
    },
    "com/corrodinggames/rts/gameFramework/CommandControllerCommand": {
        "fields": {"scheduledTick": "I"},
        "methods": {"applyQueuedActionBookkeeping": "()V"},
        "old_fields": {"scheduledGameSecond": "I"},
        "old_methods": {"j": "()V"},
    },
    "com/corrodinggames/rts/game/units/OrderableUnit": {
        "fields": {},
        "methods": {"setTechLevel": "(I)V"},
        "old_fields": {},
        "old_methods": {"setRadius": "(I)V"},
    },
    "com/corrodinggames/rts/game/Team": {
        "fields": {"explicitAllyTeam": "Z"},
        "methods": {},
        "old_fields": {"PENDING_flag_u": "Z"},
        "old_methods": {},
    },
    "com/corrodinggames/rts/game/units/Waypoint": {
        "fields": {
            "formationFaceEnemy": "Z",
            "addedByAction": "Z",
            "repairReclaimRangePadding": "F",
            "maxWaypointDuration": "F",
        },
        "methods": {},
        "old_fields": {
            "PENDING_flagJ": "Z",
            "PENDING_flagN": "Z",
            "PENDING_posK": "F",
            "PENDING_posL": "F",
        },
        "old_methods": {},
    },
}


def _class_members(data: bytes) -> tuple[str, dict[str, set[str]], dict[str, set[str]]]:
    """Read declared member names and descriptors from one JVM class file."""
    offset = 0

    def unpack(fmt: str) -> tuple:
        nonlocal offset
        try:
            values = struct.unpack_from(fmt, data, offset)
        except struct.error as error:
            raise ValueError("Truncated class file") from error
        offset += struct.calcsize(fmt)
        return values

    def take(length: int) -> bytes:
        nonlocal offset
        end = offset + length
        if length < 0 or end > len(data):
            raise ValueError("Truncated class file")
        value = data[offset:end]
        offset = end
        return value

    def skip_attributes(count: int) -> None:
        for _ in range(count):
            unpack(">H")  # attribute_name_index
            length, = unpack(">I")
            take(length)

    magic, = unpack(">I")
    if magic != 0xCAFEBABE:
        raise ValueError("Invalid class-file magic")
    unpack(">HH")  # minor_version, major_version
    constant_pool_count, = unpack(">H")
    constant_pool: list[object | None] = [None] * constant_pool_count

    def modified_utf8(payload: bytes) -> str:
        # Java encodes NUL as C0 80 and supplementary characters as UTF-16 surrogates.
        return payload.replace(b"\xc0\x80", b"\x00").decode("utf-8", errors="surrogatepass")

    index = 1
    while index < constant_pool_count:
        tag, = unpack(">B")
        if tag == 1:  # CONSTANT_Utf8
            size, = unpack(">H")
            constant_pool[index] = modified_utf8(take(size))
        elif tag in (3, 4):  # CONSTANT_Integer, CONSTANT_Float
            take(4)
        elif tag in (5, 6):  # CONSTANT_Long, CONSTANT_Double occupy two entries
            take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            constant_pool[index] = (tag, *unpack(">H"))
        elif tag in (9, 10, 11, 12, 17, 18):  # refs, NameAndType, Dynamic, InvokeDynamic
            constant_pool[index] = (tag, *unpack(">HH"))
        elif tag == 15:  # CONSTANT_MethodHandle
            constant_pool[index] = (tag, *unpack(">BH"))
        else:
            raise ValueError(f"Unsupported constant-pool tag: {tag}")
        index += 1

    unpack(">H")  # access_flags
    this_class, = unpack(">H")
    unpack(">H")  # super_class
    interface_count, = unpack(">H")
    take(interface_count * 2)

    def utf8(constant_index: int) -> str:
        if not 0 < constant_index < constant_pool_count:
            raise ValueError("Invalid constant-pool index")
        value = constant_pool[constant_index]
        if not isinstance(value, str):
            raise ValueError("Expected a CONSTANT_Utf8 entry")
        return value

    if not 0 < this_class < constant_pool_count:
        raise ValueError("Invalid this_class constant-pool index")
    class_entry = constant_pool[this_class]
    if not isinstance(class_entry, tuple) or class_entry[0] != 7:
        raise ValueError("this_class does not reference CONSTANT_Class")
    internal_name = utf8(class_entry[1])

    def members() -> dict[str, set[str]]:
        result: dict[str, set[str]] = {}
        count, = unpack(">H")
        for _ in range(count):
            unpack(">H")  # access_flags
            name = utf8(unpack(">H")[0])
            descriptor = utf8(unpack(">H")[0])
            result.setdefault(name, set()).add(descriptor)
            attribute_count, = unpack(">H")
            skip_attributes(attribute_count)
        return result

    fields = members()
    methods = members()
    class_attribute_count, = unpack(">H")
    skip_attributes(class_attribute_count)
    if offset != len(data):
        raise ValueError("Trailing data after class-file attributes")
    return internal_name, fields, methods


def _source_declares(source: str, name: str, method: bool) -> bool:
    modifiers = r"(?:(?:public|protected|private|static|final|transient|volatile|" \
        r"synchronized|native|abstract|strictfp|default)\s+)*"
    java_type = r"[\w.$<>\[\],?]+"
    tail = r"\s*\(" if method else r"\s*(?:=|;)"
    pattern = re.compile(
        rf"(?m)^\s*{modifiers}{java_type}\s+{re.escape(name)}{tail}"
    )
    return pattern.search(source) is not None


def _obsolete_members(
    fields: dict[str, set[str]],
    methods: dict[str, set[str]],
    requirements: dict,
) -> list[str]:
    return sorted(
        [
            f"{name}:{descriptor}"
            for name, descriptor in requirements["old_fields"].items()
            if descriptor in fields.get(name, set())
        ]
        + [
            f"{name}{descriptor}"
            for name, descriptor in requirements["old_methods"].items()
            if descriptor in methods.get(name, set())
        ]
    )


def validate_game_lib(binary: ZipFile, sources: ZipFile) -> None:
    """Require the renamed public API in class declarations and its source archive."""
    binary_names = set(binary.namelist())
    source_names = set(sources.namelist())
    for class_name, requirements in GAME_LIB_MAPPINGS.items():
        class_path = class_name + ".class"
        source_path = class_name + ".java"
        if class_path not in binary_names:
            raise ValueError(f"Missing mapped class: {class_name}")
        if source_path not in source_names:
            raise ValueError(f"Missing mapped source: {class_name}")

        internal_name, fields, methods = _class_members(binary.read(class_path))
        if internal_name != class_name:
            raise ValueError(f"Class path does not match declaration: {class_name}")

        old_members = _obsolete_members(fields, methods, requirements)
        if old_members:
            raise ValueError(f"Obsolete mapped member in {class_name}: {', '.join(old_members)}")

        missing = [
            f"{name}:{descriptor}"
            for name, descriptor in requirements["fields"].items()
            if descriptor not in fields.get(name, set())
        ] + [
            f"{name}{descriptor}"
            for name, descriptor in requirements["methods"].items()
            if descriptor not in methods.get(name, set())
        ]
        if missing:
            raise ValueError(f"Missing mapped declaration in {class_name}: {', '.join(missing)}")

        source = sources.read(source_path).decode("utf-8", errors="replace")
        source_members = (
            [(name, False) for name in requirements["fields"]]
            + [(name, True) for name in requirements["methods"]]
        )
        missing_source = [
            name for name, is_method in source_members
            if not _source_declares(source, name, is_method)
        ]
        if missing_source:
            raise ValueError(
                f"Missing mapped source declaration in {class_name}: {', '.join(missing_source)}"
            )


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
            with ZipFile(io.BytesIO(archive.read(prefix + base + "-javadoc.jar"))) as documentation:
                readme = documentation.read("README.md").decode("utf-8")
                if "game-lib:1.15" in readme or "game-lib-1.15-sources.jar" in readme:
                    raise ValueError("Game library documentation still refers to the obsolete mapping release")
            required_classes = (
                "com/corrodinggames/rts/gameFramework/GameEngine",
                "com/corrodinggames/rts/game/RWGameEngine",
                "com/corrodinggames/rts/java/MainLauncher",
            )
            with ZipFile(io.BytesIO(archive.read(prefix + base + ".jar"))) as binary, \
                    ZipFile(io.BytesIO(archive.read(prefix + base + "-sources.jar"))) as sources:
                for name in required_classes:
                    if name + ".class" not in binary.namelist():
                        raise ValueError(f"Missing mapped class: {name}")
                for name in required_classes:
                    if name + ".java" not in sources.namelist():
                        raise ValueError(f"Missing mapped source: {name}")
                if not sources.namelist() or any(not name.endswith(".java") for name in sources.namelist()):
                    raise ValueError("Source archive must contain decompiled Java sources")
                validate_game_lib(binary, sources)
    print(f"Central bundle OK: {coordinates}, POM, sources, Javadoc, checksums, and {len(artifacts)} valid signatures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--gpg", required=True)
    parser.add_argument("--homedir", required=True)
    parser.add_argument("--coordinates", required=True)
    args = parser.parse_args()
    verify(args.bundle, args.gpg, args.homedir, args.coordinates)
