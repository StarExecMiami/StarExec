"""Static validation and concurrent execution of the E2E capability DAG."""

from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
import sys
import threading
import time

from .report import Capability


class DAGValidationError(ValueError):
    """Raised when capability NEEDS, CREATES, AFTER, or bucket metadata is invalid."""


@dataclass
class CapabilityDAG:
    """Validated dependency graph over the selected capability numbers."""

    order: list[int]
    by_number: dict[int, tuple]
    buckets: dict[int, str]
    preds: dict[int, set[int]]
    succs: dict[int, set[int]]
    producers: dict[str, int]
    warnings: list[str] = field(default_factory=list)


def _find_cycle(order: list[int], preds: dict[int, set[int]], succs: dict[int, set[int]]) -> list[int]:
    """Return one concrete directed cycle among nodes remaining after Kahn pruning."""
    in_degree = {n: len(preds[n]) for n in order}
    queue = deque(n for n in order if in_degree[n] == 0)
    visited = set()
    while queue:
        cur = queue.popleft()
        visited.add(cur)
        for nxt in sorted(succs[cur]):
            in_degree[nxt] -= 1
            if in_degree[nxt] == 0:
                queue.append(nxt)

    remaining = [n for n in order if n not in visited]
    if not remaining:
        return []

    remaining_set = set(remaining)
    state = {}  # 0=visiting, 1=done
    stack = []

    def dfs(node: int) -> list[int]:
        state[node] = 0
        stack.append(node)
        for nxt in sorted(s for s in succs[node] if s in remaining_set):
            if nxt not in state:
                found = dfs(nxt)
                if found:
                    return found
            elif state[nxt] == 0:
                idx = stack.index(nxt)
                return stack[idx:] + [nxt]
        stack.pop()
        state[node] = 1
        return []

    for start in remaining:
        if start not in state:
            cycle = dfs(start)
            if cycle:
                return cycle
    return remaining + [remaining[0]]


def build_dag(
    capabilities,
    selected=None,
    seeded_keys=(),
    buckets=None,
    after_map=None,
) -> CapabilityDAG:
    """Validate metadata and construct the capability DAG.

    Combines ``needs`` -> ``creates`` edges, explicit ``after`` edges, and the
    ``teardown`` phase barrier (all non-teardown capabilities precede ``teardown``).
    """
    caps_list = list(capabilities)
    by_number = {}
    for entry in caps_list:
        num = entry[0]
        if num in by_number:
            raise DAGValidationError(f"duplicate capability number {num}")
        by_number[num] = entry

    if selected is None:
        order = [entry[0] for entry in caps_list]
    else:
        order = []
        seen = set()
        for num in selected:
            if num not in by_number:
                raise DAGValidationError(f"unknown capability number {num}")
            if num not in seen:
                order.append(num)
                seen.add(num)

    selected_set = set(order)
    seeded_set = set(seeded_keys or ())
    bucket_lookup = buckets or {}
    after_lookup = after_map or {}

    resolved_buckets: dict[int, str] = {}
    producers: dict[str, int] = {}
    for num in order:
        _, _, _, _, creates = by_number[num]
        bucket = bucket_lookup(num) if callable(bucket_lookup) else bucket_lookup.get(num, "parallel")
        resolved_buckets[num] = bucket
        for key in creates:
            if key in producers:
                raise DAGValidationError(
                    f"duplicate creates key {key!r} declared by capabilities {producers[key]} and {num}"
                )
            producers[key] = num

    preds: dict[int, set[int]] = {num: set() for num in order}
    succs: dict[int, set[int]] = {num: set() for num in order}
    warnings: list[str] = []

    def add_edge(src: int, dst: int) -> None:
        if src not in preds or dst not in preds:
            return
        if src == dst:
            raise DAGValidationError(f"capability {src} cannot depend on itself")
        preds[dst].add(src)
        succs[src].add(dst)

    for num in order:
        _, _, _, needs, _ = by_number[num]
        for key in needs:
            if key in producers:
                add_edge(producers[key], num)
            elif key not in seeded_set:
                raise DAGValidationError(
                    f"capability {num} requires {key!r}, which has no producer in the selected capabilities or seeded state"
                )

        explicit_preds = after_lookup(num) if callable(after_lookup) else after_lookup.get(num, ())
        for pred in explicit_preds:
            if pred not in by_number:
                raise DAGValidationError(f"capability {num} has unknown AFTER target {pred}")
            if pred == num:
                raise DAGValidationError(f"capability {num} cannot depend on itself in AFTER")
            if pred not in selected_set:
                warnings.append(
                    f"ignoring AFTER edge {pred} -> {num}: capability {pred} is not in selected subset"
                )
                continue
            add_edge(pred, num)

    teardown_nodes = [n for n in order if resolved_buckets[n] == "teardown"]
    non_teardown_nodes = [n for n in order if resolved_buckets[n] != "teardown"]
    for t_node in teardown_nodes:
        for p_node in non_teardown_nodes:
            add_edge(p_node, t_node)

    cycle = _find_cycle(order, preds, succs)
    if cycle:
        raise DAGValidationError(
            "cycle detected in capability DAG: " + " -> ".join(map(str, cycle))
        )

    return CapabilityDAG(
        order=order,
        by_number=by_number,
        buckets=resolved_buckets,
        preds=preds,
        succs=succs,
        producers=producers,
        warnings=warnings,
    )


