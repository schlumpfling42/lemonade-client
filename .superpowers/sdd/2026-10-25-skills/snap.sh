# usage: bash snap.sh NAME  (repo root) — snapshot sources
W=.superpowers/sdd/2026-10-25-skills; rm -rf $W/snap-$1; mkdir -p $W/snap-$1; cp -r src docs README.md CLAUDE.md $W/snap-$1/; mkdir -p $W/snap-$1/gp $W/snap-$1/ga; cp -r graph-processor/src $W/snap-$1/gp/; cp -r graph-annotations/src $W/snap-$1/ga/; echo snap-$1
