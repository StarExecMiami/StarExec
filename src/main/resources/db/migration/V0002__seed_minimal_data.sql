-- Minimal seed data to bootstrap StarExec
-- Generated from sql/new-install/MinimalData.sql

-- Note: Flyway is configured with defaultSchema("starexec"), so all references work correctly

-- Populate the StarExec DB with a minimal set of data to get started.

-- the password for admin is admin -- recommended to change
-- the password for public is public
INSERT INTO users (email, first_name, last_name, institution, created, password, disk_quota)
SELECT 'admin', 'Admin', 'User', 'The University of Miami', CURRENT_TIMESTAMP, 'c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec', 107374182400
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='admin');

INSERT INTO users (email, first_name, last_name, institution, created, password, disk_quota)
SELECT 'public', 'Public', 'User', 'None', CURRENT_TIMESTAMP, 'd32997e9747b65a3ecf65b82533a4c843c4e16dd30cf371e8c81ab60a341de00051da422d41ff29c55695f233a1e06fac8b79aeb0a4d91ae5d3d18c8e09b8c73', 52428800
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='public');

INSERT INTO user_roles (email, role)
SELECT 'admin','admin'
WHERE NOT EXISTS (SELECT 1 FROM user_roles WHERE email='admin' AND role='admin');

INSERT INTO user_roles (email, role)
SELECT 'public','user'
WHERE NOT EXISTS (SELECT 1 FROM user_roles WHERE email='public' AND role='user');

-- Starts at 2 (the root default permission is defined in the schema)
INSERT INTO permissions(id, add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader)
SELECT 2, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE id = 2);

INSERT INTO permissions(id, add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader)
SELECT 3, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,FALSE
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE id = 3);

INSERT INTO permissions(id, add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader)
SELECT 4, TRUE,TRUE,TRUE,TRUE,TRUE,FALSE,FALSE,FALSE,FALSE,FALSE,FALSE
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE id = 4);

-- Ensure the permissions sequence is correct
SELECT setval(pg_get_serial_sequence('starexec.permissions','id'), 4, true);

-- Ensure the processors sequence is correct (processor with id=1 is seeded in baseline)
SELECT setval(pg_get_serial_sequence('starexec.processors','id'), (SELECT MAX(id) FROM starexec.processors), true);

-- Starts at 2 (the root space is defined in the schema)
INSERT INTO spaces(name, created, description, locked, default_permission)
SELECT 'Test', CURRENT_TIMESTAMP, 'The Test community', FALSE,
	(SELECT id FROM permissions WHERE add_solver=TRUE AND add_bench=TRUE AND add_user=TRUE AND add_space=TRUE AND add_job=TRUE AND remove_solver=TRUE AND remove_bench=TRUE AND remove_user=TRUE AND remove_space=TRUE AND remove_job=TRUE AND is_leader=FALSE LIMIT 1)
WHERE NOT EXISTS (SELECT 1 FROM spaces WHERE name='Test');

INSERT INTO set_assoc (space_id, child_id)
SELECT (SELECT id FROM spaces WHERE name='root' LIMIT 1), (SELECT id FROM spaces WHERE name='Test' LIMIT 1)
WHERE NOT EXISTS (
	SELECT 1 FROM set_assoc sa
	JOIN spaces s1 ON s1.id=sa.space_id AND s1.name='root'
	JOIN spaces s2 ON s2.id=sa.child_id AND s2.name='Test'
);

INSERT INTO closure (ancestor, descendant)
SELECT (SELECT id FROM spaces WHERE name='root' LIMIT 1), (SELECT id FROM spaces WHERE name='Test' LIMIT 1)
WHERE NOT EXISTS (
	SELECT 1 FROM closure c
	JOIN spaces a ON a.id=c.ancestor AND a.name='root'
	JOIN spaces d ON d.id=c.descendant AND d.name='Test'
);
INSERT INTO closure (ancestor, descendant)
SELECT (SELECT id FROM spaces WHERE name='Test' LIMIT 1), (SELECT id FROM spaces WHERE name='Test' LIMIT 1)
WHERE NOT EXISTS (
	SELECT 1 FROM closure c
	JOIN spaces a ON a.id=c.ancestor AND a.name='Test'
	JOIN spaces d ON d.id=c.descendant AND d.name='Test'
);

INSERT INTO user_assoc (user_id, space_id, permission)
SELECT 
	(SELECT id FROM users WHERE email='admin' LIMIT 1),
	(SELECT id FROM spaces WHERE name='root' LIMIT 1),
	(SELECT id FROM permissions WHERE add_solver=TRUE AND add_bench=TRUE AND add_user=TRUE AND add_space=TRUE AND add_job=TRUE AND remove_solver=TRUE AND remove_bench=TRUE AND remove_user=TRUE AND remove_space=TRUE AND remove_job=TRUE AND is_leader=TRUE LIMIT 1)
WHERE NOT EXISTS (
	SELECT 1 FROM user_assoc ua
	JOIN users u ON u.id=ua.user_id AND u.email='admin'
	JOIN spaces s ON s.id=ua.space_id AND s.name='root'
);

INSERT INTO queues(name, status, global_access)
SELECT 'all.q','ACTIVE', true WHERE NOT EXISTS (SELECT 1 FROM queues WHERE name='all.q');

INSERT INTO system_flags (integrity_keeper, paused, test_queue, major_version, minor_version)
SELECT '',
	false,
	(SELECT id FROM queues WHERE name='all.q' LIMIT 1),
	1, 1
WHERE NOT EXISTS (SELECT 1 FROM system_flags);
