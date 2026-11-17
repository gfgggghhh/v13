import cv2, numpy as np, os, shutil
bases = ['skin00','skin03','skin06']
NF = 32
def illum_map(h,w,seed):
    r=np.random.default_rng(seed); yy,xx=np.mgrid[0:h,0:w].astype(np.float32)
    il = 0.7+0.45*((xx/w)*0.7+(yy/h)*0.3)
    d=((xx-0.3*w)+0.3*(yy-0.5*h))/(0.08*w); il*=1-0.25*np.exp(-d**2)
    return il
def traj(kind, rng):
    T=[]; tx=ty=rot=0.0
    for i in range(NF):
        if kind=='still': tx=ty=rot=0
        elif kind=='tremor':
            tx=0.7*tx+rng.normal(0,1.2); ty=0.7*ty+rng.normal(0,1.2); rot=0.7*rot+rng.normal(0,0.15)
        elif kind=='drift': tx=1.2*i; ty=0.6*i; rot=0
        elif kind=='jerk': tx=0 if i<12 else (40 if i<22 else 5); ty=0 if i<12 else (15 if i<22 else 0); rot=0
        elif kind=='shake': tx=16*np.sin(2*np.pi*i/7); ty=10*np.sin(2*np.pi*i/5); rot=0.4*np.sin(2*np.pi*i/6)
        T.append((tx,ty,rot))
    return T
for b in bases:
    base = cv2.imread(f'real_in/{b}__clean.png',0).astype(np.float32)
    thin=np.load(f'real_gt/{b}__thin.npy').astype(np.uint8); thick=np.load(f'real_gt/{b}__thick.npy').astype(np.uint8)
    gt=(thin|thick)
    h,w=base.shape; il=illum_map(h,w,1)
    for kind in ['still','tremor','drift','jerk','shake']:
        rng=np.random.default_rng(hash((b,kind))%1000); name=f'{b}_{kind}'
        d=f'seq_in/{name}'; shutil.rmtree(d,ignore_errors=True); os.makedirs(d); os.makedirs('seq_gt',exist_ok=True)
        gts=[]
        for i,(tx,ty,rot) in enumerate(traj(kind,rng)):
            M=cv2.getRotationMatrix2D((w/2,h/2),rot,1.0); M[0,2]+=tx; M[1,2]+=ty
            fr=cv2.warpAffine(base,M,(w,h),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_REFLECT)
            g =cv2.warpAffine(gt,M,(w,h),flags=cv2.INTER_NEAREST,borderMode=cv2.BORDER_REFLECT)
            f=np.clip(fr*il,0,255)
            f=f+rng.normal(0,1,f.shape)*np.sqrt(np.maximum(f,1))*0.35+rng.normal(0,8,f.shape)
            cv2.imwrite(f'{d}/f{i:03d}.png',np.clip(np.round(f),0,255).astype(np.uint8)); gts.append(g.astype(bool))
        np.save(f'seq_gt/{name}.npy',np.stack(gts))
print('done')
