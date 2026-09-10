"""Read-only upgrade evidence. Emits counts and hashes, never articles or key material."""
import hashlib
import json
import pathlib
import sqlite3
import subprocess
import sys
import tempfile

serial, phase = sys.argv[1:]
assert phase in ('before', 'after')
adb = ['/Users/schober/Library/Android/sdk/platform-tools/adb', '-s', serial]
def read(path):
    return subprocess.run(adb + ['exec-out', 'run-as', 'com.reader.app', 'cat', path], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

tables = ['documents', 'document_content', 'highlights', 'labels', 'document_labels', 'channels', 'review_state', 'reading_days']
result = {'package': 'com.reader.app', 'tables': {}}
with tempfile.TemporaryDirectory(prefix='reader-owner-integrity-') as folder:
    root = pathlib.Path(folder)
    for name in ('reader.db', 'reader.db-wal'):
        try: (root / name).write_bytes(read('databases/' + name))
        except subprocess.CalledProcessError:
            if name == 'reader.db': raise
    db = sqlite3.connect(f'file:{root}/reader.db?mode=ro', uri=True)
    result['schema'] = db.execute('PRAGMA user_version').fetchone()[0]
    assert result['schema'] == 13
    for table in tables:
        columns = [row[1] for row in db.execute(f'PRAGMA table_info({table})')]
        if not columns: continue
        rows = db.execute(f'SELECT * FROM {table} ORDER BY ' + ','.join('"'+c+'"' for c in columns))
        digest = hashlib.sha256(); count = 0
        for row in rows:
            digest.update(json.dumps(row, default=lambda b: {'bytes': b.hex()}, ensure_ascii=False, separators=(',', ':')).encode())
            digest.update(b'\n'); count += 1
        result['tables'][table] = {'rows': count, 'sha256': digest.hexdigest()}
    db.close()
result['preferencesSha256'] = hashlib.sha256(read('files/datastore/reader_settings.preferences_pb')).hexdigest()
path = pathlib.Path('evidence/focused-20260910') / f'owner-{phase}.json'
path.write_text(json.dumps(result, indent=2)+'\n')
if phase == 'after':
    before = json.loads(path.with_name('owner-before.json').read_text())
    result['preserved'] = result == before
    path.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({'ownerDataPreserved': result['preserved'], 'tablesCompared': len(result['tables'])}))
    assert result['preserved'], 'Owner snapshot changed; inspect hashes before claiming preservation.'
else:
    print(json.dumps({'ownerArticles': result['tables']['documents']['rows'], 'ownerHighlights': result['tables']['highlights']['rows'], 'ownerChannels': result['tables']['channels']['rows']}))
