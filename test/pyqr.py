import sys
from itertools import product
from qrcode import base
def expected_matrix(code_bytes, v, size, mask, aligns):
    raw=base.RS_BLOCK_TABLE[(v-1)*4+1]; groups=[tuple(raw[i:i+3]) for i in range(0,len(raw),3)]
    dcnt=sum(c*d for c,t,d in groups)
    bits=[]
    def put(val,n):
        for i in range(n-1,-1,-1): bits.append((val>>i)&1)
    put(4,4); put(len(code_bytes), 8 if v<=9 else 16)
    for c in code_bytes: put(c,8)
    rem=dcnt*8-len(bits); put(0,min(4,max(0,rem)))
    while len(bits)%8: bits.append(0)
    data=[int(''.join(map(str,bits[i:i+8])),2) for i in range(0,len(bits),8)]
    pad=[0xEC,0x11]; i=0
    while len(data)<dcnt: data.append(pad[i&1]); i+=1
    EXP=[0]*512; LOG=[0]*256; x=1
    for i in range(255):
        EXP[i]=x; LOG[x]=i; x<<=1
        if x&0x100: x^=0x11D
    for i in range(255,512): EXP[i]=EXP[i-255]
    def genpoly(deg):
        g=[1]
        for i in range(deg):
            ng=[0]*(len(g)+1)
            for j,val in enumerate(g):
                ng[j]^=val
                ng[j+1]^= EXP[LOG[val]+i] if val else 0
            g=ng
        return g
    def ecblk(blk,nec):
        g=genpoly(nec); res=[0]*nec
        for b in blk:
            f=b^res[0]; res=res[1:]+[0]
            for k in range(nec): res[k]^= EXP[LOG[g[k+1]]+LOG[f]] if f and g[k+1] else 0
        return res
    db=[]; pos=0; eb=[]
    for c,t,d in groups:
        for k in range(c):
            b=data[pos:pos+d]; pos+=d; db.append(b); eb.append(ecblk(b,t-d))
    inter=[]; nblk=len(db)
    for i in range(max(len(b) for b in db)):
        for b in range(nblk):
            if i<len(db[b]): inter.append(db[b][i])
    for i in range(len(eb[0])):
        for b in range(nblk): inter.append(eb[b][i])
    mat=[[0]*size for _ in range(size)]; resv=[[False]*size for _ in range(size)]
    for oy,ox in ((0,0),(0,size-7),(size-7,0)):
        for dy in range(-1,8):
            for dx in range(-1,8):
                y,x=oy+dy,ox+dx
                if 0<=y<size and 0<=x<size:
                    a=max(abs(dy-3),abs(dx-3))
                    mat[y][x]=1 if (a<=1 or a==3) else 0; resv[y][x]=True
    for i in range(8,size-8):
        mat[6][i]=mat[i][6]= 1 if i%2==0 else 0; resv[6][i]=resv[i][6]=True
    mat[size-8][8]=1; resv[size-8][8]=True
    for y0,x0 in product(aligns,aligns):
        if (y0<=8 and x0<=8) or (y0<=8 and x0>=size-9) or (y0>=size-8 and x0<=8): continue
        for dy in range(-2,3):
            for dx in range(-2,3):
                a=max(abs(dy),abs(dx))
                mat[y0+dy][x0+dx]=1 if a!=1 else 0; resv[y0+dy][x0+dx]=True
    for i in range(9): resv[8][i]=True; resv[i][8]=True
    for i in range(8): resv[8][size-1-i]=True; resv[size-8+i][8]=True
    if v>=7:
        for i in range(6):
            for j in range(3): resv[size-11+j][i]=True; resv[i][size-11+j]=True
    allbits=[]
    for cw in inter:
        for i in range(7,-1,-1): allbits.append((cw>>i)&1)
    bi=0; upward=True; col=size-1
    while col>0:
        if col==6: col-=1
        for vert in range(size):
            for j in range(2):
                x=col-j; y=(size-1-vert) if upward else vert
                if not resv[y][x]:
                    mat[y][x]=allbits[bi] if bi<len(allbits) else 0
                    bi+=1
        col-=2; upward=not upward
    def fmt_bits(ecl,m):
        d=(ecl<<3)|m; rem=d
        for _ in range(10): rem=(rem<<1)^(0x537 if rem>>9 else 0)
        return ((d<<10)|rem)^0x5412
    f=fmt_bits(0,mask)
    def gb(val,i): return (val>>i)&1
    for i in range(0,6): mat[i][8]=gb(f,i)
    mat[7][8]=gb(f,6); mat[8][8]=gb(f,7); mat[8][7]=gb(f,8)
    for i in range(9,15): mat[8][14-i]=gb(f,i)
    for i in range(8): mat[8][size-1-i]=gb(f,i)
    for i in range(8,15): mat[size-15+i][8]=gb(f,i)
    if v>=7:
        rem=v
        for _ in range(12): rem=(rem<<1)^(0x1F25 if rem>>11 else 0)
        vb=(v<<12)|rem
        for i in range(18):
            b=gb(vb,i)
            mat[size-11+i//3][i%3]=b; mat[i%3][size-11+i//3]=b
    for y in range(size):
        for x in range(size):
            if not resv[y][x] and maskfn(mask,x,y): mat[y][x]^=1
    return [''.join(map(str,r)) for r in mat], inter
def maskfn(m,x,y):
    return [ (x+y)%2==0, y%2==0, x%3==0, (x+y)%3==0, (y//2+x//3)%2==0,
             (x*y)%2+(x*y)%3==0, ((x*y)%2+(x*y)%3)%2==0, ((x+y)%2+(x*y)%3)%2==0 ][m]
if __name__=='__main__':
    lines=open('cases.txt').read().splitlines()
    idx=int(sys.argv[1]); mask=int(sys.argv[2]); v=int(sys.argv[3]); size=4*v+17
    ALIGN={7:[6,22,38]}
    from qrcode.util import pattern_position
    ap=list(pattern_position(v)) if v>1 else []
    mm,_=expected_matrix(lines[idx].encode(), v, size, mask, ap)
    open('calc.txt','w').write('\n'.join(mm)+'\n')
    print('calc written', size, 'aligns', ap)
