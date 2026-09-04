import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app import create_app
from app.database import close_db, get_db
from app.firebase_auth import FirebaseAuthProvisioningError
from app.routes import (
    mobile_profile_from_payload,
    repair_admin_firebase_profile,
    repair_missing_firebase_profile_links,
)


class FirebaseProfileRepairTests(unittest.TestCase):
    def test_verified_admin_claim_repairs_the_single_existing_admin_profile(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "admin-repair.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    admin_id = db.execute(
                        "SELECT id FROM users WHERE role = 'admin' AND activated = 1 ORDER BY id LIMIT 1"
                    ).fetchone()["id"]
                    db.commit()
                    with patch("app.routes.verified_firebase_admin_uid", return_value="admin-firebase-uid"):
                        admin = repair_admin_firebase_profile(db, "admin-firebase-uid", "verified-token")
                    self.assertEqual(admin["id"], admin_id)
                    self.assertEqual(
                        db.execute("SELECT firebase_uid FROM users WHERE id = ?", (admin_id,)).fetchone()["firebase_uid"],
                        "admin-firebase-uid",
                    )
                    close_db()

    def test_verified_shared_parent_repairs_only_matching_profiles(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "FLASK_DATABASE": str(Path(temp_dir) / "profile-repair.db"),
                    "FLASK_UPLOAD_FOLDER": str(Path(temp_dir) / "uploads"),
                    "DATABASE_URL": "",
                },
                clear=False,
            ):
                app = create_app()
                app.config.update(TESTING=True)
                with app.app_context():
                    db = get_db()
                    student_a = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, email, activated)
                        VALUES (?, ?, ?, 'student', ?, 1) RETURNING id""",
                        ("STU001", "hash", "Student A", "parent@example.com"),
                    ).fetchone()["id"]
                    student_b = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, email, activated)
                        VALUES (?, ?, ?, 'student', ?, 1) RETURNING id""",
                        ("STU002", "hash", "Student B", "parent@example.com"),
                    ).fetchone()["id"]
                    student_c = db.execute(
                        """INSERT INTO users (username, password_hash, full_name, role, email, activated)
                        VALUES (?, ?, ?, 'student', ?, 1) RETURNING id""",
                        ("STU003", "hash", "Student C", "other@example.com"),
                    ).fetchone()["id"]
                    db.commit()

                    with patch("app.routes.firebase_identity_email", return_value=("parent@example.com", True)):
                        profiles = repair_missing_firebase_profile_links(db, "parent-uid", "verified-token")

                    self.assertEqual({row["id"] for row in profiles}, {student_a, student_b})
                    linked = db.execute(
                        "SELECT user_id FROM firebase_profile_links WHERE firebase_uid = ? ORDER BY user_id",
                        ("parent-uid",),
                    ).fetchall()
                    self.assertEqual([row["user_id"] for row in linked], [student_a, student_b])
                    self.assertIsNone(
                        db.execute(
                            "SELECT 1 FROM firebase_profile_links WHERE user_id = ?", (student_c,)
                        ).fetchone()
                    )

                    with patch("app.routes.verified_firebase_uid", return_value="parent-uid"):
                        self.assertEqual(
                            mobile_profile_from_payload(
                                {"firebase_id_token": "verified-token", "profile_id": student_a}, "student"
                            )["id"],
                            student_a,
                        )
                        self.assertEqual(
                            mobile_profile_from_payload(
                                {"firebase_id_token": "verified-token", "profile_id": student_b}, "student"
                            )["id"],
                            student_b,
                        )
                        with patch("app.routes.firebase_identity_email", return_value=("parent@example.com", True)):
                            with self.assertRaises(FirebaseAuthProvisioningError):
                                mobile_profile_from_payload(
                                    {"firebase_id_token": "verified-token", "profile_id": student_c}, "student"
                                )
                    close_db()


if __name__ == "__main__":
    unittest.main()
