"""Capability registry. Order is execution order; numbers are the ones in the acceptance plan.

needs:   state keys the capability reads (missing -> BLOCKED)
creates: state keys it writes, so --only can tell the operator what an earlier run must have produced
"""

from . import (
    c00_deployment,
    c01_accounts,
    c03_setup,
    c07_solvers,
    c08_benchmarks,
    c10_single_stage,
    c13_pipeline,
    c14_lifecycle,
    c15_exports,
    c16_concurrency,
    c17_deletion,
    c20_browser,
    c24_closing,
    c26_classification,
    c27_account_lifecycle,
    c28_pictures,
    c29_space_tree,
    c30_membership,
    c31_processors,
    c32_tptp,
    c33_solver_lifecycle,
    c34_linking,
    c36_job_extras,
    c37_user_deletion,
    c38_home_writable,
    c35_settings_profiles,
    c39_upload_dependency_errors,
    c40_upload_by_url,
    c41_command_client,
    c42_multi_item_copy,
    c43_upload_space_permissions,
    c44_command_client_more,
)

CAPABILITIES = [
    (
        0,
        "deployment preflight: readiness, active queue, DB state",
        c00_deployment.run_preflight,
        [],
        ["queue"],
    ),
    (
        1,
        "accounts: admin-created user, login, picture bytes",
        c01_accounts.run_accounts,
        [],
        ["owner_user", "second_user", "community", "owner_personal_space"],
    ),
    (
        3,
        "subspace and permissions",
        c03_setup.run_spaces,
        ["owner_user", "second_user", "owner_personal_space"],
        ["space"],
    ),
    (
        4,
        "benchmark processor upload",
        c03_setup.run_bench_processor,
        ["community"],
        ["bench_processor"],
    ),
    (
        5,
        "post-processor upload",
        c03_setup.run_post_processor,
        ["community"],
        ["post_processor"],
    ),
    (
        6,
        "settings profile create and select",
        c03_setup.run_settings,
        ["owner_user", "post_processor", "bench_processor"],
        ["settings"],
    ),
    (
        7,
        "solver upload, build status, configurations",
        c07_solvers.run_solvers,
        ["space"],
        [
            "solver_e",
            "config_e",
            "solver_fixture",
            "fixture_configs",
            "config_verbatim",
        ],
    ),
    (
        18,
        "#101 solver description byte-exact, '<' refused",
        c07_solvers.run_description,
        ["space"],
        [],
    ),
    (
        8,
        "benchmark upload with processor and dependencies",
        c08_benchmarks.run_benchmarks,
        ["space", "bench_processor", "owner_user"],
        ["benchmarks", "expected_status"],
    ),
    (
        9,
        "#139 benchmark upload by URL/Git",
        c08_benchmarks.run_url_upload,
        ["space", "bench_processor"],
        [],
    ),
    (
        21,
        "solver upload with runTestJob=true: H1 queue, H2a NPE, H2b post=pre",
        c00_deployment.run_test_jobs,
        [
            "owner_user",
            "bench_processor",
            "post_processor",
            "benchmarks",
            "community",
            "owner_personal_space",
        ],
        [],
    ),
    (
        10,
        "single-stage job creation",
        c10_single_stage.run_create,
        ["benchmarks", "config_e", "fixture_configs", "space", "post_processor"],
        ["job_single", "job_single_configs"],
    ),
    (
        19,
        "#197 pairs listing before the statistics page",
        c10_single_stage.run_closure,
        ["benchmarks", "fixture_configs", "space", "post_processor"],
        ["job_closure"],
    ),
    (
        11,
        "execution and known answers",
        c10_single_stage.run_execute,
        ["job_single", "job_single_configs", "expected_status"],
        [],
    ),
    (
        12,
        "solver statistics: #198 unknown, R2 primary row, #194 conflicts",
        c10_single_stage.run_stats,
        ["job_single", "job_single_configs"],
        [],
    ),
    (
        13,
        "two-stage pipeline: exact attributes and measurements",
        c13_pipeline.run_pipeline,
        ["config_verbatim", "config_e", "post_processor", "space", "benchmarks"],
        ["job_pipeline"],
    ),
    (
        25,
        "#186/#190 attribute totals and table at page precision",
        c24_closing.run_attribute_totals,
        ["job_pipeline"],
        [],
    ),
    (
        24,
        "#183 pipeline pair rerun: attributes and measurements of the new attempt",
        c24_closing.run_pipeline_rerun,
        ["job_pipeline"],
        [],
    ),
    (
        23,
        "#187/#189 conflicts across stages and across jobs",
        c24_closing.run_conflicts,
        ["space", "post_processor", "benchmarks"],
        [],
    ),
    (
        14,
        "pause/resume, rerun, rerun-all, kill",
        None,
        ["job_single", "benchmarks", "fixture_configs"],
        [],
    ),
    (
        15,
        "CSV / XML / output exports",
        c15_exports.run_exports,
        ["job_single", "fixture_configs", "space"],
        [],
    ),
    (
        16,
        "#144 FAST/SLOW concurrency markers",
        c16_concurrency.run_concurrency,
        ["benchmarks", "space"],
        [],
    ),
    (
        17,
        "recycle, restore, delete",
        c17_deletion.run_deletion,
        ["owner_user", "space", "bench_processor"],
        [],
    ),
    (
        2,
        "registration through SMTP capture (optional)",
        c01_accounts.run_registration,
        [],
        [],
    ),
    (
        20,
        "browser DOM: #194 link and R2 row (optional)",
        c20_browser.run_browser,
        ["job_single", "owner_user"],
        [],
    ),
    (
        26,
        "F1/F2 per-type listings classify pairs like the summary",
        c26_classification.run_classification,
        ["space", "post_processor", "benchmarks", "fixture_configs"],
        [],
    ),
    (
        27,
        "account lifecycle: profile fields, websites, password change/reset, email change, suspend, logout",
        c27_account_lifecycle.run_account_lifecycle,
        [],
        ["acct_user"],
    ),
    (
        28,
        "profile picture replace, delete (P3, probed at runtime), default image",
        c28_pictures.run_pictures,
        ["acct_user", "owner_user"],
        [],
    ),
    (
        29,
        "space tree: nested, edit, public, move, copy, XML export/import round-trip, website",
        c29_space_tree.run_space_tree,
        ["acct_user"],
        ["tree_root", "tree_ids"],
    ),
    (
        30,
        "leadership and membership: make/demote leader, join approve/decline, add/remove, leave",
        c30_membership.run_membership,
        [],
        ["leader_user", "membership_community"],
    ),
    (
        35,
        "settings profiles: delete default, clear, ownership, community defaults",
        c35_settings_profiles.run_settings_profiles,
        ["acct_user", "leader_user", "membership_community"],
        [],
    ),
    (
        31,
        "processors: bench/pre/post upload, edit, re-process, job pre/post, re-post-process, delete in use",
        c31_processors.run_processors,
        ["community", "space", "owner_user"],
        ["proc_job", "proc_ids"],
    ),
    (
        32,
        "TPTP: convert vs dump, include() dependencies, download bytes, edit, deleting a needed axiom (A7)",
        c32_tptp.run_tptp,
        ["space", "bench_processor"],
        [],
    ),
    (
        33,
        "solver lifecycle: download bytes, configurations, rebuild, link vs copy, recycle/restore/delete",
        c33_solver_lifecycle.run_solver_lifecycle,
        ["space"],
        ["solver_c33"],
    ),
    (
        34,
        "link/unlink/copy of benchmarks, solvers and jobs; subspace removal with and without recycling; orphans",
        c34_linking.run_linking,
        ["space", "job_single", "benchmarks", "owner_user"],
        [],
    ),
    (
        36,
        "job extras: name/description, priority, add job pairs (F9/F10 closure evidence), anonymous link, "
        "matrix, deleting a running job (L2)",
        c36_job_extras.run_job_extras,
        ["benchmarks", "fixture_configs", "space", "post_processor"],
        ["job_extra"],
    ),
    (
        37,
        "user deletion: cascades, personal space subtree, files, session, and a running job",
        c37_user_deletion.run_user_deletion,
        ["space", "bench_processor"],
        [],
    ),
    (
        38,
        "solver state under $HOME: a pair whose solver writes ~/.cache completes (K8s read-only root, #249)",
        c38_home_writable.run_home_writable,
        ["benchmarks", "space", "post_processor"],
        [],
    ),
    (
        39,
        "upload dependency errors: named reason, escaped page, invisible root refused",
        c39_upload_dependency_errors.run_dependency_errors,
        ["space", "bench_processor", "owner_user"],
        [],
    ),
    (
        40,
        "upload by URL (#139): worker download, completion, refusals",
        c40_upload_by_url.run_url_upload,
        ["space", "bench_processor", "owner_user"],
        [],
    ),
    (
        41,
        "StarexecCommand client: login, benchmark upload of a local archive",
        c41_command_client.run_command_client,
        ["space", "bench_processor", "owner_user"],
        [],
    ),
    (
        42,
        "multi-item replies: two benchmarks, two solvers, StarexecCommand copy, two-job XML",
        c42_multi_item_copy.run_multi_item_copy,
        ["owner_user", "owner_personal_space"],
        [],
    ),
    (
        43,
        "upload form permissions become the created subspaces' default permissions",
        c43_upload_space_permissions.run_upload_space_permissions,
        ["space", "bench_processor", "owner_user"],
        [],
    ),
    (
        44,
        "StarexecCommand client: getjobpairs, pushspacexml, setspacepublic refusal, pushsolver set=",
        c44_command_client_more.run_command_client_more,
        ["owner_user", "owner_personal_space"],
        [],
    ),
    (
        22,
        "app log checks: #202 stats save error, container creation (Podman only)",
        c00_deployment.run_container_logs,
        [],
        [],
    ),
]


