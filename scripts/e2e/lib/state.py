"""Ids created by capabilities, persisted so a single capability can be re-run against them.

A new run always writes its own state file. `--state <file>` seeds it from an earlier run; the source
file is never modified. Nothing secret is stored: passwords are regenerated on each login.
"""

import json
import os
import tempfile
import threading
from pathlib import Path

from .report import Blocked

# Capabilities numbered at or above this declare every key they write. Older ones are only held to
# "never write a key another capability creates", because some record undeclared facts (queue_table).
STRICT_FROM = 27

_UNSET = object()


class StateWriteRefused(Exception):
    """A capability tried to write a state key it does not own."""


class ScopedState:
    """Per-capability view of State enforcing ownership without mutating shared acting state."""

    def __init__(self, state, number, creates):
        self._state = state
        self.number = number
        self.creates = tuple(creates)

    @property
    def path(self):
        return self._state.path

    @property
    def owners(self):
        return self._state.owners

    @property
    def acting(self):
        return self.number

    @property
    def acting_creates(self):
        return self.creates

    def set(self, key, value):
        self._state._write(
            "ids", key, value, acting=self.number, acting_creates=self.creates
        )

    def fact(self, key, value):
        self._state._write(
            "facts", key, value, acting=self.number, acting_creates=self.creates
        )

    def get(self, key, default=None):
        return self._state.get(key, default)

    def need(self, *keys):
        return self._state.need(*keys)

    def save(self):
        self._state.save()

    def for_capability(self, number, creates):
        return self._state.for_capability(number, creates)


class State:
    def __init__(self, path, seed=None, owners=None):
        # owners: {key: (capability number, ...)} from the registry's `creates`. None disables the guard.
        self._lock = threading.RLock()
        self.owners = owners
        self.acting = None
        self.acting_creates = ()
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            raise SystemExit(f"refusing to overwrite state {self.path}")
        self.data = {"seeded_from": str(seed) if seed else None, "ids": {}, "facts": {}}
        if seed:
            prior = json.loads(Path(seed).read_text())
            self.data["ids"] = prior.get("ids", {})
            self.data["facts"] = prior.get("facts", {})
        self.save()

    def save(self):
        with self._lock:
            payload = json.dumps(self.data, indent=2, sort_keys=True) + "\n"
            fd, tmp_name = tempfile.mkstemp(
                dir=self.path.parent,
                prefix=f".{self.path.name}.",
                suffix=".tmp",
            )
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(payload)
                os.replace(tmp_name, self.path)
            except BaseException:
                try:
                    os.unlink(tmp_name)
                except OSError:
                    pass
                raise

    def act_as(self, number, creates):
        """Every write until the next call is attributed to capability `number`."""
        with self._lock:
            self.acting = number
            self.acting_creates = tuple(creates)

    def for_capability(self, number, creates):
        """Return an isolated ScopedState bound to capability `number` and its `creates`."""
        return ScopedState(self, number, creates)

    def _guard(self, key, acting=_UNSET, acting_creates=_UNSET):
        with self._lock:
            eff_acting = self.acting if acting is _UNSET else acting
            eff_creates = (
                self.acting_creates
                if acting_creates is _UNSET
                else tuple(acting_creates)
            )
            if self.owners is None or eff_acting is None:
                return
            owners = self.owners.get(key, ())
            if owners and eff_acting not in owners:
                raise StateWriteRefused(
                    f"capability {eff_acting} may not write state key {key!r}: it belongs to capability"
                    f" {', '.join(map(str, owners))}"
                )
            if eff_acting >= STRICT_FROM and key not in eff_creates:
                raise StateWriteRefused(
                    f"capability {eff_acting} wrote undeclared state key {key!r}; add it to its creates"
                )

    def _write(self, kind, key, value, acting=_UNSET, acting_creates=_UNSET):
        with self._lock:
            self._guard(key, acting=acting, acting_creates=acting_creates)
            self.data[kind][key] = value
            self.save()

    def set(self, key, value):
        self._write("ids", key, value)

    def fact(self, key, value):
        self._write("facts", key, value)

    def get(self, key, default=None):
        with self._lock:
            return self.data["ids"].get(key, self.data["facts"].get(key, default))

    def need(self, *keys):
        with self._lock:
            missing = [k for k in keys if self.get(k) in (None, [], {})]
            if missing:
                raise Blocked(
                    f"state has no {', '.join(missing)} (run the capability that creates it, or pass --state)"
                )
            return [self.get(k) for k in keys] if len(keys) > 1 else self.get(keys[0])
