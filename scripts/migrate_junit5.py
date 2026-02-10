#!/usr/bin/env python3
"""
JUnit 5 Migration Script

Automatically migrates JUnit 4 test files to JUnit 5 (Jupiter).

Usage:
    python3 migrate_junit5.py <file_path> [--dry-run] [--verbose]

Options:
    --dry-run   Preview changes without modifying files
    --verbose   Show detailed debug information
"""

import argparse
import os
import re
import shutil
import sys
from pathlib import Path
from typing import Dict, List, Tuple


# ANSI Color codes
class Colors:
    BLUE = "\033[0;34m"
    GREEN = "\033[0;32m"
    YELLOW = "\033[1;33m"
    RED = "\033[0;31m"
    NC = "\033[0m"


def log_info(msg: str):
    print(f"{Colors.BLUE}[INFO]{Colors.NC} {msg}")


def log_success(msg: str):
    print(f"{Colors.GREEN}[SUCCESS]{Colors.NC} {msg}")


def log_warning(msg: str):
    print(f"{Colors.YELLOW}[WARNING]{Colors.NC} {msg}")


def log_error(msg: str):
    print(f"{Colors.RED}[ERROR]{Colors.NC} {msg}")


def log_debug(msg: str, verbose: bool = False):
    if verbose:
        print(f"{Colors.BLUE}[DEBUG]{Colors.NC} {msg}")


def migrate_imports(content: str) -> Tuple[str, int]:
    """Migrate JUnit 4 import statements to JUnit 5."""
    changes = 0

    # Import statement mappings
    import_mappings = {
        r"import org\.junit\.Assert;": "import static org.junit.jupiter.api.Assertions.*;",
        r"import org\.junit\.Assert\.\*;": "import static org.junit.jupiter.api.Assertions.*;",
        r"import org\.junit\.Test;": "import org.junit.jupiter.api.Test;",
        r"import org\.junit\.Before;": "import org.junit.jupiter.api.BeforeEach;",
        r"import org\.junit\.After;": "import org.junit.jupiter.api.AfterEach;",
        r"import org\.junit\.BeforeClass;": "import org.junit.jupiter.api.BeforeAll;",
        r"import org\.junit\.AfterClass;": "import org.junit.jupiter.api.AfterAll;",
        r"import org\.junit\.Ignore;": "import org.junit.jupiter.api.Disabled;",
    }

    for old_pattern, new_import in import_mappings.items():
        if re.search(old_pattern, content):
            content = re.sub(old_pattern, new_import, content)
            changes += 1

    return content, changes


def migrate_annotations(content: str) -> Tuple[str, int]:
    """Migrate JUnit 4 annotations to JUnit 5."""
    changes = 0

    annotation_mappings = {
        r"@Before\b": "@BeforeEach",
        r"@After\b": "@AfterEach",
        r"@BeforeClass\b": "@BeforeAll",
        r"@AfterClass\b": "@AfterAll",
        r"@Ignore\b": "@Disabled",
    }

    for old_pattern, new_annotation in annotation_mappings.items():
        if re.search(old_pattern, content):
            content = re.sub(old_pattern, new_annotation, content)
            changes += 1

    return content, changes


def migrate_assertions(content: str) -> Tuple[str, int]:
    """Remove Assert. prefix from assertion calls."""
    changes = 0

    assertion_mappings = {
        r"Assert\.assertEquals": "assertEquals",
        r"Assert\.assertTrue": "assertTrue",
        r"Assert\.assertFalse": "assertFalse",
        r"Assert\.assertNull": "assertNull",
        r"Assert\.assertNotNull": "assertNotNull",
        r"Assert\.fail": "fail",
        r"Assert\.assertThrows": "assertThrows",
    }

    for old_pattern, new_assertion in assertion_mappings.items():
        if re.search(old_pattern, content):
            content = re.sub(old_pattern, new_assertion, content)
            changes += 1

    return content, changes


def migrate_test_methods(content: str) -> Tuple[str, int]:
    """Update test method visibility to package-private where appropriate."""
    changes = 0

    # Change "public void testXxx()" to "void testXxx()" for @Test methods
    # This pattern looks for @Test followed by public void
    pattern = r"(@Test\s+)public\s+(void\s+\w+\s*\()"
    if re.search(pattern, content):
        content = re.sub(pattern, r"\1\2", content)
        changes += 1

    # Also handle @BeforeEach, @AfterEach, etc.
    for annotation in [
        "@BeforeEach",
        "@AfterEach",
        "@BeforeAll",
        "@AfterAll",
        "@Disabled",
    ]:
        pattern = f"({annotation}\\s+)public\\s+(void\\s+\\w+\\s*\\()"
        if re.search(pattern, content):
            content = re.sub(pattern, r"\1\2", content)
            changes += 1

    return content, changes