def _lifecycle(cap, ctx):
    c14_lifecycle.run_pause_resume(cap, ctx)
    c14_lifecycle.run_rerun(cap, ctx)
    c14_lifecycle.run_kill(cap, ctx)


CAPABILITIES = [
    (n, name, fn if fn else _lifecycle, needs, creates)
    for n, name, fn, needs, creates in CAPABILITIES
]
BY_NUMBER = {c[0]: c for c in CAPABILITIES}

VALID_BUCKETS = ("setup", "parallel", "k8s", "exclusive", "teardown")

_BUCKET_GROUPS = {
    "setup": (0, 1, 3, 4, 5, 6, 7, 8),
    "teardown": (22,),
    "parallel": (2, 9, 15, 18, 20, 27, 28, 29, 30, 35, 39, 40, 41, 42, 43, 44),
    "k8s": (10, 11, 12, 13, 19, 21, 23, 24, 25, 26, 31, 32, 33, 36, 37, 38),
    # c17 asserts exact users.disk_size deltas on owner_user, so it must run exclusively
    "exclusive": (14, 16, 17, 34),
}

CONCURRENCY_BUCKETS = {
    number: bucket
    for bucket, numbers in _BUCKET_GROUPS.items()
    for number in numbers
}

# Explicit ordering edges beyond state needs/creates:
# - c14 mutates/reruns job_single, so it must wait for readers c11, c12, c15, c20.
# - c15 and c20 read job_single produced by c10.
# - c24 reruns job_pipeline pairs, so it must run after c25 inspects attributes/totals.
AFTER = {
    14: (11, 12, 15, 20),
    15: (10,),
    20: (10,),
    24: (25,),
}


