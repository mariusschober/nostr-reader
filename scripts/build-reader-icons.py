"""Deterministic exports of the supplied straight-path SVG; no redrawing."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw
root = Path(__file__).resolve().parents[1]
svg = ET.parse(root / 'logo.svg').getroot()
shapes = []
for element in svg:
    kind = element.tag.rsplit('}', 1)[-1]
    if kind == 'path':
        tokens = re.findall(r'[MHVLZ]|-?\d+(?:\.\d+)?', element.attrib['d'])
        points=[]; x=y=0; i=0
        while i < len(tokens):
            command=tokens[i]; i+=1
            if command in ('M','L'): x,y=map(float,tokens[i:i+2]); i+=2
            elif command=='H': x=float(tokens[i]); i+=1
            elif command=='V': y=float(tokens[i]); i+=1
            elif command=='Z': continue
            else: raise ValueError(command)
            points.append((x,y))
        shapes.append((points,element.attrib['fill']))
    elif kind=='rect':
        x,y,w,h=(float(element.attrib[k]) for k in ('x','y','width','height'))
        shapes.append(([(x,y),(x+w,y),(x+w,y+h),(x,y+h)],element.attrib['fill']))

def render(size, android=False):
    scale=8
    im=Image.new('RGBA',(size*scale,size*scale),'#FDF8EB' if android else (0,0,0,0))
    draw=ImageDraw.Draw(im)
    # Artwork bounds are 269..975 / 132..1119. Preserve its aspect ratio.
    factor=size*scale*(.66 if android else .94)/987
    for points,color in shapes:
        draw.polygon([((x-622)*factor+size*scale/2,(y-625.5)*factor+size*scale/2) for x,y in points],fill=color)
    return im.resize((size,size),Image.Resampling.LANCZOS)
for size in (16,32,48,128): render(size).save(root/f'chrome/icons/icon{size}.png')
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    directory=root/f'android/app/src/main/res/mipmap-{density}'; directory.mkdir(exist_ok=True)
    render(size,True).save(directory/'ic_launcher.png')
render(256,True).save('/tmp/reader-icon-preview.png')
