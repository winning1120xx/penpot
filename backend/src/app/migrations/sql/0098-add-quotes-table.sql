CREATE TABLE usage_quote (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  profile_id uuid NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  project_id uuid NULL REFERENCES project(id) ON DELETE CASCADE DEFERRABLE,
  team_id uuid NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  file_id uuid NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  quote bigint NOT NULL,
  obj text NOT NULL
);

ALTER TABLE usage_quote
  ALTER COLUMN obj SET STORAGE external;

CREATE INDEX usage_quote__profile_id__idx ON usage_quote(profile_id, obj);
CREATE INDEX usage_quote__project_id__idx ON usage_quote(project_id, obj);
CREATE INDEX usage_quote__team_id__idx ON usage_quote(project_id, obj);

CREATE TABLE usage_quote_test (
  id bigserial NOT NULL PRIMARY KEY,
  profile_id bigint NULL,
  project_id bigint NULL,
  team_id bigint NULL,
  file_id bigint NULL,

  quote bigint NOT NULL,
  obj text NOT NULL
);

-- INSERT INTO usage_quote_test (profile_id, project_id, team_id, file_id, obj, quote)
--      VALUES (   1, null, null, null, 'team',    100),
--             (null,    1, null, null, 'file',    100),
--             (null, null,    1, null, 'file',    500),
--             (   1, null,    1, null, 'file',    800),
--             (   1,    1, null, null, 'file',    150),
--             (null, null,    1, null, 'project',  50),
--             (   1, null,    1, null, 'project', 100);

-- CREATE INDEX usage_quote_test__profile_id__idx ON usage_quote_test(profile_id);
-- CREATE INDEX usage_quote_test__project_id__idx ON usage_quote_test(project_id);
-- CREATE INDEX usage_quote_test__team_id__idx ON usage_quote_test(project_id);

-- select * from usage_quote_test where obj = 'file' and team_id = 1
-- union
-- select * from usage_quote_test where obj = 'file' and project_id = 1
-- union
-- select * from usage_quote_test where obj = 'file' and profile_id = 1 and team_id = 1
-- union
-- select * from usage_quote_test where obj = 'file' and profile_id = 1 and project_id = 1;


-- select * from usage_quote_test
--  where obj = 'file'
--    and ((profile_id is null and team_id = 1) or
--         (profile_id = 1 and team_id = 1));


select count(*) as total
  from team_profile_rel
 where is_owner is true;
