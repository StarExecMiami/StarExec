#!/usr/bin/env python3
"""Prove a proposed Helm repository retains everything already published.

The previous shell version took the versions to check as arguments, and
helm-repo.yml passed the literals `0.1.0 0.2.0`. That is a check that only
works for one release: the day the chart moves to 0.3.0, the caller either
keeps asserting a stale pair or somebody edits the workflow, and a check that
must be hand-edited to stay true is a check that will silently stop being true.

Everything here is derived instead:

    current version   charts/starexec/Chart.yaml
    old versions      the published index.yaml, downloaded before packaging
    proposed versions the merged index.yaml in the prepared repository

and the assertions are the properties that actually matter to someone who
pinned an old chart:

  * every version in the old index still appears in the new index;
  * every package the old index referenced is still present on disk;
  * every retained package is byte-identical to what was published, checked
    against the digest recorded in the old index;
  * the current Chart.yaml version appears in the new index;
  * the package for the current version exists;
  * that package's own metadata agrees with Chart.yaml (version and appVersion);
  * the current package is not byte-identical to an older one, which would mean
    the version was bumped without the contents changing.

Exit status is 0 only if all of them hold.
"""
import argparse
import hashlib
import os
import sys
import tarfile

try:
    import yaml
except ImportError:
    sys.exit("check-helm-retention: PyYAML is required")

CHART = "starexec"


def load_yaml(path):
    with open(path) as fh:
        return yaml.safe_load(fh) or {}


