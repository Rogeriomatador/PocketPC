#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "toolchains" / "android-build-lock.json"
FEED = ROOT / "updates" / "stable.json"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
UPDATER = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "update" / "PocketPcUpdater.kt"
CENTER = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "UpdateCenterApp.kt"
SYSTEM = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "SystemApp.kt"
SHELL = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "PocketPcApp.kt"
AUTO_TEST = ROOT / "app" / "src" / "test" / "java" / "dev" / "pocketpc" / "core" / "update" / "PocketPcUpdaterPolicyTest.kt"
PREPARE = ROOT / "scripts" / "prepare-update-feed.py"

SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
REVISION_RE = re.compile(r"^[0-9a-fA-F]{40}$")


def load_text(path: pathlib.Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(path)
    return path.read_text(encoding="utf-8")


def main() -> int:
    failures: list[str] = []

    try:
        lock = json.loads(load_text(LOCK))
        feed = json.loads(load_text(FEED))
    except Exception as error:
        print(f"UPDATE_FEED_POLICY_FAILED\n- invalid metadata: {error}", file=sys.stderr)
        return 1

    app = lock.get("app") or {}
    expected_package = str(app.get("packageName") or "")
    expected_version = str(app.get("versionName") or "")
    expected_code = int(app.get("versionCode") or -1)

    if feed.get("schemaVersion") != 1:
        failures.append("stable feed schemaVersion must be 1")
    if feed.get("channel") != "stable":
        failures.append("stable feed channel must be 'stable'")
    if feed.get("packageName") != expected_package:
        failures.append("stable feed packageName must match build lock")
    if str(feed.get("versionName") or "") != expected_version:
        failures.append("stable feed versionName must match build lock")
    if int(feed.get("versionCode") or -1) != expected_code:
        failures.append("stable feed versionCode must match build lock")

    min_api = int(feed.get("minApi") or -1)
    if min_api < 26:
        failures.append("stable feed minApi must be >= PocketPC minSdk 26")

    published = bool(feed.get("published"))
    apk_url = str(feed.get("apkUrl") or "")
    apk_sha = str(feed.get("apkSha256") or "")
    source_revision = str(feed.get("sourceRevision") or "")

    if published:
        if not apk_url.startswith("https://"):
            failures.append("published update must use an HTTPS apkUrl")
        if not SHA256_RE.fullmatch(apk_sha):
            failures.append("published update must contain a 64-hex SHA-256")
        if not REVISION_RE.fullmatch(source_revision):
            failures.append("published update must pin a 40-hex sourceRevision")
    else:
        if apk_url or apk_sha:
            failures.append(
                "unpublished bootstrap feed must not expose APK URL/hash"
            )

    required = {
        MANIFEST: (
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "dev.pocketpc.SOURCE_REVISION",
            "dev.pocketpc.SOURCE_REVISION_PINNED",
            "pocketPcSourceRevision",
            "pocketPcSourceRevisionPinned",
        ),
        UPDATER: (
            "raw.githubusercontent.com/Rogeriomatador/PocketPC/main/updates/stable.json",
            'manifest.apkUrl.startsWith("https://")',
            "SHA-256 do APK não confere.",
            "archiveInfo.packageName",
            "signaturesCompatible",
            "canRequestPackageInstalls",
            "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
            "getUriForDownloadedFile",
            "GET_SIGNING_CERTIFICATES",
            "autoCheckEnabled",
            "autoDownloadUnmeteredEnabled",
            "shouldRunAutomaticCheck",
            "NET_CAPABILITY_NOT_METERED",
            "KEY_VERIFIED_DOWNLOAD_ID",
            "AUTO_CHECK_INTERVAL_MS",
            "shouldRunUpdateCheck",
            "META_SOURCE_REVISION",
            "META_SOURCE_REVISION_PINNED",
            "GET_META_DATA",
            "Source revision do APK não corresponde",
            "APK foi compilado sem revisão Git fixada",
        ),
        CENTER: (
            "Verificar agora",
            "Baixar atualização",
            "Verificar APK",
            "Instalar",
            "Fail-closed",
            "PocketPcUpdateAutoCheck",
            "Verificar automaticamente",
            "Baixar automaticamente",
            "isPendingDownloadVerified",
            "verifyPendingDownload",
        ),
        AUTO_TEST: (
            "firstAutomaticCheckRunsImmediately",
            "disabledAutomaticCheckNeverRuns",
            "automaticCheckIsThrottledInsideInterval",
            "automaticCheckRunsAtIntervalBoundary",
            "clockRollbackDoesNotBlockUpdatesForever",
        ),
        SYSTEM: (
            'UPDATES("Atualizações")',
            "PcInfoTab.UPDATES",
            "UpdateCenterApp()",
        ),
        SHELL: (
            "PocketPcUpdateAutoCheck()",
        ),
        PREPARE: (
            "UPDATE_FEED_PREPARED",
            "UNPUBLISHED_FAIL_CLOSED",
            "hashlib.sha256",
            "apk URL must be an absolute HTTPS URL",
            "source revision must be exactly 40 hexadecimal characters",
            "--publish",
            '"published": bool(args.publish)',
        ),
    }

    for path, sentinels in required.items():
        try:
            text = load_text(path)
        except Exception as error:
            failures.append(f"missing updater source {path.relative_to(ROOT)}: {error}")
            continue

        for sentinel in sentinels:
            if sentinel not in text:
                failures.append(
                    f"{path.relative_to(ROOT)} missing sentinel: {sentinel}"
                )

    if failures:
        print("UPDATE_FEED_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("UPDATE_FEED_POLICY_OK")
    print(f"channel={feed['channel']}")
    print(f"published={str(published).lower()}")
    print(f"version={expected_version}")
    print(f"version_code={expected_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
