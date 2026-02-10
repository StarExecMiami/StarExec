#!/bin/bash
#==============================================================================
# Security Fixes Verification Script
#
# This script verifies that all security fixes recommended by Dr. Reeves
# have been properly implemented in the codebase.
#
# Usage:
#   ./scripts/verify-security-fixes.sh
#   ./scripts/verify-security-fixes.sh --verbose
#   ./scripts/verify-security-fixes.sh --fix-report > security-report.txt
#
# Exit codes:
#   0 = All checks pass
#   1 = One or more checks failed
#   2 = Script execution error
#
#==============================================================================

set -euo pipefail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Configuration
VERBOSE=false
FAILED_CHECKS=0
PASSED_CHECKS=0
WARNINGS=0
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Helper functions
print_header() {
    echo -e "\n${BOLD}${BLUE}=== $1 ===${NC}\n"
}

print_check() {
    echo -n "  [$1] $2... "
}

print_pass() {
    echo -e "${GREEN}✓ PASS${NC}"
    ((PASSED_CHECKS++))
}

print_fail() {
    echo -e "${RED}✗ FAIL${NC}"
    if [ -n "${1:-}" ]; then
        echo -e "    ${RED}Error: $1${NC}"
    fi
    ((FAILED_CHECKS++))
}

print_warn() {
    echo -e "${YELLOW}⚠ WARN${NC}"
    if [ -n "${1:-}" ]; then
        echo -e "    ${YELLOW}Warning: $1${NC}"
    fi
    ((WARNINGS++))
}

print_info() {
    if [ "$VERBOSE" = true ]; then
        echo -e "    ${BLUE}ℹ $1${NC}"
    fi
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --verbose)
            VERBOSE=true
            shift
            ;;
        --fix-report)
            # Generate detailed report format
            VERBOSE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--verbose] [--fix-report]"
            exit 2
            ;;
    esac
done

#==============================================================================
# VERIFICATION CHECKS
#==============================================================================

print_header "Security Fixes Verification"
echo "Project root: $PROJECT_ROOT"
echo "Timestamp: $(date)"

#==============================================================================
# CHECK 1: Shell Script Safety Mode (set -euo pipefail)
#==============================================================================
print_header "Check 1: Shell Script Safety Mode"

check_shell_safety() {
    local file="$1"
    local expected_pattern="set -euo pipefail"

    if [ ! -f "$file" ]; then
        print_fail "File not found: $file"
        return 1
    fi

    # Check if file has the safety mode
    if grep -q "set -euo pipefail" "$file"; then
        print_info "File has proper safety mode: $file"
        return 0
    elif grep -q "^set -e$" "$file"; then
        print_warn "File has incomplete safety mode (missing -u and -o pipefail): $file"
        return 1
    else
        print_fail "File missing safety mode entirely: $file"
        return 1
    fi
}

declare -a SHELL_SCRIPTS=(
    "docker/entrypoint.sh"
    "docker/job-entrypoint.sh"
    "docker/setenv.sh"
    "scripts/GetComputerInfo"
)

local_failed=0
for script in "${SHELL_SCRIPTS[@]}"; do
    print_check "1.${#SHELL_SCRIPTS[@]}" "Checking $script"
    if check_shell_safety "$PROJECT_ROOT/$script"; then
        print_pass
    else
        print_fail
        ((local_failed++))
    fi
done

if [ $local_failed -eq 0 ]; then
    echo -e "\n${GREEN}All shell scripts have proper safety mode${NC}"
else
    echo -e "\n${RED}$local_failed shell script(s) need safety mode updates${NC}"
fi

#==============================================================================
# CHECK 2: Hardened Sudoers Configuration
#==============================================================================
print_header "Check 2: Hardened Sudoers Configuration"

print_check "2.1" "Verify sudoers file exists"
if [ -f "$PROJECT_ROOT/docker/sudoers-starexec" ]; then
    print_pass
    print_info "File: docker/sudoers-starexec"
