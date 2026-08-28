/**
 * Import function triggers from their respective submodules:
 *
 * const {onCall} = require("firebase-functions/v2/https");
 * const {onDocumentWritten} = require("firebase-functions/v2/firestore");
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */

const functions = require("firebase-functions/v1");
const {logger} = require("firebase-functions/v1");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();

const sanitizeTopic = (value) =>
  value
    .toString()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");

const userTopic = (username) => `user_${sanitizeTopic(username)}`;
const roleTopic = (role) => `role_${sanitizeTopic(role)}`;
const classTopic = (className) => `class_${sanitizeTopic(className)}`;
const audienceTopic = (role, className) =>
  `audience_${sanitizeTopic(role)}_${sanitizeTopic(className)}`;
const PERSONAL_NOTIFICATION_TYPES = new Set([
  "account_approval",
  "password_reset",
  "feedback_reply",
  "admission_reply",
  "marks",
  "homework_submission",
]);
const PERSONAL_EVENTS_COLLECTION = "personal_events";
const PERSONAL_NOTIFICATIONS_COLLECTION = "personal_notifications";
const MAX_PERSONAL_NOTIFICATIONS = 80;

async function appendPersonalNotification(username, title, subtitle, badge = "", timestamp = Date.now()) {
  const normalizedUsername = String(username || "").trim().toLowerCase();
  if (!normalizedUsername) return;
  const docRef = admin.firestore().collection(PERSONAL_NOTIFICATIONS_COLLECTION).doc(normalizedUsername);
  const snapshot = await docRef.get().catch(() => null);
  const existingItems = parseArrayJson(snapshot?.data()?.items_json);
  const nextItems = [
    {
      title: String(title || "").trim(),
      subtitle: String(subtitle || "").trim(),
      badge: String(badge || "").trim(),
      targetUsername: normalizedUsername,
      timestamp,
    },
    ...existingItems,
  ].slice(0, MAX_PERSONAL_NOTIFICATIONS);
  await docRef.set({
    username: normalizedUsername,
    items_json: JSON.stringify(nextItems),
    updatedAt: timestamp,
  }, {merge: true});
}

function buildRealtimePayload(parsed) {
  const title = parsed.title || "Cambridge National School";
  const message = parsed.message || "Check the app for the latest notification.";
  const role = parsed.role || "";
  const className = parsed.className || "";
  const targetUsername = parsed.targetUsername || "";
  const deliveryScope = targetUsername ? "user" : "audience";

  const topics = new Set();
  if (targetUsername) {
    topics.add(userTopic(targetUsername));
  } else if (role && className) {
    topics.add(audienceTopic(role, className));
  } else if (className) {
    topics.add(classTopic(className));
  } else if (role) {
    topics.add(roleTopic(role));
  }

  return {
    topics,
    payload: {
      android: {
        priority: "high",
        notification: {
          channelId: "cns_realtime_updates",
        },
      },
      notification: {
        title,
        body: message,
      },
      data: {
        title,
        message,
        event_type: parsed.type || "",
        role,
        class_name: className,
        target: targetUsername,
        delivery_scope: deliveryScope,
      },
    },
  };
}

async function dispatchRealtimeNotification(parsed) {
  const notifiableTypes = new Set([
    "registration_request",
    "account_approval",
    "password_reset",
    "announcement_publish",
    "announcement_update",
    "events",
    "attendance",
    "homework_publish",
    "homework_submission",
    "marks",
    "feedback",
    "feedback_reply",
    "admission",
    "admission_reply",
  ]);
  const targetUsername = parsed.targetUsername || "";
  if (!notifiableTypes.has(parsed.type || "")) {
    return;
  }

  if (PERSONAL_NOTIFICATION_TYPES.has(parsed.type || "") && !targetUsername) {
    logger.warn("Skipping personal notification without target username", parsed);
    return;
  }

  const {topics, payload} = buildRealtimePayload(parsed);
  if (topics.size === 0) {
    return;
  }

  const sends = Array.from(topics).map((topic) =>
    admin
      .messaging()
      .send({...payload, topic})
      .then(() => logger.info("Sent realtime notification to", topic))
      .catch((error) => logger.error("Error sending to topic", topic, error))
  );
  return Promise.all(sends);
}

const PUBLIC_CONTENT_COLLECTION = "public_content";
const PUBLIC_CONTENT_DOCUMENT = "schoolhub";

