"""44: StarexecCommand calls that did not match the server (beyond benchmark upload, see 41).

Runs the client named by E2E_COMMAND_CLASSES / E2E_COMMAND_CLASSPATH (see capability 41) in one
session, and checks each result against what the server recorded:

- 44.1 getjobpairs: the query named the ids as id[], unencoded, which Tomcat refuses (HTTP 400).
- 44.2 pushspacexml: the server answers an accepted upload with a redirect to its status page and
  no New_ID cookie, which the client read as an internal error.
- 44.3 setspacepublic: the server answers HTTP 200 with success=false when it refuses, which the
  client read as success.
- 44.4 pushsolver set=: the client parsed the parameter's name instead of its value, and sent the
  profile as "setting" where the server reads "settingId".
"""

import os
import tempfile
import zipfile

from lib.report import Blocked

from capabilities.c41_command_client import _session


def run_command_client_more(cap, ctx):
    uid, personal = ctx.state.need("owner_user", "owner_personal_space")
    pairs = ctx.db.scalar(
        "SELECT string_agg(id::text, ',' ORDER BY id) FROM (SELECT p.id FROM starexec.job_pairs p "
        f"JOIN starexec.jobs j ON j.id = p.job_id WHERE j.user_id = {int(uid)} AND p.status_code = 7 "
        "AND p.job_id = (SELECT p2.job_id FROM starexec.job_pairs p2 JOIN starexec.jobs j2 ON "
        f"j2.id = p2.job_id WHERE j2.user_id = {int(uid)} AND p2.status_code = 7 GROUP BY p2.job_id "
        "HAVING count(*) >= 2 ORDER BY p2.job_id DESC LIMIT 1) ORDER BY p.id LIMIT 2) x;",
        record=False,
    )
    xml_source = ctx.db.scalar(
        "SELECT max(c.child_id) FROM starexec.set_assoc c JOIN starexec.spaces s ON s.id = c.child_id "
        f"JOIN starexec.user_assoc u ON u.space_id = s.id AND u.user_id = {int(uid)} "
        "JOIN starexec.permissions p ON p.id = u.permission AND p.is_leader;",
        record=False,
    )
    foreign = ctx.db.scalar(
        "SELECT min(s.id) FROM starexec.spaces s WHERE NOT s.public_access AND s.id > 1 AND NOT EXISTS ("
        "SELECT 1 FROM starexec.user_assoc u JOIN starexec.permissions p ON p.id = u.permission "
        f"WHERE u.space_id = s.id AND u.user_id = {int(uid)} AND p.is_leader);",
        record=False,
    )
    profile = ctx.db.scalar(
        "SELECT min(id) FROM starexec.default_settings;", record=False
    )
    solver_fx = ctx.fx.get("files", {}).get("fixture-solver.zip")
    if not (pairs and xml_source and foreign and profile and solver_fx):
        raise Blocked(
            f"missing inputs: pairs={pairs} xml_source={xml_source} foreign={foreign} "
            f"profile={profile} solver={bool(solver_fx)}"
        )

    work = tempfile.mkdtemp(prefix="c44-")
    pairs_zip = os.path.join(work, "pairs.zip")
    space_zip = os.path.join(work, "space.zip")
    solvers_before = ctx.db.int(
        f"SELECT count(*) FROM starexec.solver_assoc WHERE space_id = {int(personal)};"
    )
    xml_jobs_before = ctx.db.int("SELECT count(*) FROM starexec.space_xml_uploads;")
    chunks, _ = _session(
        ctx,
        uid,
        [
            f"getjobpairs id={pairs} out={pairs_zip}",
            f"getspacexml id={int(xml_source)} out={space_zip}",
            f"pushspacexml f={space_zip} id={int(personal)}",
            f"setspacepublic id={int(foreign)} hier=",
            f"pushsolver f={solver_fx['path']} id={int(personal)} n=c44-solver set={int(profile)}",
        ],
    )

    def out(i):
        return chunks[2 + i] if len(chunks) > 2 + i else ""

    entries = []
    if os.path.isfile(pairs_zip):
        try:
            with zipfile.ZipFile(pairs_zip) as z:
                entries = z.namelist()
        except zipfile.BadZipFile:
            entries = ["<not a zip>"]
    cap.check(
        "1",
        "getjobpairs downloads the output of the listed pairs",
        "getjobpairs with two pair ids of one finished job prints no error and writes a zip of their"
        " output",
        {"client_output": "no ERROR", "zip_entries": ">= 2"},
        {"pairs": pairs, "client_output": out(0)[:300], "zip_entries": len(entries)},
        "ERROR" not in out(0) and len(entries) >= 2,
        [],
    )

    xml_jobs_after = ctx.db.int("SELECT count(*) FROM starexec.space_xml_uploads;")
    cap.check(
        "2",
        "pushspacexml reports an upload the server accepted",
        "getspacexml writes the space's XML; pushspacexml of it prints no error, and the server"
        " records one new space XML upload",
        {"getspacexml": "no ERROR", "pushspacexml": "no ERROR", "new_xml_uploads": 1},
        {
            "source_space": xml_source,
            "getspacexml_output": out(1)[:200],
            "space_zip_written": os.path.isfile(space_zip),
            "pushspacexml_output": out(2)[:300],
            "new_xml_uploads": xml_jobs_after - xml_jobs_before,
        },
        "ERROR" not in out(1)
        and os.path.isfile(space_zip)
        and "ERROR" not in out(2)
        and xml_jobs_after - xml_jobs_before == 1,
        [],
    )

    still_private = ctx.db.scalar(
        f"SELECT NOT public_access FROM starexec.spaces WHERE id = {int(foreign)};",
        record=False,
    )
    cap.check(
        "3",
        "setspacepublic reports a refusal as an error",
        "setspacepublic on a space the user does not lead prints an error, and the space stays private",
        {"client_output": "ERROR ...", "still_private": "t"},
        {
            "space": foreign,
            "client_output": out(3)[:300],
            "still_private": still_private,
        },
        "ERROR" in out(3) and still_private == "t",
        [],
    )

    solvers_after = ctx.db.int(
        f"SELECT count(*) FROM starexec.solver_assoc WHERE space_id = {int(personal)};"
    )
    cap.check(
        "4",
        "pushsolver with a settings profile uploads the solver",
        "pushsolver with set=<profile id> prints no error and the solver appears in the space",
        {"client_output": "no ERROR", "new_solvers": 1},
        {
            "profile": profile,
            "client_output": out(4)[:300],
            "new_solvers": solvers_after - solvers_before,
        },
        "ERROR" not in out(4) and solvers_after - solvers_before == 1,
        [],
    )
