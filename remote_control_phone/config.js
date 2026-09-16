// export const firebaseConfig = {
//   apiKey: "YOUR_API_KEY",
//   authDomain: "YOUR_PROJECT.firebaseapp.com",
//   databaseURL: "https://YOUR_PROJECT-default-rtdb.firebaseio.com",
//   projectId: "YOUR_PROJECT_ID",
//   storageBucket: "YOUR_PROJECT.firebasestorage.app",
//   messagingSenderId: "YOUR_SENDER_ID",
//   appId: "YOUR_APP_ID"
// };

export const SERVER_URL = "ws://localhost:3000";
// export const SERVER_URL = "wss://relay-server-remote-control.onrender.com/";
// export const SERVER_URL = "wss://remote-control-lmxu.vercel.app/";


// One-time pairing token. The relay only accepts a brand-new public key if
// this token is presented; it is never sent again once this phone is paired.
// You can blank this out (delete the value) after the first pairing.
export const PAIR_TOKEN = "naanga4PeruEngalukkuBayamKidayathu";

export const PRIVATE_KEY_STORAGE = "rc:phone:private_key";
export const PUBLIC_KEY_STORAGE = "rc:phone:public_key";
export const PAIRED_STORAGE = "rc:phone:paired";