exports.sharedStateNotifier = functions.firestore
  .document("shared_state/{docId}")
  .onWrite(async (change, context) => {
    const beforeEvent = change.before.exists ? change.before.data()?.last_event : "";
    const afterEvent = change.after.exists ? change.after.data()?.last_event : "";
    if (!afterEvent || afterEvent === beforeEvent) {
      return;
    }
    let parsed = null;
    try {
      parsed = JSON.parse(afterEvent);
    } catch (error) {
      logger.error("Unable to parse last_event JSON", error);
      return;
    }
    if (parsed.targetUsername && PERSONAL_NOTIFICATION_TYPES.has(parsed.type || "")) {
      await appendPersonalNotification(parsed.targetUsername, parsed.title, parsed.message, "", parsed.timestamp || Date.now());
    }
    return dispatchRealtimeNotification(parsed);
  });

// Public content has its own document. This trigger emits only a generic public
// update and never reads or exposes profile, marks, attendance, feedback, or
// admission fields.
exports.publicContentNotifier = functions.firestore
  .document(`${PUBLIC_CONTENT_COLLECTION}/${PUBLIC_CONTENT_DOCUMENT}`)
  .onWrite(async (change) => {
    const before = change.before.exists ? change.before.data() : null;
    const after = change.after.exists ? change.after.data() : null;
    if (!after || !after.schemaVersion || before?.updatedAt === after.updatedAt) {
      return null;
    }
    return admin.messaging().send({
      topic: "school_public",
      notification: {
        title: "Cambridge National School",
        body: "School information has been updated.",
      },
      data: {
        delivery_scope: "public",
        event_type: "public_content",
        destination: "announcements",
      },
      android: {
        priority: "high",
        notification: {
          channelId: "cns_realtime_updates",
        },
      },
    });
  });

exports.personalEventNotifier = functions.firestore
  .document(`${PERSONAL_EVENTS_COLLECTION}/{eventId}`)
  .onCreate(async (snap) => {
    const parsed = snap.data() || {};
    const targetUsername = String(parsed.targetUsername || "").trim().toLowerCase();
    try {
      if (!targetUsername) {
        return;
      }
      await appendPersonalNotification(
        targetUsername,
        parsed.title,
        parsed.message,
        "",
        Number(parsed.timestamp) || Date.now()
      );
      await dispatchRealtimeNotification(parsed);
    } finally {
      await snap.ref.delete().catch(() => null);
    }
  });

const RECOVERY_SECRET_ENV = "RECOVERY_SECRET";
const recoveryAttempts = new Map();
const SHARED_STATE_COLLECTION = "shared_state";
const SHARED_STATE_DOCUMENT = "schoolhub";
const REGISTRATION_REQUESTS_COLLECTION = "registration_requests";
const PASSWORD_RESET_REQUESTS_COLLECTION = "password_reset_requests";
const KEY_OUR_SCHOLARS = "our_scholars";
const KEY_SCHOOL_CONTACT = "school_contact";
const KEY_SCHOOL_CONTENT = "school_content";

const DEFAULT_OUR_SCHOLARS = [
  {
    title: "Proud CNS achievers",
    subtitle: "Celebrate academic toppers, competition winners, and student achievements here. Admin can update this message for everyone.",
    badge: "Scholars",
  },
];

const DEFAULT_SCHOOL_CONTACT = [
  {
    title: "Contact: +91 98765 43210",
    subtitle: "Email: info@cnspaunta.edu",
    badge: "Office: 9:00 AM - 3:00 PM",
  },
];

const DEFAULT_SCHOOL_CONTENT = [
  {
    title: "School content",
    subtitle: "CAMBRIDGE NATIONAL SCHOOL PAUNTA is designed to keep everyday school communication, academics, and administration in one place.\n\nAdmin users can manage shared school data directly inside the app. Facilities, events, student profiles, attendance records, timetable entries, gallery photos, feedback, and admission enquiries are visible across the matching screens.\n\nTeachers can manage attendance and homework. Students can track attendance percentage, assigned homework, marks, and profile information.\n\nGallery image updates are shared across connected devices so changes can be seen by all users.",
    badge: "CNS",
  },
];

