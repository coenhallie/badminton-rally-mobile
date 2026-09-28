"""Reference implementation of automatic badminton court detection.

The Kotlin detector in :analysis (com.badmintontracker.analysis.court) is a port
of this file; design docs/plans/2026-09-27-court-auto-detect-design.md. It is a
development tool: it builds and checks the corpus in ~/shuttl-corpus/court-detect
and is the oracle a divergence in the port is traced against, stage by stage.

    python3 tools/court/court_probe.py FRAME.png [...]   # detect, print, write FRAME_court.png
"""
import cv2, numpy as np, itertools, time
W,L,S,LS=6.1,13.4,1.98,0.76; O=(W-5.18)/2
WL=[0,LS,L/2-S,L/2+S,L-LS,L]
LL=[0,O,W-O,W]
SEGS=[((0,y),(W,y)) for y in WL]+[((x,0),(x,L)) for x in LL]+[((W/2,0),(W/2,L/2-S)),((W/2,L/2+S),(W/2,L))]
NAMED=[(0,0),(W,0),(W,L),(0,L),(0,L/2),(W,L/2),(0,L/2-S),(W,L/2-S),(0,L/2+S),(W,L/2+S),(W/2,L/2-S),(W/2,L/2+S)]
NORM_T=1.2
WORK=960
def samples(step):
    P=[];SID=[]
    for k,(a,b) in enumerate(SEGS):
        a=np.array(a,float);b=np.array(b,float);n=max(2,int(np.linalg.norm(b-a)/step)+1)
        for t in np.linspace(0,1,n): P.append(a+(b-a)*t);SID.append(k)
    return np.array(P),np.array(SID)
MP_C,SID_C=samples(0.5); MP_F,SID_F=samples(0.1)
def proj(H,pts):
    p=np.asarray(pts,float); v=np.c_[p,np.ones(len(p))]@H.T; return v[:,:2]/v[:,2:3]
def ridge(img):
    Y=cv2.cvtColor(img,cv2.COLOR_BGR2GRAY).astype(np.float32)
    h,w=Y.shape; best=np.zeros((h,w),np.float32); ori=np.zeros((h,w),np.float32)
    for s in (1.0,1.8,3.0):
        G=cv2.GaussianBlur(Y,(0,0),s)
        dxx=cv2.Sobel(G,cv2.CV_32F,2,0,ksize=3); dyy=cv2.Sobel(G,cv2.CV_32F,0,2,ksize=3); dxy=cv2.Sobel(G,cv2.CV_32F,1,1,ksize=3)
        tr=dxx+dyy; det=np.sqrt(((dxx-dyy)/2)**2+dxy**2); l1=tr/2+det; l2=tr/2-det
        rb=np.where(l2<0,-l2-np.abs(l1),0)*s*s; rd=np.where(l1>0,l1-np.abs(l2),0)*s*s
        r=np.maximum(rb,rd); theta=0.5*np.arctan2(2*dxy,dxx-dyy)
        th=np.where(rb>=rd,theta+np.pi/2,theta)
        u=r>best; best[u]=r[u]; ori[u]=th[u]
    nx=np.cos(ori); ny=np.sin(ori); xs,ys=np.meshgrid(np.arange(w,dtype=np.float32),np.arange(h,dtype=np.float32))
    a=cv2.remap(best,xs+nx,ys+ny,cv2.INTER_LINEAR); b=cv2.remap(best,xs-nx,ys-ny,cv2.INTER_LINEAR)
    mu=cv2.blur(Y,(41,41)); sd=np.sqrt(np.maximum(cv2.blur(Y*Y,(41,41))-mu*mu,0))
    m=(best>=a)&(best>=b)&(best/(sd+6.0)>NORM_T)&(best>4.0)
    n,lab,st,_=cv2.connectedComponentsWithStats(m.astype(np.uint8),connectivity=8)
    keep=np.hypot(st[:,2],st[:,3])>=0.03*w; keep[0]=False
    return (keep[lab].astype(np.uint8)*255),ori
