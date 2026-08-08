-- Plan — Postgres schema.
--
-- Migrated from MongoDB. The Mongo model was a `days` collection of documents, each
-- embedding meals -> foods. That nesting is preserved here as three tables with cascading
-- foreign keys; ordering inside a day/meal is explicit (`position`) because SQL rows are
-- unordered while JSON arrays are not.
--
-- Dates: Mongo stored them as 'DDMMYYYY' strings. Here they are real DATE columns; the
-- DDMMYYYY form is a wire/display format only and is converted at the API boundary.
--
-- Numerics: Mongo held macros as strings ('67.5') because the Excel/VBA client sends them
-- quoted. Storage is now NUMERIC; the API coerces string input so Excel keeps working.

CREATE TABLE IF NOT EXISTS settings (
    id                  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),  -- single-row table
    groups              JSONB       NOT NULL,
    daily               JSONB       NOT NULL,
    person              JSONB       NOT NULL,
    start_date          DATE        NOT NULL,
    timezone_name       TEXT        NOT NULL
);

CREATE TABLE IF NOT EXISTS foods (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                TEXT           NOT NULL,
    type                TEXT           NOT NULL,
    inner_type          TEXT           NOT NULL DEFAULT '',
    protein             NUMERIC(10,2)  NOT NULL DEFAULT 0,
    fat                 NUMERIC(10,2)  NOT NULL DEFAULT 0,
    calories            NUMERIC(10,2)  NOT NULL DEFAULT 0,
    available           TEXT           NOT NULL DEFAULT ''
);

-- The catalog is keyed by name case-insensitively: BL._recompute_meals_nutrition and the
-- clients all match foods by UPPER(name), so two rows differing only in case would make
-- lookups ambiguous. Verified zero collisions in the migrated data.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_foods_name_upper ON foods (UPPER(TRIM(name)));
CREATE INDEX IF NOT EXISTS idx_foods_sort ON foods (type, inner_type);
CREATE INDEX IF NOT EXISTS idx_foods_group_pool ON foods (inner_type, available);

CREATE TABLE IF NOT EXISTS days (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    date                DATE           NOT NULL UNIQUE,
    weight              NUMERIC(10,2)  NOT NULL DEFAULT 0,
    day_closed          BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Denormalized day totals, written by end_day. NULL until the day is closed.
    nutrition_protein   NUMERIC(10,2),
    nutrition_fat       NUMERIC(10,2),
    nutrition_calories  NUMERIC(10,2),

    -- Frozen goal snapshot, written by end_day (see the note below the table).
    settings_snapshot   JSONB,
    target_protein      NUMERIC(10,2),
    target_fat_calories NUMERIC(10,2),
    target_calories     NUMERIC(10,2),
    formula_version     INT,
    snapshot_source     TEXT
);

-- Carries over the two Mongo invariants (uniq_date above, and at most one open day).
-- The partial unique index is the direct translation of Mongo's partialFilterExpression.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_open_day ON days ((day_closed)) WHERE day_closed = FALSE;