function parseArrayJson(raw) {
  try {
    const parsed = JSON.parse(raw || "[]");
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

function usernameFromEmail(email) {
  const raw = String(email || "").trim().toLowerCase();
  if (!raw) return "";
  return raw.split("@")[0] || "";
}

function normalizePhoneNumber(raw) {
  const value = String(raw || "").trim();
  if (!value) return "";
  const hasPlus = value.startsWith("+");
  const digits = value.replace(/\D/g, "");
  if (!digits) return "";
  if (hasPlus && digits.length >= 10 && digits.length <= 15) return `+${digits}`;
  if (digits.length === 10) return `+91${digits}`;
  if (digits.length >= 11 && digits.length <= 15) return `+${digits}`;
  return "";
}

function verificationContactMatches(user, verificationContact) {
  const rawContact = String(verificationContact || "").trim();
  const normalizedContact = normalizePhoneNumber(rawContact);
  const userMobile = normalizePhoneNumber(user.mobileNumber);
  const guardianContact = String(user.guardianContact || "").trim();
  const guardianMobile = normalizePhoneNumber(guardianContact);
  const role = String(user.role || "").trim().toUpperCase();
  if (!rawContact || !role) return false;
  if (role === "STUDENT") {
    return normalizedContact && normalizedContact === userMobile ||
      rawContact.toLowerCase() === guardianContact.toLowerCase() ||
      (normalizedContact && normalizedContact === guardianMobile);
  }
  if (role === "TEACHER") {
    return normalizedContact && normalizedContact === userMobile;
  }
  return false;
}

function titleCaseUsername(username) {
  return username
    .split(/[._-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ")
    .trim() || username;
}

async function verifyAdminRequest(req) {
  const authHeader = req.get("authorization") || "";
  if (!authHeader.startsWith("Bearer ")) {
    throw new functions.https.HttpsError("unauthenticated", "Missing bearer token.");
  }
  const token = authHeader.slice("Bearer ".length).trim();
  const decoded = await admin.auth().verifyIdToken(token);
  if (!decoded || decoded.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin access required.");
  }
  return decoded;
}

async function isSharedStateAdmin(username) {
  const normalizedUsername = String(username || "").trim().toLowerCase();
  if (!normalizedUsername) return false;

  if (normalizedUsername === "admin") {
    return true;
  }

  const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
  const sharedSnap = await sharedRef.get();
  const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
  const sharedUsers = parseArrayJson(sharedData && sharedData.users);
  return sharedUsers.some((user) => {
    const candidateUsername = String(user.username || "").trim().toLowerCase();
    const candidateRole = String(user.role || "").trim().toUpperCase();
    const approved = user.approved !== false;
    return candidateUsername === normalizedUsername && candidateRole === "ADMIN" && approved;
  });
}

exports.ensureAdminSessionAccess = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }

    const authHeader = req.get("authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ok: false, error: "Missing bearer token."});
      return;
    }

    const token = authHeader.slice("Bearer ".length).trim();
    const decoded = await admin.auth().verifyIdToken(token);
    const username = usernameFromEmail(decoded.email);
    if (!await isSharedStateAdmin(username)) {
      res.status(403).json({ok: false, error: "This user is not an app admin."});
      return;
    }

    const userRecord = await admin.auth().getUser(decoded.uid);
    const currentClaims = userRecord.customClaims || {};
    if (currentClaims.admin === true) {
      res.json({ok: true, updated: false});
      return;
    }

    await admin.auth().setCustomUserClaims(decoded.uid, {
      ...currentClaims,
      admin: true,
    });
    res.json({ok: true, updated: true});
  } catch (error) {
    logger.error("ensureAdminSessionAccess failed", error);
    const message = error && error.message ? error.message : "Unable to grant admin session access.";
    res.status(500).json({ok: false, error: message});
  }
});

