#!/usr/bin/env python3
"""Stage independent installer payloads, hash-pinned Termux, notices and app source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request

TERMUX_URL = 'https://github.com/termux/termux-app/releases/download/v0.119.0-beta.3/termux-app_v0.119.0-beta.3%2Bapt-android-5-github-debug_universal.apk'
TERMUX_HASH = 'bad6b661128baeb57e4c05dca5cc2a1f18feaee8b594abdbe99ca4e54e7f05d6'
SOURCE_URL = 'https://codeload.github.com/termux/termux-app/tar.gz/refs/tags/v0.119.0-beta.3'
SOURCE_HASH = '484a114a19187084156af0f9a00ef7386112e4c5e9f62bf9d20c13a91c2c20d6'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(65536), b''): h.update(chunk)
    return h.hexdigest()


def fetch(url, path):
    temporary = path.with_suffix(path.suffix + '.partial')
    try:
        with urllib.request.urlopen(url, timeout=60) as src, temporary.open('wb') as dst:
            shutil.copyfileobj(src, dst)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--termux', type=Path, help='Existing unmodified pinned Android 5 universal APK; otherwise downloads official asset')
    parser.add_argument('--preview', type=Path, help='Optional locally built motor-free Workout Lab APK')
    parser.add_argument('--android-sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    assets = root / 'local/assets'; payloads = assets / 'payloads'; payloads.mkdir(parents=True, exist_ok=True)
    termux = payloads / 'termux.apk'
    if args.termux:
        if digest(args.termux) != TERMUX_HASH: raise SystemExit('Termux SHA-256 differs from the tested artifact; refusing')
        shutil.copy2(args.termux, termux)
    elif not termux.exists() or digest(termux) != TERMUX_HASH:
        fetch(TERMUX_URL, termux)
    if digest(termux) != TERMUX_HASH: raise SystemExit('Official download hash mismatch; do not build or install')
    toolchains = sorted((args.android_sdk / 'build-tools').glob('*/aapt'), reverse=True)
    if not toolchains: raise SystemExit('Android SDK aapt required to validate APK identities')
    aapt = str(toolchains[0])
    def identity(path, package, version=None, code=None):
        out = subprocess.check_output([aapt, 'dump', 'badging', str(path)], text=True)
        match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", out)
        if not match or match[1] != package or (version and match[3] != version) or (code and match[2] != code):
            raise SystemExit('Unexpected APK identity: ' + str(path))
        return {'package':match[1], 'version':match[3], 'versionCode':match[2], 'sha256':digest(path)}
    metadata = {'termux.apk':identity(termux,'com.termux','0.119.0-beta.3','1022')}
    if args.preview:
        if not args.preview.is_file(): raise SystemExit('Build Workout Lab before staging its payload')
        metadata['preview.apk'] = identity(args.preview,'dev.stride.lab','0.2.1-console-preview','3')
        shutil.copy2(args.preview,payloads / 'preview.apk')
    else:
        (payloads / 'preview.apk').unlink(missing_ok=True)
    # AAPT transparently expands *.gz assets. Keep exact upstream gzip bytes in
    # a .bin asset; the provider exports the original .tar.gz filename/MIME type.
    source = payloads / 'termux-source.bin'
    old = payloads / 'termux-source.tar.gz'
    if old.exists() and not source.exists(): shutil.copy2(old,source)
    old.unlink(missing_ok=True)
    if not source.exists(): fetch(SOURCE_URL, source)
    if digest(source) != SOURCE_HASH: raise SystemExit('Termux source archive hash mismatch; refusing')
    metadata['termux-source.tar.gz'] = {'sha256':digest(source),'source':SOURCE_URL,'asset':'payloads/termux-source.bin'}
    for name,url in [('TERMUX-LICENSE.md','https://raw.githubusercontent.com/termux/termux-app/v0.119.0-beta.3/LICENSE.md'),
                     ('GPL-3.0.txt','https://www.gnu.org/licenses/gpl-3.0.txt')]:
        dest=assets/name
        if not dest.exists(): fetch(url,dest)
    notice='''Termux is an independent unmodified application, optionally installed with Android approval.
Termux app v0.119.0-beta.3, version code 1022, apt-android-5 universal.
Copyright and license notices remain in the nested APK and source archive.
Termux app is GPLv3-only, with exceptions described in TERMUX-LICENSE.md and its source tree.
Full GPL text is bundled as GPL-3.0.txt.

Pinned release: https://github.com/termux/termux-app/releases/tag/v0.119.0-beta.3
App source: https://github.com/termux/termux-app/tree/v0.119.0-beta.3
App source archive is bundled and can be shared with the Source archive button.
Termux's bootstrap contains separate upstream packages; see termux-packages for their recipes/sources:
https://github.com/termux/termux-packages
Retain all applicable licenses and provide corresponding sources when redistributing binaries.
This local bundle is not a completed public-release/source-compliance audit.

STRIDE Console Setup is a separate app. It does not link against Termux, alter its APK,
install it silently, reuse its signing identity, or execute commands through its service.
Termux does not install su or guarantee Android root access.
'''
    (assets/'TERMUX-NOTICES.txt').write_text(notice)
    (assets/'payloads.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print(json.dumps(metadata,indent=2))
    print('Staged locally; no payload binaries are added to git. Build :app:assembleBundleDebug.')


if __name__=='__main__': main()
