#!/bin/bash
#
# WAR File Size Analysis Script
# Analyzes the StarExec WAR file to identify large dependencies and optimization opportunities
#
# Usage:
#   ./scripts/analyze-war-size.sh
#   ./scripts/analyze-war-size.sh --extract  # Extract and keep WAR contents
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
WAR_FILE="starexec-app/target/starexec.war"
WORK_DIR="/tmp/war-analysis-$$"
KEEP_EXTRACTED=false

# Parse arguments
if [[ "$1" == "--extract" ]]; then
    KEEP_EXTRACTED=true
fi

# Helper functions
log_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

log_success() {
    echo -e "${GREEN}✓${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "${RED}✗${NC} $1"
}

format_size() {
    numfmt --to=iec-i --suffix=B "$1" 2>/dev/null || echo "$1 bytes"
}

# Check if WAR file exists
if [[ ! -f "$WAR_FILE" ]]; then
    log_error "WAR file not found: $WAR_FILE"
    log_info "Please run 'mvn clean package' first"
    exit 1
fi

log_info "Analyzing StarExec WAR file: $WAR_FILE"
echo

# Create temporary working directory
mkdir -p "$WORK_DIR"
trap "rm -rf $WORK_DIR" EXIT

# Display WAR file size
war_size=$(stat -f%z "$WAR_FILE" 2>/dev/null || stat -c%s "$WAR_FILE" 2>/dev/null)
log_success "WAR file size: $(format_size $war_size)"
echo

# Extract WAR
log_info "Extracting WAR file..."
cd "$WORK_DIR"
unzip -q "$OLDPWD/$WAR_FILE"
cd "$OLDPWD"
log_success "Extraction complete"
echo

# === WAR Structure Analysis ===
echo -e "${BLUE}=== WAR Structure ===${NC}"

# Analyze top-level directories
log_info "Top-level directory sizes:"
for dir in "$WORK_DIR"/*; do
    if [[ -d "$dir" ]]; then
        dir_name=$(basename "$dir")
        size=$(du -sb "$dir" 2>/dev/null | awk '{print $1}')
        echo "  $(format_size $size) - $dir_name"
    fi
done
echo

# === WEB-INF/lib Analysis (Biggest contributor) ===
echo -e "${BLUE}=== WEB-INF/lib Analysis (Dependencies) ===${NC}"

lib_dir="$WORK_DIR/WEB-INF/lib"
if [[ -d "$lib_dir" ]]; then
    lib_count=$(find "$lib_dir" -name "*.jar" | wc -l)
    lib_total=$(du -sb "$lib_dir" 2>/dev/null | awk '{print $1}')

    log_success "Total libraries: $lib_count JAR files"
    log_success "Total library size: $(format_size $lib_total)"
    echo

    log_info "Top 20 largest libraries:"
    echo "  Size          JAR Name"
    echo "  ────────────  ────────────────────────────────────────"
    find "$lib_dir" -name "*.jar" -exec ls -l {} \; | \
        awk '{print $5, $NF}' | \
        sort -rn | \
        head -20 | \
        while read size file; do
            jar_name=$(basename "$file")
            echo "  $(printf '%-12s' "$(format_size $size)")  $jar_name"
        done
    echo
fi

# === WEB-INF/classes Analysis ===
echo -e "${BLUE}=== WEB-INF/classes Analysis (Compiled Code) ===${NC}"

classes_dir="$WORK_DIR/WEB-INF/classes"
if [[ -d "$classes_dir" ]]; then
    class_count=$(find "$classes_dir" -name "*.class" | wc -l)
    classes_total=$(du -sb "$classes_dir" 2>/dev/null | awk '{print $1}')

    log_success "Total classes: $class_count .class files"
    log_success "Total compiled code size: $(format_size $classes_total)"
    echo

    # Show largest packages
    log_info "Top 10 largest packages (by directory):"
    echo "  Size          Package"
    echo "  ────────────  ────────────────────────────────────────"
    du -sb "$classes_dir"/*/ 2>/dev/null | \
        sort -rn | \
        head -10 | \
        while read size dir; do
            pkg_name=$(basename "$dir")
            echo "  $(printf '%-12s' "$(format_size $size)")  $pkg_name"
        done
    echo