exports.resetPasswordWithPhoneOtp = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }

    const authHeader = req.get("authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ok: false, error: "Missing bearer token."});
      return;
    }

    const token = authHeader.slice("Bearer ".length).trim();
    const decoded = await admin.auth().verifyIdToken(token);
    const provider = decoded.firebase && decoded.firebase.sign_in_provider;
    const verifiedPhone = normalizePhoneNumber(decoded.phone_number);
    if (provider !== "phone" || !verifiedPhone) {
      res.status(403).json({ok: false, error: "Phone verification is required."});
      return;
    }

    const body = typeof req.body === "object" && req.body ? req.body : {};
    const username = String(body.username || "").trim().toLowerCase();
    const role = String(body.role || "").trim().toUpperCase();
    const mobileNumber = normalizePhoneNumber(body.mobileNumber);
    const newPassword = String(body.newPassword || "").trim();

    if (!username || !role || !mobileNumber || newPassword.length < 6) {
      res.status(400).json({ok: false, error: "Username, role, mobile number, and password are required."});
      return;
    }
    if (mobileNumber !== verifiedPhone) {
      res.status(403).json({ok: false, error: "Verified phone number does not match the request."});
      return;
    }

    const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const sharedSnap = await sharedRef.get();
    const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
    const sharedUsers = parseArrayJson(sharedData && sharedData.users);
    const index = sharedUsers.findIndex((user) => {
      return String(user.username || "").trim().toLowerCase() === username &&
        String(user.role || "").trim().toUpperCase() === role &&
        user.approved !== false &&
        normalizePhoneNumber(user.mobileNumber) === verifiedPhone;
    });
    if (index < 0) {
      res.status(404).json({ok: false, error: "No approved account matches this username and mobile number."});
      return;
    }

    const email = `${username}@cns-paunta.app`;
    const authUser = await admin.auth().getUserByEmail(email);
    await admin.auth().updateUser(authUser.uid, {password: newPassword});

    const updatedUsers = [...sharedUsers];
    updatedUsers[index] = {
      ...updatedUsers[index],
      password: "__activated__",
      mobileNumber: verifiedPhone,
    };

    const timestamp = Date.now();
    await sharedRef.set({
      users: JSON.stringify(updatedUsers),
      updatedAt: timestamp,
    }, {merge: true});
    await admin.firestore().collection(PERSONAL_EVENTS_COLLECTION).doc(`event_${timestamp}_password_reset_${username}`).set({
      id: `event_${timestamp}_password_reset_${username}`,
      type: "password_reset",
      title: "Password reset",
      message: "Your password was updated after OTP verification.",
      actor: "Password Recovery",
      role: "",
      className: "",
      targetUsername: username,
      sourceDeviceId: "server_password_reset",
      timestamp,
    });

    res.json({ok: true});
  } catch (error) {
    logger.error("resetPasswordWithPhoneOtp failed", error);
    res.status(500).json({ok: false, error: error.message || "Password reset failed"});
  }
});

// A school administrator creates the record first. This endpoint only turns a
// phone-verified, unactivated student/teacher record into a Firebase account.
exports.activateAccountWithPhoneOtp = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }
    const authHeader = req.get("authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ok: false, error: "Missing bearer token."});
      return;
    }
    const decoded = await admin.auth().verifyIdToken(authHeader.slice("Bearer ".length).trim());
    const provider = decoded.firebase && decoded.firebase.sign_in_provider;
    const verifiedPhone = normalizePhoneNumber(decoded.phone_number);
    if (provider !== "phone" || !verifiedPhone) {
      res.status(403).json({ok: false, error: "Phone verification is required."});
      return;
    }
    const body = typeof req.body === "object" && req.body ? req.body : {};
    const username = String(body.username || "").trim().toLowerCase();
    const role = String(body.role || "").trim().toUpperCase();
    const requestedPhone = normalizePhoneNumber(body.mobileNumber);
    const password = String(body.newPassword || "").trim();
    if (!username || !["STUDENT", "TEACHER"].includes(role) || !requestedPhone || password.length < 6) {
      res.status(400).json({ok: false, error: "A provisioned ID, role, registered mobile number, and password are required."});
      return;
    }
    if (requestedPhone !== verifiedPhone) {
      res.status(403).json({ok: false, error: "Verified phone number does not match the request."});
      return;
    }
    const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const sharedSnap = await sharedRef.get();
    const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
    const users = parseArrayJson(sharedData.users);
    const index = users.findIndex((user) =>
      String(user.username || "").trim().toLowerCase() === username &&
      String(user.role || "").trim().toUpperCase() === role &&
      user.approved !== false &&
      normalizePhoneNumber(user.mobileNumber) === verifiedPhone &&
      !String(user.password || "").trim()
    );
    if (index < 0) {
      res.status(404).json({ok: false, error: "No provisioned account is awaiting activation for this ID and mobile number."});
      return;
    }
    const email = `${username}@cns-paunta.app`;
    try {
      await admin.auth().getUserByEmail(email);
      res.status(409).json({ok: false, error: "This account is already activated. Use login or Forgot Password."});
      return;
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
    await admin.auth().createUser({email, password, phoneNumber: verifiedPhone});
    const updatedUsers = [...users];
    // This legacy shared-state model uses the field as an activation marker.
    // Never copy the new password into Firestore.
    updatedUsers[index] = {...updatedUsers[index], password: "__activated__", mobileNumber: verifiedPhone};
    await sharedRef.set({users: JSON.stringify(updatedUsers), updatedAt: Date.now()}, {merge: true});
    res.json({ok: true});
  } catch (error) {
    logger.error("activateAccountWithPhoneOtp failed", error);
    res.status(500).json({ok: false, error: error.message || "Account activation failed."});
  }
});

