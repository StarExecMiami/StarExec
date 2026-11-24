"""15: job CSV, job XML (and its re-import, #122) and pair output downloads against the database."""

import csv
import io
import re
import xml.etree.ElementTree as ET
import zipfile

from lib import endpoints as ep
from lib.report import Blocked
from lib.steps import job_id_by_name, pair_statuses


def _zip(response):
    try:
        return zipfile.ZipFile(io.BytesIO(response.content))
    except zipfile.BadZipFile:
        return None


def run_exports(cap, ctx):
    job = ctx.state.need("job_single")
    owner = ctx.owner()

    r = owner.get(
        ep.path("download"),
        params={"type": "job", "id": job, "returnids": "true", "getcompleted": "false"},
        timeout=120,
    )
    z = _zip(r)
    names = z.namelist() if z else []
    csvs = [n for n in names if n.endswith(".csv")]
    cap.check(
        "1",
        "job CSV download is a zip holding one CSV",
        "GET /secure/download?type=job returns a zip with exactly one .csv entry",
        1,
        {"http": r.status_code, "entries": names},
        len(csvs) == 1,
        ["download", "download_job_csv"],
    )
    if len(csvs) == 1:
        rows = list(
            csv.DictReader(io.StringIO(z.read(csvs[0]).decode("utf-8", "replace")))
        )
        db = {
            int(p): (int(b), int(c), float(cpu), float(wall), res)
            for p, b, c, cpu, wall, res in ctx.db.rows(
                "SELECT p.id, p.bench_id, s.config_id, coalesce(s.cpu,0), coalesce(s.wallclock,0),"
                " coalesce((SELECT a.attr_value FROM starexec.job_attributes a WHERE a.pair_id=p.id AND a.stage_number=s.stage_number"
                "  AND a.attr_key='starexec-result' LIMIT 1),'')"
                f" FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id WHERE p.job_id={job};"
            )
        }
        mismatches = []
        seen = set()
        for row in rows:
            try:
                pid = int(row["pair id"])
            except (KeyError, ValueError):
                mismatches.append({"unparseable_row": dict(list(row.items())[:4])})
                continue
            seen.add(pid)
            want = db.get(pid)
            got = (
                int(row["benchmark id"]),
                int(row["configuration id"]),
                float(row["cpu time"]),
                float(row["wallclock time"]),
                row["result"],
            )
            if want is None or got[:4] != want[:4] or (want[4] and got[4] != want[4]):
                mismatches.append({"pair": pid, "csv": got, "db": want})
        cap.check(
            "2",
            "every CSV row matches the database",
            "the CSV has exactly one row per pair, and each row's"
            " benchmark id, configuration id, cpu time, wallclock time (as doubles) and result equal the pair's"
            " stage row and stage-1 starexec-result",
            {"rows": len(db), "mismatches": []},
            {
                "rows": len(rows),
                "missing": sorted(set(db) - seen),
                "mismatches": mismatches[:20],
                "header": list(rows[0].keys()) if rows else None,
            },
            len(rows) == len(db) and set(db) == seen and not mismatches,
            ["download_job_csv"],
        )

    r = owner.get(
        ep.path("download"), params={"type": "jobXML", "id": job}, timeout=120
    )
    z = _zip(r)
    xmls = [n for n in (z.namelist() if z else []) if n.endswith(".xml")]
    pairs_xml = set()
    if xmls:
        raw = z.read(xmls[0])
        # defusedxml is not installed here. The export comes from the disposable deployment, and expat never
        # fetches external entities; a DTD (the entity-expansion vector) is refused outright instead.
        if b"<!DOCTYPE" in raw or b"<!ENTITY" in raw:
            raise Blocked("exported job XML carries a DTD; refusing to parse it")
        root = ET.fromstring(raw)
        for el in root.iter():
            tag = el.tag.split("}")[-1]
            if (
                tag in ("JobPair", "JobLine")
                and el.get("bench-id")
                and el.get("config-id")
            ):
                pairs_xml.add((int(el.get("bench-id")), int(el.get("config-id"))))
    db_pairs = {
        (int(b), int(c))
        for b, c in ctx.db.rows(
            f"SELECT p.bench_id, s.config_id FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
            f" WHERE p.job_id={job};"
        )
    }
    cap.check(
        "3",
        "job XML export lists exactly the job's (benchmark, configuration) pairs",
        "GET /secure/download?type=jobXML returns a zip with an XML whose JobPair/JobLine elements carry exactly"
        " the job's bench-id/config-id combinations",
        sorted(db_pairs),
        {"http": r.status_code, "xml_entries": xmls, "pairs": sorted(pairs_xml)},
        bool(xmls) and pairs_xml == db_pairs,
        ["download", "download_job_xml"],
    )
    if xmls:
        text = z.read(xmls[0]).decode("utf-8", "replace")
        new_name = ctx.name("reimport")
        text = re.sub(
            r'(<Job\s[^>]*name=")[^"]*(")', rf"\g<1>{new_name}\g<2>", text, count=1
        )
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as out:
            out.writestr("job.xml", text)
        r = owner.post(
            ep.path("upload_job_xml"),
            data={"space": ctx.state.need("space")},
            files={"f": ("reimport.zip", buf.getvalue(), "application/zip")},
        )
        rejob = job_id_by_name(ctx, new_name)
        n = len(pair_statuses(ctx, rejob)) if rejob else 0
        passed = cap.check(
            "4",
            "the exported job XML re-imports (#122)",
            "uploading the exported XML (renamed) through"
            " /secure/upload/jobXML creates a job with the same number of pairs",
            len(db_pairs),
            {"http": r.status_code, "job": rejob, "pairs": n},
            n == len(db_pairs),
            ["upload_job_xml", "download_job_xml"],
            known="#122",
        )
        if not passed:
            cap.finding(
                "122",
                "exported job XML does not re-import",
                f"re-import of job {job}'s export produced job={rejob} with {n} pairs (expected {len(db_pairs)}).",
            )
        if rejob:
            owner.post(ep.path("delete_job"), data={"selectedIds[]": rejob})

    config = ctx.state.need("fixture_configs")["wrong"]
    wrong_pairs = [
        int(p)
        for (p,) in ctx.db.rows(
            f"SELECT p.id FROM starexec.job_pairs p JOIN starexec.jobpair_stage_data s ON s.jobpair_id=p.id"
            f" WHERE p.job_id={job} AND s.config_id={config} ORDER BY p.id;"
        )
    ]
    if not wrong_pairs:
        raise Blocked("no 'wrong' fixture pairs in the single-stage job")
    r = owner.get(
        ep.path("download"),
        # One comma-separated value: Download reads getParameter("id[]") and Validator.convertToIntList splits on ",".
        params={"type": "jp_outputs", "id[]": ",".join(str(p) for p in wrong_pairs)},
        timeout=120,
    )
    z = _zip(r)
    marked = 0
    entries = z.namelist() if z else []
    for n in entries:
        if not n.endswith(
            "/"
        ) and b"% SZS status CounterSatisfiable for fixture" in z.read(n):
            marked += 1
    cap.check(
        "5",
        "pair outputs zip holds each selected pair's own output",
        "GET /secure/download?type=jp_outputs for"
        " the 'wrong' pairs returns a zip in which exactly one entry per pair carries the fixture's SZS line",
        len(wrong_pairs),
        {
            "http": r.status_code,
            "entries": len(entries),
            "marked_entries": marked,
            "sample": entries[:6],
        },
        marked == len(wrong_pairs),
        ["download", "download_pair_outputs"],
    )
    r = owner.get(
        ep.path("download"),
        params={"type": "jp_output", "id": wrong_pairs[0]},
        timeout=60,
    )
    z = _zip(r)
    entries = z.namelist() if z else []
    hits = sum(
        1
        for n in entries
        if not n.endswith("/")
        and b"% SZS status CounterSatisfiable for fixture" in z.read(n)
    )
    cap.check(
        "6",
        "single pair output zip",
        "GET /secure/download?type=jp_output returns a zip with exactly one entry"
        " carrying that pair's fixture line",
        1,
        {"http": r.status_code, "entries": entries[:10], "hits": hits},
        hits == 1,
        ["download", "download_pair_output"],
    )
