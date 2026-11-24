"""Harness parameters and the guards that keep it off anything that is not a disposable deployment.

A config is a JSON file (see e2e/config.example.json). Nothing in it is a secret: database access is
an argv template for an exec into the database container or pod, where psql authenticates locally,
and the template is rejected if it carries a password.
"""

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

# Hostnames and namespaces of live production systems. Refused whatever the allowlist says.
PRODUCTION_HOST_PATTERNS = [
    r"quokka",
    r"se-m1",
    r"starexec\.org$",
    r"miami\.edu$",
    r"uiowa\.edu$",
]
# Also refused: the date-named 2026-09-15/17 K8s stacks, which the user has told us not to touch.
# Their names are 8 decimal digits, so the hex pattern below would otherwise accept them.
PRODUCTION_NAMESPACES = {
    "starexec",
    "starexec-jobs",
    "sq-20260915-k8s",
    "sq-20260917-k8s",
}
SAFE_NAMESPACES = {"starexec-dev"}
# Disposable local-microk8s qualification stacks are named after the commit they run, e.g.
# sq-f4911235-k8s. Only that exact shape is accepted.
SAFE_NAMESPACE_PATTERN = re.compile(r"^sq-[0-9a-f]{8}-k8s$")
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
SECRET_MARKERS = re.compile(r"(?i)(password|passwd|pgpassword|secret|token=)")
VALID_MODES = ("fresh", "upgrade")
VALID_FIXTURE_MODES = ("reproducible-offline", "scientific")


class ConfigError(Exception):
    pass


@dataclass
class Config:
    backend: str
    base_url: str
    deployment_name: str
    db_exec: list
    queue_id: object  # an int, or "auto": discover the single ACTIVE queue by SQL
    commit: str
    image: str
    report_dir: Path
    mode: str = "fresh"
    image_digest: str = None
    fixture_mode: str = "reproducible-offline"
    app_exec: list = None
    app_logs: list = (
        None  # argv template; "{since}" is replaced by an RFC3339 timestamp
    )
    app_data_root: str = "/app/data"
    allowed_hosts: list = field(default_factory=list)
    smtp_capture: dict = None
    community_id: int = None
    admin_user_id: int = 1
    enable_browser: bool = False
    timeouts: dict = field(default_factory=dict)
    source: str = None

    def timeout(self, name, default):
        return int(self.timeouts.get(name, default))

    def summary(self):
        """What goes into every report: identifying fields only, never the exec templates' internals."""
        return {
            "backend": self.backend,
            "mode": self.mode,
            "base_url": self.base_url,
            "deployment_name": self.deployment_name,
            "queue_id": self.queue_id,
            "commit": self.commit,
            "image": self.image,
            "image_digest": self.image_digest,
            "fixture_mode": self.fixture_mode,
            "db_exec_program": self.db_exec[:2],
            "app_exec_program": (self.app_exec or [])[:2] or None,
            "smtp_capture": (self.smtp_capture or {}).get("kind"),
            "enable_browser": self.enable_browser,
            "config_file": self.source,
        }


def _namespace(argv):
    for i, a in enumerate(argv):
        if a in ("-n", "--namespace") and i + 1 < len(argv):
            return argv[i + 1]
        if a.startswith("--namespace="):
            return a.split("=", 1)[1]
    return None


def check_exec_template(name, argv):
    if (
        not isinstance(argv, list)
        or not argv
        or not all(isinstance(a, str) for a in argv)
    ):
        raise ConfigError(f"{name} must be a non-empty argv list of strings")
    joined = " ".join(argv)
    if SECRET_MARKERS.search(joined):
        raise ConfigError(
            f"{name} must not carry credentials; use the container's local authentication"
        )
    if argv[0] in ("kubectl", "microk8s", "oc") or "kubectl" in argv[:3]:
        ns = _namespace(argv)
        if ns is None:
            raise ConfigError(
                f"{name}: a kubectl template must name its namespace explicitly"
            )
        if ns in PRODUCTION_NAMESPACES or not (
            ns in SAFE_NAMESPACES or SAFE_NAMESPACE_PATTERN.match(ns)
        ):
            raise ConfigError(
                f"{name}: namespace {ns!r} is not an allowed disposable namespace"
            )
    for token in argv:
        for pat in PRODUCTION_HOST_PATTERNS:
            if re.search(pat, token, re.I):
                raise ConfigError(f"{name}: refers to a production system ({token!r})")


