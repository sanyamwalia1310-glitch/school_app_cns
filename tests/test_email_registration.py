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

    def test_admin_creates_a_complete_server_student_record(self):
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
                with app.app_context():
                    db = get_db()
                    class_id = db.execute("INSERT INTO classes (name, section) VALUES (?, ?) RETURNING id", ("Grade 8", "A")).fetchone()["id"]
                    db.commit()
                    close_db()
                with patch("app.routes.mobile_profile_from_payload", return_value={"id": 1, "role": "admin"}):
                    response = app.test_client().post(
                        "/api/mobile/admin/students",
                        json={
                            "firebase_id_token": "verified-token",
                            "profile_id": 1,
                            "student_id": "STU001",
                            "full_name": "Student A",
                            "class_name": "Grade 8 - A",
                            "roll_no": "01",
                            "guardian_name": "Parent A",
                            "phone": "9876543210",
                            "notes": "Needs transport.",
                            "email": "parent@example.com",
                        },
                    )

                self.assertEqual(response.status_code, 201)
                with app.app_context():
                    row = get_db().execute(
                        "SELECT student_id, full_name, email, phone, notes, class_id, registration_completed FROM student_master_records WHERE student_id = ?",
                        ("stu001",),
                    ).fetchone()
                    close_db()
                self.assertEqual(dict(row), {
                    "student_id": "stu001",
                    "full_name": "Student A",
                    "email": "parent@example.com",
                    "phone": "+919876543210",
                    "notes": "Needs transport.",
                    "class_id": class_id,
                    "registration_completed": 0,
                })

    def test_invalid_or_unknown_student_input_creates_no_partial_master_record(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(os.environ, {"FLASK_DATABASE": str(Path(temp_dir) / "atomic.db"), "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"), "DATABASE_URL": ""}, clear=False):
                app = create_app(); app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db(); db.execute("INSERT INTO classes (name, section) VALUES (?, ?)", ("Grade 8", "A")); db.commit()
                    baseline_count = db.execute("SELECT COUNT(*) AS count FROM student_master_records").fetchone()["count"]; close_db()
                with patch("app.routes.mobile_profile_from_payload", return_value={"id": 1, "role": "admin"}):
                    invalid_phone = app.test_client().post("/api/mobile/admin/students", json={
                        "firebase_id_token": "token", "profile_id": 1, "student_id": "STU-BAD-PHONE", "full_name": "Student B",
                        "class_name": "Grade 8 - A", "phone": "1234",
                    })
                    unknown_class = app.test_client().post("/api/mobile/admin/students", json={
                        "firebase_id_token": "token", "profile_id": 1, "student_id": "STU-BAD-CLASS", "full_name": "Student B",
                        "class_name": "Unknown", "phone": "9876543210",
                    })
                self.assertEqual(invalid_phone.status_code, 400)
                self.assertEqual(unknown_class.status_code, 400)
                with app.app_context():
                    count = get_db().execute("SELECT COUNT(*) AS count FROM student_master_records").fetchone()["count"]
                    close_db()
                self.assertEqual(count, baseline_count)

    def test_pending_partial_student_is_repaired_without_a_duplicate(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(os.environ, {"FLASK_DATABASE": str(Path(temp_dir) / "repair.db"), "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"), "DATABASE_URL": ""}, clear=False):
                app = create_app(); app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    class_id = db.execute("INSERT INTO classes (name, section) VALUES (?, ?) RETURNING id", ("Grade 8", "A")).fetchone()["id"]
                    db.execute("INSERT INTO student_master_records (student_id, full_name, registration_completed) VALUES (?, ?, 0)", ("STU-REPAIR", "Old Name"))
                    db.commit(); close_db()
                with patch("app.routes.mobile_profile_from_payload", return_value={"id": 1, "role": "admin"}):
                    response = app.test_client().post("/api/mobile/admin/students", json={
                        "firebase_id_token": "token", "profile_id": 1, "student_id": "stu-repair", "full_name": "Student Repaired",
                        "class_name": "Grade 8 - A", "phone": "+91 98765 43210", "notes": "Resumed",
                    })
                self.assertEqual(response.status_code, 200)
                self.assertTrue(response.get_json()["repaired"])
                with app.app_context():
                    rows = get_db().execute("SELECT full_name, class_id, phone, notes FROM student_master_records WHERE LOWER(student_id) = ?", ("stu-repair",)).fetchall()
                    close_db()
                self.assertEqual(len(rows), 1)
                self.assertEqual(dict(rows[0]), {"full_name": "Student Repaired", "class_id": class_id, "phone": "+919876543210", "notes": "Resumed"})

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