exports.createPasswordResetRequest = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }

    const body = typeof req.body === "object" && req.body ? req.body : {};
    const username = String(body.username || "").trim().toLowerCase();
    const role = String(body.role || "").trim().toUpperCase();
    const verificationContact = String(body.verificationContact || "").trim();
    if (!username || !role || role === "ADMIN") {
      res.status(400).json({ok: false, error: "Username and role are required."});
      return;
    }

    const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const sharedSnap = await sharedRef.get();
    const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
    const sharedUsers = parseArrayJson(sharedData && sharedData.users);
    const matchedUser = sharedUsers.find((user) =>
      String(user.username || "").trim().toLowerCase() === username &&
      String(user.role || "").trim().toUpperCase() === role &&
      user.approved !== false
    );
    if (!matchedUser) {
      res.status(404).json({ok: false, error: "No approved account matches this username and role."});
      return;
    }

    const normalizedMatchedMobile = normalizePhoneNumber(matchedUser.mobileNumber);
    const normalizedRequestedMobile = normalizePhoneNumber(verificationContact);
    const request = {
      username,
      role,
      fullName: String(matchedUser.fullName || "").trim(),
      verificationContact: verificationContact || matchedUser.mobileNumber || "",
      mobileNumber: normalizedMatchedMobile || normalizedRequestedMobile,
      requestedAt: Date.now(),
      source: "app",
    };
    await admin.firestore().collection(PASSWORD_RESET_REQUESTS_COLLECTION).doc(username).set(request, {merge: true});
    res.json({ok: true});
  } catch (error) {
    logger.error("createPasswordResetRequest failed", error);
    res.status(500).json({ok: false, error: error.message || "Unable to create reset request."});
  }
});

exports.passwordResetRequestNotifier = functions.firestore
  .document(`${PASSWORD_RESET_REQUESTS_COLLECTION}/{username}`)
  .onCreate(async (snap) => {
    const data = snap.data() || {};
    const username = String(data.username || "").trim().toLowerCase();
    const role = String(data.role || "").trim().toLowerCase();
    const fullName = String(data.fullName || "").trim() || username;
    try {
      await admin.messaging().send({
        topic: roleTopic("admin"),
        android: {
          priority: "high",
          notification: {
            channelId: "cns_realtime_updates",
          },
        },
        notification: {
          title: "Password reset request",
          body: `${fullName} requested a ${role} password reset.`,
        },
        data: {
          title: "Password reset request",
          message: `${fullName} requested a ${role} password reset.`,
          event_type: "password_reset_request",
          role: "admin",
          class_name: "",
          target: "",
          username,
        },
      });
    } catch (error) {
      logger.error("passwordResetRequestNotifier failed", error);
    }
  });

