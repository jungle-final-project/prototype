import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "docs" / "agent-as" / "fixtures" / "scenarios"


SCENARIOS = [
    ("REMOTE_AGENT_001", "REMOTE_AGENT", "Agent 설치 후 등록 실패", ["AGENT_HEALTH"], "REMOTE_POSSIBLE", "AGENT_INSTALL_OR_UPLOAD_FAILURE"),
    ("REMOTE_AGENT_002", "REMOTE_AGENT", "Agent token 만료/401 업로드 실패", ["AGENT_HEALTH"], "REMOTE_POSSIBLE", "CHECK_AGENT_CONFIG"),
    ("REMOTE_AGENT_003", "REMOTE_AGENT", "업로드 중 409 idempotency 충돌", ["AGENT_HEALTH"], "REMOTE_POSSIBLE", "AGENT_INSTALL_OR_UPLOAD_FAILURE"),
    ("REMOTE_AGENT_004", "REMOTE_AGENT", "config schema 오류", ["AGENT_HEALTH"], "REMOTE_POSSIBLE", "CHECK_AGENT_CONFIG"),
    ("REMOTE_AGENT_005", "REMOTE_AGENT", "heartbeat 누락 후 tray 재시작", ["AGENT_HEALTH"], "REMOTE_POSSIBLE", "AGENT_INSTALL_OR_UPLOAD_FAILURE"),
    ("REMOTE_DRIVER_OS_006", "REMOTE_DRIVER_OS", "게임 중 display driver reset", ["EVENT_LOG", "SYSTEM_METRIC"], "REMOTE_POSSIBLE", "DRIVER_CRASH_LOG"),
    ("REMOTE_DRIVER_OS_007", "REMOTE_DRIVER_OS", "Windows Update 직후 장치 오류", ["EVENT_LOG"], "REMOTE_POSSIBLE", "DRIVER_CRASH_LOG"),
    ("REMOTE_DRIVER_OS_008", "REMOTE_DRIVER_OS", "그래픽 드라이버 설치 실패 반복", ["EVENT_LOG", "AGENT_HEALTH"], "REMOTE_POSSIBLE", "REINSTALL_GRAPHICS_DRIVER"),
    ("REMOTE_DRIVER_OS_009", "REMOTE_DRIVER_OS", "장치 관리자 오류 코드 발생", ["EVENT_LOG"], "REMOTE_POSSIBLE", "DRIVER_CRASH_LOG"),
    ("REMOTE_DRIVER_OS_010", "REMOTE_DRIVER_OS", "드라이버 오류와 온도 상승 동시 발생", ["EVENT_LOG", "THERMAL_SENSOR"], "REMOTE_POSSIBLE", "DRIVER_CRASH_LOG"),
    ("VISIT_WHEA_BSOD_011", "REMOTE_DRIVER_OS", "드라이버 오류 후 WHEA도 반복", ["EVENT_LOG"], "VISIT_REQUIRED", "SUSPECTED_HARDWARE_FAILURE"),
    ("REMOTE_APP_LAUNCHER_012", "REMOTE_APP_LAUNCHER", "게임 런처 실행 실패", ["EVENT_LOG", "PROCESS_CATEGORY"], "REMOTE_POSSIBLE", "APP_SPECIFIC_FAILURE"),
    ("REMOTE_APP_LAUNCHER_013", "REMOTE_APP_LAUNCHER", "runtime missing 오류", ["EVENT_LOG"], "REMOTE_POSSIBLE", "CHECK_RUNTIME_OR_PERMISSION"),
    ("REMOTE_APP_LAUNCHER_014", "REMOTE_APP_LAUNCHER", "권한 오류로 앱 실행 실패", ["EVENT_LOG"], "REMOTE_POSSIBLE", "APP_SPECIFIC_FAILURE"),
    ("REMOTE_APP_LAUNCHER_015", "REMOTE_APP_LAUNCHER", "저장공간 부족으로 업데이트 실패", ["EVENT_LOG", "SYSTEM_METRIC"], "REMOTE_POSSIBLE", "APP_SPECIFIC_FAILURE"),
    ("REMOTE_STORAGE_MEMORY_016", "REMOTE_STORAGE_MEMORY", "PC가 전체적으로 느림", ["SYSTEM_METRIC", "PROCESS_CATEGORY"], "REMOTE_POSSIBLE", "MEMORY_PRESSURE"),
    ("REMOTE_STORAGE_MEMORY_017", "REMOTE_STORAGE_MEMORY", "RAM 사용률 95% 이상 반복", ["SYSTEM_METRIC"], "REMOTE_POSSIBLE", "MEMORY_PRESSURE"),
    ("REMOTE_STORAGE_MEMORY_018", "REMOTE_STORAGE_MEMORY", "디스크 active time 100% 반복", ["SYSTEM_METRIC", "STORAGE_HEALTH"], "REMOTE_POSSIBLE", "STORAGE_IO_BOTTLENECK"),
    ("REMOTE_STORAGE_MEMORY_019", "REMOTE_STORAGE_MEMORY", "저장공간 3% 이하", ["STORAGE_HEALTH"], "REMOTE_POSSIBLE", "CHECK_STORAGE_HEALTH"),
    ("REMOTE_STORAGE_MEMORY_020", "REMOTE_STORAGE_MEMORY", "메모리 압박과 앱 crash 동시 발생", ["SYSTEM_METRIC", "EVENT_LOG"], "REMOTE_POSSIBLE", "MEMORY_PRESSURE"),
    ("REMOTE_STARTUP_SERVICE_021", "REMOTE_STARTUP_SERVICE", "부팅 직후 CPU/RAM 높음", ["SYSTEM_METRIC", "PROCESS_CATEGORY"], "REMOTE_POSSIBLE", "BACKGROUND_SERVICE_PRESSURE"),
    ("REMOTE_STARTUP_SERVICE_022", "REMOTE_STARTUP_SERVICE", "특정 백그라운드 서비스 crash loop", ["EVENT_LOG", "PROCESS_CATEGORY"], "REMOTE_POSSIBLE", "CHECK_STARTUP_APPS"),
    ("REMOTE_STARTUP_SERVICE_023", "REMOTE_STARTUP_SERVICE", "유휴 상태인데 CPU 사용률 높음", ["SYSTEM_METRIC", "PROCESS_CATEGORY"], "REMOTE_POSSIBLE", "BACKGROUND_SERVICE_PRESSURE"),
    ("REMOTE_STARTUP_SERVICE_024", "REMOTE_STARTUP_SERVICE", "시작 프로그램 영향으로 부팅 후 느림", ["PROCESS_CATEGORY", "BOOT_SHUTDOWN"], "REMOTE_POSSIBLE", "CHECK_STARTUP_APPS"),
    ("REMOTE_LOCAL_NETWORK_025", "REMOTE_LOCAL_NETWORK", "DNS 실패", ["NETWORK_DIAGNOSTIC"], "REMOTE_POSSIBLE", "LOCAL_NETWORK_CONFIG"),
    ("REMOTE_LOCAL_NETWORK_026", "REMOTE_LOCAL_NETWORK", "gateway unreachable", ["NETWORK_DIAGNOSTIC"], "REMOTE_POSSIBLE", "LOCAL_NETWORK_CONFIG"),
    ("REMOTE_LOCAL_NETWORK_027", "REMOTE_LOCAL_NETWORK", "NIC driver error", ["NETWORK_DIAGNOSTIC", "EVENT_LOG"], "REMOTE_POSSIBLE", "CHECK_ADAPTER_DRIVER"),
    ("UNKNOWN_028", "REMOTE_LOCAL_NETWORK", "외부 ISP 장애 의심", ["NETWORK_DIAGNOSTIC"], "NEEDS_MORE_INFO", "OUT_OF_PC_SCOPE"),
    ("VISIT_BOOT_REMOTE_BLOCKED_029", "VISIT_BOOT_REMOTE_BLOCKED", "부팅 불가, heartbeat 장기 누락", ["BOOT_SHUTDOWN", "AGENT_HEALTH"], "VISIT_REQUIRED", "DEVICE_OFFLINE"),
    ("VISIT_BOOT_REMOTE_BLOCKED_030", "VISIT_BOOT_REMOTE_BLOCKED", "원격 연결 자체 불가", ["AGENT_HEALTH"], "VISIT_REQUIRED", "REMOTE_HELP_NOT_AVAILABLE"),
    ("VISIT_BOOT_REMOTE_BLOCKED_031", "VISIT_BOOT_REMOTE_BLOCKED", "마지막 정상 부팅 후 critical event", ["BOOT_SHUTDOWN", "EVENT_LOG"], "VISIT_REQUIRED", "DEVICE_OFFLINE"),
    ("VISIT_DISK_FAILURE_032", "VISIT_DISK_FAILURE", "SMART critical", ["STORAGE_HEALTH"], "VISIT_REQUIRED", "STORAGE_REPLACEMENT_SUSPECTED"),
    ("VISIT_DISK_FAILURE_033", "VISIT_DISK_FAILURE", "bad block 반복", ["STORAGE_HEALTH", "EVENT_LOG"], "VISIT_REQUIRED", "STORAGE_REPLACEMENT_SUSPECTED"),
    ("VISIT_DISK_FAILURE_034", "VISIT_DISK_FAILURE", "filesystem write failure 반복", ["EVENT_LOG", "STORAGE_HEALTH"], "VISIT_REQUIRED", "DATA_LOSS_RISK"),
    ("VISIT_DISK_FAILURE_035", "VISIT_DISK_FAILURE", "디스크 I/O 오류와 부팅 지연", ["STORAGE_HEALTH", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "STORAGE_REPLACEMENT_SUSPECTED"),
    ("VISIT_WHEA_BSOD_036", "VISIT_WHEA_BSOD", "WHEA event 반복", ["EVENT_LOG"], "VISIT_REQUIRED", "SUSPECTED_HARDWARE_FAILURE"),
    ("VISIT_WHEA_BSOD_037", "VISIT_WHEA_BSOD", "bugcheck/BSOD 반복", ["EVENT_LOG", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "BSOD_SIGNATURE"),
    ("VISIT_WHEA_BSOD_038", "VISIT_WHEA_BSOD", "memory hardware error", ["EVENT_LOG", "SYSTEM_METRIC"], "VISIT_REQUIRED", "SUSPECTED_HARDWARE_FAILURE"),
    ("VISIT_WHEA_BSOD_039", "VISIT_WHEA_BSOD", "BSOD와 thermal 신호 동시 발생", ["EVENT_LOG", "THERMAL_SENSOR"], "VISIT_REQUIRED", "HARDWARE_ERROR_RISK"),
    ("VISIT_POWER_SHUTDOWN_040", "VISIT_POWER_SHUTDOWN", "게임 중 전원 꺼짐", ["BOOT_SHUTDOWN", "SYSTEM_METRIC"], "VISIT_REQUIRED", "PSU_OR_POWER_PATH_RISK"),
    ("VISIT_POWER_SHUTDOWN_041", "VISIT_POWER_SHUTDOWN", "Kernel-Power 반복", ["EVENT_LOG", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "POWER_PATH_RISK"),
    ("VISIT_POWER_SHUTDOWN_042", "VISIT_POWER_SHUTDOWN", "부하 직전 unexpected shutdown", ["SYSTEM_METRIC", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "PSU_POWER_EVENT"),
    ("VISIT_POWER_SHUTDOWN_043", "VISIT_POWER_SHUTDOWN", "전원 꺼짐과 driver crash 혼재", ["EVENT_LOG", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "PSU_OR_POWER_PATH_RISK"),
    ("VISIT_FAN_THERMAL_044", "VISIT_FAN_THERMAL", "fan rpm 0", ["THERMAL_SENSOR"], "VISIT_REQUIRED", "THERMAL_SERVICE_REQUIRED"),
    ("VISIT_FAN_THERMAL_045", "VISIT_FAN_THERMAL", "thermal shutdown", ["THERMAL_SENSOR", "BOOT_SHUTDOWN"], "VISIT_REQUIRED", "THERMAL_DAMAGE_RISK"),
    ("VISIT_FAN_THERMAL_046", "VISIT_FAN_THERMAL", "GPU thermal throttle 반복", ["THERMAL_SENSOR", "SYSTEM_METRIC"], "VISIT_REQUIRED", "GPU_THERMAL_THROTTLE"),
    ("VISIT_FAN_THERMAL_047", "VISIT_FAN_THERMAL", "과열 + 팬 반응 없음", ["THERMAL_SENSOR"], "VISIT_REQUIRED", "STOP_USE_UNTIL_REVIEW"),
    ("UNKNOWN_048", "UNKNOWN", "로그 범위 부족", ["SYSTEM_METRIC"], "NEEDS_MORE_INFO", "INSUFFICIENT_EVIDENCE"),
    ("UNKNOWN_049", "UNKNOWN", "게임별 FPS 튜닝 요청", ["SYSTEM_METRIC"], "NEEDS_MORE_INFO", "UNSUPPORTED_SCOPE"),
    ("UNKNOWN_050", "UNKNOWN", "데이터 복구/물리 파손/불법 SW 요청", ["EVENT_LOG"], "NEEDS_MORE_INFO", "DATA_RECOVERY_REQUIRED"),
]


REMOTE_ACTION_BY_REASON = {
    "CHECK_AGENT_CONFIG": "CHECK_AGENT_CONFIG",
    "REINSTALL_GRAPHICS_DRIVER": "REINSTALL_GRAPHICS_DRIVER",
    "CHECK_RUNTIME_OR_PERMISSION": "CHECK_RUNTIME_OR_PERMISSION",
    "CHECK_STORAGE_HEALTH": "CHECK_STORAGE_HEALTH",
    "CHECK_STARTUP_APPS": "CHECK_STARTUP_APPS",
    "CHECK_ADAPTER_DRIVER": "CHECK_ADAPTER_DRIVER",
}


VISIT_REASON_BY_REASON = {
    "SUSPECTED_HARDWARE_FAILURE": "NEEDS_BENCH_REPRODUCTION",
    "STORAGE_REPLACEMENT_SUSPECTED": "STORAGE_REPLACEMENT_SUSPECTED",
    "DATA_LOSS_RISK": "DATA_RECOVERY_REQUIRED",
    "BSOD_SIGNATURE": "BSOD_SIGNATURE",
    "HARDWARE_ERROR_RISK": "SUSPECTED_HARDWARE_FAILURE",
    "PSU_OR_POWER_PATH_RISK": "PSU_OR_POWER_PATH_RISK",
    "POWER_PATH_RISK": "PSU_OR_POWER_PATH_RISK",
    "PSU_POWER_EVENT": "PSU_OR_POWER_PATH_RISK",
    "THERMAL_SERVICE_REQUIRED": "THERMAL_SERVICE_REQUIRED",
    "THERMAL_DAMAGE_RISK": "THERMAL_SERVICE_REQUIRED",
    "GPU_THERMAL_THROTTLE": "THERMAL_SERVICE_REQUIRED",
    "STOP_USE_UNTIL_REVIEW": "THERMAL_SERVICE_REQUIRED",
    "DEVICE_OFFLINE": "DEVICE_OFFLINE",
    "REMOTE_HELP_NOT_AVAILABLE": "REMOTE_HELP_NOT_AVAILABLE",
}


def payload_for(kind, scenario_id, description, reason_code):
    if kind == "SYSTEM_METRIC":
        return {
            "cpuUsagePercent": 67.4,
            "memoryUsedPercent": 88.1,
            "diskActivePercent": 92.3 if "STORAGE" in scenario_id else 42.5,
            "gpuUsagePercent": 74.9,
            "sampleIntervalSeconds": 5,
        }
    if kind == "EVENT_LOG":
        return {
            "source": "System",
            "eventId": 4101 if "DRIVER" in scenario_id else 1001,
            "level": "WARN",
            "messageMasked": f"{reason_code} signal observed for scenario {scenario_id}.",
            "eventTime": "2026-07-03T01:00:00Z",
        }
    if kind == "STORAGE_HEALTH":
        return {
            "diskIdHash": f"disk-{scenario_id.lower()}",
            "smartStatus": "WARN",
            "criticalWarnings": 1,
            "ioLatencyMs": 235,
            "freeSpacePercent": 3 if "019" in scenario_id else 18,
        }
    if kind == "THERMAL_SENSOR":
        return {
            "cpuTemperatureCelsius": 92.0,
            "gpuTemperatureCelsius": 96.0,
            "fanRpm": 0 if "044" in scenario_id or "047" in scenario_id else 780,
            "thermalThrottle": True,
            "unavailableReason": None,
        }
    if kind == "BOOT_SHUTDOWN":
        return {
            "bootTime": "2026-07-03T00:30:00Z",
            "shutdownTime": "2026-07-03T00:52:00Z",
            "shutdownType": "UNEXPECTED",
            "unexpected": True,
            "kernelPowerDetected": "POWER" in scenario_id,
        }
    if kind == "PROCESS_CATEGORY":
        return {
            "category": "GAME_OR_LAUNCHER" if "APP" in scenario_id else "BACKGROUND_SERVICE",
            "cpuUsagePercent": 31.0,
            "memoryUsedMb": 2048,
            "processNameHash": f"process-{scenario_id.lower()}",
            "allowlistedName": "game.exe" if "APP" in scenario_id else "system-service",
        }
    if kind == "NETWORK_DIAGNOSTIC":
        return {
            "adapterStatus": "UP",
            "gatewayReachable": "026" not in scenario_id,
            "dnsLookupOk": "025" not in scenario_id,
            "packetLossPercent": 18 if "026" in scenario_id else 0,
            "latencyMs": 44,
        }
    if kind == "AGENT_HEALTH":
        return {
            "agentVersion": "0.1.0",
            "serviceStatus": "WARN",
            "trayStatus": "RUNNING",
            "lastUploadErrorCode": "HTTP_401" if "002" in scenario_id else None,
            "configSchemaVersion": "1",
        }
    return {"description": description}


def risk_level(decision, reason_code):
    if decision == "VISIT_REQUIRED":
        return "HIGH"
    if reason_code in {"OUT_OF_PC_SCOPE", "UNSUPPORTED_SCOPE", "INSUFFICIENT_EVIDENCE"}:
        return "LOW"
    return "MEDIUM"


def safety(decision, reason_code):
    if decision == "VISIT_REQUIRED":
        return (
            "HIGH",
            [
                {
                    "code": "STOP_USE_UNTIL_ADMIN_REVIEW",
                    "message": "추가 손상을 막기 위해 관리자 확인 전 고부하 사용을 중단하세요.",
                }
            ],
        )
    if reason_code in {"DATA_RECOVERY_REQUIRED", "PHYSICAL_DAMAGE_POLICY_REQUIRED"}:
        return (
            "MEDIUM",
            [
                {
                    "code": "BACKUP_REQUIRED",
                    "message": "데이터 손실 가능성이 있으므로 중요한 파일을 먼저 백업하세요.",
                }
            ],
        )
    return ("LOW", [])


def write_json(path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_scenario(index, scenario):
    scenario_id, symptom_type, description, kinds, decision, reason_code = scenario
    scenario_dir = FIXTURE_ROOT / scenario_id
    scenario_dir.mkdir(parents=True, exist_ok=True)
    risk = risk_level(decision, reason_code)
    safety_level, safety_notices = safety(decision, reason_code)
    remote_actions = []
    if decision == "REMOTE_POSSIBLE":
        remote_actions.append(REMOTE_ACTION_BY_REASON.get(reason_code, "CHECK_EVENT_VIEWER"))
    visit_reasons = []
    if decision == "VISIT_REQUIRED":
        visit_reasons.append(VISIT_REASON_BY_REASON.get(reason_code, "NEEDS_BENCH_REPRODUCTION"))
    blocking_factors = []
    if decision == "NEEDS_MORE_INFO":
        blocking_factors.append(reason_code)
    if reason_code in {"DATA_LOSS_RISK", "DATA_RECOVERY_REQUIRED"}:
        blocking_factors.append("DATA_RECOVERY_REQUIRED")

    raw_rows = []
    for sequence, kind in enumerate(kinds, start=1):
        raw_rows.append(
            {
                "schemaVersion": "1",
                "collectedAt": f"2026-07-03T01:{index % 60:02d}:{sequence:02d}Z",
                "agentId": f"fixture-agent-{index:03d}",
                "deviceIdHash": f"device-{index:03d}",
                "sequence": sequence,
                "kind": kind,
                "payload": payload_for(kind, scenario_id, description, reason_code),
                "privacyFlags": {
                    "masked": True,
                    "containsRawPath": False,
                },
            }
        )
    (scenario_dir / "raw.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in raw_rows) + "\n",
        encoding="utf-8",
    )

    write_json(
        scenario_dir / "expected-log-summary.json",
        {
            "summaryVersion": "1",
            "scenarioId": scenario_id,
            "symptomType": symptom_type,
            "summaryText": description,
            "incidentWindow": {
                "startedAt": "2026-07-03T01:00:00Z",
                "endedAt": "2026-07-03T01:30:00Z",
                "rangeMinutes": 30,
            },
            "signalKinds": kinds,
            "reasonCodes": [reason_code],
            "safetyNotices": safety_notices,
            "dataQuality": "ENOUGH" if decision != "NEEDS_MORE_INFO" else "PARTIAL",
            "rawSamplePolicy": {"maxSamples": 20, "rawFullLogIncluded": False},
        },
    )
    write_json(
        scenario_dir / "expected-routing.json",
        {
            "recommendedDecision": decision,
            "supportDecision": decision,
            "riskLevel": risk,
            "confidence": "HIGH" if decision == "VISIT_REQUIRED" else "MEDIUM",
            "reasonCodes": [reason_code],
            "remoteActions": remote_actions,
            "visitReasons": visit_reasons,
            "blockingFactors": blocking_factors,
            "safetyAdviceLevel": safety_level,
            "safetyNotices": safety_notices,
            "requiresAdminApproval": True,
            "allowAutoResponse": False,
        },
    )
    write_json(
        scenario_dir / "expected-ai-result.json",
        {
            "contractVersion": "1",
            "supportDecision": decision,
            "riskLevel": risk,
            "confidence": "HIGH" if decision == "VISIT_REQUIRED" else "MEDIUM",
            "reasonCodes": [reason_code],
            "causeCandidates": [
                {
                    "label": reason_code,
                    "confidence": "HIGH" if decision == "VISIT_REQUIRED" else "MEDIUM",
                    "reason": description,
                }
            ],
            "nextActions": [
                {
                    "label": "관리자 검토",
                    "priority": "HIGH" if decision == "VISIT_REQUIRED" else "MEDIUM",
                    "instruction": "AI 제안은 확정값이 아니므로 관리자 승인 후 사용자 안내에 반영합니다.",
                }
            ],
            "remoteActions": remote_actions,
            "visitReasons": visit_reasons,
            "blockingFactors": blocking_factors,
            "requiredAdditionalLogs": [] if decision != "NEEDS_MORE_INFO" else ["더 긴 incident window 로그"],
            "evidenceRefs": [f"{scenario_id}:raw:{kind}" for kind in kinds],
            "toolRefs": [],
            "unsafeActionsExcluded": ["원본 전체 로그 반환", "전체 프로세스 목록 학습"],
            "adminReviewRequired": True,
            "userFirstNotice": {
                "title": "AS 로그 기반 1차 진단 제안",
                "summary": description,
                "safeActions": ["진행 중인 작업을 저장하고 관리자 검토를 기다리세요."],
                "additionalQuestions": [] if decision != "NEEDS_MORE_INFO" else ["문제가 발생한 정확한 시간대를 알려주세요."],
            },
            "proposedTicketPatch": {},
        },
    )
    write_json(
        scenario_dir / "admin-label.json",
        {
            "reviewStatus": "APPROVED",
            "finalSupportDecision": decision,
            "diagnosticAccuracy": "ACCURATE",
            "actualResolution": "REMOTE_RESOLVED" if decision == "REMOTE_POSSIBLE" else "VISIT_REQUIRED" if decision == "VISIT_REQUIRED" else "NEEDS_MORE_INFO",
            "eligibleForTraining": True,
            "rawFullLogIncluded": False,
            "labelSource": "fixture",
            "notes": "Raw gzip/full JSONL 대신 ticket-level summary/routing/result label만 학습에 사용한다.",
        },
    )


def main():
    FIXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for index, scenario in enumerate(SCENARIOS, start=1):
        build_scenario(index, scenario)
    print(f"Generated {len(SCENARIOS)} AS AI fixture scenarios under {FIXTURE_ROOT}")


if __name__ == "__main__":
    main()
