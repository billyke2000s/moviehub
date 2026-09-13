import assert from "node:assert/strict";
import test from "node:test";

import {
  decryptValue,
  encryptValue,
  hashPassword,
  sha256Hex,
  timingSafeEqual,
  verifyPassword,
} from "../src/index.js";

test("password hashes are salted and verifiable", async () => {
  const first = await hashPassword("a genuinely long password");
  const second = await hashPassword("a genuinely long password");
  assert.notEqual(first, second);
  assert.equal(await verifyPassword("a genuinely long password", first), true);
  assert.equal(await verifyPassword("wrong password", first), false);
});

test("credential encryption round-trips without exposing plaintext", async () => {
  const env = { ENC_KEY: "independent encryption key with enough entropy 1234" };
  const encrypted = await encryptValue(env, "private-api-token");
  assert.equal(encrypted.includes("private-api-token"), false);
  assert.equal(await decryptValue(env, encrypted), "private-api-token");
  await assert.rejects(
    decryptValue({ ENC_KEY: "a different encryption key entirely 9876" }, encrypted)
  );
});

test("short encryption secrets are rejected", async () => {
  await assert.rejects(encryptValue({ ENC_KEY: "too-short" }, "private-api-token"));
});

test("credentials encrypted by Movie Hub 3.0 remain readable", async () => {
  const secret = "legacy encryption key that is longer than thirty two";
  const raw = new TextEncoder().encode(secret.padEnd(32, "0").slice(0, 32));
  const key = await crypto.subtle.importKey(
    "raw", raw, "AES-GCM", false, ["encrypt"]
  );
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv }, key, new TextEncoder().encode("legacy-token")
  ));
  const hex = (bytes) => [...bytes].map(
    (value) => value.toString(16).padStart(2, "0")
  ).join("");
  assert.equal(
    await decryptValue({ ENC_KEY: secret }, `${hex(iv)}:${hex(ciphertext)}`),
    "legacy-token"
  );
});

test("session tokens are represented by irreversible fixed-length digests", async () => {
  const digest = await sha256Hex("raw-session-token");
  assert.match(digest, /^[a-f0-9]{64}$/);
  assert.notEqual(digest, "raw-session-token");
});

test("constant-time comparison accepts only exact matches", () => {
  assert.equal(timingSafeEqual("abc123", "abc123"), true);
  assert.equal(timingSafeEqual("abc123", "abc124"), false);
  assert.equal(timingSafeEqual("short", "longer"), false);
});
