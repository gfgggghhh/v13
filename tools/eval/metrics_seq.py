import cv2, numpy as np, glob, os, sys, collections, csv
from sklearn.metrics import roc_auc_score
out=sys.argv[1]; variants=sys.argv[2].split(','); LAG=4
res=collections.defaultdict(lambda: collections.defaultdict(list))
def shift_of(a,b):
    (dx,dy),_=cv2.phaseCorrelate(a.astype(np.float32),b.astype(np.float32)); return dx,dy
for gtf in sorted(glob.glob('seq_gt/*.npy')):
    if not os.path.isdir(f'{out}/'+os.path.basename(gtf)[:-4]): continue
    name=os.path.basename(gtf)[:-4]; kind=name.split('_')[1]; gts=np.load(gtf); nf=len(gts)
    for v in variants:
        fr=[cv2.imread(f'{out}/{name}/{v}/f{i:03d}.png',0).astype(np.float32) for i in range(nf)]
        D=[cv2.GaussianBlur(255-f,(0,0),1.0) for f in fr]
        R=res[(kind,v)]
        for i in range(6,nf):
            g=gts[i]; m=np.zeros_like(g); m[30:-30,30:-30]=True
            if g.sum()>200: R['auc'].append(roc_auc_score(g[m],D[i][m]))
            gp=gts[i-LAG]; near=cv2.dilate(g.astype(np.uint8),np.ones((15,15),np.uint8)).astype(bool)
            gz=gp&~near&m; bg=m&~near&~cv2.dilate(gp.astype(np.uint8),np.ones((15,15),np.uint8)).astype(bool)
            if gz.sum()>80 and g.sum()>200 and bg.sum()>1000:
                vc=D[i][g&m].mean()-D[i][bg].mean()
                R['ghost'].append((D[i][gz].mean()-D[i][bg].mean())/(abs(vc)+1e-6))
        if kind=='still':
            st=np.stack(fr[6:]); g=gts[0]; sk=~cv2.dilate(g.astype(np.uint8),np.ones((9,9),np.uint8)).astype(bool); sk[:30]=sk[-30:]=False; sk[:,:30]=sk[:,-30:]=False
            R['tnoise'].append(st.std(axis=0)[sk].mean())
        if kind in('tremor','shake'):
            sh=[shift_of(fr[i-1],fr[i]) for i in range(6,nf)]
            R['jit'].append(np.sqrt(np.mean([dx*dx+dy*dy for dx,dy in sh])))
    for v in variants:
        if v.startswith('stab'):
            p=f'{out}/{name}/stab_log.csv'
            for row in csv.DictReader(open(p)):
                if row['variant'].replace(':','_').replace('@','_')==v and int(row['frame'])>=6: res[(kind,v)]['rej'].append(row['status']=='REJECTED')
kinds=['still','tremor','drift','jerk','shake']
print(f"{'motion':7s} {'variant':16s} {'AUC':>6s} {'ghost':>7s} {'tNoise':>7s} {'jitter':>7s} {'rejected':>9s}")
for k in kinds:
    for v in variants:
        R=res[(k,v)]; f=lambda x: (f"{np.mean(R[x]):.3f}" if R[x] else '   -  ')
        print(f"{k:7s} {v:16s} {f('auc'):>6s} {f('ghost'):>7s} {f('tnoise'):>7s} {f('jit'):>7s} {(f'{100*np.mean(R['rej']):.0f}%' if R['rej'] else '-'):>9s}")
