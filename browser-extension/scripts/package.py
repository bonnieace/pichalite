from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json
import argparse
import copy
import shutil

root = Path(__file__).resolve().parents[1]
out = root / 'dist'
out.mkdir(exist_ok=True)
base = json.loads((root / 'manifest.json').read_text())
version = base['version']


def manifest_for(target):
    manifest = copy.deepcopy(base)
    if target in {'firefox', 'safari'}:
        manifest.pop('minimum_chrome_version', None)
        manifest.pop('side_panel', None)
        manifest['permissions'].remove('sidePanel')
    if target == 'firefox':
        manifest['background'] = {'scripts': ['background.js'], 'type': 'module'}
        manifest['sidebar_action'] = {'default_panel': 'app.html', 'default_title': 'PichaLite'}
        manifest['browser_specific_settings'] = {'gecko': {
            'id': 'photos@pichalite.co.ke', 'strict_min_version': '142.0',
            'data_collection_permissions': {'required': ['websiteContent', 'locationInfo']}
        }}
    if target == 'safari':
        # Source for Apple's converter, not an installable/signed Safari app.
        manifest['permissions'].remove('unlimitedStorage')
    return manifest


def build(target):
    directory = out / target
    directory.mkdir(exist_ok=True)
    files = [p for p in root.iterdir() if p.suffix in {'.js', '.css', '.html'} or p.name == 'README.md']
    for file in files:
        shutil.copyfile(file, directory / file.name)
    shutil.copytree(root / 'icons', directory / 'icons', dirs_exist_ok=True)
    shutil.copyfile(root.parent / 'LICENSE', directory / 'LICENSE')
    (directory / 'manifest.json').write_text(json.dumps(manifest_for(target), indent=2) + '\n')
    suffix = 'source' if target == 'safari' else 'preview'
    archive = out / f'PichaLite-Google-Photos-{version}-{target}-{suffix}.zip'
    with ZipFile(archive, 'w', ZIP_DEFLATED) as bundle:
        for file in sorted(directory.rglob('*')):
            if file.is_file():
                bundle.write(file, file.relative_to(directory))
    print(archive)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', choices=['chromium', 'firefox', 'safari', 'all'], default='all')
    target = parser.parse_args().target
    for name in ['chromium', 'firefox', 'safari'] if target == 'all' else [target]:
        build(name)
