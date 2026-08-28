import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db


class PasswordRecoveryTests(unittest.TestCase):
    def test_pending_master_record_can_request_a_firebase_password_reset(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(os.environ, {
                "FLASK_DATABASE": str(Path(temp_dir) / "recovery.db"),
                "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                "DATABASE_URL": "",
            }, clear=False):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    db.execute(
                        "INSERT INTO student_master_records (student_id, full_name, email) VALUES (?, ?, ?)",
                        ("STU011", "Student Pending", "parent@example.com"),
                    )
                    db.commit()
                    close_db()

                response = app.test_client().post(
                    "/api/password-reset/request",
                    json={"role": "student", "identifier": "STU011", "email": "PARENT@example.com"},
                )
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.get_json()["email"], "parent@example.com")
                self.assertTrue(response.get_json()["pending_activation"])

    def test_activated_profile_uses_firebase_profile_link_not_legacy_uid_only(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(os.environ, {
                "FLASK_DATABASE": str(Path(temp_dir) / "active-recovery.db"),
                "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                "DATABASE_URL": "",
            }, clear=False):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    user_id = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, email, activated)
                        VALUES (?, ?, ?, 'student', ?, 1) RETURNING id""",
                        ("STU012", "hash", "Student Active", "active@example.com"),
                    ).fetchone()["id"]
                    db.execute(
                        "INSERT INTO firebase_profile_links (firebase_uid, user_id) VALUES (?, ?)",
                        ("firebase-active", user_id),
                    )
                    db.commit()
                    close_db()

                response = app.test_client().post(
                    "/api/password-reset/request",
                    json={"role": "student", "identifier": "STU012", "email": "active@example.com"},
                )
                self.assertEqual(response.status_code, 200)
                self.assertFalse(response.get_json()["pending_activation"])


if __name__ == "__main__":
    unittest.main()
