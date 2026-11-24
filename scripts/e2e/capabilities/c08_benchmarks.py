"""8: benchmark upload through TPTPBenchmarkProcessor with recursive include dependencies.
9: benchmark upload by URL/Git (#139)."""

import posixpath

from lib import endpoints as ep
from lib.db import literal
from lib.report import Blocked
from lib.steps import need_fixture, wait_upload


def _upload(ctx, client, space, processor, fixture, dependency):
    with open(fixture["path"], "rb") as f:
        r = client.post(
            ep.path("upload_benchmarks"),
            timeout=120,
            keep=["jobId", "status"],
            data={
                "space": space,
                "upMethod": "convert",
                "benchType": processor,
                "download": "true",
                "localOrURLOrGit": "local",
                "dependency": str(dependency).lower(),
                "linked": "false",
                "depRoot": space,
            },
            files={
                "benchFile": (
                    posixpath.basename(fixture["path"]),
                    f,
                    "application/x-tar",
                )
            },
        )
    body = client.json(r) or {}
    return r, body.get("jobId")


def run_benchmarks(cap, ctx):
    space, processor, uid = ctx.state.need("space", "bench_processor", "owner_user")
    owner = ctx.owner()
    timeout = ctx.cfg.timeout("upload", 300)
    axioms, problems = (
        need_fixture(ctx, "e2e-axioms.tar"),
        need_fixture(ctx, "e2e-problems.tar"),
    )
    expected_axioms = {posixpath.basename(n) for n in ctx.fx["axioms"]}
    expected_problems = {
        posixpath.basename(n): v for n, v in ctx.fx["problems"].items()
    }

    for cid, fixture, dependency, names in (
        ("1", axioms, False, expected_axioms),
        ("2", problems, True, set(expected_problems)),
    ):
        r, job = _upload(ctx, owner, space, processor, fixture, dependency)
        cap.check(
            cid + "a",
            f"{posixpath.basename(fixture['path'])} upload is accepted asynchronously",
            "POST /secure/upload/benchmarks returns 202 with an upload job id",
            202,
            {"http": r.status_code, "jobId": job},
            r.status_code == 202 and bool(job),
            ["upload_benchmarks", "upload_benchmarks_params", "upload_benchmarks_202"],
        )
        if not job:
            raise Blocked("upload not accepted")
        ok, state, seconds = wait_upload(ctx, owner, job, timeout)
        found = {
            n
            for (n,) in ctx.db.rows(
                f"SELECT name FROM starexec.benchmarks WHERE user_id={uid} AND deleted=false;"
            )
        }
        cap.check(
            cid + "b",
            "upload completes and creates exactly the archive's files",
            f"GET /services/uploads/jobs/{{id}} reaches COMPLETED within {timeout}s with totalFilesProcessed ="
            f" {len(names)}, and every file name is a benchmark of the owner",
            {"status": "COMPLETED", "processed": len(names), "names": sorted(names)},
            {
                "status": state.get("status"),
                "processed": state.get("totalFilesProcessed"),
                "missing": sorted(names - found),
                "seconds": seconds,
            },
            ok
            and state.get("status") == "COMPLETED"
            and state.get("totalFilesProcessed") == len(names)
            and names <= found,
            ["upload_job_status"],
        )

    bench_ids = {}
    for name, meta in sorted(expected_problems.items()):
        bid = ctx.db.int(
            f"SELECT id FROM starexec.benchmarks WHERE user_id={uid} AND name={literal(name)}"
            f" AND deleted=false ORDER BY id DESC LIMIT 1;"
        )
        bench_ids[name] = bid
        attrs = dict(
            ctx.db.rows(
                f"SELECT attr_key, attr_value FROM starexec.bench_attributes WHERE bench_id={bid or 0};"
            )
        )
        want = {
            "starexec-expected-result": meta["expected_status"],
            "Status": meta["expected_status"],
            "SPC": meta["spc"],
        }
        got = {k: attrs.get(k) for k in want}
        cap.check(
            f"3-{name}",
            f"{name}: processor attributes match the TPTP header",
            "bench_attributes starexec-expected-result and Status equal the header '% Status', and SPC equals"
            " the header '% SPC' (TPTPBenchmarkProcessor prints them verbatim)",
            want,
            {"recorded": got, "all_keys": sorted(attrs)},
            bool(bid) and got == want,
            ["upload_benchmarks"],
        )
        deps = sorted(
            p
            for (p,) in ctx.db.rows(
                f"SELECT include_path FROM starexec.bench_dependency WHERE primary_bench_id={bid or 0};"
            )
        )
        axiom_names = sorted(
            b
            for (b,) in ctx.db.rows(
                f"SELECT b.name FROM starexec.bench_dependency d JOIN starexec.benchmarks b ON b.id=d.secondary_bench_id"
                f" WHERE d.primary_bench_id={bid or 0};"
            )
        )
        want_deps = sorted(meta["includes"])
        cap.check(
            f"4-{name}",
            f"{name}: every include is resolved as a dependency",
            "bench_dependency rows for the problem have include_path exactly the header include() list, and"
            " each secondary benchmark's name is that include's file name",
            {
                "include_path": want_deps,
                "secondary": sorted(posixpath.basename(p) for p in want_deps),
            },
            {"include_path": deps, "secondary": axiom_names},
            deps == want_deps
            and axiom_names == sorted(posixpath.basename(p) for p in want_deps),
            ["upload_benchmarks_params"],
        )
    ctx.state.set("benchmarks", bench_ids)
    ctx.state.fact(
        "expected_status",
        {n: m["expected_status"] for n, m in expected_problems.items()},
    )


