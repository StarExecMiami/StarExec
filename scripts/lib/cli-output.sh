# Shared POSIX sh presentation. Sourcing this file has no side effects.
# Human messages always use stderr; requested data remains with the caller.
# Plain text is intentional, including on TTYs (no terminal detection needed).

# Print informational text ($*) to stderr.
ui_info() { printf '%s\n' "$*" >&2; }
# Print a verified result ($*) to stderr.
ui_success() { printf 'OK: %s\n' "$*" >&2; }
# Print a recoverable failure or safety warning ($*) to stderr.
ui_warn() { printf 'Warning: %s\n' "$*" >&2; }
# Print a failure ($*) to stderr; the caller owns its exit policy.
ui_error() { printf 'Error: %s\n' "$*" >&2; }
# Print optional technical detail ($*) and succeed even when disabled.
ui_detail() {
    if [ "${VERBOSE:-0}" = "1" ]; then
        printf '  %s\n' "$*" >&2
    fi
    return 0
}

# Run one external mutation (argv), preserving stdin, stderr and exit status.
# Never wrap shell functions, scripts, sub-makes, queries or generated data.
# A subshell confines variables and cleanup traps to this invocation.
ui_run() (
    if [ "${VERBOSE:-0}" = "1" ]; then
        "$@" >&2
    else
        _ui_stdout=$(mktemp "${TMPDIR:-/tmp}/starexec-cli.XXXXXXXX") || {
            ui_error "Could not create a temporary file for command output."
            exit 1
        }
        trap 'rm -f -- "$_ui_stdout"' 0
        trap 'exit 129' 1
        trap 'exit 130' 2
        trap 'exit 143' 15
        if "$@" >"$_ui_stdout"; then
            _ui_status=0
        else
            _ui_status=$?
            if ! cat "$_ui_stdout" >&2; then
                ui_warn "Could not replay command stdout."
            fi
        fi
        exit "$_ui_status"
    fi
)