def entries(index, chart=CHART):
    """version -> entry mapping for one chart in an index.yaml."""
    return {
        e.get("version"): e
        for e in (index.get("entries", {}) or {}).get(chart, []) or []
        if e.get("version")
    }


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def chart_meta_from_package(pkg):
    """Read Chart.yaml out of a packaged .tgz without unpacking it."""
    with tarfile.open(pkg, "r:gz") as tf:
        for member in tf.getmembers():
            parts = member.name.split("/")
            if len(parts) == 2 and parts[1] == "Chart.yaml":
                fh = tf.extractfile(member)
                if fh is None:
                    return {}
                return yaml.safe_load(fh.read()) or {}
    return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo-dir", required=True,
                    help="prepared repository directory (contains the merged index.yaml)")
    ap.add_argument("--chart", required=True,
                    help="path to the source Chart.yaml defining the current version")
    ap.add_argument("--old-index", required=True,
                    help="the previously published index.yaml, saved before packaging")
    ap.add_argument("--allow-missing-index", action="store_true",
                    help="accept a declared-empty old index (genuine first publication). "
                         "Without this, an absent/empty/unreadable old index is an error, "
                         "because it is indistinguishable from a failed fetch.")
    args = ap.parse_args()

    failures = []

    def fail(msg):
        failures.append(msg)
        print(f"check-helm-retention: ERROR: {msg}", file=sys.stderr)

    def ok(msg):
        print(f"check-helm-retention: OK {msg}")

    # ---- derive the three version sets --------------------------------------
    chart_meta = load_yaml(args.chart)
    current = str(chart_meta.get("version") or "").strip()
    current_app = str(chart_meta.get("appVersion") or "").strip()
    if not current:
        sys.exit(f"check-helm-retention: {args.chart} declares no version")
    print(f"check-helm-retention: current chart version from {args.chart}: {current} "
          f"(appVersion {current_app or '<unset>'})")

    new_index_path = os.path.join(args.repo_dir, "index.yaml")
    if not os.path.isfile(new_index_path) or os.path.getsize(new_index_path) == 0:
        sys.exit(f"check-helm-retention: missing or empty proposed index: {new_index_path}")
    new_entries = entries(load_yaml(new_index_path))

    # The old index is the ONLY evidence of what was published. If it is absent,
    # empty, or unreadable, every retention assertion below becomes vacuously
    # true -- the validator would report that nothing was dropped precisely when
    # it cannot tell. That is how a transient fetch failure used to publish an
    # index missing a released version and still pass this check, so all four
    # cases are fatal unless the caller explicitly declares a first publication.
    if not os.path.isfile(args.old_index):
        if args.allow_missing_index:
            old_entries = {}
            print("check-helm-retention: no old index and --allow-missing-index given; "
                  "treating as a declared first publication")
        else:
            fail(f"missing old index: {args.old_index} does not exist. Retention cannot be "
                 "checked without it; pass --allow-missing-index only for a genuine first "
                 "publication.")
            old_entries = {}
    elif os.path.getsize(args.old_index) == 0:
        # Always fatal, even with the flag: a declared first publication writes a
        # well-formed empty index. A zero-byte file is what a truncated or failed
        # download leaves behind, and it cannot express intent.
        fail(f"empty old index: {args.old_index} is zero bytes. A failed fetch leaves "
             "exactly this; a declared first publication writes a valid empty index.")
        old_entries = {}
    else:
        try:
            old_doc = load_yaml(args.old_index)
        except Exception as exc:
            fail(f"malformed old index: {args.old_index} does not parse as YAML "
                 f"({str(exc).splitlines()[0]})")
            old_doc = None
        if old_doc is None:
            old_entries = {}
        elif not isinstance(old_doc, dict) or not isinstance(old_doc.get("entries"), dict):
            fail(f"malformed old index: {args.old_index} parses but has no 'entries' "
                 "mapping, so it is not a Helm repository index")
            old_entries = {}
        else:
            old_entries = entries(old_doc)
            if not old_entries:
                if args.allow_missing_index:
                    print("check-helm-retention: old index declares no versions of "
                          f"'{CHART}' and --allow-missing-index given; first publication")
                else:
                    fail(f"missing prior chart entry: the old index lists no version of "
                         f"'{CHART}'. The published repository is expected to contain at "
                         "least one; pass --allow-missing-index only for a first publication.")

    print(f"check-helm-retention: old index versions:  {sorted(old_entries) or '<none>'}")
    print(f"check-helm-retention: new index versions:  {sorted(new_entries)}")

    # ---- 1. every old version survives, with its package, byte-identical ----
    for version, old_entry in sorted(old_entries.items()):
        if version not in new_entries:
            fail(f"missing version: {version} was published but is absent from the proposed index")
            continue
        ok(f"retained version {version}")

        pkg = os.path.join(args.repo_dir, f"{CHART}-{version}.tgz")
        if not os.path.isfile(pkg) or os.path.getsize(pkg) == 0:
            fail(f"missing package: {os.path.basename(pkg)} for retained version {version}")
            continue
        ok(f"retained package {os.path.basename(pkg)}")

        published_digest = (old_entry.get("digest") or "").strip()
        if not published_digest:
            print(f"check-helm-retention: NOTE old index records no digest for {version}; "
                  "cannot prove byte-identity")
            continue
        actual = sha256_of(pkg)
        if actual != published_digest:
            fail(f"changed old-package digest: {version} published {published_digest} "
                 f"but the proposed repository holds {actual}")
        else:
            ok(f"unchanged old-package digest {version} ({actual[:16]}...)")

    # ---- 2. the current version is actually offered -------------------------
    if current not in new_entries:
        fail(f"missing current version: Chart.yaml declares {current} "
             "but the proposed index does not list it")
    else:
        ok(f"proposed index lists the current version {current}")

    current_pkg = os.path.join(args.repo_dir, f"{CHART}-{current}.tgz")
    if not os.path.isfile(current_pkg) or os.path.getsize(current_pkg) == 0:
        fail(f"missing current package: {os.path.basename(current_pkg)}")
    else:
        ok(f"current package present {os.path.basename(current_pkg)}")

        # ---- 3. packaged metadata agrees with the source chart --------------
        packaged = chart_meta_from_package(current_pkg)
        pkg_version = str(packaged.get("version") or "").strip()
        pkg_app = str(packaged.get("appVersion") or "").strip()
        if pkg_version != current:
            fail(f"metadata mismatch: packaged chart declares version {pkg_version!r} "
                 f"but {args.chart} declares {current!r}")
        else:
            ok(f"packaged version matches Chart.yaml ({current})")
        if pkg_app != current_app:
            fail(f"appVersion mismatch: packaged chart declares appVersion {pkg_app!r} "
                 f"but {args.chart} declares {current_app!r}")
        else:
            ok(f"packaged appVersion matches Chart.yaml ({current_app or '<unset>'})")

        # ---- 4. a bumped version must not be a byte-for-byte reissue --------
        current_digest = sha256_of(current_pkg)
        for version in sorted(old_entries):
            if version == current:
                continue
            other = os.path.join(args.repo_dir, f"{CHART}-{version}.tgz")
            if os.path.isfile(other) and sha256_of(other) == current_digest:
                fail(f"current package {current} is byte-identical to {version}: "
                     "the version was bumped without any content change")
        ok(f"current package is distinct from every retained version "
           f"({current_digest[:16]}...)")

    if failures:
        print(f"check-helm-retention: FAILED ({len(failures)} problem(s))", file=sys.stderr)
        return 1
    print("check-helm-retention: PASSED -- nothing published was dropped or altered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
