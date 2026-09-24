"""No-network regression tests for the ci prerelease's branch-slot reconciliation."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import ci_sync

REPO = "zhzx2026/wordsprint"
LIVE = "arena01a0d3c9"
DELETED = "arena01a0c983"


def asset(name, number):
    return {"name": name, "id": number, "browser_download_url": "test://" + name,
            "updated_at": "2026-09-24T14:32:12Z"}


def fixture_assets():
    return [asset("update.json", 1), asset("wordsprint.apk", 2),
            asset("update-" + DELETED + ".json", 3), asset("wordsprint-" + DELETED + ".apk", 4),
            asset("update-" + LIVE + ".json", 5), asset("wordsprint-" + LIVE + ".apk", 6),
            asset("other-file.txt", 7)]


class FakeGh:
    def __init__(self, branches=None, assets=None, body="旧表格"):
        self.branches = branches if branches is not None else ["main", "arena/01a0d3c9-wordsprint"]
        self.assets = assets if assets is not None else fixture_assets()
        self.body = body
        self.calls = []
        self.uploaded = {}
        self.branch_error = False
        self.no_release = False

    def __call__(self, *args):
        self.calls.append(args)
        if args[0] == "api" and "/branches?" in args[1]:
            if self.branch_error:
                raise ci_sync.GitHubError("HTTP 403: API rate limit exceeded")
            return "\n".join(self.branches) + "\n"
        if args[:2] == ("api", f"/repos/{REPO}/releases/tags/ci"):
            if self.no_release:
                raise ci_sync.GitHubError("HTTP 404: Not Found")
            return json.dumps({"id": 123, "body": self.body})
        if args[0] == "api" and "/releases/123/assets?" in args[1]:
            return "\n".join(json.dumps(a) for a in self.assets)
        if args[:3] == ("api", "--method", "DELETE"):
            deleted_id = int(args[3].split("/")[-1])
            self.assets = [a for a in self.assets if a["id"] != deleted_id]
            return ""
        if args[:3] == ("release", "upload", "ci"):
            path = Path(args[-1])
            self.uploaded[path.name] = path.read_bytes()
            return ""
        if args[:3] == ("release", "edit", "ci"):
            self.body = Path(args[-1]).read_text(encoding="utf-8")
            return ""
        raise AssertionError("Unexpected gh call: %r" % (args,))

    def deletions(self):
        return [int(c[3].split("/")[-1]) for c in self.calls if c[:3] == ("api", "--method", "DELETE")]


ROOT_MANIFEST = {"versionCode": 55, "versionName": "7.1", "url": "existing-url",
                 "notes": "现有分支的构建说明", "channel": "dev", "force": False,
                 "channels": [DELETED, LIVE]}
LIVE_MANIFEST = {"versionCode": 55, "versionName": "7.1", "notes": "本轮标题\n修复了原有更新流程 | 保留版本索引"}


def fake_download(asset):
    if asset["name"] == "update.json":
        return json.dumps(ROOT_MANIFEST)
    if asset["name"] == "update-" + LIVE + ".json":
        return json.dumps(LIVE_MANIFEST)
    raise AssertionError("不该读取幽灵分支或 APK: %s" % asset["name"])


class CiSyncTest(unittest.TestCase):
    def test_plan_only_advertises_live_complete_pairs(self):
        refs = {"main", "arena/01a0d3c9-wordsprint", "dev-build"}
        assets = fixture_assets() + [asset("update-dev-build.json", 8), asset("update-orphan.json", 9)]
        complete, stale = ci_sync.plan(refs, assets)
        self.assertEqual(list(complete), [LIVE])
        self.assertEqual({a["id"] for a in stale}, {3, 4, 9})
        self.assertEqual(ci_sync.branch_id("arena/01a0d3c9-wordsprint"), LIVE)
        self.assertEqual(ci_sync.branch_id("staging/foo"), "staging-foo")
        self.assertNotIn(1, {a["id"] for a in stale})  # root update.json must survive

    def test_deleted_branch_updates_assets_app_channels_and_release_index(self):
        fake = FakeGh()
        with patch.object(ci_sync, "gh", fake), patch.object(ci_sync, "download", fake_download):
            ci_sync.sync(REPO)
        self.assertEqual(fake.deletions(), [3, 4])
        self.assertEqual({a["name"] for a in fake.assets},
                         {"update.json", "wordsprint.apk", "update-" + LIVE + ".json",
                          "wordsprint-" + LIVE + ".apk", "other-file.txt"})
        new_root = json.loads(fake.uploaded["update.json"])
        self.assertEqual(new_root["channels"], [LIVE])
        self.assertEqual(new_root["versionCode"], ROOT_MANIFEST["versionCode"])
        self.assertEqual(new_root["url"], ROOT_MANIFEST["url"])
        self.assertIn(LIVE + " | v7.1 | 55 | 2026-09-24T14:32:12Z", fake.body)
        self.assertNotIn(DELETED, fake.body)
        self.assertNotIn("wordsprint.apk", fake.uploaded)  # cleanup does not replace last APK

    def test_build_uses_new_root_and_all_live_slots(self):
        new_branch = "arena/01a0d3d8-wordsprint"
        new_id = ci_sync.branch_id(new_branch)
        fake = FakeGh(branches=["main", "arena/01a0d3c9-wordsprint", new_branch],
                      assets=fixture_assets() + [asset("update-" + new_id + ".json", 8),
                                                 asset("wordsprint-" + new_id + ".apk", 9)])
        def get(asset):
            if asset["name"] == "update-" + new_id + ".json":
                return json.dumps({"versionName": "7.2", "versionCode": 56, "notes": "标题\n新分支版本"})
            return fake_download(asset)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root.json"
            apk = Path(tmp) / "wordsprint.apk"
            root.write_text(json.dumps({**ROOT_MANIFEST, "versionCode": 56, "versionName": "7.2"}), encoding="utf-8")
            apk.write_bytes(b"apk")
            with patch.object(ci_sync, "gh", fake), patch.object(ci_sync, "download", get):
                ci_sync.sync(REPO, root_json=root, root_apk=apk, published_branch=new_branch)
        self.assertEqual(fake.deletions(), [3, 4])
        self.assertEqual(fake.uploaded["wordsprint.apk"], b"apk")
        self.assertEqual(json.loads(fake.uploaded["update.json"])["channels"], [LIVE, new_id])
        self.assertEqual(json.loads(fake.uploaded["update.json"])["versionName"], "7.2")
        self.assertIn(new_id + " | v7.2 | 56", fake.body)

    def test_branch_deleted_during_publish_does_not_replace_root(self):
        fake = FakeGh(assets=fixture_assets() + [asset("update-dead.json", 8),
                                                  asset("wordsprint-dead.apk", 9)])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "root.json"
            apk = Path(tmp) / "wordsprint.apk"
            root.write_text(json.dumps({"versionCode": 999, "versionName": "deleted"}), encoding="utf-8")
            apk.write_bytes(b"dead")
            with patch.object(ci_sync, "gh", fake), patch.object(ci_sync, "download", fake_download):
                ci_sync.sync(REPO, root_json=root, root_apk=apk, published_branch="arena/dead-wordsprint")
        self.assertEqual(set(fake.deletions()), {3, 4, 8, 9})
        self.assertNotIn("wordsprint.apk", fake.uploaded)
        self.assertEqual(json.loads(fake.uploaded["update.json"])["versionCode"], 55)

    def test_last_branch_deletion_clears_channels_and_table(self):
        fake = FakeGh(branches=["main"])
        with patch.object(ci_sync, "gh", fake), patch.object(ci_sync, "download", fake_download):
            ci_sync.sync(REPO)
        self.assertEqual(set(fake.deletions()), {3, 4, 5, 6})
        self.assertEqual(json.loads(fake.uploaded["update.json"])["channels"], [])
        self.assertIn("暂无有测试包的现存分支", fake.body)
        self.assertNotIn(LIVE, fake.body)
        self.assertNotIn(DELETED, fake.body)

    def test_remote_api_failure_or_missing_main_never_deletes_anything(self):
        fake = FakeGh()
        fake.branch_error = True
        with patch.object(ci_sync, "gh", fake):
            with self.assertRaises(ci_sync.GitHubError):
                ci_sync.sync(REPO)
        self.assertEqual(fake.deletions(), [])
        self.assertEqual(fake.uploaded, {})
        fake = FakeGh(branches=["arena/01a0d3c9-wordsprint"])
        with patch.object(ci_sync, "gh", fake):
            with self.assertRaisesRegex(ValueError, "main"):
                ci_sync.sync(REPO)
        self.assertEqual(fake.deletions(), [])

    def test_root_fetch_failure_does_not_delete_and_no_release_is_noop(self):
        fake = FakeGh()
        with patch.object(ci_sync, "gh", fake), patch.object(ci_sync, "download", side_effect=OSError("CDN 断开")):
            with self.assertRaises(OSError):
                ci_sync.sync(REPO)
        self.assertEqual(fake.deletions(), [])
        fake = FakeGh()
        fake.no_release = True
        with patch.object(ci_sync, "gh", fake):
            ci_sync.sync(REPO)
        self.assertEqual(fake.deletions(), [])

    def test_collision_is_rejected_before_touching_assets(self):
        with self.assertRaisesRegex(ValueError, "同一坑位"):
            ci_sync.plan({"main", "arena/abc-one", "arena/abc-two"}, fixture_assets())


if __name__ == "__main__":
    unittest.main()
