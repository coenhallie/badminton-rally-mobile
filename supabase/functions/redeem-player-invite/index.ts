import { createClient } from "npm:@supabase/supabase-js@2";
import {
  type ActiveInviteRecord,
  type CompleteRedemptionResult,
  type CreateUserResult,
  type InviteDeps,
  handleInviteRequest,
} from "./handler.ts";

function buildDeps(): InviteDeps {
  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) {
    throw new Error("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required");
  }

  const admin = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  return {
    async checkRateLimit(bucketKey, maxAttempts, windowSeconds) {
      const { data, error } = await admin.rpc("check_invite_rate_limit", {
        p_bucket_key: bucketKey,
        p_max_attempts: maxAttempts,
        p_window_seconds: windowSeconds,
      });
      if (error) {
        console.error("rate limit check failed:", error.message);
        return false;
      }
      return data === true;
    },

    async findActiveInvite(normalizedCode): Promise<ActiveInviteRecord | null> {
      const nowIso = new Date().toISOString();
      const { data: invite, error } = await admin
        .from("player_invites")
        .select("id, player_id, coach_id, expires_at")
        .eq("code", normalizedCode)
        .is("redeemed_at", null)
        .is("revoked_at", null)
        .gt("expires_at", nowIso)
        .maybeSingle();

      if (error || !invite) return null;

      const [{ data: player }, { data: existingLogin }, { data: coachData }] = await Promise.all([
        admin.from("players").select("name, racket_arm").eq("id", invite.player_id).maybeSingle(),
        admin.from("player_logins").select("player_id").eq("player_id", invite.player_id).maybeSingle(),
        admin.auth.admin.getUserById(invite.coach_id),
      ]);

      if (!player) return null;

      return {
        id: invite.id,
        playerId: invite.player_id,
        coachId: invite.coach_id,
        playerName: player.name,
        racketArm: player.racket_arm === "left" || player.racket_arm === "right" ? player.racket_arm : null,
        coachEmail: coachData?.user?.email ?? "",
        alreadyLinked: existingLogin !== null,
      };
    },

    async createPlayerUser(email, password): Promise<CreateUserResult> {
      const { data, error } = await admin.auth.admin.createUser({
        email,
        password,
        email_confirm: true,
        app_metadata: { account_role: "player" },
      });
      if (error || !data.user) {
        const msg = (error?.message ?? "").toLowerCase();
        const code = (error?.code ?? "").toLowerCase();
        if (code === "email_exists" || msg.includes("already been registered") || msg.includes("already exists")) {
          return { ok: false, error: "email_exists" };
        }
        if (msg.includes("password")) {
          return { ok: false, error: "weak_password" };
        }
        if (msg.includes("email")) {
          return { ok: false, error: "invalid_email" };
        }
        return { ok: false, error: "unknown" };
      }
      return { ok: true, userId: data.user.id };
    },

    async completeRedemption(inviteId, userId): Promise<CompleteRedemptionResult> {
      const { data, error } = await admin.rpc("complete_player_invite_redemption", {
        p_invite_id: inviteId,
        p_user_id: userId,
      });
      if (error) {
        const msg = (error.message ?? "").toLowerCase();
        if (msg.includes("already_linked") || error.code === "P0005") {
          return { ok: false, error: "already_linked" };
        }
        if (msg.includes("cannot_invite_self") || error.code === "P0004") {
          return { ok: false, error: "cannot_invite_self" };
        }
        return { ok: false, error: "invalid_or_expired" };
      }
      const row = Array.isArray(data) ? data[0] : data;
      if (!row) return { ok: false, error: "invalid_or_expired" };
      return {
        ok: true,
        playerId: row.player_id,
        playerName: row.player_name,
        coachEmail: row.coach_email ?? "",
      };
    },

    async deleteUser(userId) {
      await admin.auth.admin.deleteUser(userId);
    },
  };
}

Deno.serve((req) => handleInviteRequest(req, buildDeps()));
