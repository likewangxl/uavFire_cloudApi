"""Binding review authorization checks against an isolated in-memory SQL database."""
import importlib.util
from pathlib import Path
import sqlite3
import unittest

spec = importlib.util.spec_from_file_location("bindings", Path(__file__).with_name("miniapp-bindings.py"))
bindings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bindings)


class Cursor:
    def __init__(self, conn):
        self.cursor = conn.cursor()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.cursor.close()

    def execute(self, sql, args):
        # Only adapt driver placeholders and locking syntax; production uses MySQL.
        self.cursor.execute(sql.replace("%s", "?").replace(" FOR UPDATE", ""), args)

    def fetchone(self):
        row = self.cursor.fetchone()
        return dict(row) if row is not None else None

    def fetchall(self):
        return [dict(row) for row in self.cursor.fetchall()]


class Connection:
    def __init__(self, raw):
        self.raw = raw

    def cursor(self):
        return Cursor(self.raw)

    def commit(self):
        self.raw.commit()


class BindingReviewTest(unittest.TestCase):
    def setUp(self):
        self.raw = sqlite3.connect(":memory:")
        self.raw.row_factory = sqlite3.Row
        self.raw.executescript("""
            CREATE TABLE miniapp_identity(identity_id TEXT PRIMARY KEY,binding_status TEXT,requested_username TEXT,user_id TEXT,workspace_id TEXT,reviewed_by TEXT,review_reason TEXT,update_time INTEGER);
            CREATE TABLE manage_user(user_id TEXT,username TEXT,workspace_id TEXT,user_type INTEGER);
            CREATE TABLE manage_workspace(workspace_id TEXT);
            CREATE TABLE miniapp_session(identity_id TEXT,revoked BOOLEAN);
            CREATE TABLE miniapp_login_ticket(identity_id TEXT);
            CREATE TABLE miniapp_binding_audit(audit_id TEXT,identity_id TEXT,action TEXT,actor TEXT,reason TEXT,create_time INTEGER);
            INSERT INTO miniapp_identity VALUES('i','PENDING','alice',NULL,NULL,NULL,NULL,0);
            INSERT INTO manage_workspace VALUES('ws-a'),('ws-b');
            INSERT INTO manage_user VALUES('alice-id','alice','ws-a',1),('bob-id','bob','ws-b',1);
            INSERT INTO miniapp_session VALUES('i',FALSE);
            INSERT INTO miniapp_login_ticket VALUES('i');
        """)
        self.conn = Connection(self.raw)

    def tearDown(self):
        self.raw.close()

    def review(self, action="approve", user="alice-id", workspace="ws-a"):
        return bindings.review(self.conn, action, "i", user, workspace, "reviewer", "identity checked")

    def test_approval_persists_target_revokes_sessions_consumes_tickets_and_audits(self):
        self.assertEqual("APPROVED", self.review()["bindingStatus"])
        identity = self.raw.execute("SELECT * FROM miniapp_identity").fetchone()
        self.assertEqual(("alice-id", "ws-a"), (identity["user_id"], identity["workspace_id"]))
        self.assertEqual(1, self.raw.execute("SELECT revoked FROM miniapp_session").fetchone()[0])
        self.assertEqual(0, self.raw.execute("SELECT COUNT(*) FROM miniapp_login_ticket").fetchone()[0])
        self.assertEqual("reviewer", self.raw.execute("SELECT actor FROM miniapp_binding_audit").fetchone()[0])

    def test_wrong_workspace_or_different_username_cannot_be_approved(self):
        for user, workspace in [("alice-id", "ws-b"), ("bob-id", "ws-b"), (None, "ws-a")]:
            with self.assertRaises(ValueError):
                self.review(user=user, workspace=workspace)
            self.assertEqual("PENDING", self.raw.execute("SELECT binding_status FROM miniapp_identity").fetchone()[0])
            self.assertEqual(0, self.raw.execute("SELECT COUNT(*) FROM miniapp_binding_audit").fetchone()[0])

    def test_status_transitions_reject_repeat_approval_and_record_disable(self):
        self.review()
        with self.assertRaises(ValueError):
            self.review()
        self.assertEqual("DISABLED", self.review(action="disable")["bindingStatus"])
        with self.assertRaises(ValueError):
            self.review()

    def test_audit_failure_does_not_commit_partial_approval(self):
        self.raw.execute("DROP TABLE miniapp_binding_audit")
        self.raw.commit()
        with self.assertRaises(sqlite3.OperationalError):
            self.review()
        # The CLI closes its non-autocommit connection on failure, rolling back.
        self.raw.rollback()
        self.assertEqual("PENDING", self.raw.execute("SELECT binding_status FROM miniapp_identity").fetchone()[0])
        self.assertEqual(0, self.raw.execute("SELECT revoked FROM miniapp_session").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
