// Ed25519 device identity for the phone (WebCrypto).
//
// The private key never leaves this browser/device; it is stored as a PKCS#8
// DER blob in localStorage. The public key is exchanged at registration and
// the relay verifies our signature over its fresh per-connection nonce.
//
// device_id = sha256(raw public key bytes).hexdigest() - computed identically
// by Node (relay) and Python (laptop), so it is also the key fingerprint.

import {
  PRIVATE_KEY_STORAGE,
  PUBLIC_KEY_STORAGE,
  PAIRED_STORAGE,
} from "../config.js";

function bytesToHex(bytes) {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bytes;
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function base64ToBytes(b64) {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export async function loadKeyPair() {
  const privB64 = localStorage.getItem(PRIVATE_KEY_STORAGE);
  const pubHex = localStorage.getItem(PUBLIC_KEY_STORAGE);

  if (privB64 && pubHex) {
    try {
      const privateKey = await crypto.subtle.importKey(
        "pkcs8",
        base64ToBytes(privB64),
        { name: "Ed25519" },
        true,
        ["sign"],
      );
      const publicKey = await crypto.subtle.importKey(
        "raw",
        hexToBytes(pubHex),
        { name: "Ed25519" },
        true,
        ["verify"],
      );
      return { privateKey, publicKey };
    } catch (error) {
      console.warn("Could not restore keypair, generating a fresh one.", error);
    }
  }

  const keyPair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]);

  const privDer = await crypto.subtle.exportKey("pkcs8", keyPair.privateKey);
  const pubRaw = await crypto.subtle.exportKey("raw", keyPair.publicKey);

  localStorage.setItem(
    PRIVATE_KEY_STORAGE,
    bytesToBase64(new Uint8Array(privDer)),
  );
  localStorage.setItem(PUBLIC_KEY_STORAGE, bytesToHex(new Uint8Array(pubRaw)));

  return keyPair;
}

export async function publicKeyHex(keyPair) {
  const raw = new Uint8Array(
    await crypto.subtle.exportKey("raw", keyPair.publicKey),
  );
  return bytesToHex(raw);
}

export async function deviceId(keyPair) {
  const raw = new Uint8Array(
    await crypto.subtle.exportKey("raw", keyPair.publicKey),
  );
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", raw));
  return bytesToHex(digest);
}

export async function sign(keyPair, message) {
  const data = new TextEncoder().encode(message);
  const signature = new Uint8Array(
    await crypto.subtle.sign("Ed25519", keyPair.privateKey, data),
  );
  return bytesToHex(signature);
}

export function isPaired(relayHost) {
  return localStorage.getItem(PAIRED_STORAGE + ":" + relayHost) === "1";
}

export function markPaired(relayHost, deviceIdValue) {
  localStorage.setItem(PAIRED_STORAGE + ":" + relayHost, deviceIdValue || "1");
}
