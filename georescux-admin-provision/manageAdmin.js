/**
 * GeoRescuX Admin Management CLI
 * 
 * Usage:
 *   node manageAdmin.js grant <uid>   - Grants admin custom claim (admin: true)
 *   node manageAdmin.js revoke <uid>  - Revokes admin custom claim (admin: false)
 *   node manageAdmin.js verify <uid>  - Inspects user's custom claims
 *
 * Default target UID if omitted: oJDDWyVlmNPMyfNSFQqptcActiF2
 */

const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

const serviceAccount = require('./serviceAccountKey.json');

initializeApp({
  credential: cert(serviceAccount)
});

const DEFAULT_ADMIN_UID = "oJDDWyVlmNPMyfNSFQqptcActiF2";
const command = process.argv[2] || "verify";
const targetUid = process.argv[3] || DEFAULT_ADMIN_UID;

async function run() {
  const auth = getAuth();

  switch (command.toLowerCase()) {
    case 'grant':
      await auth.setCustomUserClaims(targetUid, { admin: true });
      console.log(`[GeoRescuX Admin] Granted admin: true claim to UID: ${targetUid}`);
      break;

    case 'revoke':
      await auth.setCustomUserClaims(targetUid, { admin: false });
      console.log(`[GeoRescuX Admin] Revoked admin claim (set admin: false) for UID: ${targetUid}`);
      break;

    case 'verify':
    default:
      const user = await auth.getUser(targetUid);
      console.log(`[GeoRescuX Admin] User UID: ${user.uid}`);
      console.log(`[GeoRescuX Admin] Email: ${user.email || 'N/A'}`);
      console.log(`[GeoRescuX Admin] Custom Claims:`, user.customClaims || {});
      const isAdmin = user.customClaims && user.customClaims.admin === true;
      console.log(`[GeoRescuX Admin] Admin Status: ${isAdmin ? 'ACTIVE ADMIN (admin: true)' : 'NORMAL USER'}`);
      break;
  }
}

run()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error(`[GeoRescuX Admin] Error executing command '${command}':`, err);
    process.exit(1);
  });
