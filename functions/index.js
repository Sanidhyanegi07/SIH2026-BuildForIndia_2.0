const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * Assigns or removes the admin custom claim on a target UID.
 *
 * Every request must be authenticated. Existing administrators may manage
 * other users; the bootstrap UID may only be initialized by itself. Claims
 * are merged so this function never accidentally removes unrelated claims.
 */
exports.setAdminClaim = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "You must be signed in to manage administrator privileges."
    );
  }

  const targetUid = data?.targetUid;
  const enableAdmin = data?.admin === true;
  if (typeof targetUid !== "string" || targetUid.trim().length === 0) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "The function must be called with a valid 'targetUid'."
    );
  }

  const callerIsAdmin = context.auth.token?.admin === true;
  const isSelfBootstrap = targetUid === context.auth.uid;
  if (!callerIsAdmin && !isSelfBootstrap) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "Only administrators may manage another user's administrator privileges."
    );
  }

  try {
    const user = await admin.auth().getUser(targetUid);
    const claims = { ...(user.customClaims || {}), admin: enableAdmin };
    await admin.auth().setCustomUserClaims(targetUid, claims);
    return { status: "success", targetUid, admin: enableAdmin };
  } catch (error) {
    console.error("Failed to update admin claim", { targetUid, error });
    throw new functions.https.HttpsError("internal", "Failed to update admin privileges.");
  }
});

/** Returns the caller's own role, or an administrator's requested target role. */
exports.getAdminStatus = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be authenticated.");
  }

  const targetUid = data?.targetUid || context.auth.uid;
  if (targetUid !== context.auth.uid && context.auth.token?.admin !== true) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "Only administrators may inspect another user's role."
    );
  }

  try {
    const user = await admin.auth().getUser(targetUid);
    return { uid: user.uid, admin: user.customClaims?.admin === true };
  } catch (error) {
    console.error("Failed to inspect admin status", { targetUid, error });
    throw new functions.https.HttpsError("internal", "Failed to inspect admin status.");
  }
});
