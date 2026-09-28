"""Synthetic courts with exact truth: the scenes of design section 8.1, run through the reference implementation.

    python3 tools/court/synth.py
"""
import cv2,numpy as np,sys,os
sys.path.insert(0,os.path.dirname(__file__))
import court_probe as court
rng=np.random.default_rng(7)
def render(H,w=1920,h=1080,floor=(60,140,70),line=(235,235,235),ss=3,noise=4.0,blur=0.8,distract=True,occl=True):
    # draw in court metres onto a supersampled image through H (court->px)
    big=np.zeros((h*ss,w*ss,3),np.uint8); big[:]=floor
    Hs=np.array([[ss,0,(ss-1)/2],[0,ss,(ss-1)/2],[0,0,1.0]])@H
    ys,xs=np.mgrid[0:h*ss,0:w*ss].astype(np.float32)
    Hi=np.linalg.inv(Hs); v=np.stack([xs,ys,np.ones_like(xs)],-1)@Hi.T; X=v[...,0]/v[...,2]; Y=v[...,1]/v[...,2]
    front=v[...,2]>0
    Wc,Lc,S,LS,O=court.W,court.L,court.S,court.LS,court.O; lw=0.04
    m=np.zeros(X.shape,bool)
    inx=(X>=0)&(X<=Wc); iny=(Y>=0)&(Y<=Lc)
    for y0 in [0,LS,Lc/2-S-lw,Lc/2+S,Lc-LS-lw,Lc-lw]: m|=inx&(Y>=y0)&(Y<=y0+lw)
    for x0 in [0,O,Wc-O-lw,Wc-lw]: m|=iny&(X>=x0)&(X<=x0+lw)
    m|=(np.abs(X-Wc/2)<=lw/2)&(((Y>=0)&(Y<=Lc/2-S))|((Y>=Lc/2+S)&(Y<=Lc)))
    if distract:
        # a basketball-ish line and an adjacent court's sideline, in another colour
        dm=(np.abs(np.hypot(X-Wc/2,Y-Lc/2)-3.6)<0.03)|(np.abs(X-(Wc+1.8))<0.025)
        big[dm&front]=(40,40,190)
    big[m&front]=line
    img=cv2.resize(big,(w,h),interpolation=cv2.INTER_AREA)
    if occl:
        for _ in range(2):
            c=court.proj(H,[(rng.uniform(1,5),rng.uniform(2,12))])[0]
            cv2.ellipse(img,(int(c[0]),int(c[1]-60)),(25,70),0,0,360,(30,30,160),-1)
    img=cv2.GaussianBlur(img,(0,0),blur)
    img=np.clip(img+rng.normal(0,noise,img.shape),0,255).astype(np.uint8)
    return img
def camera(yaw=0.0,height=4.5,back=6.0,side=0.0,f=1500,w=1920,h=1080,tilt=None):
    # pinhole behind the near baseline looking at court centre
    C=np.array([court.W/2+side,court.L+back,height]); T=np.array([court.W/2,court.L/2,0.0])
    z=T-C; z/=np.linalg.norm(z); up=np.array([0,0,1.0]); x=np.cross(z,up); x/=np.linalg.norm(x); y=np.cross(z,x)
    R=np.stack([x,y,z]); K=np.array([[f,0,w/2],[0,f,h/2],[0,0,1]])
    P=K@np.c_[R,-R@C]; H=P[:,[0,1,3]]; return H/H[2,2]
cases=[dict(),dict(side=1.5,height=3.0,back=4.0),dict(side=-2.0,height=6.0,back=8.0,f=1300),dict(height=2.2,back=3.0,f=1100),dict(side=3.0,height=3.5,back=5.0)]
colours=[((60,140,70),(235,235,235)),((120,170,210),(240,240,240)),((70,110,200),(40,200,230)),((150,150,150),(250,250,250))]
errs=[]
for i,cs in enumerate(cases):
    for j,(fl,li) in enumerate(colours):
        H=camera(**cs); img=render(H,floor=fl,line=li)
        Hd,info=court.detect(img)
        truth=court.proj(court.canon(H),court.NAMED)
        if Hd is None or 'refine_failed' in info: print(i,j,'FAILED'); continue
        K=court.proj(Hd,court.NAMED); e=np.hypot(*(K-truth).T)
        inimg=(truth[:,0]>=0)&(truth[:,0]<1920)&(truth[:,1]>=0)&(truth[:,1]<1080)
        errs+=list(e[inimg]); print(i,j,f"in-frame keypoints {inimg.sum()} err median {np.median(e[inimg]):.2f} max {e[inimg].max():.2f} px rms {info['rms']:.2f} iters {info['iters']}")

print('ALL median %.2f p95 %.2f max %.2f px'%(np.median(errs),np.percentile(errs,95),np.max(errs)))
