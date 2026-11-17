import cv2, numpy as np, glob, os, json
from skimage.filters import frangi
rng = np.random.default_rng(7)
SRC = '/home/claude/dhv'
picks = ['DorsalHandVeins_DB1_png/person_001_db1_L1.png','DorsalHandVeins_DB1_png/person_010_db1_R2.png',
 'DorsalHandVeins_DB1_png/person_020_db1_L3.png','DorsalHandVeins_DB1_png/person_030_db1_R1.png','DorsalHandVeins_DB1_png/person_040_db1_L2.png',
 'DorsalHandVeins_DB2_png/person_005_db2_R2.png','DorsalHandVeins_DB2_png/person_025_db2_L1.png','DorsalHandVeins_DB2_png/person_050_db2_R2.png',
 'DorsalHandVeins_DB2_png/person_070_db2_L2.png','DorsalHandVeins_DB2_png/person_100_db2_R1.png']
def crop_skin(g):
    m = (cv2.GaussianBlur(g,(0,0),5) > 60).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(m)
    k = 1 + np.argmax(st[1:,4]); m = (lab==k).astype(np.uint8)
    # largest axis-aligned 4:3 box fully inside mask (search by shrinking)
    ys,xs = np.where(m); cy,cx = int(np.median(ys)), int(np.median(xs))
    for w in range(420, 120, -10):
        h = int(w*3/4); x0=cx-w//2; y0=cy-h//2
        if x0<0 or y0<0 or x0+w>g.shape[1] or y0+h>g.shape[0]: continue
        if m[y0:y0+h, x0:x0+w].mean() > 0.97: return g[y0:y0+h, x0:x0+w]
    return g[cy-120:cy+120, cx-160:cx+160]
def crop_full(g):
    ys,xs = np.where(cv2.GaussianBlur(g,(0,0),5)>60); cx=int(np.median(xs)); 
    x0=int(np.clip(cx-280,0,g.shape[1]-560)); return g[0:420, x0:x0+560]
def degrade(img, noise_sigma, seed, shadow=True, glare=True, illum_on=True, shot_on=True):
    r = np.random.default_rng(seed); h,w = img.shape
    f = img.astype(np.float32)/255.
    yy,xx = np.mgrid[0:h,0:w].astype(np.float32)
    illum = 0.55 + 0.6*((xx/w)*0.7+(yy/h)*0.3)                         # strong gradient
    if shadow:                                                          # broad soft shadow band (NOT a vein)
        d = ((xx - 0.25*w) + 0.35*(yy - 0.5*h))/ (0.06*w)
        illum *= 1 - 0.35*np.exp(-d**2)
    f = f*illum if illum_on else f
    if not illum_on: shadow=glare=False
    if glare and illum_on:
        gx,gy = r.uniform(0.55,0.8)*w, r.uniform(0.2,0.5)*h
        f += 0.55*np.exp(-(((xx-gx)**2+(yy-gy)**2)/(2*(0.05*w)**2)))
    f = np.clip(f,0,1)
    peak = 255.
    shot = (r.normal(0,1,f.shape)*np.sqrt(np.maximum(f,1e-3)*peak)*0.35) if shot_on else 0   # signal dependent
    read = r.normal(0,noise_sigma,f.shape)
    out = f*peak + shot + read
    return np.clip(np.round(out),0,255).astype(np.uint8)
meta = {}
for kind, cropf in [('skin',crop_skin),('full',crop_full)]:
    for pi,p in enumerate(picks):
        g = cv2.imread(os.path.join(SRC,p),0)
        c = cropf(g); c = cv2.resize(c,(640,480),interpolation=cv2.INTER_CUBIC)
        name = f'{kind}{pi:02d}'
        base = f'real_in/{name}'; os.makedirs('real_in',exist_ok=True); os.makedirs('real_gt',exist_ok=True)
        cv2.imwrite(f'real_in/{name}__clean.png', c)
        # pseudo-GT: Frangi on the clean (log) image, independent of our pipeline
        hand = (cv2.GaussianBlur(c,(0,0),4)>50)
        L = np.log(c.astype(np.float32)+8); L = L - cv2.GaussianBlur(L,(0,0),25)
        thin = frangi(cv2.GaussianBlur(L,(0,0),1.0), sigmas=[2.0,3.0], black_ridges=True, beta=0.5, gamma=None)
        thick= frangi(cv2.GaussianBlur(L,(0,0),1.0), sigmas=[4.0,5.0], black_ridges=True, beta=0.5, gamma=None)
        hm = cv2.erode(hand.astype(np.uint8), np.ones((15,15),np.uint8)).astype(bool)
        def topmask(r, frac):
            v = r[hm]; t = np.quantile(v, 1-frac); m = (r>=t)&hm
            m = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_OPEN, np.ones((2,2),np.uint8))
            n,lab,st,_ = cv2.connectedComponentsWithStats(m)
            keep = np.zeros_like(m)
            for k in range(1,n):
                if st[k,4]>=60: keep[lab==k]=1
            return keep.astype(bool)
        gt_thin = topmask(thin, 0.04); gt_thick = topmask(thick, 0.06)
        np.save(f'real_gt/{name}__thin.npy', gt_thin); np.save(f'real_gt/{name}__thick.npy', gt_thick); np.save(f'real_gt/{name}__hand.npy', hm)
        for ns in (3,8):
            cv2.imwrite(f'real_in/{name}__n{ns}.png', degrade(c, ns, seed=pi*10+ns))
        cv2.imwrite(f'real_in/{name}__ill.png', degrade(c, 0, seed=pi, shot_on=False))
        cv2.imwrite(f'real_in/{name}__nz8.png', degrade(c, 8, seed=pi*10+8, illum_on=False))
print(len(os.listdir('real_in')))
