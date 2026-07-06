-- 감사 P1-2(a): 메인보드 M.2 슬롯 수 + SATA 포트 수 백필.
-- 배경: parts.attributes에 m2Slots/sataPorts가 0/72로 전무 → STORAGE M.2 슬롯 검사(P1-2(b))의 근거가 없었다.
-- V94(memorySlots) 웹 검증 워크플로 재사용: ACTIVE/INACTIVE 72행을 66개 고유 모델로 묶어 제조사
-- 공식 스펙(asus/asrock/msi/gigabyte)으로 확인(2026-07-07, Opus 병렬 검증 + 저신뢰 재검증). E-key 무선랜 슬롯 제외,
-- M-key 스토리지 슬롯만 계상. 색상 변형(ICE/화이트/W)은 스펙 동일.

-- ASRock B850 Pro-A DDR5 → m2Slots=4, sataPorts=4 [high] ASRock 스펙: M2_1 Gen5x4(CPU)+M2_2/M2_3 Gen4x4(칩셋)+M2_4 Gen3x4=4개. SATA 2(칩셋)+2(ASM1061)=4. 출처 asrock.com/mb/AMD/B850 Pro-A
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('5489b08c-ae1d-4092-8638-8b6ee139aa53', '00fde3d6-fffd-4834-a80f-a175f21add02');

-- ASRock B850 Pro-A WiFi DDR5 → m2Slots=4, sataPorts=4 [high] B850 Pro-A와 동일 보드(WiFi 추가): M2_1 Gen5x4 + M2_2/M2_3 Gen4x4 + M2_4 Gen3x4 = 4개. SATA 2(칩셋)+2(ASM1061)=4. 출처 asrock.com B850 Pro-A WiFi
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('1c607bef-cf1f-4716-ba75-80e12a795d57');

-- ASRock B850M Steel Legend WiFi → m2Slots=3, sataPorts=4 [high] ASRock 스펙: M2_1 Gen5x4 + M2_2 Gen4x2 + M2_3 Gen4x4 = 3개, SATA3 4개. Amazon 리스팅도 '3X M.2' 명시
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013003');

-- ASRock B860 Rock WiFi 7 → m2Slots=3, sataPorts=4 [high] ASRock 스펙(Intel B860): M2_1 Gen5x4 + M2_2/M2_3 Gen4x4 = 3개, SATA3 4개(RAID 0/1/5/10). 출처 asrock.com/mb/Intel/B860 Rock WiFi 7
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('24440f00-c096-414b-a067-1acd6831bb73');

-- ASRock B860M Pro RS WiFi → m2Slots=3, sataPorts=4 [high] ASRock 스펙(Intel B860): M2_1 Gen5x4 + M2_2/M2_3 Gen4x4 = 3개, SATA3 4개. 출처 asrock.com/MB/Intel/B860M Pro RS WiFi
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013007');

-- ASRock B860M Rock WiFi → m2Slots=2, sataPorts=4 [high] ASRock 스펙(Intel B860 mATX): M2_1 Gen5x4(CPU) + M2_2 Gen4x4(칩셋) = 2개만, SATA3 4개. 상위 B860 Rock WiFi 7과 달리 M2_3 없음(2회 교차확인)
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('f786cb93-b28b-4a1f-ba83-f91dd210bb54');

-- ASRock B860M Rock WiFi 인텔 메인보드 → m2Slots=2, sataPorts=4 [high] asrock.com 스펙: M2_1 Key M PCIe Gen5x4(CPU) + M2_2 Key M PCIe Gen4x4(칩셋) = 2개. SATA3 4개. E-key 무선랜 슬롯 제외.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('74b36be0-1910-4525-90b1-8943d17a6284');

-- ASRock X870E Nova WiFi → m2Slots=5, sataPorts=4 [high] pg.asrock.com 스펙: M2_1 Gen5(CPU) + M2_2/3/4 Gen4 + M2_5 Gen3x2&SATA, 모두 Key M 스토리지 = 5개. SATA 4개(칩셋2+ASM1061 2). M2_5 사용 시 PCIE3 비활성.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('ea573d60-1029-49cb-a563-b13e9e40bcaa', 'e17a9981-266b-4724-a8f0-a35737c90b55', '938f0d25-4a6d-4edb-8a13-0d36d46263d0');

