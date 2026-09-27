"""Build ~/shuttl-corpus/court-detect: frames, labels, reference keypoints, per-stage dumps.

Frames are extracted from videos that stay outside the repo (broadcast footage
and the two handheld/club recordings). Labels were set by eye from the overlay
contact sheets on 2026-09-27; 'tune' frames shaped the parameters, 'held' frames
were extracted and labelled after the parameters were frozen.

    python3 tools/court/build_court_corpus.py
"""
import cv2, json, os, subprocess, sys, numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import court_probe as cp
HOME = os.path.expanduser('~'); OUT = HOME + '/shuttl-corpus/court-detect'
V = {
    'hall': HOME + '/Downloads/IMG_4795.MOV',
    'm5892': HOME + '/shuttl-corpus/IMG_5892/source.mov',
    'hk': HOME + '/Movies/short_test.mp4', 'hk1': HOME + '/Movies/short_test1.mp4',
    'cz': HOME + '/Downloads/syq-bd-short.mp4',
    'b2eab': HOME + '/shuttl-corpus/2eabfc01-7ba6-4d0c-a481-0f7591a0851c/source.mp4',
    'p743d': HOME + '/shuttl-corpus/743d7fb1-ad6e-44d2-bd35-f9a6c0892a87/source.mp4',
    'rev': HOME + '/Movies/shuttle-review.mp4',
    'dark': HOME + '/Movies/Screen Recording 2026-04-13 at 13.25.33.mp4',
    'par1': HOME + '/Movies/Screen Recording 2026-07-23 at 13.54.44.mp4',
    'par2': HOME + '/Movies/Screen Recording 2026-07-23 at 14.07.04.mp4',
    'czr': HOME + '/Movies/Screen Recording 2026-07-24 at 23.47.41.mp4',
    'foot': HOME + '/Downloads/france26.mov',
    'foot2': HOME + '/Downloads/Screen Recording 2026-09-13 at 18.41.54.mov',
}
# (video key, seconds, split, court visible and whole?)
C, N = True, False
FRAMES = [
    *[('hall', t, 'tune', C) for t in (5, 60, 150, 240, 330, 420, 510, 580)],
    *[('m5892', t, 'tune', C) for t in (2, 12, 25, 40, 55, 68)],
    *[('hk1', t, 'tune', C) for t in (1, 15, 30, 45, 60, 75, 90, 110)],
    *[('cz', t, 'tune', C) for t in (0.5, 3, 6)],
    *[('b2eab', t, 'tune', C) for t in (8, 20, 70, 130)], ('b2eab', 45, 'tune', N), ('b2eab', 100, 'tune', N),
    *[('p743d', t, 'tune', C) for t in (8, 20, 130)], *[('p743d', t, 'tune', N) for t in (45, 70, 100)],
    ('rev', 30, 'tune', N), ('rev', 200, 'tune', N),
    *[('hall', t, 'held', C) for t in (30, 100, 200, 300, 400, 450, 550)],
    *[('m5892', t, 'held', C) for t in (7, 33, 48, 62)],
    *[('hk', t, 'held', C) for t in (5, 20)], *[('hk1', t, 'held', C) for t in (50, 100)],
    ('b2eab', 90, 'held', C), ('b2eab', 30, 'held', N), ('b2eab', 115, 'held', N),
    *[('p743d', t, 'held', C) for t in (60, 90)], *[('p743d', t, 'held', N) for t in (30, 115)],
    ('dark', 80, 'held', C), *[('dark', t, 'held', N) for t in (20, 150, 220, 300)],
    *[('par1', t, 'held', C) for t in (20, 90, 150)], *[('par2', t, 'held', N) for t in (30, 120)],
    *[('czr', t, 'held', N) for t in (20, 100, 200, 280)],
    *[('foot', t, 'held', N) for t in (600, 2400, 4000, 6000)], *[('foot2', t, 'held', N) for t in (300, 2000)],
]
NAMES = ['top_left', 'top_right', 'bottom_right', 'bottom_left', 'net_left', 'net_right',
         'service_line_near_left', 'service_line_near_right', 'service_line_far_left',
         'service_line_far_right', 'center_near', 'center_far']
def main():
    os.makedirs(OUT + '/frames', exist_ok=True)
    manifest = []; wrong = 0
    for key, t, split, court in FRAMES:
        name = f'{key}_{t}'; path = f'{OUT}/frames/{name}.png'
        if not os.path.exists(path):
            subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-ss', str(t), '-i', V[key], '-frames:v', '1', path], check=True)
        img = cv2.imread(path); H, info = cp.detect(img); ok, why = cp.accept(img, H, info)
        e = dict(name=name, video=os.path.basename(V[key]), seconds=t, split=split, court=court,
                 width=img.shape[1], height=img.shape[0], probe_accepts=ok, probe_reason=why,
                 stages=dict(lines=len(info.get('lines', [])), groups=[len(g) for g in info.get('groups', [])],
                             quads=info.get('quads'), rms=info.get('rms'), step=info.get('step')))
        if ok: e['keypoints'] = {n: [float(x), float(y)] for n, (x, y) in zip(NAMES, cp.proj(H, cp.NAMED))}
        if ok != court: wrong += 1
        manifest.append(e); print(f"{name:12s} {split:4s} {'court' if court else 'none '} probe {'court' if ok else 'none '} ({why})", flush=True)
    # independent references
    ref = dict(
        img_5892_marking_frame=dict(frame='m5892_ref.png', source='IMG_5892 frame the marks_ideal were fitted on',
                                    keypoints=json.load(open(HOME + '/shuttl-corpus/IMG_5892/marks_ideal.json'))['manual_court_keypoints']),
        p743d_human=dict(frames=['p743d_8', 'p743d_20', 'p743d_130', 'p743d_60', 'p743d_90'], source='stored manual_court_keypoints for 743d7fb1 (near/far labels as the user placed them)',
                         keypoints=json.load(open(HOME + '/shuttl-corpus/743d7fb1-ad6e-44d2-bd35-f9a6c0892a87/video.json'))['manual_court_keypoints']))
    if not os.path.exists(OUT + '/frames/m5892_ref.png'):
        import shutil; shutil.copy('/tmp/hm/v/f3.png', OUT + '/frames/m5892_ref.png')
    json.dump(dict(frames=manifest, references=ref), open(OUT + '/manifest.json', 'w'), indent=1)
    print(f'{len(manifest)} frames, {sum(e["court"] for e in manifest)} with a court; probe disagrees with the label on {wrong}')
if __name__ == '__main__': main()
