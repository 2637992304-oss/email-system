"""Scan tracked files (or a source snapshot) without printing matching values."""
from pathlib import Path
import re, subprocess, sys

root = Path(__file__).resolve().parents[1]
try:
    names = subprocess.check_output(['git','ls-files','-z'],cwd=root,stderr=subprocess.DEVNULL).decode().split('\0')
    files = [root / n for n in names if n]
except subprocess.CalledProcessError:
    files = list(root.rglob('*'))
rules = {
    'private key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'provider token': r'\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{30,})',
    'GLM key': r'\b[a-fA-F0-9]{32}\.[A-Za-z0-9]{16}\b',
    'JWT': r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}',
}
excluded = {'.git','node_modules','target','dist','data','.npm-cache'}
findings = []
for file in files:
    if not file.is_file() or excluded.intersection(file.relative_to(root).parts): continue
    relative = file.relative_to(root).as_posix()
    if file.name.startswith('.env') and file.name != '.env.example':
        findings.append((relative,0,'local environment file')); continue
    if file.suffix in {'.pem','.key','.pfx','.p12','.ibd'}:
        findings.append((relative,0,'private/runtime file')); continue
    data = file.read_bytes()
    if b'\x00' in data: continue
    for number,line in enumerate(data.decode('utf-8',errors='replace').splitlines(),1):
        for name,pattern in rules.items():
            if re.search(pattern,line): findings.append((relative,number,name))
        if file.suffix in {'.yml','.yaml'}:
            setting = re.match(r'\s*(?:api-key|jwt-secret|password):\s*(.+)',line)
            if setting and not setting[1].lstrip('"\'').startswith('${'):
                findings.append((relative,number,'literal credential setting'))
for path,line,reason in findings: print(f'{path}:{line}: {reason}')
print(f'Scanned {len(files)} paths; {len(findings)} potential sensitive findings. Values suppressed.')
sys.exit(bool(findings))
