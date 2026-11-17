import cv2, numpy as np, glob, os, sys, collections
from sklearn.metrics import roc_auc_score
out = sys.argv[1]; variants = sys.argv[2].split(','); degs = ['n4','n10']
rows = collections.defaultdict(lambda: collections.defaultdict(list))
def auc(s,y,mask):
    s=s[mask]; y=y[mask]
    return roc_auc_score(y,s) if y.sum()>30 and (~y).sum()>30 else np.nan
for s in range(12):
    n=f'p{s:02d}'; gt=np.load(f'ph_gt/{n}__gt.npy'); w=np.load(f'ph_gt/{n}__w.npy'); em=np.load(f'ph_gt/{n}__edge.npy')
    thin = gt&(w<=2.5); thick = gt&(w>=5); near = cv2.dilate(gt.astype(np.uint8),np.ones((11,11),np.uint8)).astype(bool)
    allm = np.ones_like(gt)
    for d in degs:
        for v in variants:
            o = cv2.imread(f'{out}/{n}__{d}__{v}.png',0).astype(np.float32); D = cv2.GaussianBlur(255-o,(0,0),1.0)
            R = rows[(d,v)]
            R['auc_all'].append(auc(D,gt,allm)); R['auc_thin'].append(auc(D,thin,~(gt&~thin))); R['auc_thick'].append(auc(D,thick,~(gt&~thick)))
            skin = ~near; eb = em & skin; far = skin & ~cv2.dilate(em.astype(np.uint8),np.ones((41,41),np.uint8)).astype(bool)
            if eb.sum()>200 and far.sum()>2000 and gt.sum()>100:
                vc = D[gt].mean()-D[far].mean(); sd = D[far].std()+1e-6
                R['false_edge_over_vein'].append(abs(D[eb].mean()-D[far].mean())/ (abs(vc)+1e-6))
                R['vein_cnr'].append(vc/sd)
            hf = o-cv2.GaussianBlur(o,(0,0),2.0); R['hf_noise'].append(np.std(hf[far])/ (np.std(o[skin])+1e-6))
print(f"{'noise':5s} {'variant':8s} {'AUC all':>8s} {'AUC thin':>9s} {'AUC thick':>10s} {'veinCNR':>8s} {'falseEdge/vein':>15s} {'HFnoise':>8s}")
for (d,v),R in sorted(rows.items()):
    f=lambda k: np.nanmean(R[k]); print(f"{d:5s} {v:8s} {f('auc_all'):8.3f} {f('auc_thin'):9.3f} {f('auc_thick'):10.3f} {f('vein_cnr'):8.2f} {f('false_edge_over_vein'):15.3f} {f('hf_noise'):8.4f}")
