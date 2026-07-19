from __future__ import annotations


LIVE_DATA_MODE = "LIVE"
DEMO_DATA_MODE = "DEMO"

GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID = "GRAPHICS_CODE43_REMOTE_SUPPORT"
GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM = (
    "게임 중 화면이 잠깐 꺼졌다가 다시 켜지고,\n"
    "이후 게임이 심하게 느려졌어요."
)


def demo_scenario_id(mode: str, symptom: str) -> str | None:
    del symptom
    if str(mode or "").strip().upper() != DEMO_DATA_MODE:
        return None
    return GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
