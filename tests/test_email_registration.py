import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db
from app.routes import MobileOtpApiError, create_email_login_from_master, email_registration_master_record


class EmailRegistrationTests(unittest.TestCase):
    def test_existing_email_requires_identity_proof_before_a_profile_is_linked(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "registration.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                master = {"id": 7, "full_name": "Student A"}
                with patch("app.routes.email_registration_master_record", return_value=("student", "student_master_records", "student_id", master)), patch(
                    "app.routes.create_pending_email_account", return_value=("existing-firebase-uid", False)
                ):
                    response = app.test_client().post(
                        "/api/email-registration/start",
                        json={
                            "role": "student",
                            "identifier": "STU001",
                            "email": "parent@example.com",
                            "password": "passw0rd!",
                            "confirm_password": "passw0rd!",
                        },
                    )

                self.assertEqual(response.status_code, 409)
                self.assertIn("already has a Firebase account", response.get_json()["error"])

    def test_admin_can_save_an_unregistered_student_master_record(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "admin-record.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with patch("app.routes.verified_firebase_admin_uid", return_value="admin-firebase-uid"):
                    response = app.test_client().post(
                        "/api/mobile/admin/student-master-record",
                        json={
                            "firebase_id_token": "verified-token",
                            "student_id": "STU001",
                            "full_name": "Student A",
                            "roll_no": "01",
                            "guardian_name": "Parent A",
                            "email": "parent@example.com",
                        },
                    )

                self.assertEqual(response.status_code, 200)
                with app.app_context():
                    row = get_db().execute(
                        "SELECT student_id, full_name, email, registration_completed FROM student_master_records WHERE student_id = ?",
                        ("STU001",),
                    ).fetchone()
                    close_db()
                self.assertEqual(dict(row), {
                    "student_id": "STU001",
                    "full_name": "Student A",
                    "email": "parent@example.com",
                    "registration_completed": 0,
                })

    def test_incomplete_legacy_row_can_finish_email_activation(self):
        """Only an activated user may make a master record unavailable."""
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "pending-record.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    old_user_id = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, activated)
                        VALUES (?, ?, ?, 'student', 0) RETURNING id""",
                        ("STU009", "legacy-incomplete", "Student Pending"),
                    ).fetchone()["id"]
                    db.execute(
                        """INSERT INTO student_master_records
                        (student_id, full_name, login_user_id, registration_completed)
                        VALUES (?, ?, ?, 1)""",
                        ("STU009", "Student Pending", old_user_id),
                    )
                    db.commit()

                    role, _table, _id_column, master = email_registration_master_record(
                        {"role": "student", "identifier": "STU009"}
                    )
                    pending = db.execute(
                        "SELECT login_user_id, registration_completed FROM student_master_records WHERE student_id = ?",
                        ("STU009",),
                    ).fetchone()
                    self.assertEqual(dict(pending), {"login_user_id": None, "registration_completed": 0})

                    user = create_email_login_from_master(
                        db, role, master, "parent@example.com", "verified-firebase-uid"
                    )
                    self.assertEqual(user["id"], old_user_id)
                    self.assertEqual(user["activated"], 1)
                    self.assertEqual(
                        db.execute(
                            "SELECT registration_completed FROM student_master_records WHERE student_id = ?",
                            ("STU009",),
                        ).fetchone()["registration_completed"],
                        1,
                    )
                    self.assertIsNotNone(
                        db.execute("SELECT 1 FROM firebase_profile_links WHERE user_id = ?", (old_user_id,)).fetchone()
                    )
                    close_db()

    def test_activated_account_remains_blocked_from_repeat_registration(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "active-record.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    user_id = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, activated)
                        VALUES (?, ?, ?, 'student', 1) RETURNING id""",
                        ("STU010", "active", "Student Active"),
                    ).fetchone()["id"]
                    db.execute(
                        """INSERT INTO student_master_records
                        (student_id, full_name, login_user_id, registration_completed)
                        VALUES (?, ?, ?, 1)""",
                        ("STU010", "Student Active", user_id),
                    )
                    db.commit()
                    with self.assertRaises(MobileOtpApiError):
                        email_registration_master_record({"role": "student", "identifier": "STU010"})
                    close_db()


if __name__ == "__main__":
    unittest.main()
