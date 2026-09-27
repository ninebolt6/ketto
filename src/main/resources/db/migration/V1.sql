-- data.db schema v1.
-- Statements are separated by ';' at end of line; '--' comment lines are
-- stripped before execution. Keep every statement idempotent (IF NOT EXISTS)
-- so an interrupted step can be retried on the next open.

CREATE TABLE IF NOT EXISTS arenas(
  name TEXT PRIMARY KEY COLLATE NOCASE,
  enabled INTEGER NOT NULL DEFAULT 0,
  spawn1_world TEXT, spawn1_x REAL, spawn1_y REAL, spawn1_z REAL, spawn1_yaw REAL, spawn1_pitch REAL,
  spawn2_world TEXT, spawn2_x REAL, spawn2_y REAL, spawn2_z REAL, spawn2_yaw REAL, spawn2_pitch REAL,
  seq INTEGER NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS arena_kits(
  arena_name TEXT PRIMARY KEY REFERENCES arenas(name) ON DELETE CASCADE,
  payload TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS arena_signs(
  arena_name TEXT PRIMARY KEY REFERENCES arenas(name) ON DELETE CASCADE,
  world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS arena_signs_pos ON arena_signs(world, x, y, z);

CREATE TABLE IF NOT EXISTS lobby(
  id INTEGER PRIMARY KEY CHECK (id = 1),
  world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
  yaw REAL NOT NULL, pitch REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS backups(
  backup_id TEXT PRIMARY KEY,
  match_id TEXT NOT NULL,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  payload TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS player_stats(
  player_uuid TEXT PRIMARY KEY,
  wins INTEGER NOT NULL DEFAULT 0,
  losses INTEGER NOT NULL DEFAULT 0
);