def run_url_upload(cap, ctx):
    space, processor = ctx.state.need("space", "bench_processor")
    owner = ctx.owner()
    # Git addresses must end in .git: any other is refused with 400 by isRequestValid before the
    # Git path runs, so a .zip address could never have seen that path fixed or still broken.
    for cid, method, field, address in (
        ("1", "URL", "url", "http://127.0.0.1:9/none.zip"),
        ("2", "Git", "git", "http://127.0.0.1:9/none.git"),
    ):
        before = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        # 127.0.0.1 and a closed port: nothing is fetched even if the implementation ever did fetch.
        r = owner.post(
            ep.path("upload_benchmarks"),
            data={
                "space": space,
                "localOrURLOrGit": method,
                field: address,
                "upMethod": "convert",
                "benchType": processor,
                "download": "false",
                "dependency": "false",
                "linked": "false",
                "depRoot": space,
            },
            # Multipart, with the file field empty, as the browser form sends it. A urlencoded
            # body fails in parseMultipartRequest before the URL branch is ever reached, so this
            # check could never have seen #139 fixed.
            files={"benchFile": ("", b"", "application/octet-stream")},
        )
        after = ctx.db.int("SELECT count(*) FROM starexec.upload_jobs;")
        ok = r.status_code in (200, 202) and after == before + 1
        passed = cap.check(
            cid,
            f"#139: benchmark upload by {method}",
            f"POST /secure/upload/benchmarks localOrURLOrGit={method} is accepted (202/200) and creates"
            " an upload job; until #139 is fixed this is expected to FAIL with HTTP 500 and no row",
            {"http": "200/202", "upload_jobs_delta": 1},
            {"http": r.status_code, "upload_jobs_delta": after - before},
            ok,
            ["upload_benchmarks", "upload_benchmarks_url_throw"],
            known="#139",
        )
        if not passed and r.status_code == 500:
            cap.finding(
                f"139-{method}",
                "#139 still reproduces",
                f"{method} upload returned HTTP 500 with no upload_jobs row; UploadBenchmark throws"
                " UnsupportedOperationException before any fetch.",
            )
