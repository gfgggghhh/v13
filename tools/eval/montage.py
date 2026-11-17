import cv2, numpy as np, sys
def m(out, name, deg, variants, scale=0.6, labels=True):
    ims=[]
    src=cv2.imread(f'real_in/{name}__{deg}.png',0); ims.append(src)
    for v in variants: ims.append(cv2.imread(f'{out}/{name}__{deg}__{v}.png',0))
    ims=[cv2.resize(i,None,fx=scale,fy=scale,interpolation=cv2.INTER_AREA) for i in ims]
    if labels:
        for i,t in zip(ims,['input']+variants): cv2.putText(i,t,(6,18),cv2.FONT_HERSHEY_SIMPLEX,0.55,255 if i[:30,:80].mean()<128 else 0,1,cv2.LINE_AA)
    return np.hstack(ims)
if __name__=='__main__':
    out=sys.argv[1]; rows=[m(out,n,d,sys.argv[4].split(',')) for n,d in [(sys.argv[2],sys.argv[3]),(sys.argv[5],sys.argv[3])]]
    cv2.imwrite(sys.argv[6],np.vstack(rows))