-- ASRock Z890 Pro RS WiFi → m2Slots=4, sataPorts=4 [high] asrock.com 스펙: M2_1 Gen5(CPU) + M2_2/3 Gen4 + M2_4 Gen4x4&SATA, 모두 Key M = 4개. SATA3 4개. 4번과 동일 보드.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('4748bf64-72de-40d3-8254-7ac7f91f715d');

-- ASRock Z890 Pro RS WiFi 인텔 메인보드 → m2Slots=4, sataPorts=4 [high] 3번과 동일 모델(색상/유통표기 차). M2_1 Gen5 + M2_2/3 Gen4 + M2_4 Gen4&SATA = 4개 Key M. SATA3 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('ccebe6cc-f51b-426c-9835-dcd20f6cddec');

-- ASRock Z890 Taichi → m2Slots=6, sataPorts=4 [high] asrock.com 공식 스펙 확정: M2_1 Gen5x4(CPU) + M2_2 Gen4x4(CPU) + M2_3~M2_6 Gen4x4(칩셋), 모두 Key M 스토리지 슬롯 = 6개. M2_3은 SATA3/PCIe 겸용. SATA3 6.0Gb/s 커넥터 4개. 무선랜
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '6'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('f7cf000d-462e-4aca-bb7e-2fa7b8dbd47c');

-- ASUS B850M MAX GAMING WIFI 라이젠 AM5 게이밍 메인보드 → m2Slots=3, sataPorts=4 [high] asus.com 스펙: M2_1 Key M PCIe5.0x4(9000/7000)·4.0x4(8000) + M2_2 Gen4x4 + M2_3 Gen4x2, 모두 Key M = 3개. SATA 6Gb/s 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('29a8e397-3fa6-4f41-b7b0-3b01435c6ba5');

-- ASUS PRIME B860-PLUS-CSM DDR5 → m2Slots=3, sataPorts=4 [high] ASUS 공식 techspec: M.2 3개(M-key 전부 스토리지) = PCIe5.0x4 + PCIe4.0x2 + PCIe4.0x4, SATA 6Gb/s 4포트. asus.com/.../prime-b860-plus-csm/techspec
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('8ec71197-e398-441d-898e-c325910e9899');

-- ASUS PRIME B860M-A-CSM → m2Slots=2, sataPorts=4 [high] ASUS 공식 techspec: M.2 2개(M-key) = PCIe5.0x4 + PCIe4.0x4, SATA 6Gb/s 4포트. asus.com/.../prime-b860m-a-csm/techspec
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('3627eaa2-9c8c-4d86-9960-9b0070493740');

-- ASUS PRIME X870-P WIFI-CSM → m2Slots=4, sataPorts=2 [high] ASUS 공식 techspec: M.2 4개(M-key) = 1×PCIe5.0 + 2×PCIe4.0 + 1×PCIe3.0, SATA 6Gb/s 2포트(M.2_4가 SATA와 대역폭 공유). asus.com/.../prime-x870-p-wifi-csm/techspec
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('9a7da847-a3b1-4308-ac70-2d64396a8667');

-- ASUS PRIME X870-P-CSM → m2Slots=4, sataPorts=2 [high] ASUS 공식 techspec: WIFI 변형과 스토리지 동일 — M.2 4개(1×PCIe5.0+2×PCIe4.0+1×PCIe3.0), SATA 6Gb/s 2포트. WIFI 유무만 차이. asus.com/.../prime-x870-p-csm/techspec
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('69defd78-382d-486d-a708-4b37e6890753');

-- ASUS ROG CROSSHAIR X870E HERO → m2Slots=5, sataPorts=4 [high] ROG 공식 spec: M.2 5개(M-key) = CPU직결 3×PCIe5.0x4 + X870E칩셋 2×PCIe4.0x4, SATA 6Gb/s 4포트. SlimSAS 커넥터는 M.2 미포함. rog.asus.com/.../rog-crosshair-x870e-hero/
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('8f3dc567-9be1-40fd-8576-e8b84ceceef4');

-- ASUS ROG STRIX X870-I GAMING WIFI → m2Slots=2, sataPorts=2 [high] ROG 공식 spec(Mini-ITX): M.2 2개(M-key) = PCIe5.0x4 + PCIe4.0x4, SATA 6Gb/s 2포트(ROG FPS Card 장착 시 사용). rog.asus.com/.../rog-strix-x870-i-gaming-wifi/spec
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013001', 'f0fba553-26d1-473f-8162-69c5d295d3f7');

