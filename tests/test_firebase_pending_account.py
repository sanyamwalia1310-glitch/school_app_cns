import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.firebase_auth import create_pending_email_account


class FirebasePendingAccountTests(unittest.TestCase):
    def test_existing_firebase_email_returns_its_uid_without_creating_another_account(self):
        class FakeAuth:
            class UserNotFoundError(Exception):
                pass

            def get_user_by_email(self, email):
                self.email = email
                return SimpleNamespace(uid="existing-firebase-uid")

            def create_user(self, **_kwargs):
                raise AssertionError("An existing Firebase identity must not be created again.")

        fake_auth = FakeAuth()
        with patch("app.firebase_auth._firebase_auth", return_value=fake_auth):
            uid, created = create_pending_email_account("Parent@Example.com", "Passw0rd!", "Student A")

        self.assertEqual(uid, "existing-firebase-uid")
        self.assertFalse(created)
        self.assertEqual(fake_auth.email, "parent@example.com")


if __name__ == "__main__":
    unittest.main()
