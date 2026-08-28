import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db


class DatabaseStartupTests(unittest.TestCase):
    def test_empty_database_is_initialised_before_home_is_served(self):
        expected_tables = {
            "users",
            "login_rate_limits",
            "mobile_otp_sessions",
            "student_master_records",
            "teacher_master_records",
            "self_registration_otp_sessions",
            "email_registration_sessions",
            "firebase_profile_links",
            "firebase_registration_sessions",
            "existing_email_migration_sessions",
            "fcm_device_tokens",
            "classes",
            "subjects",
            "student_profiles",
            "enrollments",
            "class_subjects",
            "attendance",
            "homework",
            "homework_submissions",
            "marks",
            "announcements",
            "facilities",
            "events",
            "timetable_entries",
            "admissions",
            "feedback",
            "media_assets",
            "gallery_items",
            "homework_student_targets",
            "content_attachments",
            "scheduled_tests",
            "scheduled_test_student_targets",
        }

        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "fresh-school.db"
            uploads_path = Path(temp_dir) / "uploads"
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(db_path),
                    "FLASK_UPLOAD_FOLDER": str(uploads_path),
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)

                with app.app_context():
                    tables = {
                        row[0]
                        for row in get_db().execute(
                            "SELECT name FROM sqlite_master WHERE type = 'table'"
                        )
                    }
                    close_db()

                self.assertTrue(db_path.exists())
                self.assertFalse(expected_tables - tables)
                self.assertEqual(app.test_client().get("/home").status_code, 200)


if __name__ == "__main__":
    unittest.main()
