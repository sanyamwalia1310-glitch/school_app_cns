import hashlib
import os
import re
import secrets
import sqlite3
import time
import uuid
from datetime import date

from flask import (
    Blueprint,
    abort,
    current_app,
    flash,
    g,
    jsonify,
    redirect,
    render_template,
    request,
    send_from_directory,
    session,
    url_for,
)
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.utils import secure_filename

from .cloudinary_media import (
    CloudinaryUnavailable,
    delete_media,
    private_download_url,
    upload_private,
    upload_public,
)
from .database import close_db, get_db, role_required
from .db_adapter import integrity_errors
from .firebase_auth import (
    FirebaseAuthProvisioningError,
    create_pending_email_account,
    firebase_identity_email,
    finalize_email_account,
    provision_firebase_password,
    verified_firebase_admin_uid,
    verified_firebase_uid,
    verify_pending_email,
)
from .firebase_notifications import send_profile_notification, send_public_notification
from .public_content_sync import sync_public_announcements, sync_public_gallery
from .twofactor_otp import (
    TwoFactorOtpError,
    normalize_indian_phone,
    send_otp,
    verify_otp,
)


main = Blueprint("main", __name__)


@main.before_app_request
def load_logged_in_user():
    expires_at = session.get("expires_at")
    if expires_at and time.time() >= expires_at:
        session.clear()
    g.user = session.get("user")


@main.teardown_app_request
def teardown_db(exception):
    close_db(exception)


@main.app_template_filter("percentage")
def percentage_filter(value):
    return f"{value:.1f}%"


def save_uploaded_file(file_storage):
    if not file_storage or not file_storage.filename:
        return None
    original = secure_filename(file_storage.filename)
    filename = f"{uuid.uuid4().hex}_{original}"
    upload_dir = current_app.config["UPLOAD_FOLDER"]
    os.makedirs(upload_dir, exist_ok=True)
    file_storage.save(os.path.join(upload_dir, filename))
    return filename


def grade_from_score(obtained, total):
    percent = (obtained / total) * 100 if total else 0
    if percent >= 90:
        return "A+"
    if percent >= 80:
        return "A"
    if percent >= 70:
        return "B"
    if percent >= 60:
        return "C"
    return "D"


OTP_PURPOSES = {"activation", "password_reset"}
MOBILE_OTP_ROLES = {"student", "teacher"}
EMAIL_REGISTRATION_ROLES = {"student", "teacher"}


class MobileOtpApiError(ValueError):
    def __init__(self, message, status_code=400, retry_after=None):
        super().__init__(message)
        self.status_code = status_code
        self.retry_after = retry_after


def otp_session_key(purpose):
    return f"twofactor_otp_{purpose}"


def verified_phone_from_request(purpose):
    otp_state = session.get(otp_session_key(purpose))
    if not otp_state:
        raise TwoFactorOtpError("Send an OTP to this number before continuing.")

    requested_phone = normalize_indian_phone(request.form.get("phone", ""))
    if requested_phone != otp_state.get("phone"):
        raise TwoFactorOtpError("The mobile number changed. Send a new OTP to continue.")
    if purpose == "activation":
        if request.form.get("username", "").strip() != otp_state.get("identifier"):
            raise TwoFactorOtpError("The school ID changed. Send a new OTP to continue.")
        if request.form.get("role", "").strip().lower() != otp_state.get("role"):
            raise TwoFactorOtpError("The role changed. Send a new OTP to continue.")

    verify_otp(otp_state.get("session_id", ""), request.form.get("otp", "").strip())
    session.pop(otp_session_key(purpose), None)
    return requested_phone


def self_registration_master_record(payload):
    """Resolve an unregistered student/teacher master record without requiring a login user."""
    identifier = str(payload.get("identifier", "")).strip()
    requested_role = str(payload.get("role", "")).strip().lower()
    if not identifier:
        raise MobileOtpApiError("Enter your Student ID or Teacher ID.")
    if requested_role not in MOBILE_OTP_ROLES:
        raise MobileOtpApiError("Select Student or Teacher.")

    table = "student_master_records" if requested_role == "student" else "teacher_master_records"
    id_column = "student_id" if requested_role == "student" else "teacher_id"
    master = get_db().execute(
        f"SELECT * FROM {table} WHERE {id_column} = ?", (identifier,)
    ).fetchone()
    if not master:
        raise MobileOtpApiError("Student/Teacher ID not found in school records.")
    if master["registration_completed"] or master["login_user_id"] is not None:
        raise MobileOtpApiError("This Student/Teacher ID has already registered a login account.")

    try:
        phone = normalize_indian_phone(str(payload.get("phone", "")))
    except TwoFactorOtpError as error:
        raise MobileOtpApiError(str(error)) from error
    if get_db().execute("SELECT 1 FROM users WHERE phone = ?", (phone,)).fetchone():
        raise MobileOtpApiError("This mobile number is already registered to another school account.")
    return requested_role, master, phone


def mobile_otp_account(payload):
    """Resolve the existing activated login account for a password reset."""
    identifier = str(payload.get("identifier", "")).strip()
    requested_role = str(payload.get("role", "")).strip().lower()
    if not identifier:
        raise MobileOtpApiError("Enter your Student ID or Teacher ID.")
    if requested_role not in MOBILE_OTP_ROLES:
        raise MobileOtpApiError("Select Student or Teacher.")
    user = get_db().execute(
        "SELECT * FROM users WHERE username = ? AND role = ? AND activated = 1",
        (identifier, requested_role),
    ).fetchone()
    if not user:
        raise MobileOtpApiError("Student/Teacher ID not found in school records.")

    try:
        phone = normalize_indian_phone(user["phone"] or "")
    except TwoFactorOtpError as error:
        raise MobileOtpApiError("This account has no valid registered Indian mobile number. Contact the administrator.") from error
    return user, phone


def mobile_otp_token_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def normalize_email(value):
    email = str(value or "").strip().lower()
    # Firebase ultimately validates its address format; reject obviously malformed data before
    # creating a pending account or exposing any master-record state.
    if len(email) > 254 or email.count("@") != 1:
        raise MobileOtpApiError("Enter a valid email address.")
    local, domain = email.rsplit("@", 1)
    if not local or not domain or "." not in domain or any(char.isspace() for char in email):
        raise MobileOtpApiError("Enter a valid email address.")
    return email


def _master_has_activated_login(db, master, id_column):
    """Return whether a master record is backed by a real activated local login.

    A master record is deliberately created by an administrator *before* a student
    activates their account.  Older interrupted registration attempts can leave an
    inactive ``users`` row or a stale master-record flag behind.  Those artefacts
    must not turn a pending student into an "already registered" student.
    """
    user_ids = []
    if master["login_user_id"] is not None:
        user_ids.append(master["login_user_id"])

    named_user = db.execute(
        "SELECT id, activated FROM users WHERE username = ?", (master[id_column],)
    ).fetchone()
    if named_user and named_user["id"] not in user_ids:
        user_ids.append(named_user["id"])

    for user_id in user_ids:
        user = db.execute("SELECT activated FROM users WHERE id = ?", (user_id,)).fetchone()
        if user and int(user["activated"] or 0) == 1:
            return True
    return False


def _restore_pending_master_record(db, table, id_column, master):
    """Clear only stale incomplete-registration links and return a fresh record.

    This never changes an activated account.  It only repairs a legacy/incomplete
    row where no activated login exists, so the student can finish the email
    activation flow normally.
    """
    if _master_has_activated_login(db, master, id_column):
        raise MobileOtpApiError("This Student/Teacher ID has already registered a login account.")

    if master["registration_completed"] or master["login_user_id"] is not None:
        db.execute(
            f"UPDATE {table} SET login_user_id = NULL, registration_completed = 0 WHERE id = ?",
            (master["id"],),
        )
        master = db.execute("SELECT * FROM " + table + " WHERE id = ?", (master["id"],)).fetchone()
    return master


def email_registration_master_record(payload):
    """Resolve one pending master record for the Firebase email-registration flow."""
    identifier = str(payload.get("identifier", "")).strip()
    role = str(payload.get("role", "")).strip().lower()
    if not identifier:
        raise MobileOtpApiError("Enter your Student ID or Teacher ID.")
    if role not in EMAIL_REGISTRATION_ROLES:
        raise MobileOtpApiError("Select Student or Teacher.")
    table = "student_master_records" if role == "student" else "teacher_master_records"
    id_column = "student_id" if role == "student" else "teacher_id"
    db = get_db()
    master = db.execute(f"SELECT * FROM {table} WHERE {id_column} = ?", (identifier,)).fetchone()
    if not master:
        raise MobileOtpApiError("Student/Teacher ID not found in school records.")
    return role, table, id_column, _restore_pending_master_record(db, table, id_column, master)


def create_email_login_from_master(db, role, master, email, firebase_uid):
    """Create the local login link only after Firebase reports this email is verified."""
    table = "student_master_records" if role == "student" else "teacher_master_records"
    id_column = "student_id" if role == "student" else "teacher_id"
    master = _restore_pending_master_record(db, table, id_column, master)
    existing_user = db.execute(
        "SELECT * FROM users WHERE username = ?", (master[id_column],)
    ).fetchone()
    if existing_user:
        # Reuse only a demonstrably inactive row left by an interrupted legacy
        # attempt.  Keeping its ID avoids breaking any non-sensitive references.
        db.execute(
            """UPDATE users
            SET password_hash = ?, full_name = ?, role = ?, email = ?,
                email_verified_at = ?, firebase_uid = NULL, activated = 1
            WHERE id = ?""",
            (
                generate_password_hash(secrets.token_urlsafe(32)), master["full_name"], role,
                email, time.time(), existing_user["id"],
            ),
        )
        user = db.execute("SELECT * FROM users WHERE id = ?", (existing_user["id"],)).fetchone()
    else:
        cursor = db.execute(
            """
            INSERT INTO users (username, password_hash, full_name, role, email, email_verified_at, firebase_uid, activated)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1) RETURNING id
            """,
            # firebase_uid remains NULL for shared identities because the legacy column is UNIQUE.
            # The dedicated mapping table below is the authoritative link for all new registrations.
            (master[id_column], generate_password_hash(secrets.token_urlsafe(32)), master["full_name"], role, email, time.time(), None),
        )
        user = db.execute("SELECT * FROM users WHERE id = ?", (cursor.fetchone()["id"],)).fetchone()

    db.execute(
        """INSERT INTO firebase_profile_links (firebase_uid, user_id) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET firebase_uid = EXCLUDED.firebase_uid""",
        (firebase_uid, user["id"]),
    )
    if role == "student":
        db.execute(
            """INSERT INTO student_profiles
            (user_id, class_id, roll_no, email, phone, address, guardian_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                class_id = EXCLUDED.class_id, roll_no = EXCLUDED.roll_no,
                email = EXCLUDED.email, phone = EXCLUDED.phone,
                address = EXCLUDED.address, guardian_name = EXCLUDED.guardian_name""",
            (user["id"], master["class_id"], master["roll_no"], email, None, master["address"], master["guardian_name"]),
        )
    db.execute(f"UPDATE {table} SET login_user_id = ?, registration_completed = 1 WHERE id = ?", (user["id"], master["id"]))
    return user


def link_firebase_uid(db, user, firebase_uid):
    """Persist the stable Firebase mapping without allowing a UID to move between school users."""
    current_uid = (user["firebase_uid"] or "").strip()
    if current_uid and current_uid != firebase_uid:
        raise FirebaseAuthProvisioningError("This school account has an inconsistent Firebase mapping.")
    try:
        db.execute(
            "UPDATE users SET firebase_uid = ? WHERE id = ? AND (firebase_uid IS NULL OR firebase_uid = ?)",
            (firebase_uid, user["id"], firebase_uid),
        )
    except integrity_errors() as error:
        raise FirebaseAuthProvisioningError(
            "This Firebase account is already linked to another school record."
        ) from error


