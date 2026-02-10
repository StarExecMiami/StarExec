-- Populate the StarExec DB with a minimal set of data to get started.

-- the password for admin is admin -- recommended to change
-- the password for public is public
INSERT INTO users (email, first_name, last_name, institution, created, password, disk_quota) VALUES
	('admin', 'Admin', 'User', 'The University of Miami', SYSDATE(), '$2a$12$A4AmJEY.PTCaK/4AN8RU7evjxcbP9c6K25h17BtCZLGmmBln4ZJP2', 107374182400),
	('public', 'Public', 'User', 'None', SYSDATE(), '$2a$12$PtxXbL4q86Yrk40WDnTmBeKhf1MNVmME8iLg99L7ULa4HMl7Btaty', 52428800);

INSERT INTO user_roles VALUES
	('admin', 'admin'),
	('public', 'user');

-- Starts at 2 (the root default permission is defined in the schema)
INSERT INTO permissions(add_solver, add_bench, add_user, add_space, add_job, remove_solver, remove_bench, remove_user, remove_space, remove_job, is_leader) VALUES
	(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
	(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0),
	(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0);

-- Starts at 2 (the root space is defined in the schema)
INSERT INTO spaces(name, created, description, locked, default_permission) VALUES
	('Test', SYSDATE(), 'The Test community', 0, 3);

INSERT INTO set_assoc VALUES
	(1, 2);

INSERT INTO closure VALUES
	(1, 2),
	(2, 2);

INSERT INTO user_assoc VALUES
	(1, 1, 2);

INSERT INTO queues(name, status, global_access) VALUES
	("all.q", "ACTIVE", true);

INSERT INTO system_flags (paused, test_queue, major_version, minor_version) VALUES
	(false, 1, 1, 1);
