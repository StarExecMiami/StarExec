# Troubleshooting Podman Socket Path Issues

This guide helps resolve common Podman socket path errors when deploying StarExec locally.

## Problem: "Podman socket path is not available"

If you see an error like:

```
✗ Podman socket path is not available: /run/user/1000/podman/podman.sock
Your UID: 1003
```

This means StarExec couldn't find your Podman socket. The socket path is auto-detected based on your user's UID, but the socket service needs to be running.

## Solution 1: Start the Podman Socket Service

The simplest fix for most cases:

```bash
# Enable and start the Podman socket service
systemctl --user enable --now podman.socket

# Verify it's running
systemctl --user status podman.socket

# Confirm the socket exists at your UID
ls -l /run/user/$(id -u)/podman/podman.sock
```

Then retry deployment:

```bash
make deploy-podman ENV=dev
```

## Solution 2: Handle Masked Podman Socket (School Networks)

On school-managed networks, the system may **mask** the Podman socket service to prevent users from starting it. You'll see:

```
⚠️  Podman socket service is masked by system (common on school-managed networks).
```

### Workaround: Copy SystemD Units to User Home

The system-level Podman service can't be started directly, but you can enable it for your user:

```bash
# Create user systemd directory
mkdir -p ~/.config/systemd/user/

# Copy the Podman service files from system to user
cp /usr/lib/systemd/user/podman.* ~/.config/systemd/user/

# Reload user systemd daemon
systemctl --user daemon-reload

# Enable and start the socket
systemctl --user enable --now podman.socket

# Verify it's running
systemctl --user status podman.socket
```

After these steps, verify the socket exists:

```bash
ls -l /run/user/$(id -u)/podman/podman.sock
```

Then retry deployment:

```bash
make deploy-podman ENV=dev
```

## Debugging: Verify Your Setup

Check your current user's UID:

```bash
id -u
# Output: 1003 (or whatever your UID is)
```

Verify the expected socket path:

```bash
XDG_RUNTIME_DIR="/run/user/$(id -u)"
echo "$XDG_RUNTIME_DIR/podman/podman.sock"
# Should output: /run/user/1003/podman/podman.sock
```

Check if Podman daemon is running:

```bash
podman ps
# If this fails, Podman itself isn't running
```

Check socket service status:

```bash
systemctl --user status podman.socket
# Should show: active (running)
```

## Understanding UID Detection

StarExec's `make deploy-podman` command automatically detects your user's UID and corrects hardcoded paths:

- **Values file hardcodes**: `/run/user/1000/podman/podman.sock`
- **Your actual UID**: `1003` (example)
- **Deployment detects mismatch** and automatically uses: `/run/user/1003/podman/podman.sock`

If you see a message like:

```
○ Detected UID mismatch: values file has /run/user/1000/ but your UID is 1003
○ Using corrected socket path: /run/user/1003/podman/podman.sock
```

This is **expected and working correctly**. The socket path has been auto-corrected.

## If All Else Fails

1. Verify Podman itself is installed and working:
   ```bash
   podman --version
   podman info
   ```

2. Check systemd user services are enabled:
   ```bash
   systemctl --user is-enabled podman.socket
   # Should output: enabled (or masked, which requires the workaround above)
   ```

3. Review deployment logs:
   ```bash
   make deploy-podman ENV=dev 2>&1 | tee deployment.log
   ```

4. Check if your user is in the correct groups (for rootless Podman):
   ```bash
   groups
   # Should include: podman (on some systems)
   ```

5. If the socket service won't start, check its logs:
   ```bash
   journalctl --user -u podman.socket -n 50
   ```

## Manual Socket Path Override

If you need to use a non-standard socket path, you can set an environment variable before deployment:

```bash
# Set your custom socket path (advanced)
# PODMAN_SOCKET_PATH="/custom/path/podman.sock" make deploy-podman ENV=dev
```

Note: This feature is for advanced use cases. The auto-detection should work in most scenarios.

## For IT/System Administrators

If you manage a shared system and want to allow users to run StarExec:

1. Install Podman and container tools as root
2. **Do NOT mask the podman.socket service** (`systemctl mask podman.socket`)
3. Ensure users have rootless Podman support enabled
4. Document the socket path location for your system

Users on your system will then be able to run `make deploy-podman` without additional setup beyond starting their user-level socket.
