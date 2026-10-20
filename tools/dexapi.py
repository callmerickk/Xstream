import struct, sys, collections, glob, json
def uleb(b, o):
    r=0;s=0
    while True:
        x=b[o];o+=1;r|=(x&0x7f)<<s;s+=7
        if x<0x80: return r,o
def parse(path):
    b=open(path,'rb').read()
    h=lambda off: struct.unpack_from('<I',b,off)[0]
    ssz,soff=h(56),h(60); tsz,toff=h(64),h(68); psz,poff=h(72),h(76); fsz,foff=h(80),h(84); msz,moff=h(88),h(92); csz,coff=h(96),h(100)
    strs=[]
    for i in range(ssz):
        so=h(soff+4*i); _,o=uleb(b,so); e=b.index(b'\0',o); strs.append(b[o:e].decode('utf-8','replace'))
    types=[strs[h(toff+4*i)] for i in range(tsz)]
    protos=[]
    for i in range(psz):
        shorty,ret,par=struct.unpack_from('<III',b,poff+12*i)
        params=[]
        if par:
            n=h(par)
            for j in range(n): params.append(types[struct.unpack_from('<H',b,par+4+2*j)[0]])
        protos.append('('+''.join(params)+')'+types[ret])
    meths=[]
    for i in range(msz):
        c,p,n=struct.unpack_from('<HHI',b,moff+8*i); meths.append((types[c],strs[n],protos[p]))
    fields=[]
    for i in range(fsz):
        c,t,n=struct.unpack_from('<HHI',b,foff+8*i); fields.append((types[c],strs[n],types[t]))
    classes={}
    for i in range(csz):
        base=coff+32*i
        cidx,acc,sup,ifs,src,ann,cdata,sv=struct.unpack_from('<IIIIIIII',b,base)
        cname=types[cidx]; sname=types[sup] if sup!=0xffffffff else None
        ifaces=[]
        if ifs:
            n=h(ifs); ifaces=[types[struct.unpack_from('<H',b,ifs+4+2*j)[0]] for j in range(n)]
        defm=[]
        if cdata:
            o=cdata
            sf,o=uleb(b,o); inf,o=uleb(b,o); dm,o=uleb(b,o); vm,o=uleb(b,o)
            for _ in range(sf+inf): _,o=uleb(b,o); _,o=uleb(b,o)
            idx=0
            for k in range(dm+vm):
                if k==dm: idx=0
                d,o=uleb(b,o); idx+=d; a,o=uleb(b,o); _,o=uleb(b,o)
                defm.append((meths[idx][1],meths[idx][2],a))
        classes[cname]=(sname,ifaces,defm)
    return types,meths,fields,classes
if __name__=='__main__':
    out={'refs':collections.defaultdict(set),'fields':collections.defaultdict(set),'types':set(),'overrides':collections.defaultdict(set),'supers':collections.defaultdict(set)}
    for p in sys.argv[1:]:
        types,meths,fields,classes=parse(p)
        defined=set(classes)
        for t in types:
            if t.startswith(('Lcom/lagradost','[Lcom/lagradost')) and t.lstrip('[') not in defined: out['types'].add(t.lstrip('['))
        for c,n,pr in meths:
            if c not in defined and c.startswith('Lcom/lagradost'): out['refs'][c].add(n+pr)
        for c,n,t in fields:
            if c not in defined and c.startswith('Lcom/lagradost'): out['fields'][c].add(n+':'+t)
        # inherited calls: method refs on plugin classes whose name isn't defined in that class -> resolve to lagradost super
        defset={(c,n+pr) for c,(s,i,dm) in classes.items() for n,pr,a in dm}
        for c,n,pr in meths:
            if c in defined and (c,n+pr) not in defset:
                s=classes[c][0]
                while s in defined: s=classes[s][0]
                if s and s.startswith('Lcom/lagradost'): out['refs'][s].add(n+pr+'  [inherited]')
        for c,(s,ifs,dm) in classes.items():
            chain=[]; x=s
            while x in defined: x=classes[x][0]
            for base in [x]+ifs:
                if base and base.startswith('Lcom/lagradost'):
                    out['supers'][base].add(c)
                    for n,pr,a in dm:
                        if n not in ('<init>','<clinit>') and not (a & 0x8): out['overrides'][base].add(n+pr)
    allt=sorted(out['types']|set(out['refs'])|set(out['fields'])|set(out['overrides']))
    for t in allt:
        print('==',t, '(subclassed by %d)'%len(out['supers'].get(t,())) if t in out['supers'] else '')
        for m in sorted(out['refs'].get(t,())): print('   call ',m)
        for f in sorted(out['fields'].get(t,())): print('   field',f)
        for m in sorted(out['overrides'].get(t,())): print('   over ',m)
