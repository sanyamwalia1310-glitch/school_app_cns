import sqlite3
import unittest

from app.routes import content_visible_to_student, target_student_ids


class ContentTargetingTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript(
            """
            CREATE TABLE student_profiles (user_id INTEGER PRIMARY KEY, class_id INTEGER);
            CREATE TABLE homework (id INTEGER PRIMARY KEY, class_id INTEGER, target_mode TEXT NOT NULL);
            CREATE TABLE homework_student_targets (homework_id INTEGER, student_id INTEGER);
            CREATE TABLE scheduled_tests (id INTEGER PRIMARY KEY, class_id INTEGER, target_mode TEXT NOT NULL);
            CREATE TABLE scheduled_test_student_targets (test_id INTEGER, student_id INTEGER);
            CREATE TABLE users (id INTEGER PRIMARY KEY, role TEXT, activated INTEGER);
            CREATE TABLE enrollments (class_id INTEGER, student_id INTEGER);
            """
        )
        # A parent UID may switch between these two independent school profiles.
        self.db.executemany("INSERT INTO student_profiles VALUES (?, ?)", [(101, 8), (202, 9)])
        self.db.executemany("INSERT INTO users VALUES (?, 'student', 1)", [(101,), (202,)])
        self.db.executemany("INSERT INTO enrollments VALUES (?, ?)", [(8, 101), (9, 202)])

    def tearDown(self):
        self.db.close()

    def test_class_homework_isolated_between_profiles(self):
        self.db.execute("INSERT INTO homework VALUES (1, 8, 'class')")
        self.assertTrue(content_visible_to_student(self.db, content_type="homework", content_id=1, student_id=101))
        self.assertFalse(content_visible_to_student(self.db, content_type="homework", content_id=1, student_id=202))
        self.assertEqual(
            target_student_ids(
                self.db, target_mode="class", class_id=8, content_id=1,
                target_table="homework_student_targets", target_column="homework_id",
            ),
            [101],
        )

    def test_specific_student_test_does_not_leak_to_sibling_profile(self):
        self.db.execute("INSERT INTO scheduled_tests VALUES (7, 8, 'students')")
        self.db.execute("INSERT INTO scheduled_test_student_targets VALUES (7, 101)")
        self.assertTrue(content_visible_to_student(self.db, content_type="test", content_id=7, student_id=101))
        self.assertFalse(content_visible_to_student(self.db, content_type="test", content_id=7, student_id=202))


if __name__ == "__main__":
    unittest.main()