-- ASUS ROG STRIX X870E-A GAMING WIFI7 NEO → m2Slots=4, sataPorts=4 [high] ROG 공식 스펙 기준: M.2_1/M.2_2 PCIe 5.0 x4(CPU) + M.2_3/M.2_4 PCIe 4.0 x4(X870E 칩셋) = 4개, SATA 6Gb/s 4포트. 두 차례 검색 일치.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('1691009e-d2aa-4d57-9e8f-219c24ad5544');

-- ASUS ROG STRIX X870E-E GAMING WIFI7 NEO → m2Slots=5, sataPorts=4 [high] ROG 공식+TechPowerUp/Newegg 교차확인: M.2 5개(CPU 직결 2 PCIe5.0 + 칩셋 3), SATA 6Gb/s 4포트. M.2_2는 USB4와 대역폭 공유.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('0f356022-df1d-4883-a3ab-5994c07f3f58');

-- ASUS ROG STRIX Z890-I GAMING WIFI → m2Slots=2, sataPorts=2 [high] Mini-ITX. ROG 공식 스펙: M.2 2개 모두 PCIe 5.0 x4(M.2_2는 PCIEX16과 공유), SATA 6Gb/s 2포트(FPS 카드로 제공). E-key 무선랜 슬롯 제외.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013005');

-- ASUS TUF B850M-PLUS WIFI7 W 화이트 라이젠 AM5 게이밍 메인보드 → m2Slots=3, sataPorts=4 [high] 공식명 TUF GAMING B850M-PLUS WIFI7 W(mATX, W=화이트 색상만 다름 스펙 동일). M.2 3개(CPU 1 PCIe5.0 + 칩셋 2 PCIe4.0), SATA 6Gb/s 4포트.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('8ed1ff8a-f637-4979-99ac-df1609f374a9');

-- ASUS TUF Gaming B850-PLUS WIFI → m2Slots=3, sataPorts=4 [high] ASUS 공식+Amazon(3x M.2): M.2 3개(M.2_1 PCIe5.0 x4, M.2_2 PCIe4.0, M.2_3 PCIe4.0 칩셋), SATA 6Gb/s 4포트. M.2_3는 PCIEX16(G4)과 공유.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('a8b6b23a-6a22-4f62-a20c-2e38f66955fd');

-- ASUS Z890 AYW GAMING WIFI W → m2Slots=4, sataPorts=4 [high] ASUS 공식+Newegg/Amazon(4x M.2): M.2 4개(M.2_1 PCIe5.0 x4 + M.2_2/3/4 PCIe4.0, M.2_4는 SATA 모드 겸용), SATA 6Gb/s 4포트.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('a190fd1d-65a0-449c-af1f-8cddf15d1f2f');

-- B650M Pro RS WiFi → m2Slots=3, sataPorts=4 [high] ASRock 공식 스펙: M2_1(Key M, PCIe Gen5x4)+M2_2(Key M, Gen4x4)+M2_3(Key M, Gen4x2)=3, SATA3 6Gb/s 4개. asrock.com B650M Pro RS WiFi 페이지.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000010021');

-- B650M WiFi DDR5 → m2Slots=2, sataPorts=4 [medium] 한국 유통 약칭. MSI B650M WiFi급 DDR5 보드(PRO B650M-A WIFI/B650M GAMING WIFI 등)는 모두 M.2 Key M 2개+SATA 4개로 동일. 단, 바로 그 단일 SKU를 유일 특정 불가라 medium.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000010002');

-- B850 Gaming Plus WiFi → m2Slots=3, sataPorts=4 [high] MSI 공식 스펙/데이터시트(ATX): M.2 Gen5x4+Gen4x4+Gen4x2=3, SATA 6Gb/s 4개. msi.com B850 GAMING PLUS WIFI 페이지.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011109');

-- GIGABYTE B850 AORUS ELITE WIFI7 ICE → m2Slots=3, sataPorts=4 [high] GIGABYTE 공식 스펙: M2A_CPU(Gen5)+M2B_CPU(Gen4)+M2C_SB(Gen4) 모두 M-key=3, SATA 6Gb/s 4개. ICE=화이트 색상판, 스펙 동일.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('b113d15f-584d-4ea1-8b2b-64867af44b55');