CREATE TABLE IF NOT EXISTS meals (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day_id              BIGINT         NOT NULL REFERENCES days(id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    name                TEXT           NOT NULL,
    meal_closed         BOOLEAN        NOT NULL DEFAULT FALSE,
    UNIQUE (day_id, position),
    UNIQUE (day_id, name)
);

CREATE INDEX IF NOT EXISTS idx_meals_day ON meals (day_id);

CREATE TABLE IF NOT EXISTS meal_foods (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meal_id             BIGINT         NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    name                TEXT           NOT NULL,
    -- NULL is meaningful: group-reference rows (Fruits/Nuts/Vegetables inside a regular
    -- meal) have no weight of their own — their macros are derived from the group meal.
    -- Mongo held these as null or '' ; both migrate to NULL.
    weight              NUMERIC(10,2),
    protein             NUMERIC(10,2)  NOT NULL DEFAULT 0,
    fat                 NUMERIC(10,2)  NOT NULL DEFAULT 0,
    calories            NUMERIC(10,2)  NOT NULL DEFAULT 0,
    UNIQUE (meal_id, position)
);

CREATE INDEX IF NOT EXISTS idx_meal_foods_meal ON meal_foods (meal_id);

-- ---------------------------------------------------------------------------
-- Archive: the two Mongo backup collections (days_01022026, days_10052026).
--
-- Kept in separate tables, fully isolated from the live `days` graph, so no live query
-- can accidentally read them and no live invariant (single open day, unique date) is
-- imposed across archives. `archive` labels which backup a row came from; date is unique
-- only within an archive.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS days_archive (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    archive             TEXT           NOT NULL,
    date                DATE           NOT NULL,
    weight              NUMERIC(10,2)  NOT NULL DEFAULT 0,
    day_closed          BOOLEAN        NOT NULL DEFAULT FALSE,
    nutrition_protein   NUMERIC(10,2),
    nutrition_fat       NUMERIC(10,2),
    nutrition_calories  NUMERIC(10,2),
    settings_snapshot   JSONB,
    target_protein      NUMERIC(10,2),
    target_fat_calories NUMERIC(10,2),
    target_calories     NUMERIC(10,2),
    formula_version     INT,
    snapshot_source     TEXT,
    UNIQUE (archive, date)
);

CREATE INDEX IF NOT EXISTS idx_days_archive_archive ON days_archive (archive);

CREATE TABLE IF NOT EXISTS meals_archive (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day_id              BIGINT         NOT NULL REFERENCES days_archive(id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    name                TEXT           NOT NULL,
    meal_closed         BOOLEAN        NOT NULL DEFAULT FALSE,
    UNIQUE (day_id, position)
);

CREATE INDEX IF NOT EXISTS idx_meals_archive_day ON meals_archive (day_id);

CREATE TABLE IF NOT EXISTS meal_foods_archive (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meal_id             BIGINT         NOT NULL REFERENCES meals_archive(id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    name                TEXT           NOT NULL,
    weight              NUMERIC(10,2),
    protein             NUMERIC(10,2)  NOT NULL DEFAULT 0,
    fat                 NUMERIC(10,2)  NOT NULL DEFAULT 0,
    calories            NUMERIC(10,2)  NOT NULL DEFAULT 0,
    UNIQUE (meal_id, position)
);

CREATE INDEX IF NOT EXISTS idx_meal_foods_archive_meal ON meal_foods_archive (meal_id);

-- ---------------------------------------------------------------------------
-- Frozen goal snapshot
--
-- `settings` is a single mutable row, so targets (Needed protein / fat-calories / calories)
-- computed live would change retroactively whenever tdee_multiplier, the daily figures, or
-- calorie_type were edited — silently rewriting the goals of every past day. End Day therefore
-- freezes the settings that were in force, plus the three computed targets, onto the day row.
--
-- These are a RECORD OF WHAT WAS SHOWN, not derived data: a later formula change must not
-- retro-edit closed days. `formula_version` exists only so a row can be audited against the
-- formula that produced it.
--
-- ALTERs (not just the CREATE TABLE columns above) so an existing database picks the columns
-- up on startup.
-- ---------------------------------------------------------------------------

ALTER TABLE days ADD COLUMN IF NOT EXISTS settings_snapshot   JSONB;
ALTER TABLE days ADD COLUMN IF NOT EXISTS target_protein      NUMERIC(10,2);
ALTER TABLE days ADD COLUMN IF NOT EXISTS target_fat_calories NUMERIC(10,2);
ALTER TABLE days ADD COLUMN IF NOT EXISTS target_calories     NUMERIC(10,2);
ALTER TABLE days ADD COLUMN IF NOT EXISTS formula_version     INT;
ALTER TABLE days ADD COLUMN IF NOT EXISTS snapshot_source     TEXT;

ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS settings_snapshot   JSONB;
ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS target_protein      NUMERIC(10,2);
ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS target_fat_calories NUMERIC(10,2);
ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS target_calories     NUMERIC(10,2);
ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS formula_version     INT;
ALTER TABLE days_archive ADD COLUMN IF NOT EXISTS snapshot_source     TEXT;

-- Postgres has no ADD CONSTRAINT IF NOT EXISTS, so each one is guarded by a catalog check to
-- keep startup idempotent.
DO $$
BEGIN
    -- All-or-none: a partially written snapshot is never valid. Deliberately does NOT mention
    -- nutrition_*, because days closed before this feature have nutrition but no snapshot and
    -- that transitional state must stay legal until the backfill runs.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_snapshot_all_or_none'
                   AND conrelid = 'days'::regclass) THEN
        ALTER TABLE days ADD CONSTRAINT chk_days_snapshot_all_or_none CHECK (
            num_nonnulls(settings_snapshot, target_protein, target_fat_calories,
                         target_calories, formula_version, snapshot_source) IN (0, 6)
        );
    END IF;

    -- An OPEN day must never hold frozen values: it is still being edited, so its targets have
    -- to track live settings. Every reopening path clears these together with nutrition.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_open_not_frozen'
                   AND conrelid = 'days'::regclass) THEN
        ALTER TABLE days ADD CONSTRAINT chk_days_open_not_frozen CHECK (
            day_closed OR settings_snapshot IS NULL
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_snapshot_source'
                   AND conrelid = 'days'::regclass) THEN
        ALTER TABLE days ADD CONSTRAINT chk_days_snapshot_source CHECK (
            snapshot_source IS NULL
            OR snapshot_source IN ('end_day', 'backfill_current_settings')
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_arch_snapshot_all_or_none'
                   AND conrelid = 'days_archive'::regclass) THEN
        ALTER TABLE days_archive ADD CONSTRAINT chk_days_arch_snapshot_all_or_none CHECK (
            num_nonnulls(settings_snapshot, target_protein, target_fat_calories,
                         target_calories, formula_version, snapshot_source) IN (0, 6)
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_arch_snapshot_source'
                   AND conrelid = 'days_archive'::regclass) THEN
        ALTER TABLE days_archive ADD CONSTRAINT chk_days_arch_snapshot_source CHECK (
            snapshot_source IS NULL
            OR snapshot_source IN ('end_day', 'backfill_current_settings')
        );
    END IF;

    -- Same open-day rule as `days`: the archives hold historical rows, and an "open" one
    -- (the stale open day a backup captured) must not carry frozen goals either.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_days_arch_open_not_frozen'
                   AND conrelid = 'days_archive'::regclass) THEN
        ALTER TABLE days_archive ADD CONSTRAINT chk_days_arch_open_not_frozen CHECK (
            day_closed OR settings_snapshot IS NULL
        );
    END IF;
EXCEPTION
    -- Two workers starting at once can both see a constraint missing and both try to add it.
    -- The loser gets duplicate_object; the constraint exists either way, which is all we want.
    WHEN duplicate_object THEN NULL;
END
$$;
