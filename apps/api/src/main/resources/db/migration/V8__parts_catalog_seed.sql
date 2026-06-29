INSERT INTO parts (
  id,
  public_id,
  category,
  name,
  manufacturer,
  price,
  status,
  attributes,
  created_at
) VALUES
  (1011, '00000000-0000-4000-8000-000000010011', 'CPU', 'Ryzen 7 7800X3D', 'AMD', 545000, 'ACTIVE', '{"socket":"AM5","wattage":120,"coreCount":8,"threadCount":16,"usageTags":["GAMING"],"shortSpec":"8C/16T, AM5, 3D V-Cache","externalSources":{"danawa":{"keyword":"Ryzen 7 7800X3D","backupOnly":true},"naver":{"keyword":"Ryzen 7 7800X3D","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:00:00Z'),
  (1012, '00000000-0000-4000-8000-000000010012', 'CPU', 'Core i5-14600KF', 'Intel', 365000, 'ACTIVE', '{"socket":"LGA1700","wattage":125,"coreCount":14,"threadCount":20,"usageTags":["GAMING","DEV"],"shortSpec":"14C/20T, LGA1700","externalSources":{"danawa":{"keyword":"i5 14600KF","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:00:00Z'),
  (1021, '00000000-0000-4000-8000-000000010021', 'MOTHERBOARD', 'B650M Pro RS WiFi', 'ASRock', 185000, 'ACTIVE', '{"socket":"AM5","chipset":"B650","memoryType":"DDR5","formFactor":"M-ATX","hasWifi":true,"shortSpec":"AM5, B650, DDR5, WiFi","externalSources":{"danawa":{"keyword":"B650M WiFi","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:02:00Z'),
  (1022, '00000000-0000-4000-8000-000000010022', 'MOTHERBOARD', 'Z790 UD AX DDR5', 'Gigabyte', 298000, 'ACTIVE', '{"socket":"LGA1700","chipset":"Z790","memoryType":"DDR5","formFactor":"ATX","hasWifi":true,"shortSpec":"LGA1700, Z790, DDR5, WiFi","externalSources":{"danawa":{"keyword":"Z790 DDR5 WiFi","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:02:00Z'),
  (1031, '00000000-0000-4000-8000-000000010031', 'RAM', 'DDR5 16GB 5600MHz', 'Samsung', 62000, 'ACTIVE', '{"capacityGb":16,"memoryType":"DDR5","speedMhz":5600,"moduleCount":1,"shortSpec":"16GB DDR5-5600","externalSources":{"danawa":{"keyword":"DDR5 16GB 5600","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:04:00Z'),
  (1032, '00000000-0000-4000-8000-000000010032', 'RAM', 'DDR5 64GB 5600MHz Kit', 'G.SKILL', 285000, 'ACTIVE', '{"capacityGb":64,"memoryType":"DDR5","speedMhz":5600,"moduleCount":2,"usageTags":["VIDEO_EDIT","AI_DEV"],"shortSpec":"64GB(32x2) DDR5-5600","externalSources":{"danawa":{"keyword":"DDR5 64GB 5600","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:04:00Z'),
  (1041, '00000000-0000-4000-8000-000000010041', 'GPU', 'GeForce RTX 4060 Ti 8GB', 'NVIDIA Partner', 525000, 'ACTIVE', '{"wattage":160,"lengthMm":244,"vramGb":8,"usageTags":["FHD_GAMING"],"shortSpec":"RTX 4060 Ti, 8GB, 160W","externalSources":{"danawa":{"keyword":"RTX 4060 Ti 8GB","backupOnly":true},"naver":{"keyword":"RTX 4060 Ti","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:06:00Z'),
  (1042, '00000000-0000-4000-8000-000000010042', 'GPU', 'GeForce RTX 4070 Ti SUPER 16GB', 'NVIDIA Partner', 1180000, 'ACTIVE', '{"wattage":285,"lengthMm":330,"vramGb":16,"usageTags":["QHD_GAMING","AI_DEV"],"shortSpec":"RTX 4070 Ti SUPER, 16GB, 285W","externalSources":{"danawa":{"keyword":"RTX 4070 Ti SUPER 16GB","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:06:00Z'),
  (1043, '00000000-0000-4000-8000-000000010043', 'GPU', 'Radeon RX 7800 XT 16GB', 'AMD Partner', 735000, 'ACTIVE', '{"wattage":263,"lengthMm":302,"vramGb":16,"usageTags":["QHD_GAMING"],"shortSpec":"RX 7800 XT, 16GB, 263W","externalSources":{"danawa":{"keyword":"RX 7800 XT 16GB","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:06:00Z'),
  (1051, '00000000-0000-4000-8000-000000010051', 'STORAGE', 'NVMe Gen4 2TB SSD', 'SK hynix', 178000, 'ACTIVE', '{"interface":"M.2 NVMe","capacityGb":2000,"generation":"Gen4","shortSpec":"M.2 NVMe Gen4, 2TB","externalSources":{"danawa":{"keyword":"NVMe Gen4 2TB","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:08:00Z'),
  (1052, '00000000-0000-4000-8000-000000010052', 'STORAGE', 'SATA 4TB HDD', 'Seagate', 132000, 'ACTIVE', '{"interface":"SATA","capacityGb":4000,"driveType":"HDD","shortSpec":"SATA HDD, 4TB","externalSources":{"danawa":{"keyword":"4TB HDD","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:08:00Z'),
  (1061, '00000000-0000-4000-8000-000000010061', 'PSU', 'Gold 850W Modular PSU', 'SuperFlower', 158000, 'ACTIVE', '{"capacityW":850,"efficiency":"80PLUS_GOLD","modular":true,"shortSpec":"850W, 80PLUS Gold, Modular","externalSources":{"danawa":{"keyword":"850W Gold PSU","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:10:00Z'),
  (1062, '00000000-0000-4000-8000-000000010062', 'PSU', 'Bronze 650W PSU', 'Micronics', 78000, 'ACTIVE', '{"capacityW":650,"efficiency":"80PLUS_BRONZE","modular":false,"shortSpec":"650W, 80PLUS Bronze","externalSources":{"danawa":{"keyword":"650W Bronze PSU","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:10:00Z'),
  (1071, '00000000-0000-4000-8000-000000010071', 'CASE', 'Mesh Airflow ATX Case', '3RSYS', 92000, 'ACTIVE', '{"maxGpuLengthMm":360,"formFactor":"ATX_MATX","frontMesh":true,"shortSpec":"ATX/mATX, GPU 360mm, Mesh","externalSources":{"danawa":{"keyword":"Mesh ATX Case","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:12:00Z'),
  (1072, '00000000-0000-4000-8000-000000010072', 'CASE', 'Compact M-ATX Case', 'darkFlash', 59000, 'ACTIVE', '{"maxGpuLengthMm":320,"formFactor":"MATX","frontMesh":true,"shortSpec":"mATX, GPU 320mm","externalSources":{"danawa":{"keyword":"M-ATX Mesh Case","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:12:00Z'),
  (1081, '00000000-0000-4000-8000-000000010081', 'COOLER', 'Dual Tower Air Cooler', 'Thermalright', 69000, 'ACTIVE', '{"coolerType":"AIR","tdpW":240,"heightMm":157,"shortSpec":"Dual tower air, 240W TDP","externalSources":{"danawa":{"keyword":"Dual Tower Air Cooler","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:14:00Z'),
  (1082, '00000000-0000-4000-8000-000000010082', 'COOLER', '240mm AIO Liquid Cooler', 'DeepCool', 118000, 'ACTIVE', '{"coolerType":"AIO","radiatorMm":240,"tdpW":260,"shortSpec":"240mm AIO liquid cooler","externalSources":{"danawa":{"keyword":"240mm AIO Cooler","backupOnly":true}},"metadataVersion":2}', '2026-06-29T12:14:00Z');