fi

# === Static Assets Analysis ===
echo -e "${BLUE}=== Static Assets Analysis ===${NC}"

css_size=0
js_size=0
img_size=0
other_size=0

# CSS
if [[ -d "$WORK_DIR/css" ]]; then
    css_size=$(du -sb "$WORK_DIR/css" 2>/dev/null | awk '{print $1}')
fi

# JavaScript
if [[ -d "$WORK_DIR/js" ]]; then
    js_size=$(du -sb "$WORK_DIR/js" 2>/dev/null | awk '{print $1}')
fi

# Images
for img_dir in "$WORK_DIR/images" "$WORK_DIR/img" "$WORK_DIR/assets"; do
    if [[ -d "$img_dir" ]]; then
        img_size=$((img_size + $(du -sb "$img_dir" 2>/dev/null | awk '{print $1}')))
    fi
done

# Other resources
resources_dir="$WORK_DIR/META-INF"
if [[ -d "$resources_dir" ]]; then
    other_size=$(du -sb "$resources_dir" 2>/dev/null | awk '{print $1}')
fi

echo "  CSS files:        $(format_size $css_size)"
echo "  JavaScript files: $(format_size $js_size)"
echo "  Images:           $(format_size $img_size)"
echo "  Configuration:    $(format_size $other_size)"
echo

# === Summary Statistics ===
echo -e "${BLUE}=== WAR File Summary ===${NC}"

total_libs=$(du -sb "$lib_dir" 2>/dev/null | awk '{print $1}' || echo 0)
total_classes=$(du -sb "$classes_dir" 2>/dev/null | awk '{print $1}' || echo 0)

echo "Breakdown by component:"
echo "  Libraries (WEB-INF/lib):  $(format_size $total_libs) ($((total_libs * 100 / war_size))%)"
echo "  Compiled code (classes):  $(format_size $total_classes) ($((total_classes * 100 / war_size))%)"
echo "  Static assets (CSS/JS):   $(format_size $((css_size + js_size))) ($(($(( css_size + js_size )) * 100 / war_size))%)"
echo "  Configuration/Metadata:   $(format_size $((other_size + img_size))) ($(($(( other_size + img_size )) * 100 / war_size))%)"
echo

# === Optimization Suggestions ===
echo -e "${BLUE}=== Optimization Suggestions ===${NC}"

# Check for duplicate or conflicting dependencies
log_info "Checking for potential issues..."

# Look for multiple versions of same library
echo
log_warning "Checking for duplicate libraries (different versions):"
find "$lib_dir" -name "*.jar" -exec basename {} \; 2>/dev/null | \
    sed 's/-[0-9]\+\.[0-9]\+.*//' | \
    sort | uniq -d | \
    while read dup; do
        echo "  Found multiple versions of: $dup"
        find "$lib_dir" -name "${dup}-*.jar" -exec basename {} \; | \
            while read jar; do
                size=$(ls -l "$lib_dir/$jar" 2>/dev/null | awk '{print $5}')
                echo "    → $(format_size $size) - $jar"
            done
    done

echo

# Large JAR warnings
log_warning "JAR files larger than 5MB (consider if necessary):"
find "$lib_dir" -name "*.jar" -size +5M 2>/dev/null | \
    while read jar; do
        size=$(ls -l "$jar" 2>/dev/null | awk '{print $5}')
        name=$(basename "$jar")
        echo "  $(format_size $size) - $name"
    done

echo
echo -e "${GREEN}Analysis complete!${NC}"

# Show what was extracted
if [[ "$KEEP_EXTRACTED" == "true" ]]; then
    log_info "WAR contents extracted to: $WORK_DIR"
    log_info "Contents preserved for further analysis"
    log_info "Remember to clean up: rm -rf $WORK_DIR"
else
    log_info "Temporary files cleaned up"
fi
