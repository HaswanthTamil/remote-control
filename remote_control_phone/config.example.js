/* Copy this file to config.js (same folder) and fill in your real values.
 * config.js is gitignored, so it never reaches the repository.
 *
 * The one-time pairing token is a secret: it lets a brand-new device register
 * with the relay. The relay only accepts a new public key while this token is
 * presented; it is never sent again once the phone is paired.
 */
export const SERVER_URL = "wss://remote-control-lmxu.vercel.app/";

export const PAIR_TOKEN = "";

export const PRIVATE_KEY_STORAGE = "rc:phone:private_key";
export const PUBLIC_KEY_STORAGE = "rc:phone:public_key";
export const PAIRED_STORAGE = "rc:phone:paired";