def create_login_from_master(db, role, master, phone, password):
    """Create the login row only after a master record has completed OTP verification."""
    table = "student_master_records" if role == "student" else "teacher_master_records"
    id_column = "student_id" if role == "student" else "teacher_id"
    if master["registration_completed"] or master["login_user_id"] is not None:
        raise MobileOtpApiError("This Student/Teacher ID has already registered a login account.")
    if db.execute("SELECT 1 FROM users WHERE username = ?", (master[id_column],)).fetchone():
        raise MobileOtpApiError("This Student/Teacher ID has already registered a login account.")
    if db.execute("SELECT 1 FROM users WHERE phone = ?", (phone,)).fetchone():
        raise MobileOtpApiError("This mobile number is already registered to another school account.")

    cursor = db.execute(
        """
        INSERT INTO users (username, password_hash, full_name, role, phone, activated)
        VALUES (?, ?, ?, ?, ?, 1) RETURNING id
        """,
        (
            master[id_column],
            generate_password_hash(secrets.token_urlsafe(32)),
            master["full_name"],
            role,
            phone,
        ),
    )
    user = db.execute("SELECT * FROM users WHERE id = ?", (cursor.fetchone()["id"],)).fetchone()
    firebase_uid = provision_firebase_password(user, password, allow_create=True)
    link_firebase_uid(db, user, firebase_uid)
    if role == "student":
        db.execute(
            """
            INSERT INTO student_profiles
            (user_id, class_id, roll_no, email, phone, address, guardian_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                user["id"], master["class_id"], master["roll_no"], master["email"],
                phone, master["address"], master["guardian_name"],
            ),
        )
    db.execute(
        f"UPDATE {table} SET login_user_id = ?, registration_completed = 1 WHERE id = ?",
        (user["id"], master["id"]),
    )
    return user


def complete_self_registration_otp(payload, password, confirm_password):
    """Verify a master-record registration OTP, then create exactly one login user and Firebase UID."""
    token = str(payload.get("otp_session_token", "")).strip()
    otp = str(payload.get("otp", "")).strip()
    if not token:
        return jsonify(error="OTP session is invalid or has expired. Send a new OTP."), 400
    if len(password) < 8 or password != confirm_password:
        return jsonify(error="New passwords must match and be at least 8 characters."), 400

    db = get_db()
    now = time.time()
    session_row = db.execute(
        "SELECT * FROM self_registration_otp_sessions WHERE token_hash = ?",
        (mobile_otp_token_hash(token),),
    ).fetchone()
    if not session_row or session_row["expires_at"] <= now:
        if session_row:
            db.execute(
                "DELETE FROM self_registration_otp_sessions WHERE token_hash = ?",
                (session_row["token_hash"],),
            )
            db.commit()
        return jsonify(error="OTP session has expired. Send a new OTP."), 400

    try:
        verify_otp(session_row["provider_session_id"], otp)
    except TwoFactorOtpError as error:
        attempts = session_row["verify_attempts"] + 1
        if attempts >= current_app.config["MOBILE_OTP_MAX_VERIFY_ATTEMPTS"]:
            db.execute(
                "DELETE FROM self_registration_otp_sessions WHERE token_hash = ?",
                (session_row["token_hash"],),
            )
            message = "Too many invalid OTP attempts. Send a new OTP."
        else:
            db.execute(
                "UPDATE self_registration_otp_sessions SET verify_attempts = ? WHERE token_hash = ?",
                (attempts, session_row["token_hash"]),
            )
            message = str(error)
        db.commit()
        return jsonify(error=message), 400

    role = session_row["role"]
    table = "student_master_records" if role == "student" else "teacher_master_records"
    master = db.execute(
        f"SELECT * FROM {table} WHERE id = ?", (session_row["master_record_id"],)
    ).fetchone()
    if not master:
        return jsonify(error="Student/Teacher ID not found in school records."), 400
    try:
        create_login_from_master(db, role, master, session_row["phone"], password)
        db.execute(
            "DELETE FROM self_registration_otp_sessions WHERE token_hash = ?",
            (session_row["token_hash"],),
        )
        db.commit()
    except (*integrity_errors(), FirebaseAuthProvisioningError, MobileOtpApiError) as error:
        db.rollback()
        message = (
            str(error)
            if isinstance(error, (FirebaseAuthProvisioningError, MobileOtpApiError))
            else "Registration could not be completed. The ID or mobile number may already be registered."
        )
        return jsonify(error=message), 400

    return jsonify(message="Registration completed. You can now sign in.")


def send_self_registration_otp(payload):
    """Send an activation OTP for an unregistered master record without creating a login user."""
    try:
        role, master, phone = self_registration_master_record(payload)
        db = get_db()
        now = time.time()
        db.execute("DELETE FROM self_registration_otp_sessions WHERE expires_at <= ?", (now,))
        previous = db.execute(
            """
            SELECT sent_at FROM self_registration_otp_sessions
            WHERE role = ? AND master_record_id = ? ORDER BY sent_at DESC LIMIT 1
            """,
            (role, master["id"]),
        ).fetchone()
        cooldown = current_app.config["OTP_RESEND_COOLDOWN_SECONDS"]
        if previous:
            remaining = int(cooldown - (now - previous["sent_at"]))
            if remaining > 0:
                db.commit()
                raise MobileOtpApiError(
                    f"Please wait {remaining} seconds before requesting another OTP.",
                    status_code=429,
                    retry_after=remaining,
                )
        provider_session_id = send_otp(phone)
        session_token = secrets.token_urlsafe(32)
        db.execute(
            "DELETE FROM self_registration_otp_sessions WHERE role = ? AND master_record_id = ?",
            (role, master["id"]),
        )
        db.execute(
            """
            INSERT INTO self_registration_otp_sessions
            (token_hash, role, master_record_id, phone, provider_session_id, sent_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                mobile_otp_token_hash(session_token), role, master["id"], phone,
                provider_session_id, now,
                now + current_app.config["MOBILE_OTP_SESSION_TTL_SECONDS"],
            ),
        )
        db.commit()
        return jsonify(
            message="OTP sent to your mobile number.",
            otp_session_token=session_token,
            cooldown=cooldown,
            expires_in=current_app.config["MOBILE_OTP_SESSION_TTL_SECONDS"],
        )
    except MobileOtpApiError as error:
        return mobile_otp_error_response(error)
    except TwoFactorOtpError as error:
        return jsonify(error=str(error)), 400


def mobile_otp_error_response(error):
    response = {"error": str(error)}
    if error.retry_after is not None:
        response["retry_after"] = error.retry_after
    return jsonify(response), error.status_code


def teacher_has_class_access(db, teacher_id, class_id):
    row = db.execute(
        "SELECT id FROM classes WHERE id = ? AND teacher_id = ?",
        (class_id, teacher_id),
    ).fetchone()
    return row is not None


def class_has_subject(db, class_id, subject_id):
    row = db.execute(
        "SELECT 1 FROM class_subjects WHERE class_id = ? AND subject_id = ?",
        (class_id, subject_id),
    ).fetchone()
    return row is not None


def student_in_class(db, student_id, class_id):
    row = db.execute(
        "SELECT 1 FROM enrollments WHERE class_id = ? AND student_id = ?",
        (class_id, student_id),
    ).fetchone()
    return row is not None


def mobile_class_by_name(db, class_name):
    """Resolve a server class display name without guessing an ambiguous section."""
    value = str(class_name or "").strip()
    if not value:
        raise ValueError("Choose a class.")

    def normalize(value):
        # Older Android builds used "Class 10" while the server stores
        # "Grade 10".  Treat those prefixes as presentation only, but retain
        # the section so "10-A" can never silently become "10-B".
        value = re.sub(r"\b(?:class|grade|standard)\s*", "", str(value or ""), flags=re.I)
        return " ".join(value.lower().split())

    normalized = normalize(value)
    matches = []
    for row in db.execute("SELECT id, name, section FROM classes ORDER BY id").fetchall():
        name = normalize(row["name"])
        section = " ".join(str(row["section"] or "").lower().split())
        if normalized in {name, f"{name} - {section}".strip(" -")}:
            matches.append(row)
    if len(matches) != 1:
        raise ValueError("Choose the exact class and section configured on the school server.")
    return matches[0]


def mobile_subject_for_class(db, class_id, subject_name=""):
    """Resolve a subject from the server-owned class/subject assignment."""
    value = str(subject_name or "").strip()
    query = """SELECT s.id, s.name FROM class_subjects cs
        JOIN subjects s ON s.id = cs.subject_id WHERE cs.class_id = ?"""
    params = [class_id]
    if value:
        query += " AND LOWER(s.name) = LOWER(?)"
        params.append(value)
    query += " ORDER BY s.name LIMIT 1"
    row = db.execute(query, tuple(params)).fetchone()
    if not row:
        if value:
            raise ValueError("This subject is not assigned to the selected class.")
        raise ValueError("No subject is assigned to this class. Ask an administrator to configure class subjects.")
    return row


def require_mobile_staff_class_access(db, actor, class_id):
    if actor["role"] not in {"admin", "teacher"}:
        raise FirebaseAuthProvisioningError("Only an administrator or teacher may change academic records.")
    if actor["role"] == "teacher" and not teacher_has_class_access(db, actor["id"], class_id):
        raise FirebaseAuthProvisioningError("Teachers may only manage their assigned classes.")


def student_can_access_homework(db, student_id, homework_id):
    row = db.execute(
        """
        SELECT h.id
        FROM homework h
        JOIN student_profiles sp ON sp.class_id = h.class_id
        WHERE h.id = ? AND sp.user_id = ?
        """,
        (homework_id, student_id),
    ).fetchone()
    return row is not None


def linked_school_profiles(db, firebase_uid):
    """Return active school profiles linked to one verified Firebase identity."""
    return db.execute(
        """SELECT u.* FROM firebase_profile_links l JOIN users u ON u.id = l.user_id
        WHERE l.firebase_uid = ? AND u.role IN ('student', 'teacher') AND u.activated = 1
        ORDER BY u.role, u.username""",
        (firebase_uid,),
    ).fetchall()


def repair_admin_firebase_profile(db, firebase_uid, firebase_id_token):
    """Bind the sole unmapped active admin only after its Firebase admin claim is verified."""
    if verified_firebase_admin_uid(firebase_id_token) != firebase_uid:
        raise FirebaseAuthProvisioningError("Administrator authorization is required.")
    existing = db.execute(
        """SELECT * FROM users WHERE role = 'admin' AND activated = 1 AND firebase_uid = ?
        ORDER BY id""",
        (firebase_uid,),
    ).fetchone()
    if existing:
        return existing
    candidates = db.execute(
        """SELECT * FROM users WHERE role = 'admin' AND activated = 1
        AND (firebase_uid IS NULL OR TRIM(firebase_uid) = '') ORDER BY id"""
    ).fetchall()
    if len(candidates) != 1:
        raise FirebaseAuthProvisioningError("This administrator Firebase identity is not linked to a school administrator profile.")
    admin = candidates[0]
    db.execute(
        """UPDATE users SET firebase_uid = ?
        WHERE id = ? AND (firebase_uid IS NULL OR TRIM(firebase_uid) = '')""",
        (firebase_uid, admin["id"]),
    )
    db.commit()
    current_app.logger.info("Repaired Firebase mapping for the existing administrator profile.")
    return db.execute("SELECT * FROM users WHERE id = ?", (admin["id"],)).fetchone()


def repair_missing_firebase_profile_links(db, firebase_uid, firebase_id_token):
    """Safely repair legacy profile links after Firebase proves identity ownership.

    The profile-link table is authoritative because one verified parent Firebase
    identity can be linked to several student profiles. A legacy row is repaired
    only when the authenticated Firebase email exactly matches a stored school
    email, a student master-record email, or the old deterministic school-ID
    Firebase address. No relationship is guessed from client input.
    """
    firebase_email, _email_verified = firebase_identity_email(firebase_uid, firebase_id_token)
    firebase_email = firebase_email.strip().lower()
    if not firebase_email:
        return []

    legacy_domain = current_app.config["FIREBASE_AUTH_EMAIL_DOMAIN"].strip().lower()
    legacy_suffix = f"@{legacy_domain}" if legacy_domain else ""
    legacy_username = (
        firebase_email[: -len(legacy_suffix)]
        if legacy_suffix and firebase_email.endswith(legacy_suffix)
        else ""
    )
    candidates = db.execute(
        """SELECT DISTINCT u.id, u.firebase_uid
        FROM users u
        LEFT JOIN student_master_records sm ON sm.login_user_id = u.id
        WHERE u.role IN ('student', 'teacher') AND u.activated = 1
          AND (u.firebase_uid IS NULL OR u.firebase_uid = ?)
          AND NOT EXISTS (
              SELECT 1 FROM firebase_profile_links existing
              WHERE existing.user_id = u.id AND existing.firebase_uid <> ?
          )
          AND (
              u.firebase_uid = ?
              OR LOWER(TRIM(COALESCE(u.email, ''))) = ?
              OR LOWER(TRIM(COALESCE(sm.email, ''))) = ?
              OR (? = 1 AND LOWER(TRIM(u.username)) = ?)
          )""",
        (
            firebase_uid,
            firebase_uid,
            firebase_uid,
            firebase_email,
            firebase_email,
            1 if legacy_username else 0,
            legacy_username,
        ),
    ).fetchall()
    for candidate in candidates:
        # Never replace an existing link to another Firebase UID.
        db.execute(
            """INSERT INTO firebase_profile_links (firebase_uid, user_id) VALUES (?, ?)
            ON CONFLICT DO NOTHING""",
            (firebase_uid, candidate["id"]),
        )

    profiles = linked_school_profiles(db, firebase_uid)
    # users.firebase_uid is a legacy unique field, so update it only when one
    # school profile is linked. Shared parents are represented by link rows.
    if len(profiles) == 1:
        db.execute(
            """UPDATE users SET firebase_uid = ?
            WHERE id = ? AND (firebase_uid IS NULL OR firebase_uid = ?)""",
            (firebase_uid, profiles[0]["id"], firebase_uid),
        )
    if candidates:
        db.commit()
        current_app.logger.info("Repaired Firebase profile links for %s active school profile(s).", len(candidates))
    return profiles


def mobile_profile_from_payload(payload, *allowed_roles):
    """Resolve the exact profile selected by a Firebase-authenticated mobile user.

    A Firebase UID can represent several students.  Every private mobile endpoint
    therefore requires both an ID token and the selected school profile ID.
    """
    uid = verified_firebase_uid(str(payload.get("firebase_id_token", "")).strip())
    profile_id = int(payload.get("profile_id", 0))
    roles = allowed_roles or ("student", "teacher", "admin")
    placeholders = ",".join("?" for _ in roles)
    db = get_db()
    row = db.execute(
        f"""SELECT u.* FROM users u
            LEFT JOIN firebase_profile_links l ON l.user_id = u.id
            WHERE (l.firebase_uid = ? OR u.firebase_uid = ?)
              AND u.id = ? AND u.activated = 1 AND u.role IN ({placeholders})""",
        (uid, uid, profile_id, *roles),
    ).fetchone()
    if not row:
        # Repair only after Firebase authenticated this UID, then repeat the
        # exact selected-profile authorization check.
        repair_missing_firebase_profile_links(db, uid, str(payload.get("firebase_id_token", "")).strip())
        row = db.execute(
            f"""SELECT u.* FROM users u
                LEFT JOIN firebase_profile_links l ON l.user_id = u.id
                WHERE (l.firebase_uid = ? OR u.firebase_uid = ?)
                  AND u.id = ? AND u.activated = 1 AND u.role IN ({placeholders})""",
            (uid, uid, profile_id, *roles),
        ).fetchone()
    if not row:
        raise FirebaseAuthProvisioningError("This Firebase login is not authorized for the selected school profile.")
    return row


