"""Execute the device shell with isolated fake system tools, never Android/ADB."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'state.json'
        self.state.write_text(json.dumps({'ro.product.model': 'MalataSamsungRoyalWolfSinglePartition',
            'ro.build.version.incremental': 'MGRSP_20210806', 'ro.build.version.sdk': '22',
            'ro.adb.secure': '1', 'persist.adb.tcp.port': '', 'service.adb.tcp.port': ''}))
        self.log = self.root / 'writes.jsonl'
        bindir = self.root / 'bin'; bindir.mkdir()
        fake = bindir / 'fake'
        fake.write_text('#!' + sys.executable + '\n' + '''import json,os,sys
from pathlib import Path
p=Path(os.environ['FAKE_STATE']);state=json.loads(p.read_text())
cmd=Path(sys.argv[0]).name
if cmd=='getprop': print(state.get(sys.argv[1],''))
elif cmd=='id': print('uid=10049(u0_a49) gid=10049(u0_a49)')
elif cmd=='setprop':
 with open(os.environ['FAKE_LOG'],'a') as f:f.write(json.dumps(sys.argv[1:])+'\\n')
 if os.environ.get('DENY_PORT') and sys.argv[1]=='persist.adb.tcp.port':sys.exit(1)
 state[sys.argv[1]]=sys.argv[2];p.write_text(json.dumps(state))
''')
        fake.chmod(0o755)
        for name in ('getprop','setprop','id'): (bindir/name).symlink_to(fake)
        source = Path(__file__).with_name('enable-wifi-adb.sh').read_text()
        self.script = self.root/'bootstrap.sh'
        self.script.write_text(source.replace('PATH=/system/bin:/system/xbin', 'PATH='+shlex.quote(str(bindir))))
        self.env = {**os.environ, 'HOME': str(self.root), 'FAKE_STATE': str(self.state), 'FAKE_LOG': str(self.log)}

    def run_script(self, *args):
        return subprocess.run(['sh',str(self.script),*args], env=self.env, capture_output=True,text=True)

    def writes(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_check_never_mutates(self):
        result=self.run_script('--check'); self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse(self.writes()); self.assertFalse((self.root/'stride-adb-before.txt').exists())

    def test_apply_sets_ports_before_restart_and_retains_authentication(self):
        result=self.run_script('--apply'); self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual([['persist.adb.tcp.port','5555'],['service.adb.tcp.port','5555'],['ctl.restart','adbd']],self.writes())
        self.assertEqual('1',json.loads(self.state.read_text())['ro.adb.secure'])
        self.assertIn('persist.adb.tcp.port=\n',(self.root/'stride-adb-before.txt').read_text())

    def test_unknown_build_refuses_all_mutations(self):
        state=json.loads(self.state.read_text());state['ro.build.version.incremental']='new';self.state.write_text(json.dumps(state))
        self.assertNotEqual(0,self.run_script('--apply').returncode);self.assertFalse(self.writes())

    def test_denial_does_not_restart_daemon(self):
        self.env['DENY_PORT']='1'
        self.assertNotEqual(0,self.run_script('--apply').returncode)
        self.assertEqual([['persist.adb.tcp.port','5555']],self.writes())

    def test_second_apply_preserves_first_snapshot(self):
        self.assertEqual(0,self.run_script('--apply').returncode)
        snapshot=(self.root/'stride-adb-before.txt').read_text()
        self.assertEqual(0,self.run_script('--apply').returncode)
        self.assertEqual(snapshot,(self.root/'stride-adb-before.txt').read_text())

    def test_unknown_action_cannot_mutate(self):
        self.assertNotEqual(0,self.run_script('--force').returncode);self.assertFalse(self.writes())


if __name__ == '__main__': unittest.main()
