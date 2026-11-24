const INVITE_CODE_RE = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$/;
const QR_PREFIX = "shuttl-invite:";
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;

const CORS_HEADERS: Record<string, string> = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
};

export interface ActiveInviteRecord {
  id: string;
  playerId: string;
  coachId: string;
  playerName: string;
  racketArm: "left" | "right" | null;
  coachEmail: string;
  alreadyLinked: boolean;
}

export type CreateUserResult =
  | { ok: true; userId: string }
  | { ok: false; error: "email_exists" | "weak_password" | "invalid_email" | "unknown" };

export type CompleteRedemptionResult =
  | { ok: true; playerId: string; playerName: string; coachEmail: string }
  | { ok: false; error: "invalid_or_expired" | "already_linked" | "cannot_invite_self" };

export interface InviteDeps {
  checkRateLimit(bucketKey: string, maxAttempts: number, windowSeconds: number): Promise<boolean>;
  findActiveInvite(normalizedCode: string): Promise<ActiveInviteRecord | null>;
  createPlayerUser(email: string, password: string): Promise<CreateUserResult>;
  completeRedemption(inviteId: string, userId: string): Promise<CompleteRedemptionResult>;
  deleteUser(userId: string): Promise<void>;
}

/**
 * Strips whitespace, hyphens, and optional `shuttl-invite:` prefix, uppercases,
 * and checks the 8-character Crockford alphabet. Returns null when invalid.
 */
export function normalizeInviteCode(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  let trimmed = raw.trim();
  if (trimmed.toLowerCase().startsWith(QR_PREFIX)) {
    trimmed = trimmed.slice(QR_PREFIX.length);
  }
  const cleaned = trimmed.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
  return INVITE_CODE_RE.test(cleaned) ? cleaned : null;
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...CORS_HEADERS,
      "content-type": "application/json; charset=utf-8",
    },
  });
}

async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function clientIp(req: Request): string {
  const cf = req.headers.get("cf-connecting-ip");
  if (cf && cf.trim().length > 0) return cf.trim();
  const xff = req.headers.get("x-forwarded-for");
  if (xff && xff.trim().length > 0) return xff.split(",")[0].trim();
  return "unknown";
}

export async function handleInviteRequest(req: Request, deps: InviteDeps): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { status: 200, headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return jsonResponse(405, { error: "method_not_allowed" });
  }

  let payload: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return jsonResponse(400, { error: "invalid_request" });
    }
    payload = parsed as Record<string, unknown>;
  } catch {
    return jsonResponse(400, { error: "invalid_request" });
  }

  const action = payload.action;
  if (action !== "preview" && action !== "redeem") {
    return jsonResponse(400, { error: "invalid_request" });
  }

  const bucketKey = `ip:${await sha256Hex(clientIp(req))}`;
  const allowed = await deps.checkRateLimit(bucketKey, 20, 900);
  if (!allowed) {
    return jsonResponse(429, { error: "rate_limited" });
  }

  const normalizedCode = normalizeInviteCode(payload.code);
  if (!normalizedCode) {
    return jsonResponse(400, { error: "invalid_or_expired" });
  }

  const invite = await deps.findActiveInvite(normalizedCode);
  if (!invite) {
    return jsonResponse(400, { error: "invalid_or_expired" });
  }
  if (invite.alreadyLinked) {
    return jsonResponse(409, { error: "already_linked" });
  }

  if (action === "preview") {
    return jsonResponse(200, {
      playerName: invite.playerName,
      racketArm: invite.racketArm,
      coachEmail: invite.coachEmail,
    });
  }

  // action === "redeem"
  const rawEmail = typeof payload.email === "string" ? payload.email.trim().toLowerCase() : "";
  const password = typeof payload.password === "string" ? payload.password : "";

  if (!EMAIL_RE.test(rawEmail)) {
    return jsonResponse(400, { error: "invalid_email" });
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    return jsonResponse(400, { error: "weak_password" });
  }

  const created = await deps.createPlayerUser(rawEmail, password);
  if (!created.ok) {
    if (created.error === "email_exists") {
      return jsonResponse(409, { error: "email_exists" });
    }
    if (created.error === "weak_password" || created.error === "invalid_email") {
      return jsonResponse(400, { error: created.error });
    }
    return jsonResponse(500, { error: "user_creation_failed" });
  }

  const claimed = await deps.completeRedemption(invite.id, created.userId);
  if (!claimed.ok) {
    // Roll back newly created auth user so no orphan account is left behind.
    try {
      await deps.deleteUser(created.userId);
    } catch {
      // Best-effort cleanup; surface the redemption error to the caller.
    }
    const status = claimed.error === "already_linked" ? 409 : 400;
    return jsonResponse(status, { error: claimed.error });
  }

  return jsonResponse(200, {
    playerId: claimed.playerId,
    playerName: claimed.playerName,
    coachEmail: claimed.coachEmail,
  });
}
