import { z } from "zod";
import type { components } from "../api/generated/v2/openapi";
import { isApiId } from "../lib/id";

export type AuthResponse = components["schemas"]["AuthResponse"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type OtpVerifyRequest = components["schemas"]["OtpVerifyRequest"];
export type ChallengeResponse =
  components["schemas"]["ChallengeRequiredResponse"];
const id = z.string().refine(isApiId);
const instant = z.iso.datetime();

// Runtime boundaries mirror the approved v2 snapshot, not legacy token DTOs.
// Object parsing projects known fields only: unexpected secrets are not retained.
export const authSchema: z.ZodType<AuthResponse> = z.object({
  user: z.object({
    userId: id,
    customerId: id.nullable().optional(),
    userType: z.enum(["CUSTOMER", "STAFF"]),
    displayName: z.string(),
    roles: z.array(z.enum(["CUSTOMER", "OPERATIONS", "ADMIN", "AUDITOR"])),
    privileges: z.array(z.enum(["CHECKER"])),
  }),
  session: z.object({
    idleExpiresAt: instant,
    absoluteExpiresAt: instant,
    csrfToken: z.string(),
    deviceTrusted: z.boolean(),
  }),
  correlationId: z.string(),
});
export const loginSchema = z.strictObject({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(128),
  deviceRef: z.uuid().optional(),
  deviceName: z.string().min(1).max(100).optional(),
  deviceFingerprint: z.string().min(1).max(512).optional(),
}) satisfies z.ZodType<LoginRequest>;
export const otpSchema = z.strictObject({
  challengeId: z.uuid(),
  otp: z
    .string()
    .regex(/^[0-9]{6,8}$/)
    .refine((value) => !value.includes("\n")),
}) satisfies z.ZodType<OtpVerifyRequest>;
export const challengeSchema: z.ZodType<ChallengeResponse> = z.object({
  code: z.literal("AUTH_CHALLENGE_REQUIRED"),
  message: z.string(),
  correlationId: z.string(),
  challenge: z.object({
    challengeId: z.uuid(),
    purpose: z.literal("LOGIN_MFA"),
    methods: z.array(z.enum(["OTP", "FIDO", "PASSCODE"])).min(1),
    channel: z.enum(["SMS", "EMAIL", "IN_APP"]).nullable().optional(),
    maskedDestination: z.string().nullable().optional(),
    expiresAt: instant,
    maxAttempts: z.number().int().min(1),
    remainingAttempts: z.number().int().min(0),
    fidoRequestOptions: z.record(z.string(), z.unknown()).nullable().optional(),
  }),
});