def media_row_from_upload(db, file_storage, *, is_public, folder, created_by):
    uploaded = upload_public(file_storage, folder=folder) if is_public else upload_private(file_storage, folder=folder)
    cursor = db.execute(
        """INSERT INTO media_assets
        (public_id, secure_url, resource_type, delivery_type, file_format, original_filename, byte_size, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
        (
            uploaded.public_id, uploaded.secure_url, uploaded.resource_type,
            uploaded.delivery_type, uploaded.format, uploaded.original_filename,
            uploaded.bytes, created_by,
        ),
    )
    return db.execute("SELECT * FROM media_assets WHERE id = ?", (cursor.fetchone()["id"],)).fetchone()


def student_profile_class_id(db, student_user_id):
    row = db.execute("SELECT class_id FROM student_profiles WHERE user_id = ?", (student_user_id,)).fetchone()
    return row["class_id"] if row else None


def target_student_ids(db, *, target_mode, class_id, content_id, target_table, target_column):
    if target_mode == "all":
        rows = db.execute("SELECT id FROM users WHERE role = 'student' AND activated = 1").fetchall()
    elif target_mode == "class":
        rows = db.execute("SELECT student_id AS id FROM enrollments WHERE class_id = ?", (class_id,)).fetchall()
    else:
        rows = db.execute(
            f"SELECT student_id AS id FROM {target_table} WHERE {target_column} = ?", (content_id,)
        ).fetchall()
    return [row["id"] for row in rows]


def notify_student_profiles(db, student_ids, *, title, body, event_type, destination, content_id):
    """Send only profile-bound FCM messages; never use a topic for class work."""
    for student_id in set(student_ids):
        try:
            send_profile_notification(
                db,
                profile_user_id=student_id,
                title=title,
                body=body,
                data={"event_type": event_type, "destination": destination, "content_id": content_id},
            )
        except FirebaseAuthProvisioningError:
            current_app.logger.warning("Content saved but private FCM delivery is unavailable.")


def content_visible_to_student(db, *, content_type, content_id, student_id):
    if content_type == "homework":
        table, target_table, target_column = "homework", "homework_student_targets", "homework_id"
    else:
        table, target_table, target_column = "scheduled_tests", "scheduled_test_student_targets", "test_id"
    class_id = student_profile_class_id(db, student_id)
    row = db.execute(
        f"""SELECT c.id FROM {table} c
            WHERE c.id = ? AND (
                c.target_mode = 'all' OR
                (c.target_mode = 'class' AND c.class_id = ?) OR
                (c.target_mode = 'students' AND EXISTS (
                    SELECT 1 FROM {target_table} t WHERE t.{target_column} = c.id AND t.student_id = ?
                ))
            )""",
        (content_id, class_id, student_id),
    ).fetchone()
    return row is not None


def validate_content_target(db, *, actor, class_id, subject_id, target_mode, target_student_ids):
    if target_mode not in {"all", "class", "students"}:
        raise ValueError("Choose all students, a class, or specific students.")
    if actor["role"] == "teacher":
        if not class_id or not teacher_has_class_access(db, actor["id"], class_id):
            raise FirebaseAuthProvisioningError("Teachers may only manage their assigned classes.")
        if not class_has_subject(db, class_id, subject_id):
            raise ValueError("The selected subject is not assigned to this class.")
    elif actor["role"] != "admin":
        raise FirebaseAuthProvisioningError("Only an administrator or teacher may create school work.")

    if target_mode in {"class", "students"} and not class_id:
        raise ValueError("Choose a class for this targeted content.")
    if target_mode == "students":
        if not target_student_ids:
            raise ValueError("Choose at least one student for a specific-student assignment.")
        for student_id in target_student_ids:
            if not student_in_class(db, student_id, class_id):
                raise ValueError("Every selected student must belong to the selected class.")


def save_content_attachment_links(db, *, owner_type, owner_id, asset_ids, uploaded_by=None):
    for asset_id in asset_ids:
        asset = db.execute("SELECT * FROM media_assets WHERE id = ?", (asset_id,)).fetchone()
        if not asset:
            raise ValueError("The selected attachment was not found. Choose the file again and retry.")
        if asset["delivery_type"] not in {"authenticated", "private"}:
            raise ValueError("Homework attachments must be uploaded through the secure school upload.")
        if uploaded_by is not None and asset["created_by"] != uploaded_by:
            raise FirebaseAuthProvisioningError("You may attach only files uploaded from your own school account.")
        db.execute(
            """INSERT INTO content_attachments (owner_type, owner_id, media_asset_id, display_name)
            VALUES (?, ?, ?, ?)""",
            (owner_type, owner_id, asset_id, asset["original_filename"]),
        )


def private_attachments_for(db, *, owner_type, owner_id):
    rows = db.execute(
        """SELECT ca.id, ca.display_name, ma.resource_type, ma.file_format
        FROM content_attachments ca JOIN media_assets ma ON ma.id = ca.media_asset_id
        WHERE ca.owner_type = ? AND ca.owner_id = ? ORDER BY ca.id""",
        (owner_type, owner_id),
    ).fetchall()
    return [dict(row) for row in rows]


@main.route("/")
def index():
    if g.user:
        return redirect(url_for("main.dashboard"))
    return redirect(url_for("main.home"))


@main.route("/home")
def home():
    db = get_db()
    announcements = db.execute(
        "SELECT * FROM announcements WHERE audience = 'all' ORDER BY created_at DESC LIMIT 3"
    ).fetchall()
    events = db.execute("SELECT * FROM events ORDER BY event_date ASC LIMIT 3").fetchall()
    facilities = db.execute("SELECT * FROM facilities ORDER BY id ASC LIMIT 4").fetchall()
    return render_template(
        "home.html",
        announcements=announcements,
        events=events,
        facilities=facilities,
    )


def login_page(*, admin_only=False):
    if request.method == "POST":
        identifier = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        normalized_phone = ""
        try:
            normalized_phone = normalize_indian_phone(identifier)
        except ValueError:
            pass

        db = get_db()
        login_key = (normalized_phone or identifier.lower())[:255]
        # Admin attempts must not consume or lock the Student/Teacher login
        # rate-limit entry for the same identifier.
        rate_limit_key = (
            f"admin:{identifier.casefold()[:240]}"
            if admin_only
            else login_key
        )
        identifier_limit = db.execute(
            "SELECT failed_attempts, locked_until FROM login_rate_limits WHERE identifier = ?",
            (rate_limit_key,),
        ).fetchone()
        if identifier_limit and identifier_limit["locked_until"] and time.time() < identifier_limit["locked_until"]:
            remaining_minutes = max(1, int((identifier_limit["locked_until"] - time.time() + 59) // 60))
            flash(f"Too many unsuccessful attempts. Try again in {remaining_minutes} minute(s).", "danger")
            return render_template("login.html", admin_only=admin_only)

        if admin_only:
            # Administrators authenticate only against their existing users.username
            # credential.  They never use the student/teacher ID, phone, or OTP flow.
            user = db.execute(
                """
                SELECT * FROM users
                WHERE LOWER(username) = LOWER(?) AND role = 'admin'
                LIMIT 1
                """,
                (identifier,),
            ).fetchone()
        else:
            user = db.execute(
                """
                SELECT * FROM users
                WHERE (username = ? OR (phone = ? AND role = 'student'))
                AND role IN ('student', 'teacher') LIMIT 1
                """,
                (identifier, normalized_phone),
            ).fetchone()

        if user and not user["activated"]:
            flash("Activate this school-created account with OTP before signing in.", "danger")
            return render_template("login.html", admin_only=admin_only)

        locked_until = user["locked_until"] if user else None
        if locked_until and time.time() < locked_until:
            remaining_minutes = max(1, int((locked_until - time.time() + 59) // 60))
            flash(f"Too many unsuccessful attempts. Try again in {remaining_minutes} minute(s).", "danger")
            return render_template("login.html", admin_only=admin_only)

        if user and check_password_hash(user["password_hash"], password):
            db.execute(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
                (user["id"],),
            )
            db.execute("DELETE FROM login_rate_limits WHERE identifier = ?", (rate_limit_key,))
            db.commit()
            session.clear()
            session.permanent = True
            session["user"] = {
                "id": user["id"],
                "username": user["username"],
                "full_name": user["full_name"],
                "role": user["role"],
            }
            session["expires_at"] = time.time() + current_app.config["PERMANENT_SESSION_LIFETIME"].total_seconds()
            if user["must_change_password"] and user["role"] in {"student", "teacher"}:
                return redirect(url_for("main.first_login_password"))
            return redirect(url_for("main.dashboard"))

        if user:
            attempts = user["failed_login_attempts"] + 1
            locked_until = None
            if attempts >= current_app.config["LOGIN_MAX_FAILED_ATTEMPTS"]:
                locked_until = time.time() + current_app.config["LOGIN_LOCKOUT_SECONDS"]
                attempts = 0
            db.execute(
                "UPDATE users SET failed_login_attempts = ?, locked_until = ? WHERE id = ?",
                (attempts, locked_until, user["id"]),
            )
            db.commit()

        if rate_limit_key:
            previous_attempts = identifier_limit["failed_attempts"] if identifier_limit else 0
            attempts = previous_attempts + 1
            identifier_locked_until = None
            if attempts >= current_app.config["LOGIN_MAX_FAILED_ATTEMPTS"]:
                identifier_locked_until = time.time() + current_app.config["LOGIN_LOCKOUT_SECONDS"]
                attempts = 0
            db.execute(
                """
                INSERT INTO login_rate_limits (identifier, failed_attempts, locked_until)
                VALUES (?, ?, ?)
                ON CONFLICT(identifier) DO UPDATE SET
                    failed_attempts = excluded.failed_attempts,
                    locked_until = excluded.locked_until
                """,
                (rate_limit_key, attempts, identifier_locked_until),
            )
            db.commit()

        flash(
            "Invalid administrator username or password."
            if admin_only
            else "Invalid Student ID, registered phone number, or password.",
            "danger",
        )

    return render_template("login.html", admin_only=admin_only)


@main.route("/login", methods=["GET", "POST"])
def login():
    return login_page(admin_only=False)


@main.route("/api/firebase-session/login", methods=["POST"])
def firebase_session_login():
    """Create the existing Flask role session only after Firebase has authenticated the user."""
    payload = request.get_json(silent=True) or {}
    try:
        id_token = str(payload.get("firebase_id_token", "")).strip()
        uid = verified_firebase_uid(id_token)
        db = get_db()
        profiles = linked_school_profiles(db, uid)
        if not profiles:
            profiles = repair_missing_firebase_profile_links(db, uid, id_token)
        if not profiles:
            try:
                admin = repair_admin_firebase_profile(db, uid, id_token)
            except FirebaseAuthProvisioningError:
                admin = None
            if admin:
                profiles = [admin]
        if not profiles:
            raise FirebaseAuthProvisioningError("No active school account is linked to this Firebase login.")
        return jsonify(message="Select the school profile to use.", profiles=[{"id": row["id"], "identifier": row["username"], "full_name": row["full_name"], "role": row["role"]} for row in profiles])
    except FirebaseAuthProvisioningError as error:
        return jsonify(error=str(error)), 401


def establish_firebase_profile_session(user):
    session.clear(); session.permanent = True
    session["user"] = {"id": user["id"], "username": user["username"], "full_name": user["full_name"], "role": user["role"]}
    session["expires_at"] = time.time() + current_app.config["PERMANENT_SESSION_LIFETIME"].total_seconds()


@main.route("/api/firebase-session/select", methods=["POST"])
def select_firebase_profile():
    payload = request.get_json(silent=True) or {}
    try:
        uid = verified_firebase_uid(str(payload.get("firebase_id_token", "")).strip())
        user_id = int(payload.get("profile_id", 0))
        db = get_db()
        user = db.execute(
            """SELECT u.* FROM users u LEFT JOIN firebase_profile_links l ON u.id = l.user_id
            WHERE (l.firebase_uid = ? OR u.firebase_uid = ?) AND u.id = ?
            AND u.role IN ('student', 'teacher', 'admin') AND u.activated = 1""",
            (uid, uid, user_id),
        ).fetchone()
        if not user:
            repair_missing_firebase_profile_links(db, uid, str(payload.get("firebase_id_token", "")).strip())
            user = db.execute(
                """SELECT u.* FROM users u LEFT JOIN firebase_profile_links l ON u.id = l.user_id
                WHERE (l.firebase_uid = ? OR u.firebase_uid = ?) AND u.id = ?
                AND u.role IN ('student', 'teacher', 'admin') AND u.activated = 1""",
                (uid, uid, user_id),
            ).fetchone()
        if not user:
            try:
                repair_admin_firebase_profile(db, uid, str(payload.get("firebase_id_token", "")).strip())
            except FirebaseAuthProvisioningError:
                pass
            user = db.execute(
                """SELECT u.* FROM users u LEFT JOIN firebase_profile_links l ON u.id = l.user_id
                WHERE (l.firebase_uid = ? OR u.firebase_uid = ?) AND u.id = ?
                AND u.role IN ('student', 'teacher', 'admin') AND u.activated = 1""",
                (uid, uid, user_id),
            ).fetchone()
        if not user:
            raise FirebaseAuthProvisioningError("This Firebase account is not linked to the selected school profile.")
        establish_firebase_profile_session(user)
        return jsonify(message="Profile selected.", redirect=url_for("main.dashboard"))
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error="Invalid or unauthorized school profile." if isinstance(error, ValueError) else str(error)), 403


@main.route("/api/mobile/fcm-token", methods=["POST"])
def register_mobile_fcm_token():
    """Bind one FCM token to one Flask-authorized school profile.

    The Firebase UID is deliberately insufficient: a parent UID may own multiple
    student profiles, so the selected profile ID is validated against
    firebase_profile_links before this token can receive private events.
    """
    payload = request.get_json(silent=True) or {}
    try:
        uid = verified_firebase_uid(str(payload.get("firebase_id_token", "")).strip())
        profile_id = int(payload.get("profile_id", 0))
        token = str(payload.get("token", "")).strip()
        if not token or len(token) > 4096:
            raise ValueError("Invalid FCM device token.")
        db = get_db()
        profile = db.execute(
            """SELECT u.id FROM firebase_profile_links l JOIN users u ON u.id = l.user_id
            WHERE l.firebase_uid = ? AND l.user_id = ? AND u.activated = 1""",
            (uid, profile_id),
        ).fetchone()
        if not profile:
            repair_missing_firebase_profile_links(db, uid, str(payload.get("firebase_id_token", "")).strip())
            profile = db.execute(
                """SELECT u.id FROM firebase_profile_links l JOIN users u ON u.id = l.user_id
                WHERE l.firebase_uid = ? AND l.user_id = ? AND u.activated = 1""",
                (uid, profile_id),
            ).fetchone()
        if not profile:
            raise FirebaseAuthProvisioningError("This Firebase login is not authorized for the selected school profile.")
        now = time.time()
        db.execute(
            """INSERT INTO fcm_device_tokens (token, firebase_uid, user_id, platform, created_at, updated_at)
            VALUES (?, ?, ?, 'android', ?, ?)
            ON CONFLICT(token) DO UPDATE SET firebase_uid = excluded.firebase_uid,
                user_id = excluded.user_id, platform = excluded.platform, updated_at = excluded.updated_at""",
            (token, uid, profile_id, now, now),
        )
        db.commit()
        return jsonify(message="Device notifications registered for the selected school profile.")
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/admin/student-master-record", methods=["POST"])
def upsert_mobile_student_master_record():
    """Create or update an unregistered student master record from the Android admin screen.

    The Firebase admin claim is verified on Flask.  Email is retained only in the private
    server-side master record, never copied to public Firestore content.
    """
    payload = request.get_json(silent=True) or {}
    try:
        verified_firebase_admin_uid(str(payload.get("firebase_id_token", "")).strip())
        student_id = str(payload.get("student_id", "")).strip()
        full_name = str(payload.get("full_name", "")).strip()
        roll_no = str(payload.get("roll_no", "")).strip()
        guardian_name = str(payload.get("guardian_name", "")).strip()
        email_value = str(payload.get("email", "")).strip()
        if not student_id or len(student_id) > 120 or any(char.isspace() for char in student_id):
            raise MobileOtpApiError("Enter a valid student ID.")
        if not full_name or len(full_name) > 160:
            raise MobileOtpApiError("Enter the student name.")
        email = normalize_email(email_value) if email_value else None

        db = get_db()
        existing = db.execute("SELECT * FROM student_master_records WHERE student_id = ?", (student_id,)).fetchone()
        if existing:
            try:
                existing = _restore_pending_master_record(
                    db, "student_master_records", "student_id", existing
                )
            except MobileOtpApiError as error:
                raise MobileOtpApiError("This student already has an activated account.", status_code=409) from error
        if existing:
            db.execute(
                """UPDATE student_master_records
                SET full_name = ?, roll_no = ?, email = ?, guardian_name = ? WHERE id = ?""",
                (full_name, roll_no or None, email, guardian_name or None, existing["id"]),
            )
            record_id = existing["id"]
        else:
            cursor = db.execute(
                """INSERT INTO student_master_records (student_id, full_name, roll_no, email, guardian_name)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                (student_id, full_name, roll_no or None, email, guardian_name or None),
            )
            record_id = cursor.fetchone()["id"]
        db.commit()
        return jsonify(message="Student master record saved.", master_record_id=record_id)
    except MobileOtpApiError as error:
        get_db().rollback()
        return jsonify(error=str(error)), error.status_code
    except FirebaseAuthProvisioningError as error:
        get_db().rollback()
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/media/upload", methods=["POST"])
def upload_mobile_media():
    """Accept an Android file and upload it through Flask to Cloudinary.

    The API secret remains on this server.  A public image is restricted to admins;
    homework/test attachments may be uploaded only by an authenticated teacher/admin.
    """
    payload = request.form
    try:
        profile = mobile_profile_from_payload(payload, "admin", "teacher")
        purpose = str(payload.get("purpose", "")).strip().lower()
        file_storage = request.files.get("file")
        public_purposes = {"gallery", "event", "facility", "announcement", "school_info"}
        private_purposes = {"homework_attachment", "test_attachment", "homework_submission"}
        if purpose not in public_purposes | private_purposes:
            raise ValueError("Unsupported upload purpose.")
        if purpose in public_purposes and profile["role"] != "admin":
            raise FirebaseAuthProvisioningError("Only an administrator may upload public school media.")
        asset = media_row_from_upload(
            get_db(),
            file_storage,
            is_public=purpose in public_purposes,
            folder=f"schoolms/{purpose}",
            created_by=profile["id"],
        )
        get_db().commit()
        # Secure URLs are returned only for public content.  Private assets must be
        # attached to an authorized content record and fetched through a signed URL.
        return jsonify(
            media_id=asset["id"],
            public_id=asset["public_id"],
            url=asset["secure_url"] if asset["delivery_type"] == "upload" else "",
            filename=asset["original_filename"],
            resource_type=asset["resource_type"],
        )
    except CloudinaryUnavailable as error:
        return jsonify(error=str(error)), 503
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403
    except Exception:
        current_app.logger.exception("Cloudinary upload failed")
        return jsonify(error="The file could not be uploaded."), 502


