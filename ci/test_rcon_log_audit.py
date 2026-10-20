"""Log audit across Paper's midnight rollover.

Paper's log4j2 configuration rolls logs/latest.log once a day
(TimeBasedTriggeringPolicy). At midnight the log written so far is compressed to
logs/<date>-<n>.log.gz and latest.log starts empty.

A suite that starts before midnight and ends after it used to audit only
latest.log, so the boot line was invisible and a healthy server was reported as
"plugin is enabled in the log" failing. CI run 39341533768 hit exactly that on
four jobs at once, at 00:00 UTC, while the suites themselves reported 180/181
passed. These tests pin the run-wide read so it cannot regress to one file.

Run directly:  python ci/test_rcon_log_audit.py
"""
import gzip
import importlib.util
import os
import shutil
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

BOOT_LINE = ('[23:27:30] [Server thread/INFO]: '
             '[Aurelium] AurelEconomy has been enabled!')
BOOT_LINES = ('[23:27:29] [Server thread/INFO]: [Aurelium] Enabling Aurelium v1.5.5\n'
              '[23:27:30] [Server thread/INFO]: [Aurelium] AurelEconomy has been enabled!\n')
MID_LINES = ('[00:00:19] [Craft Scheduler Thread - 113 - Aurelium/INFO]: '
             '[Aurelium] Created initial balance for abc: 100.00 Aurels\n')

# A record the audit treats as a crash signature outside the adversarial section.
CRASH_LINES = ('[22:00:00] [Server thread/ERROR]: [Aurelium] old run blew up\n'
               'java.lang.NullPointerException: old run\n'
               '\tat com.aureleconomy.old.Broken.method(Broken.java:1)\n')


