# Firebase Functions

This folder contains the sender required for closed-app FCM push notifications.

What it does:
- Listens to Firestore document `shared_state/schoolhub`
- Reads the `last_event` payload written by the Android app
- Broadcasts school announcements to topic `schoolhub_all`
- Sends targeted events like approval updates to user-specific topics when available

Deploy steps:
1. Install Firebase CLI
2. In this folder run `npm install`
3. Run `firebase login`
4. Run `firebase init functions`
5. Replace the generated `index.js` with the one in this folder
6. Run `firebase deploy --only functions`

After deployment:
- Published announcements will trigger immediate closed-app push notifications
- Devices subscribed in the Android app will receive announcement pushes even when the app is closed