def run_dag(
    dag: CapabilityDAG,
    ctx,
    workers: int = 1,
    k8s_job_slots: int = 2,
    readiness_fn=None,
) -> dict:
    """Execute ``dag`` concurrently with bounded worker threads and bucket rules."""
    if workers < 1:
        raise ValueError(f"workers must be >= 1 (got {workers})")
    if k8s_job_slots < 1:
        raise ValueError(f"k8s_job_slots must be >= 1 (got {k8s_job_slots})")

    for warning in dag.warnings:
        print(f"warning: {warning}", file=sys.stderr, flush=True)

    run = getattr(ctx.run, "_run", ctx.run)
    state = ctx.state
    rank = {num: idx for idx, num in enumerate(dag.order)}

    pending_preds = {num: set(dag.preds[num]) for num in dag.order}
    ready = sorted((num for num in dag.order if not pending_preds[num]), key=rank.get)
    completed = set()
    durations: dict[int, float] = {}

    cond = threading.Condition()
    io_lock = threading.Lock()
    active_total = 0
    active_k8s = 0
    active_exclusive = False
    stop_requested = False

    def _run_one(num: int) -> None:
        nonlocal active_total, active_k8s, active_exclusive, stop_requested
        _, name, fn, _, creates = dag.by_number[num]
        bucket = dag.buckets[num]
        t0 = time.monotonic()

        scoped_state = state.for_capability(num, creates)
        worker_recorder = run.recorder.fork()
        worker_ctx = ctx.fork_for_worker(scoped_state, worker_recorder)
        status = "BLOCKED"
        try:
            if readiness_fn is not None:
                code = readiness_fn(worker_ctx)
                if code != 200:
                    with cond:
                        if not stop_requested:
                            stop_requested = True
                            with run._lock:
                                run.data["stopped"] = {
                                    "before_capability": num,
                                    "readiness": code,
                                }
                                run.save()
                            with io_lock:
                                print(
                                    f"[{num}] not started: readiness returned {code}; stopping the run",
                                    flush=True,
                                )
                    return

            with io_lock:
                print(f"[{num}] {name}", flush=True)
            cap = Capability(run, num, name, worker_ctx, recorder=worker_recorder)
            error = None
            try:
                fn(cap, worker_ctx)
            except Exception as exc:  # noqa: BLE001 - capability failures are recorded in report
                error = exc
            status = cap.close(error)
            with io_lock:
                print(f"[{num}] {status}", flush=True)
        finally:
            worker_ctx.close()
            dur = round(time.monotonic() - t0, 3)
            with cond:
                durations[num] = dur
                active_total -= 1
                if bucket == "exclusive":
                    active_exclusive = False
                elif bucket == "k8s":
                    active_k8s -= 1
                completed.add(num)
                for succ in dag.succs[num]:
                    pending_preds[succ].discard(num)
                    if not pending_preds[succ] and succ not in completed:
                        ready.append(succ)
                ready.sort(key=rank.get)
                cond.notify_all()

    wall_start = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as executor:
        with cond:
            total_nodes = len(dag.order)
            while len(completed) < total_nodes:
                if stop_requested:
                    if active_total == 0:
                        break
                    cond.wait()
                    continue

                dispatched_any = False
                if not active_exclusive and ready:
                    ready_exclusive = [n for n in ready if dag.buckets[n] == "exclusive"]
                    if ready_exclusive:
                        if active_total == 0:
                            chosen = ready_exclusive[0]
                            ready.remove(chosen)
                            active_exclusive = True
                            active_total += 1
                            executor.submit(_run_one, chosen)
                            dispatched_any = True
                        # Drain active tasks before admitting more work so exclusive never starves.
                    else:
                        still_ready = []
                        for num in ready:
                            if active_total >= workers:
                                still_ready.append(num)
                                continue
                            b = dag.buckets[num]
                            if b == "k8s" and active_k8s >= k8s_job_slots:
                                still_ready.append(num)
                                continue
                            if b == "k8s":
                                active_k8s += 1
                            active_total += 1
                            executor.submit(_run_one, num)
                            dispatched_any = True
                        ready = still_ready

                if not dispatched_any and len(completed) < total_nodes:
                    cond.wait()

    wall_clock_s = round(time.monotonic() - wall_start, 3)
    sum_capability_s = round(sum(durations.values()), 3)
    return {
        "workers": workers,
        "k8s_job_slots": k8s_job_slots,
        "wall_clock_s": wall_clock_s,
        "sum_capability_s": sum_capability_s,
        "durations": durations,
    }