-- GIGABYTE B850M AORUS ELITE WIFI6E → m2Slots=2, sataPorts=4 [high] GIGABYTE 공식 스펙: M.2 M-key 2개(PCIe 5.0 x4 포함), SATA 6Gb/s 4개. mATX. gigabyte.com B850M AORUS ELITE WIFI6E 페이지.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013004');

-- GIGABYTE B860 AORUS ELITE WIFI7 ICE → m2Slots=3, sataPorts=4 [high] GIGABYTE 공식 스펙: CPU측 M.2 1개(PCIe 5.0 x4)+칩셋측 M.2 2개(PCIe 4.0 x4/x2) 모두 M-key=3, SATA 6Gb/s 4개. Intel LGA1851.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('9e13a684-f0e0-47f4-95ce-8ae0980c2f20');

-- GIGABYTE B860M AORUS ELITE WIFI6E → m2Slots=3, sataPorts=4 [high] GIGABYTE 스펙 페이지: M.2 1x PCIe 5.0 + 2x PCIe 4.0 = 3개, SATA 6Gb/s 4포트
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013008');

-- GIGABYTE B860M AORUS ELITE WIFI6E ICE → m2Slots=3, sataPorts=4 [high] ICE=화이트 색상 변형, 스펙 동일. Amazon 공식 리스팅 '3X M.2', SATA 4. 기본형과 일치
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('98e18b13-e9aa-4108-ad6c-800bbf9791ab', 'e9255609-8300-4eb3-a5c4-7b331b6de3c1');

-- GIGABYTE X870E AORUS PRO X3D ICE → m2Slots=4, sataPorts=4 [high] GIGABYTE 스펙: M.2 2x PCIe 5.0 + 2x PCIe 4.0 = 4개, SATA 6Gb/s 4포트
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('4369d913-82e8-4bd8-a9c9-6a4584400ced');

-- MAG B850 Tomahawk WiFi → m2Slots=3, sataPorts=4 [high] 비-MAX 모델. thefpsreview/GeekaWhat: M.2_1 Gen5(CPU)+M.2_2/3 Gen4(하나는 칩셋)=3개, SATA 4. MAX(4 M.2)와 구분됨
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011108');

-- MAG Z890 Tomahawk WiFi → m2Slots=4, sataPorts=4 [high] MSI 스펙: M.2_1 PCIe5.0 + M.2_2/3/4 PCIe4.0(M.2_4는 SATA모드 겸용)=4개 M-key, SATA 6Gb/s 4포트
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011103');

