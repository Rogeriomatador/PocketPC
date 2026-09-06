#!/usr/bin/env python3
"""Self-test for the full PocketPC build/install/evidence cross-verifier."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-device-chain.py"
BUILD_LOCK = ROOT / "toolchains" / "android-build-lock.json"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: pathlib.Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def create_build(
    root: pathlib.Path,
    app: dict,
    commit: str,
    cert: str,
) -> pathlib.Path:
    build = root / "build"
    build.mkdir()

    apk_name = f"PocketPC-{app['versionName']}-aaaaaaaaaaaa-debug.apk"
    apk_bytes = b"synthetic-pocketpc-apk"
    apk_path = build / apk_name
    apk_path.write_bytes(apk_bytes)
    apk_sha = sha(apk_bytes)

    (build / f"{apk_name}.sha256").write_text(
        f"{apk_sha}  {apk_name}\n",
        encoding="ascii",
    )
    (build / "apk-signing.txt").write_text(
        f"Signer #1 certificate SHA-256 digest: {cert}\n",
        encoding="utf-8",
    )

    write_json(
        build / "local-build-record.json",
        {
            "schemaVersion": 1,
            "classification": "LOCAL_BUILD_POLICY_CHECKED",
            "generatedAtUtc": "2026-09-06T00:00:00Z",
            "source": {
                "commit": commit,
                "dirty": False,
                "embeddedRevision": commit,
            },
            "app": app,
            "toolchain": {},
            "checks": {
                "pythonPolicyChecks": "PASS",
                "unitTests": "PASS",
                "lint": "PASS",
                "assembleDebug": "PASS",
                "apkStructure": "PASS",
                "unapprovedSubstrateRejected": "PASS",
            },
            "apk": {
                "fileName": apk_name,
                "bytes": len(apk_bytes),
                "sha256": apk_sha,
                "signingCertificateSha256": [cert],
            },
        },
    )
    return build


def create_install(
    root: pathlib.Path,
    build: pathlib.Path,
    app: dict,
    commit: str,
    apk_sha: str,
    device_model: str,
) -> pathlib.Path:
    install = root / "install"
    install.mkdir()

    record = {
        "schemaVersion": 1,
        "classification": "DEVICE_INSTALL_APK_HASH_VERIFIED",
        "generatedAtUtc": "2026-09-06T00:00:00Z",
        "source": {
            "commit": commit,
            "embeddedRevision": commit,
            "dirty": False,
        },
        "build": {
            "buildRecordSha256": sha(
                (build / "local-build-record.json").read_bytes()
            ),
            "apkFileName": json.loads(
                (build / "local-build-record.json").read_text()
            )["apk"]["fileName"],
            "apkSha256": apk_sha,
            "apkBytes": 22,
            "packageName": app["packageName"],
            "versionName": app["versionName"],
            "versionCode": app["versionCode"],
        },
        "device": {
            "serialSha256": "d" * 64,
            "manufacturer": "Synthetic",
            "model": device_model,
            "androidApi": 37,
            "abis": ["arm64-v8a", "armeabi-v7a"],
            "emulator": False,
            "buildFingerprint": "synthetic/device/fingerprint",
        },
        "install": {
            "adbInstall": "PASS",
            "packagePathPresent": True,
            "installedVersionName": app["versionName"],
            "installedVersionCode": app["versionCode"],
            "installedApkHashCheck": "PASS",
            "installedApkSha256": apk_sha,
            "pullError": None,
        },
        "launch": {
            "activity": f"{app['packageName']}/.MainActivity",
            "status": "PASS",
            "pid": "1234",
            "outputFile": "activity-launch.txt",
        },
    }

    record_path = install / "device-install-record.json"
    write_json(record_path, record)
    (install / "activity-launch.txt").write_text(
        "Starting: Intent\nStatus: ok\nActivity: dev.pocketpc.core/.MainActivity\n",
        encoding="utf-8",
    )
    (install / "device-install-record.json.sha256").write_text(
        f"{sha(record_path.read_bytes())}  device-install-record.json\n",
        encoding="ascii",
    )
    return install


def create_bundle(
    path: pathlib.Path,
    app: dict,
    commit: str,
    cert: str,
    device_model: str,
) -> None:
    identity = {
        "schemaVersion": 1,
        "packageName": app["packageName"],
        "versionName": app["versionName"],
        "versionCode": app["versionCode"],
        "sourceRevision": commit,
        "sourceRevisionPinned": True,
        "debug": True,
        "signingCertificateSha256": [cert],
        "installerPackage": "com.android.shell",
    }

    evidence = {
        "schemaVersion": 2,
        "pocketPcVersion": app["versionName"],
        "generatedAtUtc": "2026-09-06T00:00:01Z",
        "buildIdentity": identity,
        "device": {
            "manufacturer": "Synthetic",
            "model": device_model,
            "androidApi": 37,
            "abis": ["arm64-v8a", "armeabi-v7a"],
        },
        "filesystem": {
            "allCriticalPassed": True,
        },
        "nativeHost": {
            "loaded": True,
            "probe": "synthetic-native-host-ok",
            "graphicsProbe": "synthetic-vulkan-probe",
            "nativeLibraryDir": "/data/app/synthetic/lib/arm64",
        },
        "substrate": {
            "prootReady": False,
        },
    }
    evidence_bytes = json.dumps(evidence, sort_keys=True).encode("utf-8")
    evidence_sha = sha(evidence_bytes)

    payload = {
        "evidence/device-evidence.json": evidence_bytes,
        "evidence/device-evidence.sha256": (
            f"{evidence_sha}  device-evidence-latest.json\n"
        ).encode("ascii"),
        "identity/build-identity.json": json.dumps(
            identity,
            sort_keys=True,
        ).encode("utf-8"),
        "bundle-info.json": json.dumps(
            {
                "schemaVersion": 1,
                "evidenceSha256": evidence_sha,
                "sourceRevision": commit,
                "sourceRevisionPinned": True,
                "versionName": app["versionName"],
                "versionCode": app["versionCode"],
                "approvalState": "SUBSTRATE_NOT_APPROVED",
                "prootReady": False,
            },
            sort_keys=True,
        ).encode("utf-8"),
        "policy/proot-substrate-approval.json": json.dumps(
            {
                "schemaVersion": 1,
                "status": "NOT_APPROVED",
                "approved": False,
                "sourceLockSha256": "",
                "artifactContractSha256": "",
                "artifactLockSha256": "",
                "artifacts": [],
                "review": {
                    "sourceAudit": False,
                    "elfAudit": False,
                    "licenseAudit": False,
                    "deviceAudit": False,
                },
            },
            sort_keys=True,
        ).encode("utf-8"),
        "policy/proot/LOCK.json": b'{"schemaVersion":1}',
        "policy/proot/ARTIFACT_CONTRACT.json": b'{"schemaVersion":1}',
    }

    manifest = {
        "schemaVersion": 1,
        "generatedAtUtc": "2026-09-06T00:00:02Z",
        "entries": [
            {
                "path": name,
                "bytes": len(data),
                "sha256": sha(data),
            }
            for name, data in sorted(payload.items())
        ],
    }

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in payload.items():
            archive.writestr(name, data)
        archive.writestr(
            "bundle-manifest.json",
            json.dumps(manifest, sort_keys=True).encode("utf-8"),
        )


def run(
    build: pathlib.Path,
    install: pathlib.Path,
    bundle: pathlib.Path,
    commit: str,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            "--build-dir",
            str(build),
            "--install-dir",
            str(install),
            "--bundle",
            str(bundle),
            "--expected-commit",
            commit,
            "--require-installed-apk-hash",
            "--require-filesystem-pass",
            "--require-native-host",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    app = json.loads(BUILD_LOCK.read_text(encoding="utf-8"))["app"]
    commit = "a" * 40
    cert = "b" * 64

    with tempfile.TemporaryDirectory(prefix="pocketpc-chain-selftest-") as temp:
        root = pathlib.Path(temp)
        build = create_build(root, app, commit, cert)
        build_record = json.loads(
            (build / "local-build-record.json").read_text(encoding="utf-8")
        )
        apk_sha = build_record["apk"]["sha256"]

        install = create_install(
            root,
            build,
            app,
            commit,
            apk_sha,
            "Device-A",
        )
        bundle = root / "good.zip"
        create_bundle(bundle, app, commit, cert, "Device-A")

        good = run(build, install, bundle, commit)
        if good.returncode != 0 or "POCKETPC_DEVICE_CHAIN_OK" not in good.stdout:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid full device chain was rejected")

        bad_bundle = root / "wrong-device.zip"
        create_bundle(bad_bundle, app, commit, cert, "Device-B")
        bad = run(build, install, bad_bundle, commit)
        if bad.returncode == 0:
            raise SystemExit("cross-device evidence unexpectedly passed")
        if "device identity differs for model" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("cross-device mismatch was not reported")

    print("POCKETPC_DEVICE_CHAIN_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