def hough(mask,ori,nmax=40):
    h,w=mask.shape; ys,xs=np.nonzero(mask); th=ori[ys,xs]%np.pi
    NT=720; D=int(np.hypot(h,w))+1; acc=np.zeros((NT,2*D),np.float32)
    for dt in np.linspace(-2.5,2.5,11):
        ti=(((th+np.radians(dt))%np.pi)/np.pi*NT).astype(int)%NT
        r=(xs*np.cos(ti*np.pi/NT)+ys*np.sin(ti*np.pi/NT)).round().astype(int)+D
        np.add.at(acc,(ti,r),1.0)
    out=[]
    for _ in range(nmax):
        k=np.argmax(acc); ti,ri=np.unravel_index(k,acc.shape); v=acc[ti,ri]
        if v<0.025*max(h,w): break
        for d in range(-8,9): acc[(ti+d)%NT,max(0,ri-8):ri+9]=0
        out.append((float(ri-D),float(ti*np.pi/NT),float(v)))
    return out
def support(mask,l):
    r,t,_=l; ys,xs=np.nonzero(mask); d=np.abs(xs*np.cos(t)+ys*np.sin(t)-r); sel=d<1.5
    if sel.sum()<5: return None
    dv=np.array([-np.sin(t),np.cos(t)]); s=xs[sel]*dv[0]+ys[sel]*dv[1]; base=np.array([np.cos(t),np.sin(t)])*r
    return base+np.percentile(s,2)*dv, base+np.percentile(s,98)*dv
def inter(a,b):
    A=np.array([[np.cos(a[1]),np.sin(a[1])],[np.cos(b[1]),np.sin(b[1])]])
    if abs(np.linalg.det(A))<1e-3: return None
    return np.linalg.solve(A,[a[0],b[0]])
def vp_groups(lines,segs,tol=1.5):
    Ls=[np.array([np.cos(t),np.sin(t),-r]) for r,t,_ in lines]; wts=[v for *_,v in lines]
    def err(vp,i):
        a,b=segs[i]; mid=(a+b)/2; d=(b-a)/max(1e-9,np.linalg.norm(b-a))
        v=vp[:2] if abs(vp[2])<1e-9 else vp[:2]/vp[2]-mid; n=np.linalg.norm(v)
        if n<1e-9: return 0
        return np.degrees(np.arccos(min(1,abs(d@(v/n)))))
    idx=list(range(len(lines))); groups=[]; vps=[]
    for g in range(2):
        best=(0,None,None)
        for i,j in itertools.combinations(idx,2):
            vp=np.cross(Ls[i],Ls[j]); nv=np.linalg.norm(vp)
            if nv<1e-12: continue
            vp/=nv; inl=[k for k in idx if err(vp,k)<tol]; wt=sum(wts[k] for k in inl)
            if wt>best[0]: best=(wt,vp,inl)
        if best[1] is None: break
        groups.append(best[2]); vps.append(best[1]); idx=[k for k in idx if k not in best[2]]
    return groups,vps
def convex(P):
    s=[(P[(i+1)%4]-P[i])[0]*(P[(i+2)%4]-P[(i+1)%4])[1]-(P[(i+1)%4]-P[i])[1]*(P[(i+2)%4]-P[(i+1)%4])[0] for i in range(4)]
    return all(x>0 for x in s) or all(x<0 for x in s)
def batch_H(src,dst):
    N=dst.shape[0]; A=np.zeros((N,8,8)); b=np.zeros((N,8))
    for i in range(4):
        X,Y=src[i]; u=dst[:,i,0]; v=dst[:,i,1]
        A[:,2*i,0]=X;A[:,2*i,1]=Y;A[:,2*i,2]=1;A[:,2*i,6]=-X*u;A[:,2*i,7]=-Y*u;b[:,2*i]=u
        A[:,2*i+1,3]=X;A[:,2*i+1,4]=Y;A[:,2*i+1,5]=1;A[:,2*i+1,6]=-X*v;A[:,2*i+1,7]=-Y*v;b[:,2*i+1]=v
    ok=np.abs(np.linalg.det(A))>1e-9; h=np.zeros((N,8))
    if ok.any(): h[ok]=np.linalg.solve(A[ok],b[ok][...,None])[...,0]
    return np.concatenate([h,np.ones((N,1))],1).reshape(N,3,3),ok
def score(H,dist,MP,SID,tol=1.5):
    h,w=dist.shape; P=np.c_[MP,np.ones(len(MP))]
    q=np.einsum('nij,mj->nmi',H,P); z=q[...,2]; valid=z>1e-6
    x=q[...,0]/np.where(valid,z,1); y=q[...,1]/np.where(valid,z,1)
    dx=np.diff(x,axis=1,append=x[:,-1:]); dy=np.diff(y,axis=1,append=y[:,-1:])
    same=np.append(SID[1:]==SID[:-1],False)[None,:]
    wgt=np.minimum(np.where(same,np.hypot(dx,dy),0),40)
    inside=(x>=0)&(x<w-1)&(y>=0)&(y<h-1)&valid
    d=dist[np.clip(y,0,h-1).astype(int),np.clip(x,0,w-1).astype(int)]
    s=(wgt*((d<=tol)&inside)).sum(1)-0.5*(wgt*((d>tol)&inside)).sum(1)
    s[~valid.all(1)]=-1e9; return s