-- MSI B850 맥스 게임컴퓨터 조립용 시피유 라이젠메인보드 WIFI → m2Slots=4, sataPorts=4 [medium] 한국 유통 제네릭 제목. 다나와 'B850 맥스 WIFI' 주력 매칭=MAG B850 TOMAHAWK MAX WIFI: M.2 2xGen5+1xGen4x4+1xGen4x2=4개, SATA 4. 단 제목에 '토마호크' 누락되어 GAMING PLUS MAX WIFI(3 M
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('8e376394-0674-4cc7-8190-e0b83a1d07b8');

-- MSI MAG B850M 박격포 WIFI → m2Slots=3, sataPorts=4 [high] MSI 스펙(MAG-B850M-MORTAR-WIFI): M.2 3개(M2_1/M2_2 PCIe5.0x4, M2_3 후면 PCIe4.0x2), SATA 6Gb/s 4포트. AM5/B850 mATX. 다수 리뷰 교차 일치.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('2725bd70-b100-4d79-bbfc-8f4065426710');

-- MSI MAG B860 토마호크 WIFI → m2Slots=3, sataPorts=4 [high] MSI 스펙(MAG-B860-TOMAHAWK-WIFI): M.2 3개(M2_1 CPU PCIe5.0x4, M2_2/M2_3 칩셋 PCIe4.0x4), SATA 6Gb/s 4포트(B860 칩셋). LGA1851 ATX.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('a80adb79-a43e-45c7-86ed-6833d7df225a');

-- MSI MAG B860M 박격포 WIFI → m2Slots=3, sataPorts=4 [high] MSI 스펙(MAG-B860M-MORTAR-WIFI): M.2 3개(M2_1 PCIe5.0x4 + M2_2/M2_3 PCIe4.0x4), SATA 6Gb/s 4포트. LGA1851 mATX.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('ae5da261-b781-4368-b475-41ec0721f241');

-- MSI MAG X870 토마호크 WIFI → m2Slots=4, sataPorts=4 [high] MSI 스펙(MAG-X870-TOMAHAWK-WIFI): M.2 4개(M2_1/M2_2 CPU PCIe5.0x4, M2_3 PCIe4.0x2, M2_4 PCIe4.0x4), SATA 6Gb/s 4포트. AM5/X870.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('fc9206d5-fa86-4b00-9946-dedf08e70405');

-- MSI MAG X870E 토마호크 WIFI → m2Slots=4, sataPorts=4 [high] MSI 스펙(MAG-X870E-TOMAHAWK-WIFI): M.2 4개(M2_1/M2_2 PCIe5.0x4, M2_3/M2_4 PCIe4.0x4), SATA 6Gb/s 4포트(X870E 칩셋). AM5 ATX.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('54f80c83-1f99-42e8-bcd8-59b9fb797879', 'e2725e81-ca25-4a96-a2cc-0a4adf05a210');

-- MSI MPG B850 엣지 TI WIFI DDR5 AMD 메인보드 → m2Slots=4, sataPorts=4 [high] MSI 스펙(MPG-B850-EDGE-TI-WIFI): M.2 4개(M2_1/M2_2 PCIe5.0x4, M2_3 PCIe4.0x2, M2_4 PCIe4.0x4), SATA 6Gb/s 4포트(SATA_A1~A4). 한 리뷰가 6포트로 오기했으나 MSI 커넥터 라벨 A1
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('a67eedd4-3cb1-4803-8e16-e8e00301b37c');

-- MSI MPG B850I EDGE TI WIFI → m2Slots=2, sataPorts=2 [high] MSI 공식 스펙(msi.com/Motherboard/MPG-B850I-EDGE-TI-WIFI/Specification): ITX AM5. M.2 2개(M2_1 PCIe5.0x4 CPU직결 + M2_2 PCIe4.0x4 칩셋, 후면), SATA 6Gb/s 2포트. Ge
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013002');

-- MSI MPG X870E 카본 WIFI → m2Slots=4, sataPorts=4 [high] MSI 공식 스펙(MPG-X870E-CARBON-WIFI): ATX AM5. M.2 4개(M2_1/M2_2 PCIe5.0x4, M2_3/M2_4 PCIe4.0x4), SATA 6Gb/s 4포트(X870E 칩셋). 색상 무관 동일.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('70a19510-7f21-4ad3-b070-09ab35bb3c52');

-- MSI MPG Z890 카본 WIFI → m2Slots=5, sataPorts=4 [high] MSI 공식 스펙(MPG-Z890-CARBON-WIFI): ATX LGA1851. M.2 5개(M2_1 PCIe5.0x4 CPU, M2_2 PCIe4.0x4 CPU, M2_3/4/5 PCIe4.0x4 칩셋), SATA 6Gb/s 4포트. Vortez 리뷰 교차확인.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('13f1c2a4-097c-41c0-b9ae-c5ff80d59c01');

-- MSI MPG Z890I EDGE TI WIFI → m2Slots=4, sataPorts=2 [medium] ITX LGA1851. 보드 온보드 고정 하드웨어 기준: M.2 4개(M2_1 PCIe5.0x4 CPU + 3x PCIe4.0x4) + SATA 6Gb/s 2포트. 번들 5-in-1 XPANDER 카드 장착 시 M.2 +1(총5), SATA +2(총4)로 MSI 스펙표
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000013006');

-- MSI PRO B860M-A WIFI → m2Slots=3, sataPorts=4 [high] MSI 공식 스펙(PRO-B860M-A-WIFI): mATX LGA1851. M.2 3개(M2_1 PCIe5.0x4, M2_2 PCIe4.0x4, M2_3 PCIe4.0x2), SATA 6Gb/s 4포트.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('26112d72-dbe9-4b56-8b07-62888d26cb9f');

-- MSI PRO Z890-A WIFI → m2Slots=4, sataPorts=4 [high] MSI 공식 스펙(PRO-Z890-A-WIFI): ATX LGA1851. M.2 4개(M2_1 PCIe5.0x4, M2_2/3/4 PCIe4.0x4, M2_4는 SATA모드 겸용), SATA 6Gb/s 4포트(SATA1~4 Z890 칩셋).
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('5a0933d2-41b2-4b43-a0e0-d023c058b9a7');

-- MSI Z890 유니파이-X 게임PC 조립용 메인보드 ATX → m2Slots=6, sataPorts=6 [high] MSI MEG Z890 UNIFY-X. 다수 독립 소스가 공식 스펙 재확인: M.2 6개 전부 M-key(Key M) 스토리지 슬롯(1x Gen5 x4 + 5x Gen4 x4, M2_4는 Mux로 CPU Gen5 전환 가능). WiFi7은 별도 E-key라 미포함. S
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '6'::jsonb, true), '{sataPorts}', '6'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('b031abc3-d33b-4893-9c47-93c25aa65538');