exports.adminResetUserPassword = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }
    const decoded = await verifyAdminRequest(req);
    const adminUsername = usernameFromEmail(decoded.email);

    const body = typeof req.body === "object" && req.body ? req.body : {};
    const username = String(body.username || "").trim().toLowerCase();
    const role = String(body.role || "").trim().toUpperCase();
    const temporaryPassword = String(body.temporaryPassword || "").trim();
    if (!username || !role || temporaryPassword.length < 6 || role === "ADMIN") {
      res.status(400).json({ok: false, error: "Username, role, and a temporary password are required."});
      return;
    }

    const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const sharedSnap = await sharedRef.get();
    const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
    const sharedUsers = parseArrayJson(sharedData && sharedData.users);
    const index = sharedUsers.findIndex((user) =>
      String(user.username || "").trim().toLowerCase() === username &&
      String(user.role || "").trim().toUpperCase() === role &&
      user.approved !== false
    );
    if (index < 0) {
      res.status(404).json({ok: false, error: "Approved user not found."});
      return;
    }

    const email = `${username}@cns-paunta.app`;
    const authUser = await admin.auth().getUserByEmail(email);
    await admin.auth().updateUser(authUser.uid, {password: temporaryPassword});

    const updatedUsers = [...sharedUsers];
    updatedUsers[index] = {
      ...updatedUsers[index],
      password: "__activated__",
      forcePasswordChange: true,
    };

    const timestamp = Date.now();
    await sharedRef.set({
      users: JSON.stringify(updatedUsers),
      updatedAt: timestamp,
    }, {merge: true});
    await admin.firestore().collection(PERSONAL_EVENTS_COLLECTION).doc(`event_${timestamp}_admin_password_reset_${username}`).set({
      id: `event_${timestamp}_admin_password_reset_${username}`,
      type: "password_reset",
      title: "Temporary password ready",
      message: "Admin has issued a temporary password for your account. Log in and change it immediately.",
      actor: adminUsername || "Admin",
      role: "",
      className: "",
      targetUsername: username,
      sourceDeviceId: "server_admin_password_reset",
      timestamp,
    });

    await admin.firestore().collection(PASSWORD_RESET_REQUESTS_COLLECTION).doc(username).delete().catch(() => null);
    res.json({ok: true});
  } catch (error) {
    logger.error("adminResetUserPassword failed", error);
    res.status(500).json({ok: false, error: error.message || "Admin password reset failed"});
  }
});

exports.syncAuthUsersToRegistrationRequests = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }
    await verifyAdminRequest(req);

    const sharedRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const sharedSnap = await sharedRef.get();
    const sharedData = sharedSnap.exists ? sharedSnap.data() : {};
    const sharedUsers = parseArrayJson(sharedData && sharedData.users);
    const sharedUsernames = new Set(
      sharedUsers
        .map((user) => String(user.username || "").trim().toLowerCase())
        .filter(Boolean)
    );

    const existingRequestsSnap = await admin.firestore().collection(REGISTRATION_REQUESTS_COLLECTION).get();
    const existingRequestUsernames = new Set(
      existingRequestsSnap.docs
        .map((doc) => doc.id.trim().toLowerCase())
        .filter(Boolean)
    );

    let imported = 0;
    let nextPageToken;
    do {
      const page = await admin.auth().listUsers(1000, nextPageToken);
      nextPageToken = page.pageToken;
      for (const userRecord of page.users) {
        const username = usernameFromEmail(userRecord.email);
        if (!username || sharedUsernames.has(username) || existingRequestUsernames.has(username)) {
          continue;
        }
        const claims = userRecord.customClaims || {};
        if (claims.admin === true) {
          continue;
        }
        const roleClaim = String(claims.role || "").trim().toLowerCase();
        const role = roleClaim === "teacher" ? "TEACHER" : "STUDENT";
        const className = String(claims.className || "").trim();
        const request = {
          username,
          authUid: userRecord.uid,
          role,
          fullName: String(userRecord.displayName || "").trim() || titleCaseUsername(username),
          className,
          subject: "",
          rollNumber: "",
          guardianContact: "",
          notes: "Imported from registered users. Review and approve from the app.",
          mobileNumber: String(userRecord.phoneNumber || "").trim(),
          source: "auth_import",
          needsReview: !className,
          createdAt: Date.now(),
        };
        await admin.firestore().collection(REGISTRATION_REQUESTS_COLLECTION).doc(username).set(request, {merge: true});
        existingRequestUsernames.add(username);
        imported += 1;
      }
    } while (nextPageToken);

    res.json({ok: true, imported});
  } catch (error) {
    logger.error("syncAuthUsersToRegistrationRequests failed", error);
    const message = error && error.message ? error.message : "Sync failed";
    const status = error && error.code === "permission-denied" ? 403 : 500;
    res.status(status).json({ok: false, error: message});
  }
});

function buildDownloadUrl(bucketName, filePath, token) {
  const encoded = encodeURIComponent(filePath);
  return `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encoded}?alt=media&token=${token}`;
}

function galleryTitleFromPath(filePath) {
  const base = filePath.split("/").pop() || "Gallery image";
  const noExt = base.replace(/\.[^/.]+$/, "");
  const cleaned = noExt
    .replace(/^\d+_/, "")
    .replace(/[_-]+/g, " ")
    .trim();
  return cleaned ? cleaned.replace(/\b\w/g, (c) => c.toUpperCase()) : "Gallery image";
}

function recoverySecret() {
  return String(process.env[RECOVERY_SECRET_ENV] || "").trim();
}

