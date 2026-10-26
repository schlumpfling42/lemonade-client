# usage: bash pkg.sh NAME OUT  — diff snapshot NAME vs working tree
W=.superpowers/sdd/2026-10-25-skills; n=$1; out=$2
{ echo "# Review package (snapshot $n -> working tree; not a git repo)";
  for d in src docs; do diff -rqN $W/snap-$n/$d $d; done; diff -rqN $W/snap-$n/gp/src graph-processor/src; diff -rqN $W/snap-$n/ga/src graph-annotations/src; echo;
  for d in src docs; do diff -ruN -U10 $W/snap-$n/$d $d; done
  diff -ruN -U10 $W/snap-$n/gp/src graph-processor/src; diff -ruN -U10 $W/snap-$n/ga/src graph-annotations/src
  diff -uN $W/snap-$n/README.md README.md; diff -uN $W/snap-$n/CLAUDE.md CLAUDE.md; } > $out; wc -l $out
