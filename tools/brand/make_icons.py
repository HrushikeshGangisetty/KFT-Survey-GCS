# Builds the rail mark, About logo and placeholder app icons from docs/brand/kft-logo.png.
# Run from the repo root: py -3 tools/brand/make_icons.py  (needs Pillow). Replace with an SVG-based build when one exists.
from PIL import Image, ImageDraw
NAVY = (0x1F, 0x2E, 0x5D, 255)
src = Image.open('docs/brand/kft-logo.png').convert('RGBA')
alpha = src.split()[3]
full = src.crop(src.getbbox())
# The letters end above the "KAPIL FUTURE TECH" line (y ~ 600 in the 1250 x 834 source).
mark = src.crop((0, 0, src.width, 590)); mark = mark.crop(mark.getbbox())
print('full', full.size, 'mark', mark.size)
def scaled(im, w): return im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
res = 'ui/design/src/commonMain/composeResources/drawable/'
scaled(full, 800).save(res + 'kft_logo.png', optimize=True)
scaled(mark, 400).save(res + 'kft_mark.png', optimize=True)
def white(im):
    w = Image.new('RGBA', im.size, (255, 255, 255, 0)); w.putalpha(im.split()[3]); return w
wmark = white(mark)
def icon(size, mark_frac, rounded):
    c = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(c).rounded_rectangle((0, 0, size - 1, size - 1), radius=round(size * 0.18) if rounded else 0, fill=NAVY)
    m = scaled(wmark, round(size * mark_frac)); c.alpha_composite(m, ((size - m.width) // 2, (size - m.height) // 2)); return c
# Desktop: window icon + Windows .ico / Linux .png for packaging.
big = icon(512, 0.70, True)
big.resize((256, 256), Image.LANCZOS).save(res + 'kft_app_icon.png', optimize=True)
big.save('app/desktop/icons/kft.png')
big.save('app/desktop/icons/kft.ico', sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
# Android adaptive icon foreground: 108 dp canvas, the mark inside the 66 dp safe circle (width 56/108).
for dpi, px in {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}.items():
    import os; d = f'app/android/src/main/res/mipmap-{dpi}'; os.makedirs(d, exist_ok=True)
    c = Image.new('RGBA', (px, px), (0, 0, 0, 0)); m = scaled(wmark, round(px * 56 / 108))
    c.alpha_composite(m, ((px - m.width) // 2, (px - m.height) // 2)); c.save(f'{d}/ic_launcher_foreground.png', optimize=True)