def check_base_url(url, allowed):
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ConfigError("base_url must be an http(s) URL")
    host = parts.hostname.lower()
    for pat in PRODUCTION_HOST_PATTERNS:
        if re.search(pat, host, re.I):
            raise ConfigError(f"base_url host {host!r} is a production system")
    if host not in LOCAL_HOSTS and host not in [h.lower() for h in allowed]:
        raise ConfigError(
            f"base_url host {host!r} is neither local nor in allowed_hosts"
        )
    if not parts.path.rstrip("/"):
        raise ConfigError(
            "base_url must include the application context path, e.g. /starexec"
        )


def is_disposable(cfg):
    """Disposable means: named sq-*, reached on a local or allowlisted host, with guarded exec templates."""
    return cfg.deployment_name.startswith("sq-")


def load(path):
    raw = json.loads(Path(path).read_text())
    required = [
        "backend",
        "base_url",
        "deployment_name",
        "db_exec",
        "commit",
        "image",
        "report_dir",
    ]
    missing = [k for k in required if k not in raw]
    if missing:
        raise ConfigError(f"config is missing {missing}")
    if raw["backend"] not in ("local", "podman", "kubernetes"):
        raise ConfigError("backend must be local, podman or kubernetes")
    mode = raw.get("mode", "fresh")
    if mode not in VALID_MODES:
        raise ConfigError(f"mode must be one of {VALID_MODES}")
    fixture_mode = raw.get("fixture_mode", "reproducible-offline")
    if fixture_mode not in VALID_FIXTURE_MODES:
        raise ConfigError(f"fixture_mode must be one of {VALID_FIXTURE_MODES}")
    if not re.fullmatch(r"[0-9a-f]{7,40}", raw["commit"]):
        raise ConfigError("commit must be a hex SHA")
    check_base_url(raw["base_url"], raw.get("allowed_hosts", []))
    check_exec_template("db_exec", raw["db_exec"])
    if raw.get("app_logs"):
        check_exec_template("app_logs", raw["app_logs"])
    if raw.get("app_exec"):
        check_exec_template("app_exec", raw["app_exec"])
    report_dir = Path(raw["report_dir"])
    if not report_dir.is_absolute():
        report_dir = (Path(path).resolve().parent / report_dir).resolve()
    cfg = Config(
        backend=raw["backend"],
        mode=mode,
        image_digest=raw.get("image_digest"),
        fixture_mode=fixture_mode,
        base_url=raw["base_url"].rstrip("/"),
        deployment_name=raw["deployment_name"],
        db_exec=raw["db_exec"],
        queue_id=(
            int(raw["queue_id"])
            if str(raw.get("queue_id", "auto")) != "auto"
            else "auto"
        ),
        commit=raw["commit"],
        image=raw["image"],
        report_dir=report_dir,
        app_exec=raw.get("app_exec"),
        app_logs=raw.get("app_logs"),
        app_data_root=raw.get("app_data_root", "/app/data"),
        allowed_hosts=raw.get("allowed_hosts", []),
        smtp_capture=raw.get("smtp_capture"),
        community_id=raw.get("community_id"),
        admin_user_id=int(raw.get("admin_user_id", 1)),
        enable_browser=bool(raw.get("enable_browser", False)),
        timeouts=raw.get("timeouts", {}),
        source=str(Path(path).resolve()),
    )
    if not is_disposable(cfg):
        raise ConfigError(
            "deployment_name must start with sq- (disposable qualification deployments only)"
        )
    return cfg
