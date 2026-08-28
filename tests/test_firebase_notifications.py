import sqlite3
import unittest

from app import firebase_notifications


class FakeMessaging:
    class Notification:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    class Message:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    def __init__(self):
        self.sent = []

    def send(self, message):
        self.sent.append(message)
        return "message-id"


class FirebaseNotificationIsolationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute(
            """CREATE TABLE fcm_device_tokens (
                token TEXT PRIMARY KEY, firebase_uid TEXT NOT NULL, user_id INTEGER NOT NULL,
                platform TEXT NOT NULL, created_at REAL NOT NULL, updated_at REAL NOT NULL)"""
        )
        self.db.executemany(
            "INSERT INTO fcm_device_tokens VALUES (?, ?, ?, 'android', 1, 1)",
            [("student-a-token", "shared-parent-uid", 101), ("student-b-token", "shared-parent-uid", 202)],
        )
        self.messaging = FakeMessaging()
        self.original_messaging = firebase_notifications._messaging
        firebase_notifications._messaging = lambda: self.messaging

    def tearDown(self):
        firebase_notifications._messaging = self.original_messaging
        self.db.close()

    def test_private_marks_targets_only_exact_student_profile(self):
        result = firebase_notifications.send_profile_notification(
            self.db,
            profile_user_id=101,
            title="Marks updated",
            body="New marks have been published.",
            data={"event_type": "marks", "destination": "marks"},
        )
        self.assertEqual((result.attempted, result.delivered), (1, 1))
        self.assertEqual(len(self.messaging.sent), 1)
        payload = self.messaging.sent[0].kwargs
        self.assertEqual(payload["token"], "student-a-token")
        self.assertEqual(payload["data"]["target_profile_id"], "101")
        self.assertNotIn("student-b-token", str(payload))

    def test_public_notice_uses_only_the_public_topic(self):
        firebase_notifications.send_public_notification(
            title="School announcement", body="Please open the app.", data={"event_type": "announcement"}
        )
        payload = self.messaging.sent[0].kwargs
        self.assertEqual(payload["topic"], "school_public")
        self.assertNotIn("token", payload)


if __name__ == "__main__":
    unittest.main()
