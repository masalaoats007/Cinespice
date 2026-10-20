#!/usr/bin/env python3
"""Compile the production login client against the pinned macOS TDLib artifact."""
import argparse
import platform
import plistlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    args = parser.parse_args()
    if platform.system() != 'Darwin':
        parser.exit(1, 'Native TDLib startup check requires macOS and Xcode.\n')
    architecture = platform.machine()
    framework = None
    for metadata in args.artifacts.rglob('TDLibFramework.xcframework/Info.plist'):
        info = plistlib.loads(metadata.read_bytes())
        for library in info['AvailableLibraries']:
            if library['SupportedPlatform'] == 'macos' and architecture in library['SupportedArchitectures']:
                framework = metadata.parent / library['LibraryIdentifier'] / library['LibraryPath']
                break
        if framework:
            break
    if not framework or not framework.is_dir():
        parser.exit(1, 'The resolved pinned TDLib artifact has no matching macOS framework.\n')
    sources = [
        'HTTPVideoRequest.swift', 'LibraryDatabase.swift', 'LibraryPaging.swift',
        'LibraryStore.swift', 'PlaybackActivity.swift', 'SubtitleAppearance.swift',
        'TelegramClient.swift', 'TelegramDiscovery.swift', 'TelegramLogin.swift',
        'TelegramMedia.swift', 'TelegramRangeReader.swift', 'TelegramVideoSearch.swift',
        'ThumbnailCache.swift',
    ]
    with tempfile.TemporaryDirectory(prefix='cinespice-native-login-') as folder:
        scratch = Path(folder)
        binary = scratch / 'check-login'
        subprocess.run([
            'xcrun', 'swiftc', '-swift-version', '5', '-parse-as-library', '-I', str(ROOT / 'Support/CSQLite'),
            '-F', str(framework.parent), '-framework', 'TDLibFramework', '-lc++', '-lz',
            '-Xlinker', '-rpath', '-Xlinker', str(framework.parent),
            '-lsqlite3', '-o', str(binary),
            *[str(ROOT / 'Sources' / source) for source in sources],
            str(ROOT / 'scripts/check-telegram-startup.swift'),
        ], cwd=ROOT, check=True)
        subprocess.run([str(binary), str(scratch / 'database')], cwd=scratch, check=True, timeout=180)


if __name__ == '__main__':
    main()