def gates(H,shape):
    h,w=shape; C=np.array([[0,0,1],[W,0,1],[W,L,1],[0,L,1]],float).T
    q=H@C; z=q[:,2,:]; ok=(z>0).all(1); xy=q[:,:2,:]/np.where(z>0,z,1)[:,None,:]
    area=0.5*np.abs(np.sum(xy[:,0,:]*np.roll(xy[:,1,:],-1,1)-np.roll(xy[:,0,:],-1,1)*xy[:,1,:],1))
    ok&=(area>0.02*w*h)&(area<60*w*h)
    n0=H@np.array([0,L/2,1.0]); n1=H@np.array([W,L/2,1.0])
    ok&=np.hypot(n0[:,0]/n0[:,2]-n1[:,0]/n1[:,2],n0[:,1]/n0[:,2]-n1[:,1]/n1[:,2])>0.08*w
    return ok
def rects():
    R=[]
    for a1,a2 in itertools.combinations(range(6),2):
        for b1,b2 in itertools.combinations(range(4),2):
            x1,x2,y1,y2=LL[b1],LL[b2],WL[a1],WL[a2]
            R+=[np.array(c,float) for c in ([(x1,y1),(x2,y1),(x2,y2),(x1,y2)],[(x1,y1),(x1,y2),(x2,y2),(x2,y1)],
                                            [(x2,y1),(x1,y1),(x1,y2),(x2,y2)],[(x1,y2),(x1,y1),(x2,y1),(x2,y2)])]
    return R
RECTS=rects()
def search(small,topk=9):
    m,ori=ridge(small); dist=cv2.distanceTransform(255-m,cv2.DIST_L2,3)
    lines=hough(m,ori); segs=[support(m,l) for l in lines]
    keep=[i for i,s in enumerate(segs) if s is not None]; lines=[lines[i] for i in keep]; segs=[segs[i] for i in keep]
    groups,vps=vp_groups(lines,segs)
    info=dict(mask=m,lines=lines,segs=segs,groups=groups)
    if len(groups)<2: return None,info
    hz=np.cross(vps[0],vps[1]); h,w=small.shape[:2]
    if np.linalg.norm(hz[:2])>1e-9*max(1,abs(hz[2])):
        hz=hz/np.linalg.norm(hz[:2]); floor=np.sign(hz@np.array([w/2,h,1.0]))
        def below(k):
            a,b=segs[k]; mid=(a+b)/2; return floor*(hz@np.array([mid[0],mid[1],1.0]))>0.01*h
        groups=[[k for k in g if below(k)] for g in groups]; info['horizon']=hz
    A,B=[sorted(g,key=lambda k:-lines[k][2])[:topk] for g in groups]
    h,w=small.shape[:2]; Q=[]
    for i,j in itertools.combinations(A,2):
        for k,l in itertools.combinations(B,2):
            P=[inter(lines[i],lines[k]),inter(lines[i],lines[l]),inter(lines[j],lines[l]),inter(lines[j],lines[k])]
            if any(p is None for p in P): continue
            P=np.array(P)
            if (np.abs(P[:,0]-w/2)>2*w).any() or (np.abs(P[:,1]-h/2)>2*h).any() or not convex(P): continue
            Q.append(P)
    info['quads']=len(Q)
    if not Q: return None,info
    Q=np.array(Q); SS=[];HH=[]
    for R in RECTS:
        H,ok=batch_H(R,Q); ok&=gates(H,(h,w))
        if ok.any(): SS.append(score(H[ok],dist,MP_C,SID_C)); HH.append(H[ok])
    if not SS: return None,info
    S=np.concatenate(SS); HH=np.concatenate(HH); order=np.argsort(-S)[:60]
    s2=score(HH[order],dist,MP_F,SID_F); k=order[np.argmax(s2)]
    info['score']=float(s2.max()); info['dist']=dist
    return HH[k],info
