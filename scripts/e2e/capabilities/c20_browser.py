"""20 (optional, off by default): browser check of a Primary stage-0 conflicts link.
Requires enable_browser in the config and the Python playwright package with a Chromium build;
otherwise BLOCKED. The browser logs in with a freshly generated disposable password."""

from urllib.parse import parse_qs, urljoin, urlsplit

from lib.report import Blocked


def run_browser(cap, ctx):
    if not ctx.cfg.enable_browser:
        cap.blocked("1", "job page DOM", "enable_browser is false in the config")
        return
    try:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
        from playwright.sync_api import sync_playwright
    except ImportError:
        cap.blocked(
            "1", "job page DOM", "the playwright Python package is not installed"
        )
        return
    job, uid = ctx.state.need("job_single", "owner_user")
    email = ctx.db.scalar(
        f"SELECT email FROM starexec.users WHERE id={uid};", record=False
    )
    password = ctx.set_password(uid)
    base = ctx.cfg.base_url
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto(base + "/secure/index.jsp")
        page.fill("input[name=j_username]", email)
        page.fill("input[name=j_password]", password)
        page.press("input[name=j_password]", "Enter")
        page.wait_for_load_state("networkidle")
        page.goto(base + f"/secure/details/job.jsp?id={job}")
        page.wait_for_load_state("networkidle")
        stage_selector = page.locator("#solverSummaryStageSelector")
        selector_visible = stage_selector.is_visible()
        page.evaluate(
            """() => {
                window.__c20SolverSummaryDraws = 0;
                window.jQuery("#solveTbl").on("draw.dt", () => {
                    window.__c20SolverSummaryDraws += 1;
                });
            }"""
        )

        def is_primary_stats_response(response):
            path_parts = urlsplit(response.url).path.rstrip("/").split("/")
            return (
                response.request.method == "POST"
                and "solvers/pagination" in response.url
                and len(path_parts) >= 2
                and path_parts[-2] == "0"
            )

        stats_response = None
        draw_count = 0
        try:
            with page.expect_response(
                is_primary_stats_response, timeout=30_000
            ) as stats_info:
                if selector_visible:
                    stage_selector.select_option("0")
                else:
                    # One-stage jobs hide this selector and force stage 1. Dispatch its documented
                    # change event explicitly so this remains a narrow Primary routing check.
                    stage_selector.evaluate(
                        """el => {
                            el.value = "0";
                            el.dispatchEvent(new Event("change", { bubbles: true }));
                        }"""
                    )
            stats_response = stats_info.value
            if stats_response.status == 200:
                page.wait_for_function(
                    "window.__c20SolverSummaryDraws > 0", timeout=30_000
                )
                draw_count = page.evaluate("window.__c20SolverSummaryDraws")
        except PlaywrightTimeoutError:
            pass
        stats_ok = (
            stats_response is not None
            and stats_response.status == 200
            and draw_count > 0
        )
        ctx.run.recorder.note(
            "requested Primary stage 0 through the solver-summary change path; "
            f"selector_visible={selector_visible}, "
            f"stats_http={stats_response.status if stats_response else None}, draws={draw_count}"
        )
        job_page_url = page.url
        links = (
            page.eval_on_selector_all(
                "a[href*='conflictingBenchmarks.jsp']",
                "els => els.map(e => ({href: e.getAttribute('href'), text: e.textContent.trim()}))",
            )
            if stats_ok
            else []
        )
        ctx.run.recorder.note(f"browser saw {len(links)} conflicts links")

        primary_links = []
        for link in links:
            query = parse_qs(urlsplit(link["href"]).query)
            if query.get("stageNumber", [None])[0] != "0":
                continue
            try:
                displayed_count = int(link["text"])
            except ValueError:
                continue
            if displayed_count > 0:
                primary_links.append((link, query, displayed_count))

        results = []
        for link, query, displayed_count in primary_links:
            response = page.goto(urljoin(job_page_url, link["href"]))
            landed = urlsplit(page.url)
            landed_query = parse_qs(landed.query)
            # DataTables scrollY clones the classed table for its fixed header.
            class_table_count = page.locator("table.conflictsTable").count()
            body_table_count = page.locator("table.conflictsTable tbody").count()
            listed_count = page.locator(
                "table.conflictsTable tbody tr"
            ).count()
            results.append(
                {
                    "config_id": query.get("configId", [None])[0],
                    "displayed": displayed_count,
                    "route": {
                        "path": landed.path,
                        "job_id": landed_query.get("jobId", [None])[0],
                        "config_id": landed_query.get("configId", [None])[0],
                        "stage_number": landed_query.get("stageNumber", [None])[0],
                    },
                    "http": response.status if response is not None else None,
                    "class_table_count": class_table_count,
                    "body_table_count": body_table_count,
                    "table_present": class_table_count > 0 and body_table_count > 0,
                    "listed": listed_count,
                }
            )
        browser.close()
    cap.check(
        "1",
        "Primary summary AJAX redraw completes",
        "selecting Primary issues the stage-0 solver-summary request and redraws the summary table",
        {"http": 200, "draws": ">0"},
        {
            "http": stats_response.status if stats_response else None,
            "draws": draw_count,
            "selector_visible": selector_visible,
        },
        stats_ok,
        ["job_stats"],
        extra={
            "selection": "visible selector" if selector_visible else "forced hidden selector change"
        },
    )
    if not stats_ok:
        return
    if not links:
        raise Blocked(
            "the job page rendered no conflicts links (table not loaded or anonymised)"
        )
    if not primary_links:
        raise Blocked(
            "the job page has no non-zero stage-0 Primary conflicts link to exercise; "
            "the expected conflict fixture or link is absent"
        )

    def matches(result):
        route = result["route"]
        return (
            route["path"].endswith("/secure/details/conflictingBenchmarks.jsp")
            and route["job_id"] == str(job)
            and route["config_id"] == result["config_id"]
            and route["stage_number"] == "0"
            and result["http"] == 200
            and result["table_present"]
            and result["listed"] == result["displayed"]
        )

    cap.check(
        "2",
        "Primary conflicts link routes to stage 0 and lists its displayed count (#194/#255)",
        "after the solver-summary Primary selection and AJAX redraw, each rendered non-zero "
        "Primary conflicts link routes to this job/configuration "
        "with stageNumber=0, "
        "returns HTTP 200, and lists exactly the number of benchmarks shown in the link",
        [
            {"config_id": item[1].get("configId", [None])[0], "displayed": item[2]}
            for item in primary_links
        ],
        results,
        all(matches(result) for result in results),
        ["conflicts_link_stage", "conflicting_benchmarks_page"],
        extra={
            "stage_selector_visible": selector_visible,
            "selection": (
                "visible selector"
                if selector_visible
                else "forced hidden selector change"
            ),
            "stats_http": stats_response.status,
        },
    )
