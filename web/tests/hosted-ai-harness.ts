/**
 * Shared test doubles for the hosted AI Worker: an in-memory ledger and a
 * fetch mock for RevenueCat / Gemini / Deepgram.
 */

import { vi } from "vitest";
import { handleHostedAIRequest, type HostedAIDependencies } from "../hosted-ai-api";
import type { EntitlementSnapshot, LedgerRow, LedgerStore, SpendReceipt } from "../hosted-ai-ledger";

export const USER_ID = "$RCAnonymousID:0123456789abcdef0123456789abcdef";
export const BASE = "https://fud-ai.app/api/hosted-ai/v1";

export class MemoryLedgerStore implements LedgerStore {
  rows = new Map<string, LedgerRow>();
  spendAttempts = 0;
  /** When set, the next `trySpend` call fails once to simulate a concurrent writer. */
  conflictOnce = false;
  /** Number of `refund` calls that should throw before one succeeds. */
  refundFailures = 0;
  /**
   * Number of `refund` calls that should apply the write and *then* throw,
   * simulating D1 committing the transaction but reporting an error.
   */
  refundCommitThenFail = 0;
  refundAttempts = 0;
  /** Receipt ids that have already been refunded (mirrors `hosted_ai_refunds`). */
  refunded = new Set<string>();

  async get(userIdHash: string): Promise<LedgerRow | null> {
    const row = this.rows.get(userIdHash);
    return row ? { ...row } : null;
  }

  async upsertEntitlement(userIdHash: string, snapshot: EntitlementSnapshot, verifiedAt: string): Promise<LedgerRow> {
    const existing = this.rows.get(userIdHash);
    const newer = existing === undefined || verifiedAt >= existing.entitlement_verified_at;
    const row: LedgerRow = existing
      ? {
          ...existing,
          plan: newer ? snapshot.plan : existing.plan,
          credits_granted: Math.max(existing.credits_granted, snapshot.creditsGranted),
          entitlement_verified_at: newer ? verifiedAt : existing.entitlement_verified_at,
          version: existing.version + 1,
        }
      : {
          user_id_hash: userIdHash,
          plan: snapshot.plan,
          credits_granted: snapshot.creditsGranted,
          credits_spent: 0,
          usage_day: verifiedAt.slice(0, 10),
          daily_used: 0,
          entitlement_verified_at: verifiedAt,
          version: 0,
        };
    this.rows.set(userIdHash, row);
    return { ...row };
  }

  async trySpend(
    userIdHash: string,
    expectedVersion: number,
    next: { usageDay: string; dailyUsed: number; creditsSpent: number }
  ): Promise<boolean> {
    this.spendAttempts += 1;
    const row = this.rows.get(userIdHash);
    if (!row || row.version !== expectedVersion) return false;
    if (this.conflictOnce) {
      this.conflictOnce = false;
      row.version += 1;
      return false;
    }
    row.usage_day = next.usageDay;
    row.daily_used = next.dailyUsed;
    row.credits_spent = next.creditsSpent;
    row.version += 1;
    return true;
  }

  async refund(userIdHash: string, receipt: SpendReceipt): Promise<void> {
    this.refundAttempts += 1;
    if (this.refundFailures > 0) {
      this.refundFailures -= 1;
      throw new Error("D1_ERROR: transient");
    }
    const row = this.rows.get(userIdHash);
    if (row && !this.refunded.has(receipt.id)) {
      if (row.usage_day === receipt.day) row.daily_used = Math.max(0, row.daily_used - receipt.fromDaily);
      row.credits_spent = Math.max(0, row.credits_spent - receipt.fromCredits);
      row.version += 1;
    }
    this.refunded.add(receipt.id);
    if (this.refundCommitThenFail > 0) {
      this.refundCommitThenFail -= 1;
      throw new Error("D1_ERROR: network error after commit");
    }
  }

  only(): LedgerRow {
    const [row] = this.rows.values();
    if (!row) throw new Error("no ledger row");
    return row;
  }
}