def canon(H):
    F=np.eye(3)
    if proj(H,[(0,0)])[0,0]>proj(H,[(W,0)])[0,0]: F=np.array([[-1,0,W],[0,1,0],[0,0,1]])
    H2=H@F
    if proj(H2,[(0,0)])[0,1]>proj(H2,[(0,L)])[0,1]: F=F@np.array([[1,0,0],[0,-1,L],[0,0,1]])
    H=H@F; return H/H[2,2]
def detect(img,work=WORK):
    sc=work/max(img.shape[:2]); small=cv2.resize(img,(int(round(img.shape[1]*sc)),int(round(img.shape[0]*sc))),interpolation=cv2.INTER_AREA)
    t=time.time(); Hs,info=search(small); info['t_search']=time.time()-t
    if Hs is None: return None,info
    H=canon(np.diag([1/sc,1/sc,1])@Hs); info['Hc']=H
    rr=refine3(img,H); info['t_total']=time.time()-t
    if rr is None or not np.isfinite(rr[0]).all() or not gates(rr[0][None],img.shape[:2])[0]:
        info['refine_failed']=True; return H,info
    info['rms'],info['n'],info['step'],info['iters']=rr[1],rr[2],rr[3],rr[4]; return canon(rr[0]),info
def draw(img,H,thick=2,col=(0,0,255),pts=True):
    out=img.copy()
    for a,b in SEGS:
        pa,pb=proj(H,[a,b])
        if not (np.isfinite(pa).all() and np.isfinite(pb).all()) or np.abs(np.r_[pa,pb]).max()>1e5: continue
        cv2.line(out,tuple(pa.astype(int)),tuple(pb.astype(int)),col,thick,cv2.LINE_AA)
    if pts:
        for q in proj(H,NAMED): cv2.circle(out,tuple(q.astype(int)),7,(0,255,255),1,cv2.LINE_AA)
    return out

LINE_W=0.04
_h=LINE_W/2
# painted line CENTRES (BWF: dimensions to outer edges, lines 40 mm, a line belongs to the area it bounds)
CWL=[_h,LS+_h,L/2-S-_h,L/2+S+_h,L-LS-_h,L-_h]
CLL=[_h,O+_h,W-O-_h,W-_h]
CSEGS=[((0,y),(W,y)) for y in CWL]+[((x,0),(x,L)) for x in CLL]+[((W/2,L/2+S),(W/2,L)),((W/2,0),(W/2,L/2-S))]
def _measure(g,H,samp,tol,pol):
    h,w=g.shape; obs=[]
    for c,u,nm,seg in samp:
        p,pu,pa,pb=proj(H,[c,c+u*0.05,c-nm*LINE_W/2,c+nm*LINE_W/2])
        d=pu-p; nd=np.hypot(*d)
        if not np.isfinite(nd) or nd<1e-6: continue
        d/=nd; nrm=np.array([-d[1],d[0]]); wpx=abs((pb-pa)@nrm); half=1.5*wpx+tol+3
        if not(half+2<p[0]<w-half-2 and half+2<p[1]<h-half-2): continue
        offs=np.arange(-half,half+0.001,0.5); pts=p[None,:]+offs[:,None]*nrm[None,:]
        prof=cv2.remap(g,pts[:,0].reshape(1,-1).astype(np.float32),pts[:,1].reshape(1,-1).astype(np.float32),cv2.INTER_LINEAR)[0].astype(float)
        m=len(prof); edge=max(3,int(0.2*m)); r=prof-np.median(np.r_[prof[:edge],prof[-edge:]])
        cen=np.abs(offs)<=tol+wpx; sgn=pol if pol else (1 if r[cen].max()>=-r[cen].min() else -1); r=sgn*r
        k=int(np.argmax(np.where(cen,r,-1e9))); pk=r[k]
        if pk<8: continue
        hm=pk/2; lo=k
        while lo>0 and r[lo-1]>=hm: lo-=1
        hi=k
        while hi<m-1 and r[hi+1]>=hm: hi+=1
        if lo==0 or hi==m-1: continue
        xl=offs[lo-1]+(hm-r[lo-1])/(r[lo]-r[lo-1])*0.5; xr=offs[hi]+(r[hi]-hm)/(r[hi]-r[hi+1])*0.5
        if xr-xl>2.5*wpx+2 or xr-xl<0.3*wpx: continue
        obs.append((c,seg,p+(xl+xr)/2*nrm,sgn))
    return obs
