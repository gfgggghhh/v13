import cv2, numpy as np, os
def make(seed, noise=6.0, size=(640,480), edge=True, glare=True):
    r = np.random.default_rng(seed); w,h = size
    yy,xx = np.mgrid[0:h,0:w].astype(np.float32)
    # skin base: low-frequency tissue variation + fine skin texture
    base = 150 + 12*cv2.GaussianBlur(r.normal(0,1,(h,w)).astype(np.float32),(0,0),40)*8
    base += 3.0*cv2.GaussianBlur(r.normal(0,1,(h,w)).astype(np.float32),(0,0),1.2)*3
    # veins: random smooth curves, widths 1.5..7 px, depth 5..14 %
    vein = np.zeros((h,w),np.float32); gt = np.zeros((h,w),np.uint8); widths = np.zeros((h,w),np.float32)
    for _ in range(9):
        p0 = np.array([r.uniform(0,w), r.uniform(0,h)]); ang = r.uniform(0,2*np.pi)
        wd = r.choice([1.5,2.0,2.5,3.5,5.0,7.0]); depth = r.uniform(0.06,0.14)*(0.6 if wd<3 else 1.0)
        pts=[p0]
        for i in range(40):
            ang += r.normal(0,0.12); pts.append(pts[-1]+8*np.array([np.cos(ang),np.sin(ang)]))
        pts = np.array(pts,np.float32).reshape(-1,1,2)
        layer = np.zeros((h,w),np.float32); cv2.polylines(layer,[pts.astype(np.int32)],False,1.0,thickness=max(1,int(round(wd))),lineType=cv2.LINE_AA)
        layer = cv2.GaussianBlur(layer,(0,0),wd*0.35+0.3)
        layer = layer/ (layer.max()+1e-6)
        vein = np.maximum(vein, layer*depth)
        m = (layer>0.5); gt[m]=1; widths[m]=np.maximum(widths[m],wd)
    img = base*(1-vein)
    # illumination: gradient + broad shadow + HARD shadow edge (false-line generator) + glare
    illum = 0.7 + 0.5*(xx/w)
    edge_mask = np.zeros((h,w),np.float32)
    if edge:
        ang = r.uniform(0.3,1.2); d = (xx-0.45*w)*np.cos(ang)+(yy-0.5*h)*np.sin(ang)
        sh = 1/(1+np.exp(d/2.5)); illum *= 1-0.30*sh                     # 30% darker on one side, edge softness ~2.5px
        edge_mask = (np.abs(d)<10).astype(np.uint8)
    if glare:
        gx,gy=r.uniform(0.2,0.8)*w, r.uniform(0.2,0.8)*h
        img = img + 70*np.exp(-(((xx-gx)**2+(yy-gy)**2)/(2*28.0**2)))
    img = img*illum
    img = np.clip(img,0,255)
    noisy = img + r.normal(0,1,img.shape)*np.sqrt(np.maximum(img,1))*0.35 + r.normal(0,noise,img.shape)
    return np.clip(np.round(noisy),0,255).astype(np.uint8), gt.astype(bool), widths, edge_mask.astype(bool), np.clip(img,0,255).astype(np.uint8)
if __name__=='__main__':
    os.makedirs('ph_in',exist_ok=True); os.makedirs('ph_gt',exist_ok=True)
    for s in range(12):
        for nm,ns in [('n4',4.0),('n10',10.0)]:
            im,gt,wd,em,clean = make(s,ns)
            cv2.imwrite(f'ph_in/p{s:02d}__{nm}.png',im)
            if nm=='n4':
                cv2.imwrite(f'ph_in/p{s:02d}__clean.png',clean)
                np.save(f'ph_gt/p{s:02d}__gt.npy',gt); np.save(f'ph_gt/p{s:02d}__w.npy',wd); np.save(f'ph_gt/p{s:02d}__edge.npy',em)
    print('ok')
