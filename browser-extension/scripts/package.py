from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json

root = Path(__file__).resolve().parents[1]
out = root / 'dist'
out.mkdir(exist_ok=True)
version = json.loads((root / 'manifest.json').read_text())['version']
target = out / f'PichaLite-Google-Photos-{version}-preview.zip'
with ZipFile(target, 'w', ZIP_DEFLATED) as bundle:
    bundle.write(root.parent / 'LICENSE', 'LICENSE')
    for file in sorted(root.iterdir()):
        if file.suffix in {'.js', '.css', '.html'} or file.name in {'manifest.json', 'README.md'}:
            bundle.write(file, file.name)
print(target)
