import sqlite3
from functools import wraps
from pathlib import Path

import click
from flask import abort, current_app, g, redirect, session, url_for
from werkzeug.security import generate_password_hash

from .twofactor_otp import normalize_indian_phone


def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(current_app.config["DATABASE"])
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
    return g.db


def close_db(_=None):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def role_required(*roles):
    def decorator(view):
        @wraps(view)
        def wrapped(*args, **kwargs):
            user = session.get("user")
            if not user:
                return redirect(url_for("main.login"))
            if roles and user["role"] not in roles:
                if roles == ("admin",):
                    abort(403)
                return redirect(url_for("main.dashboard"))
            return view(*args, **kwargs)

        return wrapped

    return decorator


def init_db():
    db_path = current_app.config["DATABASE"]
    upload_dir = current_app.config["UPLOAD_FOLDER"]
    Path(upload_dir).mkdir(parents=True, exist_ok=True)
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("PRAGMA foreign_keys = ON")

    cur.executescript(
        """
        DROP TABLE IF EXISTS announcements;
        DROP TABLE IF EXISTS feedback;
        DROP TABLE IF EXISTS admissions;
        DROP TABLE IF EXISTS timetable_entries;
        DROP TABLE IF EXISTS events;
        DROP TABLE IF EXISTS facilities;
        DROP TABLE IF EXISTS marks;
        DROP TABLE IF EXISTS homework_submissions;
        DROP TABLE IF EXISTS homework;
        DROP TABLE IF EXISTS attendance;
        DROP TABLE IF EXISTS class_subjects;
        DROP TABLE IF EXISTS enrollments;
        DROP TABLE IF EXISTS student_profiles;
        DROP TABLE IF EXISTS student_master_records;
        DROP TABLE IF EXISTS teacher_master_records;
        DROP TABLE IF EXISTS self_registration_otp_sessions;
        DROP TABLE IF EXISTS mobile_otp_sessions;
        DROP TABLE IF EXISTS login_rate_limits;
        DROP TABLE IF EXISTS subjects;
        DROP TABLE IF EXISTS classes;
        DROP TABLE IF EXISTS users;

        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            full_name TEXT NOT NULL,
            role TEXT NOT NULL CHECK(role IN ('admin', 'teacher', 'student')),
            email TEXT,
            email_verified_at REAL,
            phone TEXT,
            firebase_uid TEXT UNIQUE,
            activated INTEGER NOT NULL DEFAULT 1,
            must_change_password INTEGER NOT NULL DEFAULT 0,
            failed_login_attempts INTEGER NOT NULL DEFAULT 0,
            locked_until REAL
        );

        CREATE TABLE login_rate_limits (
            identifier TEXT PRIMARY KEY,
            failed_attempts INTEGER NOT NULL DEFAULT 0,
            locked_until REAL
        );

        CREATE TABLE mobile_otp_sessions (
            token_hash TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            purpose TEXT NOT NULL CHECK(purpose IN ('activation', 'password_reset')),
            phone TEXT,
            provider_session_id TEXT NOT NULL,
            sent_at REAL NOT NULL,
            expires_at REAL NOT NULL,
            verify_attempts INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        CREATE TABLE student_master_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            student_id TEXT UNIQUE NOT NULL,
            full_name TEXT NOT NULL,
            class_id INTEGER,
            roll_no TEXT,
            email TEXT,
            address TEXT,
            guardian_name TEXT,
            login_user_id INTEGER UNIQUE,
            registration_completed INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (login_user_id) REFERENCES users(id)
        );

        CREATE TABLE teacher_master_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            teacher_id TEXT UNIQUE NOT NULL,
            full_name TEXT NOT NULL,
            subject TEXT,
            login_user_id INTEGER UNIQUE,
            registration_completed INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (login_user_id) REFERENCES users(id)
        );

        CREATE TABLE self_registration_otp_sessions (
            token_hash TEXT PRIMARY KEY,
            role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
            master_record_id INTEGER NOT NULL,
            phone TEXT NOT NULL,
            provider_session_id TEXT NOT NULL,
            sent_at REAL NOT NULL,
            expires_at REAL NOT NULL,
            verify_attempts INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE email_registration_sessions (
            token_hash TEXT PRIMARY KEY,
            role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
            master_record_id INTEGER NOT NULL,
            email TEXT UNIQUE NOT NULL,
            firebase_uid TEXT UNIQUE NOT NULL,
            sent_at REAL NOT NULL,
            expires_at REAL NOT NULL
        );

        CREATE TABLE firebase_profile_links (
            firebase_uid TEXT NOT NULL,
            user_id INTEGER NOT NULL UNIQUE,
            PRIMARY KEY (firebase_uid, user_id),
            FOREIGN KEY (user_id) REFERENCES users(id)
        );

        -- A token is bound to one selected school profile, never merely to an email/UID.
        CREATE TABLE fcm_device_tokens (
            token TEXT PRIMARY KEY,
            firebase_uid TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            platform TEXT NOT NULL DEFAULT 'android',
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        CREATE INDEX idx_fcm_device_tokens_profile ON fcm_device_tokens(user_id);

        CREATE TABLE firebase_registration_sessions (
            token_hash TEXT PRIMARY KEY,
            role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
            master_record_id INTEGER NOT NULL,
            email TEXT NOT NULL,
            firebase_uid TEXT NOT NULL,
            email_already_verified INTEGER NOT NULL DEFAULT 0,
            sent_at REAL NOT NULL,
            expires_at REAL NOT NULL
        );

        CREATE TABLE existing_email_migration_sessions (
            token_hash TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL UNIQUE,
            email TEXT NOT NULL,
            firebase_uid TEXT NOT NULL,
            email_already_verified INTEGER NOT NULL DEFAULT 0,
            sent_at REAL NOT NULL,
            expires_at REAL NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        CREATE TABLE classes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            section TEXT NOT NULL,
            teacher_id INTEGER,
            FOREIGN KEY (teacher_id) REFERENCES users(id)
        );

        CREATE TABLE subjects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            code TEXT NOT NULL UNIQUE
        );

        CREATE TABLE student_profiles (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER UNIQUE NOT NULL,
            class_id INTEGER,
            roll_no TEXT,
            email TEXT,
            phone TEXT,
            address TEXT,
            guardian_name TEXT,
            FOREIGN KEY (user_id) REFERENCES users(id),
            FOREIGN KEY (class_id) REFERENCES classes(id)
        );

        CREATE TABLE enrollments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            class_id INTEGER NOT NULL,
            student_id INTEGER NOT NULL,
            UNIQUE(class_id, student_id),
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (student_id) REFERENCES users(id)
        );

        CREATE TABLE class_subjects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            class_id INTEGER NOT NULL,
            subject_id INTEGER NOT NULL,
            UNIQUE(class_id, subject_id),
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (subject_id) REFERENCES subjects(id)
        );

        CREATE TABLE attendance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            student_id INTEGER NOT NULL,
            class_id INTEGER NOT NULL,
            subject_id INTEGER NOT NULL,
            attendance_date TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('present', 'absent')),
            marked_by INTEGER NOT NULL,
            UNIQUE(student_id, class_id, subject_id, attendance_date),
            FOREIGN KEY (student_id) REFERENCES users(id),
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (subject_id) REFERENCES subjects(id),
            FOREIGN KEY (marked_by) REFERENCES users(id)
        );

        CREATE TABLE homework (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            class_id INTEGER NOT NULL,
            subject_id INTEGER NOT NULL,
            teacher_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            due_date TEXT NOT NULL,
            file_name TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (subject_id) REFERENCES subjects(id),
            FOREIGN KEY (teacher_id) REFERENCES users(id)
        );

        CREATE TABLE homework_submissions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            homework_id INTEGER NOT NULL,
            student_id INTEGER NOT NULL,
            notes TEXT,
            file_name TEXT,
            submitted_at TEXT DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(homework_id, student_id),
            FOREIGN KEY (homework_id) REFERENCES homework(id),
            FOREIGN KEY (student_id) REFERENCES users(id)
        );

        CREATE TABLE marks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            student_id INTEGER NOT NULL,
            class_id INTEGER NOT NULL,
            subject_id INTEGER NOT NULL,
            exam_name TEXT NOT NULL,
            total_marks INTEGER NOT NULL,
            obtained_marks INTEGER NOT NULL,
            grade TEXT NOT NULL,
            entered_by INTEGER NOT NULL,
            FOREIGN KEY (student_id) REFERENCES users(id),
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (subject_id) REFERENCES subjects(id),
            FOREIGN KEY (entered_by) REFERENCES users(id)
        );

        CREATE TABLE announcements (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            audience TEXT NOT NULL CHECK(audience IN ('all', 'admin', 'teacher', 'student')),
            created_by INTEGER NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (created_by) REFERENCES users(id)
        );

        CREATE TABLE facilities (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            icon TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            event_date TEXT NOT NULL,
            venue TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE timetable_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            class_id INTEGER NOT NULL,
            subject_id INTEGER NOT NULL,
            day_name TEXT NOT NULL,
            start_time TEXT NOT NULL,
            end_time TEXT NOT NULL,
            room TEXT,
            FOREIGN KEY (class_id) REFERENCES classes(id),
            FOREIGN KEY (subject_id) REFERENCES subjects(id)
        );

        CREATE TABLE admissions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            student_name TEXT NOT NULL,
            email TEXT NOT NULL,
            phone TEXT NOT NULL,
            applying_class TEXT NOT NULL,
            previous_school TEXT,
            message TEXT,
            status TEXT NOT NULL DEFAULT 'new',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE feedback (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            email TEXT NOT NULL,
            category TEXT NOT NULL,
            message TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );
        """
    )

    users = [
        ("admin", generate_password_hash("admin123"), "System Administrator", "admin", None),
        ("teacher1", generate_password_hash("teacher123"), "Anita Sharma", "teacher", None),
        ("teacher2", generate_password_hash("teacher123"), "Rahul Verma", "teacher", None),
        ("student1", generate_password_hash("student123"), "Aarav Mehta", "student", "+919999999991"),
        ("student2", generate_password_hash("student123"), "Diya Patel", "student", "+919999999992"),
        ("student3", generate_password_hash("student123"), "Kabir Singh", "student", "+919999999993"),
    ]
    cur.executemany(
        "INSERT INTO users (username, password_hash, full_name, role, phone) VALUES (?, ?, ?, ?, ?)",
        users,
    )

    classes = [("Grade 10", "A", 2), ("Grade 10", "B", 3)]
    cur.executemany("INSERT INTO classes (name, section, teacher_id) VALUES (?, ?, ?)", classes)

    subjects = [("Mathematics", "MATH101"), ("Science", "SCI101"), ("English", "ENG101")]
    cur.executemany("INSERT INTO subjects (name, code) VALUES (?, ?)", subjects)

    profiles = [
        (4, 1, "10A-01", "aarav@example.com", "9999999991", "Mumbai", "Rakesh Mehta"),
        (5, 1, "10A-02", "diya@example.com", "9999999992", "Pune", "Sunita Patel"),
        (6, 2, "10B-01", "kabir@example.com", "9999999993", "Delhi", "Amit Singh"),
    ]
    cur.executemany(
        """
        INSERT INTO student_profiles
        (user_id, class_id, roll_no, email, phone, address, guardian_name)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        profiles,
    )

    enrollments = [(1, 4), (1, 5), (2, 6)]
    cur.executemany("INSERT INTO enrollments (class_id, student_id) VALUES (?, ?)", enrollments)

    class_subjects = [(1, 1), (1, 2), (1, 3), (2, 1), (2, 2)]
    cur.executemany("INSERT INTO class_subjects (class_id, subject_id) VALUES (?, ?)", class_subjects)

    attendance = [
        (4, 1, 1, "2026-04-01", "present", 2),
        (4, 1, 2, "2026-04-01", "present", 2),
        (4, 1, 3, "2026-04-02", "absent", 2),
        (5, 1, 1, "2026-04-01", "present", 2),
        (5, 1, 2, "2026-04-01", "absent", 2),
        (5, 1, 3, "2026-04-02", "present", 2),
        (6, 2, 1, "2026-04-01", "present", 3),
        (6, 2, 2, "2026-04-02", "present", 3),
    ]
    cur.executemany(
        """
        INSERT INTO attendance
        (student_id, class_id, subject_id, attendance_date, status, marked_by)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        attendance,
    )

    homework = [
        (1, 1, 2, "Algebra Worksheet", "Solve questions 1 to 10.", "2026-04-10", None),
        (1, 2, 2, "Science Project", "Prepare a water cycle model.", "2026-04-12", None),
    ]
    cur.executemany(
        """
        INSERT INTO homework
        (class_id, subject_id, teacher_id, title, description, due_date, file_name)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        homework,
    )

    cur.execute(
        """
        INSERT INTO homework_submissions (homework_id, student_id, notes, file_name)
        VALUES (?, ?, ?, ?)
        """,
        (1, 4, "Completed all problems.", None),
    )

    marks = [
        (4, 1, 1, "Unit Test 1", 100, 88, "A", 2),
        (4, 1, 2, "Unit Test 1", 100, 81, "A", 2),
        (5, 1, 1, "Unit Test 1", 100, 72, "B", 2),
        (6, 2, 1, "Unit Test 1", 100, 90, "A+", 3),
    ]
    cur.executemany(
        """
        INSERT INTO marks
        (student_id, class_id, subject_id, exam_name, total_marks, obtained_marks, grade, entered_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        marks,
    )

    announcements = [
        ("Welcome", "Welcome to the school management portal.", "all", 1),
        ("Staff Meeting", "Teacher meeting at 3 PM on Friday.", "teacher", 1),
        ("Exam Notice", "Unit tests begin next Monday.", "student", 1),
    ]
    cur.executemany(
        "INSERT INTO announcements (title, content, audience, created_by) VALUES (?, ?, ?, ?)",
        announcements,
    )

    facilities = [
        ("Smart Classrooms", "Digital boards, projectors, and connected learning spaces.", "bi-easel"),
        ("Science Labs", "Fully equipped physics, chemistry, and biology laboratories.", "bi-beaker"),
        ("Library", "Quiet study area with academic and reference collections.", "bi-book"),
        ("Sports Complex", "Indoor and outdoor sports facilities for all grades.", "bi-trophy"),
    ]
    cur.executemany(
        "INSERT INTO facilities (title, description, icon) VALUES (?, ?, ?)",
        facilities,
    )

    events = [
        ("Annual Day", "Cultural performances and awards ceremony.", "2026-12-20", "Main Auditorium"),
        ("Science Exhibition", "Student innovation showcase and project demos.", "2026-08-14", "Science Block"),
        ("Parent Orientation", "Session for new admissions and school policies.", "2026-05-10", "Conference Hall"),
    ]
    cur.executemany(
        "INSERT INTO events (title, description, event_date, venue) VALUES (?, ?, ?, ?)",
        events,
    )

    timetable_entries = [
        (1, 1, "Monday", "09:00", "09:45", "Room 201"),
        (1, 2, "Monday", "10:00", "10:45", "Lab 1"),
        (1, 3, "Tuesday", "09:00", "09:45", "Room 204"),
        (2, 1, "Monday", "11:00", "11:45", "Room 301"),
        (2, 2, "Tuesday", "10:00", "10:45", "Lab 2"),
    ]
    cur.executemany(
        """
        INSERT INTO timetable_entries (class_id, subject_id, day_name, start_time, end_time, room)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        timetable_entries,
    )

    admissions = [
        ("Riya Kapoor", "riya@example.com", "9999999988", "Grade 8", "Sunrise School", "Interested in STEM programs.", "new"),
    ]
    cur.executemany(
        """
        INSERT INTO admissions
        (student_name, email, phone, applying_class, previous_school, message, status)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        admissions,
    )

    feedback_rows = [
        ("Parent Council", "parents@example.com", "Suggestion", "Please add more weekend activity updates."),
    ]
    cur.executemany(
        "INSERT INTO feedback (name, email, category, message) VALUES (?, ?, ?, ?)",
        feedback_rows,
    )

    # Sample master records mirror existing sample users as already registered.
    cur.execute(
        """
        INSERT INTO student_master_records
        (student_id, full_name, class_id, roll_no, email, address, guardian_name,
         login_user_id, registration_completed)
        SELECT u.username, u.full_name, sp.class_id, sp.roll_no, sp.email, sp.address,
               sp.guardian_name, u.id, 1
        FROM users u LEFT JOIN student_profiles sp ON sp.user_id = u.id
        WHERE u.role = 'student'
        """
    )
    cur.execute(
        """
        INSERT INTO teacher_master_records
        (teacher_id, full_name, login_user_id, registration_completed)
        SELECT username, full_name, id, 1 FROM users WHERE role = 'teacher'
        """
    )

    conn.commit()
    conn.close()


def migrate_db():
    """Add verified-phone and login-protection fields without deleting user data."""
    db_path = current_app.config["DATABASE"]
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        has_users_table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'users'"
        ).fetchone()
        if not has_users_table:
            return

        columns = {row[1] for row in conn.execute("PRAGMA table_info(users)")}
        if "phone" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN phone TEXT")
        if "email" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN email TEXT")
        if "email_verified_at" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN email_verified_at REAL")
        if "firebase_uid" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN firebase_uid TEXT")
        if "activated" not in columns:
            # Existing accounts keep working; new admin-provisioned records use 0.
            conn.execute("ALTER TABLE users ADD COLUMN activated INTEGER NOT NULL DEFAULT 1")
        if "must_change_password" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0")
        if "failed_login_attempts" not in columns:
            conn.execute(
                "ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0"
            )
        if "locked_until" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN locked_until REAL")

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS login_rate_limits (
                identifier TEXT PRIMARY KEY,
                failed_attempts INTEGER NOT NULL DEFAULT 0,
                locked_until REAL
            )
            """
        )

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS mobile_otp_sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                purpose TEXT NOT NULL CHECK(purpose IN ('activation', 'password_reset')),
                phone TEXT,
                provider_session_id TEXT NOT NULL,
                sent_at REAL NOT NULL,
                expires_at REAL NOT NULL,
                verify_attempts INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_mobile_otp_sessions_user_purpose "
            "ON mobile_otp_sessions(user_id, purpose, sent_at)"
        )
        mobile_otp_columns = {
            row[1] for row in conn.execute("PRAGMA table_info(mobile_otp_sessions)")
        }
        if "phone" not in mobile_otp_columns:
            conn.execute("ALTER TABLE mobile_otp_sessions ADD COLUMN phone TEXT")

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS student_master_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                student_id TEXT UNIQUE NOT NULL,
                full_name TEXT NOT NULL,
                class_id INTEGER,
                roll_no TEXT,
                email TEXT,
                address TEXT,
                guardian_name TEXT,
                login_user_id INTEGER UNIQUE,
                registration_completed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (class_id) REFERENCES classes(id),
                FOREIGN KEY (login_user_id) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS teacher_master_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                teacher_id TEXT UNIQUE NOT NULL,
                full_name TEXT NOT NULL,
                subject TEXT,
                login_user_id INTEGER UNIQUE,
                registration_completed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (login_user_id) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS self_registration_otp_sessions (
                token_hash TEXT PRIMARY KEY,
                role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
                master_record_id INTEGER NOT NULL,
                phone TEXT NOT NULL,
                provider_session_id TEXT NOT NULL,
                sent_at REAL NOT NULL,
                expires_at REAL NOT NULL,
                verify_attempts INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_self_registration_otp_master "
            "ON self_registration_otp_sessions(role, master_record_id, sent_at)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS email_registration_sessions (
                token_hash TEXT PRIMARY KEY,
                role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
                master_record_id INTEGER NOT NULL,
                email TEXT UNIQUE NOT NULL,
                firebase_uid TEXT UNIQUE NOT NULL,
                sent_at REAL NOT NULL,
                expires_at REAL NOT NULL
            )
            """
        )

        # Existing student contact numbers are retained and made usable for OTP recovery.
        has_profiles_table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'student_profiles'"
        ).fetchone()
        if has_profiles_table:
            profiles = conn.execute(
                """
                SELECT u.id, sp.phone
                FROM users u
                JOIN student_profiles sp ON sp.user_id = u.id
                WHERE u.phone IS NULL AND sp.phone IS NOT NULL AND TRIM(sp.phone) != ''
                """
            ).fetchall()
            for user_id, phone in profiles:
                try:
                    conn.execute(
                        "UPDATE users SET phone = ? WHERE id = ?",
                        (normalize_indian_phone(phone), user_id),
                    )
                except (ValueError, sqlite3.IntegrityError):
                    # Preserve non-Indian legacy values; they cannot use this Indian OTP flow.
                    continue

        # Existing login accounts are preserved and represented as already-complete master records.
        conn.execute(
            """
            INSERT OR IGNORE INTO student_master_records
            (student_id, full_name, class_id, roll_no, email, address, guardian_name,
             login_user_id, registration_completed)
            SELECT u.username, u.full_name, sp.class_id, sp.roll_no, sp.email, sp.address,
                   sp.guardian_name, u.id, 1
            FROM users u
            LEFT JOIN student_profiles sp ON sp.user_id = u.id
            WHERE u.role = 'student'
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO teacher_master_records
            (teacher_id, full_name, login_user_id, registration_completed)
            SELECT username, full_name, id, 1 FROM users WHERE role = 'teacher'
            """
        )

        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL")
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL")
        # A parent/guardian may deliberately link one verified Firebase email to several school
        # profiles.  Keep the legacy UID column intact, but use firebase_profile_links for the
        # many-to-one relationship going forward.
        conn.execute("DROP INDEX IF EXISTS idx_users_email")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS firebase_profile_links (
                firebase_uid TEXT NOT NULL,
                user_id INTEGER NOT NULL UNIQUE,
                PRIMARY KEY (firebase_uid, user_id),
                FOREIGN KEY (user_id) REFERENCES users(id)
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS firebase_registration_sessions (
                token_hash TEXT PRIMARY KEY,
                role TEXT NOT NULL CHECK(role IN ('student', 'teacher')),
                master_record_id INTEGER NOT NULL,
                email TEXT NOT NULL,
                firebase_uid TEXT NOT NULL,
                email_already_verified INTEGER NOT NULL DEFAULT 0,
                sent_at REAL NOT NULL,
                expires_at REAL NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS existing_email_migration_sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL UNIQUE,
                email TEXT NOT NULL,
                firebase_uid TEXT NOT NULL,
                email_already_verified INTEGER NOT NULL DEFAULT 0,
                sent_at REAL NOT NULL,
                expires_at REAL NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_firebase_profile_links_uid ON firebase_profile_links(firebase_uid)")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS fcm_device_tokens (
                token TEXT PRIMARY KEY,
                firebase_uid TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                platform TEXT NOT NULL DEFAULT 'android',
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_fcm_device_tokens_profile ON fcm_device_tokens(user_id)")
        # Cloudinary asset metadata is stored in the school database; the API secret
        # never appears in a row or is sent to the Android client.
        conn.execute(
            """CREATE TABLE IF NOT EXISTS media_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                public_id TEXT NOT NULL UNIQUE,
                secure_url TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                delivery_type TEXT NOT NULL CHECK(delivery_type IN ('upload', 'private', 'authenticated')),
                file_format TEXT,
                original_filename TEXT NOT NULL,
                byte_size INTEGER NOT NULL DEFAULT 0,
                created_by INTEGER,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_media_assets_public_id ON media_assets(public_id)")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS gallery_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                image_url TEXT NOT NULL,
                cloudinary_public_id TEXT,
                media_asset_id INTEGER,
                sort_order INTEGER NOT NULL DEFAULT 0,
                legacy_gallery_id TEXT,
                legacy_image_url TEXT,
                created_by INTEGER,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (media_asset_id) REFERENCES media_assets(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_gallery_items_sort ON gallery_items(sort_order, id)")
        for table in ("facilities", "events", "announcements"):
            columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
            if "image_url" not in columns:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN image_url TEXT")
            if "cloudinary_public_id" not in columns:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN cloudinary_public_id TEXT")
            if "media_asset_id" not in columns:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN media_asset_id INTEGER")

        homework_columns = {row[1] for row in conn.execute("PRAGMA table_info(homework)")}
        for column, definition in {
            "section": "TEXT",
            "target_mode": "TEXT NOT NULL DEFAULT 'class'",
            "instructions": "TEXT",
            "external_link": "TEXT",
            "assigned_date": "TEXT",
        }.items():
            if column not in homework_columns:
                conn.execute(f"ALTER TABLE homework ADD COLUMN {column} {definition}")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS homework_student_targets (
                homework_id INTEGER NOT NULL,
                student_id INTEGER NOT NULL,
                PRIMARY KEY (homework_id, student_id),
                FOREIGN KEY (homework_id) REFERENCES homework(id) ON DELETE CASCADE,
                FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS content_attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_type TEXT NOT NULL CHECK(owner_type IN ('homework', 'test')),
                owner_id INTEGER NOT NULL,
                media_asset_id INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE CASCADE
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_content_attachments_owner ON content_attachments(owner_type, owner_id)")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS scheduled_tests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                class_id INTEGER,
                section TEXT,
                subject_id INTEGER NOT NULL,
                teacher_id INTEGER NOT NULL,
                target_mode TEXT NOT NULL DEFAULT 'class',
                title TEXT NOT NULL,
                syllabus TEXT,
                instructions TEXT,
                test_date TEXT NOT NULL,
                start_time TEXT,
                end_time TEXT,
                maximum_marks INTEGER,
                external_link TEXT,
                result_published INTEGER NOT NULL DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (class_id) REFERENCES classes(id),
                FOREIGN KEY (subject_id) REFERENCES subjects(id),
                FOREIGN KEY (teacher_id) REFERENCES users(id)
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_scheduled_tests_class_date ON scheduled_tests(class_id, test_date)")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS scheduled_test_student_targets (
                test_id INTEGER NOT NULL,
                student_id INTEGER NOT NULL,
                PRIMARY KEY (test_id, student_id),
                FOREIGN KEY (test_id) REFERENCES scheduled_tests(id) ON DELETE CASCADE,
                FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_firebase_registration_master ON firebase_registration_sessions(role, master_record_id)")
        conn.execute(
            """INSERT OR IGNORE INTO firebase_profile_links (firebase_uid, user_id)
            SELECT firebase_uid, id FROM users
            WHERE firebase_uid IS NOT NULL AND TRIM(firebase_uid) <> ''"""
        )
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_firebase_uid ON users(firebase_uid) WHERE firebase_uid IS NOT NULL")
        conn.commit()
    finally:
        conn.close()


@click.command("init-db")
def init_db_command():
    init_db()
    click.echo("Initialized the development database with sample data.")


@click.command("migrate-db")
def migrate_db_command():
    migrate_db()
    click.echo("Applied non-destructive database migrations.")


def register_db_commands(app):
    app.cli.add_command(init_db_command)
    app.cli.add_command(migrate_db_command)
