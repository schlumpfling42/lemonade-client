# usage: bash pkg.sh N  (run from repo root) — diff snap-N against working tree
W=.superpowers/sdd/2026-10-18-coding-subgraph; n=$1; out=${2:-$W/task-$n-review.diff}
{ echo "# Review package (snapshot diff snap-$n -> working tree; not a git repo)"; for d in src docs; do diff -rqN $W/snap-$n/$d $d; done; diff -qN $W/snap-$n/README.md README.md; echo; for d in src docs; do diff -ruN -U10 $W/snap-$n/$d $d; done; diff -uN $W/snap-$n/README.md README.md; [ -f .env ] && diff -uN $W/snap-$n/.env .env; } > $out
wc -l $out