INSERT INTO price_snapshots (
  part_id,
  price,
  source,
  collected_at,
  raw_payload
)
SELECT p.id,
       p.price,
       'DANAWA_BACKUP',
       '2026-06-29T12:30:00Z',
       jsonb_build_object(
         'source', 'danawa-backup',
         'collector', 'manual-seed',
         'partPublicId', p.public_id::text,
         'keyword', p.attributes #>> '{externalSources,danawa,keyword}',
         'backupOnly', true,
         'shippingIncluded', false,
         'couponIncluded', false
       )
FROM parts p
WHERE p.id BETWEEN 1011 AND 1082;

INSERT INTO benchmark_summaries (
  part_id,
  benchmark_key,
  summary,
  score,
  metadata,
  created_at
) VALUES
  (1011, 'RYZEN_7800X3D_QHD_GAMING', 'QHD gaming workloads favor 7800X3D class CPUs when paired with mid-high GPUs.', 94.50, '{"usageTags":["GAMING"],"metadataVersion":2}', '2026-06-29T12:35:00Z'),
  (1012, 'I5_14600KF_GAME_DEV', 'Balanced gaming and development CPU candidate with higher power draw.', 86.00, '{"usageTags":["GAMING","DEV"],"metadataVersion":2}', '2026-06-29T12:35:00Z'),
  (1032, 'DDR5_64GB_CREATOR_AI_DEV', '64GB memory is suitable for heavier editing, local AI tooling, and multitasking.', 88.00, '{"usageTags":["VIDEO_EDIT","AI_DEV"],"capacityGb":64,"metadataVersion":2}', '2026-06-29T12:35:00Z'),
  (1041, 'RTX_4060TI_FHD_GAMING', 'FHD gaming GPU candidate with lower power draw and limited VRAM headroom.', 74.00, '{"usageTags":["FHD_GAMING"],"metadataVersion":2}', '2026-06-29T12:35:00Z'),
  (1042, 'RTX_4070TI_SUPER_QHD_AI', 'QHD high-refresh and AI development candidate with 16GB VRAM.', 94.00, '{"usageTags":["QHD_GAMING","AI_DEV"],"vramGb":16,"metadataVersion":2}', '2026-06-29T12:35:00Z'),
  (1043, 'RX_7800XT_QHD_VALUE', 'QHD raster gaming value candidate with 16GB VRAM.', 86.00, '{"usageTags":["QHD_GAMING"],"vramGb":16,"metadataVersion":2}', '2026-06-29T12:35:00Z');

SELECT setval(pg_get_serial_sequence('parts', 'id'), (SELECT max(id) FROM parts));
SELECT setval(pg_get_serial_sequence('price_snapshots', 'id'), (SELECT max(id) FROM price_snapshots));
SELECT setval(pg_get_serial_sequence('benchmark_summaries', 'id'), (SELECT max(id) FROM benchmark_summaries));
