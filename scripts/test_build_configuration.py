"""Synthetic build tests: reject missing keys and prevent edition contamination."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('configure-telegram.py')
spec = importlib.util.spec_from_file_location('build_config', SCRIPT)
build_config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_config)
SYNTHETIC = {'TELEGRAM_API_ID': '12345', 'TELEGRAM_API_HASH': '0123456789abcdef' * 2}

class BuildConfigurationTests(unittest.TestCase):
    def test_standard_never_contains_credentials(self):
        self.assertEqual(build_config.configuration('standard', SYNTHETIC), {'mode': 'standard'})

    def test_configured_requires_both_valid_values(self):
        for values in [{}, {'TELEGRAM_API_ID': '12345'}, {**SYNTHETIC, 'TELEGRAM_API_ID': '2147483648'}, {**SYNTHETIC, 'TELEGRAM_API_HASH': 'bad'}, {**SYNTHETIC, 'TELEGRAM_API_ID': '-1'}]:
            with self.subTest(values=list(values)):
                with self.assertRaises(ValueError): build_config.configuration('configured', values)

    def test_configured_preserves_synthetic_values(self):
        result = build_config.configuration('configured', SYNTHETIC)
        self.assertEqual(result['apiID'], SYNTHETIC['TELEGRAM_API_ID'])
        self.assertEqual(result['apiHash'], SYNTHETIC['TELEGRAM_API_HASH'])

    def test_generator_replaces_and_removes_stale_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            target = root / 'scripts/configure-telegram.py'
            shutil.copyfile(SCRIPT, target)
            outputs = [root / 'Resources/BuildConfig/telegram-build.json', root / 'android/app/src/main/assets/telegram-build.json']
            env = {key: value for key, value in os.environ.items() if key not in SYNTHETIC}
            def run(mode, credentials):
                return subprocess.run(['python3', str(target), '--mode', mode], env={**env, **credentials}, capture_output=True, text=True)
            configured = run('configured', SYNTHETIC)
            self.assertEqual(configured.returncode, 0)
            for output in outputs:
                self.assertEqual(json.loads(output.read_text())['mode'], 'configured')
            for value in SYNTHETIC.values(): self.assertNotIn(value, configured.stdout + configured.stderr)
            self.assertEqual(run('standard', SYNTHETIC).returncode, 0)
            for output in outputs: self.assertEqual(json.loads(output.read_text()), {'mode': 'standard'})
            self.assertEqual(run('configured', SYNTHETIC).returncode, 0)
            self.assertNotEqual(run('configured', {}).returncode, 0)
            for output in outputs: self.assertFalse(output.exists())

    def test_country_data_agrees_across_platforms(self):
        root = SCRIPT.parent.parent
        ios = json.loads((root / 'Resources/BuildConfig/countries.json').read_text())
        android = json.loads((root / 'android/app/src/main/assets/countries.json').read_text())
        self.assertEqual(ios, android)
        self.assertEqual(len({item['id'] for item in ios}), len(ios))
        self.assertGreater(len(ios), 200)
        for item in ios: self.assertRegex(item['dial'], r'^\+[1-9][0-9]*$')

if __name__ == '__main__': unittest.main()
