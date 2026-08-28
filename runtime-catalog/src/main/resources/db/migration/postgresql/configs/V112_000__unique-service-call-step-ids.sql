-- Camel 4.18.2 rejects a route that gives two nodes the same id (CAMEL-22420), and snapshots built
-- before the service call template was fixed repeat 'Handle response--<element id>' per response
-- handler and 'Validations--<element id>' per response validation. Numbering the repeats is enough:
-- the id only has to differ, and it keeps the '<name>--<element id>' shape the engine parses.
--
-- Only snapshots that back a deployment are rewritten. An installation can hold hundreds of thousands
-- of snapshots, and rewriting all of them would hold up service startup. Deploying an older snapshot
-- built before the fix still fails; rebuild the chain to get one from the fixed template.
--
-- The document is rebuilt from the text around the step ids rather than re-serialized, so every byte
-- outside the rewritten id attributes stays as it was. Splitting on the step ids and matching them
-- give two interleaved sequences -- part 1, id 1, part 2, id 2, ... -- which the join on ordinality
-- puts back together. There is one more part than id, so the trailing part joins to no id.
WITH affected AS (
    SELECT id,
           xml_configuration                             AS xml,
           'id="(?:Handle response|Validations)--[^"]*"' AS step_id_pattern
    FROM catalog.snapshots
    WHERE EXISTS (SELECT 1 FROM catalog.deployments d WHERE d.snapshot_id = snapshots.id)
      AND (strpos(xml_configuration, 'id="Handle response--') > 0
           OR strpos(xml_configuration, 'id="Validations--') > 0)
),
-- every step id in document order, numbered per snapshot and per id, so repeats get 2, 3, ...
step_ids AS (
    SELECT a.id,
           s.step_id[1] AS step_id,
           s.ordinality,
           row_number() OVER (PARTITION BY a.id, s.step_id[1] ORDER BY s.ordinality) AS occurrence
    FROM affected a,
         regexp_matches(a.xml, a.step_id_pattern, 'g') WITH ORDINALITY AS s(step_id, ordinality)
),
rebuilt AS (
    SELECT a.id,
           string_agg(
                   part.fragment || coalesce(
                           CASE s.occurrence
                               WHEN 1 THEN s.step_id
                               ELSE regexp_replace(s.step_id, '--', ' ' || s.occurrence || '--')
                               END, ''),
                   '' ORDER BY part.ordinality) AS xml
    FROM affected a
             CROSS JOIN LATERAL regexp_split_to_table(a.xml, a.step_id_pattern)
                 WITH ORDINALITY AS part(fragment, ordinality)
             LEFT JOIN step_ids s ON s.id = a.id AND s.ordinality = part.ordinality
    GROUP BY a.id
)
UPDATE catalog.snapshots
SET xml_configuration = rebuilt.xml
FROM rebuilt
WHERE snapshots.id = rebuilt.id
  AND snapshots.xml_configuration <> rebuilt.xml;

-- A chain keeps the hash of the archive it was last imported from, and an import with hash validation
-- skips a chain whose hash still matches. Clearing the hash makes the next import process every chain
-- instead of skipping it, so re-importing with a snapshot or deploy action rebuilds the chain from the
-- fixed template. That is the way out for the snapshots this migration leaves alone.
UPDATE catalog.chains
SET last_import_hash = NULL
WHERE last_import_hash IS NOT NULL;
