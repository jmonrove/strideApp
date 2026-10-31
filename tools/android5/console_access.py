#!/usr/bin/env python3
"""Explicit-device access setup for the tested Android 5.1 NordicTrack console."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

MODEL = 'MalataSamsungRoyalWolfSinglePartition'
BUILD = 'MGRSP_20210806'
ADMIN = {'version': '2.2.20', 'code': 1602}
RECEIVER = 'com.ifit.eru/com.ifit.eru.receivers.PrivilegedModeReceiver'
MARKER = '/sdcard/.wolfDev'
PROPS = ('ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk',
         'ro.build.version.incremental', 'ro.build.display.id', 'ro.product.cpu.abi',
         'ro.adb.secure', 'ro.secure', 'ro.debuggable', 'sys.adb.debug',
         'service.adb.tcp.port', 'persist.adb.tcp.port', 'init.svc.adbd', 'sys.boot_completed')


class Access:
    def __init__(self, adb, serial):
        if not serial or serial.startswith('-') or any(c.isspace() for c in serial):
            raise ValueError('Provide one explicit ADB serial or IP:port')
        self.adb, self.serial = adb, serial

    def run(self, *args):
        p = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True,
                           text=True, timeout=15)
        if p.returncode:
            raise RuntimeError(p.stderr.strip() or p.stdout.strip() or 'ADB command failed')
        return p.stdout.replace('\r', '').strip()

    def shell(self, *args):
        # One POSIX-quoted remote command; subprocess never uses a local shell.
        return self.run('shell', ' '.join(shlex.quote(str(a)) for a in args))

    def exists(self, path):
        out = self.run('shell', f'if [ -L {shlex.quote(path)} ]; then echo symlink; '
                       f'elif [ -d {shlex.quote(path)} ]; then echo directory; '
                       f'elif [ -e {shlex.quote(path)} ]; then echo other; else echo absent; fi')
        if out not in ('directory', 'symlink', 'other', 'absent'):
            raise RuntimeError('Cannot inspect marker state')
        return out

    def package(self, package):
        text = self.shell('dumpsys', 'package', package)
        match = re.search(r'\bversionName=([^\s]+)', text)
        code = re.search(r'\bversionCode=(\d+)', text)
        return {'version': match.group(1) if match else None,
                'code': int(code.group(1)) if code else None}

    def inspect(self):
        if self.run('get-state') != 'device':
            raise RuntimeError('Authorize this computer on the tablet before continuing')
        return {'schema': 1, 'recorded_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'serial': self.serial,
                'properties': {p: self.shell('getprop', p) for p in PROPS},
                'identity': self.shell('id'),
                'boot_id': self.shell('cat', '/proc/sys/kernel/random/boot_id'),
                'settings': {p: self.shell('settings', 'get', 'global', p)
                             for p in ('adb_enabled', 'development_settings_enabled')},
                'markers': {p: self.exists(p) for p in (MARKER, MARKER + '/keepme')},
                'packages': {p: self.package(p) for p in
                             ('com.ifit.eru', 'com.ifit.standalone', 'com.ifit.arda', 'com.android.webview')},
                'display_size': self.shell('wm', 'size'),
                'display_density': self.shell('wm', 'density')}

    @staticmethod
    def guard(state):
        if state.get('schema') != 1:
            raise ValueError('Unknown access baseline/report schema')
        props = state['properties']
        expected = {'ro.product.model': MODEL, 'ro.build.version.sdk': '22',
                    'ro.build.version.incremental': BUILD, 'ro.adb.secure': '1'}
        if any(props.get(p) != v for p, v in expected.items()):
            raise ValueError('Refusing changes: untested tablet model/build/API/authentication')
        if state['packages']['com.ifit.eru'] != ADMIN:
            raise ValueError('Refusing changes: untested iFit Admin version')
        if any(state['markers'].get(p) not in ('directory', 'absent')
               for p in (MARKER, MARKER + '/keepme')):
            raise ValueError('Marker path is not an ordinary directory; inspect it manually')

    @staticmethod
    def plan():
        return [('mkdir', '-p', MARKER + '/keepme'),
                ('am', 'broadcast', '-a', 'com.ifit.eru.PRIVILEGEDMODE', '-n', RECEIVER,
                 '--ez', 'PRIVILEGED_KEY', 'true'),
                ('setprop', 'persist.adb.tcp.port', '5555')]

    def configure(self, state, backup, apply):
        self.guard(state)
        for command in self.plan():
            print('Plan:', shlex.join(command))
        if not apply:
            print('Preview only. Add --apply to save a baseline and make these changes.')
            return
        save(backup, state)  # Exclusive creation BEFORE the first mutation.
        print('Baseline saved:', backup)
        for command in self.plan():
            result = self.shell(*command)
            if result:
                print(result)
            if 'exception' in result.lower() or 'error:' in result.lower():
                raise RuntimeError('Device refused a command; inspect baseline and current state')
        if self.shell('getprop', 'persist.adb.tcp.port') != '5555':
            raise RuntimeError('Persistent property did not stick; no success claimed')
        if self.exists(MARKER + '/keepme') != 'directory':
            raise RuntimeError('Marker was not created')
        print('Marker and persistent port verified. Check privileged mode visually, then power-cycle manually.')
        print('No daemon restart or reboot performed. Provisioning does not prove boot persistence.')

    def remove_marker(self, state, baseline, apply):
        self.guard(state)
        self.guard(baseline)
        # Serial can legitimately change with DHCP. Require explicit matching device identity
        # properties, and never infer ownership from the current IP address alone.
        if baseline.get('serial') != self.serial:
            raise ValueError('Baseline serial differs; review DHCP/device identity and update a COPY manually')
        paths = [p for p in (MARKER + '/keepme', MARKER)
                 if baseline['markers'][p] == 'absent' and state['markers'][p] != 'absent']
        print('Remove only empty directories absent in baseline:', paths)
        if apply:
            for p in paths:
                self.shell('rmdir', p)  # Never recursive: existing contents must be handled manually.
                if self.exists(p) != 'absent':
                    raise RuntimeError('Marker not removed; inspect contents/permissions manually')
        else:
            print('Preview only. Add --apply to remove them. Privileged-mode setting and ports are unchanged.')

    def disable_wifi(self, state, apply):
        self.guard(state)
        print('Clear both TCP properties and restart adbd. Network ADB will disconnect.')
        if not apply:
            print('Preview only. Add --apply. Local Termux access should remain available.')
            return
        # One remote shell so the final restart can execute after both writes.
        command = "setprop persist.adb.tcp.port '' && setprop service.adb.tcp.port '' && setprop ctl.restart adbd"
        try:
            print(self.run('shell', command))
        except (RuntimeError, subprocess.TimeoutExpired) as exc:
            print('Connection closed or restart failed; verify locally in Termux:', exc, file=sys.stderr)
            raise RuntimeError('Remote confirmation unavailable; no success claimed') from exc
        print('Disable requested; verify both properties locally in Termux. No remote success assumed.')


def save(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
    path.chmod(0o600)


def verify(state, baseline):
    Access.guard(state)
    Access.guard(baseline)
    if state['serial'] != baseline['serial']:
        raise ValueError('Baseline serial differs; check DHCP/device identity before comparing')
    props = state['properties']
    checks = {'new_boot': bool(state['boot_id']) and state['boot_id'] != baseline['boot_id'],
              'boot_completed': props['sys.boot_completed'] == '1',
              'persistent_port': props['persist.adb.tcp.port'] == '5555',
              'authenticated_adb': props['ro.adb.secure'] == '1',
              'usb_debugging': state['settings']['adb_enabled'] == '1',
              'developer_options': state['settings']['development_settings_enabled'] == '1',
              'marker': state['markers'][MARKER + '/keepme'] == 'directory'}
    print(json.dumps(checks, indent=2))
    if not all(checks.values()):
        raise ValueError('Persistence checks incomplete; do not claim reboot verification')
    print('ADB reboot persistence verified. Confirm privileged-mode UI yourself; root is not a pass condition.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True, help='Explicit authorized ADB device, e.g. 192.0.2.10:5555')
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('inspect').add_argument('--output', type=Path)
    configure = sub.add_parser('configure')
    configure.add_argument('--apply', action='store_true')
    configure.add_argument('--backup', type=Path, default=Path(__file__).resolve().parent / 'local' /
                           ('before-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.json'))
    check = sub.add_parser('verify-reboot')
    check.add_argument('--baseline', type=Path, required=True)
    remove = sub.add_parser('remove-marker')
    remove.add_argument('--baseline', type=Path, required=True)
    remove.add_argument('--apply', action='store_true')
    disable = sub.add_parser('disable-wifi')
    disable.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    device = Access(args.adb, args.serial)
    state = device.inspect()
    if args.action == 'inspect':
        print(json.dumps(state, indent=2))
        if args.output:
            save(args.output, state)
    elif args.action == 'configure':
        device.configure(state, args.backup, args.apply)
    elif args.action == 'verify-reboot':
        verify(state, json.loads(args.baseline.read_text()))
    elif args.action == 'remove-marker':
        device.remove_marker(state, json.loads(args.baseline.read_text()), args.apply)
    elif args.action == 'disable-wifi':
        device.disable_wifi(state, args.apply)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        print('Stopped:', error, file=sys.stderr)
        sys.exit(1)
