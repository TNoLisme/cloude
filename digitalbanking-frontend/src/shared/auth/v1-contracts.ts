import { z } from "zod";
import type { components } from "../api/generated/openapi";
import { isApiId } from "../lib/id";

export type V1AuthResponse = components["schemas"]["AuthResponse"];
export type V1ChallengeResponse =
  components["schemas"]["ChallengeRequiredResponse"];

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });

// Project only the fields declared in BE OpenAPI v1.1.0. Never retain a token
// or an unexpected property from a response in the session snapshot.
export const v1AuthSchema: z.ZodType<V1AuthResponse> = z.object({
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
    csrfToken: z.string().min(1),
    deviceTrusted: z.boolean(),
  }),
  correlationId: z.string(),
});

export const v1ChallengeSchema: z.ZodType<V1ChallengeResponse> = z.object({
  code: z.literal("AUTH_CHALLENGE_REQUIRED"),
  message: z.string(),
  correlationId: z.string(),
  challenge: z.object({
    challengeId: z.uuid(),
    purpose: z.literal("LOGIN_NEW_DEVICE"),
    methods: z.array(z.enum(["OTP", "FIDO", "PASSCODE"])).min(1),
    channel: z.enum(["SMS", "EMAIL", "IN_APP"]).nullable().optional(),
    maskedDestination: z.string().nullable().optional(),
    expiresAt: instant,
    maxAttempts: z.number().int().min(1),
    remainingAttempts: z.number().int().min(0),
    fidoRequestOptions: z
      .object({
        challenge: z.string(),
        rpId: z.string(),
        allowCredentials: z.array(
          z.object({
            type: z.literal("public-key"),
            id: z.string(),
            transports: z.array(z.string()).optional(),
          }),
        ),
        timeout: z.number(),
        userVerification: z.literal("required"),
      })
      .nullable()
      .optional(),
  }),
});
