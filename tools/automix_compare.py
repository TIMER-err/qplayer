#!/usr/bin/env python3
"""参考包对拍台：读 qplayer 导出的决策 JSON，用参考包重跑一遍并逐字段比对。

用法：
    # 1) 启动时打开 dump（每次 automix 过渡写一个 JSON）
    java -Dqplayer.automix.dump=/tmp/automix-dump ... dev.t1m3.qplayer.desktop.app.Main
    # 2) 对拍
    python3 tools/automix_compare.py /tmp/automix-dump

比对字段：chosen 的 template_id / tier / score / 出场与进场区间。
退出码：0 = 全部一致；1 = 有差异（打印差异明细）。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / '..' /
                  'Documents' / 'automix-c' / 'reference-python'))
sys.path.insert(0, '/home/strokeshading/Documents/automix-c/reference-python')

import scoring as ref  # noqa: E402

FIELDS = {
    'template_id': lambda c: c['template_id'],
    'tier': lambda c: c['tier'],
    'score': lambda c: c['score'],
    'out_start': lambda c: c['plan']['outgoing_source_range'][0],
    'out_end': lambda c: c['plan']['outgoing_source_range'][1],
    'in_start': lambda c: c['plan']['incoming_source_range'][0],
    'in_end': lambda c: c['plan']['incoming_source_range'][1],
}


def track_from_dump(d):
    t = {
        'id': d['id'],
        'duration': d['duration'],
        'leading_silence': d.get('leading_silence', 0),
        'trailing_silence': d.get('trailing_silence', 0),
        'features': dict(d.get('features', {})),
        'regions': [{'id': r['id'], 'start': r['start'], 'end': r['end'],
                     'features': dict(r.get('features', {}))} for r in d.get('regions', [])],
        'downbeats': list(d.get('downbeats', [])),
    }
    return t


def compare(path):
    data = json.loads(pathlib_read(path))
    outgoing = track_from_dump(data['outgoing'])
    incoming = track_from_dump(data['incoming'])
    result = ref.plan_transition(outgoing, incoming, seed=data['seed'], selection='weighted')
    chosen = result['chosen']
    diffs = []
    # 先逐模板比分数（24 条 ranking）：哪一条不一致，直接指向哪段特征/闸门
    java_ranking = {r['template_id']: r for r in data.get('ranking', [])}
    for r in result['ranking']:
        j = java_ranking.get(r['template_id'])
        if j is None:
            diffs.append((f'template {r["template_id"]} 缺失', None, r['template_id']))
            continue
        if j['eligible'] != r['eligible']:
            diffs.append((f'template {r["template_id"]} eligible', j['eligible'], r['eligible']))
        if abs(float(j['score']) - float(r['score'])) > 1e-9:
            diffs.append((f'template {r["template_id"]} score', j['score'], r['score']))
            # 并排打出两边的因子里程碑（差在哪一项，一眼看到）
            jf = j.get('factors', {})
            rf = r.get('factors', {})
            for name in sorted(set(jf) | set(rf)):
                jv = jf.get(name)
                rv = rf.get(name)
                same = (jv is not None and rv is not None
                        and abs(float(jv) - float(rv)) <= 1e-12)
                if not same:
                    print(f'      factor {name}: java={jv} · reference={rv}')
    for name, getter in FIELDS.items():
        expected = data['java'].get(name)
        actual = getter(chosen)
        if isinstance(actual, float) or isinstance(expected, float):
            if expected is None or abs(float(actual) - float(expected)) > 1e-9:
                diffs.append((name, expected, actual))
        elif expected != actual:
            diffs.append((name, expected, actual))
    return diffs, chosen


def pathlib_read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


FIELD_HINTS = {
    'template_id': '候选指纹变了：先看 ranking 里哪条模板的 score/eligible 不同',
    'tier': 'tier 不同 ⇒ 最高 tier 那层变了：看 ranking 里更高 tier 的模板为什么在一边合格',
    'score': 'score 差 ⇒ 因子或 boundary_delta；用 ranking 逐模板比，定位到具体模板',
    'out_start': '出场起点 ⇒ duration / out_rate / downbeat 吸附（tempo 与 downbeats 口径）',
    'out_end': '出场终点 = out_start + duration×out_rate ⇒ duration 或速率',
    'in_start': '进场起点 ⇒ in_region.start 与 downbeat 吸附',
    'in_end': '进场终点 = in_start + duration×in_rate ⇒ duration 或速率',
}


def explain(diffs):
    """把差异字段映射到"大概是哪段特征/规则"，方便照着修。"""
    seen = set()
    for name, _, _ in diffs:
        key = name.split(' ')[0]
        hint = FIELD_HINTS.get(key)
        if hint and key not in seen:
            seen.add(key)
            print(f'    ↳ {name}: {hint}')


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    root = Path(argv[1])
    files = sorted(root.glob('*.json'))
    if not files:
        print(f'没有找到 dump 文件：{root}')
        return 2
    bad = 0
    for f in files:
        diffs, chosen = compare(f)
        if diffs:
            bad += 1
            print(f'✗ {f.name} 模板 {chosen["template_id"]} 与 Java 不一致：')
            for name, expected, actual in diffs:
                print(f'    {name}: java={expected} · reference={actual}')
            explain(diffs)
        else:
            print(f'✓ {f.name} 模板 {chosen["template_id"]} 全等')
    print(f'\n共 {len(files)} 份，{bad} 份有差异')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
