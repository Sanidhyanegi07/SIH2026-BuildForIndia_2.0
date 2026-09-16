const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

const serviceAccount = require('./serviceAccountKey.json');

initializeApp({
  credential: cert(serviceAccount)
});

const targetUid = "bKkjL4ObTDVfh8P9BZWq7nzgjdf2";

getAuth()
  .setCustomUserClaims(targetUid, { admin: true })
  .then(() => {
    console.log(`Success! Admin claim successfully added to UID: ${targetUid}`);
    console.log("The user must log out and log back in on their Android device for the new claims to take effect.");
    process.exit(0);
  })
  .catch((error) => {
    console.error("Error setting custom claims:", error);
    process.exit(1);
  });
