const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * Known bootstrap admin UID provisioned during initial setup.
 * All subsequent admin promotions require existing admin privileges.
 */
const BOOTSTRAP_ADMIN_UID = "oJDDWyVlmNPMyfNSFQqptcActiF2";

/**
 * Assigns or removes the admin custom claim on a target UID.
 * 
 * Authorization rule:
 * - Either the caller is authenticated and already has the admin claim, OR
 * - The target UID is the designated BOOTSTRAP_ADMIN_UID being initialized.
 */
exports.setAdminClaim = functions.https.onCall(async (data, context) => {
  const targetUid = data.targetUid;
  const enableAdmin = data.admin === true;

  if (!targetUid || typeof targetUid !== "string") {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "The function must be called with a valid 'targetUid'."
    );
  }

  const isBootstrapTarget = targetUid === BOOTSTRAP_ADMIN_UID;
  const callerIsAdmin = context.auth && context.auth.token && context.auth.token.admin === true;

  if (!isBootstrapTarget && !callerIsAdmin) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "Only authenticated administrators may manage admin privileges."
    );
  }

  try {
    await admin.auth().setCustomUserClaims(targetUid, { admin: enableAdmin });
    return {
      status: "success",
      targetUid,
      admin: enableAdmin,
      message: `Admin claim set to ${enableAdmin} for user ${targetUid}`
    };
  } catch (error) {
    throw new functions.https.HttpsError(
      "internal",
      "Failed to set custom user claims: " + error.message
    );
  }
});

/**
 * Verifies custom claims for a given UID.
 */
exports.getAdminStatus = functions.https.onCall(async (data, context) => {
  const targetUid = data.targetUid || context.auth?.uid;
  if (!targetUid) {
    throw new functions.https.HttpsError("unauthenticated", "User must be authenticated.");
  }

  try {
    const user = await admin.auth().getUser(targetUid);
    return {
      uid: user.uid,
      admin: user.customClaims?.admin === true
    };
  } catch (error) {
    throw new functions.https.HttpsError("internal", error.message);
  }
});
