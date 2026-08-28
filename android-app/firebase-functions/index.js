const admin = require("firebase-admin");
const {onDocumentWritten} = require("firebase-functions/v2/firestore");

admin.initializeApp();

function normalizeTopic(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function userTopic(username) {
  return `user_${normalizeTopic(username)}`;
}

function roleTopic(role) {
  return `role_${normalizeTopic(role)}`;
}

function classTopic(className) {
  return `class_${normalizeTopic(className)}`;
}

function audienceTopic(role, className) {
  return `audience_${normalizeTopic(role)}_${normalizeTopic(className)}`;
}

function buildTopicTargets(syncEvent) {
  const type = String(syncEvent.type || "");
  const role = normalizeTopic(syncEvent.role || "");
  const className = normalizeTopic(syncEvent.className || "");
  const targetUsername = normalizeTopic(syncEvent.targetUsername || "");

  if (type === "announcement_publish" || type === "announcement_update") {
    return ["schoolhub_all"];
  }

  if (targetUsername) {
    return [userTopic(targetUsername)];
  }

  if (role && className) {
    return [audienceTopic(role, className)];
  }

  if (className) {
    return [classTopic(className)];
  }

  if (role) {
    return [roleTopic(role)];
  }

  return [];
}

exports.broadcastSharedStateEvent = onDocumentWritten("shared_state/schoolhub", async (event) => {
  const afterData = event.data.after.exists ? event.data.after.data() : null;
  if (!afterData || !afterData.last_event) {
    return;
  }

  let syncEvent;
  try {
    syncEvent = JSON.parse(afterData.last_event);
  } catch (error) {
    console.error("Invalid last_event payload", error);
    return;
  }

  if (!syncEvent || !syncEvent.id || !syncEvent.title || !syncEvent.message) {
    return;
  }

  const beforeData = event.data.before.exists ? event.data.before.data() : null;
  if (beforeData && beforeData.last_event === afterData.last_event) {
    return;
  }

  const topics = buildTopicTargets(syncEvent);
  if (topics.length === 0) {
    return;
  }

  await Promise.all(
    topics.map((topic) =>
      admin.messaging().send({
        topic,
        notification: {
          title: syncEvent.title,
          body: syncEvent.message
        },
        data: {
          eventId: String(syncEvent.id || ""),
          type: String(syncEvent.type || ""),
          title: String(syncEvent.title || ""),
          message: String(syncEvent.message || ""),
          targetUsername: String(syncEvent.targetUsername || "")
        },
        android: {
          priority: "high",
          notification: {
            channelId: "cns_realtime_updates"
          }
        }
      })
    )
  );
});