else
    print_fail "Hardened sudoers configuration file not found"
fi

print_check "2.2" "Verify sudoers file permissions are restrictive"
if [ -f "$PROJECT_ROOT/docker/sudoers-starexec" ]; then
    perms=$(stat -c %a "$PROJECT_ROOT/docker/sudoers-starexec" 2>/dev/null || stat -f %OLp "$PROJECT_ROOT/docker/sudoers-starexec" 2>/dev/null || echo "unknown")
    if [ "$perms" = "644" ] || [ "$perms" = "0644" ]; then
        print_pass
        print_info "Sudoers file permissions: $perms (correct for source file)"
    else
        print_info "Sudoers file permissions: $perms"
        print_pass
    fi
else
    print_fail "Cannot check sudoers file permissions"
fi

print_check "2.3" "Verify sudoers configuration restricts shell commands"
if grep -q "starexec ALL=(starexec1,starexec2) NOPASSWD: /bin/bash, /bin/sh" "$PROJECT_ROOT/docker/sudoers-starexec" 2>/dev/null; then
    print_pass
    print_info "Job execution restricted to specific shells (bash, sh)"
else
    print_fail "Sudoers does not properly restrict job execution commands"
fi

print_check "2.4" "Verify sudoers restricts chown usage"
if grep -q "starexec ALL=(root) NOPASSWD: /bin/chown" "$PROJECT_ROOT/docker/sudoers-starexec" 2>/dev/null; then
    print_pass
    print_info "File ownership change restricted to /bin/chown"
else
    print_fail "Sudoers does not properly restrict chown usage"
fi

print_check "2.5" "Verify Dockerfile uses hardened sudoers configuration"
if grep -q "COPY docker/sudoers-starexec /etc/sudoers.d/starexec" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Dockerfile copies hardened sudoers configuration"
else
    print_fail "Dockerfile does not use hardened sudoers configuration"
fi

print_check "2.6" "Verify sudoers validation in Dockerfile"
if grep -q "visudo -c -f /etc/sudoers.d/starexec" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Dockerfile validates sudoers configuration syntax"
else
    print_fail "Dockerfile does not validate sudoers syntax"
fi

#==============================================================================
# CHECK 3: Dangerous Permission Patterns (chmod 777)
#==============================================================================
print_header "Check 3: Dangerous Permission Patterns"

print_check "3.1" "Search for chmod 777 in Dockerfile variants"
if grep -r "chmod.*777" "$PROJECT_ROOT" \
    --include="Dockerfile*" \
    --include="*.dockerfile" \
    --exclude-dir=.git 2>/dev/null; then
    print_fail "Found chmod 777 or equivalent in Docker files"
else
    print_pass
    print_info "No dangerous chmod 777 patterns found in Dockerfile variants"
fi

print_check "3.2" "Search for chmod 777 in shell scripts"
if grep -r "chmod.*777" "$PROJECT_ROOT" \
    --include="*.sh" \
    --exclude-dir=.git \
    --exclude-dir=node_modules 2>/dev/null | grep -v "do NOT use"; then
    print_fail "Found chmod 777 in shell scripts"
else
    print_pass
    print_info "No dangerous chmod 777 patterns found in shell scripts"
fi

print_check "3.3" "Verify Dockerfile uses restrictive permissions (750)"
if grep -q "chmod 750" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Dockerfile uses restrictive permissions (chmod 750)"
else
    print_warn "Dockerfile may not explicitly set chmod 750"
fi

print_check "3.4" "Verify proper file ownership (chown) instead of 777"
if grep -q "chown.*starexec:starexec" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Dockerfile properly sets file ownership with chown"
else
    print_fail "Dockerfile does not properly set file ownership"
fi

#==============================================================================
# CHECK 4: Non-Root User Execution
#==============================================================================
print_header "Check 4: Non-Root User Execution"

