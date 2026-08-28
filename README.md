# School Management Web App

Professional school management web app built with Flask, SQLite, Bootstrap, and Jinja templates.

## Features

- Admin, teacher, and student login
- Role-based dashboards
- Attendance tracking with percentage calculation
- Homework upload and submission
- Marks and grades management
- Student profile management
- Teacher class management
- Admin management for users, classes, and subjects
- File upload support
- Notifications and announcements
- Seeded sample database

## Development Setup

1. Create and activate a virtual environment.
2. Install dependencies:

```bash
python -m pip install -r requirements.txt
```

3. Set Flask environment variables.

PowerShell:

```powershell
$env:FLASK_APP="run.py"
$env:FLASK_DEBUG="1"
```

You can also copy values from `.env.example` into your shell or use `dev.ps1`.

4. Initialize the sample database:

```bash
python -m flask init-db
```

5. Start the development server:

```bash
python -m flask run
```

6. Open `http://127.0.0.1:5000`

## Development Notes

- SQLite database and uploaded files are stored under the `instance/` folder.
- `python seed_db.py` still works if you prefer a direct seed script.
- `FLASK_SECRET_KEY` can be overridden from the environment for local testing.

## Deploy Online

This project now includes production-ready files for simple Python hosting:

- [wsgi.py](/C:/Users/ASUS/school-management-app/wsgi.py)
- [Procfile](/C:/Users/ASUS/school-management-app/Procfile)
- [render.yaml](/C:/Users/ASUS/school-management-app/render.yaml)

Typical deployment flow:

1. Push the project to GitHub
2. Create a new web service on Render or a similar Python host
3. Use:
   `buildCommand: python -m pip install -r requirements.txt`
4. Use:
   `startCommand: gunicorn wsgi:app`
5. Set `FLASK_SECRET_KEY` in the hosting environment
6. Open the generated HTTPS URL

## Android APK Wrapper

An Android Studio project is included in [android-app](/C:/Users/ASUS/school-management-app/android-app).

To build the APK:

1. Deploy the Flask app online first
2. Edit `android-app/gradle.properties`
3. Replace `APP_BASE_URL` with your live HTTPS URL, for example:
   `https://your-app-name.onrender.com/home`
4. Open the repository root in Android Studio
5. Build the APK from:
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`

More details are in [ANDROID_APP.md](/C:/Users/ASUS/school-management-app/ANDROID_APP.md).

## Sample Logins

- Admin: `admin` / `admin123`
- Teacher: `teacher1` / `teacher123`
- Student: `student1` / `student123`

Any user created from the admin panel can log in globally with their own username and password. The app now detects the correct role automatically after login.
