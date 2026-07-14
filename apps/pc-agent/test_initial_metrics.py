from __future__ import annotations

import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path

from initial_metrics import (
    ABNORMAL,
    AVAILABLE,
    ERROR,
    FAILED,
    MODERATE,
    NORMAL,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    UNSUPPORTED,
    WARNING,
    DemoSensorProvider,
    InitialCollectionSettings,
    InitialMetricsCoordinator,
    MetricsNormalizer,
    MetricsStore,
    ProviderSample,
)


NOW = "2026-07-13T03:00:00+00:00"


def complete_payload(usage: float = 42.0, fan_rpm: float = 1200.0) -> dict[str, object]:
    return {
        "cpuUsagePercent": usage,
        "cpuTempCelsius": 62.0,
        "cpuClockMhz": 4100.0,
        "gpuUsagePercent": usage,
        "gpuTempCelsius": 68.0,
        "gpuFanRpm": fan_rpm,
        "gpuFanPercent": None,
        "memoryUsedPercent": usage,
        "memoryUsedBytes": 8 * 1024**3,
        "memoryTotalBytes": 16 * 1024**3,
        "diskBusyEstimatePercent": usage,
        "diskUsedPercent": 50.0,
        "diskUsedBytes": 500 * 1024**3,
        "diskTotalBytes": 1000 * 1024**3,
        "diskSmartStatus": "정상",
        "sensorStatus": {"gpuFanPercent": "unsupported"},
        "unavailableReason": {"gpuFanPercent": "fan percentage unsupported"},
        "gpuCollectorSource": "nvidia-smi",
        "diskCollectorSource": "windows-performance-counter",
    }


class StaticProvider:
    def __init__(self, values: tuple[float, ...] = (42.0,)) -> None:
        self.values = values
        self.calls = 0

    def collect_sample(self, sample_index: int) -> ProviderSample:
        self.calls += 1
        value = self.values[min(sample_index, len(self.values) - 1)]
        return ProviderSample(complete_payload(value), NOW, "hardware")


class InitialMetricsNormalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.normalizer = MetricsNormalizer()

    def reading(self, payload: dict[str, object], component: str, metric_type: str):
        readings = self.normalizer.normalize(ProviderSample(payload, NOW, "hardware"))
        return next(item for item in readings if item.component == component and item.metric_type == metric_type)

    def test_normalizes_real_cpu_gpu_ram_and_disk_values(self) -> None:
        readings = self.normalizer.normalize(ProviderSample(complete_payload(), NOW, "hardware"))
        values = {(item.component, item.metric_type): item for item in readings}

        self.assertEqual(42.0, values[("cpu", "usage")].value)
        self.assertEqual(4100.0, values[("cpu", "clock")].value)
        self.assertEqual(1200.0, values[("gpu", "fan_rpm")].value)
        self.assertEqual(8 * 1024**3, values[("ram", "used_bytes")].value)
        self.assertEqual(1000 * 1024**3, values[("disk", "total_bytes")].value)
        self.assertEqual("nvidia-smi", values[("gpu", "usage")].source)
        self.assertEqual("windows-performance-counter", values[("disk", "activity")].source)

    def test_usage_80_is_warning_and_95_is_abnormal(self) -> None:
        warning = self.reading(complete_payload(80.0), "cpu", "usage")
        abnormal = self.reading(complete_payload(95.0), "cpu", "usage")

        self.assertEqual(WARNING, warning.status)
        self.assertEqual(ABNORMAL, abnormal.status)

    def test_usage_moderate_range_is_distinct(self) -> None:
        moderate = self.reading(complete_payload(60.0), "ram", "usage")
        self.assertEqual(MODERATE, moderate.status)

    def test_temperature_and_fan_unsupported_are_not_zero(self) -> None:
        payload = complete_payload()
        payload["cpuTempCelsius"] = None
        payload["gpuFanRpm"] = None
        payload["sensorStatus"] = {
            "cpuTempCelsius": "unsupported",
            "gpuFanRpm": "unsupported",
            "gpuFanPercent": "unsupported",
        }
        payload["unavailableReason"] = {
            "cpuTempCelsius": "CPU temperature sensor unsupported",
            "gpuFanRpm": "GPU fan RPM unsupported",
            "gpuFanPercent": "GPU fan percentage unsupported",
        }

        temperature = self.reading(payload, "cpu", "temperature")
        fan = self.reading(payload, "gpu", "fan_rpm")

        self.assertEqual(UNSUPPORTED, temperature.availability)
        self.assertIsNone(temperature.value)
        self.assertEqual(UNAVAILABLE, temperature.status)
        self.assertEqual(UNSUPPORTED, fan.availability)
        self.assertIsNone(fan.value)

    def test_actual_zero_fan_remains_available(self) -> None:
        fan = self.reading(complete_payload(fan_rpm=0.0), "gpu", "fan_rpm")

        self.assertEqual(AVAILABLE, fan.availability)
        self.assertEqual(0.0, fan.value)
        self.assertEqual(MODERATE, fan.status)

    def test_permission_and_failure_have_distinct_availability(self) -> None:
        permission_payload = complete_payload()
        permission_payload["cpuTempCelsius"] = None
        permission_payload["sensorStatus"] = {"cpuTempCelsius": "permission_required"}
        permission_payload["unavailableReason"] = {"cpuTempCelsius": "administrator permission required"}
        failure_payload = complete_payload()
        failure_payload["gpuTempCelsius"] = None
        failure_payload["sensorStatus"] = {"gpuTempCelsius": "failed"}
        failure_payload["unavailableReason"] = {"gpuTempCelsius": "sensor query failed"}

        permission = self.reading(permission_payload, "cpu", "temperature")
        failure = self.reading(failure_payload, "gpu", "temperature")

        self.assertEqual(PERMISSION_REQUIRED, permission.availability)
        self.assertEqual(UNAVAILABLE, permission.status)
        self.assertEqual("PERMISSION_REQUIRED", permission.error_code)
        self.assertEqual(FAILED, failure.availability)
        self.assertEqual(ERROR, failure.status)
        self.assertEqual("SENSOR_FAILED", failure.error_code)

    def test_explicit_bad_smart_is_abnormal(self) -> None:
        payload = complete_payload()
        payload["diskSmartStatus"] = "비정상"
        smart = self.reading(payload, "disk", "smart")
        self.assertEqual(ABNORMAL, smart.status)

    def test_common_model_contains_required_contract_fields(self) -> None:
        reading = self.reading(complete_payload(), "cpu", "usage").to_dict()
        self.assertEqual(
            {
                "component",
                "metricType",
                "value",
                "unit",
                "availability",
                "status",
                "source",
                "sampledAt",
                "errorCode",
                "failureReason",
            },
            set(reading),
        )


