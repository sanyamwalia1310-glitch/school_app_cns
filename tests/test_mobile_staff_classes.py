import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db


class MobileStaffClassesTests(unittest.TestCase):
    def test_admin_gets_all_classes_and_teacher_gets_only_assigned_classes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "classes.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    teacher_id = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, activated)
                        VALUES (?, ?, ?, 'teacher', 1) RETURNING id""",
                        ("teacher-classes", "hash", "Teacher Classes"),
                    ).fetchone()["id"]
                    db.execute("INSERT INTO classes (name, section, teacher_id) VALUES (?, ?, ?)", ("Grade 7", "A", teacher_id))
                    db.execute("INSERT INTO classes (name, section) VALUES (?, ?)", ("Grade 8", "B"))
                    db.commit()
                    close_db()

                with patch("app.routes.mobile_profile_from_payload", return_value={"id": 1, "role": "admin"}):
                    admin_response = app.test_client().post("/api/mobile/staff/classes", json={"firebase_id_token": "token", "profile_id": 1})
                self.assertEqual(admin_response.status_code, 200)
                self.assertTrue({"Grade 7 - A", "Grade 8 - B"}.issubset(
                    {item["name"] for item in admin_response.get_json()["items"]}
                ))

                with patch("app.routes.mobile_profile_from_payload", return_value={"id": teacher_id, "role": "teacher"}):
                    teacher_response = app.test_client().post("/api/mobile/staff/classes", json={"firebase_id_token": "token", "profile_id": teacher_id})
                self.assertEqual(teacher_response.status_code, 200)
                self.assertEqual([item["name"] for item in teacher_response.get_json()["items"]], ["Grade 7 - A"])