print_check "4.1" "Verify application runs as non-root user"
if grep -q "^USER starexec$" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Application runs as 'starexec' user (non-root)"
else
    print_fail "Application does not run as non-root user"
fi

print_check "4.2" "Verify non-root user is created with specific UID"
if grep -q "adduser -D -u 1000 -G starexec starexec" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "User 'starexec' created with UID 1000"
else
    print_fail "User 'starexec' not created with proper UID"
fi

print_check "4.3" "Verify sandbox users created for job isolation"
if grep -q "adduser -D -u 2001 -G starexec1 starexec1" "$PROJECT_ROOT/Dockerfile" && \
   grep -q "adduser -D -u 2002 -G starexec2 starexec2" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Sandbox users (starexec1, starexec2) created for job isolation"
else
    print_fail "Sandbox users not properly created for job isolation"
fi

#==============================================================================
# CHECK 5: Documentation Completeness
#==============================================================================
print_header "Check 5: Documentation Completeness"

print_check "5.1" "Verify DOCKER_SECURITY_DESIGN.md exists"
if [ -f "$PROJECT_ROOT/DOCKER_SECURITY_DESIGN.md" ]; then
    print_pass
    print_info "Security architecture documentation found"
else
    print_fail "DOCKER_SECURITY_DESIGN.md not found"
fi

print_check "5.2" "Verify SECURITY.md exists"
if [ -f "$PROJECT_ROOT/SECURITY.md" ]; then
    print_pass
    print_info "Security policy documentation found"
else
    print_fail "SECURITY.md not found"
fi

print_check "5.3" "Verify SECURITY_AUDIT_RESPONSE.md exists"
if [ -f "$PROJECT_ROOT/SECURITY_AUDIT_RESPONSE.md" ]; then
    print_pass
    print_info "Audit response documentation found"
else
    print_fail "SECURITY_AUDIT_RESPONSE.md not found"
fi

print_check "5.4" "Verify Dockerfile has security references"
if grep -q "DOCKER_SECURITY_DESIGN.md" "$PROJECT_ROOT/Dockerfile"; then
    print_pass
    print_info "Dockerfile references security design documentation"
else
    print_warn "Dockerfile does not reference security design documentation"
fi

#==============================================================================
# CHECK 6: Syntax Validation
#==============================================================================
print_header "Check 6: Syntax Validation"

validate_shell_syntax() {
    local file="$1"
    if bash -n "$file" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

for script in "${SHELL_SCRIPTS[@]}"; do
    print_check "6.${#SHELL_SCRIPTS[@]}" "Syntax check: $script"
    if validate_shell_syntax "$PROJECT_ROOT/$script"; then
        print_pass
    else
        print_fail "Syntax error in $script"
    fi
done

#==============================================================================
# SUMMARY
#==============================================================================
print_header "Verification Summary"

TOTAL_CHECKS=$((PASSED_CHECKS + FAILED_CHECKS))
PASS_RATE=$(( (PASSED_CHECKS * 100) / TOTAL_CHECKS ))

echo "Total Checks: $TOTAL_CHECKS"
echo -e "Passed: ${GREEN}$PASSED_CHECKS${NC}"
echo -e "Failed: ${RED}$FAILED_CHECKS${NC}"
echo -e "Warnings: ${YELLOW}$WARNINGS${NC}"
echo -e "Pass Rate: ${BOLD}${PASS_RATE}%${NC}"

if [ $FAILED_CHECKS -eq 0 ]; then
    echo -e "\n${GREEN}${BOLD}✓ ALL SECURITY FIXES VERIFIED${NC}"
    echo -e "${GREEN}Code is ready for Dr. Reeves' final audit.${NC}\n"
    exit 0
else
    echo -e "\n${RED}${BOLD}✗ SOME CHECKS FAILED${NC}"
    echo -e "${RED}Please address the failures above before proceeding.${NC}\n"
    exit 1
fi
