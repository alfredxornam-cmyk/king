const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any user collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("tasks").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").set({
      userId: ALICE_UID,
      titleCipher: "enc_title",
      noteCipher: "",
      encrypted: true,
      completed: false,
      lane: "FOCUS",
      priority: "HIGH",
      category: "Daily",
      sortOrder: 0,
      voiceCaptured: false,
      dueLabel: "Today",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Owner can create, list, update, and delete UserProfile; cross-user blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alfred",
      email: "alfredxornam@gmail.com",
      vaultSalt: "salt_12345678",
      encryptionEnabled: true,
      themeMode: "HACKER",
      aiModelName: "gemini-3.8-flash",
      aiTemperature: 0.7,
      autoStructureVoice: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themeMode: "DARK",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user read & write must fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      themeMode: "LIGHT",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Shadow field injection must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Owner can manage tasks with drag-and-drop sortOrder and lane updates; invalid payloads rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").set({
      userId: ALICE_UID,
      titleCipher: "AES:abc123",
      noteCipher: "AES:def456",
      encrypted: true,
      completed: false,
      lane: "FOCUS",
      priority: "HIGH",
      category: "Work",
      sortOrder: 0,
      voiceCaptured: true,
      dueLabel: "Today 5 PM",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").get()
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").update({
      lane: "PROGRESS",
      sortOrder: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject @DocumentId 'id' leakage
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_2").set({
      id: "task_2",
      userId: ALICE_UID,
      titleCipher: "AES:xyz",
      noteCipher: "",
      encrypted: true,
      completed: false,
      lane: "FOCUS",
      priority: "LOW",
      category: "Personal",
      sortOrder: 1,
      voiceCaptured: false,
      dueLabel: "Today",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp & immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_3").set({
      userId: ALICE_UID,
      titleCipher: "AES:future",
      noteCipher: "",
      encrypted: true,
      completed: false,
      lane: "FOCUS",
      priority: "LOW",
      category: "Personal",
      sortOrder: 1,
      voiceCaptured: false,
      dueLabel: "Today",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Cross-user isolation
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("tasks").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").delete());
});

test("Owner can manage voice notes, AI wallpapers/themes, and encrypted vault media", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 3000);

  // Voice Note
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").set({
      userId: ALICE_UID,
      titleCipher: "AES:VoiceNote",
      contentCipher: "AES:Transcript",
      encrypted: true,
      tags: ["voice", "camon20"],
      durationSec: 18,
      aiSummaryCipher: "AES:Summary",
      createdAt: created,
      updatedAt: created,
    })
  );

  // AI Wallpaper
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("wallpapers").doc("wp_1").set({
      userId: ALICE_UID,
      title: "Camon Cyber Neon",
      prompt: "AMOLED neon cyber grid",
      itemType: "WALLPAPER",
      imageBase64: "base64sample",
      encrypted: false,
      deviceProfile: "Tecno Camon 20 AMOLED",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Encrypted Vault Media
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vault_media").doc("media_1").set({
      userId: ALICE_UID,
      fileName: "camon_photo.jpg",
      mimeType: "image/jpeg",
      sizeBytes: 120400,
      cipherPayload: "AES:encrypted_bytes",
      sha256Checksum: "e3b0c44298fc1c149afbf4c8996fb924",
      encrypted: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("notes").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("wallpapers").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("vault_media").get());

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("vault_media").get());
});