def _solve(H,obs,iters=10):
    # point-to-projected-line least squares, Huber IRLS, GN
    C=np.array([o[0] for o in obs]); Q=np.array([o[2] for o in obs]); SEGI=[o[1] for o in obs]
    A_=np.array([s[0] for s in SEGI],float); B_=np.array([s[1] for s in SEGI],float)
    hv=(H/H[2,2]).flatten()[:8].copy()
    def resid(hv):
        Hm=np.append(hv,1).reshape(3,3); pa=proj(Hm,A_); pb=proj(Hm,B_); d=pb-pa; n=np.c_[-d[:,1],d[:,0]]/np.hypot(d[:,0],d[:,1])[:,None]
        return ((Q-pa)*n).sum(1)
    for _ in range(iters):
        r=resid(hv); s=1.4826*np.median(np.abs(r))+0.1; wt=1/np.maximum(1,np.abs(r)/(1.5*s))
        J=np.zeros((len(r),8)); eps=1e-6
        for k in range(8):
            e=np.zeros(8); sc=max(abs(hv[k]),1e-3)*eps; e[k]=sc; J[:,k]=(resid(hv+e)-r)/sc
        Jw=J*np.sqrt(wt)[:,None]; rw=r*np.sqrt(wt)
        dx=np.linalg.lstsq(Jw,-rw,rcond=None)[0]; hv=hv+dx
        if np.abs(dx/np.maximum(np.abs(hv),1e-9)).max()<1e-9: break
    r=resid(hv); return np.append(hv,1).reshape(3,3), r, wt
def refine3(img,H,outer=8,tol0=12.0,tol_min=2.5,step=0.05,trace=False):
    g=cv2.cvtColor(img,cv2.COLOR_BGR2GRAY).astype(np.float32); h,w=g.shape
    samp=[]
    for k,(a,b) in enumerate(CSEGS):
        a=np.array(a,float);b=np.array(b,float); u=(b-a)/np.linalg.norm(b-a); nm=np.array([-u[1],u[0]])
        for t in np.linspace(0.02,0.98,int(np.linalg.norm(b-a)/step)): samp.append((a+(b-a)*t,u,nm,(a,b)))
    tol=tol0; pol=None; out=None
    for it in range(outer):
        obs=_measure(g,H,samp,tol,pol)
        if len(obs)<30: return out
        if pol is None: pol=1 if np.mean([o[3] for o in obs])>=0 else -1
        K0=proj(H,NAMED); H,r,wt=_solve(H,obs)
        mv=np.hypot(*(proj(H,NAMED)-K0).T); inimg=(K0[:,0]>=0)&(K0[:,0]<w)&(K0[:,1]>=0)&(K0[:,1]<h)
        stepmax=float(mv[inimg].max()) if inimg.any() else float(mv.max()); rms=float(np.sqrt(np.sum(wt*r*r)/np.sum(wt)))
        if trace: print(f'   outer{it} tol {tol:.1f} n {len(obs)} rms {rms:.2f} step {stepmax:.3f}')
        out=(H,rms,len(obs),stepmax,it)
        if stepmax<0.02 and tol<=tol_min: break
        tol=max(tol_min,tol*0.5)
    return out

def search_near(small,hint_small,radius,topk=9):
    """search(), keeping only hypotheses whose four corners are each within radius of the hint."""
    m,ori=ridge(small); dist=cv2.distanceTransform(255-m,cv2.DIST_L2,3)
    lines=hough(m,ori,nmax=60); segs=[support(m,l) for l in lines]
    keep=[i for i,s in enumerate(segs) if s is not None]; lines=[lines[i] for i in keep]; segs=[segs[i] for i in keep]
    groups,vps=vp_groups(lines,segs)
    if len(groups)<2: return None
    h,w=small.shape[:2]; Q=[]
    A,B=[sorted(g,key=lambda k:-lines[k][2])[:14] for g in groups]
    for i,j in itertools.combinations(A,2):
        for k,l in itertools.combinations(B,2):
            P=[inter(lines[i],lines[k]),inter(lines[i],lines[l]),inter(lines[j],lines[l]),inter(lines[j],lines[k])]
            if any(p is None for p in P): continue
            P=np.array(P)
            if (np.abs(P[:,0]-w/2)>2*w).any() or (np.abs(P[:,1]-h/2)>2*h).any() or not convex(P): continue
            Q.append(P)
    if not Q: return None
    Q=np.array(Q); best=(-1e18,None)
    for R in RECTS:
        H,ok=batch_H(R,Q); ok&=gates(H,(h,w))
        if not ok.any(): continue
        H=H[ok]; C=np.einsum('nij,mj->nmi',H,np.c_[np.array(NAMED[:4]),np.ones(4)]); C=C[...,:2]/C[...,2:3]
        # corners in canonical order may be permuted: compare as sets (min over the 8 dihedral orders)
        D=np.full(len(H),np.inf)
        for perm in ([0,1,2,3],[1,0,3,2],[3,2,1,0],[2,3,0,1],[0,3,2,1],[3,0,1,2],[1,2,3,0],[2,1,0,3]):
            D=np.minimum(D,np.hypot(*(C[:,perm,:]-hint_small[None]).transpose(2,0,1)).max(1))
        near=D<=radius
        if not near.any(): continue
        s=score(H[near],dist,MP_F,SID_F); k=np.argmax(s)
        if s[k]>best[0]: best=(s[k],H[near][k])
    return best[1]
