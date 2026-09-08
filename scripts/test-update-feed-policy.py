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
INSTALL_RECEIVER = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "update" / "PocketPcInstallReceiver.kt"
WORKER = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "update" / "PocketPcUpdateWorker.kt"
CENTER = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "UpdateCenterApp.kt"
SYSTEM = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "SystemApp.kt"
SHELL = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "ui" / "PocketPcApp.kt"
MAIN_ACTIVITY = ROOT / "app" / "src" / "main" / "java" / "dev" / "pocketpc" / "core" / "MainActivity.kt"
AUTO_TEST = ROOT / "app" / "src" / "test" / "java" / "dev" / "pocketpc" / "core" / "update" / "PocketPcUpdaterPolicyTest.kt"
PREPARE = ROOT / "scripts" / "prepare-update-feed.py"
PUBLISH_WORKFLOW = ROOT / ".github" / "workflows" / "publish-update.yml"
BOOTSTRAP_SIGNER = ROOT / "updates" / "bootstrap-signer.json"
VERIFY_BOOTSTRAP_SIGNER = ROOT / "scripts" / "verify-bootstrap-signer.py"
TEST_BOOTSTRAP_SIGNER = ROOT / "scripts" / "test-bootstrap-signer-verifier.py"
BOOTSTRAP_WINDOWS = ROOT / "scripts" / "bootstrap-update-signing-windows.ps1"
BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"

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

    feed_version = str(feed.get("versionName") or "")
    feed_code = int(feed.get("versionCode") or -1)
    published = bool(feed.get("published"))

    if feed_code > expected_code:
        failures.append(
            "stable feed versionCode must not be ahead of the source lock"
        )
    elif feed_code == expected_code:
        if feed_version != expected_version:
            failures.append(
                "stable feed versionName must match build lock at equal versionCode"
            )
    elif not published:
        failures.append(
            "an unpublished bootstrap feed must match the current source version"
        )

    min_api = int(feed.get("minApi") or -1)
    if min_api < 26:
        failures.append("stable feed minApi must be >= PocketPC minSdk 26")

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

    try:
        bootstrap_signer = json.loads(load_text(BOOTSTRAP_SIGNER))
    except Exception as error:
        failures.append(f"invalid bootstrap signer metadata: {error}")
        bootstrap_signer = {}

    if bootstrap_signer.get("schemaVersion") != 1:
        failures.append("bootstrap signer schemaVersion must be 1")
    if bootstrap_signer.get("packageName") != expected_package:
        failures.append("bootstrap signer packageName must match build lock")

    allowed_signers = bootstrap_signer.get(
        "allowedSigningCertificateSha256"
    )
    if not isinstance(allowed_signers, list) or not allowed_signers:
        failures.append("bootstrap signer allow-list must be non-empty")
    else:
        for signer in allowed_signers:
            if not SHA256_RE.fullmatch(str(signer)):
                failures.append(
                    "bootstrap signer allow-list contains invalid SHA-256"
                )

    required = {
        MANIFEST: (
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
            ".update.PocketPcInstallReceiver",
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
            "PackageInstaller.SessionParams",
            "USER_ACTION_NOT_REQUIRED",
            "SESSION_ALREADY_PENDING",
            "KEY_INSTALL_ATTEMPT_DOWNLOAD_ID",
            "autoInstallVerifiedEnabled",
            "setAutoInstallVerifiedEnabled",
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
            "Instalar automaticamente",
            "autoInstallVerifiedEnabled",
            "setAutoInstallVerifiedEnabled",
            "SESSION_COMMITTED",
            "SESSION_ALREADY_PENDING",
            "attemptAutomaticInstall",
            "Manifest.permission.POST_NOTIFICATIONS",
            "Aviso de confirmação",
            "notificationPermissionLauncher",
        ),
        INSTALL_RECEIVER: (
            "PackageInstaller.STATUS_PENDING_USER_ACTION",
            "Intent.EXTRA_INTENT",
            "KEY_INSTALL_ATTEMPT_DOWNLOAD_ID",
            "EXTRA_UPDATE_DOWNLOAD_ID",
            "PocketPcInstallStatusStore",
            "NotificationChannel",
            "PendingIntent.getActivity",
            "UPDATE_NOTIFICATION_CHANNEL",
            "UPDATE_NOTIFICATION_ID",
            "manager.notify",
        ),
        WORKER: (
            "CoroutineWorker",
            "PeriodicWorkRequestBuilder",
            "ExistingPeriodicWorkPolicy.UPDATE",
            "NetworkType.CONNECTED",
            "6,",
            "TimeUnit.HOURS",
            "PocketPcUpdateScheduler",
            "shouldAutoInstallUpdate",
            "verifyPendingDownload",
            "beginDownload",
        ),
        AUTO_TEST: (
            "firstAutomaticCheckRunsImmediately",
            "disabledAutomaticCheckNeverRuns",
            "automaticCheckIsThrottledInsideInterval",
            "automaticCheckRunsAtIntervalBoundary",
            "clockRollbackDoesNotBlockUpdatesForever",
            "autoInstallRequiresAllSafetyConditions",
            "autoInstallRejectsUnverifiedApk",
            "autoInstallRejectsMissingInstallerPermission",
            "autoInstallRejectsDuplicateAttempt",
        ),
        SYSTEM: (
            'UPDATES("Atualizações")',
            "PcInfoTab.UPDATES",
            "UpdateCenterApp()",
        ),
        SHELL: (
            "PocketPcUpdateAutoCheck(",
        ),
        MAIN_ACTIVITY: (
            "PocketPcUpdateScheduler.schedule",
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
        BUILD_GRADLE: (
            'androidx.work:work-runtime:2.11.2',
            "POCKETPC_SIGNING_STORE_FILE",
            "POCKETPC_SIGNING_STORE_PASSWORD",
            "POCKETPC_SIGNING_KEY_ALIAS",
            "POCKETPC_SIGNING_KEY_PASSWORD",
            "pocketPcReleaseSigningConfigured",
            'create("pocketPcRelease")',
        ),
        BOOTSTRAP_SIGNER: (
            "PHYSICAL_BOOTSTRAP_SIGNER_PINNED",
            "allowedSigningCertificateSha256",
            "bootstrapVersionName",
            "bootstrapSourceRevision",
            "evidenceBundleSha256",
        ),
        VERIFY_BOOTSTRAP_SIGNER: (
            "BOOTSTRAP_SIGNER_VERIFY_OK",
            "BOOTSTRAP_SIGNER_VERIFY_FAILED",
            "candidate APK signer does not match",
            "--signer-sha256",
        ),
        TEST_BOOTSTRAP_SIGNER: (
            "BOOTSTRAP_SIGNER_SELFTEST_OK",
            "negative_signer_rejected=true",
            "malformed_signer_rejected=true",
            "run_verifier",
        ),
        BOOTSTRAP_WINDOWS: (
            "POCKETPC_UPDATE_BOOTSTRAP_OK",
            "POCKETPC_UPDATE_BOOTSTRAP_FAILED",
            "updates\\bootstrap-signer.json",
            "Signer fisico: PASS",
            "POCKETPC_SIGNING_KEYSTORE_BASE64",
            "POCKETPC_SIGNING_STORE_PASSWORD",
            "POCKETPC_SIGNING_KEY_ALIAS",
            "POCKETPC_SIGNING_KEY_PASSWORD",
            "gh",
            "publish-update.yml",
            "Nenhum Secret foi enviado",
        ),
        PUBLISH_WORKFLOW: (
            "PUBLISH_UPDATE_BLOCKED_SIGNING_NOT_CONFIGURED",
            "PUBLISH_UPDATE_READY_NEWER_VERSION",
            "PUBLISH_UPDATE_SKIPPED_CURRENT_VERSION_ALREADY_PUBLISHED",
            "PUBLISH_UPDATE_BLOCKED_FEED_AHEAD_OF_SOURCE",
            "POCKETPC_SIGNING_KEYSTORE_BASE64",
            "POCKETPC_SIGNING_STORE_PASSWORD",
            "POCKETPC_SIGNING_KEY_ALIAS",
            "POCKETPC_SIGNING_KEY_PASSWORD",
            ":app:assembleRelease",
            "apksigner",
            "verify-bootstrap-signer.py",
            "PUBLISH_UPDATE_FAILED_SIGNER_DIGEST_MISSING",
            "gh release create",
            "scripts/prepare-update-feed.py",
            "--publish",
            "git push origin HEAD:main",
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
