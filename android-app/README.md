# SchoolHub Android App

Native Android school app built with Kotlin, XML layouts, Material Design, and intent-based navigation.

## Public Repo Safety

This repository is safe to keep public when real project files stay local:

- Do not commit `app/google-services.json`, `.firebaserc`, `.env*`, `keystore.properties`, or signing keys.
- Copy `app/google-services.example.json` to `app/google-services.json` locally and use your own Firebase Android app config.
- Copy `.firebaserc.example` to `.firebaserc` locally and set your Firebase project ID.
- Configure the first admin through local Gradle properties or environment variables, not source code:

```properties
SCHOOLMS_BOOTSTRAP_ADMIN_USERNAME=your_admin_username
SCHOOLMS_BOOTSTRAP_ADMIN_PASSWORD=your_private_password
SCHOOLMS_BOOTSTRAP_ADMIN_NAME=Your Name
```

Set the recovery Cloud Function secret as an environment variable named `RECOVERY_SECRET`. Do not put the real value in code.

## Firebase Security

- Enable Firebase Authentication before using the app.
- Give real admin accounts a Firebase custom claim: `admin: true`.
- Firestore writes to sensitive account fields require that admin claim.
- User passwords are stripped before shared user data is synced to Firestore.
- Storage uploads require authentication, path-specific permissions, content type checks, and file-size limits.
- Firebase Hosting uses HTTPS by default. Publish APKs through GitHub Releases or Firebase Hosting downloads, not inside the source commit.

Implemented app features:

- Splash screen
- Role-based login
- Home screen with buttons
- Admin, teacher, and student dashboard flow
- Gallery screen using drawable resources
- Content screen
- RecyclerView-based list screens
- Attendance percentage view
- Homework create and student submission flow with file picker
- Marks and grades
- Student profile and class management
- Facilities, events, notifications, timetable, feedback, and admission enquiry

Scope note:

The original request mixed Android requirements with backend-specific Flask concepts such as Jinja templates, Werkzeug password hashing, app factory pattern, route protection, and authenticated file download routes. Those are not part of a native Android client. This project implements the Android app side only, with local seeded data and Kotlin-side role restrictions.