export interface Harness {
  env: Parameters<typeof handleHostedAIRequest>[1];
  deps: HostedAIDependencies;
  store: MemoryLedgerStore;
  calls: Array<{ url: string; init: RequestInit | undefined }>;
  revenueCat: { plan: "none" | "plus" | "pro"; credits?: Array<{ product: string; id: string }>; status?: number };
  gemini: { status?: number; body?: unknown; throwName?: string };
  deepgram: { status?: number; body?: unknown };
  rateLimit: { user: boolean; address: boolean };
  clock: { now: Date };
  /** Promises handed to `waitUntil` (refund retries). */
  background: Promise<unknown>[];
}

function subscriberPayload(harness: Harness): unknown {
  const expires = new Date(harness.clock.now.getTime() + 86_400_000).toISOString();
  const entitlements: Record<string, unknown> = {};
  if (harness.revenueCat.plan === "plus") entitlements.plus = { expires_date: expires };
  if (harness.revenueCat.plan === "pro") {
    entitlements.pro = { expires_date: expires };
    entitlements.plus = { expires_date: expires };
  }
  const nonSubscriptions: Record<string, Array<{ id: string }>> = {};
  for (const credit of harness.revenueCat.credits ?? []) {
    (nonSubscriptions[credit.product] ??= []).push({ id: credit.id });
  }
  return { subscriber: { entitlements, non_subscriptions: nonSubscriptions } };
}

export function createHarness(overrides: Partial<Pick<Harness, "revenueCat" | "gemini" | "deepgram">> = {}): Harness {
  const store = new MemoryLedgerStore();
  const harness: Harness = {
    store,
    calls: [],
    revenueCat: overrides.revenueCat ?? { plan: "plus" },
    gemini: overrides.gemini ?? {
      body: { candidates: [{ content: { parts: [{ text: "ok" }] } }] },
    },
    deepgram: overrides.deepgram ?? {
      body: { results: { channels: [{ alternatives: [{ transcript: "two eggs" }] }] } },
    },
    rateLimit: { user: true, address: true },
    clock: { now: new Date("2026-09-13T01:00:00.000Z") },
    env: undefined as unknown as Harness["env"],
    deps: {},
    background: [],
  };

  const fetchImpl: typeof fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    harness.calls.push({ url, init });
    if (url.startsWith("https://api.revenuecat.com/")) {
      const status = harness.revenueCat.status ?? 200;
      return new Response(status === 200 ? JSON.stringify(subscriberPayload(harness)) : "{}", { status });
    }
    if (url.startsWith("https://generativelanguage.googleapis.com/")) {
      if (harness.gemini.throwName) {
        const error = new Error("upstream fetch failed");
        error.name = harness.gemini.throwName;
        throw error;
      }
      return new Response(JSON.stringify(harness.gemini.body ?? {}), { status: harness.gemini.status ?? 200 });
    }
    if (url.startsWith("https://api.deepgram.com/")) {
      return new Response(JSON.stringify(harness.deepgram.body ?? {}), { status: harness.deepgram.status ?? 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  harness.env = {
    GEMINI_API_KEY: "gemini-key",
    DEEPGRAM_API_KEY: "dg-key",
    REVENUECAT_API_KEY: "sk_test",
    CHALLENGE_DB: {
      withSession: () => {
        throw new Error("D1 must not be used when a store is injected");
      },
    } as unknown as D1Database,
    HOSTED_AI_USER_RATE_LIMITER: { limit: async () => ({ success: harness.rateLimit.user }) },
    HOSTED_AI_ADDRESS_RATE_LIMITER: { limit: async () => ({ success: harness.rateLimit.address }) },
  };
  harness.deps = {
    fetch: fetchImpl,
    store,
    now: () => harness.clock.now,
    sleep: async () => {},
    waitUntil: (promise) => {
      harness.background.push(promise);
    },
  };
  return harness;
}

export function captureLogs(): { lines: string[]; restore: () => void } {
  const lines: string[] = [];
  const spy = vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    lines.push(args.map(String).join(" "));
  });
  return { lines, restore: () => spy.mockRestore() };
}

export async function call(harness: Harness, request: Request): Promise<Response> {
  return handleHostedAIRequest(request, harness.env, harness.deps);
}
