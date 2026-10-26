"""Real CLI contract test; no third-party Python packages required."""
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zlib


def png(width, height):
    def chunk(kind, data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
    rows=b''.join(b'\0'+bytes((x+y)%256 for x in range(width*3)) for y in range(height))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows,0))+chunk(b'IEND',b'')


with tempfile.TemporaryDirectory() as temp:
    root=Path(temp)
    source=root/'photo with spaces.png'
    source.write_bytes(png(800,600))
    original=source.read_bytes()
    broken=root/'broken.jpg'
    broken.write_bytes(b'not an image')
    request={'inputs':[str(source),str(broken)],'output_dir':str(root/'out'),'quality':82,'max_edge':512}
    result=subprocess.run([sys.argv[1]],input=json.dumps(request),text=True,capture_output=True,timeout=120)
    assert result.returncode==2,(result.returncode,result.stderr)
    rows=[json.loads(line) for line in result.stdout.splitlines()]
    good=next(row for row in rows if row['status']=='compressed')
    assert good['saved_bytes']>0
    assert Path(good['output']).read_bytes().startswith(b'\xff\xd8')
    assert source.read_bytes()==original
    assert sum(row['status']=='failed' for row in rows)==1
    again=subprocess.run([sys.argv[1]],input=json.dumps(request),text=True,capture_output=True,timeout=120)
    other=next(json.loads(line) for line in again.stdout.splitlines() if json.loads(line)['status']=='compressed')
    assert other['output']!=good['output']
    request['quality']=0
    invalid=subprocess.run([sys.argv[1]],input=json.dumps(request),text=True,capture_output=True,timeout=120)
    assert invalid.returncode==1
    print('PASS CLI: real image, paths with spaces, mixed failures, savings, immutable sources, collision-safe output, settings validation')
