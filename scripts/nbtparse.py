#!/usr/bin/env python3
"""Standalone NBT reader/writer sufficient for schematic <-> anvil work. No deps."""
import gzip, struct, io, sys

class R:
    def __init__(self, b): self.b=b; self.i=0
    def u1(self): v=self.b[self.i]; self.i+=1; return v
    def s(self):
        n=struct.unpack_from('>h',self.b,self.i)[0]; self.i+=2
        v=self.b[self.i:self.i+n]; self.i+=n; return v.decode('utf8','replace')
    def take(self,n):
        v=self.b[self.i:self.i+n]; self.i+=n; return v
def _val(r,t):
    if t==0: return None
    if t==1: r.u1()
    elif t==2: r.take(2)
    if t==1: return r.u1()
    if t==2: return struct.unpack('>h',r.take(2))[0]
    if t==3: return struct.unpack('>i',r.take(4))[0]
    if t==4: return struct.unpack('>q',r.take(8))[0]
    if t==5: return struct.unpack('>f',r.take(4))[0]
    if t==6: return struct.unpack('>d',r.take(8))[0]
    if t==7:
        n=struct.unpack('>i',r.take(4))[0]; return bytearray(r.take(n))
    if t==8: return r.s()
    if t==9:
        ct=r.u1(); n=struct.unpack('>i',r.take(4))[0]
        out=[]
        for _ in range(n):
            if ct==10: out.append(_compound(r))
            elif ct==8: out.append(r.s())
            else: out.append(_val(r,ct)) if ct!=0 else None
        return out
    if t==10: return _compound(r)
    if t==11:
        n=struct.unpack('>i',r.take(4))[0]; return list(struct.unpack('>%di'%n,r.take(4*n)))
    if t==12:
        n=struct.unpack('>i',r.take(4))[0]; return list(struct.unpack('>%dq'%n,r.take(8*n)))
    raise ValueError('tag %d'%t)
def _compound(r):
    d={}
    while True:
        t=r.u1()
        if t==0: return d
        name=r.s()
        d[name]=_val(r,t)
def _wrap(b):
    r=R(b); t=r.u1(); assert t==10,'root not compound: %d'%t; r.s()
    return _compound(r)

def loads(data_or_path):
    if isinstance(data_or_path,(bytes,bytearray)):
        if data_or_path[:2]==b'\x1f\x8b': return _wrap(gzip.decompress(bytes(data_or_path)))
        return _wrap(bytes(data_or_path))
    with open(data_or_path,'rb') as f: d=f.read()
    if d[:2]==b'\x1f\x8b': d=gzip.decompress(d)
    return _wrap(d)

# --- minimal writer (uncompressed payloads only) ---
def _w(b,fmt,*v): b.extend(struct.pack(fmt,*v))
def _str(s):
    s=s.encode('utf8'); return struct.pack('>h',len(s))+s
def _build(t,v,out):
    fmt={1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}
    if t in fmt: _w(out,fmt[t],v)
    elif t==7:
        _w(out,'>i',len(v)); out.extend(v)
    elif t==8: out.extend(_str(v))
    elif t==9:
        out.append(10 if (v and isinstance(v[0],dict)) else 8)
        _w(out,'>i',len(v))
        for e in v:
            if isinstance(e,dict): _build_compound(e,out)
            else: out.extend(_str(str(e)))
    elif t==10: _build_compound(v,out)
    elif t==11:
        _w(out,'>i',len(v))
        for x in v: _w(out,'>i',x)
    elif t==12:
        _w(out,'>i',len(v))
        for x in v: _w(out,'>q',x)
def _build_compound(d,out):
    for k,v in d.items():
        if isinstance(v,dict): t=10
        elif isinstance(v,str): t=8
        elif isinstance(v,(list,tuple)):
            t=9 if v and isinstance(v[0],(int,float))and max(abs(x) for x in v)>0xFFFFFFFF else (9 if not isinstance(v,int)else 11)
        else: t=None
        # simpler: infer
        if isinstance(v,bool): t=1
        if t==9 and all(isinstance(x,(dict,))for x in v): pass
        if not isinstance(v,list):
            if isinstance(v,dict): t=10
            elif isinstance(v,str): t=8
            elif isinstance(v,int):
                t=3 if -2147483648<=v<2147483648 else 4
            elif isinstance(v,float): t=6
            elif isinstance(v,(bytes,bytearray)): t=7
            else: raise ValueError(k)
        elif all(isinstance(x,(dict,)) for x in v): t=9
        elif all(isinstance(x,str) for x in v): t=9
        elif all(isinstance(x,int) for x in v) and len(v)>0 and abs(v[0])>=2147483648: t=12
        elif all(isinstance(x,int) for x in v): t=11
        elif all(isinstance(x,float) for x in v): t=9 if False else 9 # unsupported list-of-double -> skip
        else: raise ValueError(k)
        if t in (9,) and v and all(isinstance(x,float) for x in v): continue
        out.append(t); out.extend(_str(k)); _build(t,v,out)
    out.append(0)
def to_nbt(root_d):
    out=bytearray()
    out.append(10); out.extend(_str(''))
    _build_compound(root_d,out)
    return bytes(out)
def dumps_zlib(raw): return zlib_compress(raw)
def zlib_compress(b):
    import zlib as z; return z.compress(b,9)

if __name__=='__main__':
    s=loads(sys.argv[1])
    for k in ('Width','Height','Length','WEOffsetX','WEOffsetY','WEOffsetZ','WEOriginX','WEOriginY','WEOriginZ'):
        print(k,s.get(k))
    p=s.get('Palette'); blocks=s.get('Blocks')
    print('has Palette',p is not None,'has Blocks',blocks is not None)