class InitialMetricsCoordinatorTest(unittest.TestCase):
    def test_live_mode_uses_only_live_provider_and_completes_with_real_history(self) -> None:
        store = MetricsStore()
        live = StaticProvider((20.0, 80.0, 95.0))
        demo_factory_calls: list[bool] = []
        completed = []
        updates = []
        coordinator = InitialMetricsCoordinator(
            store,
            live_provider_factory=lambda: live,
            demo_provider_factory=lambda: demo_factory_calls.append(True) or DemoSensorProvider(),
            settings=InitialCollectionSettings(3, 0.05, 0.5),
            on_update=updates.append,
            on_complete=completed.append,
        )

        self.assertTrue(coordinator.start("diagnosis-live", "LIVE"))
        self.assertTrue(coordinator.wait(2.0))
        self.assertTrue(coordinator.stop(1.0))

        snapshot = store.snapshot
        self.assertEqual([], demo_factory_calls)
        self.assertEqual(3, live.calls)
        self.assertEqual((20.0, 80.0, 95.0), snapshot.history("cpu", "usage"))
        self.assertEqual(
            [(20.0,), (20.0, 80.0), (20.0, 80.0, 95.0)],
            [update.history("cpu", "usage") for update in updates[:3]],
        )
        self.assertEqual({"cpu", "gpu", "ram", "disk"}, snapshot.terminal_components())
        self.assertTrue(snapshot.initial_complete)
        self.assertEqual(1, len(completed))

    def test_demo_mode_never_constructs_live_provider(self) -> None:
        store = MetricsStore()
        live_factory_calls: list[bool] = []
        coordinator = InitialMetricsCoordinator(
            store,
            live_provider_factory=lambda: live_factory_calls.append(True) or StaticProvider(),
            demo_provider_factory=DemoSensorProvider,
            settings=InitialCollectionSettings(2, 0.05, 0.5),
        )

        self.assertTrue(coordinator.start("diagnosis-demo", "DEMO"))
        self.assertTrue(coordinator.wait(2.0))
        self.assertTrue(coordinator.stop(1.0))

        self.assertEqual([], live_factory_calls)
        self.assertEqual("DEMO", store.snapshot.mode)
        self.assertTrue(all(item.source == "demo-scenario" for item in store.snapshot.readings))

    def test_provider_permission_failure_and_timeout_are_terminal(self) -> None:
        class PermissionProvider:
            def collect_sample(self, sample_index: int) -> ProviderSample:
                raise PermissionError("denied")

        class FailureProvider:
            def collect_sample(self, sample_index: int) -> ProviderSample:
                raise RuntimeError("failed")

        class TimeoutProvider:
            def collect_sample(self, sample_index: int) -> ProviderSample:
                time.sleep(0.1)
                return ProviderSample(complete_payload(), NOW, "hardware")

        cases = (
            (PermissionProvider(), PERMISSION_REQUIRED, "PERMISSION_REQUIRED"),
            (FailureProvider(), FAILED, "SENSOR_FAILED"),
            (TimeoutProvider(), FAILED, "SENSOR_TIMEOUT"),
        )
        for index, (provider, availability, error_code) in enumerate(cases):
            with self.subTest(error_code=error_code):
                store = MetricsStore()
                coordinator = InitialMetricsCoordinator(
                    store,
                    live_provider_factory=lambda current=provider: current,
                    demo_provider_factory=DemoSensorProvider,
                    settings=InitialCollectionSettings(1, 0.05, 0.01),
                )
                self.assertTrue(coordinator.start(f"diagnosis-{index}", "LIVE"))
                self.assertTrue(coordinator.wait(1.0))
                self.assertTrue(coordinator.stop(1.0))
                snapshot = store.snapshot
                self.assertTrue(snapshot.initial_complete)
                self.assertEqual({"cpu", "gpu", "ram", "disk"}, snapshot.terminal_components())
                self.assertTrue(all(item.availability == availability for item in snapshot.readings))
                self.assertTrue(all(item.error_code == error_code for item in snapshot.readings))

    def test_store_persists_completed_metrics_across_window_restore(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metrics.json"
            store = MetricsStore(path)
            provider = StaticProvider((31.0,))
            coordinator = InitialMetricsCoordinator(
                store,
                live_provider_factory=lambda: provider,
                demo_provider_factory=DemoSensorProvider,
                settings=InitialCollectionSettings(1, 0.05, 0.5),
            )
            coordinator.start("diagnosis-persisted", "LIVE")
            self.assertTrue(coordinator.wait(1.0))
            self.assertTrue(coordinator.stop(1.0))

            restored = MetricsStore(path).snapshot

        self.assertEqual("diagnosis-persisted", restored.diagnosis_id)
        self.assertTrue(restored.initial_complete)
        self.assertEqual((31.0,), restored.history("cpu", "usage"))

    def test_store_keeps_bounded_recent_sample_history(self) -> None:
        store = MetricsStore()
        normalizer = MetricsNormalizer()
        store.begin("diagnosis-bounded", "LIVE")

        for index in range(40):
            sample = ProviderSample(complete_payload(float(index)), NOW, "hardware")
            self.assertTrue(store.append("diagnosis-bounded", normalizer.normalize(sample)))

        history = store.snapshot.history("cpu", "usage", limit=100)
        self.assertEqual(30, len(history))
        self.assertEqual(tuple(float(index) for index in range(10, 40)), history)

    def test_collection_continues_after_initial_completion_until_stopped(self) -> None:
        class ContinuousProvider:
            def __init__(self) -> None:
                self.calls = 0

            def collect_sample(self, sample_index: int) -> ProviderSample:
                self.calls += 1
                sampled_at = datetime.now(timezone.utc).isoformat()
                return ProviderSample(complete_payload(float(sample_index)), sampled_at, "hardware")

        store = MetricsStore()
        provider = ContinuousProvider()
        updates = []
        completed = []
        coordinator = InitialMetricsCoordinator(
            store,
            live_provider_factory=lambda: provider,
            demo_provider_factory=DemoSensorProvider,
            settings=InitialCollectionSettings(3, 0.01, 0.5),
            on_update=updates.append,
            on_complete=completed.append,
        )

        self.assertTrue(coordinator.start("diagnosis-continuous", "LIVE"))
        self.assertTrue(coordinator.wait(1.0))
        self.assertTrue(coordinator.is_running("diagnosis-continuous"))
        self.assertFalse(coordinator.start("diagnosis-continuous", "LIVE"))
        deadline = time.monotonic() + 1.0
        while provider.calls < 5 and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertGreaterEqual(provider.calls, 5)
        self.assertTrue(coordinator.stop(1.0))

        snapshot = store.snapshot
        cpu_readings = [
            reading
            for reading in snapshot.readings
            if reading.component == "cpu" and reading.metric_type == "usage"
        ]
        self.assertGreaterEqual(len(cpu_readings), 5)
        self.assertEqual(len(cpu_readings), len({reading.sampled_at for reading in cpu_readings}))
        self.assertGreaterEqual(len(snapshot.history("cpu", "usage")), 5)
        self.assertLessEqual(len(snapshot.history("cpu", "usage", limit=100)), 30)
        self.assertEqual(1, len(completed))
        self.assertFalse(coordinator.is_running())
        self.assertGreaterEqual(len(updates), 6)


if __name__ == "__main__":
    unittest.main()
