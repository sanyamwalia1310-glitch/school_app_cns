import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db


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


if __name__ == "__main__":
    unittest.main()
