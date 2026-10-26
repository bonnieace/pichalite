"""Verify the installable extension actually includes a Windows native engine."""
import json
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as package:
    assert package.testzip() is None
    binary=package.read('extension/bin/pichalite-cli.exe')
    assert binary[:2]==b'MZ'
    manifest=json.loads(package.read('extension/package.json'))
    assert manifest['main']=='./src/extension.js'
    assert 'win32-x64' in package.read('extension.vsixmanifest').decode()
    assert len(binary)>10000
    print('PASS VSIX: bundled Windows executable, extension entrypoint and target platform verified')