function isRecoveryRateLimited(req) {
  const key = req.get("x-forwarded-for") || req.ip || "unknown";
  const now = Date.now();
  const windowMs = 60 * 1000;
  const maxAttempts = 5;
  const attempts = (recoveryAttempts.get(key) || []).filter((ts) => now - ts < windowMs);
  attempts.push(now);
  recoveryAttempts.set(key, attempts);
  return attempts.length > maxAttempts;
}

exports.recoverGalleryFromStorage = functions.https.onRequest(async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ok: false, error: "Use POST."});
      return;
    }
    if (isRecoveryRateLimited(req)) {
      res.status(429).json({ok: false, error: "Too many requests."});
      return;
    }
    const expectedSecret = recoverySecret();
    if (!expectedSecret) {
      res.status(503).json({ok: false, error: "Recovery is not configured."});
      return;
    }
    const secret = req.query.secret || req.get("x-recovery-secret");
    if (secret !== expectedSecret) {
      res.status(403).json({ok: false, error: "Forbidden"});
      return;
    }

    const bucket = admin.storage().bucket();
    const [files] = await bucket.getFiles({prefix: "gallery/"});
    const imageFiles = files.filter((file) => {
      const name = file.name || "";
      return (
        !name.endsWith("/") &&
        /\.(png|jpg|jpeg|webp)$/i.test(name)
      );
    });

    const recovered = [];
    for (const file of imageFiles) {
      const [metadata] = await file.getMetadata();
      const existingToken = metadata.metadata && metadata.metadata.firebaseStorageDownloadTokens;
      const token = existingToken || crypto.randomUUID();
      if (!existingToken) {
        await file.setMetadata({
          metadata: {
            ...(metadata.metadata || {}),
            firebaseStorageDownloadTokens: token,
          },
        });
      }
      recovered.push({
        id: Date.now() + recovered.length,
        title: galleryTitleFromPath(file.name),
        subtitle: "Recovered from backup storage",
        imageUrl: buildDownloadUrl(bucket.name, file.name, token),
        imageResName: "",
      });
    }

    const docRef = admin.firestore().collection(SHARED_STATE_COLLECTION).doc(SHARED_STATE_DOCUMENT);
    const snap = await docRef.get();
    const data = snap.exists ? snap.data() : {};
    const existingGallery = parseArrayJson(data.gallery);
    const existingUrls = new Set(existingGallery.map((it) => (it.imageUrl || "").trim()).filter(Boolean));
    const merged = [...existingGallery];
    for (const item of recovered) {
      if (!existingUrls.has(item.imageUrl)) merged.push(item);
    }

    const existingScholars = parseArrayJson(data[KEY_OUR_SCHOLARS]);
    const existingContact = parseArrayJson(data[KEY_SCHOOL_CONTACT]);
    const existingContent = parseArrayJson(data[KEY_SCHOOL_CONTENT]);

    const scholarsToSave = existingScholars.length > 0 ? existingScholars : DEFAULT_OUR_SCHOLARS;
    const contactToSave = existingContact.length > 0 ? existingContact : DEFAULT_SCHOOL_CONTACT;
    const contentToSave = existingContent.length > 0 ? existingContent : DEFAULT_SCHOOL_CONTENT;

    await docRef.set(
      {
        gallery: JSON.stringify(merged),
        [KEY_OUR_SCHOLARS]: JSON.stringify(scholarsToSave),
        [KEY_SCHOOL_CONTACT]: JSON.stringify(contactToSave),
        [KEY_SCHOOL_CONTENT]: JSON.stringify(contentToSave),
        updatedAt: Date.now(),
        last_event: JSON.stringify({
          id: `event_${Date.now()}_server_recovery`,
          type: "content_recovered",
          title: "School content recovered",
          message: `${recovered.length} gallery image(s) recovered and school details restored.`,
          actor: "Server Recovery",
          role: "",
          className: "",
          targetUsername: "",
          sourceDeviceId: "server_recovery",
          timestamp: Date.now(),
        }),
      },
      {merge: true}
    );

    res.json({
      ok: true,
      scanned: imageFiles.length,
      recovered: recovered.length,
      mergedCount: merged.length,
      scholarsCount: scholarsToSave.length,
      contactCount: contactToSave.length,
      contentCount: contentToSave.length,
    });
  } catch (error) {
    logger.error("recoverGalleryFromStorage failed", error);
    res.status(500).json({ok: false, error: error.message || "Recovery failed"});
  }
});
