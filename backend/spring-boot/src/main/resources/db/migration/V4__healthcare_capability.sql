-- Add the isolated chest-image research capability without changing existing model records.
ALTER TABLE models DROP CONSTRAINT ck_models_capability;
ALTER TABLE models ADD CONSTRAINT ck_models_capability
    CHECK (capability IN ('TEXT', 'CODE', 'IMAGE', 'DOCUMENT', 'HEALTHCARE'));

