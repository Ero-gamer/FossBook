# Download stats

Weekly snapshot of GitHub release download counts, written by
`.github/workflows/download-stats.yml` on `main`. Each run appends one row per
release to `downloads.csv` (`date,tag,downloads`, date in UTC). Counts are
cumulative per release, so the weekly growth is the difference between two
snapshots.

`badge.json` is the README's downloads badge (a shields.io endpoint badge): the
total of every release's downloads, refreshed every 3 hours by
`.github/workflows/download-badge.yml` (committed only when it changes).

This branch holds data only; it never merges into `main`.