def load_suite_module():
    spec = importlib.util.spec_from_file_location(
        'test_rcon', os.path.join(REPO, 'test_rcon.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class FakeSuite:
    """The slice of Suite that sec_log_audit uses."""

    def __init__(self, inj_offset):
        self._inj_offset = inj_offset
        self.checks = []
        self.skips = []

    def head(self, title):
        pass

    def skip(self, label, why=''):
        self.skips.append(label)

    def check(self, condition, label, detail=''):
        self.checks.append((bool(condition), label))


class Sandbox:
    """A throwaway server directory with a logs/ folder."""

    def __init__(self, mod):
        self.mod = mod
        self.root = tempfile.mkdtemp(prefix='aurelium-log-audit-')
        self.log_dir = os.path.join(self.root, 'logs')
        os.makedirs(self.log_dir)
        self.original = (mod.LOG_DIR, mod.LOG_PATH, mod.RUN_STARTED)
        mod.LOG_DIR = self.log_dir
        mod.LOG_PATH = os.path.join(self.log_dir, 'latest.log')
        # A run that has been going for an hour, so "started before midnight"
        # is the normal case rather than a special one.
        mod.RUN_STARTED = time.time() - 3600

    def write_latest(self, text):
        with open(os.path.join(self.log_dir, 'latest.log'), 'w', encoding='utf-8') as fh:
            fh.write(text)

    def write_rolled(self, name, text, age=1800):
        path = os.path.join(self.log_dir, name)
        with gzip.open(path, 'wt', encoding='utf-8') as fh:
            fh.write(text)
        stamp = time.time() - age
        os.utime(path, (stamp, stamp))
        return path

    def close(self):
        self.mod.LOG_DIR, self.mod.LOG_PATH, self.mod.RUN_STARTED = self.original
        shutil.rmtree(self.root, ignore_errors=True)


def test_reads_boot_from_rolled_file():
    mod = load_suite_module()
    box = Sandbox(mod)
    try:
        box.write_rolled('2026-10-19-1.log.gz', BOOT_LINES)
        box.write_latest(MID_LINES)

        log = mod.read_run_log()
        assert BOOT_LINE in log, 'the rolled file was not read'
        assert 'Created initial balance' in log, 'latest.log was not read'
        assert log.index(BOOT_LINE) < log.index('Created initial balance'), \
            'rolled content must come before the live file'
    finally:
        box.close()


def test_audit_passes_across_a_rollover():
    mod = load_suite_module()
    box = Sandbox(mod)
    try:
        box.write_rolled('2026-10-19-1.log.gz', BOOT_LINES)
        box.write_latest(MID_LINES)

        suite = FakeSuite(inj_offset=len(mod.read_run_log()))
        mod.sec_log_audit(suite)

        labels = dict((label, ok) for ok, label in suite.checks)
        assert 'plugin is enabled in the log' in labels, suite.checks
        assert labels['plugin is enabled in the log'], \
            'the audit still could not see the boot line across a rollover'
        assert all(ok for ok, _ in suite.checks), suite.checks
    finally:
        box.close()


def test_latest_only_is_the_bug_this_guards():
    # Documents the failure CI hit: with the boot half rolled away and only
    # latest.log read, the audit reports a healthy server as not enabled.
    mod = load_suite_module()
    box = Sandbox(mod)
    try:
        box.write_latest(MID_LINES)

        assert BOOT_LINE not in mod.read_run_log()

        suite = FakeSuite(inj_offset=0)
        mod.sec_log_audit(suite)
        labels = dict((label, ok) for ok, label in suite.checks)
        assert not labels['plugin is enabled in the log'], \
            'without the rolled file the audit should have failed'
    finally:
        box.close()


def test_an_older_run_is_not_audited():
    # A datestamped log from before this run must not contribute stack traces,
    # or a reused server directory would fail the audit with an old crash.
    mod = load_suite_module()
    box = Sandbox(mod)
    try:
        box.write_rolled('2026-10-19-1.log.gz', CRASH_LINES, age=7200)
        box.write_latest(MID_LINES)

        log = mod.read_run_log()
        assert 'old run blew up' not in log, 'an older run leaked into the audit'

        suite = FakeSuite(inj_offset=0)
        mod.sec_log_audit(suite)
        crash_checks = [(ok, label) for ok, label in suite.checks
                        if label.startswith('no crash signature')]
        assert crash_checks and all(ok for ok, _ in crash_checks), \
            f'an older run was audited: {suite.checks}'
    finally:
        box.close()


def test_rollover_keeps_the_injection_offset_aligned():
    # The adversarial-section split is a slice of the run-wide text. A rollover
    # mid-run must not shift it, or a crash before the section would be read as
    # tolerated or a tolerated frame as fatal.
    mod = load_suite_module()
    box = Sandbox(mod)
    try:
        box.write_rolled('2026-10-19-1.log.gz', BOOT_LINES + CRASH_LINES)
        box.write_latest(MID_LINES)

        full = mod.read_run_log()
        offset = full.index('Created initial balance')
        suite = FakeSuite(inj_offset=offset)
        mod.sec_log_audit(suite)

        labels = dict((label, ok) for ok, label in suite.checks)
        assert not labels['no crash signature in the log before the adversarial section'], \
            'a crash in the rolled file was not attributed to this run'
        assert labels['no crash signature in the log during the adversarial section'], \
            'the live file was scanned as if it were before the section'
    finally:
        box.close()


if __name__ == '__main__':
    failures = 0
    for name, fn in [
        ('rolled file is read', test_reads_boot_from_rolled_file),
        ('audit across a rollover', test_audit_passes_across_a_rollover),
        ('latest-only failure', test_latest_only_is_the_bug_this_guards),
        ('older run excluded', test_an_older_run_is_not_audited),
        ('injection offset aligned', test_rollover_keeps_the_injection_offset_aligned),
    ]:
        print(f'=== {name} ===')
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f'  FAIL {type(exc).__name__}: {exc}')
    print()
    if failures:
        print(f'{failures} test(s) FAILED')
        raise SystemExit(1)
    print('ALL LOG AUDIT TESTS PASS')
