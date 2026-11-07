package org.starexec.backend.storage;

import java.nio.file.Path;
import java.util.List;

/** Trusted pre-execution wait; release still requires current admission and runtime attestation. */
public final class BuildExecutionHold {
    public static final String READY = "STAREXEC_BUILD_HOLD_V1";
    public static final String RELEASE_SIGNAL = "USR2";
    private static final String PROGRAM = """
            set -euo pipefail
            export PATH=/usr/bin:/bin
            if [[ $# != 2 || ! "$1" =~ ^[1-9][0-9]{0,2}$ || "$1" -gt 300 || "$2" != /* || "$2" == / ]]; then
                exit 64
            fi
            released=false
            trap 'released=true' USR2
            trap ':' CONT USR1
            trap 'exit 143' TERM
            trap 'exit 130' INT
            trap 'exit 129' HUP
            printf '%s\\n' STAREXEC_BUILD_HOLD_V1
            for ((remaining=$1*10; remaining>0; --remaining)); do
                if [[ "$released" == true ]]; then break; fi
                /bin/sleep 0.1
            done
            if [[ "$released" != true ]]; then exit 75; fi
            trap - USR2 CONT USR1 TERM INT HUP
            exec /bin/bash --noprofile --norc -p -- "$2"
            """;

    private BuildExecutionHold() { }

    public static List<String> entrypoint(int waitSeconds) {
        if (waitSeconds < 1 || waitSeconds > 300) throw new IllegalArgumentException("Build hold must be bounded");
        return List.of("/bin/bash", "--noprofile", "--norc", "-p", "-c", PROGRAM, "--", Integer.toString(waitSeconds));
    }

    public static List<String> command(Path script) {
        if (script == null || !script.isAbsolute() || script.getParent() == null || !script.equals(script.normalize())) {
            throw new IllegalArgumentException("Build script must be an absolute normalized non-root path");
        }
        return List.of(script.toString());
    }
}