-- ROG Crosshair X870E Hero → m2Slots=5, sataPorts=4 [high] rog.asus.com 스펙: M.2 5개(CPU 3 + X870E 칩셋 2, 전부 Key M). SATA 6Gb/s 4개. SlimSAS(PCIe 4.0 x4)는 별도로 SATA 포트에 미포함.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011105');

-- ROG Maximus Z890 Hero → m2Slots=6, sataPorts=4 [high] 확정. rog.asus.com 공식 스펙(다중 소스 교차확인): M.2 6개 전부 Key M 스토리지 = PCIe5.0 x4 3개(M.2_1/3/4) + PCIe4.0 x4 3개(M.2_2/5/6). SATA 6Gb/s 4개 + 별도 SlimSAS(PCIe4.0 x4 
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '6'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011101');

-- ROG Strix X870E-E Gaming WiFi → m2Slots=5, sataPorts=4 [high] rog.asus.com 스펙 + Newegg: M.2 5개(Key M; PCIe5 x3 + PCIe4 x2). SATA 6Gb/s 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011106');

-- ROG Strix Z890-A Gaming WiFi → m2Slots=5, sataPorts=4 [high] rog.asus.com 스펙 + Newegg(5x M.2): M.2 5개(Key M; PCIe5 x1 + PCIe4 x4, M.2_5는 SATA모드 겸용). SATA 6Gb/s 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '5'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011102');

-- X870E AORUS Master → m2Slots=4, sataPorts=4 [high] gigabyte.com 스펙: M.2 4개(M2A/M2B/M2C_CPU 3 + M2D_SB 칩셋 1, 전부 M key; PCIe5 x3). SATA 6Gb/s 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011107');

-- Z790 UD AX DDR5 → m2Slots=3, sataPorts=6 [high] GIGABYTE 스펙(검색 인용): 3*PCIe 4.0 x4 M.2, 6 x SATA 6Gb/s. Newegg/Amazon 'Triple M.2' 교차확인. PCIe5.0 x16는 NVMe 가능하나 전용 M.2 M-key 커넥터 아님이라 제외.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '3'::jsonb, true), '{sataPorts}', '6'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000010022');

-- Z890 AORUS Elite WiFi7 → m2Slots=4, sataPorts=4 [high] GIGABYTE 스펙(검색 인용) 슬롯 전수: M2A_CPU(PCIe5.0)+M2B_CPU(PCIe4.0)+M2Q_SB(PCIe4.0)+M2M_SB(SATA/PCIe4.0)=4 M-key, 4 x SATA 6Gb/s.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('00000000-0000-4000-8000-000000011104');

-- 기가바이트 B850M AORUS ELITE WIFI6E ICE → m2Slots=2, sataPorts=4 [high] micro-ATX. GIGABYTE 스펙(검색 인용): M2A_CPU(PCIe5.0 x4)+M2B_CPU(PCIe4.0 x4)=2 M-key, 4 x SATA 6Gb/s. Amazon '2X M.2' 교차확인.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('3820f1e8-e190-4086-b545-9dc38c75e22f');

-- 기가바이트 B850M AORUS ELITE WIFI7 ICE-P → m2Slots=2, sataPorts=4 [medium] micro-ATX ICE-P. 검색 인용된 ICE-P 스펙: M2A_CPU(PCIe5.0)+M2B_CPU(PCIe4.0)=2 M-key, 4 x SATA. ATX 'B850 ...ICE'(3 M.2)와 명칭 혼동 주의. 제조사 페이지 직접 열람 불가(도메인 차단)라 m
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('fe45227b-a58c-4b67-8fa5-15ea923e499a');

