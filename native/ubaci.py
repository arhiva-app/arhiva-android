# Ubacuje Android adapter u www/index.html (poziva ga GitHub Actions pri pravljenju APK-a).
import sys, re
p = sys.argv[1] if len(sys.argv) > 1 else 'www/index.html'
s = open(p, encoding='utf-8').read()
tag = '<script src="capacitor.js"></script><script src="arhiva-native.js"></script>'
if tag not in s:
    m = re.search(r'<meta charset="UTF-8">', s)
    assert m, 'nema <meta charset>'
    s = s[:m.end()] + '\n' + tag + s[m.end():]
open(p, 'w', encoding='utf-8').write(s)
print('ubaceno u', p)
