# Read-only watcher for exact per-stage measurement checks. Runs inside the app container or pod via the
# configured app_exec template: `<app_exec> timeout <s> bash -s -- <min_pair_id> <data_root>`; writes only
# to stdout. Same event format as ../watch_stage_measurements.bash, with the data root as a parameter.
# Fields: utc  pair  kind  stage_named_by_stats_json  size_mtime  values
MIN_PAIR=${1:-0}
ROOT=${2:-/app/data}
declare -A SIG
shopt -s nullglob
while true; do
  for f in "$ROOT"/logs/*/*/pair_*/var.out "$ROOT"/logs/*/*/pair_*/watcher.out \
           "$ROOT"/logs/*/*/pair_*/stats.json "$ROOT"/logs/*/*/pair_*/stage-stats/*.json; do
    case "$f" in
      */stage-stats/*) pairdir=${f%/stage-stats/*};;
      *) pairdir=${f%/*};;
    esac
    pair=${pairdir##*/pair_}
    [[ "$pair" =~ ^[0-9]+$ ]] && [ "$pair" -ge "$MIN_PAIR" ] || continue
    s=$(stat -c '%s %.9Y' "$f" 2>/dev/null) || continue
    [ "${SIG[$f]:-}" = "$s" ] && continue
    SIG[$f]=$s
    t=$(date -u +%Y-%m-%dT%H:%M:%S.%3N)
    named=$(sed -n 's/.*"stageNumber": *\([0-9]*\).*/\1/p' "$pairdir/stats.json" 2>/dev/null | head -1)
    case "$f" in
      */var.out) kind=var; v=$(grep -E '^(WCTIME|CPUTIME|USERTIME|SYSTEMTIME|MAXVM)=' "$f" 2>/dev/null | tr '\n' ' ');;
      */watcher.out) kind=watcher; v="RSS=$(awk '/maximum resident set size/ { print $5 }' "$f" 2>/dev/null)";;
      */stats.json) kind=stats; v=$(tr -d '\n' < "$f" 2>/dev/null | head -c 400);;
      *) kind="stage-stats/${f##*/}"; v=$(tr -d '\n' < "$f" 2>/dev/null | head -c 400);;
    esac
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$t" "$pair" "$kind" "${named:-}" "$s" "$v"
  done
  sleep 0.05
done
