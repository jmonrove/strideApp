#!/usr/bin/env python3
"""Verify the assembled APK, including exact nested payload bytes (not just staging)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
from prepare_bundle import TERMUX_HASH, SOURCE_HASH


def verify(apk, sdk):
    tools=sorted((sdk/'build-tools').glob('*/apksigner'),reverse=True)
    if not tools: raise RuntimeError('SDK apksigner required')
    signing=subprocess.run([str(tools[0]),'verify','--min-sdk-version','22',str(apk)],capture_output=True,text=True)
    if signing.returncode: raise RuntimeError(signing.stderr)
    aapt=tools[0].with_name('aapt')
    manifest=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
    assert "package: name='dev.stride.setup'" in manifest
    assert "sdkVersion:'22'" in manifest
    with zipfile.ZipFile(apk) as z:
        expected=Path(__file__).resolve().parents[2]/'tools/android5/enable-wifi-adb.sh'
        assert z.read('assets/enable-wifi-adb.sh')==expected.read_bytes()
        names=z.namelist()
        payloads=json.loads(z.read('assets/payloads.json')) if 'assets/payloads.json' in names else {}
        assert not (set(payloads)-{'termux.apk','preview.apk','termux-source.tar.gz'})
        for name,info in payloads.items():
            asset=info.get('asset','payloads/'+name)
            h=hashlib.sha256()
            with z.open('assets/'+asset) as f:
                for chunk in iter(lambda:f.read(65536),b''): h.update(chunk)
            assert h.hexdigest()==info['sha256'],name
            if name=='termux.apk': assert info['sha256']==TERMUX_HASH
            if name=='termux-source.tar.gz': assert info['sha256']==SOURCE_HASH
        if payloads:
            assert 'termux.apk' in payloads and 'termux-source.tar.gz' in payloads
            for n in ('TERMUX-NOTICES.txt','TERMUX-LICENSE.md','GPL-3.0.txt'): assert 'assets/'+n in names
        else:
            assert not any(n.startswith('assets/payloads/') for n in names)
    return {'file':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
            'api22_signature_verified':True,'verified_payloads':sorted(payloads)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path,nargs='+')
    parser.add_argument('--android-sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk'))))
    args=parser.parse_args()
    print(json.dumps([verify(p,args.android_sdk) for p in args.apk],indent=2))


if __name__=='__main__': main()
