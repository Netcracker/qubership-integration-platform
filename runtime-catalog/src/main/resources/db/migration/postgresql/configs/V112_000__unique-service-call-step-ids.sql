-- Camel 4.18.2 rejects a route that gives two nodes the same id (CAMEL-22420), and snapshots built
-- before the service call template was fixed repeat 'Handle response--<element id>' per response
-- handler and 'Validations--<element id>' per response validation. Numbering the repeats is enough:
-- the id only has to differ, and it keeps the '<name>--<element id>' shape the engine parses.
WITH affected AS (
    SELECT id,
           xml_configuration                            AS xml,
           'id="(?:Handle response|Validations)--[^"]*"' AS step_id_pattern
    FROM catalog.snapshots
    WHERE strpos(xml_configuration, 'id="Handle response--') > 0
       OR strpos(xml_configuration, 'id="Validations--') > 0
),
-- splitting on the step ids and collecting them separately keeps every other byte of the document intact
fragments AS (
    SELECT a.id, f.fragment, f.ordinality
    FROM affected a,
         regexp_split_to_table(a.xml, a.step_id_pattern) WITH ORDINALITY AS f(fragment, ordinality)
),
step_ids AS (
    SELECT a.id,
           s.step_id[1] AS step_id,
           s.ordinality,
           row_number() OVER (PARTITION BY a.id, s.step_id[1] ORDER BY s.ordinality) AS occurrence
    FROM affected a,
         regexp_matches(a.xml, a.step_id_pattern, 'g') WITH ORDINALITY AS s(step_id, ordinality)
),
rebuilt AS (
    SELECT f.id,
           string_agg(
                   f.fragment || coalesce(
                           CASE s.occurrence
                               WHEN 1 THEN s.step_id
                               ELSE regexp_replace(s.step_id, '--', ' ' || s.occurrence || '--')
                               END, ''),
                   '' ORDER BY f.ordinality) AS xml
    FROM fragments f
             LEFT JOIN step_ids s ON s.id = f.id AND s.ordinality = f.ordinality
    GROUP BY f.id
)
UPDATE catalog.snapshots
SET xml_configuration = rebuilt.xml
FROM rebuilt
WHERE snapshots.id = rebuilt.id
  AND snapshots.xml_configuration <> rebuilt.xml;
