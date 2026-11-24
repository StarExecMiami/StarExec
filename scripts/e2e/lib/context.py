"""Shared context: config, DB, state, fixtures, and logged-in sessions.

Passwords are generated per login and never stored. Setting one writes a bcrypt $2a hash into the
disposable deployment's users table, and only after two guards pass: the deployment is a disposable
sq-* one (config.load already refuses anything else) and the stored hash is itself a $2 bcrypt hash,
so an installation that uses another scheme is never locked out by a foreign hash.
"""

import secrets
import subprocess
import time

import bcrypt

from . import config as config_mod
from .db import DB, literal
from .http import Client
from .report import Blocked


class Context:
    def __init__(self, cfg, run, state, fixtures):
        self.cfg = cfg
        self.run = run
        self.state = state
        self.fx = fixtures
        self.db = DB(cfg.db_exec, run.recorder)
        self._sessions = {}

    # ------------------------------------------------------------------ sessions
    def set_password(self, user_id):
        if not config_mod.is_disposable(self.cfg):
            raise Blocked(
                "password reset refused: deployment is not a disposable sq-* deployment"
            )
        stored = self.db.scalar(
            f"SELECT substr(password,1,2) FROM starexec.users WHERE id={int(user_id)};",
            record=False,
        )
        if stored is None:
            raise Blocked(f"user {user_id} does not exist")
        if stored != "$2":
            raise Blocked(
                f"password reset refused: user {user_id}'s stored hash is not bcrypt ($2*)"
            )
        password = secrets.token_urlsafe(24)
        hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(prefix=b"2a")).decode()
        got = self.db.scalar(
            f"UPDATE starexec.users SET password={literal(hashed)} WHERE id={int(user_id)} RETURNING id;",
            record=False,
        )
        if got != str(user_id):
            raise Blocked(f"password reset for user {user_id} did not apply")
        self.run.recorder.note(
            f"disposable password set for user {user_id} (hash not recorded)"
        )
        return password

    def session(self, role, user_id=None, email=None, fresh=False):
        if role in self._sessions and not fresh:
            cached = self._sessions[role]
            if cached.alive():
                return cached
            # A full run logs in once per role and can then leave a session idle for
            # longer than the container keeps it. Sending the dead cookie makes the next
            # capability read an authentication failure as a product defect: on
            # 2026-09-19 a 35-minute idle admin session turned into "registration creates
            # no user" (C27) and "no processor row" (C31), both of which pass in
            # isolation. Log in again instead.
            self.run.recorder.note(f"session {role} was no longer live; logging in again")
            del self._sessions[role]
        if role == "admin":
            user_id = self.cfg.admin_user_id
        if email is None:
            email = self.db.scalar(
                f"SELECT email FROM starexec.users WHERE id={int(user_id)};",
                record=False,
            )
        client = Client(self.cfg.base_url, self.run.recorder, role)
        if not client.login(email, self.set_password(user_id)):
            raise Blocked(f"could not log in as {role} (user {user_id})")
        self._sessions[role] = client
        return client

    def admin(self):
        return self.session("admin")

    def owner(self):
        return self.session("owner", self.state.need("owner_user"))

    def second(self):
        return self.session("second", self.state.need("second_user"))

    # ------------------------------------------------------------------ app filesystem
    def app(self, *argv, input=None, timeout=60):
        if not self.cfg.app_exec:
            raise Blocked(
                "no app_exec template configured; file-level evidence is unavailable"
            )
        p = subprocess.run(
            list(self.cfg.app_exec) + list(argv),
            input=input,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        self.run.recorder.note(
            f"app_exec {' '.join(argv)[:200]} -> exit {p.returncode}"
        )
        return p.returncode, p.stdout

    # ------------------------------------------------------------------ queue and logs
    def queue(self):
        """The queue jobs are submitted to: the configured id, or the single ACTIVE queue found by SQL.

        Never assumes queue 1: on PodmanBackend all.q (1) is INACTIVE and PodmanBackend.initialize creates
        container.q. More than one ACTIVE queue with queue_id=auto is refused rather than guessed.
        """
        if self.cfg.queue_id != "auto":
            return int(self.cfg.queue_id)
        cached = self.state.get("queue")
        if cached:
            return int(cached)
        rows = self.db.rows("SELECT id, name, status FROM starexec.queues ORDER BY id;")
        self.state.fact("queue_table", rows)
        active = [r for r in rows if r[2] == "ACTIVE"]
        if len(active) != 1:
            raise Blocked(f"queue_id=auto needs exactly one ACTIVE queue; found {rows}")
        self.state.set("queue", int(active[0][0]))
        return int(active[0][0])

    def logs(self, since):
        """App log text since an RFC3339 timestamp, via the app_logs template; None when not configured."""
        if not self.cfg.app_logs:
            return None
        argv = [a.replace("{since}", since) for a in self.cfg.app_logs]
        p = subprocess.run(argv, capture_output=True, text=True, timeout=120)
        return p.stdout + p.stderr

    # ------------------------------------------------------------------ polling
    def wait(self, what, predicate, timeout, interval=1.0):
        start = time.monotonic()
        last = None
        while time.monotonic() - start < timeout:
            ok, value = predicate()
            if value != last:
                self.run.recorder.note(
                    f"wait {what}: {str(value)[:300]} at +{time.monotonic() - start:.1f}s"
                )
                last = value
            if ok:
                return True, value, round(time.monotonic() - start, 2)
            time.sleep(interval)
        return False, last, round(time.monotonic() - start, 2)

    def name(self, suffix):
        """A primitive name unique to this run: e2e<yyyymmddhhmmss>_<suffix>."""
        return (
            "e2e"
            + "".join(c for c in self.run.runid.split("-")[0] if c.isdigit())
            + "_"
            + suffix
        )
