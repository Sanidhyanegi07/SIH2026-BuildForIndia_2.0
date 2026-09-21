const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

const serviceAccount = require('./serviceAccountKey.json');
initializeApp({ credential: cert(serviceAccount) });

const targetUid = process.argv[2];
if (!targetUid) {
  console.error('Usage: node makeAdmin.js <firebase-user-uid>');
  process.exit(2);
}

getAuth().getUser(targetUid)
  .then((user) => getAuth().setCustomUserClaims(targetUid, {
    ...(user.customClaims || {}),
    admin: true
  }))
  .then(() => {
    console.log(`Admin claim granted to UID: ${targetUid}`);
    console.log('The user must refresh their ID token or sign in again.');
  })
  .catch((error) => {
    console.error('Error setting custom claims:', error.message);
    process.exitCode = 1;
  });
