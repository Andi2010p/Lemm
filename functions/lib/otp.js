'use strict';

const crypto = require('node:crypto');
const admin = require('firebase-admin');
const nodemailer = require('nodemailer');
const { Denied } = require('./social');

const db = () => admin.database();

const TTL_MS = 10 * 60 * 1000;   // a code is valid for 10 minutes
const MAX_SENDS_PER_HOUR = 5;
const MAX_ATTEMPTS = 5;

/** RTDB keys cannot contain . # $ [ ] / — and we never store the raw address as a key anyway. */
const emailKey = (email) => crypto.createHash('sha256').update(String(email).trim().toLowerCase()).digest('hex');

const hashCode = (code, salt) => crypto.createHash('sha256').update(`${salt}:${code}`).digest('hex');

/**
 * Sends a 6-digit code.
 *
 * The Gmail app password lives in a Cloud Functions secret. It used to be compiled into
 * `BuildConfig.MAIL_APP_PASSWORD` and was recoverable from any APK in minutes — which grants access
 * to the whole mailbox, not merely the ability to send from it.
 *
 * The code itself is never stored: only a salted SHA-256 of it, so a database dump does not hand an
 * attacker other people's verification codes.
 */
async function sendOtp({ email, mailUser, mailPass, appName = 'Lemma' }) {
  const key = emailKey(email);
  const ref = db().ref(`otp/${key}`);
  const now = Date.now();

  const existing = (await ref.get()).val() || {};
  const windowStart = existing.windowStart || 0;
  const sends = now - windowStart < 3600e3 ? (existing.sends || 0) : 0;
  if (sends >= MAX_SENDS_PER_HOUR) {
    throw new Denied('resource-exhausted', 'Too many codes requested. Try again later.');
  }

  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  const salt = crypto.randomBytes(16).toString('hex');

  await ref.set({
    hash: hashCode(code, salt),
    salt,
    expiresAt: now + TTL_MS,
    attempts: 0,
    sends: sends + 1,
    windowStart: now - windowStart < 3600e3 ? windowStart : now,
  });

  const transport = nodemailer.createTransport({
    service: 'gmail',
    auth: { user: mailUser, pass: mailPass },
  });

  await transport.sendMail({
    from: `${appName} <${mailUser}>`,
    to: email,
    subject: `${appName} verification code`,
    text: `Your ${appName} code is ${code}. It expires in 10 minutes.`,
    html: `<p>Your <b>${appName}</b> verification code is:</p>
           <p style="font-size:28px;letter-spacing:6px;font-weight:700">${code}</p>
           <p style="color:#666">It expires in 10 minutes. If you didn't ask for it, ignore this email.</p>`,
  });

  return { ok: true, expiresInMs: TTL_MS };
}

/** Verifies and burns the code. Constant-time compare; limited attempts; single use. */
async function verifyOtp({ email, code }) {
  const key = emailKey(email);
  const ref = db().ref(`otp/${key}`);
  const rec = (await ref.get()).val();

  if (!rec) throw new Denied('not-found', 'Request a code first.');
  if (Date.now() > rec.expiresAt) { await ref.remove(); throw new Denied('deadline-exceeded', 'That code expired.'); }
  if ((rec.attempts || 0) >= MAX_ATTEMPTS) { await ref.remove(); throw new Denied('resource-exhausted', 'Too many attempts.'); }

  const candidate = hashCode(String(code).trim(), rec.salt);
  const ok = crypto.timingSafeEqual(Buffer.from(candidate, 'hex'), Buffer.from(rec.hash, 'hex'));

  if (!ok) {
    await ref.child('attempts').transaction((a) => (a || 0) + 1);
    throw new Denied('permission-denied', 'Wrong code.');
  }

  await ref.remove(); // single use
  return { ok: true };
}

module.exports = { sendOtp, verifyOtp, emailKey };
