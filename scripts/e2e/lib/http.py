"""HTTP sessions against the deployment. Evidence keeps method, path, status and selected JSON fields;
never cookies, headers, request bodies with passwords, or raw HTML."""

import json
from urllib.parse import urlsplit

import requests

COMMAND_HEADER = {
    "StarExecCommand": "StarExecCommand"
}  # CsrfFilter exemption (endpoint csrf_command_header)


class HTTPError(Exception):
    pass


def _summary(response, keep=None):
    item = {
        "method": response.request.method,
        "path": urlsplit(response.url).path,
        "query_keys": sorted(
            dict(
                p.split("=", 1) if "=" in p else (p, "")
                for p in (urlsplit(response.url).query or "").split("&")
                if p
            ).keys()
        ),
        "status": response.status_code,
        "redirects": [r.status_code for r in response.history],
        "redirected_to": urlsplit(response.url).path if response.history else None,
        "content_type": response.headers.get("Content-Type", "").split(";")[0],
        "bytes": len(response.content),
    }
    if keep and item["content_type"] == "application/json":
        try:
            data = response.json()
            item["json"] = (
                {k: data.get(k) for k in keep} if isinstance(data, dict) else None
            )
        except ValueError:
            pass
    return item


class Client:
    def __init__(self, base_url, recorder, label, command_header=True):
        self.base = base_url
        self.recorder = recorder
        self.label = label
        self.s = requests.Session()
        if command_header:
            self.s.headers.update(COMMAND_HEADER)

    def url(self, path):
        return self.base + path

    def request(self, method, path, keep=None, timeout=60, **kw):
        response = self.s.request(method, self.url(path), timeout=timeout, **kw)
        self.recorder.http(self.label, _summary(response, keep))
        return response

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, **kw):
        return self.request("POST", path, **kw)

    def json(self, response):
        try:
            return response.json()
        except ValueError:
            return None

    def alive(self):
        """Whether this client's cookie still names an authenticated session.

        Deliberately not recorded: it is the harness checking itself, not evidence about
        the deployment. The probe also resets the container's idle timer, which is what
        keeps a cached session usable between capabilities.
        """
        try:
            response = self.s.get(self.url("/services/session/logged-in"), timeout=30)
        except requests.RequestException:
            return False
        return response.status_code == 200 and response.text.strip() == "true"

    def login(self, username, password):
        # Endpoint login: form authentication; the index page establishes the session first.
        self.s.get(self.url("/secure/index.jsp"), timeout=30)
        self.s.post(
            self.url("/secure/j_security_check"),
            data={
                "j_username": username,
                "j_password": password,
                "cookieexists": "false",
            },
            timeout=30,
        )
        check = self.get("/services/session/logged-in", timeout=30)
        ok = check.status_code == 200 and check.text.strip() == "true"
        self.recorder.note(f"login {self.label}: {'ok' if ok else 'failed'}")
        return ok

    def close(self):
        """Close the underlying requests session."""
        if self.s is not None:
            self.s.close()

    def datatables(self, length=1000):
        """The DataTables form fields RESTHelpers reads (endpoint datatables_params)."""
        return {
            "sEcho": "1",
            "iDisplayStart": "0",
            "iDisplayLength": str(length),
            "sSearch": "",
            "iSortCol_0": "0",
            "sSortDir_0": "asc",
        }


def anonymous(base_url, recorder, label="anonymous"):
    return Client(base_url, recorder, label)