def is_junit_test_file(content: str) -> bool:
    """Check if file contains JUnit 4 imports."""
    junit4_patterns = [
        r"import org\.junit\.Test",
        r"import org\.junit\.Assert",
        r"import org\.junit\.Before",
        r"@Test",
        r"@Before",
        r"@After",
    ]

    return any(re.search(pattern, content) for pattern in junit4_patterns)


def get_diff(original: str, modified: str) -> List[str]:
    """Generate a simple diff view of changes."""
    original_lines = original.split("\n")
    modified_lines = modified.split("\n")

    diff = []
    for i, (orig, mod) in enumerate(zip(original_lines, modified_lines), 1):
        if orig != mod:
            diff.append(f"Line {i}:")
            diff.append(f"  - {orig}")
            diff.append(f"  + {mod}")

    return diff


def migrate_file(file_path: str, dry_run: bool = False, verbose: bool = False) -> bool:
    """Migrate a single file from JUnit 4 to JUnit 5."""

    # Validate file exists
    if not os.path.isfile(file_path):
        log_error(f"File not found: {file_path}")
        return False

    if not file_path.endswith(".java"):
        log_error(f"File must be a Java file: {file_path}")
        return False

    log_info(f"Starting JUnit 5 migration for: {file_path}")

    if dry_run:
        log_warning("DRY RUN MODE - No changes will be made")

    # Read file
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            original_content = f.read()
    except Exception as e:
        log_error(f"Failed to read file: {e}")
        return False

    # Check if it's a JUnit test file
    if not is_junit_test_file(original_content):
        log_warning(
            "File does not appear to be a JUnit test file (no JUnit 4 imports found)"
        )
        return False

    # Create backup
    backup_path = f"{file_path}.backup"
    try:
        shutil.copy(file_path, backup_path)
        log_debug(f"Created backup: {backup_path}", verbose)
    except Exception as e:
        log_error(f"Failed to create backup: {e}")
        return False

    # Perform migrations
    modified_content = original_content
    total_changes = 0

    log_debug("Migrating import statements...", verbose)
    modified_content, changes = migrate_imports(modified_content)
    total_changes += changes
    log_debug(f"Import migrations: {changes} changes", verbose)

    log_debug("Migrating annotations...", verbose)
    modified_content, changes = migrate_annotations(modified_content)
    total_changes += changes
    log_debug(f"Annotation migrations: {changes} changes", verbose)

    log_debug("Migrating assertions...", verbose)
    modified_content, changes = migrate_assertions(modified_content)
    total_changes += changes
    log_debug(f"Assertion migrations: {changes} changes", verbose)

    log_debug("Updating test method visibility...", verbose)
    modified_content, changes = migrate_test_methods(modified_content)
    total_changes += changes
    log_debug(f"Test method migrations: {changes} changes", verbose)

    # Show diff
    if total_changes > 0:
        print()
        log_info("Showing changes:")
        print()
        diff = get_diff(original_content, modified_content)
        for line in diff[:20]:  # Show first 20 changes
            print(f"  {line}")
        if len(diff) > 20:
            print(f"  ... and {len(diff) - 20} more changes")
        print()
    else:
        log_warning("No changes detected. File may already be migrated.")

    if total_changes == 0:
        # Restore backup
        try:
            os.remove(backup_path)
        except:
            pass
        return True

    log_info(f"Total changes: {total_changes}")

    # Apply changes
    if dry_run:
        log_warning("Dry run mode: No changes applied")
        try:
            os.remove(backup_path)
        except:
            pass
        return True

    # Write migrated content
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(modified_content)
        log_success(f"Migration complete: {file_path}")
        log_info(f"Backup saved: {backup_path}")
        return True
    except Exception as e:
        log_error(f"Failed to write file: {e}")
        # Restore from backup on failure
        try:
            shutil.copy(backup_path, file_path)
            log_info("Restored from backup")
        except:
            pass
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Migrate JUnit 4 tests to JUnit 5",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 migrate_junit5.py path/to/TestFile.java
  python3 migrate_junit5.py path/to/TestFile.java --dry-run
  python3 migrate_junit5.py path/to/TestFile.java --verbose
        """,
    )

    parser.add_argument("file", help="Java test file to migrate")
    parser.add_argument(
        "--dry-run", action="store_true", help="Preview changes without modifying"
    )
    parser.add_argument(
        "--verbose", action="store_true", help="Show detailed debug information"
    )

    args = parser.parse_args()

    success = migrate_file(args.file, dry_run=args.dry_run, verbose=args.verbose)

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
