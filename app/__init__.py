import os
from datetime import timedelta
from pathlib import Path

from flask import Flask
from dotenv import load_dotenv


def create_app():
    # Local-only configuration. Production hosts should provide the same values as environment variables.
    load_dotenv()
    app = Flask(__name__, instance_relative_config=True)
    app.config.from_mapping(
        SECRET_KEY=os.getenv("FLASK_SECRET_KEY", "dev-secret-key-change-me"),
        DATABASE=str(Path(app.instance_path) / "school.db"),
        UPLOAD_FOLDER=str(Path(app.instance_path) / "uploads"),
        MAX_CONTENT_LENGTH=16 * 1024 * 1024,
        # Deliberately read the provider key directly from the server environment.
        # It is never made available to templates, JavaScript, or Android clients.
        TWOFACTOR_API_KEY=os.getenv("TWOFACTOR_API_KEY", "").strip(),
        FIREBASE_PROJECT_ID=os.getenv("FIREBASE_PROJECT_ID", "").strip(),
        FIREBASE_SERVICE_ACCOUNT_PATH=os.getenv("FIREBASE_SERVICE_ACCOUNT_PATH", "").strip(),
        # Render stores the entire service-account JSON as an encrypted environment
        # variable. It is parsed in memory and never written to the filesystem.
        FIREBASE_SERVICE_ACCOUNT_JSON=os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip(),
        FIREBASE_AUTH_EMAIL_DOMAIN=os.getenv("FIREBASE_AUTH_EMAIL_DOMAIN", "cns-paunta.app").strip(),
        # Firebase Web API keys identify a project, but are not server credentials.  Keep them
        # configurable so the rendered registration page can use Firebase's official email links.
        FIREBASE_WEB_API_KEY=os.getenv("FIREBASE_WEB_API_KEY", "").strip(),
        FIREBASE_WEB_AUTH_DOMAIN=os.getenv("FIREBASE_WEB_AUTH_DOMAIN", "").strip(),
        FIREBASE_WEB_PROJECT_ID=os.getenv("FIREBASE_WEB_PROJECT_ID", "").strip(),
        FIREBASE_WEB_APP_ID=os.getenv("FIREBASE_WEB_APP_ID", "").strip(),
        # Cloudinary is server-only.  The Android client receives delivery URLs,
        # never a Cloudinary API key or API secret.
        CLOUDINARY_CLOUD_NAME=os.getenv("CLOUDINARY_CLOUD_NAME", "").strip(),
        CLOUDINARY_API_KEY=os.getenv("CLOUDINARY_API_KEY", "").strip(),
        CLOUDINARY_API_SECRET=os.getenv("CLOUDINARY_API_SECRET", "").strip(),
        OTP_RESEND_COOLDOWN_SECONDS=60,
        EMAIL_REGISTRATION_SESSION_TTL_SECONDS=30 * 60,
        MOBILE_OTP_SESSION_TTL_SECONDS=10 * 60,
        MOBILE_OTP_MAX_VERIFY_ATTEMPTS=5,
        LOGIN_MAX_FAILED_ATTEMPTS=5,
        LOGIN_LOCKOUT_SECONDS=15 * 60,
        PERMANENT_SESSION_LIFETIME=timedelta(hours=8),
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        SESSION_COOKIE_SECURE=os.getenv("FLASK_SESSION_COOKIE_SECURE", "false").lower() == "true",
    )
    app.config.from_prefixed_env()

    Path(app.instance_path).mkdir(parents=True, exist_ok=True)
    Path(app.config["UPLOAD_FOLDER"]).mkdir(parents=True, exist_ok=True)

    from .routes import main
    from .database import migrate_db, register_db_commands

    app.register_blueprint(main)
    with app.app_context():
        migrate_db()
    register_db_commands(app)
    return app
