# Admin Provisioning Guide

In GeoRescuX, Admin authorization is represented securely using **Firebase Auth Custom Claims**. This ensures that the admin identity is cryptographically signed and verified by Firebase, preventing unauthorized access even if the Android app is tampered with.

Because custom claims provide true security, they **cannot** be faked or assigned from the Android client application.

To provision the first admin, or any subsequent admins, you must run a script utilizing the Firebase Admin SDK in a trusted environment (like your local machine or a secure backend).

## How to mint an Admin

Below is a Node.js script to provision an admin.

### 1. Prerequisites
- Node.js installed.
- A Firebase Service Account Key JSON file from your Firebase Project Console (Project Settings > Service Accounts > Generate new private key).
- The `firebase-admin` npm package.

```bash
mkdir georescux-admin-provision
cd georescux-admin-provision
npm init -y
npm install firebase-admin
```

### 2. The Provisioning Script (`makeAdmin.js`)

Save the following code to `makeAdmin.js`, replacing the placeholders with your actual service account path and the target user's UID.

```javascript
const admin = require("firebase-admin");

// Path to the downloaded service account key
const serviceAccount = require("./path/to/serviceAccountKey.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// The UID of the user you want to make an admin. 
// You can find this in the Firebase Console under Authentication.
const targetUid = "INSERT_UID_HERE";

admin.auth().setCustomUserClaims(targetUid, { admin: true })
  .then(() => {
    console.log(`Success! Admin claim successfully added to UID: ${targetUid}`);
    console.log("The user must log out and log back in on their Android device for the new claims to take effect.");
    process.exit(0);
  })
  .catch((error) => {
    console.error("Error setting custom claims:", error);
    process.exit(1);
  });
```

### 3. Run the Script

```bash
node makeAdmin.js
```

After running this script successfully, when the target user logs in to the GeoRescuX Android app, they will automatically be routed to the Admin Portal!
