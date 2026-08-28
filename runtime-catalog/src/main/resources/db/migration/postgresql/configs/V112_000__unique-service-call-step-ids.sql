-- Camel 4.18.2 rejects a route that gives two nodes the same id (CAMEL-22420), and snapshots built
-- before the service call template was fixed repeat 'Handle response--<element id>' per response
-- handler and 'Validations--<element id>' per response validation. Numbering the repeats is enough:
-- the id only has to differ, and it keeps the '<name>--<element id>' shape the engine parses.
WITH affected AS (
    SELECT id, xml_configuration AS xml
    FROM catalog.snapshots
    WHERE xml_configuration LIKE '%id="Handle response--%'
       OR xml_configuration LIKE '%id="Validations--%'
),
-- splitting on the step ids and collecting them separately keeps every other byte of the document intact
fragments AS (
    SELECT a.id, f.fragment, f.position
    FROM affected a,
         regexp_split_to_table(a.xml, 'id="(?:Handle response|Validations)--[^"]*"')
             WITH ORDINALITY AS f(fragment, position)
),
step_ids AS (
    SELECT a.id, s.step_id[1] AS step_id, s.position
    FROM affected a,
         regexp_matches(a.xml, '(id="(?:Handle response|Validations)--[^"]*")', 'g')
             WITH ORDINALITY AS s(step_id, position)
),
numbered_step_ids AS (
    SELECT id,
           position,
           CASE
               WHEN row_number() OVER (PARTITION BY id, step_id ORDER BY position) = 1
                   THEN step_id
               ELSE regexp_replace(
                       step_id,
                       '--',
                       ' ' || row_number() OVER (PARTITION BY id, step_id ORDER BY position) || '--')
               END AS step_id
    FROM step_ids
),
rebuilt AS (
    SELECT f.id,
           string_agg(f.fragment || coalesce(s.step_id, ''), '' ORDER BY f.position) AS xml
    FROM fragments f
             LEFT JOIN numbered_step_ids s ON s.id = f.id AND s.position = f.position
    GROUP BY f.id
)
UPDATE catalog.snapshots snapshot
SET xml_configuration = rebuilt.xml
FROM rebuilt
WHERE snapshot.id = rebuilt.id
  AND snapshot.xml_configuration <> rebuilt.xml;