-- 기가바이트 X870 AORUS ELITE WIFI7 → m2Slots=4, sataPorts=4 [high] GIGABYTE 스펙(검색 인용) 슬롯 전수: M2A/M2B/M2C_CPU(PCIe5.0 x3)+M2D_SB(PCIe4.0)=4 M-key, 4 x SATA 6Gb/s. TalosPC/Vortez/Newegg '4x M.2' 교차확인.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('3c6c4ac8-cc42-4682-9faf-03a5aee3b649');

-- 기가바이트 X870 AORUS ELITE WIFI7 ICE → m2Slots=4, sataPorts=4 [high] 일반판 X870 ELITE WIFI7의 화이트 색상 변형, 스펙 동일. Amazon/Newegg '4X M.2', 공유 매뉴얼(mb_manual_x870-aorus-elite-wifi7-ice) 확인: 4 M.2, 4 x SATA 6Gb/s.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('cdf69044-f406-4649-b036-2d9bce66fbec');

-- 기가바이트 X870E AORUS ELITE X3D , 화이트 → m2Slots=4, sataPorts=4 [high] GIGABYTE 공식 스펙(X870E-AORUS-ELITE-X3D/sp): 2x PCIe5.0(CPU)+2x PCIe4.0(칩셋 M2C_SB/M2D_SB) = M-key 4개, SATA 6Gb/s 4개. 화이트는 색상 변형으로 스펙 동일.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('d25077b4-0198-45b7-a273-e4604e2bc74d');

-- 기가바이트 X870E AORUS PRO ICE → m2Slots=4, sataPorts=4 [high] GIGABYTE 공식 스펙(X870E-AORUS-PRO-ICE): 3x PCIe5.0 x4 + 1x PCIe4.0 x4 = M-key 4개, SATA 6Gb/s 4개. Newegg/Amazon 리스팅도 4x M.2 일치.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('deda83e0-1c62-4b9d-8316-b58c77be9a19');

-- 기가바이트 Z890 AORUS ELITE WIFI7 → m2Slots=4, sataPorts=4 [high] GIGABYTE 공식 스펙(Z890-AORUS-ELITE-WIFI7/sp): CPU PCIe5.0 1 + CPU PCIe4.0 1 + 칩셋 PCIe4.0 1 + 칩셋(SATA/PCIe4.0 겸용) 1 = M-key 4개, SATA 6Gb/s 4개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('90f17bb2-ed9d-43f6-95b0-01811fc978fc');

-- 기가바이트 Z890 AORUS ELITE WIFI7 ICE → m2Slots=4, sataPorts=4 [high] GIGABYTE 공식 스펙(Z890-AORUS-ELITE-WIFI7-ICE/sp): M-key 4개(1x PCIe5.0 CPU + 나머지 PCIe4.0, 1개 SATA 겸용), SATA 6Gb/s 4개. ICE는 색상만 차이로 비-ICE와 동일.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '4'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('5e5b91a3-1484-4176-a411-87c7d945dac9');

-- 기가바이트 Z890I AORUS ULTRA → m2Slots=2, sataPorts=2 [high] GIGABYTE 공식 스펙(Z890I-AORUS-ULTRA/sp): Mini-ITX, M2A_CPU PCIe5.0 1개 + 후면 M2Q_SB PCIe4.0 1개 = M-key 2개, SATA 6Gb/s 2개.
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '2'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('32d4c63f-1e22-4aa7-8b1b-6aade10968fd');

-- 에이수스 ASUS B860M AYW GAMING WIFI 인텔 1851소켓 D5 메인보드 → m2Slots=2, sataPorts=4 [high] ASUS 공식 스펙(b860m-ayw-gaming-wifi/techspec): M.2_1(M-key PCIe5.0 x4) + M.2_2(M-key PCIe4.0 x4) = 2개, SATA 6Gb/s 4개. M.2_2 SATA 모드 시 SATA_2 비활성(공유).
UPDATE parts SET attributes = jsonb_set(jsonb_set(coalesce(attributes, '{}'::jsonb), '{m2Slots}', '2'::jsonb, true), '{sataPorts}', '4'::jsonb, true)
WHERE category = 'MOTHERBOARD' AND public_id IN ('a2d6ed2a-fd32-4906-852f-ae33f2ab08b4');