@main.route("/api/mobile/gallery", methods=["POST"])
def mobile_gallery():
    """Return only publicly deliverable Cloudinary gallery image metadata."""
    payload = request.get_json(silent=True) or {}
    try:
        mobile_profile_from_payload(payload, "student", "teacher", "admin")
        rows = get_db().execute(
            """SELECT id, title, caption, image_url, cloudinary_public_id, sort_order, created_at
            FROM gallery_items WHERE image_url LIKE 'https://%' ORDER BY sort_order, id"""
        ).fetchall()
        return jsonify(items=[dict(row) for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/admin/gallery", methods=["GET", "POST"])
@role_required("admin")
def admin_gallery():
    """Cloudinary-backed gallery manager for the existing web admin session."""
    db = get_db()
    if request.method == "POST":
        action = request.form.get("action", "create")
        try:
            if action == "create":
                asset = media_row_from_upload(
                    db,
                    request.files.get("image"),
                    is_public=True,
                    folder="schoolms/gallery",
                    created_by=g.user["id"],
                )
                db.execute(
                    """INSERT INTO gallery_items
                    (title, caption, image_url, cloudinary_public_id, media_asset_id, sort_order, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (
                        request.form.get("title", "").strip(), request.form.get("caption", "").strip(),
                        asset["secure_url"], asset["public_id"], asset["id"],
                        int(request.form.get("sort_order", 0) or 0), g.user["id"],
                    ),
                )
                flash("Gallery image uploaded to Cloudinary.", "success")
            elif action == "delete":
                item = db.execute(
                    """SELECT g.*, m.public_id, m.resource_type, m.delivery_type
                    FROM gallery_items g LEFT JOIN media_assets m ON m.id = g.media_asset_id WHERE g.id = ?""",
                    (int(request.form["gallery_id"]),),
                ).fetchone()
                if not item:
                    raise ValueError("Gallery image was not found.")
                db.execute("DELETE FROM gallery_items WHERE id = ?", (item["id"],))
                db.commit()
                try:
                    sync_public_gallery(db)
                except Exception:
                    current_app.logger.exception("Gallery was removed but the public Firestore snapshot needs retrying.")
                    flash("Gallery image removed. Public Firestore synchronization needs retrying.", "warning")
                if item["public_id"]:
                    try:
                        delete_media(
                            public_id=item["public_id"], resource_type=item["resource_type"],
                            delivery_type=item["delivery_type"],
                        )
                    except CloudinaryUnavailable:
                        current_app.logger.warning("Gallery record removed; Cloudinary cleanup is pending.")
                flash("Gallery image removed.", "success")
                return redirect(url_for("main.admin_gallery"))
            else:
                raise ValueError("Unsupported gallery action.")
            db.commit()
            try:
                sync_public_gallery(db)
            except Exception:
                current_app.logger.exception("Gallery was saved but the public Firestore snapshot needs retrying.")
                flash("Gallery image uploaded. Public Firestore synchronization needs retrying.", "warning")
        except CloudinaryUnavailable as error:
            db.rollback()
            flash(str(error), "danger")
        except (ValueError, KeyError) as error:
            db.rollback()
            flash(str(error), "danger")
        except Exception:
            db.rollback()
            current_app.logger.exception("Gallery update failed")
            flash("Gallery update failed. The existing gallery was not changed.", "danger")
        return redirect(url_for("main.admin_gallery"))

    rows = db.execute(
        """SELECT g.*, m.original_filename FROM gallery_items g
        LEFT JOIN media_assets m ON m.id = g.media_asset_id ORDER BY g.sort_order, g.id"""
    ).fetchall()
    return render_template("admin_gallery.html", gallery_items=rows)


def _content_payload(payload):
    target_students = payload.get("target_student_ids") or []
    attachment_ids = payload.get("attachment_media_ids") or []
    if not isinstance(target_students, list) or not isinstance(attachment_ids, list):
        raise ValueError("Student targets and attachment IDs must be lists.")
    return [int(value) for value in target_students], [int(value) for value in attachment_ids]


@main.route("/api/mobile/homework", methods=["POST"])
def create_mobile_homework():
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        target_students, attachment_ids = _content_payload(payload)
        class_id = int(payload.get("class_id") or 0) or None
        if not class_id:
            class_id = mobile_class_by_name(db, payload.get("class_name"))["id"]
        subject_id = int(payload.get("subject_id") or 0)
        if not subject_id:
            subject_id = mobile_subject_for_class(db, class_id, payload.get("subject_name"))["id"]
        target_mode = str(payload.get("target_mode", "class")).strip().lower()
        title = str(payload.get("title", "")).strip()
        description = str(payload.get("description", "")).strip()
        due_date = str(payload.get("due_date", "")).strip()
        if not (title and description and due_date and subject_id and class_id):
            raise ValueError("Title, description, class, subject, and due date are required.")
        validate_content_target(
            db, actor=actor, class_id=class_id, subject_id=subject_id,
            target_mode=target_mode, target_student_ids=target_students,
        )
        cursor = db.execute(
            """INSERT INTO homework
            (class_id, subject_id, teacher_id, title, description, due_date, section, target_mode,
             instructions, external_link, assigned_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            (
                class_id, subject_id, actor["id"], title, description, due_date,
                str(payload.get("section", "")).strip(), target_mode,
                str(payload.get("instructions", "")).strip(), str(payload.get("external_link", "")).strip(),
                str(payload.get("assigned_date", "")).strip() or date.today().isoformat(),
            ),
        )
        homework_id = cursor.fetchone()["id"]
        if target_mode == "students":
            db.executemany(
                "INSERT INTO homework_student_targets (homework_id, student_id) VALUES (?, ?)",
                ((homework_id, student_id) for student_id in target_students),
            )
        save_content_attachment_links(
            db, owner_type="homework", owner_id=homework_id,
            asset_ids=attachment_ids, uploaded_by=actor["id"],
        )
        db.commit()
        recipients = target_student_ids(
            db, target_mode=target_mode, class_id=class_id, content_id=homework_id,
            target_table="homework_student_targets", target_column="homework_id",
        )
        subject = db.execute("SELECT name FROM subjects WHERE id = ?", (subject_id,)).fetchone()
        notify_student_profiles(
            db, recipients, title="New homework", body=f"New {subject['name'] if subject else 'school'} homework has been posted.",
            event_type="homework", destination="homework", content_id=homework_id,
        )
        return jsonify(id=homework_id, message="Homework created."), 201
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/staff/subjects", methods=["POST"])
def mobile_staff_subjects():
    """Return server-authoritative subjects for a teacher/admin class."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        school_class = mobile_class_by_name(db, payload.get("class_name"))
        require_mobile_staff_class_access(db, actor, school_class["id"])
        rows = db.execute(
            """SELECT s.id, s.name FROM class_subjects cs JOIN subjects s ON s.id = cs.subject_id
            WHERE cs.class_id = ? ORDER BY s.name""",
            (school_class["id"],),
        ).fetchall()
        return jsonify(items=[dict(row) for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/staff/classes", methods=["POST"])
def mobile_staff_classes():
    """Return the exact server-owned class labels a staff profile may manage."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        query = "SELECT id, name || ' - ' || section AS class_name FROM classes"
        params = ()
        if actor["role"] == "teacher":
            query += " WHERE teacher_id = ?"
            params = (actor["id"],)
        query += " ORDER BY name, section"
        return jsonify(items=[dict(row) for row in db.execute(query, params).fetchall()])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/staff/class-students", methods=["POST"])
def mobile_staff_class_students():
    """Return enrolled students only after the staff member is authorized for the class."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        school_class = mobile_class_by_name(db, payload.get("class_name"))
        require_mobile_staff_class_access(db, actor, school_class["id"])
        rows = db.execute(
            """SELECT u.username, u.full_name, COALESCE(sp.roll_no, '') AS roll_no
            FROM users u
            JOIN enrollments e ON e.student_id = u.id
            LEFT JOIN student_profiles sp ON sp.user_id = u.id
            WHERE e.class_id = ? AND u.role = 'student' AND u.activated = 1
            ORDER BY sp.roll_no, u.full_name, u.username""",
            (school_class["id"],),
        ).fetchall()
        return jsonify(items=[dict(row) for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/marks", methods=["POST"])
def save_mobile_marks():
    """Save marks server-side and notify only the affected student profile."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        school_class = mobile_class_by_name(db, payload.get("class_name"))
        require_mobile_staff_class_access(db, actor, school_class["id"])
        subject = mobile_subject_for_class(db, school_class["id"], payload.get("subject_name"))
        username = str(payload.get("student_username", "")).strip()
        student = db.execute(
            """SELECT u.id FROM users u JOIN enrollments e ON e.student_id = u.id
            WHERE LOWER(u.username) = LOWER(?) AND u.role = 'student' AND e.class_id = ?""",
            (username, school_class["id"]),
        ).fetchone()
        assessment = str(payload.get("assessment", "")).strip()
        score, total = int(payload.get("score")), int(payload.get("out_of"))
        if not student or not assessment or total <= 0 or score < 0 or score > total:
            raise ValueError("Enter a valid enrolled student, assessment, and marks.")
        grade = grade_from_score(score, total)
        existing = db.execute(
            """SELECT id FROM marks WHERE student_id = ? AND class_id = ? AND subject_id = ? AND exam_name = ?
            ORDER BY id DESC LIMIT 1""",
            (student["id"], school_class["id"], subject["id"], assessment),
        ).fetchone()
        if existing:
            db.execute(
                "UPDATE marks SET total_marks = ?, obtained_marks = ?, grade = ?, entered_by = ? WHERE id = ?",
                (total, score, grade, actor["id"], existing["id"]),
            )
            mark_id = existing["id"]
        else:
            cursor = db.execute(
                """INSERT INTO marks (student_id, class_id, subject_id, exam_name, total_marks, obtained_marks, grade, entered_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                (student["id"], school_class["id"], subject["id"], assessment, total, score, grade, actor["id"]),
            )
            mark_id = cursor.fetchone()["id"]
        db.commit()
        notify_student_profiles(
            db, [student["id"]], title="New marks published", body="New marks have been published.",
            event_type="marks", destination="marks", content_id=mark_id,
        )
        return jsonify(id=mark_id, grade=grade, message="Marks saved.")
    except (TypeError, ValueError, FirebaseAuthProvisioningError) as error:
        get_db().rollback()
        return jsonify(error=str(error) or "Enter valid marks."), 403


@main.route("/api/mobile/attendance", methods=["POST"])
def save_mobile_attendance():
    """Save a class attendance batch in the private server database."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        school_class = mobile_class_by_name(db, payload.get("class_name"))
        require_mobile_staff_class_access(db, actor, school_class["id"])
        subject = mobile_subject_for_class(db, school_class["id"], payload.get("subject_name"))
        attendance_date = str(payload.get("attendance_date") or date.today().isoformat()).strip()
        date.fromisoformat(attendance_date)
        requested_marks = payload.get("marks")
        if not isinstance(requested_marks, dict) or not requested_marks:
            raise ValueError("Choose at least one student attendance record.")
        enrolled = {
            row["username"]: row["id"]
            for row in db.execute(
                """SELECT u.id, LOWER(u.username) AS username FROM users u JOIN enrollments e ON e.student_id = u.id
                WHERE e.class_id = ? AND u.role = 'student'""", (school_class["id"],)
            ).fetchall()
        }
        rows = []
        for raw_username, present in requested_marks.items():
            username = str(raw_username).strip().lower()
            if username not in enrolled or not isinstance(present, bool):
                raise ValueError("Attendance may be saved only for enrolled students.")
            rows.append((enrolled[username], school_class["id"], subject["id"], attendance_date,
                         "present" if present else "absent", actor["id"]))
        db.executemany(
            """INSERT INTO attendance (student_id, class_id, subject_id, attendance_date, status, marked_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(student_id, class_id, subject_id, attendance_date) DO UPDATE SET
                status = excluded.status, marked_by = excluded.marked_by""",
            rows,
        )
        db.commit()
        return jsonify(saved_count=len(rows), message="Attendance saved.")
    except (TypeError, ValueError, FirebaseAuthProvisioningError) as error:
        get_db().rollback()
        return jsonify(error=str(error) or "Attendance could not be saved."), 403


@main.route("/api/mobile/tests", methods=["POST"])
def create_mobile_test():
    """Create a server-authorized test/assignment and notify only recipients."""
    payload = request.get_json(silent=True) or {}
    try:
        actor = mobile_profile_from_payload(payload, "admin", "teacher")
        db = get_db()
        target_students, attachment_ids = _content_payload(payload)
        class_id = int(payload.get("class_id") or 0) or None
        subject_id = int(payload.get("subject_id") or 0)
        target_mode = str(payload.get("target_mode", "class")).strip().lower()
        title = str(payload.get("title", "")).strip()
        test_date = str(payload.get("test_date", "")).strip()
        if not (title and test_date and subject_id and class_id):
            raise ValueError("Title, class, subject, and test date are required.")
        validate_content_target(
            db, actor=actor, class_id=class_id, subject_id=subject_id,
            target_mode=target_mode, target_student_ids=target_students,
        )
        cursor = db.execute(
            """INSERT INTO scheduled_tests
            (class_id, section, subject_id, teacher_id, target_mode, title, syllabus, instructions,
             test_date, start_time, end_time, maximum_marks, external_link, result_published)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            (
                class_id, str(payload.get("section", "")).strip(), subject_id, actor["id"], target_mode,
                title, str(payload.get("syllabus", "")).strip(), str(payload.get("instructions", "")).strip(),
                test_date, str(payload.get("start_time", "")).strip(), str(payload.get("end_time", "")).strip(),
                int(payload.get("maximum_marks") or 0) or None, str(payload.get("external_link", "")).strip(),
                1 if payload.get("result_published") else 0,
            ),
        )
        test_id = cursor.fetchone()["id"]
        if target_mode == "students":
            db.executemany(
                "INSERT INTO scheduled_test_student_targets (test_id, student_id) VALUES (?, ?)",
                ((test_id, student_id) for student_id in target_students),
            )
        save_content_attachment_links(
            db, owner_type="test", owner_id=test_id,
            asset_ids=attachment_ids, uploaded_by=actor["id"],
        )
        db.commit()
        recipients = target_student_ids(
            db, target_mode=target_mode, class_id=class_id, content_id=test_id,
            target_table="scheduled_test_student_targets", target_column="test_id",
        )
        subject = db.execute("SELECT name FROM subjects WHERE id = ?", (subject_id,)).fetchone()
        notify_student_profiles(
            db, recipients, title="New test scheduled", body=f"New {subject['name'] if subject else 'school'} test has been scheduled.",
            event_type="test", destination="tests", content_id=test_id,
        )
        return jsonify(id=test_id, message="Test scheduled."), 201
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/tests/list", methods=["POST"])
def list_mobile_tests():
    payload = request.get_json(silent=True) or {}
    try:
        profile = mobile_profile_from_payload(payload, "student", "teacher", "admin")
        db = get_db()
        query = """SELECT t.*, s.name AS subject_name, u.full_name AS teacher_name,
            c.name || ' - ' || c.section AS class_name
            FROM scheduled_tests t JOIN subjects s ON s.id = t.subject_id JOIN users u ON u.id = t.teacher_id
            LEFT JOIN classes c ON c.id = t.class_id"""
        params = ()
        if profile["role"] == "teacher":
            query += " WHERE t.teacher_id = ?"
            params = (profile["id"],)
        query += " ORDER BY t.test_date ASC, t.id DESC"
        rows = db.execute(query, params).fetchall()
        if profile["role"] == "student":
            rows = [row for row in rows if content_visible_to_student(db, content_type="test", content_id=row["id"], student_id=profile["id"])]
        return jsonify(items=[{**dict(row), "attachments": private_attachments_for(db, owner_type="test", owner_id=row["id"])} for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/marks/list", methods=["POST"])
def list_mobile_marks():
    """Return marks only for the Firebase-authorized selected student profile."""
    payload = request.get_json(silent=True) or {}
    try:
        profile = mobile_profile_from_payload(payload, "student")
        rows = get_db().execute(
            """SELECT m.id, m.exam_name, m.total_marks, m.obtained_marks, m.grade,
                      s.name AS subject_name, m.class_id
               FROM marks m JOIN subjects s ON s.id = m.subject_id
               WHERE m.student_id = ? ORDER BY m.id DESC""",
            (profile["id"],),
        ).fetchall()
        return jsonify(items=[dict(row) for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/attendance/list", methods=["POST"])
def list_mobile_attendance():
    """Return attendance only for the Firebase-authorized selected student profile."""
    payload = request.get_json(silent=True) or {}
    try:
        profile = mobile_profile_from_payload(payload, "student")
        rows = get_db().execute(
            """SELECT a.id, a.attendance_date, a.status, s.name AS subject_name,
                      c.name || ' - ' || c.section AS class_name
               FROM attendance a
               JOIN subjects s ON s.id = a.subject_id
               JOIN classes c ON c.id = a.class_id
               WHERE a.student_id = ? ORDER BY a.attendance_date DESC, a.id DESC""",
            (profile["id"],),
        ).fetchall()
        return jsonify(items=[dict(row) for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/attachments/<int:attachment_id>/download", methods=["POST"])
def mobile_attachment_download(attachment_id):
    """Return a five-minute signed Cloudinary download only after server authorization."""
    payload = request.get_json(silent=True) or {}
    try:
        profile = mobile_profile_from_payload(payload, "student", "teacher", "admin")
        db = get_db()
        attachment = db.execute(
            """SELECT ca.*, ma.public_id, ma.resource_type, ma.file_format, ma.delivery_type,
            h.teacher_id AS homework_teacher_id, t.teacher_id AS test_teacher_id
            FROM content_attachments ca JOIN media_assets ma ON ma.id = ca.media_asset_id
            LEFT JOIN homework h ON ca.owner_type = 'homework' AND h.id = ca.owner_id
            LEFT JOIN scheduled_tests t ON ca.owner_type = 'test' AND t.id = ca.owner_id
            WHERE ca.id = ?""",
            (attachment_id,),
        ).fetchone()
        if not attachment:
            raise ValueError("Attachment not found.")
        if profile["role"] == "student" and not content_visible_to_student(
            db, content_type=attachment["owner_type"], content_id=attachment["owner_id"], student_id=profile["id"]
        ):
            raise FirebaseAuthProvisioningError("This attachment is not assigned to the selected student profile.")
        if profile["role"] == "teacher" and profile["id"] not in {attachment["homework_teacher_id"], attachment["test_teacher_id"]}:
            raise FirebaseAuthProvisioningError("This attachment is not assigned to this teacher.")
        if attachment["delivery_type"] != "authenticated":
            raise ValueError("Attachment delivery is not private.")
        return jsonify(url=private_download_url(
            public_id=attachment["public_id"], resource_type=attachment["resource_type"],
            file_format=attachment["file_format"],
        ), filename=attachment["display_name"], expires_in=300)
    except CloudinaryUnavailable as error:
        return jsonify(error=str(error)), 503
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/api/mobile/homework/list", methods=["POST"])
def list_mobile_homework():
    payload = request.get_json(silent=True) or {}
    try:
        profile = mobile_profile_from_payload(payload, "student", "teacher", "admin")
        db = get_db()
        if profile["role"] == "student":
            rows = db.execute(
                """SELECT h.*, s.name AS subject_name, u.full_name AS teacher_name,
                    c.name || ' - ' || c.section AS class_name
                FROM homework h JOIN subjects s ON s.id = h.subject_id JOIN users u ON u.id = h.teacher_id
                LEFT JOIN classes c ON c.id = h.class_id ORDER BY h.due_date ASC, h.id DESC"""
            ).fetchall()
            rows = [row for row in rows if content_visible_to_student(db, content_type="homework", content_id=row["id"], student_id=profile["id"])]
        elif profile["role"] == "teacher":
            rows = db.execute(
                """SELECT h.*, s.name AS subject_name, u.full_name AS teacher_name,
                    c.name || ' - ' || c.section AS class_name
                FROM homework h JOIN subjects s ON s.id = h.subject_id JOIN users u ON u.id = h.teacher_id
                LEFT JOIN classes c ON c.id = h.class_id WHERE h.teacher_id = ? ORDER BY h.due_date ASC, h.id DESC""",
                (profile["id"],),
            ).fetchall()
        else:
            rows = db.execute(
                """SELECT h.*, s.name AS subject_name, u.full_name AS teacher_name,
                    c.name || ' - ' || c.section AS class_name
                FROM homework h JOIN subjects s ON s.id = h.subject_id JOIN users u ON u.id = h.teacher_id
                LEFT JOIN classes c ON c.id = h.class_id ORDER BY h.due_date ASC, h.id DESC"""
            ).fetchall()
        return jsonify(items=[{**dict(row), "attachments": private_attachments_for(db, owner_type="homework", owner_id=row["id"])} for row in rows])
    except (ValueError, FirebaseAuthProvisioningError) as error:
        return jsonify(error=str(error)), 403


@main.route("/admin/login", methods=["GET", "POST"])
def admin_login():
    return login_page(admin_only=True)


@main.route("/first-login-password", methods=["GET", "POST"])
@role_required("student", "teacher")
def first_login_password():
    db = get_db()
    user = db.execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    if not user or not user["must_change_password"]:
        return redirect(url_for("main.dashboard"))
    if request.method == "POST":
        current_password = request.form.get("current_password", "")
        new_password = request.form.get("new_password", "")
        confirm_password = request.form.get("confirm_password", "")
        if not check_password_hash(user["password_hash"], current_password):
            flash("Current temporary password is incorrect.", "danger")
        elif len(new_password) < 8 or new_password != confirm_password:
            flash("New passwords must match and be at least 8 characters.", "danger")
        else:
            db.execute("UPDATE users SET password_hash = ?, must_change_password = 0 WHERE id = ?", (generate_password_hash(new_password), user["id"]))
            db.commit()
            flash("Password updated.", "success")
            return redirect(url_for("main.dashboard"))
    return render_template("first_login_password.html")


@main.route("/register", methods=["GET", "POST"])
def register_student():
    return render_template("register.html", firebase_web_config=firebase_web_config())


@main.route("/forgot-password", methods=["GET", "POST"])
def forgot_password():
    return render_template("forgot_password.html", firebase_web_config=firebase_web_config())


def firebase_web_config():
    return {
        "apiKey": current_app.config["FIREBASE_WEB_API_KEY"],
        "authDomain": current_app.config["FIREBASE_WEB_AUTH_DOMAIN"],
        "projectId": current_app.config["FIREBASE_WEB_PROJECT_ID"],
        "appId": current_app.config["FIREBASE_WEB_APP_ID"],
    }


def needs_verified_email(user):
    """Legacy/synthetic identities remain usable, but are invited to attach a verified real email."""
    email = str(user["email"] or "").strip().lower()
    return not user["email_verified_at"] or not email or email.endswith("@cns-paunta.app")


@main.app_context_processor
def inject_firebase_web_config():
    email_migration_needed = False
    if g.get("user") and g.user.get("role") in EMAIL_REGISTRATION_ROLES:
        row = get_db().execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
        email_migration_needed = bool(row and needs_verified_email(row))
    return {"firebase_web_config": firebase_web_config(), "email_migration_needed": email_migration_needed}


@main.route("/add-verify-email")
@role_required("student", "teacher")
def add_verify_email():
    user = get_db().execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    if not user or not needs_verified_email(user):
        return redirect(url_for("main.dashboard"))
    return render_template("add_verify_email.html", firebase_web_config=firebase_web_config())


@main.route("/api/existing-email-migration/start", methods=["POST"])
@role_required("student", "teacher")
def start_existing_email_migration():
    payload = request.get_json(silent=True) or {}
    db = get_db()
    user = db.execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    try:
        if not user or not check_password_hash(user["password_hash"], str(payload.get("current_password", ""))):
            raise MobileOtpApiError("Current account password is incorrect.")
        email = normalize_email(payload.get("email"))
        now = time.time()
        db.execute("DELETE FROM existing_email_migration_sessions WHERE expires_at <= ?", (now,))
        db.execute("DELETE FROM existing_email_migration_sessions WHERE user_id = ?", (user["id"],))
        firebase_uid, created = create_pending_email_account(email, str(payload.get("current_password", "")), user["full_name"])
        already_verified = False
        if not created:
            owner_email, already_verified = firebase_identity_email(firebase_uid, str(payload.get("firebase_id_token", "")).strip())
            if owner_email != email:
                raise FirebaseAuthProvisioningError("The Firebase sign-in email does not match this migration.")
        token = secrets.token_urlsafe(32)
        db.execute(
            """INSERT INTO existing_email_migration_sessions
            (token_hash, user_id, email, firebase_uid, email_already_verified, sent_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (mobile_otp_token_hash(token), user["id"], email, firebase_uid, int(already_verified), now,
             now + current_app.config["EMAIL_REGISTRATION_SESSION_TTL_SECONDS"]),
        )
        db.commit()
        return jsonify(registration_token=token, email=email, verification_required=not already_verified)
    except (MobileOtpApiError, FirebaseAuthProvisioningError) as error:
        db.rollback()
        return jsonify(error=str(error)), 400


@main.route("/api/existing-email-migration/complete", methods=["POST"])
@role_required("student", "teacher")
def complete_existing_email_migration():
    payload = request.get_json(silent=True) or {}
    db = get_db()
    row = db.execute(
        "SELECT * FROM existing_email_migration_sessions WHERE token_hash = ? AND user_id = ?",
        (mobile_otp_token_hash(str(payload.get("registration_token", "")).strip()), g.user["id"]),
    ).fetchone()
    if not row or row["expires_at"] <= time.time():
        return jsonify(error="Email migration has expired. Start again."), 400
    try:
        verified_email = verify_pending_email(row["firebase_uid"], str(payload.get("firebase_id_token", "")).strip())
        if verified_email != row["email"]:
            raise FirebaseAuthProvisioningError("The verified Firebase email does not match this migration.")
        user = db.execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
        db.execute(
            "INSERT INTO firebase_profile_links (firebase_uid, user_id) VALUES (?, ?) "
            "ON CONFLICT (firebase_uid, user_id) DO UPDATE SET user_id = EXCLUDED.user_id",
            (row["firebase_uid"], user["id"]),
        )
        db.execute("UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?", (verified_email, time.time(), user["id"]))
        if user["role"] == "student":
            db.execute("UPDATE student_profiles SET email = ? WHERE user_id = ?", (verified_email, user["id"]))
        finalize_email_account(row["firebase_uid"], user["role"], user["id"])
        db.execute("DELETE FROM existing_email_migration_sessions WHERE token_hash = ?", (row["token_hash"],))
        db.commit()
        return jsonify(message="Email verified and linked to your school profile.", redirect=url_for("main.dashboard"))
    except FirebaseAuthProvisioningError as error:
        db.rollback()
        return jsonify(error=str(error)), 400


@main.route("/api/email-registration/start", methods=["POST"])
def start_email_registration():
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify(error="A JSON registration request is required."), 400
    password = str(payload.get("password", ""))
    if len(password) < 8 or password != str(payload.get("confirm_password", "")):
        return jsonify(error="Passwords must match and be at least 8 characters."), 400
    try:
        role, _table, _id_column, master = email_registration_master_record(payload)
        email = normalize_email(payload.get("email"))
        db = get_db()
        now = time.time()
        db.execute("DELETE FROM firebase_registration_sessions WHERE expires_at <= ?", (now,))
        if db.execute("SELECT 1 FROM firebase_registration_sessions WHERE role = ? AND master_record_id = ?", (role, master["id"])).fetchone():
            raise MobileOtpApiError("Registration is already in progress for this school ID. Complete it first.")
        firebase_uid, created = create_pending_email_account(email, password, master["full_name"])
        already_verified = False
        if not created:
            # A shared email must prove ownership of its existing Firebase identity. If it has
            # not been verified yet, Firebase's normal link is sent again. Do not make a
            # new-email registration first wait for a Firebase sign-in that must fail.
            firebase_id_token = str(payload.get("firebase_id_token", "")).strip()
            if not firebase_id_token:
                raise MobileOtpApiError(
                    "This email already has a Firebase account. Confirm its password to link this school profile.",
                    status_code=409,
                )
            owner_email, already_verified = firebase_identity_email(firebase_uid, firebase_id_token)
            if owner_email != email:
                raise FirebaseAuthProvisioningError("The Firebase sign-in email does not match this registration.")
        token = secrets.token_urlsafe(32)
        db.execute(
            """INSERT INTO firebase_registration_sessions
            (token_hash, role, master_record_id, email, firebase_uid, email_already_verified, sent_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (mobile_otp_token_hash(token), role, master["id"], email, firebase_uid, int(already_verified), now,
             now + current_app.config["EMAIL_REGISTRATION_SESSION_TTL_SECONDS"]),
        )
        db.commit()
        return jsonify(message="Verification email ready to send.", registration_token=token, email=email, verification_required=not already_verified)
    except MobileOtpApiError as error:
        get_db().rollback()
        return jsonify(error=str(error)), error.status_code
    except FirebaseAuthProvisioningError as error:
        get_db().rollback()
        return jsonify(error=str(error)), 400


@main.route("/api/email-registration/resend", methods=["POST"])
def resend_email_registration():
    payload = request.get_json(silent=True) or {}
    token = str(payload.get("registration_token", "")).strip()
    row = get_db().execute("SELECT * FROM firebase_registration_sessions WHERE token_hash = ?", (mobile_otp_token_hash(token),)).fetchone()
    if not row or row["expires_at"] <= time.time():
        return jsonify(error="Registration verification has expired. Start registration again."), 400
    remaining = int(current_app.config["OTP_RESEND_COOLDOWN_SECONDS"] - (time.time() - row["sent_at"]))
    if remaining > 0:
        return jsonify(error=f"Please wait {remaining} seconds before requesting another email."), 429
    if row["email_already_verified"]:
        return jsonify(message="This email is already verified. Continue to link the school profile.", email=row["email"])
    get_db().execute("UPDATE firebase_registration_sessions SET sent_at = ? WHERE token_hash = ?", (time.time(), row["token_hash"]))
    get_db().commit()
    return jsonify(message="You may send the Firebase verification email again.", email=row["email"], cooldown=current_app.config["OTP_RESEND_COOLDOWN_SECONDS"])


@main.route("/api/email-registration/complete", methods=["POST"])
def complete_email_registration():
    payload = request.get_json(silent=True) or {}
    token = str(payload.get("registration_token", "")).strip()
    id_token = str(payload.get("firebase_id_token", "")).strip()
    db = get_db()
    row = db.execute("SELECT * FROM firebase_registration_sessions WHERE token_hash = ?", (mobile_otp_token_hash(token),)).fetchone()
    if not row or row["expires_at"] <= time.time() or not id_token:
        return jsonify(error="Registration verification has expired. Start registration again."), 400
    try:
        verified_email = verify_pending_email(row["firebase_uid"], id_token)
        if verified_email != row["email"]:
            raise FirebaseAuthProvisioningError("The verified Firebase email does not match this registration.")
        table = "student_master_records" if row["role"] == "student" else "teacher_master_records"
        master = db.execute(f"SELECT * FROM {table} WHERE id = ?", (row["master_record_id"],)).fetchone()
        if not master:
            raise MobileOtpApiError("Student/Teacher ID not found in school records.")
        user = create_email_login_from_master(db, row["role"], master, verified_email, row["firebase_uid"])
        finalize_email_account(row["firebase_uid"], row["role"], user["id"])
        db.execute("DELETE FROM firebase_registration_sessions WHERE token_hash = ?", (row["token_hash"],))
        db.commit()
        return jsonify(message="Email verified successfully. Your account is ready.")
    except (MobileOtpApiError, FirebaseAuthProvisioningError, *integrity_errors()) as error:
        db.rollback()
        return jsonify(error=str(error) if not isinstance(error, integrity_errors()) else "This email or school ID is already registered."), 400


@main.route("/api/password-reset/request", methods=["POST"])
def request_email_password_reset():
    """Validate reset delivery for both activated and pending email registrations.

    A student master record exists before Firebase email activation.  Its registered
    email may therefore need a Firebase password reset even though a completed
    ``users`` profile does not exist yet.  The exact school ID + private master
    email must match before the client is told to ask Firebase to deliver a link.
    """
    payload = request.get_json(silent=True) or {}
    try:
        identifier = str(payload.get("identifier", "")).strip()
        role = str(payload.get("role", "")).strip().lower()
        email = normalize_email(payload.get("email"))
        if role not in EMAIL_REGISTRATION_ROLES or not identifier:
            raise MobileOtpApiError("Enter your Student ID or Teacher ID and select the correct role.")
        db = get_db()
        user = db.execute(
            "SELECT * FROM users WHERE username = ? AND role = ? AND email = ? AND activated = 1",
            (identifier, role, email),
        ).fetchone()
        if user:
            linked_identity = (user["firebase_uid"] or "").strip() or db.execute(
                "SELECT 1 FROM firebase_profile_links WHERE user_id = ?", (user["id"],)
            ).fetchone()
            if linked_identity:
                return jsonify(message="Password reset email ready to send.", email=email, pending_activation=False)

        table = "student_master_records" if role == "student" else "teacher_master_records"
        id_column = "student_id" if role == "student" else "teacher_id"
        master = db.execute(
            f"SELECT * FROM {table} WHERE {id_column} = ? AND LOWER(COALESCE(email, '')) = ?",
            (identifier, email),
        ).fetchone()
        if master and not _master_has_activated_login(db, master, id_column):
            return jsonify(
                message="Pending-account password reset email ready to send.",
                email=email,
                pending_activation=True,
            )

        raise MobileOtpApiError("No school account or pending activation matches these details.")
    except MobileOtpApiError as error:
        return jsonify(error=str(error)), 400


@main.route("/otp/send", methods=["POST"])
def send_twofactor_otp():
    payload = request.get_json(silent=True) or request.form
    purpose = payload.get("purpose", "")
    if purpose not in OTP_PURPOSES:
        return jsonify(error="Invalid OTP request."), 400

    try:
        phone = normalize_indian_phone(payload.get("phone", ""))
        if purpose == "password_reset":
            account_exists = get_db().execute(
                "SELECT 1 FROM users WHERE phone = ? AND activated = 1", (phone,)
            ).fetchone()
            if not account_exists:
                raise TwoFactorOtpError("No account is registered with this mobile number.")
        if purpose == "activation":
            try:
                self_registration_master_record(
                    {"identifier": payload.get("username", ""), "role": payload.get("role", ""), "phone": phone}
                )
            except MobileOtpApiError as error:
                raise TwoFactorOtpError(str(error)) from error

        previous = session.get(otp_session_key(purpose), {})
        cooldown = current_app.config["OTP_RESEND_COOLDOWN_SECONDS"]
        elapsed = time.time() - previous.get("sent_at", 0)
        if previous and elapsed < cooldown:
            remaining = max(1, int(cooldown - elapsed))
            return jsonify(error=f"Please wait {remaining} seconds before requesting another OTP."), 429

        otp_state = {
            "phone": phone,
            "session_id": send_otp(phone),
            "sent_at": time.time(),
        }
        if purpose == "activation":
            otp_state["identifier"] = payload.get("username", "").strip()
            otp_state["role"] = payload.get("role", "").strip().lower()
        session[otp_session_key(purpose)] = otp_state
        return jsonify(message="OTP sent successfully.", cooldown=cooldown)
    except TwoFactorOtpError as error:
        return jsonify(error=str(error)), 400


@main.route("/api/mobile/otp/send", methods=["POST"])
def send_mobile_otp():
    """Native-app OTP gateway; only first activation accepts a phone to verify."""
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify(error="A JSON OTP request is required."), 400
    purpose = str(payload.get("purpose", "")).strip()
    if purpose not in OTP_PURPOSES:
        return jsonify(error="Invalid OTP request."), 400
    if purpose == "activation":
        return send_self_registration_otp(payload)

    try:
        user, phone = mobile_otp_account(payload)
        db = get_db()
        now = time.time()
        db.execute("DELETE FROM mobile_otp_sessions WHERE expires_at <= ?", (now,))
        previous = db.execute(
            "SELECT sent_at FROM mobile_otp_sessions WHERE user_id = ? AND purpose = ? "
            "ORDER BY sent_at DESC LIMIT 1",
            (user["id"], purpose),
        ).fetchone()
        cooldown = current_app.config["OTP_RESEND_COOLDOWN_SECONDS"]
        if previous:
            remaining = int(cooldown - (now - previous["sent_at"]))
            if remaining > 0:
                db.commit()
                raise MobileOtpApiError(
                    f"Please wait {remaining} seconds before requesting another OTP.",
                    status_code=429,
                    retry_after=remaining,
                )

        # The 2Factor helper is the only component that sends SMS.
        provider_session_id = send_otp(phone)
        session_token = secrets.token_urlsafe(32)
        db.execute(
            "DELETE FROM mobile_otp_sessions WHERE user_id = ? AND purpose = ?",
            (user["id"], purpose),
        )
        db.execute(
            """
            INSERT INTO mobile_otp_sessions
            (token_hash, user_id, purpose, phone, provider_session_id, sent_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                mobile_otp_token_hash(session_token),
                user["id"],
                purpose,
                phone,
                provider_session_id,
                now,
                now + current_app.config["MOBILE_OTP_SESSION_TTL_SECONDS"],
            ),
        )
        db.commit()
        return jsonify(
            message="OTP sent to the mobile number registered with your school.",
            otp_session_token=session_token,
            cooldown=cooldown,
            expires_in=current_app.config["MOBILE_OTP_SESSION_TTL_SECONDS"],
        )
    except MobileOtpApiError as error:
        return mobile_otp_error_response(error)
    except TwoFactorOtpError as error:
        return jsonify(error=str(error)), 400


@main.route("/api/mobile/otp/verify", methods=["POST"])
def verify_mobile_otp():
    """Verify with 2Factor and complete the requested server-side account action atomically."""
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify(error="A JSON OTP request is required."), 400
    purpose = str(payload.get("purpose", "")).strip()
    token = str(payload.get("otp_session_token", "")).strip()
    otp = str(payload.get("otp", "")).strip()
    password = str(payload.get("new_password", ""))
    confirm_password = str(payload.get("confirm_password", ""))
    if purpose not in OTP_PURPOSES or not token:
        return jsonify(error="OTP session is invalid or has expired. Send a new OTP."), 400
    if len(password) < 8 or password != confirm_password:
        return jsonify(error="New passwords must match and be at least 8 characters."), 400
    if purpose == "activation":
        return complete_self_registration_otp(payload, password, confirm_password)

    db = get_db()
    now = time.time()
    row = db.execute(
        """
        SELECT s.*, u.id AS id, u.username, u.full_name, u.role, u.activated, u.firebase_uid
        FROM mobile_otp_sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = ? AND s.purpose = ?
        """,
        (mobile_otp_token_hash(token), purpose),
    ).fetchone()
    if not row or row["expires_at"] <= now:
        if row:
            db.execute("DELETE FROM mobile_otp_sessions WHERE token_hash = ?", (row["token_hash"],))
            db.commit()
        return jsonify(error="OTP session has expired. Send a new OTP."), 400
    if purpose == "activation" and not row["phone"]:
        # Tokens issued before mobile-number binding was introduced cannot activate an account.
        db.execute("DELETE FROM mobile_otp_sessions WHERE token_hash = ?", (row["token_hash"],))
        db.commit()
        return jsonify(error="OTP session is outdated. Send a new OTP."), 400

    try:
        verify_otp(row["provider_session_id"], otp)
    except TwoFactorOtpError as error:
        attempts = row["verify_attempts"] + 1
        if attempts >= current_app.config["MOBILE_OTP_MAX_VERIFY_ATTEMPTS"]:
            db.execute("DELETE FROM mobile_otp_sessions WHERE token_hash = ?", (row["token_hash"],))
            message = "Too many invalid OTP attempts. Send a new OTP."
        else:
            db.execute(
                "UPDATE mobile_otp_sessions SET verify_attempts = ? WHERE token_hash = ?",
                (attempts, row["token_hash"]),
            )
            message = str(error)
        db.commit()
        return jsonify(error=message), 400

    if row["role"] not in MOBILE_OTP_ROLES:
        return jsonify(error="OTP is available only for student and teacher accounts."), 403
    try:
        firebase_uid = provision_firebase_password(
            row, password, allow_create=(purpose == "activation")
        )
        link_firebase_uid(db, row, firebase_uid)
    except FirebaseAuthProvisioningError as error:
        db.rollback()
        return jsonify(error=str(error)), 503

    if purpose == "activation":
        updated = db.execute(
            """
            UPDATE users SET phone = ?, activated = 1
            WHERE id = ? AND activated = 0 AND (phone IS NULL OR TRIM(phone) = '')
            """,
            (row["phone"], row["user_id"]),
        )
        success_message = "Account activated. You can now sign in."
    else:
        updated = db.execute(
            "UPDATE users SET firebase_uid = firebase_uid WHERE id = ? AND activated = 1",
            (row["user_id"],),
        )
        success_message = "Password updated. You can now sign in."
    if not updated.rowcount:
        return jsonify(error="This account is no longer eligible for this OTP action."), 400

    db.execute("DELETE FROM mobile_otp_sessions WHERE token_hash = ?", (row["token_hash"],))
    db.commit()
    return jsonify(message=success_message)


@main.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("main.login"))


@main.route("/uploads/<path:filename>")
@role_required("admin", "teacher", "student")
def uploaded_file(filename):
    safe_name = os.path.basename(filename)
    if safe_name != filename:
        abort(404)
    return send_from_directory(current_app.config["UPLOAD_FOLDER"], safe_name, as_attachment=True)


@main.route("/facilities")
def facilities():
    rows = get_db().execute("SELECT * FROM facilities ORDER BY id ASC").fetchall()
    return render_template("facilities.html", rows=rows)


@main.route("/events")
def events():
    rows = get_db().execute("SELECT * FROM events ORDER BY event_date ASC").fetchall()
    return render_template("events.html", rows=rows)


@main.route("/admission", methods=["GET", "POST"])
def admission():
    db = get_db()
    if request.method == "POST":
        db.execute(
            """
            INSERT INTO admissions
            (student_name, email, phone, applying_class, previous_school, message)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                request.form["student_name"].strip(),
                request.form["email"].strip(),
                request.form["phone"].strip(),
                request.form["applying_class"].strip(),
                request.form["previous_school"].strip(),
                request.form["message"].strip(),
            ),
        )
        db.commit()
        flash("Admission enquiry submitted successfully.", "success")
        return redirect(url_for("main.admission"))
    return render_template("admission.html")


@main.route("/feedback", methods=["GET", "POST"])
def feedback():
    db = get_db()
    if request.method == "POST":
        db.execute(
            "INSERT INTO feedback (name, email, category, message) VALUES (?, ?, ?, ?)",
            (
                request.form["name"].strip(),
                request.form["email"].strip(),
                request.form["category"],
                request.form["message"].strip(),
            ),
        )
        db.commit()
        flash("Thank you for your feedback.", "success")
        return redirect(url_for("main.feedback"))
    return render_template("feedback.html")


@main.route("/timetable")
def timetable():
    db = get_db()
    classes = db.execute("SELECT id, name || ' - ' || section AS class_name FROM classes ORDER BY name, section").fetchall()
    selected_class_id = request.args.get("class_id", type=int)
    if not selected_class_id and classes:
        selected_class_id = classes[0]["id"]
    rows = []
    if selected_class_id:
        rows = db.execute(
            """
            SELECT t.*, s.name AS subject_name, c.name || ' - ' || c.section AS class_name
            FROM timetable_entries t
            JOIN subjects s ON s.id = t.subject_id
            JOIN classes c ON c.id = t.class_id
            WHERE t.class_id = ?
            ORDER BY
                CASE t.day_name
                    WHEN 'Monday' THEN 1
                    WHEN 'Tuesday' THEN 2
                    WHEN 'Wednesday' THEN 3
                    WHEN 'Thursday' THEN 4
                    WHEN 'Friday' THEN 5
                    WHEN 'Saturday' THEN 6
                    ELSE 7
                END,
                t.start_time
            """,
            (selected_class_id,),
        ).fetchall()
    return render_template(
        "timetable.html",
        classes=classes,
        rows=rows,
        selected_class_id=selected_class_id,
    )


@main.route("/dashboard")
@role_required("admin", "teacher", "student")
def dashboard():
    db = get_db()
    role = g.user["role"]
    if role == "admin":
        stats = {
            "users": db.execute("SELECT COUNT(*) AS count FROM users").fetchone()["count"],
            "students": db.execute("SELECT COUNT(*) AS count FROM users WHERE role='student'").fetchone()["count"],
            "teachers": db.execute("SELECT COUNT(*) AS count FROM users WHERE role='teacher'").fetchone()["count"],
            "classes": db.execute("SELECT COUNT(*) AS count FROM classes").fetchone()["count"],
        }
        announcements = db.execute(
            """
            SELECT a.*, u.full_name AS creator_name
            FROM announcements a
            JOIN users u ON u.id = a.created_by
            ORDER BY a.created_at DESC
            """
        ).fetchall()
        return render_template("dashboard_admin.html", stats=stats, announcements=announcements)

    if role == "teacher":
        classes = db.execute(
            """
            SELECT c.*, COUNT(e.student_id) AS student_count
            FROM classes c
            LEFT JOIN enrollments e ON e.class_id = c.id
            WHERE c.teacher_id = ?
            GROUP BY c.id
            ORDER BY c.name, c.section
            """,
            (g.user["id"],),
        ).fetchall()
        homework_count = db.execute(
            "SELECT COUNT(*) AS count FROM homework WHERE teacher_id = ?", (g.user["id"],)
        ).fetchone()["count"]
        announcements = db.execute(
            "SELECT * FROM announcements WHERE audience IN ('all', 'teacher') ORDER BY created_at DESC"
        ).fetchall()
        return render_template(
            "dashboard_teacher.html",
            classes=classes,
            homework_count=homework_count,
            announcements=announcements,
        )

    profile = db.execute(
        """
        SELECT sp.*, c.name || ' - ' || c.section AS class_name
        FROM student_profiles sp
        LEFT JOIN classes c ON c.id = sp.class_id
        WHERE sp.user_id = ?
        """,
        (g.user["id"],),
    ).fetchone()
    attendance = db.execute(
        """
        SELECT COUNT(*) AS total,
               SUM(CASE WHEN status = 'present' THEN 1 ELSE 0 END) AS present_count
        FROM attendance
        WHERE student_id = ?
        """,
        (g.user["id"],),
    ).fetchone()
    total = attendance["total"] or 0
    present = attendance["present_count"] or 0
    attendance_percentage = (present / total * 100) if total else 0
    marks = db.execute(
        """
        SELECT m.*, s.name AS subject_name
        FROM marks m
        JOIN subjects s ON s.id = m.subject_id
        WHERE m.student_id = ?
        ORDER BY m.id DESC
        LIMIT 5
        """,
        (g.user["id"],),
    ).fetchall()
    homework = db.execute(
        """
        SELECT h.*, s.name AS subject_name
        FROM homework h
        JOIN student_profiles sp ON sp.class_id = h.class_id
        JOIN subjects s ON s.id = h.subject_id
        WHERE sp.user_id = ?
        ORDER BY h.due_date ASC
        """,
        (g.user["id"],),
    ).fetchall()
    announcements = db.execute(
        "SELECT * FROM announcements WHERE audience IN ('all', 'student') ORDER BY created_at DESC"
    ).fetchall()
    return render_template(
        "dashboard_student.html",
        profile=profile,
        attendance_percentage=attendance_percentage,
        marks=marks,
        homework=homework,
        announcements=announcements,
    )


@main.route("/admin/users", methods=["GET", "POST"])
@role_required("admin")
def admin_users():
    db = get_db()
    if request.method == "POST":
        try:
            role = request.form["role"]
            if role not in {"student", "teacher"}:
                raise ValueError("Only student and teacher master records can be created here.")
            school_id = request.form["username"].strip()
            full_name = request.form["full_name"].strip()
            if not school_id or not full_name:
                raise ValueError("School ID and full name are required.")
            if role == "student":
                db.execute(
                    "INSERT INTO student_master_records (student_id, full_name) VALUES (?, ?)",
                    (school_id, full_name),
                )
            else:
                db.execute(
                    "INSERT INTO teacher_master_records (teacher_id, full_name) VALUES (?, ?)",
                    (school_id, full_name),
                )
            db.commit()
            flash("Master record created. The person can now self-register with OTP.", "success")
        except Exception as error:
            flash(str(error) if isinstance(error, ValueError) else "Could not create master record. School ID may already exist.", "danger")
        return redirect(url_for("main.admin_users"))

    users = db.execute("SELECT * FROM users ORDER BY role, full_name").fetchall()
    master_records = db.execute(
        """
        SELECT student_id AS school_id, full_name, 'student' AS role, registration_completed
        FROM student_master_records
        UNION ALL
        SELECT teacher_id AS school_id, full_name, 'teacher' AS role, registration_completed
        FROM teacher_master_records
        ORDER BY role, full_name
        """
    ).fetchall()
    return render_template("admin_users.html", users=users, master_records=master_records)


@main.route("/admin/users/<int:user_id>/reset-password", methods=["POST"])
@role_required("admin")
def admin_reset_user_password(user_id):
    temporary_password = request.form.get("temporary_password", "")
    if len(temporary_password) < 8:
        flash("Temporary password must be at least 8 characters.", "danger")
    else:
        db = get_db()
        updated = db.execute("UPDATE users SET password_hash = ?, must_change_password = 1 WHERE id = ? AND role IN ('student', 'teacher')", (generate_password_hash(temporary_password), user_id))
        db.commit()
        flash(f"Temporary password reset. Give it once to the user: {temporary_password}" if updated.rowcount else "Only student and teacher passwords can be reset here.", "success" if updated.rowcount else "danger")
    return redirect(url_for("main.admin_users"))


@main.route("/admin/classes", methods=["GET", "POST"])
@role_required("admin")
def admin_classes():
    db = get_db()
    if request.method == "POST":
        teacher_id = request.form.get("teacher_id") or None
        db.execute(
            "INSERT INTO classes (name, section, teacher_id) VALUES (?, ?, ?)",
            (request.form["name"].strip(), request.form["section"].strip(), teacher_id),
        )
        db.commit()
        flash("Class created successfully.", "success")
        return redirect(url_for("main.admin_classes"))

    classes = db.execute(
        """
        SELECT c.*, u.full_name AS teacher_name
        FROM classes c
        LEFT JOIN users u ON u.id = c.teacher_id
        ORDER BY c.name, c.section
        """
    ).fetchall()
    teachers = db.execute("SELECT id, full_name FROM users WHERE role='teacher' ORDER BY full_name").fetchall()
    return render_template("admin_classes.html", classes=classes, teachers=teachers)


@main.route("/admin/subjects", methods=["GET", "POST"])
@role_required("admin")
def admin_subjects():
    db = get_db()
    if request.method == "POST":
        try:
            db.execute(
                "INSERT INTO subjects (name, code) VALUES (?, ?)",
                (request.form["name"].strip(), request.form["code"].strip().upper()),
            )
            db.commit()
            flash("Subject added successfully.", "success")
        except Exception:
            flash("Subject code already exists.", "danger")
        return redirect(url_for("main.admin_subjects"))

    subjects = db.execute("SELECT * FROM subjects ORDER BY name").fetchall()
    return render_template("admin_subjects.html", subjects=subjects)


@main.route("/admin/site-content", methods=["GET", "POST"])
@role_required("admin")
def admin_site_content():
    db = get_db()
    form_type = request.form.get("form_type")
    if request.method == "POST":
        if form_type == "facility":
            image = request.files.get("image")
            asset = (
                media_row_from_upload(db, image, is_public=True, folder="schoolms/facilities", created_by=g.user["id"])
                if image and image.filename else None
            )
            db.execute(
                """INSERT INTO facilities
                (title, description, icon, image_url, cloudinary_public_id, media_asset_id) VALUES (?, ?, ?, ?, ?, ?)""",
                (
                    request.form["title"].strip(),
                    request.form["description"].strip(),
                    request.form["icon"].strip(),
                    asset["secure_url"] if asset else None,
                    asset["public_id"] if asset else None,
                    asset["id"] if asset else None,
                ),
            )
            flash("Facility added.", "success")
        elif form_type == "event":
            image = request.files.get("image")
            asset = (
                media_row_from_upload(db, image, is_public=True, folder="schoolms/events", created_by=g.user["id"])
                if image and image.filename else None
            )
            db.execute(
                """INSERT INTO events
                (title, description, event_date, venue, image_url, cloudinary_public_id, media_asset_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (
                    request.form["title"].strip(),
                    request.form["description"].strip(),
                    request.form["event_date"],
                    request.form["venue"].strip(),
                    asset["secure_url"] if asset else None,
                    asset["public_id"] if asset else None,
                    asset["id"] if asset else None,
                ),
            )
            flash("Event added.", "success")
        elif form_type == "timetable":
            db.execute(
                """
                INSERT INTO timetable_entries (class_id, subject_id, day_name, start_time, end_time, room)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    request.form["class_id"],
                    request.form["subject_id"],
                    request.form["day_name"],
                    request.form["start_time"],
                    request.form["end_time"],
                    request.form["room"].strip(),
                ),
            )
            flash("Timetable entry added.", "success")
        db.commit()
        if form_type in {"facility", "event"}:
            try:
                title = "School facilities updated" if form_type == "facility" else "New school event"
                body = "School-wide content has been updated. Open the app to view it."
                send_public_notification(
                    title=title,
                    body=body,
                    data={"event_type": "public_content_update", "destination": form_type},
                )
            except FirebaseAuthProvisioningError:
                current_app.logger.warning("Public content saved but FCM delivery is unavailable.")
        return redirect(url_for("main.admin_site_content"))

    facilities_rows = db.execute("SELECT * FROM facilities ORDER BY id DESC").fetchall()
    event_rows = db.execute("SELECT * FROM events ORDER BY event_date ASC").fetchall()
    timetable_rows = db.execute(
        """
        SELECT t.*, c.name || ' - ' || c.section AS class_name, s.name AS subject_name
        FROM timetable_entries t
        JOIN classes c ON c.id = t.class_id
        JOIN subjects s ON s.id = t.subject_id
        ORDER BY t.day_name, t.start_time
        """
    ).fetchall()
    classes = db.execute("SELECT id, name || ' - ' || section AS class_name FROM classes ORDER BY name, section").fetchall()
    subjects = db.execute("SELECT id, name FROM subjects ORDER BY name").fetchall()
    admissions_rows = db.execute("SELECT * FROM admissions ORDER BY created_at DESC").fetchall()
    feedback_rows = db.execute("SELECT * FROM feedback ORDER BY created_at DESC").fetchall()
    return render_template(
        "admin_site_content.html",
        facilities_rows=facilities_rows,
        event_rows=event_rows,
        timetable_rows=timetable_rows,
        classes=classes,
        subjects=subjects,
        admissions_rows=admissions_rows,
        feedback_rows=feedback_rows,
    )


@main.route("/announcements", methods=["GET", "POST"])
@role_required("admin", "teacher")
def announcements():
    db = get_db()
    if request.method == "POST":
        db.execute(
            "INSERT INTO announcements (title, content, audience, created_by) VALUES (?, ?, ?, ?)",
            (
                request.form["title"].strip(),
                request.form["content"].strip(),
                request.form["audience"],
                g.user["id"],
            ),
        )
        db.commit()
        if request.form["audience"] == "all":
            try:
                sync_public_announcements(db)
            except Exception:
                current_app.logger.exception("Announcement saved but Firestore public sync failed")
            try:
                send_public_notification(
                    title=f"New announcement: {request.form['title'].strip()}",
                    body="Open SchoolMS to read the latest school announcement.",
                    data={"event_type": "announcement", "destination": "announcements"},
                )
            except FirebaseAuthProvisioningError:
                current_app.logger.warning("Announcement saved but FCM delivery is unavailable.")
        flash("Announcement published.", "success")
        return redirect(url_for("main.announcements"))

    if g.user["role"] == "admin":
        rows = db.execute(
            """
            SELECT a.*, u.full_name AS creator_name
            FROM announcements a
            JOIN users u ON u.id = a.created_by
            ORDER BY a.created_at DESC
            """
        ).fetchall()
    else:
        rows = db.execute(
            """
            SELECT a.*, u.full_name AS creator_name
            FROM announcements a
            JOIN users u ON u.id = a.created_by
            WHERE a.created_by = ?
            ORDER BY a.created_at DESC
            """,
            (g.user["id"],),
        ).fetchall()
    return render_template("announcements.html", announcements=rows)


@main.route("/teacher/classes")
@role_required("teacher")
def teacher_classes():
    classes = get_db().execute(
        """
        SELECT c.*, COUNT(e.student_id) AS student_count
        FROM classes c
        LEFT JOIN enrollments e ON e.class_id = c.id
        WHERE c.teacher_id = ?
        GROUP BY c.id
        ORDER BY c.name, c.section
        """,
        (g.user["id"],),
    ).fetchall()
    return render_template("teacher_classes.html", classes=classes)


@main.route("/teacher/class/<int:class_id>")
@role_required("teacher")
def teacher_class_detail(class_id):
    db = get_db()
    class_row = db.execute(
        "SELECT * FROM classes WHERE id = ? AND teacher_id = ?", (class_id, g.user["id"])
    ).fetchone()
    if not class_row:
        flash("Class not found.", "danger")
        return redirect(url_for("main.teacher_classes"))

    students = db.execute(
        """
        SELECT u.id, u.full_name, sp.roll_no
        FROM enrollments e
        JOIN users u ON u.id = e.student_id
        LEFT JOIN student_profiles sp ON sp.user_id = u.id
        WHERE e.class_id = ?
        ORDER BY sp.roll_no, u.full_name
        """,
        (class_id,),
    ).fetchall()
    subjects = db.execute(
        """
        SELECT s.*
        FROM class_subjects cs
        JOIN subjects s ON s.id = cs.subject_id
        WHERE cs.class_id = ?
        ORDER BY s.name
        """,
        (class_id,),
    ).fetchall()
    return render_template("teacher_class_detail.html", class_row=class_row, students=students, subjects=subjects)


@main.route("/teacher/attendance/<int:class_id>", methods=["GET", "POST"])
@role_required("teacher")
def teacher_attendance(class_id):
    db = get_db()
    class_row = db.execute(
        "SELECT * FROM classes WHERE id = ? AND teacher_id = ?", (class_id, g.user["id"])
    ).fetchone()
    if not class_row:
        flash("Class not found.", "danger")
        return redirect(url_for("main.teacher_classes"))

    students = db.execute(
        """
        SELECT u.id, u.full_name, sp.roll_no
        FROM enrollments e
        JOIN users u ON u.id = e.student_id
        LEFT JOIN student_profiles sp ON sp.user_id = u.id
        WHERE e.class_id = ?
        ORDER BY sp.roll_no, u.full_name
        """,
        (class_id,),
    ).fetchall()
    subjects = db.execute(
        """
        SELECT s.*
        FROM class_subjects cs
        JOIN subjects s ON s.id = cs.subject_id
        WHERE cs.class_id = ?
        ORDER BY s.name
        """,
        (class_id,),
    ).fetchall()

    if request.method == "POST":
        subject_id = int(request.form["subject_id"])
        if not class_has_subject(db, class_id, subject_id):
            flash("Select a valid subject for this class.", "danger")
            return redirect(url_for("main.teacher_attendance", class_id=class_id))
        for student in students:
            try:
                db.execute(
                    """
                    INSERT INTO attendance (student_id, class_id, subject_id, attendance_date, status, marked_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        student["id"],
                        class_id,
                        subject_id,
                        request.form["attendance_date"],
                        request.form.get(f"status_{student['id']}", "absent"),
                        g.user["id"],
                    ),
                )
            except Exception:
                flash("Attendance for this date and subject already exists.", "warning")
                return redirect(url_for("main.teacher_attendance", class_id=class_id))
        db.commit()
        flash("Attendance saved.", "success")
        return redirect(url_for("main.teacher_attendance", class_id=class_id))

    attendance_summary = db.execute(
        """
        SELECT u.full_name, sp.roll_no, COUNT(a.id) AS total_days,
               SUM(CASE WHEN a.status = 'present' THEN 1 ELSE 0 END) AS present_days
        FROM enrollments e
        JOIN users u ON u.id = e.student_id
        LEFT JOIN student_profiles sp ON sp.user_id = u.id
        LEFT JOIN attendance a ON a.student_id = u.id AND a.class_id = e.class_id
        WHERE e.class_id = ?
        GROUP BY u.id, u.full_name, sp.roll_no
        ORDER BY sp.roll_no, u.full_name
        """,
        (class_id,),
    ).fetchall()

    return render_template(
        "teacher_attendance.html",
        class_row=class_row,
        students=students,
        subjects=subjects,
        attendance_summary=attendance_summary,
        today=date.today().isoformat(),
    )


@main.route("/teacher/homework", methods=["GET", "POST"])
@role_required("teacher")
def teacher_homework():
    db = get_db()
    teacher_classes = db.execute(
        "SELECT * FROM classes WHERE teacher_id = ? ORDER BY name, section", (g.user["id"],)
    ).fetchall()
    available_subjects = db.execute(
        """
        SELECT DISTINCT s.*
        FROM classes c
        JOIN class_subjects cs ON cs.class_id = c.id
        JOIN subjects s ON s.id = cs.subject_id
        WHERE c.teacher_id = ?
        ORDER BY s.name
        """,
        (g.user["id"],),
    ).fetchall()

    if request.method == "POST":
        class_id = int(request.form["class_id"])
        subject_id = int(request.form["subject_id"])
        if not teacher_has_class_access(db, g.user["id"], class_id):
            flash("You can only create homework for your assigned classes.", "danger")
            return redirect(url_for("main.teacher_homework"))
        if not class_has_subject(db, class_id, subject_id):
            flash("Selected subject is not assigned to that class.", "danger")
            return redirect(url_for("main.teacher_homework"))
        db.execute(
            """
            INSERT INTO homework (class_id, subject_id, teacher_id, title, description, due_date, file_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                class_id,
                subject_id,
                g.user["id"],
                request.form["title"].strip(),
                request.form["description"].strip(),
                request.form["due_date"],
                save_uploaded_file(request.files.get("attachment")),
            ),
        )
        db.commit()
        flash("Homework created.", "success")
        return redirect(url_for("main.teacher_homework"))

    homework = db.execute(
        """
        SELECT h.*, c.name || ' - ' || c.section AS class_name, s.name AS subject_name
        FROM homework h
        JOIN classes c ON c.id = h.class_id
        JOIN subjects s ON s.id = h.subject_id
        WHERE h.teacher_id = ?
        ORDER BY h.created_at DESC
        """,
        (g.user["id"],),
    ).fetchall()
    return render_template(
        "teacher_homework.html",
        teacher_classes=teacher_classes,
        available_subjects=available_subjects,
        homework=homework,
    )


@main.route("/teacher/marks", methods=["GET", "POST"])
@role_required("teacher")
def teacher_marks():
    db = get_db()
    classes = db.execute(
        "SELECT id, name || ' - ' || section AS class_name FROM classes WHERE teacher_id = ? ORDER BY name, section",
        (g.user["id"],),
    ).fetchall()
    students = db.execute(
        """
        SELECT DISTINCT u.id, u.full_name, c.id AS class_id, c.name || ' - ' || c.section AS class_name
        FROM classes c
        JOIN enrollments e ON e.class_id = c.id
        JOIN users u ON u.id = e.student_id
        WHERE c.teacher_id = ?
        ORDER BY u.full_name
        """,
        (g.user["id"],),
    ).fetchall()
    subjects = db.execute(
        """
        SELECT DISTINCT s.*
        FROM classes c
        JOIN class_subjects cs ON cs.class_id = c.id
        JOIN subjects s ON s.id = cs.subject_id
        WHERE c.teacher_id = ?
        ORDER BY s.name
        """,
        (g.user["id"],),
    ).fetchall()

    if request.method == "POST":
        student_id = int(request.form["student_id"])
        class_id = int(request.form["class_id"])
        subject_id = int(request.form["subject_id"])
        total_marks = int(request.form["total_marks"])
        obtained_marks = int(request.form["obtained_marks"])
        if total_marks <= 0 or obtained_marks < 0 or obtained_marks > total_marks:
            flash("Enter a valid marks range.", "danger")
            return redirect(url_for("main.teacher_marks"))
        if not teacher_has_class_access(db, g.user["id"], class_id):
            flash("You can only enter marks for your assigned classes.", "danger")
            return redirect(url_for("main.teacher_marks"))
        if not student_in_class(db, student_id, class_id):
            flash("Selected student is not enrolled in that class.", "danger")
            return redirect(url_for("main.teacher_marks"))
        if not class_has_subject(db, class_id, subject_id):
            flash("Selected subject is not assigned to that class.", "danger")
            return redirect(url_for("main.teacher_marks"))
        db.execute(
            """
            INSERT INTO marks (student_id, class_id, subject_id, exam_name, total_marks, obtained_marks, grade, entered_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                student_id,
                class_id,
                subject_id,
                request.form["exam_name"].strip(),
                total_marks,
                obtained_marks,
                grade_from_score(obtained_marks, total_marks),
                g.user["id"],
            ),
        )
        db.commit()
        # Recipient selection is server-side and exact-profile based.  Never put
        # marks values in the lock-screen body or use a broadcast topic here.
        try:
            send_profile_notification(
                db,
                profile_user_id=student_id,
                title="Marks updated",
                body="New marks have been published.",
                data={"event_type": "marks", "destination": "marks"},
            )
        except FirebaseAuthProvisioningError:
            current_app.logger.warning("Marks saved but private FCM delivery is unavailable.")
        flash("Marks saved.", "success")
        return redirect(url_for("main.teacher_marks"))

    marks = db.execute(
        """
        SELECT m.*, u.full_name AS student_name, s.name AS subject_name, c.name || ' - ' || c.section AS class_name
        FROM marks m
        JOIN users u ON u.id = m.student_id
        JOIN subjects s ON s.id = m.subject_id
        JOIN classes c ON c.id = m.class_id
        WHERE m.entered_by = ?
        ORDER BY m.id DESC
        """,
        (g.user["id"],),
    ).fetchall()
    return render_template(
        "teacher_marks.html",
        classes=classes,
        students=students,
        subjects=subjects,
        marks=marks,
    )


@main.route("/student/profile", methods=["GET", "POST"])
@role_required("student")
def student_profile():
    db = get_db()
    if request.method == "POST":
        db.execute(
            """
            UPDATE student_profiles
            SET email = ?, address = ?, guardian_name = ?
            WHERE user_id = ?
            """,
            (
                request.form["email"].strip(),
                request.form["address"].strip(),
                request.form["guardian_name"].strip(),
                g.user["id"],
            ),
        )
        db.commit()
        flash("Profile updated.", "success")
        return redirect(url_for("main.student_profile"))

    profile = db.execute(
        """
        SELECT sp.*, c.name || ' - ' || c.section AS class_name, u.full_name,
               u.phone AS verified_phone
        FROM student_profiles sp
        JOIN users u ON u.id = sp.user_id
        LEFT JOIN classes c ON c.id = sp.class_id
        WHERE sp.user_id = ?
        """,
        (g.user["id"],),
    ).fetchone()
    return render_template("student_profile.html", profile=profile)


@main.route("/student/attendance")
@role_required("student")
def student_attendance():
    db = get_db()
    rows = db.execute(
        """
        SELECT a.*, s.name AS subject_name, c.name || ' - ' || c.section AS class_name
        FROM attendance a
        JOIN subjects s ON s.id = a.subject_id
        JOIN classes c ON c.id = a.class_id
        WHERE a.student_id = ?
        ORDER BY a.attendance_date DESC
        """,
        (g.user["id"],),
    ).fetchall()
    summary = db.execute(
        """
        SELECT COUNT(*) AS total,
               SUM(CASE WHEN status = 'present' THEN 1 ELSE 0 END) AS present_count
        FROM attendance
        WHERE student_id = ?
        """,
        (g.user["id"],),
    ).fetchone()
    total = summary["total"] or 0
    present = summary["present_count"] or 0
    percentage = (present / total * 100) if total else 0
    return render_template("student_attendance.html", rows=rows, percentage=percentage)


@main.route("/student/homework", methods=["GET", "POST"])
@role_required("student")
def student_homework():
    db = get_db()
    if request.method == "POST":
        homework_id = int(request.form["homework_id"])
        if not student_can_access_homework(db, g.user["id"], homework_id):
            flash("You can only submit homework assigned to your class.", "danger")
            return redirect(url_for("main.student_homework"))
        try:
            db.execute(
                """
                INSERT INTO homework_submissions (homework_id, student_id, notes, file_name)
                VALUES (?, ?, ?, ?)
                """,
                (
                    homework_id,
                    g.user["id"],
                    request.form["notes"].strip(),
                    save_uploaded_file(request.files.get("submission_file")),
                ),
            )
        except Exception:
            flash("You have already submitted this homework.", "warning")
            return redirect(url_for("main.student_homework"))
        db.commit()
        flash("Homework submitted.", "success")
        return redirect(url_for("main.student_homework"))

    rows = db.execute(
        """
        SELECT h.*, s.name AS subject_name, hs.id AS submission_id, hs.submitted_at
        FROM homework h
        JOIN student_profiles sp ON sp.class_id = h.class_id
        JOIN subjects s ON s.id = h.subject_id
        LEFT JOIN homework_submissions hs ON hs.homework_id = h.id AND hs.student_id = sp.user_id
        WHERE sp.user_id = ?
        ORDER BY h.due_date ASC
        """,
        (g.user["id"],),
    ).fetchall()
    return render_template("student_homework.html", rows=rows)


@main.route("/student/marks")
@role_required("student")
def student_marks():
    rows = get_db().execute(
        """
        SELECT m.*, s.name AS subject_name
        FROM marks m
        JOIN subjects s ON s.id = m.subject_id
        WHERE m.student_id = ?
        ORDER BY m.id DESC
        """,
        (g.user["id"],),
    ).fetchall()
    return render_template("student_marks.html", rows=rows)
