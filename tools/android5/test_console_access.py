import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('console_access', Path(__file__).with_name('console_access.py'))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def baseline():
    return {'schema': 1, 'serial': '192.0.2.10:5555', 'boot_id': 'boot-before',
            'properties': {**dict.fromkeys(mod.PROPS, ''),
                'ro.product.model': mod.MODEL, 'ro.build.version.sdk': '22',
                'ro.build.version.incremental': mod.BUILD, 'ro.adb.secure': '1'},
            'settings': {'adb_enabled': '1', 'development_settings_enabled': '1'},
            'packages': {'com.ifit.eru': copy.deepcopy(mod.ADMIN)},
            'markers': {mod.MARKER: 'absent', mod.MARKER + '/keepme': 'absent'}}


class Fake(mod.Access):
    def __init__(self, state=None):
        super().__init__('unused-adb', '192.0.2.10:5555')
        self.state = copy.deepcopy(state or baseline())
        self.writes = []
        self.backup_required = None
        self.deny_port = False
        self.nonempty = False

    def shell(self, *args):
        if args[0] == 'getprop':
            return self.state['properties'].get(args[1], '')
        if self.backup_required:
            assert self.backup_required.exists(), 'Mutation occurred before baseline save'
        self.writes.append(args)
        if args[0] == 'mkdir':
            self.state['markers'] = dict.fromkeys(self.state['markers'], 'directory')
        elif args[0] == 'setprop' and not self.deny_port:
            self.state['properties'][args[1]] = args[2]
        elif args[0] == 'rmdir':
            if self.nonempty:
                raise RuntimeError('Directory not empty')
            self.state['markers'][args[1]] = 'absent'
        return ''

    def exists(self, path):
        return self.state['markers'][path]


class AccessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.backup = Path(self.temp.name) / 'before.json'
        self.quiet = contextlib.redirect_stdout(io.StringIO())
        self.quiet.__enter__()
        self.addCleanup(self.quiet.__exit__, None, None, None)

    def test_explicit_device_is_required(self):
        for serial in ('', '-s', 'one two', 'one\nsecond'):
            with self.assertRaises(ValueError):
                mod.Access('adb', serial)

    def test_unknown_hardware_and_admin_fail_before_backup_or_write(self):
        for key, value in [('ro.product.model', 'other'), ('ro.build.version.sdk', '23'),
                           ('ro.build.version.incremental', 'new'), ('ro.adb.secure', '0')]:
            state = baseline(); state['properties'][key] = value
            fake = Fake(state)
            with self.assertRaises(ValueError):
                fake.configure(state, self.backup, True)
            self.assertFalse(self.backup.exists()); self.assertFalse(fake.writes)
        state = baseline(); state['packages']['com.ifit.eru']['code'] = 1603
        with self.assertRaises(ValueError):
            Fake(state).configure(state, self.backup, True)

    def test_preview_is_read_only_and_creates_no_backup(self):
        fake = Fake(); fake.configure(fake.state, self.backup, False)
        self.assertFalse(self.backup.exists()); self.assertFalse(fake.writes)

    def test_apply_saves_original_state_before_first_change(self):
        fake = Fake(); original = copy.deepcopy(fake.state); fake.backup_required = self.backup
        fake.configure(fake.state, self.backup, True)
        self.assertEqual(original, json.loads(self.backup.read_text()))
        self.assertEqual(0o600, self.backup.stat().st_mode & 0o777)
        self.assertEqual('5555', fake.state['properties']['persist.adb.tcp.port'])
        self.assertFalse(any(a[0] in ('reboot', 'pm', 'appops') for a in fake.writes))
        self.assertFalse(any('ctl.restart' in a for a in fake.writes))

    def test_existing_baseline_cannot_be_overwritten_or_followed_by_mutations(self):
        self.backup.write_text('original')
        fake = Fake()
        with self.assertRaises(FileExistsError):
            fake.configure(fake.state, self.backup, True)
        self.assertEqual('original', self.backup.read_text()); self.assertFalse(fake.writes)

    def test_failed_property_readback_is_not_success(self):
        fake = Fake(); fake.deny_port = True
        with self.assertRaises(RuntimeError):
            fake.configure(fake.state, self.backup, True)
        self.assertTrue(self.backup.exists())

    def test_existing_marker_is_never_removed(self):
        old = baseline(); old['markers'] = dict.fromkeys(old['markers'], 'directory')
        fake = Fake(old); fake.remove_marker(fake.state, old, True)
        self.assertFalse(fake.writes)

    def test_symlink_or_other_marker_is_rejected_before_any_change(self):
        for kind in ('symlink', 'other'):
            state = baseline(); state['markers'][mod.MARKER] = kind
            fake = Fake(state)
            with self.assertRaises(ValueError):
                fake.configure(state, self.backup, True)
            self.assertFalse(fake.writes)

    def test_silent_legacy_shell_rmdir_failure_is_detected_by_readback(self):
        old = baseline(); fake = Fake()
        fake.state['markers'] = dict.fromkeys(fake.state['markers'], 'directory')
        fake.shell = lambda *args: ''
        with self.assertRaises(RuntimeError):
            fake.remove_marker(fake.state, old, True)

    def test_nonempty_marker_aborts_without_recursive_deletion(self):
        old = baseline(); fake = Fake()
        fake.state['markers'] = dict.fromkeys(fake.state['markers'], 'directory')
        fake.nonempty = True
        with self.assertRaises(RuntimeError):
            fake.remove_marker(fake.state, old, True)
        self.assertEqual([('rmdir', mod.MARKER + '/keepme')], fake.writes)

    def test_reboot_success_requires_changed_boot_and_completed_secure_state(self):
        old = baseline(); current = copy.deepcopy(old)
        current['properties'].update({'sys.boot_completed': '1', 'persist.adb.tcp.port': '5555'})
        current['markers'] = dict.fromkeys(current['markers'], 'directory')
        with self.assertRaises(ValueError):
            mod.verify(current, old)
        current['boot_id'] = 'boot-after'; mod.verify(current, old)
        for field in ('sys.boot_completed', 'persist.adb.tcp.port', 'ro.adb.secure'):
            altered = copy.deepcopy(current); altered['properties'][field] = '0'
            with self.assertRaises(ValueError):
                mod.verify(altered, old)

    def test_changed_address_is_not_silently_treated_as_the_same_device(self):
        old = baseline(); old['serial'] = '192.0.2.99:5555'
        fake = Fake()
        with self.assertRaises(ValueError):
            fake.remove_marker(fake.state, old, True)
        self.assertFalse(fake.writes)

    def test_remote_shell_quotes_values_without_local_shell(self):
        access = mod.Access('adb', 'usb-serial')
        commands = []
        access.run = lambda *args: commands.append(args) or ''
        access.shell('setprop', 'service.adb.tcp.port', '')
        self.assertEqual(('shell', "setprop service.adb.tcp.port ''"), commands[0])

    def test_inspection_calls_only_read_operations(self):
        access = mod.Access('adb', 'usb-serial')
        calls = []
        def run(*args):
            calls.append(args)
            if args == ('get-state',): return 'device'
            if args[0] == 'shell' and args[1].startswith('if '): return 'absent'
            if args[0] == 'shell' and args[1].startswith('dumpsys '): return 'versionName=2.2.20\nversionCode=1602 minSdk=22'
            return ''
        access.run = run
        state = access.inspect()
        self.assertEqual(mod.ADMIN, state['packages']['com.ifit.eru'])
        for args in calls:
            if args[0] == 'shell':
                self.assertTrue(args[1].startswith(('getprop ', 'id', 'cat ', 'settings get ', 'if ', 'dumpsys ', 'wm ')), args)


if __name__ == '__main__':
    unittest.main()
