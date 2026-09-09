const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

const serviceAccount = require('./serviceAccountKey.json');

initializeApp({
  credential: cert(serviceAccount)
});

const targetUid = process.argv[2] || "oJDDWyVlmNPMyfNSFQqptcActiF2";

getAuth()
  .setCustomUserClaims(targetUid, { admin: false })
  .then(() => {
    console.log(`Success! Admin claim successfully removed from UID: ${targetUid}`);
    console.log("The user must refresh their token or log out and log back in for changes to take effect.");
    process.exit(0);
  })
  .catch((error) => {
    console.error("Error removing custom claims:", error);
    process.exit(1);
  });
