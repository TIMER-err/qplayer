#!/usr/bin/env python3
"""把对拍目录里的选定结果打成一张表（便于和真机日志逐行核对）。

用法：
    python3 tools/automix_table.py /tmp/qplayer-dump-real > docs/automix-parity-table.md
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, '/home/strokeshading/Documents/automix-c/reference-python')
import scoring as ref  # noqa: E402

NAMES = {
    'song-1': '春めく', 'song-2': '神的随波逐流',
    'song-3': '夏が僕らを呼ぶのなら', 'song-4': '風の中は走るっきゃないっ！',
    'song-5': '秒針を噛む', 'song-6': '愛とU',
}


def name(track_id):
    return NAMES.get(track_id, track_id)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    rows = []
    for path in sorted(Path(argv[1]).glob('*.json')):
        d = json.loads(path.read_text())
        java = d.get('java', {})
        r_window = (java.get('out_end') or 0) - (java.get('out_start') or 0)
        chosen = None
        for r in d.get('ranking', []):
            if r['template_id'] == java.get('template_id'):
                chosen = r
                break
        # 参考包侧：用同一份 dump 的特征重跑，作为"人类可读的 diff=0"
        outgoing = {
            'id': d['outgoing']['id'], 'duration': d['outgoing']['duration'],
            'leading_silence': d['outgoing'].get('leading_silence', 0),
            'trailing_silence': d['outgoing'].get('trailing_silence', 0),
            'features': d['outgoing'].get('features', {}),
            'regions': d['outgoing'].get('regions', []),
            'downbeats': d['outgoing'].get('downbeats', []),
        }
        incoming = {
            'id': d['incoming']['id'], 'duration': d['incoming']['duration'],
            'leading_silence': d['incoming'].get('leading_silence', 0),
            'trailing_silence': d['incoming'].get('trailing_silence', 0),
            'features': d['incoming'].get('features', {}),
            'regions': d['incoming'].get('regions', []),
            'downbeats': d['incoming'].get('downbeats', []),
        }
        try:
            result = ref.plan_transition(outgoing, incoming, seed=d['seed'], selection='weighted')
            rc = result['chosen']
            ref_cell = (rc['template_id'], rc['plan']['duration_seconds'],
                        rc['plan']['outgoing_source_range'], rc['plan']['incoming_source_range'])
        except Exception as exc:  # 参考包跑不动时如实标注
            ref_cell = ('—', 0.0, (0, 0), (0, 0))
            print(f'> 参考包侧失败（{path.name}）：{exc}', file=sys.stderr)
        same = (ref_cell[0] == java.get('template_id')
                and abs(ref_cell[1] - r_window) <= 1e-9
                and abs(ref_cell[2][0] - (java.get('out_start') or 0)) <= 1e-9
                and abs(ref_cell[2][1] - (java.get('out_end') or 0)) <= 1e-9
                and abs(ref_cell[3][0] - (java.get('in_start') or 0)) <= 1e-9
                and abs(ref_cell[3][1] - (java.get('in_end') or 0)) <= 1e-9)
        r_window = (java.get('out_end') or 0) - (java.get('out_start') or 0)
        rows.append({
            'file': path.name,
            'out': name(d['outgoing']['id']), 'in': name(d['incoming']['id']),
            'template': java.get('template_id'),
            'tier': java.get('tier'),
            'score': java.get('score'),
            'window': (java.get('out_end') or 0) - (java.get('out_start') or 0),
            'out_range': (java.get('out_start'), java.get('out_end')),
            'in_range': (java.get('in_start'), java.get('in_end')),
            'eligible': chosen['eligible'] if chosen else None,
            'reasons': chosen['reasons'] if chosen else '',
            'ref_template': ref_cell[0],
            'ref_window': ref_cell[1],
            'ref_out': ref_cell[2],
            'ref_in': ref_cell[3],
            'same': same,
        })
    print('| # | 出场曲 | 进场曲 | Java 模板 | 参考包模板 | 窗口（Java / 参考包） | '
          '出场区间（Java / 参考包） | 一致 |')
    print('| --- | --- | --- | --- | --- | --- | --- | --- |')
    for i, r in enumerate(rows, 1):
        print(f"| {i} | {r['out']} | {r['in']} | {r['template']} | {r['ref_template']} | "
              f"{r['window']:.2f}s / {r['ref_window']:.2f}s | "
              f"{r['out_range'][0]:.2f}→{r['out_range'][1]:.2f}s / "
              f"{r['ref_out'][0]:.2f}→{r['ref_out'][1]:.2f}s | "
              f"{'✓' if r['same'] else '✗'} |")
    same = sum(1 for r in rows if r['same'])
    print(f'\n共 {len(rows)} 份 · {same} 份与参考包一致（Java 与参考包逐位相同）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
