-- Seeds 50,000 simulated endpoints (MAC 02:00:..., IP 10.x.x.x, same mapping as SimIdentity)
-- with one assessment each. Run against the SIM database only.
INSERT INTO endpoint (mac_address, ip_address, hostname, os_name, os_version, connected, first_seen_at, last_seen_at)
SELECT mac, ip, 'SIM-' || lpad(i::text, 5, '0'), 'Microsoft Windows 11 Pro', '10.0.22631', false,
       now() - interval '30 days', now() - random() * interval '2 days'
FROM (
    SELECT i,
           upper('02:00:' || substr(h,1,2) || ':' || substr(h,3,2) || ':' || substr(h,5,2) || ':' || substr(h,7,2)) AS mac,
           '10.' || ((i >> 16) & 255) || '.' || ((i >> 8) & 255) || '.' || (i & 255) AS ip
    FROM (SELECT i, lpad(to_hex(i), 8, '0') AS h FROM generate_series(1, 50000) AS i) x
) y
ON CONFLICT (mac_address) DO NOTHING;

INSERT INTO assessment (id, endpoint_id, status, detail, started_at, completed_at, created_at)
SELECT gen_random_uuid(), e.id,
       CASE WHEN r < 0.80 THEN 'COMPLIANT' WHEN r < 0.95 THEN 'NON_COMPLIANT' ELSE 'ERROR' END,
       'sim seed', t, t, t
FROM (SELECT id, random() AS r, now() - random() * interval '2 days' AS t
      FROM endpoint WHERE mac_address LIKE '02:00:%') e;

INSERT INTO check_result (assessment_id, check_type, status, details)
SELECT a.id, 'FIREWALL', CASE WHEN a.status = 'ERROR' THEN 'COMPLIANT' ELSE a.status END, '{"summary":"sim"}'::jsonb
FROM assessment a JOIN endpoint e ON e.id = a.endpoint_id
WHERE e.mac_address LIKE '02:00:%' AND a.detail = 'sim seed';