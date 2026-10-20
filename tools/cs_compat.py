"""
Xstream: checks that every class / method / field the chosen CloudStream plugins call exists in the built APK
(our own CloudStream library + bundled libraries). Usage: cs_compat.py <apk> <plugin.cs3>...
Prints one line per missing member and a summary. Framework classes (android.*, java.*, javax.*, org.json, org.w3c,
org.xml) are provided by the phone and skipped.
"""
import sys, zipfile, io, os, tempfile
sys.path.insert(0, os.path.dirname(__file__))
import dexapi

SKIP = ('Landroid/', 'Ljava/', 'Ljavax/', 'Lorg/json/', 'Lorg/w3c/', 'Lorg/xml/', 'Ldalvik/', 'Lsun/', 'Llibcore/', 'Lorg/apache/http/')

def load_dexes(paths):
    classes = {}
    for p in paths:
        _, _, _, cl = dexapi.parse(p)
        classes.update(cl)
    return classes

def members(classes, c):
    seen = set(); out = set(); fl = set()
    while c and c in classes and c not in seen:
        seen.add(c)
        s, ifs, dm = classes[c]
        for n, pr, a in dm: out.add(n + pr)
        c = s
    return out, c  # c = first class outside the APK (framework) or None

def main():
    apk = sys.argv[1]
    tmp = tempfile.mkdtemp()
    dexes = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.startswith('classes') and n.endswith('.dex'):
                p = os.path.join(tmp, n); open(p, 'wb').write(z.read(n)); dexes.append(p)
    app = load_dexes(dexes)
    total_missing = 0
    for plug in sys.argv[2:]:
        name = os.path.basename(plug)
        with zipfile.ZipFile(plug) as z:
            p = os.path.join(tmp, name + '.dex'); open(p, 'wb').write(z.read('classes.dex'))
        types, meths, fields, own = dexapi.parse(p)
        missing = []
        for c, n, pr in meths:
            if c in own or c.startswith(SKIP) or c.startswith('['): continue
            if c not in app: missing.append('class ' + c); continue
            ms, outside = members(app, c)
            if n + pr in ms: continue
            if outside is not None and outside.startswith(SKIP): continue  # may come from the framework parent
            if n == '<init>' and (n + pr) not in ms: missing.append('ctor %s%s' % (c, pr)); continue
            # interfaces: search their members too
            found = False
            stack = [c]
            while stack and not found:
                x = stack.pop()
                if x not in app: continue
                s, ifs, dm = app[x]
                if any(dn + dpr == n + pr for dn, dpr, a in dm): found = True
                stack += ifs + ([s] if s else [])
            if not found: missing.append('method %s->%s%s' % (c, n, pr))
        for c, n, t in fields:
            if c in own or c.startswith(SKIP): continue
            if c not in app: missing.append('class ' + c); continue
        missing = sorted(set(missing))
        total_missing += len(missing)
        print('CSCHK %s: %s' % (name, 'OK' if not missing else '%d missing' % len(missing)))
        for m in missing[:60]: print('CSCHK   ' + m)
    print('CSCHK TOTAL missing: %d' % total_missing)

main()