def bucket_for(number):
    """Return the concurrency bucket name for capability ``number``."""
    if number not in CONCURRENCY_BUCKETS:
        raise KeyError(f"unknown capability number for concurrency bucket: {number!r}")
    return CONCURRENCY_BUCKETS[number]


def after_for(number):
    """Return the tuple of explicit prerequisite capability numbers for ``number``."""
    if number not in BY_NUMBER:
        raise KeyError(f"unknown capability number: {number!r}")
    return AFTER.get(number, ())


def _validate_registry():
    """Verify CAPABILITIES, CONCURRENCY_BUCKETS, and AFTER are consistent."""
    cap_numbers = [c[0] for c in CAPABILITIES]
    if len(cap_numbers) != len(BY_NUMBER):
        raise ValueError("duplicate capability number in CAPABILITIES")

    grouped = [n for nums in _BUCKET_GROUPS.values() for n in nums]
    if len(grouped) != len(set(grouped)):
        raise ValueError("duplicate capability number in _BUCKET_GROUPS")

    known = set(BY_NUMBER)
    unknown_buckets = set(CONCURRENCY_BUCKETS) - known
    missing_buckets = known - set(CONCURRENCY_BUCKETS)
    if unknown_buckets or missing_buckets:
        raise ValueError(
            f"CONCURRENCY_BUCKETS mismatch: unknown={sorted(unknown_buckets)}, "
            f"missing={sorted(missing_buckets)}"
        )

    invalid_buckets = {b for b in CONCURRENCY_BUCKETS.values() if b not in VALID_BUCKETS}
    if invalid_buckets:
        raise ValueError(f"invalid bucket names: {sorted(invalid_buckets)}")

    for num, deps in AFTER.items():
        if num not in known:
            raise ValueError(f"unknown capability {num} in AFTER")
        unknown_deps = set(deps) - known
        if unknown_deps:
            raise ValueError(f"unknown AFTER prerequisites for {num}: {sorted(unknown_deps)}")
        if num in deps:
            raise ValueError(f"capability {num} cannot depend on itself in AFTER")


_validate_registry()
