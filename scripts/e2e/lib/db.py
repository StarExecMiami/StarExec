"""SQL through the configured exec template. Every query and its rows are recorded as check evidence."""

import subprocess


class DBError(Exception):
    pass


def literal(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


class DB:
    def __init__(self, argv, recorder):
        self.argv = list(argv)
        self.recorder = recorder

    def sql(self, statement, record=True, timeout=60):
        p = subprocess.run(
            self.argv, input=statement, text=True, capture_output=True, timeout=timeout
        )
        if p.returncode:
            # stderr can echo connection details; keep only the first psql ERROR line.
            first = next(
                (l for l in p.stderr.splitlines() if "ERROR" in l), "psql failed"
            )
            if record:
                self.recorder.sql(statement, None, error=first[:300])
            raise DBError(first[:300])
        out = p.stdout.strip()
        if record:
            self.recorder.sql(statement, out)
        return out

    def rows(self, statement, record=True):
        out = self.sql(statement, record=record)
        return [line.split("|") for line in out.splitlines()] if out else []

    def scalar(self, statement, record=True):
        out = self.sql(statement, record=record)
        return out.splitlines()[0] if out else None

    def int(self, statement, record=True):
        v = self.scalar(statement, record=record)
        return int(v) if v not in (None, "") else None
