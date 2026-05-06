# Database Standards for StarExec

## Foreign Key Constraint Standards

### ON DELETE Clause Requirements

All foreign key constraints MUST have an explicit `ON DELETE` clause. The default behavior (RESTRICT) is not allowed.

### Appropriate ON DELETE Behaviors

Use the following guidelines to determine the appropriate `ON DELETE` behavior:

#### 1. ON DELETE CASCADE
Use when child records have no meaning without the parent record.
- **Examples**: 
  - `solver_pipelines.user_id → users.id` - Solver pipelines are owned by a user and should be removed when that user is deleted
  - `runscript_errors.job_pair_id → job_pairs.id` - Runscript errors should be deleted when job pair is deleted
  - `benchmarks.user_id → users.id` - Benchmarks should be deleted when user is deleted

#### 2. ON DELETE SET NULL
Use when child records can exist without the parent reference, but the reference should be cleared.
- **Examples**:
  - `spaces.default_permission → permissions.id` - Space can exist without default permission
  - `solvers.executable_type → executable_types.type_id` - Solver can exist without executable type

#### 3. ON DELETE RESTRICT / NO ACTION
Use when parent record should not be deleted if child records exist (prevents orphaned references).
- **Examples**:
  - `analytics_users.user_id → users.id` - Historical analytics should not silently disappear during user deletion
  - `analytics_users.event_id → analytics_events.event_id` - Analytics rows should not outlive the event they summarize
  - `logins.user_id → users.id` - Login history should be preserved even if user is deleted

#### 4. ON DELETE SET DEFAULT
Use when child rows must remain valid but should fall back to a stable lookup default.
- **Examples**:
  - `processors.syntax_id → syntax.id` - Processors fall back to syntax id `1` (Plain Text) instead of being deleted

#### 5. ON DELETE CASCADE with ON UPDATE CASCADE
Use when foreign key references a column that may be updated.
- **Examples**:
  - `user_roles.email → users.email` - Email updates should cascade to user roles

### Validation Rules

1. **No Default Behavior**: All foreign keys must have explicit `ON DELETE` clause
2. **Consistent Naming**: Foreign key names should follow pattern: `[table]_[column]_fk` or descriptive name
3. **Documentation**: All non-obvious constraint choices must be documented in comments

## Schema Change Process

### Adding New Tables
1. Define all foreign key constraints with appropriate `ON DELETE` clauses
2. Test cascade behavior with sample data
3. Include in schema validation script

### Modifying Existing Tables
1. Check existing foreign key constraints
2. Update any missing `ON DELETE` clauses
3. Test that changes don't break existing functionality

## Build-Time Validation

The build process includes validation that:
1. All foreign key constraints have explicit `ON DELETE` clauses
2. No constraints use default RESTRICT behavior
3. Constraint names follow naming conventions

## Common Patterns

### User Deletion Pattern
When a user is deleted:
- User records in `users` table are deleted
- All dependent records with `ON DELETE CASCADE` are automatically removed
- Records with `ON DELETE SET NULL` have user references cleared
- Records with `ON DELETE RESTRICT/NO ACTION` prevent user deletion (requires manual cleanup)
- Any filesystem cleanup that depends on IDs or paths which become unreachable after the DB cascade must be **pre-gathered before deletion**. StarExec now pre-collects job, solver, and benchmark IDs before `DeleteUser()` so output directories and pictures can still be removed after the database rows are gone.
- In-memory authorization caches must also be invalidated as part of the deletion flow. StarExec removes the deleted user from `isAdminCache` after a successful DB delete.

### Space Deletion Pattern
When a space is deleted:
- Space hierarchy in `closure` table is cascaded
- User associations are cascaded
- Benchmark/solver/job associations are cascaded
- Files owned by rows that will be cascade-deleted but are not otherwise recoverable from post-delete queries must be **pre-gathered before the transaction** and removed **after successful commit**. StarExec applies this pattern to processor files during `removeSubspaces()`.

### Historical Snapshot Pattern
Some columns are intentionally left unconstrained because they preserve the historical identity of completed benchmark runs even after primitives are deleted or recycled. Examples include:

- `job_pairs.bench_id`
- `jobpair_stage_data.solver_id`
- `jobpair_stage_data.config_id`
- `jobpair_stage_data.job_space_id`

These columns should be documented in SQL comments and must not be converted to ordinary foreign keys unless the historical data-retention policy changes.

## Testing Requirements

### Unit Tests
1. Test cascade deletion for each foreign key relationship
2. Test that RESTRICT constraints properly prevent deletion
3. Test SET NULL behavior clears references appropriately

### Integration Tests
1. Test complete user deletion flow
2. Test space deletion with all dependencies
3. Test job deletion with all associated records

## Emergency Procedures

### Rollback Procedures
If a constraint change causes issues:
1. Use backup created before changes
2. Apply rollback script to revert constraint changes
3. Document the issue and create fix

### Monitoring
Monitor for:
1. Unexpected cascade deletions
2. Orphaned records after SET NULL operations
3. Performance issues with large cascade operations

## Examples

### Good Examples
```sql
-- CASCADE when child has no meaning without parent
CONSTRAINT benchmarks_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE

-- SET NULL when reference is optional
CONSTRAINT spaces_default_permission FOREIGN KEY (default_permission) REFERENCES permissions(id) ON DELETE SET NULL

-- RESTRICT when parent shouldn't be deleted if referenced
CONSTRAINT processors_syntax FOREIGN KEY (syntax_id) REFERENCES syntax(id) ON DELETE RESTRICT
```

### Bad Examples
```sql
-- Missing ON DELETE clause (defaults to RESTRICT)
CONSTRAINT analytics_users_to_users FOREIGN KEY (user_id) REFERENCES users(id)

-- Wrong behavior choice
CONSTRAINT logins_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
-- Should be NO ACTION to preserve login history
```
