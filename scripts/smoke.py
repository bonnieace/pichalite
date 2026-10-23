#!/usr/bin/env python3
"""Real Rivium process + Apalis persistence and crash recovery smoke test."""
import json
import contextlib
import pathlib
import signal
import sqlite3
import subprocess
import tempfile
import time

from PIL import Image

PROJECT = pathlib.Path(__file__).resolve().parents[1]
BINARY = PROJECT / 'target/debug/pichalite'

def wait_for(root, predicate, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with sqlite3.connect(root / 'queue.sqlite') as db:
                if predicate(db):
                    return
        except sqlite3.Error:
            pass
        time.sleep(.04)
    raise AssertionError('Timed out waiting for queue state')

def start(root):
    offset = (root / 'runtime.log').stat().st_size if (root / 'runtime.log').exists() else 0
    log = open(root / 'runtime.log', 'ab')
    process = subprocess.Popen([str(BINARY), '--root', str(root)], stdout=log, stderr=log)
    log.close()
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError((root / 'runtime.log').read_text())
        with open(root / 'runtime.log', 'rb') as output:
            output.seek(offset)
            if b'PichaLite ready:' in output.read():
                return process
        time.sleep(.03)
    process.kill()
    process.wait()
    raise AssertionError('Runtime did not announce readiness')

def stop(process, crash=False):
    process.send_signal(signal.SIGKILL if crash else signal.SIGINT)
    process.wait(timeout=15)
    if not crash:
        assert process.returncode == 0, process.returncode

with contextlib.nullcontext(tempfile.mkdtemp(prefix='pichalite-smoke-')) as directory:
    root = pathlib.Path(directory)
    print('Smoke evidence:', root, flush=True)
    p = start(root)
    try:
        wait_for(root, lambda db: db.execute("SELECT count(*) FROM settings").fetchone()[0] == 0)
    finally:
        stop(p)
    # The first launch stops before any photos arrive. Pause is persisted.
    with sqlite3.connect(root / 'queue.sqlite') as db:
        db.execute("INSERT INTO settings VALUES('paused','true')")
    image = Image.effect_noise((1800, 1200), 90).convert('RGB')
    for i in range(3):
        image.save(root / f'inbox/test-{i}.jpg', quality=98)
    p = start(root)
    try:
        wait_for(root, lambda db: db.execute('SELECT count(*) FROM Jobs').fetchone()[0] == 3)
        assert not list((root / 'output').glob('*.jpg'))
    finally:
        stop(p, crash=True)
    # The process died with a claimed job and two queued jobs.
    with sqlite3.connect(root / 'queue.sqlite') as db:
        assert db.execute('SELECT count(*) FROM Jobs').fetchone()[0] == 3
        db.execute("UPDATE settings SET value='false' WHERE key='paused'")
    p = start(root)
    try:
        wait_for(root, lambda db: db.execute("SELECT count(*) FROM Jobs WHERE status='Done'").fetchone()[0] == 3, timeout=180)
        with sqlite3.connect(root / 'queue.sqlite') as db:
            assert db.execute('SELECT count(*) FROM photos WHERE output IS NOT NULL').fetchone()[0] == 3
        files = list((root / 'output').glob('*.jpg'))
        assert len(files) == 3
        for file in files:
            output = Image.open(file)
            assert max(output.size) == 1600
    finally:
        stop(p)
    p = start(root)
    try:
        wait_for(root, lambda db: db.execute("SELECT count(*) FROM Jobs WHERE status='Done'").fetchone()[0] == 3)
        assert len(list((root / 'output').glob('*.jpg'))) == 3
    finally:
        stop(p)
    print(json.dumps({'ok': True, 'photos': 3, 'crash_recovery': True, 'duplicate_outputs': 0, 'max_edge': 1600}))
