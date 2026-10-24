"""报告输出不能变成绕过临时库边界的任意文件覆盖入口。"""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'verify-cli-migration.py'

class MigrationReportBoundary(unittest.TestCase):
    def test_existing_file_and_symlink_are_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='aegisos-report-test-') as directory:
            root=Path(directory)
            existing=root/'existing.db';existing.write_bytes(b'SQLite format 3\x00synthetic-sentinel')
            link=root/'report.json';link.symlink_to(existing)
            for destination in (existing,link):
                before=existing.read_bytes()
                result=subprocess.run([sys.executable,str(SCRIPT),'--output',str(destination)],capture_output=True)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(existing.read_bytes(),before)
            fresh=root/'fresh.json'
            result=subprocess.run([sys.executable,str(SCRIPT),'--output',str(fresh)],capture_output=True)
            self.assertEqual(result.returncode,0)
            self.assertIn('"status": "PASS"',fresh.read_text())

if __name__=='__main__': unittest.main()
