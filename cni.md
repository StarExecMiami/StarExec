Summary
- make start (alias for make deploy-podman) runs podman play kube render.yaml to create a pod (see Makefile:93 and Makefile:600-606).
- Podman (via netavark) uses the host CNI plugins / libcni to configure container networking when podman play kube creates the pod.
- When the system CNI plugin binaries are CNI plugins version >= 0.4.0 (and/or corresponding libcni behavior), Podman/netavark/libcni on the host may receive outputs or new fields/verbs it does not expect (the v0.4.0 release introduced spec changes such as a VERSION command, an args field and version negotiation changes). This mismatch causes the CNI invocation step to fail and podman play kube to fail — which makes make start fail.
In short: the root cause is an incompatibility between the installed CNI plugins (>= v0.4.0) and the runtime tooling Podman/netavark/libcni that podman play kube uses to run CNI plugins. The Makefile is not at fault — it calls podman play kube — the failure happens inside Podman/CNI.
Evidence from this repo
- Makefile starts deployment with Podman and calls podman play kube render.yaml (see Makefile:93 and Makefile:600-606).
- Helper scripts ensure pod infra image before calling podman play kube (scripts/ensure-pause-image.sh).
- There are no CNI-specific code paths in this repo; the failure is in the host container/network layer invoked by the Makefile.
Why CNI >= 0.4.0 breaks things (concise technical cause)
- CNI v0.4.0 bundled changes to the CNI spec and plugin behavior (added VERSION verb, args handling and multi-version reporting). Plugins and libcni started exposing additional/changed JSON fields and commands.
- Runtimes (Podman/netavark) and their linked libcni expect a certain plugin interface and result JSON shape. If the runtime or its libcni is older (or otherwise incompatible), the new plugin behavior can lead to:
  - JSON unmarshal/parse errors when the plugin returns fields the runtime code doesn't expect, or
  - unexpected response to new verbs (e.g., VERSION, STATUS, GC) causing the runtime to treat the plugin as failing, or
  - different nftables/iptables behavior in portmap/ipmasq plugins causing networking setup to fail.
- The practical effect: podman play kube fails to configure networking for the pod, and the make start target aborts.
(You can confirm the above from the CNI v0.4.0 release notes: it rolled spec/behavior changes and added VERSION/args/reporting features.)
How to reproduce & gather diagnostic information (what to run on the host)
Run these (I did not execute them; they are the steps to run locally):
1. Check the failing command and see Podman errors:
   - podman play kube render.yaml (run from repository root). Capture full output.
2. Collect versions:
   - podman --version
   - podman info (look for networking backend and netavark version)
   - netavark --version (if available) or dnf/apt list installed | grep netavark
   - ls /opt/cni/bin (or wherever your CNI plugins live) and check plugin binary filenames
   - For each plugin binary: "$PLUGIN" --version or "$PLUGIN" version (some plugins support --version; many are just binaries).
   - rpm -qa | grep cni or dpkg -l | grep cni to see installed package versions.
3. Inspect logs for why CNI failed:
   - journalctl -u podman -b or journalctl -u podman.service (systemd) around the time you ran the podman play kube command.
   - Podman debugging output: podman --log-level=debug play kube render.yaml to see exact CNI invocation and errors.
   - podman logs for any infra container messages.
4. Try a minimal CNI ADD invocation (advanced): create a trivial netconf and exercise plugin with libcni to see parse errors — useful if you maintain/test the host runtime.
Fixes / workarounds
Choose one of these depending on your environment constraints:
1. Upgrade Podman/netavark/libcni to a version that supports CNI plugins >= 0.4.0
   - Best long-term fix: upgrade Podman (and underlying netavark/libcni) to a version that vendors/supports the newer CNI spec. This removes the interface mismatch.
   - After upgrade, re-run podman play kube.
2. Pin / downgrade CNI plugin binaries to a version < 0.4.0
   - If upgrading Podman is not possible, install an older set of CNI plugins compatible with the runtime (deploy plugin binaries from an earlier release, e.g. 0.3.x series). Place them in your CNI plugin directory (commonly /opt/cni/bin).
   - This is a quicker workaround but not ideal long-term.
3. Use Podman configuration flags / environment to force a compatible libcni or plugin path
   - Some environments allow you to point Podman/netavark to a specific CNI plugin dir or use a matching libcni; consult your Podman/netavark packaging docs.
4. If the failure is specific to one plugin (portmap/ipmasq) due to iptables→nftables differences, inspect plugin logs and consider:
   - Installing iptables-nft/legacy compatibility packages
   - Upgrading netfilter tooling to match the plugin backend expectations
Suggested next steps I can run for you (pick any)
- I can run the diagnostic commands and paste outputs:
  - podman --version && podman info
  - podman --log-level=debug play kube render.yaml (to capture the exact error)
  - ls -l /opt/cni/bin and for p in /opt/cni/bin/*; do echo $p; $p --version 2>&1 || true; done
- Or I can draft exact commands and a short checklist for your ops/host team to run if you prefer to run them locally.
Would you like me to collect the host Podman/CNI/netavark versions and the podman play kube debug output now? If yes, tell me I have your permission to run the diagnostic commands.
