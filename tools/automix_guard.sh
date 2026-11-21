#!/usr/bin/env bash
# 对拍回归护栏：跑 dump 判据 → 让参考包重跑 → 任何一份不一致就非零退出。
#
#   tools/automix_guard.sh          # 只跑已有 dump 目录
#   tools/automix_guard.sh --regen  # 先重新生成 dump（需要 /tmp/qplayer-real/wav）
set -u
cd "$(dirname "$0")/.." || exit 2

MVN=$(find "$HOME/.m2/wrapper" -name mvn -type f | head -1)
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}

status=0

if [ "${1:-}" = "--regen" ]; then
  echo "== 重新生成 dump =="
  rm -rf /tmp/qplayer-dump-matrix /tmp/qplayer-dump-real
  env JAVA_HOME="$JAVA_HOME" "$MVN" -pl player-core test \
      -Dtest='ReferenceDumpTest,RealSongParityTest' -q >/tmp/guard-dump.log 2>&1 || {
    echo "dump 生成失败，见 /tmp/guard-dump.log"; exit 1; }
fi

for dir in /tmp/qplayer-dump-matrix /tmp/qplayer-dump-real; do
  [ -d "$dir" ] || { echo "缺少 $dir（先用 --regen 生成）"; status=1; continue; }
  echo "== 对拍 $dir =="
  python3 tools/automix_compare.py "$dir" || status=1
done

if [ "$status" = "0" ]; then
  echo "== 结论：全部一致 ✓ =="
else
  echo "== 结论：存在差异 ✗（看上面每份的模板/因子并排输出） =="
fi
exit "$status"
