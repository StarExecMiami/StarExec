"""Independent recomputation of the job page's solver statistics from the database.

Two answers per (stage, configuration) row:
  * "as_coded": what the code at the commit under test should display, reproducing
    Jobs.addStageToSolverStats (endpoint stats_accumulate), JobPairs.isPairCorrect (pair_correct),
    the Status predicates (Status.java failed/resource/incomplete/statComplete) and
    SolverStats.getUnknown (stats_unknown), including the stage-0 primary row (stats_primary_row).
  * "intended": the same counts, except unknown = stat-complete stages that are neither correct,
    incorrect nor resource-out (#198). Where the two differ the displayed value is recorded against both.

Stage rows come from jobpair_stage_data; results from job_attributes on the SAME stage; expected results
from bench_attributes starexec-expected-result -- the joins GetJobPairStagesInJobSpaceHierarchy uses.
"""

import math

UNKNOWN_RESULT = "starexec-unknown"


def java_round(x):
    """Math.round: floor(x + 0.5), unlike Python's round-half-even."""
    return math.floor(x + 0.5)


# Status.java: StatCompleteness.COMPLETE members (7, 14-17, 25, 26); everything else INCOMPLETE.
STAT_COMPLETE = {7, 14, 15, 16, 17, 25, 26}


def failed(code):
    return 8 <= code <= 13 or code == 18 or 24 <= code <= 26


def resource(code):
    return 14 <= code <= 17


def incomplete(code):
    return code <= 6 or code >= 19


def is_pair_correct(code, result, expected):
    """JobPairs.isPairCorrect: 0 correct, 1 incorrect, 2 unknown, -1 not complete."""
    if code != 7:
        return -1
    if result == UNKNOWN_RESULT:
        return 2
    if expected is not None and expected != UNKNOWN_RESULT:
        return 0 if result == expected else 1
    return 2


class Acc:
    def __init__(self, stage, config_id, solver_id):
        self.stage, self.config_id, self.solver_id = stage, config_id, solver_id
        self.failed = self.resource = self.incomplete = self.complete = 0
        self.correct = self.incorrect = 0
        self.true_unknown = 0
        self.wall = self.cpu = 0.0

    def add(self, code, result, expected, wall, cpu, include_unknown):
        if failed(code):
            self.failed += 1
        elif resource(code):
            # Jobs.addStageToSolverStats at 5af7357b3 (:6983-6988, #198): a resource-out is also complete.
            self.resource += 1
            self.complete += 1
        elif incomplete(code):
            self.incomplete += 1
        elif code in STAT_COMPLETE:
            self.complete += 1
        c = is_pair_correct(code, result, expected)
        if c == 0:
            self.correct += 1
            self.wall += wall
            self.cpu += cpu
        elif c == 1:
            self.incorrect += 1
        elif c == 2:
            if include_unknown:
                self.wall += wall
                self.cpu += cpu
        if (
            code in STAT_COMPLETE
            and not failed(code)
            and not resource(code)
            and c not in (0, 1)
        ):
            self.true_unknown += 1

    def row(self, conflicts=None):
        return {
            "stage": self.stage,
            "config_id": self.config_id,
            "solved": f"{self.correct}/{self.complete}",
            "wrong": self.incorrect,
            "resourced": self.resource,
            "failed": self.failed,
            "unknown_as_coded": self.complete
            - (self.resource + self.correct + self.incorrect),
            "unknown_intended": self.true_unknown,
            "incomplete": self.incomplete,
            "time_x100": {
                "wall": java_round(self.wall * 100),
                "cpu": java_round(self.cpu * 100),
            },
            "conflicts": conflicts,
        }


def stage_rows_sql(job_id):
    return (
        "SELECT jp.id, jp.primary_jobpair_data, s.stage_number, s.status_code, coalesce(s.solver_id,0),"
        " coalesce(s.config_id,0), coalesce(s.wallclock,0), coalesce(s.cpu,0),"
        " coalesce((SELECT ja.attr_value FROM starexec.job_attributes ja WHERE ja.pair_id=jp.id"
        "   AND ja.stage_number=s.stage_number AND ja.attr_key='starexec-result' LIMIT 1),'<null>'),"
        " coalesce((SELECT ba.attr_value FROM starexec.bench_attributes ba WHERE ba.bench_id=jp.bench_id"
        "   AND ba.attr_key='starexec-expected-result' LIMIT 1),'<null>')"
        " FROM starexec.job_pairs jp JOIN starexec.jobpair_stage_data s ON s.jobpair_id=jp.id"
        f" WHERE jp.job_id={int(job_id)} ORDER BY jp.id, s.stage_number;"
    )


def expected_stats(rows, include_unknown=False):
    """rows: the output of stage_rows_sql. Returns {(stage, config): row} with stage 0 as the primary row."""
    accs = {}
    for pair, primary, stage, code, solver, config, wall, cpu, result, expected in rows:
        stage, code, solver, config = int(stage), int(code), int(solver), int(config)
        result = None if result == "<null>" else result
        expected = None if expected == "<null>" else expected
        if solver == 0:  # a no-op stage is skipped (processPairsToSolverStats)
            continue
        key = (stage, config)
        accs.setdefault(key, Acc(stage, config, solver)).add(
            code, result, expected, float(wall), float(cpu), include_unknown
        )
        if primary not in (None, "") and stage == int(primary):
            # Jobs.processPairsToSolverStats at 5af7357b3 (:7108, #206): the primary row accumulates its stage.
            accs.setdefault((0, config), Acc(0, config, solver)).add(
                code, result, expected, float(wall), float(cpu), include_unknown
            )
    return accs


def parse_displayed(aadata):
    """RESTHelpers.convertSolverStatsToJsonObject, long format: indexes per job.js SOLVER_ID..CONFIG_DELETED."""
    out = {}
    for r in aadata:
        solved = r[4]
        out[(int(r[6]), int(r[1]))] = {
            "stage": int(r[6]),
            "config_id": int(r[1]),
            "solver_id": int(r[0]),
            "solved": solved,
            "time_x100": r[5],
            "wrong": int(r[7]),
            "resourced": int(r[8]),
            "failed": int(r[9]),
            "unknown": int(r[10]),
            "incomplete": int(r[11]),
            "conflicts": r[12],
            "config_deleted": r[13],
        }
    return out
