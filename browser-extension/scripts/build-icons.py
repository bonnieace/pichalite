"""Render the existing Android PichaLite photo mark as store/toolbar PNGs."""
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1]
directory = root / 'icons'
directory.mkdir(exist_ok=True)

# Same geometry/colors as android/.../drawable/ic_pichalite.xml, with transparent
# store-safe padding. This preserves the established logo rather than inventing one.
scale = 8
canvas = Image.new('RGBA', (128 * scale, 128 * scale))
draw = ImageDraw.Draw(canvas)
def point(x, y):
    return ((16 + x * 96 / 108) * scale, (16 + y * 96 / 108) * scale)
def rect(x1, y1, x2, y2, color):
    draw.rectangle((*point(x1, y1), *point(x2, y2)), fill=color)
rect(0, 0, 108, 108, '#D5502B')
rect(29, 27, 79, 81, '#FFF5D9')
rect(35, 33, 73, 64, '#D5502B')
draw.polygon([point(x, y) for x, y in [(36, 63), (49, 47), (58, 56), (65, 48), (73, 63)]], fill='#FFF5D9')
draw.ellipse((*point(62, 36), *point(72, 46)), fill='#F3C858')
for size in [16, 32, 48, 128]:
    canvas.resize((size, size), Image.Resampling.LANCZOS).save(directory / f'icon-{size}.png')
print(directory)
