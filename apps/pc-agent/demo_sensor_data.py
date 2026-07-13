from __future__ import annotations


DEMO_SENSOR_SAMPLES: tuple[dict[str, object], ...] = (
    {
        "cpuUsagePercent": 32.0,
        "cpuTempCelsius": 58.0,
        "cpuClockMhz": 4200.0,
        "gpuUsagePercent": 78.0,
        "gpuTempCelsius": 82.0,
        "gpuFanRpm": 0.0,
        "gpuFanPercent": None,
        "memoryUsedPercent": 60.0,
        "memoryUsedBytes": 10 * 1024**3,
        "memoryTotalBytes": 16 * 1024**3,
        "diskBusyEstimatePercent": 21.0,
        "diskUsedPercent": 48.0,
        "diskUsedBytes": 476 * 1024**3,
        "diskTotalBytes": 1000 * 1024**3,
        "diskSmartStatus": "정상",
    },
    {
        "cpuUsagePercent": 35.0,
        "cpuTempCelsius": 59.0,
        "cpuClockMhz": 4180.0,
        "gpuUsagePercent": 83.0,
        "gpuTempCelsius": 86.0,
        "gpuFanRpm": 0.0,
        "gpuFanPercent": None,
        "memoryUsedPercent": 61.0,
        "memoryUsedBytes": 10.1 * 1024**3,
        "memoryTotalBytes": 16 * 1024**3,
        "diskBusyEstimatePercent": 24.0,
        "diskUsedPercent": 48.0,
        "diskUsedBytes": 476 * 1024**3,
        "diskTotalBytes": 1000 * 1024**3,
        "diskSmartStatus": "정상",
    },
    {
        "cpuUsagePercent": 38.0,
        "cpuTempCelsius": 60.0,
        "cpuClockMhz": 4150.0,
        "gpuUsagePercent": 86.0,
        "gpuTempCelsius": 88.0,
        "gpuFanRpm": 0.0,
        "gpuFanPercent": None,
        "memoryUsedPercent": 62.0,
        "memoryUsedBytes": 10.2 * 1024**3,
        "memoryTotalBytes": 16 * 1024**3,
        "diskBusyEstimatePercent": 26.0,
        "diskUsedPercent": 48.0,
        "diskUsedBytes": 476 * 1024**3,
        "diskTotalBytes": 1000 * 1024**3,
        "diskSmartStatus": "정상",
    },
)


DEMO_SENSOR_STATUS: dict[str, str] = {
    "cpuUsagePercent": "collected",
    "cpuTempCelsius": "collected",
    "cpuClockMhz": "collected",
    "gpuUsagePercent": "collected",
    "gpuTempCelsius": "collected",
    "gpuFanRpm": "collected",
    "gpuFanPercent": "unsupported",
    "gpuClockMhz": "unsupported",
    "gpuThermalThrottling": "unsupported",
    "memoryUsedPercent": "collected",
    "memoryUsedBytes": "collected",
    "memoryTotalBytes": "collected",
    "diskBusyEstimatePercent": "collected",
    "diskUsedPercent": "collected",
    "diskUsedBytes": "collected",
    "diskTotalBytes": "collected",
    "diskSmartStatus": "collected",
}


DEMO_UNAVAILABLE_REASONS: dict[str, str] = {
    "gpuFanPercent": "demo fan percentage unsupported",
    "gpuClockMhz": "demo GPU clock unsupported",
    "gpuThermalThrottling": "demo thermal throttling sensor unsupported",
}
