#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import unittest

import numpy as np

from recommend_android_asset import read_asset


ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/recommendation-model.bin"
MANIFEST = ROOT / "app/src/main/assets/recommendation-model.json"


class AndroidRecommendationAssetTest(unittest.TestCase):
    def test_checked_in_asset_matches_manifest_and_is_parseable(self):
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(ASSET.read_bytes()).hexdigest(), manifest["assetSha256"])
        asset = read_asset(ASSET)
        self.assertEqual(manifest["items"], len(asset["items"]))
        self.assertEqual(manifest["dimensions"], asset["dimensions"])
        self.assertEqual(manifest["catalogAliases"], len(asset["aliases"]))
        self.assertEqual(
            manifest["candidateItems"], sum(item["candidate"] for item in asset["items"])
        )
        self.assertTrue(np.isfinite(asset["factors"]).all())
        by_work = {item["workId"]: item for item in asset["items"]}
        self.assertFalse(by_work[216334]["candidate"])
        self.assertFalse(by_work[4873881]["candidate"])


if __name__ == "__main__":
    unittest.main()
