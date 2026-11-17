import cv2, numpy as np, glob, os, sys, collections
from sklearn.metrics import roc_auc_score
out = sys.argv[1] if len(sys.argv)>1 else 'real_out'
variants = sys.argv[2].split(',') if len(sys.argv)>2 else ['legacy','low','medium','high']
rows = collections.defaultdict(lambda: collections.defaultdict(list))
def auc(score, gt, mask):
    s = score[mask]; y = gt[mask]
    if y.sum()<50 or (~y).sum()<50: return np.nan
    return roc_auc_score(y, s)
names = sorted({os.path.basename(f).split('__')[0] for f in glob.glob('real_in/*__clean.png')})
for n in names:
    hand = np.load(f'real_gt/{n}__hand.npy'); thin=np.load(f'real_gt/{n}__thin.npy'); thick=np.load(f'real_gt/{n}__thick.npy')
    allv = thin|thick
    kind = 'skin' if n.startswith('skin') else 'full'
    for v in variants:
        clean = cv2.imread(f'{out}/{n}__clean__{v}.png',0).astype(np.float32)
        for deg in ('clean','ill','nz8','n8'):
            o = cv2.imread(f'{out}/{n}__{deg}__{v}.png',0).astype(np.float32)
            dark = 255-o                                            # veins are dark -> high score
            sm = cv2.GaussianBlur(dark,(0,0),1.0)                   # same light smoothing for all (fair AUC)
            R = rows[(kind,deg,v)]
            R['auc_all'].append(auc(sm, allv, hand)); R['auc_thin'].append(auc(sm, thin, hand)); R['auc_thick'].append(auc(sm, thick, hand))
            if deg!='clean':
                a = cv2.GaussianBlur(o,(0,0),0.8)[hand]; b = cv2.GaussianBlur(clean,(0,0),0.8)[hand]
                R['corr_vs_clean'].append(np.corrcoef(a,b)[0,1])
            skin = hand & ~cv2.dilate(allv.astype(np.uint8), np.ones((9,9),np.uint8)).astype(bool)
            hf = o - cv2.GaussianBlur(o,(0,0),2.0)
            R['hf_noise'].append(np.std(hf[skin]) / (np.std(o[hand])+1e-6))   # relative pixel noise in vein-free skin
print(f"{'set':5s} {'input':5s} {'variant':8s} {'AUC all':>8s} {'AUC thin':>9s} {'AUC thick':>10s} {'corr/clean':>11s} {'HF noise':>9s}")
for (kind,deg,v),R in sorted(rows.items()):
    f = lambda k: np.nanmean(R[k]) if R[k] else float('nan')
    print(f"{kind:5s} {deg:5s} {v:8s} {f('auc_all'):8.3f} {f('auc_thin'):9.3f} {f('auc_thick'):10.3f} {f('corr_vs_clean'):11.3f} {f('hf_noise'):9.4f}")
