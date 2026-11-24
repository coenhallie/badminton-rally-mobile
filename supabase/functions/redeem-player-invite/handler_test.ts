import {
  type ActiveInviteRecord,
  type InviteDeps,
  handleInviteRequest,
  normalizeInviteCode,
} from "./handler.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`Expected ${e}, got ${a}`);
  }
}

function makeInvite(overrides: Partial<ActiveInviteRecord> = {}): ActiveInviteRecord {
  return {
    id: "inv-1",
    playerId: "player-1",
    coachId: "coach-1",
    playerName: "Marco",
    racketArm: "right",
    coachEmail: "coach@club.nl",
    alreadyLinked: false,
    ...overrides,
  };
}

function makeDeps(overrides: Partial<InviteDeps> = {}): {
  deps: InviteDeps;
  deletedUsers: string[];
  createdUsers: Array<{ email: string; password: string; accountRole: string }>;
  claimedInvites: Array<{ inviteId: string; userId: string }>;
} {
  const deletedUsers: string[] = [];
  const createdUsers: Array<{ email: string; password: string; accountRole: string }> = [];
  const claimedInvites: Array<{ inviteId: string; userId: string }> = [];

  const deps: InviteDeps = {
    checkRateLimit: async () => true,
    findActiveInvite: async (code) => (code === "K7M4QP9R" ? makeInvite() : null),
    createPlayerUser: async (email, password) => {
      createdUsers.push({ email, password, accountRole: "player" });
      return { ok: true, userId: "new-user-1" };
    },
    completeRedemption: async (inviteId, userId) => {
      claimedInvites.push({ inviteId, userId });
      return { ok: true, playerId: "player-1", playerName: "Marco", coachEmail: "coach@club.nl" };
    },
    deleteUser: async (userId) => {
      deletedUsers.push(userId);
    },
    ...overrides,
  };

  return { deps, deletedUsers, createdUsers, claimedInvites };
}

function postJson(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("https://example.supabase.co/functions/v1/redeem-player-invite", {
    method: "POST",
    headers: { "content-type": "application/json", "x-forwarded-for": "203.0.113.10", ...headers },
    body: JSON.stringify(body),
  });
}

Deno.test("normalizeInviteCode strips hyphens/spaces and qr prefix", () => {
  assertEquals(normalizeInviteCode("k7m4-qp9r"), "K7M4QP9R");
  assertEquals(normalizeInviteCode("shuttl-invite:K7M4-QP9R"), "K7M4QP9R");
  assertEquals(normalizeInviteCode("0000-0000"), null); // 0 is excluded from Crockford alphabet
  assertEquals(normalizeInviteCode("SHORT"), null);
});

Deno.test("preview returns player name, racket arm, and coach email without IDs", async () => {
  const { deps } = makeDeps();
  const res = await handleInviteRequest(postJson({ action: "preview", code: "k7m4-qp9r" }), deps);
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body, {
    playerName: "Marco",
    racketArm: "right",
    coachEmail: "coach@club.nl",
  });
});

Deno.test("preview returns identical invalid_or_expired for malformed or unknown codes", async () => {
  const { deps } = makeDeps();
  const r1 = await handleInviteRequest(postJson({ action: "preview", code: "bad" }), deps);
  const r2 = await handleInviteRequest(postJson({ action: "preview", code: "ZZZZZZZZ" }), deps);
  assertEquals(r1.status, 400);
  assertEquals(r2.status, 400);
  assertEquals(await r1.json(), { error: "invalid_or_expired" });
  assertEquals(await r2.json(), { error: "invalid_or_expired" });
});

Deno.test("rate limited requests return 429 before code lookup", async () => {
  let lookedUp = false;
  const { deps } = makeDeps({
    checkRateLimit: async () => false,
    findActiveInvite: async () => {
      lookedUp = true;
      return makeInvite();
    },
  });
  const res = await handleInviteRequest(postJson({ action: "preview", code: "K7M4-QP9R" }), deps);
  assertEquals(res.status, 429);
  assertEquals(await res.json(), { error: "rate_limited" });
  assertEquals(lookedUp, false);
});

Deno.test("redeem creates player account and atomically claims invite", async () => {
  const { deps, createdUsers, claimedInvites, deletedUsers } = makeDeps();
  const res = await handleInviteRequest(
    postJson({
      action: "redeem",
      code: "K7M4-QP9R",
      email: "  Marco@Example.com ",
      password: "strongpassword1",
    }),
    deps,
  );
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    playerId: "player-1",
    playerName: "Marco",
    coachEmail: "coach@club.nl",
  });
  assertEquals(createdUsers.length, 1);
  assertEquals(createdUsers[0].email, "marco@example.com");
  assertEquals(claimedInvites, [{ inviteId: "inv-1", userId: "new-user-1" }]);
  assertEquals(deletedUsers, []);
});

Deno.test("redeem rejects short passwords and invalid emails before creating user", async () => {
  const { deps, createdUsers } = makeDeps();
  const r1 = await handleInviteRequest(
    postJson({ action: "redeem", code: "K7M4-QP9R", email: "marco@example.com", password: "short" }),
    deps,
  );
  assertEquals(r1.status, 400);
  assertEquals(await r1.json(), { error: "weak_password" });

  const r2 = await handleInviteRequest(
    postJson({ action: "redeem", code: "K7M4-QP9R", email: "not-an-email", password: "strongpassword1" }),
    deps,
  );
  assertEquals(r2.status, 400);
  assertEquals(await r2.json(), { error: "invalid_email" });
  assertEquals(createdUsers.length, 0);
});

Deno.test("redeem returns 409 email_exists without consuming invite when email already registered", async () => {
  const { deps, claimedInvites, deletedUsers } = makeDeps({
    createPlayerUser: async () => ({ ok: false, error: "email_exists" }),
  });
  const res = await handleInviteRequest(
    postJson({ action: "redeem", code: "K7M4-QP9R", email: "marco@example.com", password: "strongpassword1" }),
    deps,
  );
  assertEquals(res.status, 409);
  assertEquals(await res.json(), { error: "email_exists" });
  assertEquals(claimedInvites.length, 0);
  assertEquals(deletedUsers.length, 0);
});

Deno.test("redeem rolls back newly created auth user when concurrent claim fails", async () => {
  const { deps, deletedUsers } = makeDeps({
    completeRedemption: async () => ({ ok: false, error: "invalid_or_expired" }),
  });
  const res = await handleInviteRequest(
    postJson({ action: "redeem", code: "K7M4-QP9R", email: "marco@example.com", password: "strongpassword1" }),
    deps,
  );
  assertEquals(res.status, 400);
  assertEquals(await res.json(), { error: "invalid_or_expired" });
  assertEquals(deletedUsers, ["new-user-1"]);
});

Deno.test("redeem returns 409 already_linked if player already linked before user creation", async () => {
  const { deps, createdUsers } = makeDeps({
    findActiveInvite: async () => makeInvite({ alreadyLinked: true }),
  });
  const res = await handleInviteRequest(
    postJson({ action: "redeem", code: "K7M4-QP9R", email: "marco@example.com", password: "strongpassword1" }),
    deps,
  );
  assertEquals(res.status, 409);
  assertEquals(await res.json(), { error: "already_linked" });
  assertEquals(createdUsers.length, 0);
});