def assist(img,taps,work=WORK):
    sc=work/max(img.shape[:2]); small=cv2.resize(img,(int(round(img.shape[1]*sc)),int(round(img.shape[0]*sc))),interpolation=cv2.INTER_AREA)
    Hs=search_near(small,np.asarray(taps)*sc,radius=0.05*work)
    if Hs is not None: H0=canon(np.diag([1/sc,1/sc,1])@Hs); tol0=12.0
    else:
        H0=cv2.getPerspectiveTransform(np.array(NAMED[:4],np.float32),np.asarray(taps,np.float32)); tol0=30.0
    rr=refine3(img,H0,tol0=tol0)
    return None if rr is None else (canon(rr[0]),rr)

def accept(img,H,info):
    """The acceptance rule of design section 4.6. Returns (ok, reason)."""
    if H is None: return False,'no hypothesis'
    if info.get('refine_failed') or 'rms' not in info: return False,'refinement failed'
    hh,ww=img.shape[:2]; scale=1920.0/max(hh,ww)
    if info['step']>0.1: return False,'not converged'
    if info['rms']*scale>1.5: return False,'residual %.2f px'%(info['rms']*scale)
    K=proj(H,NAMED)
    if not ((K[:,0]>=0)&(K[:,0]<ww)&(K[:,1]>=0)&(K[:,1]<hh)).all(): return False,'keypoint outside the frame'
    gw,gl=line_support(img,H)
    if gw<2 or gl<2: return False,'line support %d/%d'%(gw,gl)
    return True,'ok'
def line_support(img,H):
    sc=WORK/max(img.shape[:2]); small=cv2.resize(img,(int(round(img.shape[1]*sc)),int(round(img.shape[0]*sc))),interpolation=cv2.INTER_AREA)
    m,_=ridge(small); dist=cv2.distanceTransform(255-m,cv2.DIST_L2,3); h,w=dist.shape; Hs=np.diag([sc,sc,1])@H
    gw=gl=0
    for k,(a,b) in enumerate(SEGS):
        C=np.array(a)+np.outer(np.linspace(0,1,200),np.array(b)-np.array(a)); P=proj(Hs,C)
        seg=np.hypot(*np.diff(P,axis=0).T); ins=(P[:-1,0]>=0)&(P[:-1,0]<w-1)&(P[:-1,1]>=0)&(P[:-1,1]<h-1); vis=(seg*ins).sum()
        if vis<0.05*max(h,w): continue
        d=dist[np.clip(P[:-1,1],0,h-1).astype(int),np.clip(P[:-1,0],0,w-1).astype(int)]
        if (seg*ins*(d<=1.5)).sum()/vis>=0.4:
            if k<6: gw+=1
            else: gl+=1
    return gw,gl
if __name__=='__main__':
    import sys
    for f in sys.argv[1:]:
        img=cv2.imread(f); t=time.time(); H,info=detect(img); ok,why=accept(img,H,info)
        print(f"{f}: {'court' if ok else 'no court'} ({why}) in {time.time()-t:.2f}s")
        if ok:
            for n,p in zip(['top_left','top_right','bottom_right','bottom_left','net_left','net_right','service_line_near_left','service_line_near_right','service_line_far_left','service_line_far_right','center_near','center_far'],proj(H,NAMED)):
                print(f"  {n:24s} {p[0]:8.2f} {p[1]:8.2f}")
            cv2.imwrite(f.rsplit('.',1)[0]+'_court.png',draw(img,H,2))
