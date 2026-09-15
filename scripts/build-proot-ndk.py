#!/usr/bin/env python3
"""Build the pinned ARM64 PRoot substrate outside the APK, using the Android NDK.

No artifact is promoted or approved by this build. --work must not exist.
Source caches contain archives and original Termux recipes, never built objects.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / 'third_party/proot'


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def checked_fetch(url: str, expected: str, path: Path) -> None:
    if path.exists() and digest(path) == expected:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + '.part')
    with urllib.request.urlopen(url, timeout=90) as response, partial.open('wb') as out:
        shutil.copyfileobj(response, out)
    if digest(partial) != expected:
        partial.unlink()
        raise ValueError(f'SHA-256 mismatch: {url}')
    partial.replace(path)


def unpack(archive: Path, destination: Path) -> Path:
    destination.mkdir(parents=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                if not (destination / entry.filename).resolve().is_relative_to(destination.resolve()):
                    raise ValueError('Archive path escapes destination')
            z.extractall(destination)
    else:
        with tarfile.open(archive) as t:
            t.extractall(destination, filter='data')
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise ValueError('Expected exactly one source root')
    return roots[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--source-cache', required=True, type=Path)
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 16:
        parser.error('--jobs must be between 1 and 16')
    recipe = json.loads((POLICY / 'NDK_BUILD.json').read_text())
    lock = json.loads((POLICY / 'LOCK.json').read_text())
    ndk = args.ndk.resolve()
    if f'Pkg.Revision = {recipe["ndkVersion"]}' not in (ndk / 'source.properties').read_text():
        raise ValueError('NDK version differs from NDK_BUILD.json')
    work = args.work.resolve()
    if work.is_relative_to(ROOT):
        raise ValueError('--work must be outside the repository')
    work.mkdir(parents=True, exist_ok=False)
    cache = args.source_cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    toolbin = ndk / f'toolchains/llvm/prebuilt/{recipe["hostTag"]}/bin'
    cc = toolbin / f'aarch64-linux-android{recipe["androidApi"]}-clang'
    env = os.environ.copy()
    env.update(CC=str(cc), CXX=str(cc) + '++', AR=str(toolbin / 'llvm-ar'),
               RANLIB=str(toolbin / 'llvm-ranlib'), STRIP=str(toolbin / 'llvm-strip'),
               PYTHON=sys.executable, CFLAGS='-O2 -fPIC',
               LDFLAGS='-Wl,-z,max-page-size=16384',
               GIT_CEILING_DIRECTORIES=str(work))
    env['PATH'] = str(toolbin) + os.pathsep + env['PATH']
    records = []

    def run(name: str, command: list[str], cwd: Path) -> None:
        with (work / (name + '.log')).open('w') as log:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        records.append({'stage': name, 'argv': command, 'exitCode': result.returncode})
        (work / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
        print(f'{name}: exit {result.returncode}', flush=True)
        if result.returncode:
            raise RuntimeError(f'{name} failed; see {work / (name + ".log")}')

    run('compiler-version', [str(cc), '--version'], work)
    sources = {}
    for component in lock['components']:
        name = component['id']
        archive = cache / (name + '.source')
        checked_fetch(component['sourceUrl'], component['sourceSha256'], archive)
        authority = lock['recipeAuthority']
        url = f'https://raw.githubusercontent.com/{authority["repository"]}/{authority["commit"]}/{component["recipePath"]}'
        original_recipe = cache / 'recipes' / name / 'build.sh'
        checked_fetch(url, recipe['recipes'][name], original_recipe)
        if any(x not in original_recipe.read_text() for x in component['recipeAssertions']):
            raise ValueError(f'Recipe assertion failed: {name}')
        sources[name] = unpack(archive, work / 'sources' / name)

    for patch_name, expected in recipe['patches'].items():
        patch = ROOT / patch_name
        if digest(patch) != expected:
            raise ValueError(f'Patch digest mismatch: {patch_name}')
        target = sources['libandroid-shmem'] if patch.name.startswith('shmem-') else sources['proot']
        run(patch.stem, ['patch', '--batch', '--forward', '-p1', '-i', str(patch)], target)

    prefix = work / 'prefix'
    (prefix / 'lib').mkdir(parents=True)
    (prefix / 'include/sys').mkdir(parents=True)
    talloc = sources['libtalloc']
    answers = POLICY / 'cross-answers-aarch64.txt'
    if digest(answers) != recipe['crossAnswersSha256']:
        raise ValueError('Cross answers digest mismatch')
    shutil.copy2(answers, talloc / 'cross-answers.txt')
    run('talloc-configure', ['./configure', '--prefix=' + str(prefix), '--disable-rpath',
        '--disable-python', '--cross-compile', '--cross-answers=cross-answers.txt'], talloc)
    run('talloc-build', ['make', '-j' + str(args.jobs)], talloc)
    # This object belongs to the upstream shared-library target, not its private test helper.
    talloc_object = talloc / 'bin/default/talloc.c.6.o'
    run('talloc-android-soname', [str(cc), '-shared', '-Wl,--no-undefined',
        '-Wl,-soname,libtalloc.so', '-Wl,-z,max-page-size=16384', str(talloc_object),
        '-o', str(prefix / 'lib/libtalloc.so')], talloc)
    shutil.copy2(talloc / 'talloc.h', prefix / 'include/talloc.h')
    shmem = sources['libandroid-shmem']
    run('shmem-build', ['make', 'CC=' + str(cc), 'AR=' + str(toolbin / 'llvm-ar'),
        'CFLAGS=-O2 -fPIC -std=c11 -Wall -Wextra',
        'LDFLAGS=-Wl,--version-script=exports.txt -Wl,-soname,libandroid-shmem.so -Wl,-z,max-page-size=16384',
        'libandroid-shmem.so'], shmem)
    shutil.copy2(shmem / 'libandroid-shmem.so', prefix / 'lib/libandroid-shmem.so')
    shutil.copy2(shmem / 'shm.h', prefix / 'include/sys/shm.h')
    src = sources['proot'] / 'src'
    run('proot-build', ['make', '-j' + str(args.jobs), 'CC=' + str(cc),
        'STRIP=' + str(toolbin / 'llvm-strip'), 'OBJCOPY=' + str(toolbin / 'llvm-objcopy'),
        'OBJDUMP=' + str(toolbin / 'llvm-objdump'), 'PROOT_WITH_LIBANDROID_SHMEM=true',
        'HAS_LOADER_32BIT=', 'PROOT_UNBUNDLE_LOADER=/pocketpc-loader-must-be-set-by-environment',
        'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\"5.1.107.92\\" -I. -I' + str(src) + ' -I' + str(prefix / 'include'),
        'LDFLAGS=-L' + str(prefix / 'lib') + ' -ltalloc -landroid-shmem -Wl,-z,noexecstack -Wl,-z,max-page-size=16384'], src)
    out = work / 'quarantine/arm64-v8a'
    out.mkdir(parents=True)
    for source, name in [(src / 'proot', 'proot'), (src / 'loader/loader', 'loader'),
                         (prefix / 'lib/libtalloc.so', 'libtalloc.so'),
                         (prefix / 'lib/libandroid-shmem.so', 'libandroid-shmem.so')]:
        shutil.copy2(source, out / name)
    run('elf-audit', [sys.executable, str(ROOT / 'scripts/audit-proot-artifacts.py'),
        '--artifact-dir', str(out), '--report', str(work / 'proot-elf-audit.json'),
        '--readelf', str(toolbin / 'llvm-readelf')], ROOT)
    manifest = {'status': 'COMPILED_NOT_DEVICE_TESTED_NOT_APPROVED',
        'sourceLockSha256': digest(POLICY / 'LOCK.json'),
        'buildRecipeSha256': digest(POLICY / 'NDK_BUILD.json'),
        'buildScriptSha256': digest(Path(__file__)),
        'artifacts': {p.name: digest(p) for p in sorted(out.iterdir())},
        'notExecuted': ['Android execution', 'PRoot guest execution', 'Box64/Wine', 'Roblox'],
        'crossConfigureAnswersAreRuntimeTests': False}
    (work / 'build-result.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Compiled and structurally audited; no binary was added to the APK.', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
