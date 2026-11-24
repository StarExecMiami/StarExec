"""Ids created by capabilities, persisted so a single capability can be re-run against them.

A new run always writes its own state file. `--state <file>` seeds it from an earlier run; the source
file is never modified. Nothing secret is stored: passwords are regenerated on each login.
"""

import json
import os
from pathlib import Path

from .report import Blocked

# Capabilities numbered at or above this declare every key they write. Older ones are only held to
# "never write a key another capability creates", because some record undeclared facts (queue_table).
STRICT_FROM = 27


class StateWriteRefused(Exception):
    """A capability tried to write a state key it does not own."""


class State:
    def __init__(self, path, seed=None, owners=None):
        # owners: {key: (capability number, ...)} from the registry's `creates`. None disables the guard.
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
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2, sort_keys=True) + "\n")
        os.replace(tmp, self.path)

    def act_as(self, number, creates):
        """Every write until the next call is attributed to capability `number`."""
        self.acting = number
        self.acting_creates = tuple(creates)

    def _guard(self, key):
        if self.owners is None or self.acting is None:
            return
        owners = self.owners.get(key, ())
        if owners and self.acting not in owners:
            raise StateWriteRefused(
                f"capability {self.acting} may not write state key {key!r}: it belongs to capability"
                f" {', '.join(map(str, owners))}"
            )
        if self.acting >= STRICT_FROM and key not in self.acting_creates:
            raise StateWriteRefused(
                f"capability {self.acting} wrote undeclared state key {key!r}; add it to its creates"
            )

    def set(self, key, value):
        self._guard(key)
        self.data["ids"][key] = value
        self.save()

    def fact(self, key, value):
        self._guard(key)
        self.data["facts"][key] = value
        self.save()

    def get(self, key, default=None):
        return self.data["ids"].get(key, self.data["facts"].get(key, default))

    def need(self, *keys):
        missing = [k for k in keys if self.get(k) in (None, [], {})]
        if missing:
            raise Blocked(
                f"state has no {', '.join(missing)} (run the capability that creates it, or pass --state)"
            )
        return [self.get(k) for k in keys] if len(keys) > 1 else self.get(keys[0])
