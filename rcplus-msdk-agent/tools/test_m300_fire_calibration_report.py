import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("m300_fire_calibration_report.py")
SPEC = importlib.util.spec_from_file_location("m300_calibration", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CalibrationReportTest(unittest.TestCase):
    def test_metrics_cover_false_positive_miss_and_latency(self):
        records = [
            {"source_ts": 0, "ground_truth": "fire", "confirmed": True, "inference_ms": 100},
            {"source_ts": 1_200_000, "ground_truth": "smoke", "confirmed": False, "inference_ms": 200},
            {"source_ts": 2_400_000, "ground_truth": "none", "confirmed": True, "inference_ms": 300},
            {"source_ts": 3_600_000, "ground_truth": "none", "confirmed": False, "inference_ms": 400},
        ]
        metrics = MODULE.evaluate(records, flight_hours=1.0)
        self.assertEqual((1, 1, 1, 1), (metrics["tp"], metrics["fp"], metrics["fn"], metrics["tn"]))
        self.assertEqual(0.5, metrics["precision"])
        self.assertEqual(0.5, metrics["recall"])
        self.assertEqual(1.0, metrics["false_alarms_per_hour"])
        self.assertEqual(400.0, metrics["inference_ms_p95"])


if __name__ == "__main__":
    unittest.main()
